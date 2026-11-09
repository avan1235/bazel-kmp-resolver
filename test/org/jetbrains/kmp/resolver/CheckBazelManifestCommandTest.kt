package org.jetbrains.kmp.resolver

import com.github.ajalt.clikt.command.test
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class CheckBazelManifestCommandTest {
    @Test
    fun `a generated manifest is up to date with the inputs it is generated from`(): Unit =
        withLocalMavenRepository { repository ->
            runBlocking {
                val inputs = resolutionInputs(repository)
                val manifestFile = generateManifest(inputs)

                val result =
                    BazelKmpResolverCommand()
                        .test(
                            listOf("check-bazel-manifest", "--manifest-file=$manifestFile") +
                                inputs,
                        )

                assertEquals(0, result.statusCode, result.output)
                assertContains(result.stdout, "is up to date")
                val manifest =
                    Json.decodeFromString(BazelManifest.serializer(), manifestFile.readText())
                assertEquals(SCHEMA_VERSION, manifest.schemaVersion)
                assertEquals("dev", manifest.resolverVersion)
                assertEquals(listOf("jvm"), manifest.askedPlatforms)
                assertEquals(
                    ManifestInputs.of(
                            platforms = setOf(ManifestPlatform.Jvm),
                            coordinates = listOf(LOCAL_LIBRARY_COORDINATES),
                            repositories = listOf(repository),
                            substitutions = emptyMap(),
                            npmRegistry = null,
                            npmPackageVersions = emptyMap(),
                        )
                        .hash(),
                    manifest.inputsHash,
                )
                assertIs<MultiplatformVariant.Jvm>(
                    manifest.libraries.values.single().variants.single(),
                )
            }
        }

    @Test
    fun `a manifest generated from other inputs is stale`(): Unit =
        withLocalMavenRepository { repository ->
            runBlocking {
                val inputs = resolutionInputs(repository)
                val manifestFile = generateManifest(inputs)

                val result =
                    BazelKmpResolverCommand()
                        .test(
                            listOf("check-bazel-manifest", "--manifest-file=$manifestFile") +
                                inputs +
                                "--coordinate=org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
                        )

                assertNotEquals(0, result.statusCode, result.output)
                assertContains(result.stderr, "is stale")
                assertContains(result.stderr, "inputsHash")
            }
        }

    @Test
    fun `a manifest of another schema version is rejected`(): Unit = runBlocking {
        val manifestFile =
            createTempDirectory("check-bazel-manifest").resolve("manifest.json").apply {
                // the first schema had no version
                writeText(
                    """{"askedCoordinates":["com.example:hello:1.0"],"askedRepositories":["https://repo1.maven.org/maven2"],"libraries":{}}""",
                )
            }

        val result =
            BazelKmpResolverCommand()
                .test(
                    listOf("check-bazel-manifest", "--manifest-file=$manifestFile") +
                        resolutionInputs("https://repo1.maven.org/maven2"),
                )

        assertNotEquals(0, result.statusCode, result.output)
        assertContains(result.stderr, "schema version 1")
    }

    @Test
    fun `a file that is not a manifest is rejected`(): Unit = runBlocking {
        val manifestFile =
            createTempDirectory("check-bazel-manifest").resolve("manifest.json").apply {
                writeText("[]")
            }

        val result =
            BazelKmpResolverCommand()
                .test(
                    listOf("check-bazel-manifest", "--manifest-file=$manifestFile") +
                        resolutionInputs("https://repo1.maven.org/maven2"),
                )

        assertNotEquals(0, result.statusCode, result.output)
        assertContains(result.stderr, "is not a manifest")
    }

    @Test
    fun `ignored published checksums are a resolution input recorded in the manifest`(): Unit =
        withLocalMavenRepository { repository ->
            runBlocking {
                val ignoring =
                    "--ignore-published-checksums=$LOCAL_MISMATCHED_CHECKSUMS_COORDINATES"
                val inputs =
                    resolutionInputs(repository) +
                        "--coordinate=$LOCAL_MISMATCHED_CHECKSUMS_COORDINATES"
                val manifestFile = generateManifest(inputs + ignoring)

                val manifestText = manifestFile.readText()
                assertContains(
                    manifestText,
                    """"askedIgnoredPublishedChecksums":["$LOCAL_MISMATCHED_CHECKSUMS_COORDINATES"]""",
                )
                val manifest = Json.decodeFromString(BazelManifest.serializer(), manifestText)
                assertEquals(
                    listOf(LOCAL_MISMATCHED_CHECKSUMS_COORDINATES),
                    manifest.askedIgnoredPublishedChecksums,
                )
                val upToDate =
                    BazelKmpResolverCommand()
                        .test(
                            listOf("check-bazel-manifest", "--manifest-file=$manifestFile") +
                                inputs +
                                ignoring,
                        )
                assertEquals(0, upToDate.statusCode, upToDate.output)
                val stale =
                    BazelKmpResolverCommand()
                        .test(
                            listOf("check-bazel-manifest", "--manifest-file=$manifestFile") +
                                inputs,
                        )
                assertNotEquals(0, stale.statusCode, stale.output)
                assertContains(stale.stderr, "is stale")
            }
        }

    @Test
    fun `an ignored published checksum must name a library with its version`(): Unit = runBlocking {
        val result =
            BazelKmpResolverCommand()
                .test(
                    listOf(
                        "check-bazel-manifest",
                        "--manifest-file=/dev/null",
                        "--ignore-published-checksums=com.example:corrupt",
                    ) + resolutionInputs("https://repo1.maven.org/maven2"),
                )

        assertNotEquals(0, result.statusCode, result.output)
        assertContains(result.stderr, "'com.example:corrupt' is not group:artifact:version")
    }

    private fun resolutionInputs(repository: String): List<String> =
        listOf(
            "--coordinate=$LOCAL_LIBRARY_COORDINATES",
            "--repository=$repository",
            "--platform=jvm",
        )

    /** Runs `generate-bazel-manifest` with the given resolution inputs, returning the manifest. */
    private suspend fun generateManifest(inputs: List<String>): Path {
        val directory = createTempDirectory("check-bazel-manifest")
        val manifestFile = directory.resolve("manifest.json")
        val result =
            BazelKmpResolverCommand()
                .test(
                    listOf(
                        "generate-bazel-manifest",
                        "--cache-dir=${directory.resolve("cache")}",
                        "--output-manifest-file=$manifestFile",
                    ) + inputs,
                )
        assertEquals(0, result.statusCode, result.output)
        return manifestFile
    }
}
