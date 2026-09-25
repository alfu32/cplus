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
    val verbosity = args.firstOrNull { it.matches(Regex("-v[012]")) }?.substring(2)?.toInt() ?: 1
    val exitCode = try {
        CPlusCli().run(args.toList())
    } catch (error: CPlusSyntaxException) {
        val span = error.sourceSpan
        if (verbosity >= 1) {
            if (span?.file != null) {
                System.err.println("${span.file}:${span.startLine}:${span.startColumn}: error: ${error.message}")
            } else {
                System.err.println("cplus: error: ${error.message}")
            }
        }
        2
    } catch (error: Exception) {
        if (verbosity >= 1) System.err.println("cplus: ${error.message ?: error::class.simpleName}")
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
    private var verbosity: Int = 1
    private var activeLogger: CompilationLogger = logger

    fun run(arguments: List<String>): Int {
        cliStdlibRoot = null
        verbosity = 1
        activeLogger = SilentCompilationLogger
        val commandArguments = mutableListOf<String>()
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            if (argument.matches(Regex("-v[012]"))) {
                verbosity = argument.substring(2).toInt()
                activeLogger = if (verbosity >= 2) logger else SilentCompilationLogger
                index++
            } else if (argument == "--stdlib") {
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
        val source = activeLogger.pass("read-source") { readSource(sourcePath) }
        val transcoded = transpiler.transpile(
            source,
            sourcePath.toString(),
            activeLogger,
            importPaths,
            CPlusTarget.osFromCompilerOptions(parsed.passthrough)
        )
        printAllocationDiagnostics(transcoded)
        activeLogger.pass("write-c") { writeText(destination, transcoded.code) }
        return 0
    }

    private fun compile(arguments: List<String>, runAfter: Boolean): Int {
        val parsed = parseFileCommand(arguments, allowTccOptions = true)
        printTranscoderVersion()
        val destination = parsed.output ?: defaultExecutablePath(parsed.source)
        val sourcePath = parsed.source.toAbsolutePath().normalize()
        val importPaths = importPathsFor(sourcePath)
        val source = activeLogger.pass("read-source") { readSource(sourcePath) }
        val transcoded = transpiler.transpile(
            source,
            sourcePath.toString(),
            activeLogger,
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

        val result = compiler.compileExecutable(transcoded, destination, options, activeLogger)
        result.diagnostics.forEach(::printDiagnostic)
        if (result.exitCode != 0) return result.exitCode
        if (!runAfter) return 0

        return activeLogger.pass("run-executable") {
            val process = ProcessBuilder(destination.toAbsolutePath().normalize().toString())
                .inheritIO()
                .start()
            process.waitFor()
        }
    }

    private fun test(arguments: List<String>): Int {
        val parsed = parseTestCommand(arguments)
        val sources = parsed.sources.map(::Path).map { it.toAbsolutePath().normalize() }
        if (parsed.mode != TestMode.RUN && sources.size != 1) {
            throw IllegalArgumentException("test ${parsed.mode.name.lowercase()} currently requires exactly one input source")
        }
        val requestedNames = parsed.testNames.distinct()
        sources.forEach { path ->
            if (!Files.isRegularFile(path)) throw IllegalArgumentException("test source does not exist: $path")
        }
        printTranscoderVersion()

        if (parsed.mode == TestMode.TRANSCODE) {
            val path = sources.single()
            val importPaths = importPathsFor(path)
            val transpiled = transpiler.transpileTests(
                readSource(path), path.toString(), activeLogger, importPaths,
                CPlusTarget.osFromCompilerOptions(parsed.compilerFlags)
            )
            val destination = parsed.output ?: path.resolveSibling(path.fileName.toString().substringBeforeLast('.') + ".test.c")
            writeText(
                destination,
                addCompilerFlagsComment(transpiled.source.code, CompilerOptions.merge(transpiled.source.compilerOptions, parsed.compilerFlags))
            )
            return 0
        }

        val compiledSources = sources.map { path ->
            val importPaths = importPathsFor(path)
            val source = activeLogger.pass("read-source") { readSource(path) }
            val testSource = transpiler.transpileTests(
                source, path.toString(), activeLogger, importPaths,
                CPlusTarget.osFromCompilerOptions(parsed.compilerFlags)
            )
            printAllocationDiagnostics(testSource.source)
            TestSource(path, testSource, importPaths)
        }

        if (parsed.mode == TestMode.COMPILE) {
            val compiled = compiledSources.single()
            val destination = parsed.output ?: defaultExecutablePath(compiled.path)
            val result = compiler.compileExecutable(
                compiled.transcoded.source,
                destination,
                testCompilerOptions(compiled, parsed.compilerFlags),
                activeLogger
            )
            result.diagnostics.forEach(::printDiagnostic)
            return result.exitCode
        }
        val allNames = compiledSources.flatMap { it.transcoded.testNames }.toSet()
        if (allNames.isEmpty()) {
            if (verbosity > 0) errors.append("cplus: no @test declarations found in the input sources\n")
            return 2
        }
        val unmatched = requestedNames.filterNot { it in allNames }
        if (unmatched.isNotEmpty()) {
            if (verbosity > 0) errors.append("cplus: unknown test name(s): ").append(unmatched.joinToString(", ")).append('\n')
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
                val options = testCompilerOptions(compiled, parsed.compilerFlags + compiledSources.flatMap { it.transcoded.source.compilerOptions })
                val result = compiler.compileExecutable(compiled.transcoded.source, executable, options, activeLogger)
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
                val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
                processBuilder.environment().apply {
                    put("CPLUS_TEST_FIXTURE_OFFSET", currentFixtureOffset.toString())
                    put("CPLUS_TEST_FIXTURE_TOTAL", totalFixtures.toString())
                    put("CPLUS_TEST_ASSERTION_OFFSET", currentAssertionOffset.toString())
                    put("CPLUS_TEST_ASSERTION_TOTAL", totalAssertions.toString())
                }
                val exitCode = activeLogger.pass("run-tests") {
                    val process = processBuilder.start()
                    val reader = process.inputStream.bufferedReader()
                    val chunk = CharArray(4096)
                    while (true) {
                        val count = reader.read(chunk)
                        if (count < 0) break
                        output.append(String(chunk, 0, count))
                    }
                    process.waitFor()
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

    private fun testCompilerOptions(compiled: TestSource, flags: List<String>): List<String> =
        CompilerOptions.merge(buildList {
            compiled.path.parent?.let { add("-I$it") }
            add("-I${Path("").toAbsolutePath().normalize()}")
            compiled.importPaths.moduleRoots.forEach { add("-I$it") }
            compiled.importPaths.standardLibraryRoots.forEach { add("-I$it") }
        }, flags)

    private fun addCompilerFlagsComment(source: String, flags: List<String>): String {
        val unique = CompilerOptions.distinct(flags)
        val body = source.replace(Regex("(?m)^/\\* cplus compiler flags:.*\\*/\\R?"), "")
        if (unique.isEmpty()) return body
        return "/* cplus compiler flags: ${unique.joinToString(" ") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"").replace("*/", "* /")}\"" }} */\n$body"
    }

    private fun parseTestCommand(arguments: List<String>): ParsedTestCommand {
        val modes = setOf("run", "compile", "transcode")
        val mode = arguments.firstOrNull()?.takeIf(modes::contains)?.uppercase()?.let(TestMode::valueOf) ?: TestMode.RUN
        val start = if (arguments.firstOrNull() in modes) 1 else 0
        val sources = mutableListOf<String>()
        val testNames = mutableListOf<String>()
        val flags = mutableListOf<String>()
        var outputPath: Path? = null
        val paired = setOf("-l", "-L", "-F", "-I", "-D", "-U", "-include", "-isystem", "-iquote", "-isysroot", "--sysroot", "-sysroot", "--target", "-target", "-arch", "-framework", "-Xlinker", "-Xclang")
        var index = start
        while (index < arguments.size) {
            val value = arguments[index]
            when {
                value == "-o" -> {
                    if (index + 1 >= arguments.size) throw IllegalArgumentException("-o requires an output path")
                    outputPath = Path(arguments[index + 1]); index += 2
                }
                isCPlusSource(value) -> { sources += value; index++ }
                value.startsWith("-") -> {
                    flags += value
                    if (value in paired && index + 1 < arguments.size) flags += arguments[++index]
                    index++
                }
                else -> { testNames += value; index++ }
            }
        }
        if (sources.isEmpty()) throw IllegalArgumentException("test requires one or more .cp or .c+ source files")
        return ParsedTestCommand(mode, sources, outputPath, flags, testNames)
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
        if (verbosity == 0) return
        if (verbosity == 1 && diagnostic.severity !in setOf(DiagnosticSeverity.ERROR, DiagnosticSeverity.UNKNOWN)) return
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
        if (verbosity < 2) return
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
  cplus test [run] [compiler flags] filename.cp ... [test name ...]
  cplus test transcode [-o output.c] filename.cp
  cplus test compile [-o executable] [compiler flags] filename.cp
  cplus new project_name|.

global options:
  --stdlib directory    use this standard-library root (also settable with CPLUS_STDLIB)
  -v0                   silence all C-plus messages
  -v1                   show errors only (default)
  -v2                   show passes, compiler details, and test output

defaults:
  transcode: filename.cp -> filename.c
  compile/run: filename.cp -> filename
  compiler: bundled TinyCC, then TCC, system tcc on PATH, then compiler from CC
  test: runs all @test blocks by default; run, compile, and transcode are explicit modes
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
        if (verbosity >= 2) errors.append("[cplus] transcoder runtime: ").append(Version().toString()).append('\n')
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

    private enum class TestMode { RUN, COMPILE, TRANSCODE }

    private data class ParsedTestCommand(
        val mode: TestMode,
        val sources: List<String>,
        val output: Path?,
        val compilerFlags: List<String>,
        val testNames: List<String>
    )

    private data class TestFileReport(
        val path: Path,
        val fixtureCount: Int,
        val assertionCount: Int,
        val status: String
    )
}
