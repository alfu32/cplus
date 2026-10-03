package cplus.intellij

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

/** Creates the same portable project layout as `cpc new`. */
internal class CPlusNewProjectAction : AnAction("C-plus Project") {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project
        val selected = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFolderDescriptor(),
            project,
            null
        ) ?: return
        val directory = Path.of(selected.path)
        val name = directory.fileName?.toString()?.ifBlank { "cplus-project" } ?: "cplus-project"
        try {
            if (Files.exists(directory.resolve("cplus.toml"))) {
                Messages.showErrorDialog(project, "The selected directory already contains cplus.toml.", "C-plus Project")
                return
            }
            Files.createDirectories(directory.resolve("src"))
            Files.createDirectories(directory.resolve("modules"))
            Files.createDirectories(directory.resolve("tests"))
            Files.writeString(directory.resolve("cplus.toml"), """name = "${name.replace("\"", "\\\"")}"
version = "0.1.0"
source = "src"
stdlib = "auto"
module-paths = ["src", "modules"]
dependencies = []
""")
            Files.writeString(directory.resolve("src/main.cp"), """#include <stdio.h>

int main(void) {
    puts("Hello from C-plus!");
    return 0;
}
""")
            Files.writeString(directory.resolve("README.md"), "# $name\n\nRun with `cpc run src/main.cp`.\n")
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)
            Messages.showInfoMessage(project, "Created a C-plus project in $directory", "C-plus Project")
        } catch (error: Exception) {
            Messages.showErrorDialog(project, error.message ?: "Could not create project", "C-plus Project")
        }
    }
}

