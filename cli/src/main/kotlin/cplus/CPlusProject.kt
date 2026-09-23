package cplus

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal data class CPlusProject(
    val root: Path,
    val name: String,
    val sourceRoot: Path,
    val stdlibRoot: Path?,
    val moduleRoots: List<Path>
) {
    fun importPaths(cliStdlib: Path?, environmentStdlib: String?): CPlusImportPaths {
        return CPlusImportPaths(defaultStandardLibraryRoots(cliStdlib, environmentStdlib, stdlibRoot), moduleRoots)
    }

    companion object {
        private val stringValue = Regex("^\"((?:[^\"\\\\]|\\\\.)*)\"$")
        private val arrayValue = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

        internal fun defaultStandardLibraryRoots(
            cliStdlib: Path?,
            environmentStdlib: String?,
            projectStdlib: Path? = null
        ): List<Path> = buildList {
            cliStdlib?.let(::add)
            projectStdlib?.let(::add)
            environmentStdlib?.takeIf(String::isNotBlank)?.let { add(Path.of(it)) }
            discoverInstalledStdlibRoots().forEach(::add)
        }.map { it.toAbsolutePath().normalize() }.distinct()

        fun find(start: Path): CPlusProject? {
            val initial = start.toAbsolutePath().normalize()
            var cursor: Path? = if (Files.isDirectory(initial)) initial else initial.parent ?: return null
            while (cursor != null) {
                val directory = cursor
                val manifest = directory.resolve("cplus.toml")
                if (Files.isRegularFile(manifest)) return read(directory, manifest)
                cursor = directory.parent
            }
            return null
        }

        private fun read(root: Path, manifest: Path): CPlusProject {
            var name = root.fileName?.toString() ?: "cplus-project"
            var sourceDirectory = "src"
            var stdlibDirectory: String? = null
            var moduleDirectories = listOf("src", "modules")

            Files.readAllLines(manifest).forEachIndexed { lineIndex, originalLine ->
                val line = stripComment(originalLine).trim()
                if (line.isEmpty() || line.startsWith("[") || '=' !in line) return@forEachIndexed
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=').trim()
                when (key) {
                    "name" -> name = parseString(value, manifest, lineIndex + 1)
                    "source" -> sourceDirectory = parseString(value, manifest, lineIndex + 1)
                    "stdlib" -> parseString(value, manifest, lineIndex + 1).takeIf(String::isNotBlank)
                        ?.let { stdlibDirectory = it }
                    "module-paths" -> moduleDirectories = arrayValue.findAll(value)
                        .map { unescape(it.groupValues[1]) }.toList()
                }
            }

            fun resolve(value: String) = root.resolve(value).normalize().toAbsolutePath()
            return CPlusProject(
                root.toAbsolutePath().normalize(),
                name,
                resolve(sourceDirectory),
                stdlibDirectory?.let(::resolve),
                moduleDirectories.map(::resolve).distinct()
            )
        }

        private fun parseString(value: String, manifest: Path, line: Int): String =
            stringValue.matchEntire(value)?.groupValues?.get(1)?.let(::unescape)
                ?: throw IllegalArgumentException("invalid string in $manifest:$line")

        private fun unescape(value: String): String = value
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

        private fun stripComment(value: String): String {
            var quoted = false
            var escaped = false
            value.forEachIndexed { index, character ->
                if (character == '"' && !escaped) quoted = !quoted
                if (character == '#' && !quoted) return value.substring(0, index)
                escaped = character == '\\' && !escaped
                if (character != '\\') escaped = false
            }
            return value
        }

        private fun discoverInstalledStdlibRoots(): List<Path> {
            val candidates = mutableListOf<Path>()
            System.getProperty("cplus.home")?.takeIf(String::isNotBlank)?.let { candidates.add(Path.of(it).resolve("stdlib")) }
            System.getenv("CPLUS_HOME")?.takeIf(String::isNotBlank)?.let { candidates.add(Path.of(it).resolve("stdlib")) }

            val codeLocation = runCatching {
                Path.of(CPlusProject::class.java.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize()
            }.getOrNull()
            var ancestor = codeLocation?.let { if (Files.isDirectory(it)) it else it.parent }
            while (ancestor != null) {
                candidates.add(ancestor.resolve("stdlib"))
                ancestor = ancestor.parent
            }

            Path.of("").toAbsolutePath().normalize().let { cwd ->
                var directory: Path? = cwd
                while (directory != null) {
                    candidates.add(directory.resolve("stdlib"))
                    directory = directory.parent
                }
            }
            System.getProperty("user.home")?.let { home ->
                candidates.add(Path.of(home, ".local", "share", "cplus", "stdlib"))
                candidates.add(Path.of(home, ".local", "opt", "cplus", "stdlib"))
            }
            System.getenv("ProgramData")?.let { candidates.add(Path.of(it, "CPlus", "stdlib")) }
            candidates.add(Path.of("/usr/local/share/cplus/stdlib"))
            candidates.add(Path.of("/usr/share/cplus/stdlib"))
            return candidates
        }
    }
}

internal object CPlusProjectScaffolder {
    fun create(requestedDirectory: Path): Path {
        val directory = requestedDirectory.toAbsolutePath().normalize()
        if (Files.exists(directory) && !Files.isDirectory(directory)) {
            throw IllegalArgumentException("project path is not a directory: $directory")
        }
        val conflicts = listOf(directory.resolve("cplus.toml"), directory.resolve("src/main.cp"), directory.resolve("README.md"))
            .filter(Files::exists)
        if (conflicts.isNotEmpty()) {
            throw IllegalArgumentException("refusing to overwrite existing project files: ${conflicts.joinToString()}")
        }
        Files.createDirectories(directory)
        val name = directory.fileName?.toString()?.takeIf(String::isNotBlank) ?: "cplus-project"
        val manifest = """name = "${escape(name)}"
version = "0.1.0"
source = "src"
stdlib = ""
module-paths = ["src", "modules"]
dependencies = []
"""
        val mainSource = """#include <stdio.h>

int main(void) {
    puts("Hello from C-plus!");
    return 0;
}
"""
        writeNew(directory.resolve("cplus.toml"), manifest)
        Files.createDirectories(directory.resolve("src"))
        Files.createDirectories(directory.resolve("modules"))
        Files.createDirectories(directory.resolve("tests"))
        writeNew(directory.resolve("src/main.cp"), mainSource)
        writeNew(directory.resolve("README.md"), """# $name

Run the program with `cpc run src/main.cp` from this directory.

Project modules may be imported with `comptime import "module:/path/to/module.cp"`.
Standard-library modules use the stable `stdlib:/` prefix.
""")
        return directory
    }

    private fun writeNew(path: Path, content: String) {
        Files.writeString(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
