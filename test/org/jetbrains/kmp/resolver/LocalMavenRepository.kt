package org.jetbrains.kmp.resolver

import com.sun.net.httpserver.HttpHandler
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import org.jetbrains.kmp.resolver.http.respond
import org.jetbrains.kmp.resolver.http.urlOf
import org.jetbrains.kmp.resolver.http.withLocalHttpServer

/** Coordinates of the JVM library served by the repository of [withLocalMavenRepository]. */
internal const val LOCAL_LIBRARY_COORDINATES = "com.example:hello:1.0"

/**
 * Coordinates of the Android library served by the repository of [withLocalMavenRepository]: a POM
 * of `aar` packaging along with its `.aar`.
 */
internal const val LOCAL_AAR_COORDINATES = "com.example:widget:1.0"

/**
 * Coordinates of the JVM library served by the repository of [withLocalMavenRepository] depending
 * on [LOCAL_AAR_COORDINATES] with an explicit `<type>aar</type>`, as the AndroidX POMs do.
 */
internal const val LOCAL_TYPED_AAR_DEPENDENT_COORDINATES = "com.example:widget-typed-user:1.0"

/**
 * Coordinates of the JVM library served by the repository of [withLocalMavenRepository] depending
 * on [LOCAL_AAR_COORDINATES] without a `<type>`, leaving its packaging to the POM of the library.
 */
internal const val LOCAL_UNTYPED_AAR_DEPENDENT_COORDINATES = "com.example:widget-user:1.0"

internal const val LOCAL_KMP_AAR_COORDINATES = "com.example:kmp-widget:1.0"
internal const val LOCAL_KMP_AAR_VARIANT_COORDINATES = "com.example:kmp-widget-android:1.0"
internal const val LOCAL_KMP_AAR_DEPENDENT_COORDINATES = "com.example:kmp-widget-user:1.0"

/**
 * Coordinates of the KMP library served by the repository of [withLocalMavenRepository] whose
 * Android variants have no file and only declare dependencies, on [LOCAL_KMP_AAR_COORDINATES] and,
 * at runtime, on [LOCAL_LIBRARY_COORDINATES], as the Android variants of the JetBrains Compose
 * libraries redirect to the AndroidX Compose ones; its JVM variant is the jar of
 * [LOCAL_KMP_REDIRECT_JVM_VARIANT_COORDINATES], published in a module of its own.
 */
internal const val LOCAL_KMP_REDIRECT_COORDINATES = "com.example:kmp-ui:1.0"
internal const val LOCAL_KMP_REDIRECT_JVM_VARIANT_COORDINATES = "com.example:kmp-ui-jvm:1.0"

/**
 * Coordinates of the POM-only library served by the repository of [withLocalMavenRepository]: a POM
 * of `pom` packaging without artifact, depending on [LOCAL_LIBRARY_COORDINATES].
 */
internal const val LOCAL_POM_ONLY_COORDINATES = "com.example:aggregate:1.0"

private val DIGEST_ALGORITHMS =
    mapOf("sha512" to "SHA-512", "sha256" to "SHA-256", "sha1" to "SHA-1", "md5" to "MD5")

/**
 * Runs [block] with the URL of a Maven repository served on the loopback interface, publishing the
 * JVM library [LOCAL_LIBRARY_COORDINATES] as a POM and a jar along with their checksums, the
 * Android library [LOCAL_AAR_COORDINATES] as a POM and an aar, and the JVM libraries
 * [LOCAL_TYPED_AAR_DEPENDENT_COORDINATES] and [LOCAL_UNTYPED_AAR_DEPENDENT_COORDINATES] depending
 * on it, the KMP libraries [LOCAL_KMP_AAR_COORDINATES] and [LOCAL_KMP_REDIRECT_COORDINATES] with
 * their platform-specific modules, and the POM-only library [LOCAL_POM_ONLY_COORDINATES], and
 * stopped once [block] returns.
 */
internal fun <T> withLocalMavenRepository(block: (String) -> T): T {
    val repository =
        createTempDirectory("local-maven-repository").also {
            publishLibrary(it)
            publishAarLibraries(it)
            publishKmpAarLibraries(it)
            publishKmpRedirectLibrary(it)
            publishPomOnlyLibrary(it)
        }
    val handler = HttpHandler { exchange ->
        val path = exchange.requestURI.path.removePrefix("/")
        val file = repository.resolve(path)
        val digestAlgorithm =
            DIGEST_ALGORITHMS.entries.firstOrNull { (extension, _) -> path.endsWith(".$extension") }
        val digested = digestAlgorithm?.let { repository.resolve(path.removeSuffix(".${it.key}")) }
        when {
            file.isRegularFile() -> exchange.respond(200, file.readBytes())
            digestAlgorithm != null && digested != null && digested.isRegularFile() ->
                exchange.respond(
                    200,
                    MessageDigest.getInstance(digestAlgorithm.value)
                        .digest(digested.readBytes())
                        .toHexString()
                        .toByteArray(),
                )

            else -> exchange.respond(404, ByteArray(0))
        }
    }
    return withLocalHttpServer(handler) { server -> block(server.urlOf("/")) }
}

