package org.jetbrains.kmp.resolver

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KlibClassificationTest {
    @Test
    fun `the klib named after its variant is the main klib`() {
        assertEquals(
            KlibRole.MAIN,
            klibRole("ktor-client-curl-linuxx64", "ktor-client-curl-linuxx64-3.4.3.klib"),
        )
        assertEquals(
            KlibRole.MAIN,
            klibRole(
                "kotlinx-coroutines-core-iossimulatorarm64",
                "kotlinx-coroutines-core-iossimulatorarm64-1.10.2.klib",
            ),
        )
    }

    @Test
    fun `klibs published with a cinterop classifier bind C libraries`() {
        assertEquals(
            KlibRole.CINTEROP,
            klibRole(
                "ktor-client-curl-linuxx64",
                "ktor-client-curl-linuxx64-3.4.3-cinterop-libcurl.klib",
            ),
        )
        assertEquals(
            KlibRole.CINTEROP,
            klibRole("atomicfu-linuxx64", "atomicfu-linuxx64-0.26.1-cinterop-interop.klib"),
        )
    }

    @Test
    fun `cinterop klibs are recognized by their Gradle module metadata name`() {
        assertEquals(
            KlibRole.CINTEROP,
            klibRole(
                "ktor-client-curl-linuxx64",
                "ktor-client-curl-linuxX64Cinterop-libcurlMain-3.4.3.klib",
            ),
        )
    }

    @Test
    fun `a library named after cinterop keeps its main klib`() {
        assertEquals(
            KlibRole.MAIN,
            klibRole("cinterop-tools-linuxx64", "cinterop-tools-linuxx64-1.0.klib"),
        )
        assertEquals(
            KlibRole.CINTEROP,
            klibRole("cinterop-tools-linuxx64", "cinterop-tools-linuxx64-1.0-cinterop-zlib.klib"),
        )
    }

    @Test
    fun `the main klib comes first, then the cinterop klibs sorted by file name`() {
        val variantId = MultiplatformLibraryId("io.ktor", "ktor-client-curl-linuxx64", "3.4.3")

        val klibs =
            classifyKlibs(
                variantId,
                listOf(
                    "ktor-client-curl-linuxx64-3.4.3-cinterop-zlib.klib",
                    "ktor-client-curl-linuxx64-3.4.3.klib",
                    "ktor-client-curl-linuxx64-3.4.3-cinterop-libcurl.klib",
                ),
            ) {
                it
            }

        assertEquals(
            listOf(
                KlibRole.MAIN to "ktor-client-curl-linuxx64-3.4.3.klib",
                KlibRole.CINTEROP to "ktor-client-curl-linuxx64-3.4.3-cinterop-libcurl.klib",
                KlibRole.CINTEROP to "ktor-client-curl-linuxx64-3.4.3-cinterop-zlib.klib",
            ),
            klibs,
        )
    }

    @Test
    fun `a variant with several main klibs is rejected`() {
        val variantId = MultiplatformLibraryId("com.example", "hello-linuxx64", "1.0")

        val error =
            assertFailsWith<IllegalArgumentException> {
                classifyKlibs(
                    variantId,
                    listOf("hello-linuxx64-1.0.klib", "hello-linuxx64-1.0-extra.klib"),
                ) {
                    it
                }
            }

        assertContains(error.message.orEmpty(), "hello-linuxx64-1.0.klib")
        assertContains(error.message.orEmpty(), "hello-linuxx64-1.0-extra.klib")
    }

    @Test
    fun `a variant without main klib is rejected`() {
        val variantId = MultiplatformLibraryId("com.example", "hello-linuxx64", "1.0")

        assertFailsWith<IllegalArgumentException> {
            classifyKlibs(variantId, listOf("hello-linuxx64-1.0-cinterop-zlib.klib")) { it }
        }
    }
}
