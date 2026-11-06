package org.jetbrains.kmp.resolver

import java.net.http.HttpClient
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.path.absolutePathString
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jetbrains.amper.dependency.resolution.*
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.dependency.resolution.diagnostics.PlatformsAreNotSupported
import org.jetbrains.amper.dependency.resolution.diagnostics.Severity
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Substitution ID represents a groupId:artifactId string */
internal typealias SubstitutionId = String

internal typealias Substitutions = Map<SubstitutionId, MultiplatformLibraryId>

/**
 * The KMP library (umbrella) a Maven module (`group:artifact`) is a platform-specific variant of,
 * for the modules of a platform's dependency graph that are such variants.
 */
internal typealias KmpParents = Map<SubstitutionId, MultiplatformLibraryId>

@Serializable(with = MultiplatformLibraryIdSerializer::class)
internal data class MultiplatformLibraryId(
    val groupId: String,
    val artifactId: String,
    val version: String,
) : Comparable<MultiplatformLibraryId> {
    val gav: String = "${groupId}:${artifactId}:${version}"

    override fun compareTo(other: MultiplatformLibraryId): Int = gav.compareTo(other.gav)

    companion object {
        fun fromString(s: String): MultiplatformLibraryId {
            val (groupId, artifactId, version) = s.split(":", limit = 3)
            return MultiplatformLibraryId(groupId, artifactId, version)
        }
    }
}

internal object MultiplatformLibraryIdSerializer : KSerializer<MultiplatformLibraryId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("MultiplatformLibraryId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: MultiplatformLibraryId) =
        encoder.encodeString(value.gav)

    override fun deserialize(decoder: Decoder): MultiplatformLibraryId =
        MultiplatformLibraryId.fromString(decoder.decodeString())
}

@Serializable
internal data class MultiplatformLibrary(
    val id: MultiplatformLibraryId,
    val variants: List<MultiplatformVariant>,
)

@Serializable
internal sealed class MultiplatformVariant {
    /**
     * ID of the parent KMP umbrella library from which this variant is from.
     *
     * For a library that is not a variant of a KMP library (a JVM-only library, or a KMP library
     * publishing its JVM artifact in the umbrella module itself like `kotlin-stdlib`), this is the
     * same as [variantId].
     */
    abstract val id: MultiplatformLibraryId

    /**
     * ID of the resolved multiplatform library, usually [id] would point to the umbrella library
     * like `kotlin-stdlib`, while [variantId] would point to the variant library like
     * `kotlin-stdlib-wasm-js`.
     */
    abstract val variantId: MultiplatformLibraryId

    /** Dependencies of this library, exposed to the link path of dependents transitively. */
    abstract val dependencies: List<SubstitutionId>

    /**
     * Dependencies of this library, exposed to the link path of dependents transitively, and
     * exposed to the compile library path of *direct* dependents.
     */
    abstract val exportedDependencies: List<SubstitutionId>

    @Serializable
    @SerialName("klib")
    data class Klib(
        /** Kotlin platform of the klibs: a konan target like `linux_x64`, or `wasm_js`. */
        val platform: KlibPlatform,
        override val id: MultiplatformLibraryId,
        override val variantId: MultiplatformLibraryId,
        override val dependencies: List<SubstitutionId>,
        override val exportedDependencies: List<SubstitutionId>,

        /**
         * .klib files of this imported dependency, exposed to the compile library path of direct
         * dependents: its [KlibRole.MAIN] klib first, then the [KlibRole.CINTEROP] klibs of the C
         * libraries it binds, sorted by file name.
         */
        val klibs: List<KlibArtifact>,
        val sourceJar: MavenMultiplatformLibraryArtifact?,

        /**
         * NPM packages required at runtime by this library, declared by the `package.json` embedded
         * in its klib, listed with their transitive NPM dependencies; only `wasm_js` klibs declare
         * some.
         */
        val npmPackages: List<NpmMultiplatformLibraryArtifact> = emptyList(),
    ) : MultiplatformVariant()

    @Serializable
    @SerialName("android")
    data class Android(
        override val id: MultiplatformLibraryId,
        override val variantId: MultiplatformLibraryId,
        override val dependencies: List<SubstitutionId>,
        override val exportedDependencies: List<SubstitutionId>,

        /**
         * .aar or .jar of this imported dependency, as told by [packaging], exposed to the compile
         * and runtime class paths of dependents.
         */
        val artifact: MavenMultiplatformLibraryArtifact,
        val packaging: AndroidPackaging,
        val sourceJar: MavenMultiplatformLibraryArtifact?,
    ) : MultiplatformVariant()

    @Serializable
    @SerialName("jvm")
    data class Jvm(
        override val id: MultiplatformLibraryId,
        override val variantId: MultiplatformLibraryId,
        override val dependencies: List<SubstitutionId>,
        override val exportedDependencies: List<SubstitutionId>,

        /**
         * .jar of this imported dependency, exposed to the compile and runtime class paths of
         * dependents.
         */
        val jar: MavenMultiplatformLibraryArtifact,
        val sourceJar: MavenMultiplatformLibraryArtifact?,
    ) : MultiplatformVariant()
}

/** Name of the platform of this variant in the manifest. */
internal val MultiplatformVariant.platformName: String
    get() =
        when (this) {
            is MultiplatformVariant.Klib -> platform.manifestName
            is MultiplatformVariant.Android -> ManifestPlatform.Android.cliName
            is MultiplatformVariant.Jvm -> ManifestPlatform.Jvm.cliName
        }

/** A klib of a [MultiplatformVariant.Klib], with the role it plays in the variant. */
@Serializable
internal data class KlibArtifact(
    val role: KlibRole,
    val artifact: MavenMultiplatformLibraryArtifact,
)

