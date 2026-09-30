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
        // Keep the persistent Output tab as a welcome/diagnostic console, but
        // give every run its own closeable content. Reusing the first content
        // hid the close handle and made completed runs impossible to dismiss.
        val console = create(project)
        val newContent = content(console, title)
        contentManager.addContent(newContent)
        contentManager.setSelectedContent(newContent)
        console.clear()
        val commandText = command.joinToString(" ")
        console.print("> $commandText\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        toolWindow.show()
        return console
    }
}
