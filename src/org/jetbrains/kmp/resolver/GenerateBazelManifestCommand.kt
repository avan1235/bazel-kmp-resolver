package org.jetbrains.kmp.resolver

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.outputStream
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.jetbrains.amper.dependency.resolution.MavenRepository
import org.jetbrains.kmp.resolver.auth.BasicRepositoryCredentials
import org.jetbrains.kmp.resolver.auth.CredentialHelperOption
import org.jetbrains.kmp.resolver.auth.CredentialHelperProvider
import org.jetbrains.kmp.resolver.auth.RepositoryCredentials
import org.jetbrains.kmp.resolver.auth.RequestCredentials
import org.jetbrains.kmp.resolver.auth.withCredentialHelperCredentials
import org.jetbrains.kmp.resolver.http.RetryPolicy
import org.jetbrains.kmp.resolver.http.withAuthenticatingHttpClient

/**
 * Commands that generates a manifest to be consumed by Bazel in a repository rule to produce
 * repositories which exposes the dependencies that have been resolved to a Bazel module.
 */
class GenerateBazelManifestCommand : SuspendingCliktCommand("generate-bazel-manifest") {
    private val inputs by ResolutionInputsOptions()

    private val outputManifest by
        option(
                "--output-manifest-file",
                help = "Path to the output manifest file.",
            )
            .convert { Path.of(it) }
            .required()

    private val repositoryCredentialsFile: Path? by
        option(
                "--repository-credentials-file",
                help =
                    """
                    Path to JSON repository credentials resolved by the caller, sent as basic authentication with the requests under
                    each repository URL.
                    """
                        .trimIndent(),
            )
            .convert { Path.of(it) }

    private val cacheDir: Path by
        option(
                "--cache-dir",
                help =
                    """
                    Directory of the cache of Maven metadata and artifacts, reused across runs. It never changes the manifest,
                    deleting it only makes the next run slower, and each ordered list of --repository gets its own cache in it.
                    """
                        .trimIndent(),
            )
            .convert { Path.of(it) }
            .required()

    private val nodeExecutable: Path? by
        option(
                "--node-executable",
                help =
                    "Path to the Node.js executable used to run npm when resolving klib-declared NPM dependencies, required with --platform=wasmjs.",
            )
            .convert { Path.of(it) }

    private val npmCliJs: Path? by
        option(
                "--npm-cli-js",
                help =
                    "Path to npm's `npm-cli.js` entry point, run with the Node.js executable (portable, unlike the `npm` wrapper scripts), required with --platform=wasmjs.",
            )
            .convert { Path.of(it) }

    private val allowedConcurrentConnections: Int by
        option(
                "--allowed-concurrent-connections",
                help = "Maximum number of concurrent artifact HTTP checks per repository host.",
            )
            .int()
            .default(32)

    private val requestTimeoutMillis: Long by
        option(
                "--request-timeout-ms",
                help = "HTTP request timeout in milliseconds when checking artifact URLs.",
            )
            .long()
            .default(30.seconds.inWholeMilliseconds)

    private val connectTimeoutMillis: Long by
        option(
                "--connect-timeout-ms",
                help =
                    "HTTP connect timeout in milliseconds, for artifact URL checks and downloads alike.",
            )
            .long()
            .default(10.seconds.inWholeMilliseconds)

    private val credentialHelpers: List<CredentialHelperOption> by
        option(
                "--credential-helper",
                help =
                    """
                    Bazel credential helper asked for the headers of every HTTP request, as `[scope=]path` like Bazel's
                    `--credential_helper`: `path` is absolute or a bare executable name looked up on PATH (`%workspace%` is not
                    expanded), `scope` is a domain name, optionally with a leading `*.` wildcard, restricting the helper to
                    matching hosts; the most specific scope wins. A host a helper applies to is authenticated by that helper alone,
                    the credentials of --repository-credentials-file serve the other hosts. Can be specified multiple times.
                    """
                        .trimIndent(),
            )
            .convert { CredentialHelperOption.parse(it) }
            .multiple(required = false)