@Serializable
internal enum class KlibRole {
    /** The klib of the library itself, of which a variant has exactly one. */
    @SerialName("main") MAIN,

    /** A klib binding a C library the library uses, produced by cinterop. */
    @SerialName("cinterop") CINTEROP,
}

/** Packaging of the artifact of a [MultiplatformVariant.Android]. */
@Serializable
internal enum class AndroidPackaging {
    /** An Android library. */
    @SerialName("aar") AAR,

    /**
     * A JVM library, or the JVM variant of a KMP library publishing no Android variant, which
     * Android resolutions fall back to.
     */
    @SerialName("jar") JAR,
}

@Serializable
internal sealed class MultiplatformLibraryArtifact {
    abstract val integrity: MultiplatformLibraryArtifactIntegrity
}

@Serializable
@SerialName("maven")
internal data class MavenMultiplatformLibraryArtifact(
    override val integrity: MultiplatformLibraryArtifactIntegrity,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val urls: List<String>,
) : MultiplatformLibraryArtifact()

@Serializable
@SerialName("npm")
internal data class NpmMultiplatformLibraryArtifact(
    override val integrity: MultiplatformLibraryArtifactIntegrity,
    val name: String,
    val version: String,
    val url: String,
) : MultiplatformLibraryArtifact()

@Serializable(with = MultiplatformLibraryArtifactIntegritySerializer::class)
internal sealed class MultiplatformLibraryArtifactIntegrity {
    abstract val hash: String
    abstract val algorithm: String

    data class Sha256(override val hash: String) : MultiplatformLibraryArtifactIntegrity() {
        override val algorithm: String = "sha256"
    }

    /**
     * Fallback for repositories publishing neither a sha512 nor a sha256 checksum: Maven Central
     * only publishes sha1 and md5 checksums for artifacts deployed without Gradle module metadata,
     * i.e. most JVM-only libraries.
     */
    data class Sha1(override val hash: String) : MultiplatformLibraryArtifactIntegrity() {
        override val algorithm: String = "sha1"
    }

    data class Sha512(override val hash: String) : MultiplatformLibraryArtifactIntegrity() {
        override val algorithm: String = "sha512"
    }

    fun asBazelIntegrityString(): String {
        val base64Hash = Base64.encode(hash.hexToBytes())
        return "$algorithm-$base64Hash"
    }

    companion object {
        fun fromBazelIntegrityString(
            bazelIntegrity: String,
        ): MultiplatformLibraryArtifactIntegrity {
            val (algorithm, base64Hash) = bazelIntegrity.split("-", limit = 2)
            val hash = Base64.decode(base64Hash).toHexString()
            return fromHash(algorithm, hash)
        }

        fun fromHash(algorithm: String, hash: String): MultiplatformLibraryArtifactIntegrity =
            when (algorithm) {
                "sha1" -> Sha1(hash)
                "sha256" -> Sha256(hash)
                "sha512" -> Sha512(hash)
                else -> throw IllegalArgumentException("Unknown hash algorithm: $algorithm")
            }
    }
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hash must have an even number of hexadecimal characters: $this" }
    return ByteArray(length / 2) { index ->
        val high = this[index * 2].hexDigitToInt()
        val low = this[index * 2 + 1].hexDigitToInt()
        ((high shl 4) or low).toByte()
    }
}

private fun ByteArray.toHexString(): String =
    buildString(size * 2) {
        for (byte in this@toHexString) {
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }

private fun Char.hexDigitToInt(): Int =
    when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> throw IllegalArgumentException("Invalid hexadecimal digit: $this")
    }

internal class MultiplatformLibraryArtifactIntegritySerializer :
    KSerializer<MultiplatformLibraryArtifactIntegrity> {
    override val descriptor: SerialDescriptor
        get() =
            PrimitiveSerialDescriptor("MultiplatformLibraryArtifactIntegrity", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: MultiplatformLibraryArtifactIntegrity): Unit =
        encoder.encodeString(value.asBazelIntegrityString())

    override fun deserialize(decoder: Decoder): MultiplatformLibraryArtifactIntegrity {
        val bazelIntegrity = decoder.decodeString()
        return MultiplatformLibraryArtifactIntegrity.fromBazelIntegrityString(bazelIntegrity)
    }
}

