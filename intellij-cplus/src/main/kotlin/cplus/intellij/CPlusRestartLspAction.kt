package cplus.intellij

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.platform.lsp.api.LspClientManager

/** Requests a bounded restart through IntelliJ's LSP lifecycle manager. */
class CPlusRestartLspAction : AnAction("Restart C-plus Language Server") {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        LspClientManager.getInstance(project)
            .stopAndRestartClientsIfNeeded(CPlusLspIntegrationProvider::class.java)
    }
}
