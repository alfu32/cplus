package cplus

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.zip.ZipInputStream

internal data class CPlusPackageReference(val name: String, val value: String, val version: String? = null)

internal data class CPlusPackageManifest(
    val name: String,
    val version: String,
    val dependencies: Map<String, CPlusPackageReference>
)

internal object CPlusPackageManager {
    private val quoted = Regex("^\"((?:[^\"\\\\]|\\\\.)*)\"$")
    private val fields = Regex("(path|url|version)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    fun init(directory: Path): Path {
        val root = directory.toAbsolutePath().normalize()
        if (Files.exists(root) && !Files.isDirectory(root)) throw IllegalArgumentException("package path is not a directory: " + root)
        Files.createDirectories(root)
        val manifest = root.resolve("cplus.toml")
        if (Files.exists(manifest)) throw IllegalArgumentException("refusing to overwrite existing manifest: " + manifest)
        val name = root.fileName?.toString()?.ifBlank { "cplus-package" } ?: "cplus-package"
        Files.writeString(manifest, "name = \"" + escape(name) + "\"\n" +
            "version = \"0.1.0\"\nsource = \"src\"\nstdlib = \"auto\"\n" +
            "module-paths = [\"src\", \"modules\"]\ndependencies = []\n")
        return manifest
    }

    fun add(directory: Path, raw: String): CPlusPackageReference {
        val root = directory.toAbsolutePath().normalize()
        val manifest = root.resolve("cplus.toml")
        if (!Files.isRegularFile(manifest)) throw IllegalArgumentException("no cplus.toml in " + root + "; run 'cpc pkg init'")
        val reference = parseReference(raw)
        if (parse(manifest).dependencies.containsKey(reference.name)) {
            throw IllegalArgumentException("dependency already declared: " + reference.name)
        }
        appendDependency(manifest, reference)
        return reference
    }

