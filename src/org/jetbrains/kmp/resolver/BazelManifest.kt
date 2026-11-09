package org.jetbrains.kmp.resolver

import java.util.Properties
import kotlinx.serialization.Serializable

/** Version of the schema of the manifests, bumped on every incompatible change of the schema. */
internal const val SCHEMA_VERSION = 2

/**
 * Manifest of a resolution, from which the Bazel module extension of rules_kotlin generates its
 * targets, see `docs/manifest.md`.
 */
@Serializable
internal data class BazelManifest(
    /** Version of the schema of this manifest, [SCHEMA_VERSION] for the manifests written here. */
    val schemaVersion: Int,

    /** Version of the resolver that generated this manifest, `dev` for a development build. */
    val resolverVersion: String,

    /** [Hash][ManifestInputs.hash] of the inputs this manifest is resolved from. */
    val inputsHash: String,
    val askedPlatforms: List<String>,
    val askedCoordinates: List<String>,
    val askedRepositories: List<String>,
    val askedSubstitutions: List<String>,

    /**
     * The libraries whose published checksums were ignored, their artifacts' integrity being the
     * sha256 of their downloaded content; absent from the manifests ignoring none.
     */
    val askedIgnoredPublishedChecksums: List<String> = emptyList(),
    val libraries: Map<MultiplatformLibraryId, MultiplatformLibrary>,
) {
    companion object {
        fun of(
            inputs: ManifestInputs,
            resolverVersion: String,
            variants: List<MultiplatformVariant>,
        ): BazelManifest =
            BazelManifest(
                schemaVersion = SCHEMA_VERSION,
                resolverVersion = resolverVersion,
                inputsHash = inputs.hash(),
                askedPlatforms = inputs.platforms,
                askedCoordinates = inputs.coordinates,
                askedRepositories = inputs.repositories,
                askedSubstitutions = inputs.substitutions,
                askedIgnoredPublishedChecksums = inputs.ignoredPublishedChecksums,
                libraries = variants.asLibraries(),
            )
    }
}

/**
 * Header of a manifest of any schema version, read to check a manifest before the rest of it, which
 * may follow another schema: the manifests of schema version 1 had neither a schema version nor an
 * inputs hash.
 */
@Serializable
internal data class BazelManifestHeader(
    val schemaVersion: Int = 1,
    val resolverVersion: String? = null,
    val inputsHash: String? = null,
)

internal fun List<MultiplatformVariant>.asLibraries():
    Map<MultiplatformLibraryId, MultiplatformLibrary> = groupBy {
    it.id
}
    .mapValues { (id, variants) ->
        MultiplatformLibrary(
            id = id,
            variants = variants,
        )
    }
    .toSortedMap()

private const val RESOLVER_PROPERTIES_RESOURCE = "/META-INF/bazel-kmp-resolver.properties"

private const val DEVELOPMENT_VERSION = "dev"

/**
 * Version of this resolver, recorded in the manifests it generates: the `version` of the
 * `META-INF/bazel-kmp-resolver.properties` resource the release builds write, `dev` for the other
 * builds.
 */
internal val resolverVersion: String by lazy {
    when (
        val stream = BazelManifest::class.java.getResourceAsStream(RESOLVER_PROPERTIES_RESOURCE)
    ) {
        null -> DEVELOPMENT_VERSION
        else -> {
            val properties = Properties().also { properties -> stream.use(properties::load) }
            properties.getProperty("version")?.trim()?.takeIf { it.isNotEmpty() }
                ?: DEVELOPMENT_VERSION
        }
    }
}
