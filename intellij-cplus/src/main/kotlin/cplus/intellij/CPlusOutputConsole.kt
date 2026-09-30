package cplus.intellij

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.ContentFactory

class CPlusOutputToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val console = CPlusOutputConsole.create(project)
        console.print("Run a C-plus test or main to see its output here.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        toolWindow.contentManager.addContent(CPlusOutputConsole.content(console, "Output"))
    }
}

internal object CPlusOutputConsole {
    private val consoleKey = Key.create<ConsoleView>("cplus.output.console")

    fun create(project: Project): ConsoleView = ConsoleViewImpl(project, false)

    fun content(console: ConsoleView, title: String) = ContentFactory.getInstance()
        .createContent(console.component, title, true)
        .also {
            it.putUserData(consoleKey, console)
            Disposer.register(it, console)
        }

    fun open(project: Project, title: String, command: List<String>): ConsoleView? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("C-plus") ?: return null
        val contentManager = toolWindow.contentManager
        // Keep one persistent Output tab. Repeated gutter runs replace its
        // contents instead of creating text tabs that cannot be dismissed.
        val outputContent = contentManager.contents.firstOrNull { it.displayName == "Output" }
        val console = outputContent?.getUserData(consoleKey) ?: create(project).also {
            val newContent = content(it, "Output")
            contentManager.addContent(newContent)
        }
        val selectedContent = contentManager.contents.firstOrNull { it.displayName == "Output" }
        if (selectedContent != null) contentManager.setSelectedContent(selectedContent)
        console.clear()
        val commandText = command.joinToString(" ")
        console.print("> $commandText\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        toolWindow.show()
        return console
    }
}
