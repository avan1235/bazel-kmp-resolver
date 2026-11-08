package org.jetbrains.kmp.resolver

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SourceJarSelectionTest {
    private val uiAndroid =
        MultiplatformLibraryId(
            groupId = "androidx.compose.ui",
            artifactId = "ui-android",
            version = "1.11.0-beta01",
        )

    @Test
    fun `a variant without source jar has no sources`() {
        assertNull(selectSourceJar(uiAndroid, emptyList<String>()) { it })
    }

    @Test
    fun `the single source jar of a variant is its sources`() {
        assertEquals(
            "clikt-core-wasm-js-5.0.3-sources.jar",
            selectSourceJar(
                MultiplatformLibraryId("com.github.ajalt.clikt", "clikt-core-wasm-js", "5.0.3"),
                listOf("clikt-core-wasm-js-5.0.3-sources.jar"),
            ) {
                it
            },
        )
    }

    @Test
    fun `the sources classifier jar is picked among the source jars of the samples`() {
        assertEquals(
            "ui-android-1.11.0-beta01-sources.jar",
            selectSourceJar(
                uiAndroid,
                listOf(
                    "ui-android-1.11.0-beta01-samples-sources.jar",
                    "ui-android-1.11.0-beta01-sources.jar",
                ),
            ) {
                it
            },
        )
    }

    @Test
    fun `several source jars without the sources classifier are an error`() {
        val error =
            assertFailsWith<IllegalStateException> {
                selectSourceJar(
                    uiAndroid,
                    listOf(
                        "ui-android-1.11.0-beta01-samples-sources.jar",
                        "ui-android-1.11.0-beta01-test-sources.jar",
                    ),
                ) {
                    it
                }
            }

        assertContains(error.message.orEmpty(), "androidx.compose.ui:ui-android:1.11.0-beta01")
        assertContains(error.message.orEmpty(), "ui-android-1.11.0-beta01-sources.jar")
    }
}
