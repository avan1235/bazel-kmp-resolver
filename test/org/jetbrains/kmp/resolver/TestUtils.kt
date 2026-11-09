package org.jetbrains.kmp.resolver

import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import org.jetbrains.amper.dependency.resolution.MavenRepository
import org.jetbrains.kmp.resolver.auth.Authentication
import org.jetbrains.kmp.resolver.auth.RequestCredentials
import org.jetbrains.kmp.resolver.http.RetryPolicy
import org.jetbrains.kmp.resolver.http.withAuthenticatingHttpClient

object TestResourceReader {
    fun readResource(path: String): InputStream {
        return this::class.java.getResourceAsStream("/$path") ?: error("Resource not found: $path")
    }
}

/**
 * Runs [block] with a [MultiplatformResolver] wired to an authenticating HTTP client closed once
 * [block] returns and, when the `wasmjs` platform is requested, to the hermetic [NodeDistribution]
 * provisioned by the `node` build plugin, so the tests never depend on a node/npm installation of
 * the host.
 */
internal suspend fun <T> testMultiplatformResolver(
    cachePath: Path,
    repositories: List<String>,
    substitutions: Substitutions,
    platforms: Set<ManifestPlatform>,
    credentials: RequestCredentials,
    ignoredPublishedChecksums: Set<String> = emptySet(),
    block: suspend (MultiplatformResolver) -> T,
): T =
    withAuthenticatingHttpClient(credentials = credentials, connectTimeout = 30.seconds) {
        httpClient ->
        block(
            MultiplatformResolver(
                cachePath = cachePath,
                platforms = platforms,
                repositories = repositories.map { MavenRepository(it) },
                httpClient = httpClient,
                artifactResolver =
                    ArtifactUrlResolver(
                        httpClient = httpClient,
                        allowedConcurrentConnections = 32,
                        requestTimeout = 10.seconds,
                        retryPolicy = RetryPolicy.DEFAULT,
                    ),
                substitutions = substitutions,
                ignoredPublishedChecksums = ignoredPublishedChecksums,
                npmResolver =
                    when {
                        ManifestPlatform.WASM_JS in platforms ->
                            NpmResolver(
                                nodeExecutable = NodeDistribution.node,
                                npmCliJs = NodeDistribution.npmCliJs,
                                registryUrl = null,
                                packageVersionOverrides = emptyMap(),
                                workDir = cachePath,
                            )

                        else -> null
                    },
            ),
        )
    }

/** Credentials applying to no request, for resolutions against public repositories. */
internal val noCredentials = RequestCredentials { Authentication.NotApplicable }

private val json = Json {
    prettyPrintIndent = "  "
    prettyPrint = true
}

/**
 * Directory the manifests of [assertUsingManifest] are recorded to instead of being compared, to
 * regenerate the recorded manifests from real resolutions: `./kotlin test
 * --jvm-args=-Dbazel.kmp.resolver.record-manifests=$PWD/testResources`, then review their diff.
 */
private val recordedManifestsDirectory: Path? =
    System.getProperty("bazel.kmp.resolver.record-manifests")?.let { Path.of(it) }

/**
 * Asserts using the given [manifestResourceFilepath] to improve readability of diff in tests, it's
 * easier to read a JSON diff. The manifest is the one `generate-bazel-manifest` writes for the
 * given inputs, by a development build of the resolver.
 */
internal fun assertUsingManifest(
    coordinates: List<String>,
    repositories: List<String>,
    platforms: Set<ManifestPlatform>,
    substitutions: Substitutions,
    libraries: List<MultiplatformVariant>,
    manifestResourceFilepath: String,
) {
    val actual =
        BazelManifest.of(
            inputs =
                ManifestInputs.of(
                    platforms = platforms,
                    coordinates = coordinates,
                    repositories = repositories,
                    substitutions = substitutions,
                    npmRegistry = null,
                    npmPackageVersions = emptyMap(),
                ),
            resolverVersion = "dev",
            variants = libraries,
        )
    val actualJson = json.encodeToString(BazelManifest.serializer(), actual)

    when (val directory = recordedManifestsDirectory) {
        null ->
            TestResourceReader.readResource(manifestResourceFilepath).use { expected ->
                val expectedManifest = expected.readAllBytes()
                assertEquals(expectedManifest.toString(Charsets.UTF_8), actualJson)
            }

        else ->
            directory
                .resolve(manifestResourceFilepath)
                .createParentDirectories()
                .writeText(actualJson)
    }
}
