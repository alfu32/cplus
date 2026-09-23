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
    } catch (error: CPlusSyntaxException) {
        val span = error.sourceSpan
        if (span?.file != null) {
            System.err.println("${span.file}:${span.startLine}:${span.startColumn}: error: ${error.message}")
        } else {
            System.err.println("cplus: error: ${error.message}")
        }
        2
    } catch (error: Exception) {
        System.err.println("cplus: ${error.message ?: error::class.simpleName}")
        2
    }
    if (exitCode != 0) exitProcess(exitCode)
}

class CPlusCli(
    private val output: Appendable = System.out,
    private val errors: Appendable = System.err,
    private val transpiler: CPlusTranspiler = CPlusTranspiler(),
    private val compiler: TccCompiler = TccCompiler(),
    private val logger: CompilationLogger = ConsoleCompilationLogger(errors)
) {
    private var cliStdlibRoot: Path? = null

    fun run(arguments: List<String>): Int {
        cliStdlibRoot = null
        val commandArguments = mutableListOf<String>()
        var index = 0
        while (index < arguments.size) {
            if (arguments[index] == "--stdlib") {
                if (index + 1 >= arguments.size) throw IllegalArgumentException("--stdlib requires a directory")
                val directory = Path(arguments[index + 1]).toAbsolutePath().normalize()
                if (!Files.isDirectory(directory)) throw IllegalArgumentException("standard-library directory does not exist: $directory")
                cliStdlibRoot = directory
                index += 2
            } else {
                commandArguments += arguments[index++]
            }
        }
        if (commandArguments.isEmpty() || commandArguments.first() in setOf("help", "--help", "-h")) {
            printHelp()
            return 0
        }

        return when (val command = commandArguments.first()) {
            "transcode" -> transcode(commandArguments.drop(1))
            "compile" -> compile(commandArguments.drop(1), runAfter = false)
            "run" -> compile(commandArguments.drop(1), runAfter = true)
            "test" -> test(commandArguments.drop(1))
            "new" -> newProject(commandArguments.drop(1))
            "version" -> {
                output.append(Version().toString()).append('\n')
                0
            }
            else -> throw IllegalArgumentException("unknown command '$command'; use 'cplus help'")
        }
    }

    private fun transcode(arguments: List<String>): Int {
        val parsed = parseFileCommand(arguments, allowTccOptions = false)
        val destination = parsed.output ?: defaultTranscodedPath(parsed.source)
        val sourcePath = parsed.source.toAbsolutePath().normalize()
        val importPaths = importPathsFor(sourcePath)
        val source = logger.pass("read-source") { readSource(sourcePath) }
        val transcoded = transpiler.transpile(source, sourcePath.toString(), logger, importPaths)
        printAllocationDiagnostics(transcoded)
        logger.pass("write-c") { writeText(destination, transcoded.code) }
        return 0
    }

    private fun compile(arguments: List<String>, runAfter: Boolean): Int {
        val parsed = parseFileCommand(arguments, allowTccOptions = true)
        val destination = parsed.output ?: defaultExecutablePath(parsed.source)
        val sourcePath = parsed.source.toAbsolutePath().normalize()
        val importPaths = importPathsFor(sourcePath)
        val source = logger.pass("read-source") { readSource(sourcePath) }
        val transcoded = transpiler.transpile(source, sourcePath.toString(), logger, importPaths)
        printAllocationDiagnostics(transcoded)
        val options = buildList {
            sourcePath.parent?.let { add("-I${it}") }
            add("-I${Path("").toAbsolutePath().normalize()}")
            importPaths.moduleRoots.forEach { add("-I$it") }
            importPaths.standardLibraryRoots.forEach { add("-I$it") }
            addAll(parsed.passthrough)
        }

        val result = compiler.compileExecutable(transcoded, destination, options, logger)
        result.diagnostics.forEach(::printDiagnostic)
        if (result.exitCode != 0) return result.exitCode
        if (!runAfter) return 0

        return logger.pass("run-executable") {
            val process = ProcessBuilder(destination.toAbsolutePath().normalize().toString())
                .inheritIO()
                .start()
            process.waitFor()
        }
    }

    private fun test(arguments: List<String>): Int {
        val sourceCount = arguments.takeWhile(::isCPlusSource).size
        if (sourceCount == 0) throw IllegalArgumentException("test requires one or more .cp or .c+ source files")
        val sources = arguments.take(sourceCount).map(::Path).map { it.toAbsolutePath().normalize() }
        val requestedNames = arguments.drop(sourceCount).distinct()
        sources.forEach { path ->
            if (!Files.isRegularFile(path)) throw IllegalArgumentException("test source does not exist: $path")
        }

        val compiledSources = sources.map { path ->
            val importPaths = importPathsFor(path)
            val source = logger.pass("read-source") { readSource(path) }
            val testSource = transpiler.transpileTests(source, path.toString(), logger, importPaths)
            printAllocationDiagnostics(testSource.source)
            TestSource(path, testSource, importPaths)
        }
        val allNames = compiledSources.flatMap { it.transcoded.testNames }.toSet()
        if (allNames.isEmpty()) {
            errors.append("cplus: no @test declarations found in the input sources\n")
            return 2
        }
        val unmatched = requestedNames.filterNot { it in allNames }
        if (unmatched.isNotEmpty()) {
            errors.append("cplus: unknown test name(s): ").append(unmatched.joinToString(", ")).append('\n')
            return 2
        }

        val temporaryDirectory = Files.createTempDirectory("cplus-tests-")
        var failed = 0
        try {
            compiledSources.forEachIndexed { index, compiled ->
                if (requestedNames.isNotEmpty() && compiled.transcoded.testNames.none { it in requestedNames }) {
                    return@forEachIndexed
                }
                val executable = temporaryDirectory.resolve("test-$index")
                val options = buildList {
                    compiled.path.parent?.let { add("-I$it") }
                    add("-I${Path("").toAbsolutePath().normalize()}")
                    compiled.importPaths.moduleRoots.forEach { add("-I$it") }
                    compiled.importPaths.standardLibraryRoots.forEach { add("-I$it") }
                }
                val result = compiler.compileExecutable(compiled.transcoded.source, executable, options, logger)
                result.diagnostics.forEach(::printDiagnostic)
                if (result.exitCode != 0) {
                    failed++
                    return@forEachIndexed
                }

                val command = buildList {
                    add(executable.toAbsolutePath().normalize().toString())
                    addAll(requestedNames)
                }
                val exitCode = logger.pass("run-tests") {
                    ProcessBuilder(command).inheritIO().start().waitFor()
                }
                if (exitCode != 0) failed++
            }
        } finally {
            Files.walk(temporaryDirectory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
        return if (failed == 0) 0 else 1
    }

    private fun isCPlusSource(argument: String): Boolean =
        sourceExtensions.any(argument::endsWith)

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

    private fun importPathsFor(source: Path): CPlusImportPaths {
        val project = CPlusProject.find(source) ?: CPlusProject.find(Path("").toAbsolutePath().normalize())
        return project?.importPaths(cliStdlibRoot, System.getenv("CPLUS_STDLIB"))
            ?: CPlusImportPaths(
                CPlusProject.defaultStandardLibraryRoots(cliStdlibRoot, System.getenv("CPLUS_STDLIB")),
                emptyList()
            )
    }

    private fun newProject(arguments: List<String>): Int {
        if (arguments.size != 1) throw IllegalArgumentException("new requires a project directory or '.'")
        val target = if (arguments.single() == ".") Path("").toAbsolutePath().normalize() else Path(arguments.single())
        val created = CPlusProjectScaffolder.create(target)
        output.append("Created C-plus project at ").append(created.toString()).append('\n')
        output.append("Next: cd ").append(created.toString()).append(" && cpc run src/main.cp\n")
        return 0
    }

    private fun writeText(path: Path, text: String) {
        path.toAbsolutePath().parent?.let(Files::createDirectories)
        path.writeText(text, Charsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    private fun printDiagnostic(diagnostic: CompilerDiagnostic) {
        val location = when {
            diagnostic.file != null && diagnostic.line != null && diagnostic.column != null ->
                "${diagnostic.file}:${diagnostic.line}:${diagnostic.column}"
            diagnostic.file != null && diagnostic.line != null -> "${diagnostic.file}:${diagnostic.line}"
            diagnostic.file != null -> diagnostic.file
            else -> "<generated>"
        }
        errors.append(location)
            .append(": ")
            .append(diagnostic.severity.name.lowercase())
            .append(": ")
            .append(diagnostic.message)
            .append('\n')
    }

    private fun printAllocationDiagnostics(source: TranscodedSource) {
        source.allocationAnalysis.diagnostics.forEach { diagnostic ->
            val span = diagnostic.sourceSpan
            val location = span.file?.let { "$it:${span.startLine}:${span.startColumn}" } ?: "<c-plus-input>"
            errors.append(location).append(": warning: ").append(diagnostic.message).append('\n')
        }
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
  cplus version
  cplus transcode filename.cp [-o some_file_name.c]
  cplus compile filename.cp [-o executable] [passthrough tcc parameters]
  cplus run filename.cp [-o executable] [passthrough tcc parameters]
  cplus test filename.cp [filename2.cp ...] [test name ...]
  cplus new project_name|.

global options:
  --stdlib directory    use this standard-library root (also settable with CPLUS_STDLIB)

defaults:
  transcode: filename.cp -> filename.c
  compile/run: filename.cp -> filename
  test: runs all @test blocks, or only the exact names supplied after the source files
  new: creates a C-plus project with cplus.toml and src/main.cp

Imports:
  comptime import "stdlib:/containers/dynamic_list.cp"
  comptime import "module:/shared/types.cp"

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

    private data class TestSource(
        val path: Path,
        val transcoded: TranscodedTestSource,
        val importPaths: CPlusImportPaths
    )
}
