package cplus.intellij

import com.intellij.openapi.project.Project
import java.io.File

/** Resolves user-facing C-plus commands without requiring cpc to be on IntelliJ's PATH. */
internal object CPlusCommand {
    fun resolve(command: String, project: Project): List<String> = resolve(command, project.basePath)

    fun resolve(
        command: String,
        projectBasePath: String?,
        isWindows: Boolean = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true),
        exists: (String) -> Boolean = { File(it).isFile }
    ): List<String> {
        val parts = CPlusLspCommand.parse(command)
        require(parts.isNotEmpty()) { "C-plus command must not be empty" }

        val executable = parts.first()
        val name = File(executable).name.lowercase()
        val isBareCpc = name == "cplus" || name == "cpc" ||
            name == "cpc.sh" || name == "cpc.cmd" || name == "cpc.exe"
        if (!isBareCpc) return parts

        val discovered = CPlusLspCommand.discover(
            configured = "",
            projectBasePath = projectBasePath,
            isWindows = isWindows,
            exists = exists
        )
        return listOf(discovered) + parts.drop(1)
    }

    fun configureJava(processBuilder: ProcessBuilder): ProcessBuilder {
        CPlusLspCommand.ideJavaExecutable()?.let { processBuilder.environment()["CPLUS_JAVA"] = it }
        return processBuilder
    }
}