private fun publishLibrary(repository: Path) {
    val directory = repository.resolve("com/example/hello/1.0").createDirectories()
    directory
        .resolve("hello-1.0.pom")
        .writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>hello</artifactId>
              <version>1.0</version>
              <packaging>jar</packaging>
            </project>
            """
                .trimIndent(),
        )
    JarOutputStream(directory.resolve("hello-1.0.jar").outputStream(), Manifest()).use { jar ->
        jar.putNextEntry(ZipEntry("com/example/Hello.txt"))
        jar.write("hello".toByteArray())
        jar.closeEntry()
    }
}

/**
 * Publishes [LOCAL_AAR_COORDINATES] along with the two JVM libraries depending on it, one with an
 * explicit `<type>aar</type>` ([LOCAL_TYPED_AAR_DEPENDENT_COORDINATES]) and one without
 * ([LOCAL_UNTYPED_AAR_DEPENDENT_COORDINATES]): resolved together, they request the same library
 * with and without a packaging type.
 */
private fun publishAarLibraries(repository: Path) {
    val widget = repository.resolve("com/example/widget/1.0").createDirectories()
    widget
        .resolve("widget-1.0.pom")
        .writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>widget</artifactId>
              <version>1.0</version>
              <packaging>aar</packaging>
            </project>
            """
                .trimIndent(),
        )
    ZipOutputStream(widget.resolve("widget-1.0.aar").outputStream()).use { aar ->
        aar.putNextEntry(ZipEntry("AndroidManifest.xml"))
        aar.write("""<manifest package="com.example.widget"/>""".toByteArray())
        aar.closeEntry()
    }
    publishDependentLibrary(repository, artifactId = "widget-typed-user", dependencyType = "aar")
    publishDependentLibrary(repository, artifactId = "widget-user", dependencyType = null)
}

/**
 * Publishes the JVM library `com.example:[artifactId]:1.0` depending on [LOCAL_AAR_COORDINATES],
 * declaring the dependency with `<type>[dependencyType]</type>` unless `null`.
 */
