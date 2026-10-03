package cplus

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream

internal object CPlusToolchainManager {
    private const val triplesUrl = "https://github.com/alfu32/cplus-sysroots/releases/latest/download/triples.txt"
    private const val releaseBase = "https://github.com/alfu32/cplus-sysroots/releases/latest/download/"

    fun listLocal(): List<String> {
        val root = storageRoot()
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "MANIFEST.json" }
                .map { root.relativize(it.parent).toString().replace('\\', '/') }
                .sorted().toList()
        }
    }

    fun listRemote(): List<String> = parseReferences(fetchText(triplesUrl))

    fun install(reference: String): List<Path> {
        val remote = listRemote()
        val selected = selectReferences(reference, remote)
        val installed = selected.map { installExact(it) }
        return installed
    }

    fun update(reference: String?): List<Path> {
        val remote = listRemote()
        val selected = if (reference == null || reference == "all") remote else selectReferences(reference, remote)
        return selected.map { installExact(it) }
    }

    internal fun developmentSysrootFor(target: String?): Path? {
        return CPlusToolchainLocator.developmentSysrootFor(target)
    }

    internal fun canonicalTriple(target: String?): String? {
        return CPlusToolchainLocator.canonicalTriple(target)
    }

    fun storageRoot(): Path {
        return CPlusToolchainLocator.storageRoot()
    }

    internal fun selectReferences(requested: String, available: List<String>): List<String> {
        val exact = available.filter { it == requested }
        if (exact.isNotEmpty()) return exact
        val base = requested.removeSuffix("-dev").removeSuffix("-rt")
        val matches = available.filter { it == "$base-dev" || it == "$base-rt" }
        if (matches.isEmpty()) throw IllegalArgumentException("unknown toolchain reference '$requested'; use 'cpc toolchain list remote'")
        return matches
    }

    private fun installExact(reference: String): Path {
        val target = storageRoot().resolve(reference).normalize()
        val archive = Files.createTempFile("cplus-toolchain-", ".zip")
        try {
            val url = releaseBase + reference + ".zip"
            val metadata = fetchText(releaseBase + reference + ".json")
            if (!metadata.contains("\"reference\": \"" + reference + "\"")) {
                throw IllegalArgumentException("toolchain metadata reference does not match: " + reference)
            }
            val response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build().send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
                HttpResponse.BodyHandlers.ofFile(archive)
            )
            if (response.statusCode() !in 200..299) {
                throw IllegalArgumentException("toolchain download failed (" + response.statusCode() + "): " + url)
            }
            val expectedDigest = fetchText(url + ".sha256").trim().split(Regex("\\s+"), limit = 2).firstOrNull()
                ?.lowercase()
                ?: throw IllegalArgumentException("toolchain checksum is empty: " + reference)
            val actualDigest = sha256(archive)
            if (actualDigest != expectedDigest) {
                throw IllegalArgumentException("toolchain checksum mismatch: " + reference)
            }
            Files.createDirectories(storageRoot())
            val staging = Files.createTempDirectory(storageRoot(), ".staging-")
            try {
                unzip(archive, staging)
                val manifest = staging.resolve("MANIFEST.json")
                if (!Files.isRegularFile(manifest)) throw IllegalArgumentException("toolchain archive has no MANIFEST.json: " + reference)
                val declared = Files.readString(manifest)
                if (!declared.contains("\"reference\": \"" + reference + "\"")) {
                    throw IllegalArgumentException("toolchain manifest reference does not match: " + reference)
                }
                deleteTree(target)
                Files.createDirectories(target.parent)
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                return target
            } finally {
                deleteTree(staging)
            }
        } finally {
            Files.deleteIfExists(archive)
        }
    }

    internal fun parseReferences(text: String): List<String> = text.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .filter { it.endsWith("-dev") || it.endsWith("-rt") }
        .distinct().toList()

    private fun fetchText(url: String): String {
        val response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build().send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(1)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (response.statusCode() !in 200..299) throw IllegalArgumentException("remote toolchain catalog failed (" + response.statusCode() + ")")
        return response.body()
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unzip(archive: Path, destination: Path) {
        ZipInputStream(Files.newInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = destination.resolve(entry.name).normalize()
                if (!target.startsWith(destination)) throw IllegalArgumentException("unsafe path in toolchain archive: " + entry.name)
                if (entry.isDirectory) Files.createDirectories(target) else {
                    target.parent?.let(Files::createDirectories)
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
