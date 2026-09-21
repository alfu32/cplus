package cplus

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

private val sourceExtensions = listOf(".cp", ".c+")

fun main(args: Array<String>) {
    val exitCode = try {
        CPlusCli().run(args.toList())
    } catch (error: Exception) {
        System.err.println("cplus: ${error.message ?: error::class.simpleName}")
        2
    }
    if (exitCode != 0) exitProcess(exitCode)
}

class CPlusCli(
    private val output: Appendable = System.out,
    private val transpiler: CPlusTranspiler = CPlusTranspiler(),
    private val compiler: TccCompiler = TccCompiler()
) {
    fun run(arguments: List<String>): Int {
        if (arguments.isEmpty() || arguments.first() in setOf("help", "--help", "-h")) {
            printHelp()
            return 0
        }

        return when (val command = arguments.first()) {
            "transcode" -> transcode(arguments.drop(1))
            "compile" -> compile(arguments.drop(1), runAfter = false)
            "run" -> compile(arguments.drop(1), runAfter = true)
            else -> throw IllegalArgumentException("unknown command '$command'; use 'cplus help'")
        }
    }

    private fun transcode(arguments: List<String>): Int {
        val parsed = parseFileCommand(arguments, allowTccOptions = false)
        val destination = parsed.output ?: defaultTranscodedPath(parsed.source)
        writeText(destination, transpiler.transpile(readSource(parsed.source)))
        return 0
    }

    private fun compile(arguments: List<String>, runAfter: Boolean): Int {
        val parsed = parseFileCommand(arguments, allowTccOptions = true)
        val destination = parsed.output ?: defaultExecutablePath(parsed.source)
        val sourcePath = parsed.source.toAbsolutePath().normalize()
        val source = transpiler.transpile(readSource(sourcePath))
        val options = buildList {
            sourcePath.parent?.let { add("-I${it}") }
            addAll(parsed.passthrough)
        }

        val result = compiler.compileExecutable(source, destination, options)
        if (result != 0) return result
        if (!runAfter) return 0

        val process = ProcessBuilder(destination.toAbsolutePath().normalize().toString())
            .inheritIO()
            .start()
        return process.waitFor()
    }

    private fun parseFileCommand(arguments: List<String>, allowTccOptions: Boolean): ParsedCommand {
        val source = arguments.firstOrNull()?.let(::Path)
            ?: throw IllegalArgumentException("missing input filename")
        var outputPath: Path? = null
        val passthrough = mutableListOf<String>()
        var index = 1
        while (index < arguments.size) {
            when (val argument = arguments[index]) {
                "-o" -> {
                    if (index + 1 >= arguments.size) throw IllegalArgumentException("-o requires an output path")
                    outputPath = Path(arguments[index + 1])
                    index += 2
                }
                else -> {
                    if (!allowTccOptions) {
                        throw IllegalArgumentException("unexpected argument '$argument' for transcode")
                    }
                    passthrough += argument
                    index++
                }
            }
        }
        return ParsedCommand(source, outputPath, passthrough)
    }

    private fun readSource(path: Path): String = path.readText()

    private fun writeText(path: Path, text: String) {
        path.toAbsolutePath().parent?.let(Files::createDirectories)
        path.writeText(text, Charsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    private fun defaultTranscodedPath(source: Path): Path {
        val base = sourceWithoutExtension(source)
        return base.resolveSibling(base.fileName.toString() + ".c")
    }

    private fun defaultExecutablePath(source: Path): Path = sourceWithoutExtension(source)

    private fun sourceWithoutExtension(source: Path): Path {
        val name = source.fileName.toString()
        val extension = sourceExtensions.firstOrNull { name.endsWith(it) }
        return if (extension == null) source else source.resolveSibling(name.removeSuffix(extension))
    }

    private fun printHelp() {
        output.append(
            """C-plus processor

usage:
  cplus help
  cplus transcode filename.cp [-o some_file_name.c]
  cplus compile filename.cp [-o executable] [passthrough tcc parameters]
  cplus run filename.cp [-o executable] [passthrough tcc parameters]

defaults:
  transcode: filename.cp -> filename.c
  compile/run: filename.cp -> filename

The access and ownership annotations are retained in generated C and defined as
empty macros: pub, priv, mut, borrowed, owned, and stat.
""".trimIndent()
        )
        output.append('\n')
    }

    private data class ParsedCommand(
        val source: Path,
        val output: Path?,
        val passthrough: List<String>
    )
}
