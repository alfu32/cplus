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

    /** Builds a process invocation, allowing configured shell statements separated by semicolons. */
    fun execution(command: String, projectBasePath: String?, appendedArguments: List<String> = emptyList()): List<String> {
        val statements = splitStatements(command)
        require(statements.isNotEmpty()) { "C-plus command must not be empty" }
        if (statements.size == 1) return resolve(statements.single(), projectBasePath) + appendedArguments

        val rewritten = statements.map { rewriteDiscoverableExecutable(it, projectBasePath) }.toMutableList()
        if (appendedArguments.isNotEmpty()) {
            rewritten[rewritten.lastIndex] += appendedArguments.joinToString(" ", prefix = " ", transform = ::shellQuote)
        }
        val shellCommand = rewritten.joinToString("; ")
        return if (System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)) {
            listOf("cmd.exe", "/d", "/s", "/c", shellCommand)
        } else {
            listOf("sh", "-c", shellCommand)
        }
    }

    fun configureJava(processBuilder: ProcessBuilder): ProcessBuilder {
        CPlusLspCommand.ideJavaExecutable()?.let { processBuilder.environment()["CPLUS_JAVA"] = it }
        return processBuilder
    }

    fun configureEnvironment(processBuilder: ProcessBuilder, environmentText: String): ProcessBuilder {
        processBuilder.environment().putAll(parseEnvironment(environmentText, processBuilder.environment()))
        return processBuilder
    }

    fun probe(command: String, environmentText: String, timeoutSeconds: Long = 10): CPlusCommandProbeResult {
        val overrides = parseEnvironment(environmentText, System.getenv())
        val workingDirectory = File(System.getProperty("user.dir").orEmpty().ifBlank { "." }).absoluteFile
        val resolved = try {
            execution(command, workingDirectory.path)
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

    internal fun parseEnvironment(text: String, base: Map<String, String> = System.getenv()): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val separator = line.indexOf('=')
            require(separator > 0) { "environment line ${index + 1} must use NAME=VALUE" }
            val name = line.substring(0, separator).trim()
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "invalid environment name '$name' on line ${index + 1}" }
            result[name] = expandEnvironmentValue(unquoteEnvironmentValue(line.substring(separator + 1).trim()), result, base)
        }
        return result
    }

    private fun unquoteEnvironmentValue(value: String): String {
        if (value.length < 2) return value
        val first = value.first()
        val last = value.last()
        return if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    private fun expandEnvironmentValue(value: String, overrides: Map<String, String>, base: Map<String, String>): String {
        fun lookup(name: String): String = overrides[name] ?: base[name] ?: ""
        var expanded = value.replace(Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")) { lookup(it.groupValues[1]) }
        expanded = expanded.replace(Regex("\\$([A-Za-z_][A-Za-z0-9_]*)")) { lookup(it.groupValues[1]) }
        if (System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)) {
            expanded = expanded.replace(Regex("%([A-Za-z_][A-Za-z0-9_]*)%")) { lookup(it.groupValues[1]) }
        }
        return expanded
    }

    internal fun splitStatements(command: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        command.forEach { character ->
            when {
                escaped -> { current.append(character); escaped = false }
                character == '\\' && quote != '\'' -> { current.append(character); escaped = true }
                quote != null && character == quote -> { current.append(character); quote = null }
                quote == null && (character == '\'' || character == '"') -> { current.append(character); quote = character }
                quote == null && character == ';' -> {
                    if (current.toString().trim().isNotEmpty()) result += current.toString().trim()
                    current.clear()
                }
                else -> current.append(character)
            }
        }
        if (current.toString().trim().isNotEmpty()) result += current.toString().trim()
        return result
    }

    private fun rewriteDiscoverableExecutable(statement: String, projectBasePath: String?): String {
        val match = Regex("^(\\s*)(?:'|\\\")?(cpc(?:\\.sh|\\.cmd|\\.exe)?|cplus)(?:'|\\\")?(?=\\s|$)", RegexOption.IGNORE_CASE)
            .find(statement) ?: return statement
        val discovered = resolve(match.groupValues[2], projectBasePath).first()
        return match.groupValues[1] + shellQuote(discovered) + statement.substring(match.range.last + 1)
    }

    private fun shellQuote(value: String): String =
        if (System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)) {
            "\"${value.replace("\"", "\\\"")}\""
        } else {
            "'${value.replace("'", "'\\\"'\\\"'")}'"
        }
}
