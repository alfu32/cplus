package cplus

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Shared path policy for source imports in the compiler and parser migration pipeline. */
class CPlusImportResolver(private val importPaths: CPlusImportPaths = CPlusImportPaths()) {
    fun resolve(
        source: SourceFile,
        requestedPath: String,
        extensionlessCandidates: List<String>
    ): Path {
        val (roots, relativePath, confined) = when {
            requestedPath.startsWith("stdlib:/") -> Triple(importPaths.standardLibraryRoots, requestedPath.removePrefix("stdlib:/"), true)
            requestedPath.startsWith("module:/") -> Triple(importPaths.moduleRoots, requestedPath.removePrefix("module:/"), true)
            requestedPath.startsWith("project:/") -> Triple(importPaths.moduleRoots, requestedPath.removePrefix("project:/"), true)
            else -> {
                val sourceName = source.name
                    ?: throw CPlusImportResolutionException("import requires a named source file")
                val base = try {
                    Paths.get(sourceName).toAbsolutePath().normalize().parent
                } catch (error: Exception) {
                    throw CPlusImportResolutionException("cannot determine the directory of $sourceName: ${error.message}")
                } ?: throw CPlusImportResolutionException("cannot determine the directory of $sourceName")
                Triple(listOf(base), requestedPath, false)
            }
        }
        if (roots.isEmpty()) {
            val namespace = requestedPath.substringBefore(":/")
            throw CPlusImportResolutionException("no $namespace search path is configured for import '$requestedPath'")
        }

        val requested = try {
            Paths.get(relativePath.replace('/', java.io.File.separatorChar))
        } catch (error: Exception) {
            throw CPlusImportResolutionException("invalid import path '$requestedPath': ${error.message}")
        }
        val extension = requestedPath.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "")
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
                    throw CPlusImportResolutionException("import path escapes its configured root: '$requestedPath'")
                }
                if (Files.isRegularFile(resolved)) return resolved
            }
        }
        throw CPlusImportResolutionException("imported file does not exist: '$requestedPath'")
    }
}

class CPlusImportResolutionException(message: String) : IllegalArgumentException(message)
