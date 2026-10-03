package cplus.intellij

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory

internal data class CPlusStdlibSymbol(val name: String, val detail: String)

/** Resolves the same conventional stdlib locations used by the CLI. */
internal object CPlusStdlibIndex {
    private val cache = ConcurrentHashMap<String, List<CPlusStdlibSymbol>>()

    fun root(project: Project): Path? {
        val base = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
        val manifest = base?.resolve("cplus.toml")
        if (manifest != null && Files.isRegularFile(manifest)) {
            val configured = Regex("(?m)^\\s*stdlib\\s*=\\s*\\\"([^\\\"]*)\\\"").find(Files.readString(manifest))
                ?.groupValues?.get(1)?.trim()
            if (!configured.isNullOrEmpty() && configured != "auto") {
                val path = base.resolve(configured).normalize()
                if (path.isDirectory()) return path
            }
        }
        val candidates = buildList {
            System.getenv("CPLUS_STDLIB")?.takeIf(String::isNotBlank)?.let { add(Path.of(it)) }
            System.getenv("CPLUS_HOME")?.takeIf(String::isNotBlank)?.let { add(Path.of(it).resolve("stdlib")) }
            System.getProperty("cplus.home")?.takeIf(String::isNotBlank)?.let { add(Path.of(it).resolve("stdlib")) }
            var cursor = base
            while (cursor != null) {
                add(cursor.resolve("stdlib"))
                cursor = cursor.parent
            }
            System.getProperty("user.home")?.let {
                add(Path.of(it, ".local", "share", "cplus", "stdlib"))
                add(Path.of(it, ".local", "opt", "cplus", "stdlib"))
            }
            System.getenv("ProgramData")?.let { add(Path.of(it, "CPlus", "stdlib")) }
            add(Path.of("/usr/local/share/cplus/stdlib"))
            add(Path.of("/usr/share/cplus/stdlib"))
        }
        return candidates.map { it.toAbsolutePath().normalize() }.firstOrNull(Path::isDirectory)
    }

    fun symbols(project: Project): List<CPlusStdlibSymbol> {
        val roots = listOfNotNull(root(project)) + moduleRoots(project)
        return roots.flatMap { directory ->
            cache.computeIfAbsent(directory.toString()) { index(Path.of(it)) }
        }.distinctBy { it.name }
    }

    fun imports(project: Project): List<String> {
        val stdlib = root(project) ?: return emptyList()
        return runCatching {
            Files.walk(stdlib).use { files ->
                files.filter { Files.isRegularFile(it) && (it.toString().endsWith(".cp") || it.toString().endsWith(".c+")) }
                    .map { stdlib.relativize(it).toString().replace('\\', '/')
                        .replace(Regex("\\.(cp|c\\+)$"), "") }
                    .map { "stdlib:/$it" }
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    fun moduleRoots(project: Project): List<Path> {
        val base = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: return emptyList()
        return moduleRootsAt(base, mutableSetOf())
    }

    private fun moduleRootsAt(projectRoot: Path, visited: MutableSet<String>): List<Path> {
        val identity = projectRoot.toAbsolutePath().normalize().toString()
        if (!visited.add(identity)) return emptyList()
        val manifest = projectRoot.resolve("cplus.toml")
        if (!Files.isRegularFile(manifest)) return emptyList()
        val text = Files.readString(manifest)
        val paths = Regex("(?m)^\\s*module-paths\\s*=\\s*\\[([^]]*)]").find(text)
            ?.groupValues?.get(1)?.let { Regex("\\\"([^\\\"]+)\\\"").findAll(it).map { match -> match.groupValues[1] }.toList() }
            ?: listOf("src", "modules")
        val result = paths.map { projectRoot.resolve(it).normalize() }.filter(Path::isDirectory).toMutableList()
        val dependencySection = Regex("(?s)\\[dependencies](.*?)(?:\\n\\[|$)").find(text)?.groupValues?.get(1).orEmpty()
        Regex("(?m)^\\s*[A-Za-z_]\\w*\\s*=\\s*\\{[^}]*path\\s*=\\s*\\\"([^\\\"]+)\\\"").findAll(dependencySection).forEach {
            result += moduleRootsAt(projectRoot.resolve(it.groupValues[1]).normalize(), visited)
        }
        return result.distinct()
    }

    private fun index(root: Path): List<CPlusStdlibSymbol> {
        val result = linkedMapOf<String, CPlusStdlibSymbol>()
        runCatching {
            Files.walk(root).use { files ->
                files.filter { Files.isRegularFile(it) && (it.toString().endsWith(".cp") || it.toString().endsWith(".c+")) }
                    .forEach { source ->
                        val text = Files.readString(source)
                        Regex("(?m)^\\s*#\\s*define\\s+([A-Za-z_]\\w*)").findAll(text).forEach {
                            result.putIfAbsent(it.groupValues[1], CPlusStdlibSymbol(it.groupValues[1], "C-plus stdlib macro"))
                        }
                        Regex("(?m)^\\s*typedef\\s+(?:struct|enum|union)?\\s*[^;{}]*\\b([A-Za-z_]\\w*_t)\\s*;").findAll(text).forEach {
                            result.putIfAbsent(it.groupValues[1], CPlusStdlibSymbol(it.groupValues[1], "C-plus stdlib type"))
                        }
                        Regex("(?m)^\\s*(?:pub\\s+|static\\s+|extern\\s+|const\\s+|unsigned\\s+|signed\\s+|long\\s+|short\\s+|struct\\s+|enum\\s+|union\\s+|[A-Za-z_]\\w*_t\\s+|[A-Za-z_]\\w+\\s+)+([A-Za-z_]\\w*)\\s*\\(").findAll(text).forEach {
                            val name = it.groupValues[1]
                            if (name !in setOf("if", "for", "while", "switch")) {
                                result.putIfAbsent(name, CPlusStdlibSymbol(name, "C-plus stdlib function"))
                            }
                        }
                    }
            }
        }
        return result.values.sortedBy { it.name }
    }
}