private fun publishDependentLibrary(
    repository: Path,
    artifactId: String,
    dependencyType: String?,
) {
    val directory = repository.resolve("com/example/$artifactId/1.0").createDirectories()
    val type = dependencyType?.let { "<type>$it</type>" }.orEmpty()
    directory
        .resolve("$artifactId-1.0.pom")
        .writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>$artifactId</artifactId>
              <version>1.0</version>
              <packaging>jar</packaging>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                  $type
                </dependency>
              </dependencies>
            </project>
            """
                .trimIndent(),
        )
    JarOutputStream(directory.resolve("$artifactId-1.0.jar").outputStream(), Manifest()).use { jar
        ->
        jar.putNextEntry(ZipEntry("com/example/$artifactId.txt"))
        jar.write(artifactId.toByteArray())
        jar.closeEntry()
    }
}

/** Publishes a KMP Android variant and a POM consumer requesting its umbrella as an AAR. */
private fun publishKmpAarLibraries(repository: Path) {
    val umbrella = repository.resolve("com/example/kmp-widget/1.0").createDirectories()
    umbrella
        .resolve("kmp-widget-1.0.pom")
        .writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId><artifactId>kmp-widget</artifactId><version>1.0</version>
              <packaging>aar</packaging>
              <!-- do_not_remove: published-with-gradle-metadata -->
            </project>
            """
                .trimIndent(),
        )
    umbrella
        .resolve("kmp-widget-1.0.module")
        .writeText(
            """
            {
              "formatVersion": "1.1",
              "component": {"group": "com.example", "module": "kmp-widget", "version": "1.0"},
              "variants": [
                {
                  "name": "releaseApiElements-published",
                  "attributes": {"org.gradle.category": "library", "org.gradle.jvm.environment": "android", "org.gradle.usage": "java-api", "org.jetbrains.kotlin.platform.type": "androidJvm"},
                  "available-at": {"url": "../../kmp-widget-android/1.0/kmp-widget-android-1.0.module", "group": "com.example", "module": "kmp-widget-android", "version": "1.0"}
                },
                {
                  "name": "releaseRuntimeElements-published",
                  "attributes": {"org.gradle.category": "library", "org.gradle.jvm.environment": "android", "org.gradle.usage": "java-runtime", "org.jetbrains.kotlin.platform.type": "androidJvm"},
                  "available-at": {"url": "../../kmp-widget-android/1.0/kmp-widget-android-1.0.module", "group": "com.example", "module": "kmp-widget-android", "version": "1.0"}
                }
              ]
            }
            """
                .trimIndent(),
        )
    ZipOutputStream(umbrella.resolve("kmp-widget-1.0.aar").outputStream()).use { aar ->
        aar.putNextEntry(ZipEntry("AndroidManifest.xml"))
        aar.write("<manifest package=\"com.example.kmp\"/>".toByteArray())
        aar.closeEntry()
    }

    val android = repository.resolve("com/example/kmp-widget-android/1.0").createDirectories()
    android
        .resolve("kmp-widget-android-1.0.pom")
        .writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId><artifactId>kmp-widget-android</artifactId><version>1.0</version>
              <packaging>aar</packaging>
              <!-- do_not_remove: published-with-gradle-metadata -->
            </project>
            """
                .trimIndent(),
        )
    android
        .resolve("kmp-widget-android-1.0.module")
        .writeText(
            """
            {
              "formatVersion": "1.1",
              "component": {"url": "../../kmp-widget/1.0/kmp-widget-1.0.module", "group": "com.example", "module": "kmp-widget", "version": "1.0"},
              "variants": [
                {
                  "name": "releaseApiElements-published",
                  "attributes": {"org.gradle.category": "library", "org.gradle.jvm.environment": "android", "org.gradle.usage": "java-api", "org.jetbrains.kotlin.platform.type": "androidJvm"},
                  "files": [{"name": "kmp-widget-android-1.0.aar", "url": "kmp-widget-android-1.0.aar"}]
                },
                {
                  "name": "releaseRuntimeElements-published",
                  "attributes": {"org.gradle.category": "library", "org.gradle.jvm.environment": "android", "org.gradle.usage": "java-runtime", "org.jetbrains.kotlin.platform.type": "androidJvm"},
                  "files": [{"name": "kmp-widget-android-1.0.aar", "url": "kmp-widget-android-1.0.aar"}]
                }
              ]
            }
            """
                .trimIndent(),
        )
    ZipOutputStream(android.resolve("kmp-widget-android-1.0.aar").outputStream()).use { aar ->
        aar.putNextEntry(ZipEntry("AndroidManifest.xml"))
        aar.write("<manifest package=\"com.example.kmp.android\"/>".toByteArray())
        aar.closeEntry()
    }

    val user = repository.resolve("com/example/kmp-widget-user/1.0").createDirectories()
    user
        .resolve("kmp-widget-user-1.0.pom")
        .writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId><artifactId>kmp-widget-user</artifactId><version>1.0</version>
              <packaging>jar</packaging>
              <dependencies><dependency>
                <groupId>com.example</groupId><artifactId>kmp-widget</artifactId><version>1.0</version>
                <type>aar</type>
              </dependency></dependencies>
            </project>
            """
                .trimIndent(),
        )
    JarOutputStream(user.resolve("kmp-widget-user-1.0.jar").outputStream(), Manifest()).use { jar ->
        jar.putNextEntry(ZipEntry("com/example/User.txt"))
        jar.write("user".toByteArray())
        jar.closeEntry()
    }
}

/**
 * Publishes [LOCAL_KMP_REDIRECT_COORDINATES] as the JetBrains Compose libraries are: Gradle module
 * metadata whose Android variants have no file and no `available-at`, only dependencies, and whose
 * JVM variants are `available-at` [LOCAL_KMP_REDIRECT_JVM_VARIANT_COORDINATES], a module with a
 * jar.
 */
