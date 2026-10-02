package cplus.intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import org.eclipse.lsp4j.InitializeResult
import java.io.File

/** Starts the repository's CLI language server for C-plus files opened in IntelliJ. */
class CPlusLspIntegrationProvider : LspIntegrationProvider {
    private val logger = Logger.getInstance(CPlusLspIntegrationProvider::class.java)

    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        clientStarter: LspIntegrationProvider.LspClientStarter
    ) {
        logger.info("C-plus LSP fileOpened: ${file.path} extension=${file.extension}")
        if (file.extension == "cp" || file.extension == "c+") {
            logger.info("C-plus LSP starting project client: ${project.basePath}")
            clientStarter.ensureClientStarted(CPlusLspServerDescriptor(project))
        }
    }

    override fun createWidgetItem(
        lspClient: LspClient,
        currentFile: VirtualFile?
    ): LspClientWidgetItem = LspClientWidgetItem(
        lspClient,
        currentFile,
        CPlusFileType.INSTANCE.icon,
        CPlusSettingsConfigurable::class.java
    )
}

private class CPlusLspServerDescriptor(project: Project) : ProjectWideLspClientDescriptor(project, "C-plus") {
    private val logger = Logger.getInstance(CPlusLspServerDescriptor::class.java)

    override fun getLanguageId(file: VirtualFile): String = "cplus"

    override val lspServerListener: LspServerListener = object : LspServerListener {
        override fun serverInitialized(params: InitializeResult) {
            logger.info("C-plus LSP initialized; capabilities=${params.capabilities}")
        }

        override fun serverStopped(shutdownNormally: Boolean) {
            logger.info("C-plus LSP stopped; normal=$shutdownNormally")
            if (shutdownNormally || project.isDisposed) return
            val retry = CPlusLspRecovery.getInstance(project).claimRestart()
            NotificationGroupManager.getInstance().getNotificationGroup("C-plus language server")
                .createNotification(if (retry) "C-plus language server exited; restarting once." else
                    "C-plus language server exited again. Check Settings → Tools → C-plus command diagnostics, then restart manually.",
                    NotificationType.WARNING).notify(project)
            if (retry) ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) LspClientManager.getInstance(project)
                    .stopAndRestartClientsIfNeeded(CPlusLspIntegrationProvider::class.java)
            }
        }
    }

    override fun isSupportedFile(file: VirtualFile): Boolean =
        (file.extension == "cp" || file.extension == "c+").also {
            logger.info("C-plus LSP supported-file check: ${file.path} -> $it")
        }

    override fun createCommandLine(): GeneralCommandLine {
        val configured = CPlusSettings.getInstance().current().languageServerCommand.trim()
        val arguments = CPlusCommand.execution(configured, project.basePath, listOf("lsp"))
        logger.info("C-plus LSP command: ${arguments.joinToString(" ")}")
        val commandLine = GeneralCommandLine(arguments)
        project.basePath?.let { commandLine.withWorkDirectory(it) }
        CPlusCommand.parseEnvironment(CPlusSettings.getInstance().current().environment, System.getenv())
            .forEach { (name, value) -> commandLine.withEnvironment(name, value) }
        CPlusLspCommand.ideJavaExecutable()?.let { commandLine.withEnvironment("CPLUS_JAVA", it) }
        logger.info("C-plus LSP work directory: ${commandLine.workDirectory}; environment override names: " +
            CPlusCommand.parseEnvironment(CPlusSettings.getInstance().current().environment).keys.joinToString(", "))
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

    fun discover(
        configured: String,
        projectBasePath: String?,
        isWindows: Boolean = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true),
        userHome: String? = System.getProperty("user.home"),
        exists: (String) -> Boolean = { File(it).isFile }
    ): String {
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
            val localHome = userHome?.takeIf { it.isNotBlank() }
            if (localHome != null) {
                addAll(names.flatMap { name ->
                    listOf(
                        File(File(localHome, ".local/bin"), name).path,
                        File(File(File(localHome, ".local/bin"), "c-plus"), name).path,
                        File(File(localHome, ".local/share/c-plus"), name).path
                    )
                })
            }
        }
        candidates.firstOrNull(exists)?.let { return it }
        return "cpc"
    }

    fun arguments(command: String): List<String> {
        return parse(command) + "lsp"
    }

    fun parse(command: String): List<String> {
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
        return result
    }
}