internal class MultiplatformResolver(
    cachePath: Path,
    private val platforms: Set<ManifestPlatform>,
    private val repositories: List<MavenRepository>,
    private val substitutions: Substitutions,
    private val httpClient: HttpClient,
    private val artifactResolver: ArtifactUrlResolver,
    /**
     * Resolver of the NPM dependencies declared by `wasm_js` klibs, only needed when [platforms]
     * include `wasmjs`.
     */
    private val npmResolver: NpmResolver?,
) {
    val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Amper caches files by their Maven path, whatever the repository they come from, while a
     * library could be published with different contents in different repositories: each ordered
     * list of repositories gets its own cache, so that a cache filled with other repositories is
     * never used for a resolution against these ones.
     */
    private val amperCachePath: Path =
        cachePath.resolve("amper-cache").resolve(repositories.cacheDirectoryName())

    /**
     * Requested platforms in the order of [SUPPORTED_PLATFORMS], which is the order variants are
     * listed in the manifest.
     */
    private val orderedPlatforms: List<ManifestPlatform> = SUPPORTED_PLATFORMS.filter {
        it in platforms
    }

    init {
        require(platforms.isNotEmpty()) { "At least one platform must be requested" }
        substitutions.forEach { (substituted, substitute) ->
            require(substituted != substitute.toSubstitutionId()) {
                "Substitution of $substituted by ${substitute.gav} substitutes a library by another version of itself, " +
                    "which is not supported: versions are picked by conflict resolution among the requested ones"
            }
        }
    }

    internal suspend fun resolve(coordinatesBag: Collection<String>): List<MultiplatformVariant> {
        logger.debug("Resolving dependency graph...")
        logger.info("Cache location: ${amperCachePath.absolutePathString()}")
        logger.info("Against repositories:\n${repositories.joinToString("\n") { "  ${it.url}" }}")
        if (substitutions.isNotEmpty()) {
            logger.info(
                "Using substitutions:\n${substitutions.entries.joinToString("\n") { "  ${it.key} -> ${it.value.gav}" }}",
            )
        }
        logger.info("Platforms: ${orderedPlatforms.joinToString { it.cliName }}")
        logger.info("Coordinates:\n${coordinatesBag.joinToString("\n") { "  $it" }}")
        // Amper exposes the platform-specific variants of KMP libraries (and their link to the
        // umbrella library) only
        // when resolving for a single platform, so each platform gets its own resolution.
        // Resolutions run sequentially because they share the same Amper cache directory, and
        // platforms are
        // iterated in a fixed order so that the manifest does not depend on the order of the
        // `--platform` flags.
        // Substitutes are resolved in place of the libraries they substitute: they take part in the
        // resolution as
        // roots, so that their artifacts, hashes and dependencies are the ones Amper resolves for
        // them, while the
        // substituted libraries are dropped from the result and the edges pointing at them
        // redirected (see [collectNodes]).
        val substitutes = substitutions.values.map { it.gav }.distinct()
        val requestedModules =
            coordinatesBag
                .map { coordinates ->
                    coordinates.toMavenCoordinates().let {
                        substitutionId(it.groupId, it.artifactId)
                    }
                }
                .toSet()
        val nodes = orderedPlatforms.flatMap { platform ->
            logger.debug("Resolving dependency graph for platform ${platform.cliName}...")
            val root =
                callAmperResolution(
                    coordinatesBag = (coordinatesBag.toList() + substitutes).distinct(),
                    platforms = setOf(platform.resolutionPlatform),
                    scope =
                        listOf(
                            ResolutionScope.RUNTIME,
                            ResolutionScope.COMPILE,
                        ),
                )
            logger.debug("Collecting nodes of the ${platform.cliName} dependency graph...")
            collectNodes(root, platform, requestedModules)
                .dropLibrariesOnlyReachableFromSubstitutes(coordinatesBag, substitutes)
        }
        logger.debug("Resolving NPM dependencies declared by the klibs of the dependency graph...")
        val npmPackagesByVariant = resolveNpmPackages(nodes)
        logger.debug(
            "Resolving artifacts of nodes of the dependency graph against the specified repositories...",
        )
        return resolveArtifacts(
                nodes,
                npmPackagesByVariant,
            )
            .also { variants -> variants.requireOneVariantPerPlatform() }
    }

    /**
     * A library has at most one variant per platform, otherwise consumers could not tell which
     * artifacts to use.
     */
    private fun List<MultiplatformVariant>.requireOneVariantPerPlatform() {
        val duplicates = groupBy { it.id to it.platformName }.filterValues { it.size > 1 }
        require(duplicates.isEmpty()) {
            buildString {
                appendLine("Libraries resolved to several variants of the same platform:")
                duplicates.forEach { (key, variants) ->
                    appendLine(
                        "\t[${key.first.gav}] ${key.second}: ${variants.map { it.variantId.gav }}",
                    )
                }
            }
        }
    }

    private suspend fun resolveNpmPackages(
        nodes: List<UnresolvedMultiplatformLibrary>,
    ): Map<MultiplatformLibraryId, List<NpmMultiplatformLibraryArtifact>> {
        val requests = nodes.mapNotNull { node ->
            when (node) {
                is UnresolvedMultiplatformLibrary.Klib ->
                    when (node.platform) {
                        KlibPlatform.WASM_JS -> {
                            val manifest =
                                withContext(Dispatchers.IO) {
                                    readEmbeddedNpmManifest(node.mainKlibPath())
                                }
                            when {
                                manifest == null || manifest.dependencies.isEmpty() -> null
                                else ->
                                    NpmResolutionRequest(
                                        variantId = node.variantId,
                                        manifest = manifest,
                                    )
                            }
                        }

                        // NPM packages are only relevant to the wasmJs platform
                        else -> null
                    }

                is UnresolvedMultiplatformLibrary.Android,
                is UnresolvedMultiplatformLibrary.Jvm -> null
            }
        }
        return when {
            requests.isEmpty() -> emptyMap()
            else ->
                requireNotNull(npmResolver) {
                        "Klibs declare NPM dependencies, but no NPM resolver was given to resolve them: ${requests.joinToString { it.variantId.gav }}"
                    }
                    .resolve(requests)
        }
    }

    private suspend fun callAmperResolution(
        coordinatesBag: Collection<String>,
        platforms: Set<ResolutionPlatform>,
        scope: List<ResolutionScope>,
    ): RootDependencyNodeWithContext {
        val defaultSettings: SettingsBuilder.() -> Unit = {
            this.platforms = platforms
            repositories = this@MultiplatformResolver.repositories
            cache = getDefaultFileCacheBuilder(amperCachePath)
        }
        // Amper takes the HTTP client of its downloads from the resolution cache under its internal
        // `httpClientKey`, a
        // `Key<java.net.http.HttpClient>("httpClient")`; `Key` is a data class so this key is equal
        // to it. The cache is
        // shared by every context of the session, as Amper expects, so all downloads go through
        // [httpClient].
        val resolutionCache = Cache().apply { set(Key<HttpClient>("httpClient"), httpClient) }
        val templateContext =
            Context(resolutionCache) {
                defaultSettings()
            }
        val contexts = scope.map {
            Context(resolutionCache) {
                defaultSettings()
                this.scope = it
            }
        }
        val root =
            RootDependencyNodeWithContext(
                templateContext = templateContext,
                children = coordinatesBag.flatMap { c -> contexts.map { c.toMavenNode(it) } },
            )

        val resolver = Resolver()
        resolver.resolveDependencies(
            root = root,
            resolutionLevel = ResolutionLevel.NETWORK,
            transitive = true,
            downloadSources = true,
            incrementalCacheUsage = IncrementalCacheUsage.SKIP,
            unspecifiedVersionResolver = MavenDependencyUnspecifiedVersionResolverBase(),
        )

        return root
    }

    private fun collectNodes(
        root: RootDependencyNode,
        platform: ManifestPlatform,
        requestedModules: Set<SubstitutionId>,
    ): List<UnresolvedMultiplatformLibrary> {
        val nodes = root.distinctBfsSequence().filterIsInstance<MavenDependencyNode>().toList()
        // Amper tells the KMP library a node is a platform-specific variant of per graph node, that
        // is per requester: a
        // variant also requested directly, as POM-only JVM libraries do with `*-jvm` artifacts, has
        // nodes whose parent
        // is that requester. The KMP parent is therefore looked up per Maven module, across all the
        // nodes of the graph.
        val kmpParents: KmpParents =
            nodes
                .mapNotNull { node ->
                    node.getParentKmpLibraryCoordinates()?.let { parent ->
                        node.gav.toSubstitutionId() to
                            MultiplatformLibraryId(
                                groupId = parent.groupId,
                                artifactId = parent.artifactId,
                                version =
                                    parent.version
                                        ?: error(
                                            "${node.gav.gav} parent $parent must have a version",
                                        ),
                            )
                    }
                }
                .toMap()
        val errorsByNode =
            nodes
                .map { node ->
                    node.gav to node.resolutionErrors(logger)
                }
                .filter { (_, errors) ->
                    errors.isNotEmpty()
                }
        require(errorsByNode.isEmpty()) {
            buildString {
                appendLine("Amper resolution failed with errors:")
                errorsByNode.forEach { (gav, errors) ->
                    appendLine("\t[$gav] errors:")
                    errors.forEach { error ->
                        appendLine("\t\t${error.message} (${error.details ?: ""})")
                    }
                }
            }
        }

        // nodes requesting the same library point at the same dependency, hence the same artifacts:
        // whether it is collected is decided once per dependency, which also logs the reason it is
        // skipped once
        val collectedDependencies =
            nodes
                .distinctBy { node -> node.dependency }
                .filter { node -> node.isCollectedFor(platform, kmpParents, requestedModules) }
                .map { node -> node.dependency }
                .toSet()
        val (substitutedNodes, filteredNodes) =
            nodes
                .filter { node -> node.dependency in collectedDependencies }
                .partition { node -> node.isSubstituted(kmpParents) }
        substitutedNodes.forEach { node ->
            logger.debug(
                "[${node.gav.gav}] dropped, it is substituted (platform ${platform.cliName})",
            )
        }

        // some KMP dependencies are incorrectly declaring a JVM dependency
        // e.g. `ai.jetbrains.code.files:code-files-model-wasm-js:1.0.0-beta.170` which depends on
        // `org.slf4j:slf4j-api:2.0.17`
        // a purely JVM dependency
        val possibleDependencies =
            filteredNodes
                .flatMap { node ->
                    listOfNotNull(
                        node.gav.toSubstitutionId(),
                        kmpParents[node.gav.toSubstitutionId()]?.toSubstitutionId(),
                    )
                }
                .toSet()
        return filteredNodes
            .groupBy { it.gav }
            .map { (_, scopedNodes) ->
                when (platform) {
                    is ManifestPlatform.Klib ->
                        UnresolvedMultiplatformLibrary.Klib(
                            platform = platform.klibPlatform,
                            resolvedNodes = scopedNodes,
                            substitutions = substitutions,
                            kmpParents = kmpParents,
                            possibleDependencies = possibleDependencies,
                            repositories = repositories,
                        )

                    ManifestPlatform.Android ->
                        UnresolvedMultiplatformLibrary.Android(
                            resolvedNodes = scopedNodes,
                            substitutions = substitutions,
                            kmpParents = kmpParents,
                            possibleDependencies = possibleDependencies,
                            repositories = repositories,
                        )

                    ManifestPlatform.Jvm ->
                        UnresolvedMultiplatformLibrary.Jvm(
                            resolvedNodes = scopedNodes,
                            substitutions = substitutions,
                            kmpParents = kmpParents,
                            possibleDependencies = possibleDependencies,
                            repositories = repositories,
                        )
                }
            }
    }

    /**
     * Whether this node, or the KMP library it is a platform-specific variant of, is substituted by
     * another library: its substitute is resolved in its place (see [resolve]), so the node itself
     * is not part of the result.
     */
    private fun MavenDependencyNode.isSubstituted(kmpParents: KmpParents): Boolean =
        substitutionKeys(kmpParents).isNotEmpty()

    /**
     * The substitution keys (`group:artifact`) this node matches, by its own coordinates or by the
     * KMP library it is a platform-specific variant of.
     */
    private fun MavenDependencyNode.substitutionKeys(kmpParents: KmpParents): List<SubstitutionId> =
        listOfNotNull(
                gav.toSubstitutionId(),
                kmpParents[gav.toSubstitutionId()]?.toSubstitutionId(),
            )
            .filter { it in substitutions }

    /**
     * Substitutes are resolved as roots whether or not something depends on them: those that
     * nothing reachable from the asked [coordinatesBag] depends on are dropped, along with what
     * only they pull in, so that the manifest only contains what the asked coordinates need.
     * Libraries reachable from neither (e.g. pulled by a pom-only intermediate, which the manifest
     * cannot express) are kept, as they were before substitutes became roots.
     */
    private fun List<UnresolvedMultiplatformLibrary>.dropLibrariesOnlyReachableFromSubstitutes(
        coordinatesBag: Collection<String>,
        substitutes: Collection<String>,
    ): List<UnresolvedMultiplatformLibrary> {
        val librariesByModule = associateBy { it.id.toSubstitutionId() }
        val reachableFromAsked = librariesByModule.reachableFrom(coordinatesBag)
        val onlyReachableFromSubstitutes =
            librariesByModule.reachableFrom(substitutes) - reachableFromAsked
        onlyReachableFromSubstitutes.forEach { library ->
            logger.debug(
                "[${library.id.gav}] dropped, nothing asked depends on it, it is only reachable from a substitute",
            )
        }
        return this - onlyReachableFromSubstitutes
    }

    /**
     * Libraries (keyed by module) reachable from the given root coordinates through their
     * dependencies, substitutions applied to the roots.
     */
    private fun Map<SubstitutionId, UnresolvedMultiplatformLibrary>.reachableFrom(
        roots: Collection<String>,
    ): Set<UnresolvedMultiplatformLibrary> {
        val rootModules =
            substitutions.substituteSubstitutionIds(
                roots.map { root ->
                    root.toMavenCoordinates().let { substitutionId(it.groupId, it.artifactId) }
                },
            )
        val reached = linkedSetOf<UnresolvedMultiplatformLibrary>()
        val pending = ArrayDeque(rootModules.mapNotNull { this[it] })
        while (pending.isNotEmpty()) {
            val library = pending.removeFirst()
            if (reached.add(library)) {
                (library.dependencies + library.exportedDependencies).mapNotNullTo(pending) {
                    this[it]
                }
            }
        }
        return reached
    }

    /**
     * Whether this node of the dependency graph resolved for [platform] is a library to expose in
     * the manifest.
     *
     * In a single-platform resolution, KMP umbrella libraries, BOMs and pom-only libraries have no
     * artifact, nor has `kotlin-stdlib` on native platforms, as it ships with the Kotlin/Native
     * distribution: they are skipped, and the edges pointing at an umbrella library are kept
     * through the platform-specific variants it is the KMP parent of. The platform-specific variant
     * of a KMP library (`*-wasm-js`, `*-linuxx64`, `*-jvm`) has artifacts, and so has a JVM-only
     * library, whose artifacts tell whether it is a library of the platform.
     *
     * A klib platform collects the variants of KMP libraries, along with the libraries requested
     * directly ([requestedModules]), such as a platform-specific variant asked for itself.
     */
    private fun MavenDependencyNode.isCollectedFor(
        platform: ManifestPlatform,
        kmpParents: KmpParents,
        requestedModules: Set<SubstitutionId>,
    ): Boolean {
        val artifacts =
            (dependency as MavenDependencyImpl).files(withSources = true).filterNot {
                it.isDocumentation
            }
        val module = gav.toSubstitutionId()
        return when {
            artifacts.isEmpty() -> {
                logger.debug(
                    "[${gav.gav}] skipping ${platform.cliName} platform, it has no artifact: a KMP library, a BOM, a POM-only library, or a library shipped with the Kotlin/Native distribution",
                )
                false
            }

            else ->
                when (platform) {
                    is ManifestPlatform.Klib ->
                        when {
                            artifacts.none { it.isKlib() } -> {
                                // e.g. a JVM library a KMP library wrongly declares a dependency on
                                logger.warn(
                                    "[${gav.gav}] skipping ${platform.cliName} platform, no klib among its artifacts: ${artifacts.map { it.fullName }}",
                                )
                                false
                            }

                            module in kmpParents || module in requestedModules -> true
                            else -> {
                                logger.warn(
                                    "[${gav.gav}] skipping ${platform.cliName} platform, it is neither a variant of a KMP library nor requested: ${artifacts.map { it.fullName }}",
                                )
                                false
                            }
                        }

                    ManifestPlatform.Android ->
                        when {
                            artifacts.any { it.isAar() || it.isClasspathJar() } -> true
                            else -> {
                                logger.warn(
                                    "[${gav.gav}] skipping android platform, neither aar nor jar among its artifacts: ${artifacts.map { it.fullName }}",
                                )
                                false
                            }
                        }

                    ManifestPlatform.Jvm ->
                        when {
                            artifacts.any { it.isClasspathJar() } -> true
                            else -> {
                                // e.g. a library only published as an Android `.aar`
                                logger.warn(
                                    "[${gav.gav}] skipping jvm platform, no jar among its artifacts: ${artifacts.map { it.fullName }}",
                                )
                                false
                            }
                        }
                }
        }
    }

    private suspend fun resolveArtifacts(
        nodes: List<UnresolvedMultiplatformLibrary>,
        npmPackagesByVariant: Map<MultiplatformLibraryId, List<NpmMultiplatformLibraryArtifact>>,
    ): List<MultiplatformVariant> = coroutineScope {
        nodes
            .map { unresolvedNode ->
                when (unresolvedNode) {
                    is UnresolvedMultiplatformLibrary.Klib ->
                        async {
                            unresolvedNode.resolve(
                                artifactResolver,
                                when (unresolvedNode.platform) {
                                    KlibPlatform.WASM_JS ->
                                        npmPackagesByVariant[unresolvedNode.variantId].orEmpty()

                                    else -> emptyList()
                                },
                            )
                        }

                    is UnresolvedMultiplatformLibrary.Android ->
                        async {
                            unresolvedNode.resolve(artifactResolver)
                        }

                    is UnresolvedMultiplatformLibrary.Jvm ->
                        async {
                            unresolvedNode.resolve(artifactResolver)
                        }
                }
            }
            .awaitAll()
    }
}

