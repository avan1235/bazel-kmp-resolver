package org.jetbrains.kmp.resolver

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform

/**
 * Kotlin platform the klibs of a [MultiplatformVariant.Klib] are compiled for.
 *
 * [manifestName] is the name rules_kotlin gives the platform, so that the Bazel side needs no
 * mapping table: the konan target as keyed in `KONAN_TARGETS`
 * (`kotlin/internal/native/targets.bzl`) for a native platform, and the spelling of
 * `KtKlibInfo.platform` otherwise. Entries are declared in the order their variants are listed in
 * the manifest: `wasm_js`, then the konan targets in alphabetical order.
 */
@Serializable(with = KlibPlatformSerializer::class)
internal enum class KlibPlatform(
    val manifestName: String,
    val resolutionPlatform: ResolutionPlatform,
) {
    WASM_JS("wasm_js", ResolutionPlatform.WASM_JS),
    IOS_ARM64("ios_arm64", ResolutionPlatform.IOS_ARM64),
    IOS_SIMULATOR_ARM64("ios_simulator_arm64", ResolutionPlatform.IOS_SIMULATOR_ARM64),
    LINUX_ARM64("linux_arm64", ResolutionPlatform.LINUX_ARM64),
    LINUX_X64("linux_x64", ResolutionPlatform.LINUX_X64),
    MACOS_ARM64("macos_arm64", ResolutionPlatform.MACOS_ARM64);

    /** The konan target of a native platform, `null` for another platform. */
    val konanTarget: String?
        get() = resolutionPlatform.nativeTarget

    companion object {
        fun fromManifestName(manifestName: String): KlibPlatform =
            entries.firstOrNull { it.manifestName == manifestName }
                ?: throw IllegalArgumentException(
                    "Unknown klib platform $manifestName, known platforms: ${entries.joinToString { it.manifestName }}",
                )
    }
}

internal object KlibPlatformSerializer : KSerializer<KlibPlatform> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("KlibPlatform", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: KlibPlatform) =
        encoder.encodeString(value.manifestName)

    override fun deserialize(decoder: Decoder): KlibPlatform =
        KlibPlatform.fromManifestName(decoder.decodeString())
}

/**
 * A platform the dependency graph is resolved for, by an Amper resolution of its own, producing the
 * variants of one kind: `klib` variants for a [Klib] platform, `android` variants for [Android] and
 * `jvm` variants for [Jvm].
 */
internal sealed interface ManifestPlatform {
    /** Name of the platform on the command line, as a value of `--platform`. */
    val cliName: String

    /** The platform Amper resolves the dependency graph for. */
    val resolutionPlatform: ResolutionPlatform

    data class Klib(val klibPlatform: KlibPlatform) : ManifestPlatform {
        // `wasmjs` predates the platforms named after rules_kotlin, it is kept for existing
        // invocations
        override val cliName: String =
            when (klibPlatform) {
                KlibPlatform.WASM_JS -> "wasmjs"
                else -> klibPlatform.manifestName
            }

        override val resolutionPlatform: ResolutionPlatform = klibPlatform.resolutionPlatform
    }

    data object Android : ManifestPlatform {
        override val cliName: String = "android"
        override val resolutionPlatform: ResolutionPlatform = ResolutionPlatform.ANDROID
    }

    data object Jvm : ManifestPlatform {
        override val cliName: String = "jvm"
        override val resolutionPlatform: ResolutionPlatform = ResolutionPlatform.JVM
    }

    companion object {
        val WASM_JS: ManifestPlatform = Klib(KlibPlatform.WASM_JS)
    }
}

/**
 * Platforms the resolver knows how to collect libraries for, in the order their variants are listed
 * in the manifest: the klib platforms first, in the order of [KlibPlatform], then `android`, then
 * `jvm`.
 */
internal val SUPPORTED_PLATFORMS: List<ManifestPlatform> =
    KlibPlatform.entries.map { ManifestPlatform.Klib(it) } +
        ManifestPlatform.Android +
        ManifestPlatform.Jvm
