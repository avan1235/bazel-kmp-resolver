package org.jetbrains.kmp.resolver

import java.security.MessageDigest
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The inputs a manifest is resolved from, normalized so that the command lines resolving the same
 * manifest have equal inputs: a manifest depends neither on the order of the platforms,
 * coordinates, substitutions and NPM package versions nor on their duplicates, while the order of
 * the repositories is the order artifacts are looked up in and their URLs listed in.
 *
 * Their [hash] is recorded in the manifest as its `inputsHash`, telling whether a pinned manifest
 * is stale. The options only changing how a resolution runs (cache, credentials, timeouts, Node.js)
 * are not inputs.
 */
@Serializable
internal data class ManifestInputs(
    /** `--platform` names, in the order of [SUPPORTED_PLATFORMS]. */
    val platforms: List<String>,

    /** `--coordinate` values, sorted. */
    val coordinates: List<String>,

    /** `--repository` URLs, in the order they are given. */
    val repositories: List<String>,

    /** `--substitution` values as `group:artifact=group:artifact:version`, sorted. */
    val substitutions: List<String>,

    /**
     * `--ignore-published-checksums` values as `group:artifact:version`, sorted. Left out of the
     * canonical JSON when empty, so that the hashes of the manifests predating the option (none of
     * which ignores published checksums) are unchanged.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val ignoredPublishedChecksums: List<String> = emptyList(),

    /** URL of the registry the NPM dependencies of `wasm_js` klibs are resolved against. */
    val npmRegistry: String,

    /** `--npm-package-version` values as `name=version`, sorted. */
    val npmPackageVersions: List<String>,
) {
    /**
     * Lowercase hexadecimal sha256 digest of the canonical JSON of these inputs: their properties
     * in declaration order, without whitespace.
     */
    fun hash(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonicalJson.encodeToString(serializer(), this).encodeToByteArray())
            .toHexString()

    companion object {
        private val canonicalJson = Json {
            encodeDefaults = true
        }

        fun of(
            platforms: Collection<ManifestPlatform>,
            coordinates: Collection<String>,
            repositories: List<String>,
            substitutions: Substitutions,
            ignoredPublishedChecksums: Collection<String> = emptySet(),
            npmRegistry: String?,
            npmPackageVersions: Map<String, String>,
        ): ManifestInputs =
            ManifestInputs(
                platforms = SUPPORTED_PLATFORMS.filter { it in platforms }.map { it.cliName },
                coordinates = coordinates.distinct().sorted(),
                repositories = repositories.distinct(),
                substitutions =
                    substitutions
                        .map { (substituted, substitute) -> "$substituted=${substitute.gav}" }
                        .sorted(),
                ignoredPublishedChecksums = ignoredPublishedChecksums.distinct().sorted(),
                npmRegistry = npmRegistry ?: NpmResolver.DEFAULT_REGISTRY_URL,
                npmPackageVersions =
                    npmPackageVersions.map { (name, version) -> "$name=$version" }.sorted(),
            )
    }
}
