package cplus.intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import java.io.File

/** Starts the repository's CLI language server for C-plus files opened in IntelliJ. */
class CPlusLspIntegrationProvider : LspIntegrationProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        clientStarter: LspIntegrationProvider.LspClientStarter
    ) {
        if (file.extension == "cp" || file.extension == "c+") {
            clientStarter.ensureClientStarted(CPlusLspServerDescriptor(project))
        }
    }
}

private class CPlusLspServerDescriptor(project: Project) : ProjectWideLspClientDescriptor(project, "C-plus") {
    override fun isSupportedFile(file: VirtualFile): Boolean =
        file.extension == "cp" || file.extension == "c+"

    override fun createCommandLine(): GeneralCommandLine {
        val configured = CPlusSettings.getInstance().current().languageServerCommand.trim()
        val commandLine = GeneralCommandLine(CPlusLspCommand.arguments(
            CPlusLspCommand.discover(configured, project.basePath)
        ))
        CPlusLspCommand.ideJavaExecutable()?.let { commandLine.withEnvironment("CPLUS_JAVA", it) }
        return commandLine
    }
}

internal object CPlusLspCommand {
    fun ideJavaExecutable(
        javaHome: String = System.getProperty("java.home").orEmpty(),
        isWindows: Boolean = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true),
        exists: (File) -> Boolean = { it.isFile && it.canExecute() }
    ): String? {
        val executable = if (isWindows) "java.exe" else "java"
        return File(File(javaHome, "bin"), executable).takeIf(exists)?.path
    }

    fun discover(configured: String, projectBasePath: String?, isWindows: Boolean = System.getProperty("os.name")
        .orEmpty().contains("win", ignoreCase = true), exists: (String) -> Boolean = { File(it).isFile }): String {
        if (configured.isNotBlank()) return configured.trim()
        val root = projectBasePath?.takeIf { it.isNotBlank() }
        val names = if (isWindows) listOf("cpc.cmd", "cpc.exe", "cpc") else listOf("cpc.sh", "cpc")
        val candidates = buildList {
            if (root != null) {
                addAll(names.map { File(File(root, ".cplus"), it).path })
                addAll(names.map { File(File(root, "c-plus-bin"), it).path })
                addAll(names.map { File(root, it).path })
            }
            System.getenv("CPLUS_HOME")?.takeIf { it.isNotBlank() }?.let { home ->
                addAll(names.map { File(home, it).path })
            }
            val userHome = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
            if (userHome != null) {
                addAll(names.map { File(File(userHome, ".local/bin"), it).path })
                addAll(names.map { File(File(userHome, ".local/share/c-plus"), it).path })
            }
        }
        candidates.firstOrNull(exists)?.let { return it }
        return "cpc"
    }

    fun arguments(command: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (character in command) {
            when {
                escaped -> {
                    current.append(character)
                    escaped = false
                }
                character == '\\' && quote != '\'' -> escaped = true
                quote != null && character == quote -> quote = null
                quote == null && (character == '\'' || character == '"') -> quote = character
                quote == null && character.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(character)
            }
        }
        if (escaped) current.append('\\')
        if (current.isNotEmpty()) result += current.toString()
        require(result.isNotEmpty()) { "C-plus language server command must not be empty" }
        return result + "lsp"
    }
}