    fun install(directory: Path, requested: List<String>): List<Path> {
        val root = directory.toAbsolutePath().normalize()
        val manifestPath = root.resolve("cplus.toml")
        if (!Files.isRegularFile(manifestPath)) throw IllegalArgumentException("no cplus.toml in " + root + "; run 'cpc pkg init'")
        val manifest = parse(manifestPath)
        val references = if (requested.isEmpty()) manifest.dependencies.values.toList() else requested.map(::parseReference)
        if (references.isEmpty()) return emptyList()
        val stage = Files.createTempDirectory(root, ".cplus-package-staging-")
        val packages = linkedMapOf<String, Path>()
        try {
            references.forEach { resolve(it, root, stage, packages, emptyList()) }
            val modules = root.resolve("modules")
            Files.createDirectories(modules)
            packages.keys.map(modules::resolve).firstOrNull(Files::exists)?.let { target ->
                throw IllegalArgumentException("module already exists: " + target + "; remove it before installing")
            }
            packages.forEach { (name, staged) ->
                val target = modules.resolve(name)
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE)
            }
            return packages.keys.map { modules.resolve(it) }
        } finally {
            deleteTree(stage)
        }
    }

    fun parse(path: Path): CPlusPackageManifest {
        var name = path.parent.fileName?.toString() ?: "cplus-package"
        var version = "0.1.0"
        var section = ""
        val dependencies = linkedMapOf<String, CPlusPackageReference>()
        Files.readAllLines(path).forEach { raw ->
            val line = stripComment(raw).trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim()
                return@forEach
            }
            if ('=' !in line) return@forEach
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim()
            if (section == "dependencies") dependencies[key] = parseDependency(key, value, path)
            else when (key) {
                "name" -> name = parseString(value, path)
                "version" -> version = parseString(value, path)
            }
        }
        return CPlusPackageManifest(name, version, dependencies)
    }

    private fun resolve(reference: CPlusPackageReference, relativeTo: Path, stage: Path, packages: LinkedHashMap<String, Path>, chain: List<String>) {
        if (reference.name in chain) throw IllegalArgumentException("package dependency cycle: " + (chain + reference.name).joinToString(" -> "))
        if (packages.containsKey(reference.name)) return
        val destination = stage.resolve("packages").resolve(reference.name)
        val root = obtain(reference, relativeTo, destination)
        val manifestPath = locateManifest(root)
        val manifest = parse(manifestPath)
        if (manifest.name != reference.name) throw IllegalArgumentException(
            "dependency '" + reference.name + "' has manifest name '" + manifest.name + "'"
        )
        packages[manifest.name] = root
        val childBase = if (reference.value.startsWith("http://") || reference.value.startsWith("https://")) {
            manifestPath.parent
        } else {
            val source = if (reference.value.startsWith("file:")) Path.of(URI.create(reference.value))
            else relativeTo.resolve(reference.value).normalize().toAbsolutePath()
            source
        }
        manifest.dependencies.values.forEach { child ->
            resolve(child, childBase, stage, packages, chain + reference.name)
        }
    }

    private fun obtain(reference: CPlusPackageReference, relativeTo: Path, destination: Path): Path {
        Files.createDirectories(destination)
        val value = reference.value
        if (value.startsWith("http://") || value.startsWith("https://")) {
            if (!value.lowercase().endsWith(".zip")) throw IllegalArgumentException("unsupported package download (only .zip is supported): " + value)
            val archive = destination.resolve("package.zip")
            val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build().send(
                HttpRequest.newBuilder(URI.create(value)).timeout(Duration.ofMinutes(2)).GET().build(),
                HttpResponse.BodyHandlers.ofFile(archive)
            )
            if (response.statusCode() !in 200..299) throw IllegalArgumentException("package download failed (" + response.statusCode() + "): " + value)
            unzip(archive, destination)
            Files.deleteIfExists(archive)
            return flatten(destination)
        }
        val source = if (value.startsWith("file:")) Path.of(URI.create(value)) else relativeTo.resolve(value).normalize().toAbsolutePath()
        if (!Files.isDirectory(source)) throw IllegalArgumentException("package path does not exist: " + source)
        copyTree(source, destination)
        return flatten(destination)
    }

    private fun locateManifest(root: Path): Path {
        val direct = root.resolve("cplus.toml")
        if (Files.isRegularFile(direct)) return direct
        val candidates = Files.list(root).use { it.filter(Files::isDirectory).map { child -> child.resolve("cplus.toml") }.filter(Files::isRegularFile).toList() }
        return candidates.singleOrNull() ?: throw IllegalArgumentException("package must contain exactly one root cplus.toml: " + root)
    }

    private fun flatten(root: Path): Path {
        if (Files.isRegularFile(root.resolve("cplus.toml"))) return root
        val children = Files.list(root).use { it.toList() }
        if (children.size == 1 && Files.isDirectory(children.single())) {
            val nested = children.single()
            Files.list(nested).use { it.toList() }.forEach { child ->
                Files.move(child, root.resolve(child.fileName.toString()), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.deleteIfExists(nested)
        }
        return root
    }

    private fun unzip(archive: Path, destination: Path) {
        ZipInputStream(Files.newInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = destination.resolve(entry.name).normalize()
                if (!target.startsWith(destination)) throw IllegalArgumentException("unsafe path in package archive: " + entry.name)
                if (entry.isDirectory) Files.createDirectories(target)
                else {
                    target.parent?.let(Files::createDirectories)
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun copyTree(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val target = destination.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(target)
                else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun appendDependency(path: Path, reference: CPlusPackageReference) {
        val original = Files.readString(path)
        val line = reference.name + " = " + formatDependency(reference)
        val lines = original.lines().toMutableList()
        val section = lines.indexOfFirst { it.trim() == "[dependencies]" }
        if (section < 0) {
            val prefix = if (original.endsWith("\n")) original else original + "\n"
            Files.writeString(path, prefix + "\n[dependencies]\n" + line + "\n")
            return
        }
        var insert = section + 1
        while (insert < lines.size && !lines[insert].trim().startsWith("[")) insert++
        lines.add(insert, line)
        Files.writeString(path, lines.joinToString("\n"))
    }

    private fun parseReference(raw: String): CPlusPackageReference {
        val input = raw.trim().trim('"', '\'')
        if (input.isBlank()) throw IllegalArgumentException("dependency reference cannot be empty")
        val separator = input.indexOf('=')
        if (separator > 0) return CPlusPackageReference(input.substring(0, separator).trim(), input.substring(separator + 1).trim())
        val name = input.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".zip").removeSuffix(".tar.gz")
        if (name.isBlank()) throw IllegalArgumentException("cannot infer package name from: " + input)
        return CPlusPackageReference(name, input)
    }

    private fun parseDependency(name: String, value: String, path: Path): CPlusPackageReference {
        val values = fields.findAll(value).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        val reference = values["path"] ?: values["url"] ?: quoted.matchEntire(value)?.groupValues?.get(1)?.let(::unescape)
        if (reference.isNullOrBlank()) throw IllegalArgumentException("dependency '" + name + "' in " + path + " requires path or url")
        return CPlusPackageReference(name, reference, values["version"])
    }

    private fun parseString(value: String, path: Path): String = quoted.matchEntire(value)?.groupValues?.get(1)?.let(::unescape)
        ?: throw IllegalArgumentException("invalid string in " + path)

    private fun formatDependency(reference: CPlusPackageReference): String {
        val field = if (reference.value.startsWith("http://") || reference.value.startsWith("https://") || reference.value.startsWith("file:")) "url" else "path"
        val version = reference.version?.let { ", version = \"" + escape(it) + "\"" } ?: ""
        return "{ " + field + " = \"" + escape(reference.value) + "\"" + version + " }"
    }

    private fun unescape(value: String) = value.replace("\\\"", "\"").replace("\\\\", "\\")
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun stripComment(value: String): String {
        var quoted = false
        var escaped = false
        value.forEachIndexed { index, c ->
            if (c == '"' && !escaped) quoted = !quoted
            if (c == '#' && !quoted) return value.substring(0, index)
            escaped = c == '\\' && !escaped
            if (c != '\\') escaped = false
        }
        return value
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
