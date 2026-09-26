package cplus.intellij

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.nio.file.Files

class CPlusShowImportGraphAction : AnAction("Show Import Graph", "Resolve and navigate C-plus imports", null) {
    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        event.presentation.isEnabledAndVisible = event.project != null && file.isCPlusSource()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (!file.isCPlusSource()) return
        val editor = event.getData(CommonDataKeys.EDITOR)
        editor?.document?.let { FileDocumentManager.getInstance().saveDocument(it) }
        val commandText = CPlusSettings.getInstance().current().importGraphCommand.trim()
        val command = splitCommand(commandText)
        if (command.isEmpty()) {
            Messages.showErrorDialog(project, "Configure the C-plus import graph command in Settings → Tools → C-plus.", "C-plus Import Graph")
            return
        }

        object : Task.Backgroundable(project, "C-plus: resolving imports", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = runCatching { execute(file, command) }
                ApplicationManager.getApplication().invokeLater {
                    result.fold(
                        onSuccess = { graph -> showGraph(project, file, graph, editor) },
                        onFailure = { error ->
                            Messages.showErrorDialog(
                                project,
                                error.message ?: "Could not resolve imports for ${file.path}",
                                "C-plus Import Graph"
                            )
                        }
                    )
                }
            }
        }.queue()
    }

    private fun execute(source: VirtualFile, command: List<String>): CPlusImportGraph {
        val process = ProcessBuilder(command + source.path)
            .directory(source.parent?.path?.let(::File))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException(output.trim().ifEmpty { "cplus graph exited with status $exitCode" })
        }
        return CPlusImportGraphJson.decode(output)
    }

    private fun showGraph(
        project: Project,
        source: VirtualFile,
        graph: CPlusImportGraph,
        editor: com.intellij.openapi.editor.Editor?
    ) {
        if (graph.imports.isEmpty()) {
            Messages.showInfoMessage(project, "${source.name} has no resolved C-plus imports.", "C-plus Import Graph")
            return
        }
        val chooser = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(graph.imports)
            .setTitle("C-plus Imports — ${graph.dependencyOrder.size} sources")
            .setItemChosenCallback { edge ->
                val imported = runCatching { java.nio.file.Path.of(edge.imported) }.getOrNull()
                if (imported != null && Files.isRegularFile(imported)) {
                    val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(imported)
                    if (virtualFile != null) {
                        OpenFileDescriptor(project, virtualFile).navigate(true)
                    } else {
                        Messages.showErrorDialog(project, "Could not open imported source:\n${edge.imported}", "C-plus Import Graph")
                    }
                } else {
                    Messages.showErrorDialog(project, "Imported source no longer exists:\n${edge.imported}", "C-plus Import Graph")
                }
            }
            .createPopup()
        editor?.let(chooser::showInBestPositionFor)
            ?: chooser.showCenteredInCurrentWindow(project)
    }

    private fun VirtualFile?.isCPlusSource(): Boolean = this != null && extension in setOf("cp", "c+")

    private fun splitCommand(command: String): List<String> =
        Regex("\\\"([^\\\"]*)\\\"|'([^']*)'|([^\\s]+)")
            .findAll(command)
            .map { it.groups[1]?.value ?: it.groups[2]?.value ?: it.groups[3]!!.value }
            .toList()
}
