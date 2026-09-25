package cplus.intellij

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

class CPlusOutputToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val console = CPlusOutputConsole.create(project)
        console.print("Run a C-plus test or main to see its output here.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(console.component, "Output", false)
        )
    }
}

internal object CPlusOutputConsole {
    fun create(project: Project): ConsoleView = ConsoleViewImpl(project, false)

    fun open(project: Project, title: String, command: List<String>): ConsoleView? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("C-plus") ?: return null
        val console = create(project)
        val commandText = command.joinToString(" ")
        console.print("> $commandText\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(console.component, title, false)
        )
        toolWindow.show()
        return console
    }
}
