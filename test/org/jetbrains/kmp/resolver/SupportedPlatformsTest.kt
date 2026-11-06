package org.jetbrains.kmp.resolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class SupportedPlatformsTest {
    @Test
    fun `platforms are listed klib platforms first, then android, then jvm`() {
        assertEquals(
            listOf(
                "wasmjs",
                "ios_arm64",
                "ios_simulator_arm64",
                "linux_arm64",
                "linux_x64",
                "macos_arm64",
                "android",
                "jvm",
            ),
            SUPPORTED_PLATFORMS.map { it.cliName },
        )
    }

    @Test
    fun `native platforms are named after the konan targets of rules_kotlin`() {
        val nativePlatforms = KlibPlatform.entries.filter { it.konanTarget != null }

        // the keys of `KONAN_TARGETS` in `kotlin/internal/native/targets.bzl` of rules_kotlin
        assertEquals(
            listOf("ios_arm64", "ios_simulator_arm64", "linux_arm64", "linux_x64", "macos_arm64"),
            nativePlatforms.map { it.manifestName },
        )
        nativePlatforms.forEach { platform ->
            assertEquals(platform.konanTarget, platform.manifestName)
            assertEquals(platform.manifestName, ManifestPlatform.Klib(platform).cliName)
        }
    }

    @Test
    fun `wasm klibs are named after the KtKlibInfo platform`() {
        assertEquals("wasm_js", KlibPlatform.WASM_JS.manifestName)
        assertNull(KlibPlatform.WASM_JS.konanTarget)
        assertEquals("wasmjs", ManifestPlatform.WASM_JS.cliName)
    }

    @Test
    fun `klib platforms are serialized by their manifest name`() {
        assertEquals(
            "\"linux_x64\"",
            Json.encodeToString(KlibPlatformSerializer, KlibPlatform.LINUX_X64),
        )
        assertEquals(
            KlibPlatform.IOS_SIMULATOR_ARM64,
            Json.decodeFromString(KlibPlatformSerializer, "\"ios_simulator_arm64\""),
        )
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString(KlibPlatformSerializer, "\"macos_x64\"")
        }
    }
}
