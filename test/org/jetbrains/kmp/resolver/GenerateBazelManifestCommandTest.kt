package org.jetbrains.kmp.resolver

import com.github.ajalt.clikt.command.test
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class GenerateBazelManifestCommandTest {
    @Test
    fun `native platforms are resolved without Node js`(): Unit =
        withLocalMavenRepository { repository ->
            runBlocking {
                val directory = createTempDirectory("generate-bazel-manifest")
                val manifestFile = directory.resolve("manifest.json")

                val result =
                    BazelKmpResolverCommand()
                        .test(
                            listOf(
                                "generate-bazel-manifest",
                                "--coordinate=$LOCAL_LIBRARY_COORDINATES",
                                "--repository=$repository",
                                "--platform=linux_x64",
                                "--cache-dir=${directory.resolve("cache")}",
                                "--output-manifest-file=$manifestFile",
                            ),
                        )

                assertEquals(0, result.statusCode, result.output)
                val manifest =
                    Json.decodeFromString(BazelManifest.serializer(), manifestFile.readText())
                assertEquals(listOf("linux_x64"), manifest.askedPlatforms)
                // a JVM library publishes no klib
                assertEquals(emptyMap(), manifest.libraries)
            }
        }

    @Test
    fun `the wasmjs platform requires Node js`(): Unit = runBlocking {
        val directory = createTempDirectory("generate-bazel-manifest")

        val result =
            BazelKmpResolverCommand()
                .test(
                    listOf(
                        "generate-bazel-manifest",
                        "--coordinate=$LOCAL_LIBRARY_COORDINATES",
                        "--repository=https://repo1.maven.org/maven2",
                        "--platform=jvm",
                        "--platform=wasmjs",
                        "--cache-dir=${directory.resolve("cache")}",
                        "--output-manifest-file=${directory.resolve("manifest.json")}",
                    ),
                )

        assertNotEquals(0, result.statusCode, result.output)
        assertContains(result.stderr, "--node-executable")
    }

    @Test
    fun `an unknown platform is rejected, listing the supported ones`(): Unit = runBlocking {
        val directory = createTempDirectory("generate-bazel-manifest")

        val result =
            BazelKmpResolverCommand()
                .test(
                    listOf(
                        "generate-bazel-manifest",
                        "--coordinate=$LOCAL_LIBRARY_COORDINATES",
                        "--repository=https://repo1.maven.org/maven2",
                        "--platform=macos_x64",
                        "--cache-dir=${directory.resolve("cache")}",
                        "--output-manifest-file=${directory.resolve("manifest.json")}",
                    ),
                )

        assertNotEquals(0, result.statusCode, result.output)
        assertContains(result.stderr, "macos_x64")
        SUPPORTED_PLATFORMS.forEach { platform -> assertContains(result.stderr, platform.cliName) }
    }

    @Test
    fun `the resolver version is printed`(): Unit = runBlocking {
        val result = BazelKmpResolverCommand().test(listOf("--version"))

        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stdout, "dev")
    }
}
