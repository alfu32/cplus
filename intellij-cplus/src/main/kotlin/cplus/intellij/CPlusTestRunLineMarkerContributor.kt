package cplus.intellij

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.psi.PsiElement
import com.intellij.execution.lineMarker.RunLineMarkerContributor

class CPlusTestRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        val file = element.containingFile
        val virtualFile = file.virtualFile ?: return null
        val parserFixtures = CPlusParserTreeCache.fixtures(virtualFile.path, file.text)
        val fixture = if (element.text == "@test") {
            parserFixtures?.firstOrNull { it.startOffset == element.textOffset }?.let {
                CPlusTestFixture(it.name, it.startOffset, it.endOffset)
            } ?: if (parserFixtures == null) {
                CPlusTestFixtures.find(file.text).firstOrNull { it.start == element.textOffset }
            } else null
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
                val console = CPlusOutputConsole.open(project, title, command)
                if (console == null) return
                object : Task.Backgroundable(project, "C-plus: $title", true) {
                    override fun run(indicator: ProgressIndicator) {
                        val process = try {
                            val builder = ProcessBuilder(command)
                                .redirectErrorStream(true)
                            virtualFile.parent?.path?.let { builder.directory(java.io.File(it)) }
                            builder.start()
                        } catch (error: Exception) {
                            console.print("Could not start '$commandText': ${error.message}\n", com.intellij.execution.ui.ConsoleViewContentType.ERROR_OUTPUT)
                            return
                        }
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { console.print("$it\n", com.intellij.execution.ui.ConsoleViewContentType.NORMAL_OUTPUT) }
                        }
                        val exitCode = process.waitFor()
                        val contentType = if (exitCode == 0) com.intellij.execution.ui.ConsoleViewContentType.SYSTEM_OUTPUT
                            else com.intellij.execution.ui.ConsoleViewContentType.ERROR_OUTPUT
                        console.print("\nProcess finished with exit code $exitCode\n", contentType)
                    }
                }.queue()
            }
        }
        return Info(AllIcons.RunConfigurations.TestState.Run, { "Run C-plus ${if (fixture == null) "program" else "test"}: $title" }, action)
    }

    private fun splitCommand(command: String): List<String> =
        Regex("\\\"([^\\\"]*)\\\"|'([^']*)'|([^\\s]+)")
            .findAll(command)
            .map { it.groups[1]?.value ?: it.groups[2]?.value ?: it.groups[3]!!.value }
            .toList()
}
