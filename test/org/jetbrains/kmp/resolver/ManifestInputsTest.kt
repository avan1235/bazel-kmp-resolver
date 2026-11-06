package org.jetbrains.kmp.resolver

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ManifestInputsTest {
    private val linuxX64 = ManifestPlatform.Klib(KlibPlatform.LINUX_X64)

    private val defaultCoordinates =
        listOf(
            "io.ktor:ktor-client-core:3.4.3",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
        )

    private val defaultRepositories =
        listOf(
            "https://repo1.maven.org/maven2",
            "https://dl.google.com/dl/android/maven2",
        )

    private val defaultSubstitutions: Substitutions =
        mapOf(
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" to
                MultiplatformLibraryId.fromString(
                    "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1",
                ),
            "org.jetbrains.kotlinx:kotlinx-coroutines-slf4j" to
                MultiplatformLibraryId.fromString(
                    "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-slf4j:1.10.2-intellij-1",
                ),
        )

    private val defaultNpmPackageVersions = mapOf("ws" to "8.18.3", "@js-joda/core" to "5.6.5")

    private fun inputs(
        platforms: List<ManifestPlatform> = listOf(linuxX64, ManifestPlatform.Jvm),
        coordinates: List<String> = defaultCoordinates,
        repositories: List<String> = defaultRepositories,
        substitutions: Substitutions = defaultSubstitutions,
        npmRegistry: String? = null,
        npmPackageVersions: Map<String, String> = defaultNpmPackageVersions,
    ): ManifestInputs =
        ManifestInputs.of(
            platforms = platforms,
            coordinates = coordinates,
            repositories = repositories,
            substitutions = substitutions,
            npmRegistry = npmRegistry,
            npmPackageVersions = npmPackageVersions,
        )

    @Test
    fun `inputs are hashed as their canonical JSON`() {
        val canonicalJson =
            """
            {"platforms":["linux_x64","jvm"],
            "coordinates":["io.ktor:ktor-client-core:3.4.3","org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"],
            "repositories":["https://repo1.maven.org/maven2","https://dl.google.com/dl/android/maven2"],
            "substitutions":["org.jetbrains.kotlinx:kotlinx-coroutines-core=org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1","org.jetbrains.kotlinx:kotlinx-coroutines-slf4j=org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-slf4j:1.10.2-intellij-1"],
            "npmRegistry":"https://registry.npmjs.org",
            "npmPackageVersions":["@js-joda/core=5.6.5","ws=8.18.3"]}
            """
                .trimIndent()
                .replace("\n", "")

        assertEquals(sha256(canonicalJson), inputs().hash())
    }

    @Test
    fun `the hash is a lowercase hexadecimal sha256 digest`() {
        assertTrue(Regex("[0-9a-f]{64}").matches(inputs().hash()), inputs().hash())
    }

    @Test
    fun `the hash depends neither on the order nor on the duplicates of unordered inputs`() {
        val shuffled =
            inputs(
                platforms = listOf(ManifestPlatform.Jvm, linuxX64, ManifestPlatform.Jvm),
                coordinates = defaultCoordinates.reversed() + defaultCoordinates.first(),
                substitutions = defaultSubstitutions.entries.reversed().associate { it.toPair() },
                npmPackageVersions =
                    defaultNpmPackageVersions.entries.reversed().associate { it.toPair() },
            )

        assertEquals(inputs().hash(), shuffled.hash())
    }

    @Test
    fun `the default NPM registry is the same input as no registry`() {
        assertEquals(
            inputs(npmRegistry = null).hash(),
            inputs(npmRegistry = "https://registry.npmjs.org").hash(),
        )
    }

    @Test
    fun `the hash changes with every input`() {
        val changedInputs =
            listOf(
                inputs(platforms = listOf(linuxX64)),
                inputs(coordinates = defaultCoordinates.take(1)),
                inputs(repositories = defaultRepositories.take(1)),
                // repositories are looked up in order, their order is an input
                inputs(repositories = defaultRepositories.reversed()),
                inputs(substitutions = emptyMap()),
                inputs(npmRegistry = "https://npm.example.com"),
                inputs(npmPackageVersions = emptyMap()),
            )

        changedInputs.forEach { changed ->
            assertNotEquals(inputs().hash(), changed.hash(), "$changed")
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).toHexString()
}
