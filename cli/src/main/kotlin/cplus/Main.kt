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
        val parsed = parseFileCommand(arguments, allowTccOptions = true)
        validateTranscodeTargetOptions(parsed.passthrough)
        printTranscoderVersion()
        val destination = parsed.output ?: defaultTranscodedPath(parsed.source)
        val sourcePath = parsed.source.toAbsolutePath().normalize()
        val importPaths = importPathsFor(sourcePath)
        val source = logger.pass("read-source") { readSource(sourcePath) }
        val transcoded = transpiler.transpile(
            source,
            sourcePath.toString(),
            logger,
            importPaths,
            CPlusTarget.osFromCompilerOptions(parsed.passthrough)
        )
        printAllocationDiagnostics(transcoded)
        logger.pass("write-c") { writeText(destination, transcoded.code) }
        return 0
    }

    private fun compile(arguments: List<String>, runAfter: Boolean): Int {
        val parsed = parseFileCommand(arguments, allowTccOptions = true)
        printTranscoderVersion()
        val destination = parsed.output ?: defaultExecutablePath(parsed.source)
        val sourcePath = parsed.source.toAbsolutePath().normalize()
        val importPaths = importPathsFor(sourcePath)
        val source = logger.pass("read-source") { readSource(sourcePath) }
        val transcoded = transpiler.transpile(
            source,
            sourcePath.toString(),
            logger,
            importPaths,
            CPlusTarget.osFromCompilerOptions(parsed.passthrough)
        )
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
        printTranscoderVersion()

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

        val selectedFixtures = compiledSources.map { compiled ->
            compiled.transcoded.fixtures.filter { requestedNames.isEmpty() || it.name in requestedNames }
        }
        val totalFixtures = selectedFixtures.sumOf { it.size }
        val totalAssertions = selectedFixtures.sumOf { fixtures -> fixtures.sumOf { it.assertionCount } }
        val temporaryDirectory = Files.createTempDirectory("cplus-tests-")
        var failed = 0
        val reports = mutableListOf<TestFileReport>()
        var fixtureOffset = 0
        var assertionOffset = 0
        try {
            compiledSources.forEachIndexed { index, compiled ->
                val fixtures = selectedFixtures[index]
                if (fixtures.isEmpty()) {
                    return@forEachIndexed
                }
                val currentFixtureOffset = fixtureOffset
                val currentAssertionOffset = assertionOffset
                fixtureOffset += fixtures.size
                assertionOffset += fixtures.sumOf { it.assertionCount }

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
                    reports += TestFileReport(compiled.path, fixtures.size, fixtures.sumOf { it.assertionCount }, "COMPILE FAIL")
                    return@forEachIndexed
                }

                val command = buildList {
                    add(executable.toAbsolutePath().normalize().toString())
                    addAll(requestedNames)
                }
                val processBuilder = ProcessBuilder(command).inheritIO()
                processBuilder.environment().apply {
                    put("CPLUS_TEST_FIXTURE_OFFSET", currentFixtureOffset.toString())
                    put("CPLUS_TEST_FIXTURE_TOTAL", totalFixtures.toString())
                    put("CPLUS_TEST_ASSERTION_OFFSET", currentAssertionOffset.toString())
                    put("CPLUS_TEST_ASSERTION_TOTAL", totalAssertions.toString())
                }
                val exitCode = logger.pass("run-tests") {
                    processBuilder.start().waitFor()
                }
                val fileStatus = if (exitCode == 0) "PASS" else "FAIL"
                if (exitCode != 0) {
                    errors.append("cplus: test process for ")
                        .append(compiled.path.toString())
                        .append(" exited with status ")
                        .append(exitCode.toString())
                        .append('\n')
                }
                reports += TestFileReport(compiled.path, fixtures.size, fixtures.sumOf { it.assertionCount }, fileStatus)
                if (exitCode != 0) failed++
            }
        } finally {
            Files.walk(temporaryDirectory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
        printTestAggregateReport(reports, totalFixtures, totalAssertions, failed)
        return if (failed == 0) 0 else 1
    }

    private fun printTestAggregateReport(
        reports: List<TestFileReport>,
        totalFixtures: Int,
        totalAssertions: Int,
        failedFiles: Int
    ) {
        output.append("\n========== AGGREGATE TEST REPORT ==========\n")
        output.append("FILE | FIXTURES | ASSERTS | RESULT\n")
        reports.forEach { report ->
            val path = displayTestPath(report.path)
            val result = if (report.status == "PASS") {
                "\u001B[1;32mPASS\u001B[0m"
            } else {
                "\u001B[1;31m${report.status}\u001B[0m"
            }
            output.append("$path | ${report.fixtureCount} | ${report.assertionCount} | $result\n")
        }
        val overallStatus = if (failedFiles == 0) "\u001B[1;32mPASS\u001B[0m" else "\u001B[1;31mFAIL\u001B[0m"
        output.append(
            "$overallStatus TOTAL: ${reports.size} files, $totalFixtures fixtures, " +
                "$totalAssertions asserts, $failedFiles failed files\n"
        )
    }

    private fun displayTestPath(path: Path): String {
        val workingDirectory = Path("").toAbsolutePath().normalize()
        return if (path.startsWith(workingDirectory)) workingDirectory.relativize(path).toString() else path.toString()
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

    private fun validateTranscodeTargetOptions(options: List<String>) {
        var index = 0
        while (index < options.size) {
            when {
                options[index] == "--target" -> {
                    if (options.getOrNull(index + 1).isNullOrBlank()) {
                        throw IllegalArgumentException("--target requires a target triple")
                    }
                    index += 2
                }
                options[index].startsWith("--target=") -> {
                    if (options[index].substringAfter('=').isBlank()) {
                        throw IllegalArgumentException("--target requires a target triple")
                    }
                    index++
                }
                else -> throw IllegalArgumentException("transcode accepts only --target compiler options")
            }
        }
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
  cplus transcode filename.cp [-o some_file_name.c] [--target=TRIPLE]
  cplus compile filename.cp [-o executable] [passthrough tcc parameters]
  cplus run filename.cp [-o executable] [passthrough tcc parameters]
  cplus test filename.cp [filename2.cp ...] [test name ...]
  cplus new project_name|.

global options:
  --stdlib directory    use this standard-library root (also settable with CPLUS_STDLIB)

defaults:
  transcode: filename.cp -> filename.c
  compile/run: filename.cp -> filename
  compiler: bundled TinyCC, then TCC, system tcc on PATH, then compiler from CC
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

    private fun printTranscoderVersion() {
        errors.append("[cplus] transcoder runtime: ").append(Version().toString()).append('\n')
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

    private data class TestFileReport(
        val path: Path,
        val fixtureCount: Int,
        val assertionCount: Int,
        val status: String
    )
}
