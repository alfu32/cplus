package cplus.intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor

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
        return GeneralCommandLine(CPlusLspCommand.arguments(configured.ifEmpty { "cplus" }))
    }
}

internal object CPlusLspCommand {
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
