package cplus

import java.nio.file.Files
import java.nio.file.Path

/** Locates user-installed cplus-sysroots development bundles for compiler invocations. */
object CPlusToolchainLocator {
    @JvmStatic
    fun storageRoot(): Path {
        System.getProperty("cplus.toolchains")?.takeIf(String::isNotBlank)?.let { return Path.of(it).toAbsolutePath().normalize() }
        System.getenv("CPLUS_TOOLCHAINS")?.takeIf(String::isNotBlank)?.let { return Path.of(it).toAbsolutePath().normalize() }
        val home = Path.of(System.getProperty("user.home"))
        val os = System.getProperty("os.name").lowercase()
        return if (os.contains("win")) {
            val local = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Path::of)
            (local ?: home.resolve("AppData/Local")).resolve("cplus/toolchains")
        } else if (os.contains("mac") || os.contains("darwin")) {
            home.resolve("Library/Application Support/cplus/toolchains")
        } else {
            val data = System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
            (data ?: home.resolve(".local/share")).resolve("cplus/toolchains")
        }.toAbsolutePath().normalize()
    }

    @JvmStatic
    fun canonicalTriple(target: String?): String? {
        val raw = target?.lowercase()?.trim()?.removePrefix("--target=") ?: hostTriple()
        if (raw.isNullOrBlank()) return null
        if (raw.endsWith("-dev") || raw.endsWith("-rt")) return raw.substringBeforeLast('-')
        if (raw.count { it == '-' } >= 2 &&
            (raw.contains("-unknown-linux-") || raw.contains("-apple-darwin") || raw.contains("-w64-mingw32"))) {
            return raw.replace("aarch64", "arm64")
        }
        val normalized = raw.replace("aarch64", "arm64")
        return when {
            normalized.startsWith("linux-") -> normalized.removePrefix("linux-").takeIf { it == "x86_64" || it == "arm64" }?.let { "$it-unknown-linux-gnu" }
            normalized.startsWith("macos-") -> normalized.removePrefix("macos-").takeIf { it == "x86_64" || it == "arm64" }?.let { "$it-apple-darwin" }
            normalized.startsWith("windows-") -> normalized.removePrefix("windows-").takeIf { it == "x86_64" || it == "arm64" }?.let { "$it-w64-mingw32" }
            else -> null
        }
    }

    @JvmStatic
    fun developmentSysrootFor(target: String?): Path? {
        val triple = canonicalTriple(target) ?: return null
        val root = storageRoot().resolve(triple + "-dev")
        return root.takeIf { Files.isRegularFile(it.resolve("MANIFEST.json")) }
    }

    private fun hostTriple(): String? {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase().replace("amd64", "x86_64").replace("aarch64", "arm64")
        return when {
            os.contains("linux") && arch in setOf("x86_64", "arm64") -> "$arch-unknown-linux-gnu"
            (os.contains("mac") || os.contains("darwin")) && arch in setOf("x86_64", "arm64") -> "$arch-apple-darwin"
            os.contains("win") && arch in setOf("x86_64", "arm64") -> "$arch-w64-mingw32"
            else -> null
        }
    }
}
