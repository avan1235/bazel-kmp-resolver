package org.jetbrains.kmp.resolver

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.unique
import com.github.ajalt.clikt.parameters.types.choice

/**
 * Options of the inputs a manifest is resolved from, shared by the commands generating and checking
 * manifests so that both digest the same [ManifestInputs].
 */
internal class ResolutionInputsOptions :
    OptionGroup(
        name = "Resolution inputs",
        help =
            "Options a manifest is resolved from, digested into its inputsHash: check-bazel-manifest takes the ones generate-bazel-manifest was given.",
    ) {
    val coordinates: List<String> by
        option(
                "--coordinate",
                help =
                    "Maven coordinate to resolve, can be specified multiple times for resolving many coordinates at once.",
            )
            .multiple(required = true)

    val platforms: Set<ManifestPlatform> by
        option(
                "--platform",
                help =
                    """
                    Platform to resolve the coordinates for, can be specified multiple times to resolve many platforms at once.
                    `wasmjs` and the konan targets produce the klib variants of KMP libraries, `android` their aar or jar
                    variants along with Android and JVM libraries, `jvm` their jar variants along with JVM-only libraries.
                    """
                        .trimIndent(),
            )
            .choice(SUPPORTED_PLATFORMS.associateBy { it.cliName })
            .multiple(required = true)
            .unique()

    val substitutions: List<Pair<SubstitutionId, MultiplatformLibraryId>> by
        option(
                "--substitution",
                help =
                    """
                    `group:artifact=group:artifact:version` substituting a library by another one, resolved in its place with
                    its own artifacts and dependencies; substituting a KMP library covers its platform-specific variants. Can
                    be specified multiple times.
                    """
                        .trimIndent(),
            )
            .convert {
                val (original, sub) = it.split("=", limit = 2)
                original to MultiplatformLibraryId.fromString(sub)
            }
            .multiple(required = false)

    val ignoredPublishedChecksums: Set<String> by
        option(
                "--ignore-published-checksums",
                help =
                    """
                    `group:artifact:version` of a library whose artifacts do not match the checksums its repository
                    publishes: the mismatch is not an error, and the manifest records the sha256 of the content actually
                    downloaded instead. Only that exact library is affected, the platform-specific variant of a KMP library
                    must be named itself. Can be specified multiple times.
                    """
                        .trimIndent(),
            )
            .convert {
                require(it.split(":").size == 3 && it.split(":").none { part -> part.isEmpty() }) {
                    "'$it' is not group:artifact:version"
                }
                it
            }
            .multiple(required = false)
            .unique()

    val repositories: List<String> by
        option(
                "--repository",
                help =
                    "Maven repository URL, can be specified multiple times for resolving against many repositories, looked up in order.",
            )
            .multiple(required = true)

    val npmRegistry: String? by
        option(
            "--npm-registry",
            help =
                "NPM registry URL used to resolve klib-declared NPM dependencies. Defaults to https://registry.npmjs.org.",
        )

    val npmPackageVersions: List<Pair<String, String>> by
        option(
                "--npm-package-version",
                help =
                    "Hardcoded `name=version` of an NPM package, resolving version conflicts between klibs manually, can be specified multiple times.",
            )
            .convert {
                val (name, version) = it.split("=", limit = 2)
                name to version
            }
            .multiple(required = false)

    fun manifestInputs(): ManifestInputs =
        ManifestInputs.of(
            platforms = platforms,
            coordinates = coordinates,
            repositories = repositories,
            substitutions = substitutions.toMap(),
            ignoredPublishedChecksums = ignoredPublishedChecksums,
            npmRegistry = npmRegistry,
            npmPackageVersions = npmPackageVersions.toMap(),
        )
}
