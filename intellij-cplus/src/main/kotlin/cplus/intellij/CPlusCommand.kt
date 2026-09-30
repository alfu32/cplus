package cplus.intellij

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

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

    fun configureEnvironment(processBuilder: ProcessBuilder, environmentText: String): ProcessBuilder {
        processBuilder.environment().putAll(parseEnvironment(environmentText))
        return processBuilder
    }

    fun probe(command: String, environmentText: String, timeoutSeconds: Long = 10): CPlusCommandProbeResult {
        val overrides = parseEnvironment(environmentText)
        val workingDirectory = File(System.getProperty("user.dir").orEmpty().ifBlank { "." }).absoluteFile
        val resolved = try {
            resolve(command, workingDirectory.path)
        } catch (error: Exception) {
            return CPlusCommandProbeResult(null, workingDirectory.path, overrides, "", null, error.message ?: error.javaClass.simpleName)
        }
        val process = try {
            ProcessBuilder(resolved).directory(workingDirectory).redirectErrorStream(true).apply {
                environment().putAll(overrides)
                configureJava(this)
            }.start()
        } catch (error: Exception) {
            return CPlusCommandProbeResult(resolved, workingDirectory.path, overrides, "", null, error.message ?: error.javaClass.simpleName)
        }
        val outputBuffer = StringBuilder()
        val outputThread = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(outputBuffer) {
                            if (outputBuffer.length < 32_768) outputBuffer.append(line).append('\n')
                        }
                    }
                }
            } catch (error: Exception) {
                synchronized(outputBuffer) { outputBuffer.append("[could not read process output: ${error.message}]\n") }
            }
        }.apply { isDaemon = true; name = "cplus-command-probe-output"; start() }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            outputThread.join(500)
            return CPlusCommandProbeResult(resolved, workingDirectory.path, overrides, outputBuffer.toString(), null, "timed out after ${timeoutSeconds}s")
        }
        outputThread.join(500)
        return CPlusCommandProbeResult(resolved, workingDirectory.path, overrides, outputBuffer.toString(), process.exitValue(), null)
    }

    internal fun parseEnvironment(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val separator = line.indexOf('=')
            require(separator > 0) { "environment line ${index + 1} must use NAME=VALUE" }
            val name = line.substring(0, separator).trim()
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "invalid environment name '$name' on line ${index + 1}" }
            result[name] = line.substring(separator + 1)
        }
        return result
    }
}
