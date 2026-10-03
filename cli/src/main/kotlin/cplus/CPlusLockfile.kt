package cplus

import java.nio.file.Files
import java.nio.file.Path

/** Deterministic, human-readable lock data for package and toolchain resolution. */
internal data class CPlusToolchainLockEntry(
    val release: String,
    val sha256: String,
    val target: String? = null,
    val kind: String? = null
)

internal object CPlusLockfile {
    fun read(path: Path): Map<String, CPlusToolchainLockEntry> {
        if (!Files.isRegularFile(path)) return emptyMap()
        val entries = linkedMapOf<String, CPlusToolchainLockEntry>()
        var section = ""
        Files.readAllLines(path).forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim()
                return@forEach
            }
            if (section != "toolchains" || '=' !in line) return@forEach
            val key = line.substringBefore('=').trim().trim('"')
            val value = line.substringAfter('=').trim()
            val fields = Regex("(release|sha256|target|kind)\\s*=\\s*\"([^\"]*)\"")
                .findAll(value).associate { it.groupValues[1] to it.groupValues[2] }
            val release = fields["release"] ?: return@forEach
            val sha256 = fields["sha256"] ?: return@forEach
            entries[key] = CPlusToolchainLockEntry(release, sha256, fields["target"], fields["kind"])
        }
        return entries.toSortedMap()
    }

    fun writeToolchains(path: Path, entries: Map<String, CPlusToolchainLockEntry>) {
        val parent = path.toAbsolutePath().parent ?: return
        Files.createDirectories(parent)
        val dependencies = dependencyBlock(path)
        val text = buildString {
            append("format = 1\n\n")
            append("[toolchains]\n")
            entries.toSortedMap().forEach { (reference, entry) ->
                append('"').append(escape(reference)).append("\" = { release = \"")
                    .append(escape(entry.release)).append("\", sha256 = \"")
                    .append(escape(entry.sha256)).append('"')
                entry.target?.let { append(", target = \"").append(escape(it)).append('"') }
                entry.kind?.let { append(", kind = \"").append(escape(it)).append('"') }
                append(" }\n")
            }
            if (dependencies.isNotEmpty()) append("\n[dependencies]\n").append(dependencies.joinToString("\n", postfix = "\n"))
        }
        Files.writeString(path, text)
    }

    fun writeDependencies(path: Path, dependencies: Map<String, CPlusPackageReference>) {
        val toolchains = read(path)
        val parent = path.toAbsolutePath().parent ?: return
        Files.createDirectories(parent)
        val text = buildString {
            append("format = 1\n\n[toolchains]\n")
            toolchains.toSortedMap().forEach { (reference, entry) ->
                append('"').append(escape(reference)).append("\" = { release = \"")
                    .append(escape(entry.release)).append("\", sha256 = \"")
                    .append(escape(entry.sha256)).append('"')
                entry.target?.let { append(", target = \"").append(escape(it)).append('"') }
                entry.kind?.let { append(", kind = \"").append(escape(it)).append('"') }
                append(" }\n")
            }
            append("\n[dependencies]\n")
            dependencies.toSortedMap().forEach { (name, dependency) ->
                append('"').append(escape(name)).append("\" = { source = \"")
                    .append(escape(dependency.value)).append('"')
                dependency.version?.let { append(", version = \"").append(escape(it)).append('"') }
                append(" }\n")
            }
        }
        Files.writeString(path, text)
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun dependencyBlock(path: Path): List<String> {
        if (!Files.isRegularFile(path)) return emptyList()
        val lines = Files.readAllLines(path)
        val start = lines.indexOfFirst { it.trim() == "[dependencies]" }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { !it.trim().startsWith("[") }.filter(String::isNotBlank)
    }
}