private fun publishKmpRedirectLibrary(repository: Path) {
    val umbrella = repository.resolve("com/example/kmp-ui/1.0").createDirectories()
    umbrella
        .resolve("kmp-ui-1.0.pom")
        .writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId><artifactId>kmp-ui</artifactId><version>1.0</version>
              <!-- do_not_remove: published-with-gradle-metadata -->
            </project>
            """
                .trimIndent(),
        )
    umbrella
        .resolve("kmp-ui-1.0.module")
        .writeText(
            """
            {
              "formatVersion": "1.1",
              "component": {"group": "com.example", "module": "kmp-ui", "version": "1.0"},
              "variants": [
                {
                  "name": "androidApiElements-published",
                  "attributes": {"org.gradle.category": "library", "org.gradle.jvm.environment": "android", "org.gradle.libraryelements": "aar", "org.gradle.usage": "java-api", "org.jetbrains.kotlin.platform.type": "jvm"},
                  "dependencies": [
                    {"group": "com.example", "module": "kmp-widget", "version": {"requires": "1.0"}}
                  ]
                },
                {
                  "name": "androidRuntimeElements-published",
                  "attributes": {"org.gradle.category": "library", "org.gradle.jvm.environment": "android", "org.gradle.libraryelements": "aar", "org.gradle.usage": "java-runtime", "org.jetbrains.kotlin.platform.type": "jvm"},
                  "dependencies": [
                    {"group": "com.example", "module": "kmp-widget", "version": {"requires": "1.0"}},
                    {"group": "com.example", "module": "hello", "version": {"requires": "1.0"}}
                  ]
                },
                {
                  "name": "jvmApiElements-published",
                  "attributes": {"org.gradle.category": "library", "org.gradle.jvm.environment": "standard-jvm", "org.gradle.libraryelements": "jar", "org.gradle.usage": "java-api", "org.jetbrains.kotlin.platform.type": "jvm"},
                  "available-at": {"url": "../../kmp-ui-jvm/1.0/kmp-ui-jvm-1.0.module", "group": "com.example", "module": "kmp-ui-jvm", "version": "1.0"}
                },
                {
                  "name": "jvmRuntimeElements-published",
                  "attributes": {"org.gradle.category": "library", "org.gradle.jvm.environment": "standard-jvm", "org.gradle.libraryelements": "jar", "org.gradle.usage": "java-runtime", "org.jetbrains.kotlin.platform.type": "jvm"},
                  "available-at": {"url": "../../kmp-ui-jvm/1.0/kmp-ui-jvm-1.0.module", "group": "com.example", "module": "kmp-ui-jvm", "version": "1.0"}
                }
              ]
            }
            """
                .trimIndent(),
        )

    val jvm = repository.resolve("com/example/kmp-ui-jvm/1.0").createDirectories()
    jvm.resolve("kmp-ui-jvm-1.0.pom")
        .writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId><artifactId>kmp-ui-jvm</artifactId><version>1.0</version>
              <packaging>jar</packaging>
              <!-- do_not_remove: published-with-gradle-metadata -->
            </project>
            """
                .trimIndent(),
        )
    jvm.resolve("kmp-ui-jvm-1.0.module")
        .writeText(
            """
            {
              "formatVersion": "1.1",
              "component": {"url": "../../kmp-ui/1.0/kmp-ui-1.0.module", "group": "com.example", "module": "kmp-ui", "version": "1.0"},
              "variants": [
                {
                  "name": "jvmApiElements-published",
                  "attributes": {"org.gradle.category": "library", "org.gradle.jvm.environment": "standard-jvm", "org.gradle.libraryelements": "jar", "org.gradle.usage": "java-api", "org.jetbrains.kotlin.platform.type": "jvm"},
                  "files": [{"name": "kmp-ui-jvm-1.0.jar", "url": "kmp-ui-jvm-1.0.jar"}]
                },
                {
                  "name": "jvmRuntimeElements-published",
                  "attributes": {"org.gradle.category": "library", "org.gradle.jvm.environment": "standard-jvm", "org.gradle.libraryelements": "jar", "org.gradle.usage": "java-runtime", "org.jetbrains.kotlin.platform.type": "jvm"},
                  "files": [{"name": "kmp-ui-jvm-1.0.jar", "url": "kmp-ui-jvm-1.0.jar"}]
                }
              ]
            }
            """
                .trimIndent(),
        )
    JarOutputStream(jvm.resolve("kmp-ui-jvm-1.0.jar").outputStream(), Manifest()).use { jar ->
        jar.putNextEntry(ZipEntry("com/example/Ui.txt"))
        jar.write("ui".toByteArray())
        jar.closeEntry()
    }
}

/** Publishes [LOCAL_POM_ONLY_COORDINATES], a POM of `pom` packaging without artifact. */
private fun publishPomOnlyLibrary(repository: Path) {
    repository
        .resolve("com/example/aggregate/1.0")
        .createDirectories()
        .resolve("aggregate-1.0.pom")
        .writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId><artifactId>aggregate</artifactId><version>1.0</version>
              <packaging>pom</packaging>
              <dependencies><dependency>
                <groupId>com.example</groupId><artifactId>hello</artifactId><version>1.0</version>
              </dependency></dependencies>
            </project>
            """
                .trimIndent(),
        )
}
