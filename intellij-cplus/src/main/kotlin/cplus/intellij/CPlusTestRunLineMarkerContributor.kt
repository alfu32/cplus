package cplus.intellij

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.execution.lineMarker.RunLineMarkerContributor

class CPlusTestRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        val file = element.containingFile
        val virtualFile = file.virtualFile ?: return null
        val fixture = if (element.text == "@test") {
            CPlusTestFixtures.find(file.text).firstOrNull { it.start == element.textOffset }
        } else null
        val isMain = element.text == "main" && Regex("\\bmain\\s*\\([^)]*\\)\\s*\\{")
            .containsMatchIn(file.text.substring(element.textOffset.coerceAtMost(file.text.length)))
        if (fixture == null && !isMain) return null
        val title = fixture?.name ?: "main"
        val action = object : AnAction("Run '$title'", "Run this C-plus ${if (fixture == null) "program" else "test fixture"}", AllIcons.RunConfigurations.TestState.Run) {
            override fun actionPerformed(event: AnActionEvent) {
                val project = event.project ?: return
                val settings = CPlusSettings.getInstance().current()
                val commandText = if (fixture != null) settings.testProgram.ifBlank { "cplus test" }
                    else settings.runnerCommand.ifBlank { "cplus run" }
                val command = splitCommand(commandText) + virtualFile.path + listOfNotNull(fixture?.name)
                object : Task.Backgroundable(project, "C-plus: $title", true) {
                    override fun run(indicator: ProgressIndicator) {
                        val process = try {
                            val builder = ProcessBuilder(command)
                                .redirectErrorStream(true)
                            virtualFile.parent?.path?.let { builder.directory(java.io.File(it)) }
                            builder.start()
                        } catch (error: Exception) {
                            showResult(project, title, "Could not start '$commandText': ${error.message}", false)
                            return
                        }
                        val output = process.inputStream.bufferedReader().use { it.readText() }
                        val exitCode = process.waitFor()
                        showResult(project, title, output.ifBlank { "C-plus command exited with status $exitCode" }, exitCode == 0)
                    }
                }.queue()
            }
        }
        return Info(AllIcons.RunConfigurations.TestState.Run, { "Run C-plus ${if (fixture == null) "program" else "test"}: $title" }, action)
    }

    private fun showResult(project: com.intellij.openapi.project.Project, name: String, output: String, passed: Boolean) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                Messages.showMessageDialog(project, output, "C-plus test: $name", if (passed) Messages.getInformationIcon() else Messages.getErrorIcon())
            }
        }
    }

    private fun splitCommand(command: String): List<String> =
        Regex("\\\"([^\\\"]*)\\\"|'([^']*)'|([^\\s]+)")
            .findAll(command)
            .map { it.groups[1]?.value ?: it.groups[2]?.value ?: it.groups[3]!!.value }
            .toList()
}
