package cplus

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Shared path policy for source imports in the compiler and parser migration pipeline. */
class CPlusImportResolver(
    private val importPaths: CPlusImportPaths = CPlusImportPaths(),
    /** Allows editor-owned, not-yet-saved files to participate in resolution. */
    private val sourceAvailable: (Path) -> Boolean = Files::isRegularFile
) {
    fun resolve(
        source: SourceFile,
        requestedPath: String,
        extensionlessCandidates: List<String>
    ): Path {
        val normalizedRequest = requestedPath.replaceFirst("^stdlib:c/".toRegex(), "stdlib:/")
        val (roots, relativePath, confined) = when {
            normalizedRequest.startsWith("stdlib:/") -> Triple(importPaths.standardLibraryRoots, normalizedRequest.removePrefix("stdlib:/"), true)
            normalizedRequest.startsWith("module:/") -> Triple(importPaths.moduleRoots, normalizedRequest.removePrefix("module:/"), true)
            normalizedRequest.startsWith("project:/") -> Triple(importPaths.moduleRoots, normalizedRequest.removePrefix("project:/"), true)
            else -> {
                val sourceName = source.name
                    ?: throw CPlusImportResolutionException("import requires a named source file")
                val base = try {
                    pathFromSourceName(sourceName).toAbsolutePath().normalize().parent
                } catch (error: Exception) {
                    throw CPlusImportResolutionException("cannot determine the directory of $sourceName: ${error.message}")
                } ?: throw CPlusImportResolutionException("cannot determine the directory of $sourceName")
                Triple(listOf(base), requestedPath, false)
            }
        }
        if (roots.isEmpty()) {
            val namespace = normalizedRequest.substringBefore(":/")
            throw CPlusImportResolutionException("no $namespace search path is configured for import '$requestedPath'")
        }

        val requested = try {
            Paths.get(relativePath.replace('/', java.io.File.separatorChar))
        } catch (error: Exception) {
            throw CPlusImportResolutionException("invalid import path '$requestedPath': ${error.message}")
        }
        val extension = normalizedRequest.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "")
        val candidates = if (extension.isNotEmpty()) {
            listOf(requested)
        } else {
            extensionlessCandidates.map { suffix -> requested.resolveSibling(requested.fileName.toString() + ".$suffix") }
        }
        for (rootPath in roots) {
            val normalizedRoot = rootPath.toAbsolutePath().normalize()
            for (candidate in candidates) {
                val resolved = (if (requested.isAbsolute) requested else normalizedRoot.resolve(candidate)).normalize()
                if (confined && !resolved.startsWith(normalizedRoot)) {
                    throw CPlusImportResolutionException("import path escapes its configured root: '$normalizedRequest'")
                }
                if (sourceAvailable(resolved)) {
                    // Keep emitted absolute C includes and SourceId values
                    // stable on platforms whose temporary directory is a
                    // symlink (macOS commonly exposes /var through /private).
                    // The old lexical path could differ from Path.toRealPath()
                    // even though both referred to the same file.
                    val canonical = try { resolved.toRealPath() } catch (_: Exception) { resolved }
                    if (confined) {
                        val canonicalRoot = try { normalizedRoot.toRealPath() } catch (_: Exception) { normalizedRoot }
                        if (!canonical.startsWith(canonicalRoot)) {
                            throw CPlusImportResolutionException("import path escapes its configured root: '$normalizedRequest'")
                        }
                    }
                    return canonical
                }
            }
        }
        throw CPlusImportResolutionException("imported file does not exist: '$normalizedRequest'")
    }
}

private fun pathFromSourceName(name: String): Path =
    if (name.startsWith("file:", ignoreCase = true)) Paths.get(URI(name)) else Paths.get(name)

class CPlusImportResolutionException(message: String) : IllegalArgumentException(message)