/** Name of the cache directory of these repositories, a digest of their URLs in order. */
private fun List<MavenRepository>.cacheDirectoryName(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(joinToString("\n") { it.url }.toByteArray())
        .toHexString()
        .take(16)

private val MavenDependencyNode.gav
    get(): MultiplatformLibraryId =
        MultiplatformLibraryId(
            groupId = group,
            artifactId = module,
            version = resolvedVersion().orUnspecified(),
        )

private fun DependencyNode.resolutionErrors(logger: Logger): List<Message> =
    messages
        .filter { it.severity >= Severity.ERROR }
        .mapNotNull {
            when (it) {
                // the library is not published for the platform being resolved, it is simply not
                // part of this platform's graph
                is PlatformsAreNotSupported -> {
                    logger.warn(
                        "[${it.coordinates}] skipping unsupported platform(s): ${it.unsupportedPlatforms.joinToString { p -> p.pretty }}",
                    )
                    null
                }

                else -> it
            }
        }

// Nodes are collected per platform, so a node's files all belong to the platform being resolved.
private fun DependencyFileImpl.isSourceJar(): Boolean =
    isDocumentation && extension == "jar" && nameWithoutExtension.endsWith("-sources")

private fun DependencyFile.isClasspathJar(): Boolean = !isDocumentation && extension == "jar"

private fun DependencyFile.isKlib(): Boolean = !isDocumentation && extension == "klib"

private fun DependencyFile.isAar(): Boolean = !isDocumentation && extension == "aar"

/** Name of this file in the repository it is downloaded from. */
private val DependencyFileImpl.fullName: String
    get() = "$nameWithoutExtension.$extension"

/**
 * Matches the klibs binding C libraries, which Kotlin publishes next to the main klib of a native
 * variant with a `cinterop-<name>` classifier
 * (`ktor-client-curl-linuxx64-3.4.3-cinterop-libcurl.klib` in the repository), named
 * `<artifact>-<Target>Cinterop-<name>-<version>.klib` in Gradle module metadata
 * (`ktor-client-curl-linuxX64Cinterop-libcurlMain-3.4.3.klib`).
 */
private val CINTEROP_KLIB = Regex("cinterop-", RegexOption.IGNORE_CASE)

/**
 * Role of the klib named [fileName] in the variant [artifactId]; the artifact ID is left out of the
 * match, so that a library whose name contains `cinterop` keeps its main klib.
 */
internal fun klibRole(artifactId: String, fileName: String): KlibRole =
    when {
        CINTEROP_KLIB.containsMatchIn(fileName.removePrefix(artifactId)) -> KlibRole.CINTEROP
        else -> KlibRole.MAIN
    }

/**
 * The [klibs] of the [variantId] variant with their role, in the order of
 * [MultiplatformVariant.Klib.klibs]: its main klib first, then its cinterop klibs sorted by file
 * name. A variant has exactly one main klib.
 */
internal fun <T> classifyKlibs(
    variantId: MultiplatformLibraryId,
    klibs: List<T>,
    fileName: (T) -> String,
): List<Pair<KlibRole, T>> {
    val (mainKlibs, cinteropKlibs) =
        klibs.sortedBy(fileName).partition { klib ->
            klibRole(variantId.artifactId, fileName(klib)) == KlibRole.MAIN
        }
    require(mainKlibs.size == 1) {
        "[${variantId.gav}] variant must have exactly one main klib besides its cinterop klibs, found ${mainKlibs.size} among: ${klibs.map(fileName)}"
    }
    return listOf(KlibRole.MAIN to mainKlibs.single()) +
        cinteropKlibs.map { klib -> KlibRole.CINTEROP to klib }
}

private data class UnresolvedMultiplatformLibraryArtifact(
    val fileName: String,
    val integrity: MultiplatformLibraryArtifactIntegrity,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val possibleLocations: List<String>,
)

/**
 * The node of the given [scope] among nodes sharing the same resolved coordinates.
 *
 * Several nodes of the graph can request the same library at different versions (common on JVM,
 * where POMs declare all kinds of `kotlin-stdlib` versions), but conflict resolution makes them
 * point at the same dependency, hence the same files and children, so they collapse into one.
 */
private fun List<MavenDependencyNode>.singleNodeOfScope(
    scope: ResolutionScope,
): MavenDependencyNode? {
    val scopeNodes = filter {
        it.dependency.resolutionConfig.scope == scope
    }
        .distinctBy { it.dependency }
    require(scopeNodes.size <= 1) {
        "UnresolvedMultiplatformLibrary cannot have multiple $scope scope instance, but got $scopeNodes"
    }
    return scopeNodes.singleOrNull()
}

/**
 * A library of the dependency graph resolved for a single platform, built from its runtime and
 * compile scoped nodes.
 */
private sealed class UnresolvedMultiplatformLibrary(
    resolvedNodes: List<MavenDependencyNode>,
    private val repositories: List<MavenRepository>,
    private val substitutions: Substitutions,
    private val kmpParents: KmpParents,
    private val possibleDependencies: Set<SubstitutionId>,
) {
    private val runtimeNode: MavenDependencyNode? =
        resolvedNodes.singleNodeOfScope(ResolutionScope.RUNTIME)
    private val compileNode: MavenDependencyNode? =
        resolvedNodes.singleNodeOfScope(ResolutionScope.COMPILE)
    private val node: MavenDependencyNode =
        runtimeNode ?: compileNode ?: error("must at least have one of compile or runtime node set")

    init {
        require(runtimeNode != null || compileNode != null) {
            "must at least have one of compile or runtime node set"
        }
        require(runtimeNode == null || compileNode == null || runtimeNode.gav == compileNode.gav) {
            """
                runtime and compile scopped nodes are from different coordinates:
                  - ${runtimeNode?.gav}
                  - ${compileNode?.gav}
            """
                .trimIndent()
        }
    }

    /**
     * ID of the KMP umbrella library this node is a platform-specific variant of, or the node's own
     * ID when it is not such a variant: a JVM-only library, or a KMP library publishing its JVM
     * artifact in the umbrella module itself, like `kotlin-stdlib`. The latter makes the JVM
     * variant of `kotlin-stdlib` land under the same library as its wasmJs variant.
     */
    val id: MultiplatformLibraryId by lazy { kmpParents[variantId.toSubstitutionId()] ?: variantId }

    val variantId: MultiplatformLibraryId = node.gav

    protected val files: List<DependencyFileImpl> by lazy {
        node.dependency.files(withSources = true).filterIsInstance<DependencyFileImpl>()
    }

    suspend fun sourceJar(): UnresolvedMultiplatformLibraryArtifact? {
        val sourceJars = files.filter { file -> file.isSourceJar() }
        require(sourceJars.size <= 1) {
            "Expected at most one source jar, found ${sourceJars.size}: $sourceJars"
        }
        return sourceJars.singleOrNull()?.toUnresolvedMultiplatformLibraryArtifact()
    }

    val exportedDependencies: Set<SubstitutionId> =
        compileNode?.platformDependencies() ?: emptySet()

    val dependencies: Set<SubstitutionId> =
        (runtimeNode?.platformDependencies() ?: emptySet()) - exportedDependencies

    /**
     * Dependencies of this node that are libraries of the manifest, identified by the ID of the
     * library they are (or are a platform-specific variant of): a dependency declared directly on a
     * `*-jvm` variant, as POM-only JVM libraries commonly do, must point at the library of that
     * variant. A dependency on a substituted library points at its substitute, resolved in its
     * place.
     */
    private fun MavenDependencyNode.platformDependencies(): Set<SubstitutionId> =
        children
            .asSequence()
            .filterIsInstance<MavenDependencyNode>()
            .map { child ->
                kmpParents[child.gav.toSubstitutionId()]?.toSubstitutionId()
                    ?: child.gav.toSubstitutionId()
            }
            .let { substitutions.substituteSubstitutionIds(it.toList()) }
            .filter { it in possibleDependencies }
            .toSet()

    protected suspend fun DependencyFileImpl.toUnresolvedMultiplatformLibraryArtifact():
        UnresolvedMultiplatformLibraryArtifact {
        val file = this
        return UnresolvedMultiplatformLibraryArtifact(
            fileName = "${file.nameWithoutExtension}.${file.extension}",
            groupId = variantId.groupId,
            artifactId = variantId.artifactId,
            version = variantId.version,
            possibleLocations =
                repositories.map { repository ->
                    file.getUrl(repository, (node as MavenDependencyNodeWithContext).context)
                },
            // Amper will not resolve all hashes, but from the more robust to the least robust, so
            // they are queried in
            // that order. sha1 is the fallback for repositories publishing nothing stronger (see
            // [MultiplatformLibraryArtifactIntegrity.Sha1]);
            // Bazel accepts it in `integrity` strings.
            integrity =
                listOf("sha512", "sha256", "sha1").firstNotNullOfOrNull { algorithm ->
                    file.getExpectedHash(HashAlgorithm(algorithm))?.let { hash ->
                        MultiplatformLibraryArtifactIntegrity.fromHash(algorithm, hash)
                    }
                } ?: error("could not find hash for artifact $file"),
        )
    }

    class Klib(
        val platform: KlibPlatform,
        resolvedNodes: List<MavenDependencyNode>,
        repositories: List<MavenRepository>,
        substitutions: Substitutions,
        kmpParents: KmpParents,
        possibleDependencies: Set<SubstitutionId>,
    ) :
        UnresolvedMultiplatformLibrary(
            resolvedNodes,
            repositories,
            substitutions,
            kmpParents,
            possibleDependencies,
        ) {
        private val classifiedKlibs: List<Pair<KlibRole, DependencyFileImpl>> by lazy {
            classifyKlibs(variantId, files.filter { file -> file.isKlib() }) { it.fullName }
        }

        /** The klibs of this library with their role, its main klib first. */
        suspend fun klibs(): List<Pair<KlibRole, UnresolvedMultiplatformLibraryArtifact>> =
            classifiedKlibs.map { (role, klib) ->
                role to klib.toUnresolvedMultiplatformLibraryArtifact()
            }

        /**
         * Local path of the main klib downloaded by the Amper resolution, allowing the inspection
         * of its contents.
         */
        suspend fun mainKlibPath(): Path =
            classifiedKlibs.first().second.getPath()
                ?: error("[${variantId.gav}] klib was not downloaded to the local cache")
    }

    class Android(
        resolvedNodes: List<MavenDependencyNode>,
        repositories: List<MavenRepository>,
        substitutions: Substitutions,
        kmpParents: KmpParents,
        possibleDependencies: Set<SubstitutionId>,
    ) :
        UnresolvedMultiplatformLibrary(
            resolvedNodes,
            repositories,
            substitutions,
            kmpParents,
            possibleDependencies,
        ) {
        /**
         * The `.aar` of this library or, when it publishes none, its classpath jar: a JVM library,
         * or the JVM variant of a KMP library publishing no Android variant.
         */
        suspend fun artifact(): Pair<AndroidPackaging, UnresolvedMultiplatformLibraryArtifact> {
            val aars = files.filter { file -> file.isAar() }
            val (packaging, artifacts) =
                when {
                    aars.isNotEmpty() -> AndroidPackaging.AAR to aars
                    else -> AndroidPackaging.JAR to files.filter { file -> file.isClasspathJar() }
                }
            require(artifacts.size == 1) {
                "[${variantId.gav}] node must have exactly one aar, or exactly one jar without aar, found: ${files.filterNot { it.isDocumentation }.map { it.fullName }}"
            }
            return packaging to artifacts.single().toUnresolvedMultiplatformLibraryArtifact()
        }
    }

    class Jvm(
        resolvedNodes: List<MavenDependencyNode>,
        repositories: List<MavenRepository>,
        substitutions: Substitutions,
        kmpParents: KmpParents,
        possibleDependencies: Set<SubstitutionId>,
    ) :
        UnresolvedMultiplatformLibrary(
            resolvedNodes,
            repositories,
            substitutions,
            kmpParents,
            possibleDependencies,
        ) {
        suspend fun jar(): UnresolvedMultiplatformLibraryArtifact {
            val jars = files.filter { file -> file.isClasspathJar() }
            require(jars.size == 1) {
                "[$variantId] node must have exactly one jar, found ${jars.size}: ${jars.map { it.fullName }}"
            }
            return jars.single().toUnresolvedMultiplatformLibraryArtifact()
        }
    }
}

private suspend fun UnresolvedMultiplatformLibraryArtifact.resolve(
    artifactUrlResolver: ArtifactUrlResolver,
): MavenMultiplatformLibraryArtifact {
    val urls = coroutineScope {
        possibleLocations
            .map { location ->
                async { artifactUrlResolver.artifactExistsAt(location) }
            }
            .awaitAll()
            .filterIsInstance<ArtifactFile.Resolved>()
            .map { it.url }
    }
    require(urls.isNotEmpty()) {
        "[$groupId:$artifactId:$version] artifact $fileName could not be found in any of the specified repositories"
    }

    return MavenMultiplatformLibraryArtifact(
        integrity = integrity,
        groupId = groupId,
        artifactId = artifactId,
        version = version,
        urls = urls,
    )
}

private suspend fun UnresolvedMultiplatformLibrary.Klib.resolve(
    artifactUrlResolver: ArtifactUrlResolver,
    npmPackages: List<NpmMultiplatformLibraryArtifact>,
): MultiplatformVariant.Klib = coroutineScope {
    val resolvedKlibs =
        klibs().map { (role, klib) ->
            async { KlibArtifact(role = role, artifact = klib.resolve(artifactUrlResolver)) }
        }
    val resolvedSourceJar = sourceJar()?.let { async { it.resolve(artifactUrlResolver) } }
    MultiplatformVariant.Klib(
        platform = platform,
        id = id,
        variantId = variantId,
        klibs = resolvedKlibs.awaitAll(),
        sourceJar = resolvedSourceJar?.await(),
        dependencies = dependencies.sorted(),
        exportedDependencies = exportedDependencies.sorted(),
        npmPackages = npmPackages,
    )
}

private suspend fun UnresolvedMultiplatformLibrary.Android.resolve(
    artifactUrlResolver: ArtifactUrlResolver,
): MultiplatformVariant.Android = coroutineScope {
    val (packaging, artifact) = artifact()
    val resolvedArtifact = async { artifact.resolve(artifactUrlResolver) }
    val resolvedSourceJar = sourceJar()?.let { async { it.resolve(artifactUrlResolver) } }
    MultiplatformVariant.Android(
        id = id,
        variantId = variantId,
        artifact = resolvedArtifact.await(),
        packaging = packaging,
        sourceJar = resolvedSourceJar?.await(),
        dependencies = dependencies.sorted(),
        exportedDependencies = exportedDependencies.sorted(),
    )
}

private suspend fun UnresolvedMultiplatformLibrary.Jvm.resolve(
    artifactUrlResolver: ArtifactUrlResolver,
): MultiplatformVariant.Jvm = coroutineScope {
    val resolvedJar = async { jar().resolve(artifactUrlResolver) }
    val resolvedSourceJar = sourceJar()?.let { async { it.resolve(artifactUrlResolver) } }
    MultiplatformVariant.Jvm(
        id = id,
        variantId = variantId,
        jar = resolvedJar.await(),
        sourceJar = resolvedSourceJar?.await(),
        dependencies = dependencies.sorted(),
        exportedDependencies = exportedDependencies.sorted(),
    )
}

private fun String.toMavenNode(context: Context): MavenDependencyNodeWithContext =
    context.toMavenDependencyNode(coordinates = toMavenCoordinates(), isBom = startsWith("bom:"))

private fun String.toMavenCoordinates(): MavenCoordinates {
    val parts = removePrefix("bom:").trim().split(":")
    return MavenCoordinates(
        groupId = parts[0],
        artifactId = parts[1],
        version =
            when {
                parts.size > 2 -> parts[2]
                else -> null
            },
    )
}

private fun MultiplatformLibraryId.toSubstitutionId(): SubstitutionId =
    substitutionId(groupId, artifactId)

private fun substitutionId(groupId: String, artifactId: String) = "$groupId:$artifactId"

private fun Substitutions.substituteSubstitutionIds(
    originalIds: Collection<SubstitutionId>,
): Set<SubstitutionId> =
    originalIds
        .map { originalId ->
            when (val subId = this[originalId]) {
                null -> originalId
                else -> subId.toSubstitutionId()
            }
        }
        .toSet()
