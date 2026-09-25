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
        if (element.text != "@test") return null
        val file = element.containingFile
        val fixture = CPlusTestFixtures.find(file.text).firstOrNull { it.start == element.textOffset } ?: return null
        val virtualFile = file.virtualFile ?: return null
        val action = object : AnAction("Run '${fixture.name}'", "Run this C-plus test fixture", AllIcons.RunConfigurations.TestState.Run) {
            override fun actionPerformed(event: AnActionEvent) {
                val project = event.project ?: return
                val compiler = System.getenv("CPLUS_COMMAND")?.takeIf(String::isNotBlank) ?: "cplus"
                object : Task.Backgroundable(project, "C-plus: ${fixture.name}", true) {
                    override fun run(indicator: ProgressIndicator) {
                        val process = try {
                            val builder = ProcessBuilder(compiler, "test", virtualFile.path, fixture.name)
                                .redirectErrorStream(true)
                            virtualFile.parent?.path?.let { builder.directory(java.io.File(it)) }
                            builder.start()
                        } catch (error: Exception) {
                            showResult(project, fixture.name, "Could not start '$compiler': ${error.message}", false)
                            return
                        }
                        val output = process.inputStream.bufferedReader().use { it.readText() }
                        val exitCode = process.waitFor()
                        showResult(project, fixture.name, output.ifBlank { "cplus test exited with status $exitCode" }, exitCode == 0)
                    }
                }.queue()
            }
        }
        return Info(AllIcons.RunConfigurations.TestState.Run, { "Run C-plus test: ${fixture.name}" }, action)
    }

    private fun showResult(project: com.intellij.openapi.project.Project, name: String, output: String, passed: Boolean) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                Messages.showMessageDialog(project, output, "C-plus test: $name", if (passed) Messages.getInformationIcon() else Messages.getErrorIcon())
            }
        }
    }
}
