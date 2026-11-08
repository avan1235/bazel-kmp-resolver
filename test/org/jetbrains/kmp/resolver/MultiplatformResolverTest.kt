package org.jetbrains.kmp.resolver

import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.extension
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.assertDoesNotThrow

// TODO: would be nice to mock Maven repositories to avoid real HTTP calls
class MultiplatformResolverTest {
    @Test
    fun `artifact integrity uses base64 encoded raw digest bytes`() {
        val sha256 =
            MultiplatformLibraryArtifactIntegrity.Sha256(
                hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            )

        val bazelIntegrity = sha256.asBazelIntegrityString()

        assertEquals("sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=", bazelIntegrity)
        assertEquals(
            sha256,
            MultiplatformLibraryArtifactIntegrity.fromBazelIntegrityString(bazelIntegrity),
        )
    }

    @Test
    fun `multi-repository resolution`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
                "https://dl.google.com/dl/android/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-cio:3.5.0",
                "io.ktor:ktor-client-core:3.4.3",
                "org.jetbrains.kotlin:kotlin-reflect:2.4.0",
            )
        val platforms = setOf(ManifestPlatform.WASM_JS)
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = platforms,
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            platforms = platforms,
            substitutions = emptyMap(),
            libraries = actual,
            manifestResourceFilepath = "manifest-ktor_resolution.json",
        )
    }

    @Test
    fun `do not resolve JVM nodes`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "org.jetbrains.kotlin:kotlin-reflect:2.4.0",
            )
        val platforms = setOf(ManifestPlatform.WASM_JS)
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = platforms,
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            platforms = platforms,
            substitutions = emptyMap(),
            libraries = actual,
            manifestResourceFilepath = "manifest-no_jvm.json",
        )
    }

    @Test
    fun `JVM-only library requested for a native platform has no variant`(): Unit = runBlocking {
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = listOf("https://repo1.maven.org/maven2"),
                substitutions = emptyMap(),
                platforms = setOf(ManifestPlatform.Klib(KlibPlatform.IOS_ARM64)),
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(listOf("org.jetbrains.kotlin:kotlin-reflect:2.4.0"))
            }

        assertEquals(emptyList(), actual)
    }

    @Test
    fun `substituted resolution`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-core:3.4.3",
            )
        val substitutions =
            mapOf(
                "org.jetbrains.kotlinx:kotlinx-coroutines-core" to
                    MultiplatformLibraryId.fromString(
                        "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1",
                    ),
                "org.jetbrains.kotlinx:kotlinx-coroutines-core-wasm-js" to
                    MultiplatformLibraryId.fromString(
                        "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-wasm-js:1.10.2-intellij-1",
                    ),
            )
        val platforms = setOf(ManifestPlatform.WASM_JS)
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = substitutions,
                platforms = platforms,
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            platforms = platforms,
            substitutions = substitutions,
            libraries = actual,
            manifestResourceFilepath = "manifest-with_substitutions.json",
        )
    }

    @Test
    fun `jvm resolution`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-cio:3.5.0",
                "org.jetbrains.kotlin:kotlin-reflect:2.4.0", // JVM-only library, its id is its
                // variantId
            )
        val platforms = setOf(ManifestPlatform.Jvm)
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = platforms,
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            platforms = platforms,
            substitutions = emptyMap(),
            libraries = actual,
            manifestResourceFilepath = "manifest-jvm_resolution.json",
        )
    }

    @Test
    fun `wasmjs and jvm resolution`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-core:3.4.3", // depends on the wasmJs-only `kotlinx-browser`,
                // skipped on JVM
            )
        // reversed on purpose: the manifest must not depend on the order platforms are requested in
        val platforms = setOf(ManifestPlatform.Jvm, ManifestPlatform.WASM_JS)
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = platforms,
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            platforms = platforms,
            substitutions = emptyMap(),
            libraries = actual,
            manifestResourceFilepath = "manifest-wasmjs_jvm.json",
        )
    }

    @Test
    fun `native resolution`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2", // `kotlin-stdlib` ships
                // with
                // the Kotlin/Native distribution, it is in neither native graph
            )
        val platforms =
            setOf(
                ManifestPlatform.Klib(KlibPlatform.LINUX_X64),
                ManifestPlatform.Klib(KlibPlatform.IOS_ARM64),
            )
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = platforms,
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            platforms = platforms,
            substitutions = emptyMap(),
            libraries = actual,
            manifestResourceFilepath = "manifest-native_linux_x64.json",
        )
    }

    @Test
    fun `native resolution of a library binding a C library`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-curl:3.4.3", // its klib comes with a `libcurl` cinterop klib
            )
        val platforms = setOf(ManifestPlatform.Klib(KlibPlatform.LINUX_X64))
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = platforms,
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            platforms = platforms,
            substitutions = emptyMap(),
            libraries = actual,
            manifestResourceFilepath = "manifest-native_cinterop_curl.json",
        )
    }

    @Test
    fun `android resolution`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
                "https://dl.google.com/dl/android/maven2",
            )
        val coordinates =
            listOf(
                "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2", // a jar
                "androidx.annotation:annotation:1.9.1", // a KMP library without Android variant,
                // falling back to its JVM variant
                "androidx.annotation:annotation-experimental:1.4.1", // an aar
            )
        val platforms = setOf(ManifestPlatform.Android)
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = platforms,
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            platforms = platforms,
            substitutions = emptyMap(),
            libraries = actual,
            manifestResourceFilepath = "manifest-android.json",
        )
    }

    @Test
    fun `a library requested with and without an explicit packaging type is one variant`(): Unit =
        withLocalMavenRepository { repository ->
            runBlocking {
                val coordinates =
                    listOf(
                        LOCAL_TYPED_AAR_DEPENDENT_COORDINATES,
                        LOCAL_UNTYPED_AAR_DEPENDENT_COORDINATES,
                    )
                val actual =
                    testMultiplatformResolver(
                        cachePath = createTempDirectory("resolution-cache"),
                        repositories = listOf(repository),
                        substitutions = emptyMap(),
                        platforms = setOf(ManifestPlatform.Android),
                        credentials = noCredentials,
                    ) { resolver ->
                        resolver.resolve(coordinates)
                    }

                val variants = actual.filterIsInstance<MultiplatformVariant.Android>()
                assertEquals(actual.size, variants.size, "$actual")
                assertEquals(3, variants.size, "$variants")
                assertEquals(
                    setOf(
                        LOCAL_AAR_COORDINATES,
                        LOCAL_TYPED_AAR_DEPENDENT_COORDINATES,
                        LOCAL_UNTYPED_AAR_DEPENDENT_COORDINATES,
                    ),
                    variants.map { it.id.gav }.toSet(),
                )
                val widget = variants.single { it.id.gav == LOCAL_AAR_COORDINATES }
                assertEquals(AndroidPackaging.AAR, widget.packaging)
                assertEquals(
                    listOf("com/example/widget/1.0/widget-1.0.aar"),
                    widget.artifact.urls.map { it.removePrefix(repository) },
                )
                variants
                    .filter { it.id.gav != LOCAL_AAR_COORDINATES }
                    .forEach { user ->
                        assertEquals(
                            listOf("com.example:widget"),
                            user.exportedDependencies,
                            user.id.gav,
                        )
                        assertEquals(emptyList(), user.dependencies, user.id.gav)
                    }
            }
        }

    @Test
    fun `KMP umbrella requested as AAR uses its Android variant`(): Unit =
        withLocalMavenRepository { repository ->
            runBlocking {
                val actual =
                    testMultiplatformResolver(
                        cachePath = createTempDirectory("resolution-cache"),
                        repositories = listOf(repository),
                        substitutions = emptyMap(),
                        platforms = setOf(ManifestPlatform.Android),
                        credentials = noCredentials,
                    ) { resolver ->
                        resolver.resolve(
                            listOf(LOCAL_KMP_AAR_COORDINATES, LOCAL_KMP_AAR_DEPENDENT_COORDINATES),
                        )
                    }

                val variants = actual.filterIsInstance<MultiplatformVariant.Android>()
                assertEquals(actual.size, variants.size, "$actual")
                assertEquals(2, variants.size, "$variants")
                val widget = variants.single { it.id.gav == LOCAL_KMP_AAR_COORDINATES }
                assertEquals(LOCAL_KMP_AAR_VARIANT_COORDINATES, widget.variantId.gav)
                assertEquals(AndroidPackaging.AAR, widget.packaging)
                assertEquals(
                    listOf("com/example/kmp-widget-android/1.0/kmp-widget-android-1.0.aar"),
                    widget.artifact.urls.map { it.removePrefix(repository) },
                )
                val user = variants.single { it.id.gav == LOCAL_KMP_AAR_DEPENDENT_COORDINATES }
                assertEquals(listOf("com.example:kmp-widget"), user.exportedDependencies)
            }
        }

    @Test
    fun `KMP library whose Android variant only declares dependencies redirects to them`(): Unit =
        withLocalMavenRepository { repository ->
            runBlocking {
                val actual =
                    testMultiplatformResolver(
                        cachePath = createTempDirectory("resolution-cache"),
                        repositories = listOf(repository),
                        substitutions = emptyMap(),
                        platforms = setOf(ManifestPlatform.Android, ManifestPlatform.Jvm),
                        credentials = noCredentials,
                    ) { resolver ->
                        resolver.resolve(listOf(LOCAL_KMP_REDIRECT_COORDINATES))
                    }

                val libraries = actual.asLibraries().mapKeys { (id, _) -> id.gav }
                assertEquals(
                    setOf(
                        LOCAL_KMP_REDIRECT_COORDINATES,
                        LOCAL_KMP_AAR_COORDINATES,
                        LOCAL_LIBRARY_COORDINATES,
                    ),
                    libraries.keys,
                )

                // the Android variant of the library is its dependencies, in the scopes they are
                // declared with; the JVM one is the jar of its own module, as usual
                val ui = libraries.getValue(LOCAL_KMP_REDIRECT_COORDINATES).variants
                assertEquals(listOf("android", "jvm"), ui.map { it.platformName })
                val redirect = ui.filterIsInstance<MultiplatformVariant.Redirect>().single()
                assertEquals(RedirectPlatform.ANDROID, redirect.platform)
                assertEquals(LOCAL_KMP_REDIRECT_COORDINATES, redirect.id.gav)
                assertEquals(LOCAL_KMP_REDIRECT_COORDINATES, redirect.variantId.gav)
                assertEquals(listOf("com.example:kmp-widget"), redirect.exportedDependencies)
                assertEquals(listOf("com.example:hello"), redirect.dependencies)
                val jvm = ui.filterIsInstance<MultiplatformVariant.Jvm>().single()
                assertEquals(LOCAL_KMP_REDIRECT_JVM_VARIANT_COORDINATES, jvm.variantId.gav)
                assertEquals(
                    listOf("com/example/kmp-ui-jvm/1.0/kmp-ui-jvm-1.0.jar"),
                    jvm.jar.urls.map { it.removePrefix(repository) },
                )

                // the libraries the redirect stands for are Android libraries of the manifest
                val widget =
                    libraries
                        .getValue(LOCAL_KMP_AAR_COORDINATES)
                        .variants
                        .filterIsInstance<MultiplatformVariant.Android>()
                        .single()
                assertEquals(LOCAL_KMP_AAR_VARIANT_COORDINATES, widget.variantId.gav)
                assertEquals(AndroidPackaging.AAR, widget.packaging)
                val hello =
                    libraries
                        .getValue(LOCAL_LIBRARY_COORDINATES)
                        .variants
                        .filterIsInstance<MultiplatformVariant.Android>()
                        .single()
                assertEquals(AndroidPackaging.JAR, hello.packaging)

                // the manifest tells the variant apart by its type, with the platform it is for
                assertEquals(
                    """{"type":"redirect","platform":"android","id":"com.example:kmp-ui:1.0","variantId":"com.example:kmp-ui:1.0","dependencies":["com.example:hello"],"exportedDependencies":["com.example:kmp-widget"]}""",
                    Json.encodeToString(MultiplatformVariant.serializer(), redirect),
                )
            }
        }

    @Test
    fun `POM-only library redirects to its dependencies`(): Unit =
        withLocalMavenRepository { repository ->
            runBlocking {
                val actual =
                    testMultiplatformResolver(
                        cachePath = createTempDirectory("resolution-cache"),
                        repositories = listOf(repository),
                        substitutions = emptyMap(),
                        platforms = setOf(ManifestPlatform.Jvm),
                        credentials = noCredentials,
                    ) { resolver ->
                        resolver.resolve(listOf(LOCAL_POM_ONLY_COORDINATES))
                    }

                assertEquals(
                    setOf(LOCAL_POM_ONLY_COORDINATES, LOCAL_LIBRARY_COORDINATES),
                    actual.map { it.id.gav }.toSet(),
                )
                val aggregate = actual.filterIsInstance<MultiplatformVariant.Redirect>().single()
                assertEquals(RedirectPlatform.JVM, aggregate.platform)
                assertEquals(LOCAL_POM_ONLY_COORDINATES, aggregate.id.gav)
                assertEquals(listOf("com.example:hello"), aggregate.exportedDependencies)
                assertEquals(emptyList(), aggregate.dependencies)
                val hello = actual.filterIsInstance<MultiplatformVariant.Jvm>().single()
                assertEquals(LOCAL_LIBRARY_COORDINATES, hello.id.gav)
                assertEquals(
                    listOf("com/example/hello/1.0/hello-1.0.jar"),
                    hello.jar.urls.map { it.removePrefix(repository) },
                )
            }
        }

    @Test
    fun `android and jvm resolution`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
            )
        val platforms = setOf(ManifestPlatform.Jvm, ManifestPlatform.Android)
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = platforms,
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            platforms = platforms,
            substitutions = emptyMap(),
            libraries = actual,
            manifestResourceFilepath = "manifest-android_jvm.json",
        )
    }

    @Test
    fun `all platforms resolution`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
            )
        // reversed on purpose: variants are listed in the order of SUPPORTED_PLATFORMS whatever
        // the order platforms are requested in
        val platforms = SUPPORTED_PLATFORMS.reversed().toSet()
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = platforms,
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            platforms = platforms,
            substitutions = emptyMap(),
            libraries = actual,
            manifestResourceFilepath = "manifest-all_platforms.json",
        )
    }

    @Test
    fun `substituted wasmjs and jvm resolution`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-core:3.4.3",
            )
        val substitutions =
            mapOf(
                "org.jetbrains.kotlinx:kotlinx-coroutines-core" to
                    MultiplatformLibraryId.fromString(
                        "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1",
                    ),
                // substituting the KMP library covers its platform-specific variants
            )
        val platforms = setOf(ManifestPlatform.WASM_JS, ManifestPlatform.Jvm)
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = substitutions,
                platforms = platforms,
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            platforms = platforms,
            substitutions = substitutions,
            libraries = actual,
            manifestResourceFilepath = "manifest-with_substitutions_jvm.json",
        )
    }

    @Test
    fun `substituted library without KMP parent`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-core:3.4.3", // depends on `kotlinx-coroutines-slf4j` on JVM, a
                // JVM-only library without KMP parent
            )
        val substitutions =
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
        val platforms = setOf(ManifestPlatform.WASM_JS, ManifestPlatform.Jvm)
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = substitutions,
                platforms = platforms,
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            platforms = platforms,
            substitutions = substitutions,
            libraries = actual,
            manifestResourceFilepath = "manifest-substituted_without_kmp_parent.json",
        )
    }

    @Test
    fun `substitute declared directly along with the substituted library`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "io.ktor:ktor-client-core:3.4.3", // depends on `kotlinx-coroutines-core`,
                // substituted below
                "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1", // the substitute itself
            )
        val substitutions =
            mapOf(
                "org.jetbrains.kotlinx:kotlinx-coroutines-core" to
                    MultiplatformLibraryId.fromString(
                        "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1",
                    ),
                "org.jetbrains.kotlinx:kotlinx-coroutines-core-wasm-js" to
                    MultiplatformLibraryId.fromString(
                        "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-wasm-js:1.10.2-intellij-1",
                    ),
                // a redundant variant key, as some generators emit: harmless
            )
        val platforms = setOf(ManifestPlatform.WASM_JS, ManifestPlatform.Jvm)
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = substitutions,
                platforms = platforms,
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            platforms = platforms,
            substitutions = substitutions,
            libraries = actual,
            manifestResourceFilepath = "manifest-with_substitutions_and_substitute.json",
        )
    }

    @Test
    fun `platform variant also requested directly by a POM-based library`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                // a pom-only JVM library depending directly on `kotlinx-coroutines-core-jvm` (of
                // the IntelliJ fork), listed first
                // so that the dependency graph walk meets that variant as its child before meeting
                // it as the KMP library's one
                "com.google.devtools.ksp:symbol-processing-aa-embeddable:2.3.6",
                "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1",
            )
        val platforms = setOf(ManifestPlatform.Jvm)
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = platforms,
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            platforms = platforms,
            substitutions = emptyMap(),
            libraries = actual,
            manifestResourceFilepath = "manifest-variant_requested_directly.json",
        )
    }

    @Test
    fun `dependency on JVM-only dependencies are excluded`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
                "https://cache-redirector.jetbrains.com/packages.jetbrains.team/maven/p/grazi/grazie-platform-public",
            )
        val coordinates =
            listOf(
                "ai.jetbrains.code.files:code-files-model:1.0.0-beta.167",
            )
        val platforms = setOf(ManifestPlatform.WASM_JS)
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = platforms,
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            platforms = platforms,
            substitutions = emptyMap(),
            libraries = actual,
            manifestResourceFilepath = "manifest-no_jvm_deps.json",
        )
    }

    @Test
    fun `klib-declared npm dependencies are resolved`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "org.jetbrains.kotlinx:kotlinx-datetime:0.7.1", // its klib embeds a package.json
                // requiring `@js-joda/core`
            )
        val platforms = setOf(ManifestPlatform.WASM_JS)
        val actual =
            testMultiplatformResolver(
                cachePath = createTempDirectory("resolution-cache"),
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = platforms,
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }

        assertUsingManifest(
            coordinates = coordinates,
            repositories = repositories,
            platforms = platforms,
            substitutions = emptyMap(),
            libraries = actual,
            manifestResourceFilepath = "manifest-npm_datetime.json",
        )
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `ensure hash of artifacts are resolved`(): Unit = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "com.github.ajalt.clikt:clikt-core:5.0.3", // previous implementation was unable to
                // resolve the hahs of
                // `com/github/ajalt/clikt/clikt-core-wasm-js/5.0.3/clikt-core-wasm-js-5.0.3-sources.jar`
            )
        val cache = createTempDirectory("resolution-cache")
        cache.deleteRecursively()
        testMultiplatformResolver(
            cachePath = cache,
            repositories = repositories,
            substitutions = emptyMap(),
            platforms = setOf(ManifestPlatform.WASM_JS),
            credentials = noCredentials,
        ) { resolver ->
            assertDoesNotThrow {
                resolver.resolve(coordinates)
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `a reused cache resolves as an empty one, even with corrupted artifacts`() = runBlocking {
        val repositories =
            listOf(
                "https://repo1.maven.org/maven2",
            )
        val coordinates =
            listOf(
                "org.jetbrains.kotlinx:kotlinx-datetime:0.7.1",
            )
        val cache = createTempDirectory("resolution-cache")
        val resolve: suspend () -> List<MultiplatformVariant> = {
            testMultiplatformResolver(
                cachePath = cache,
                repositories = repositories,
                substitutions = emptyMap(),
                platforms = setOf(ManifestPlatform.WASM_JS, ManifestPlatform.Jvm),
                credentials = noCredentials,
            ) { resolver ->
                resolver.resolve(coordinates)
            }
        }

        val cold = resolve()
        val warm = resolve()
        val cachedArtifacts =
            cache.walk().filter { it.extension == "klib" || it.extension == "jar" }.toList()
        assertTrue(cachedArtifacts.isNotEmpty(), "no artifact was cached in $cache")
        cachedArtifacts.forEach { it.writeText("") }
        val corrupted = resolve()

        assertEquals(cold, warm)
        assertEquals(cold, corrupted)
    }
}
