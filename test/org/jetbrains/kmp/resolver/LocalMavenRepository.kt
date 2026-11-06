package org.jetbrains.kmp.resolver

import com.sun.net.httpserver.HttpHandler
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
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

private val DIGEST_ALGORITHMS =
    mapOf("sha512" to "SHA-512", "sha256" to "SHA-256", "sha1" to "SHA-1", "md5" to "MD5")

/**
 * Runs [block] with the URL of a Maven repository served on the loopback interface, publishing the
 * JVM library [LOCAL_LIBRARY_COORDINATES] as a POM and a jar along with their checksums, and
 * stopped once [block] returns.
 */
internal fun <T> withLocalMavenRepository(block: (String) -> T): T {
    val repository = createTempDirectory("local-maven-repository").also { publishLibrary(it) }
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