    private val credentialHelperTimeoutMillis: Long by
        option(
                "--credential-helper-timeout-ms",
                help =
                    "Time in milliseconds a credential helper has to answer, after which the resolution fails.",
            )
            .long()
            .default(10.seconds.inWholeMilliseconds)

    private val credentialHelperCacheDurationMillis: Long by
        option(
                "--credential-helper-cache-duration-ms",
                help =
                    """
                    Time in milliseconds the headers of a credential helper are cached for a URI, when the helper gives no
                    `expires` timestamp.
                    """
                        .trimIndent(),
            )
            .long()
            .default(30.minutes.inWholeMilliseconds)

    override fun help(context: Context): String =
        "Resolves Maven coordinates for the given platforms into a manifest from which Bazel generates the targets of the resolved libraries."

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun run() {
        val manifestInputs = inputs.manifestInputs()
        // NPM dependencies are only declared by wasmJs klibs: Node.js is only needed to resolve
        // them
        val npmResolver =
            when {
                ManifestPlatform.WASM_JS !in inputs.platforms -> null
                else ->
                    NpmResolver(
                        nodeExecutable =
                            nodeExecutable
                                ?: throw UsageError(
                                    "--node-executable is required to resolve the NPM dependencies of the wasmjs platform",
                                ),
                        npmCliJs =
                            npmCliJs
                                ?: throw UsageError(
                                    "--npm-cli-js is required to resolve the NPM dependencies of the wasmjs platform",
                                ),
                        registryUrl = inputs.npmRegistry,
                        packageVersionOverrides = inputs.npmPackageVersions.toMap(),
                        workDir = outputManifest.toAbsolutePath().parent,
                    )
            }
        val repositoryCredentials =
            when (val credentialsFile = repositoryCredentialsFile) {
                null -> emptyList()
                else -> RepositoryCredentials.fromFile(credentialsFile)
            }
        val manifest =
            withCredentialHelperCredentials(
                provider = CredentialHelperProvider(credentialHelpers),
                timeout = credentialHelperTimeoutMillis.milliseconds,
                cacheDuration = credentialHelperCacheDurationMillis.milliseconds,
                clock = Clock.System,
                workingDir = null,
            ) { credentialHelperCredentials ->
                withAuthenticatingHttpClient(
                    // A matching credential helper wins over the credentials file, as in Bazel.
                    credentials =
                        RequestCredentials.firstApplicable(
                            listOf(
                                credentialHelperCredentials,
                                BasicRepositoryCredentials(repositoryCredentials),
                            ),
                        ),
                    connectTimeout = connectTimeoutMillis.milliseconds,
                ) { httpClient ->
                    val artifactResolver =
                        ArtifactUrlResolver(
                            httpClient = httpClient,
                            allowedConcurrentConnections = allowedConcurrentConnections,
                            requestTimeout = requestTimeoutMillis.milliseconds,
                            retryPolicy = RetryPolicy.DEFAULT,
                        )
                    val resolver =
                        MultiplatformResolver(
                            cachePath = cacheDir,
                            platforms = inputs.platforms,
                            repositories = inputs.repositories.map { MavenRepository(it) },
                            httpClient = httpClient,
                            artifactResolver = artifactResolver,
                            substitutions = inputs.substitutions.toMap(),
                            npmResolver = npmResolver,
                        )
                    BazelManifest.of(
                        inputs = manifestInputs,
                        resolverVersion = resolverVersion,
                        variants = resolver.resolve(inputs.coordinates),
                    )
                }
            }
        outputManifest.createParentDirectories()
        outputManifest.outputStream().use { output -> json.encodeToStream(manifest, output) }
    }

    companion object {
        private val json = Json {
            allowStructuredMapKeys = true
        }
    }
}
