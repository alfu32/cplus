package cplus

import cplus.lsp.CPlusLspServer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

private val sourceExtensions = listOf(".cp", ".c+")

fun main(args: Array<String>) {
    val commandIndex = args.indexOfFirst {
        it in setOf("help", "--help", "-h", "parse", "lsp", "graph", "transcode", "compile", "run", "test", "new", "version")
    }
    val globalPrefix = args.take(if (commandIndex >= 0) commandIndex else args.size)
    val verbosity = globalPrefix.firstOrNull { it.matches(Regex("-v[012]")) }?.substring(2)?.toInt() ?: 1
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
    private val logger: CompilationLogger = ConsoleCompilationLogger(errors),
    private val stdinText: () -> String = { System.`in`.bufferedReader().readText() }
) {
    private var cliStdlibRoot: Path? = null
    private var cliParserBackend: ParserBackendId = ParserBackendId.TREE_SITTER
    private var cliFrontend: CompilationFrontend? = null
    private var cliCompilerFlags: List<String> = emptyList()
    private var verbosity: Int = 1
    private var activeLogger: CompilationLogger = logger

    fun run(arguments: List<String>): Int {
        cliStdlibRoot = null
        cliParserBackend = ParserBackendId.TREE_SITTER
        cliFrontend = null
        cliCompilerFlags = emptyList()
        verbosity = 1
        activeLogger = SilentCompilationLogger
        val (global, commandArguments) = parseGlobalOptions(arguments)
        cliStdlibRoot = global.stdlib
        cliParserBackend = global.backend ?: ParserBackendId.TREE_SITTER
        cliFrontend = global.frontend
        cliCompilerFlags = global.compilerFlags
        verbosity = global.verbosity
        activeLogger = if (verbosity >= 2) logger else SilentCompilationLogger
        if (commandArguments.isEmpty() || commandArguments.first() in setOf("help", "--help", "-h")) {
            printHelp()
            return 0
        }

        return when (val command = commandArguments.first()) {
            "parse" -> parse(commandArguments.drop(1))
            "lsp" -> lsp(commandArguments.drop(1))
            "graph" -> importGraph(commandArguments.drop(1))
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

    private fun parseGlobalOptions(arguments: List<String>): Pair<GlobalOptions, List<String>> {
        var backend: ParserBackendId? = null
        var frontend: CompilationFrontend? = null
        var stdlib: Path? = null
        var selectedVerbosity = 1
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            when {
                argument.matches(Regex("-v[012]")) -> {
                    selectedVerbosity = argument.substring(2).toInt()
                    index++
                }
                argument == "--stdlib" -> {
                    if (index + 1 >= arguments.size) throw IllegalArgumentException("--stdlib requires a directory")
                    stdlib = validateStdlibDirectory(arguments[index + 1])
                    index += 2
                }
                argument.startsWith("--stdlib=") -> {
                    stdlib = validateStdlibDirectory(argument.substringAfter('='))
                    index++
                }
                argument == "--backend" -> {
                    if (index + 1 >= arguments.size) throw IllegalArgumentException("--backend requires 'legacy' or 'tree-sitter'")
                    backend = parseBackend(arguments[index + 1])
                    index += 2
                }
                argument.startsWith("--backend=") -> {
                    backend = parseBackend(argument.substringAfter('='))
                    index++
                }
                argument == "--frontend" -> {
                    if (index + 1 >= arguments.size) throw IllegalArgumentException("--frontend requires auto, legacy, or tree-sitter")
                    frontend = parseFrontend(arguments[index + 1])
                    index += 2
                }
                argument.startsWith("--frontend=") -> {
                    frontend = parseFrontend(argument.substringAfter('='))
                    index++
                }
                argument == "-o" -> throw IllegalArgumentException("-o is command-specific and must follow the command")
                else -> break
            }
        }
        val compilerFlags = mutableListOf<String>()
        while (index < arguments.size && arguments[index].startsWith("-") && arguments[index] != "-o") {
            val flag = arguments[index]
            compilerFlags += flag
            if (flag in globalPairedCompilerFlags) {
                if (index + 1 >= arguments.size) throw IllegalArgumentException("$flag requires a value")
                compilerFlags += arguments[index + 1]
                index += 2
            } else {
                index++
            }
        }
        return GlobalOptions(backend, frontend, stdlib, selectedVerbosity, compilerFlags) to arguments.drop(index)
    }

    private fun validateStdlibDirectory(value: String): Path {
        if (value.isBlank()) throw IllegalArgumentException("--stdlib requires a directory")
        val directory = Path(value).toAbsolutePath().normalize()
        if (!Files.isDirectory(directory)) throw IllegalArgumentException("standard-library directory does not exist: $directory")
        return directory
    }

    private fun rejectGlobalOptionAfterCommand(argument: String): Nothing =
        throw IllegalArgumentException("$argument is a global option and must appear before the command")

    private fun rejectGlobalOptionAfterCommandIfNeeded(argument: String) {
        when {
            argument.matches(Regex("-v[012]")) -> rejectGlobalOptionAfterCommand(argument)
            argument == "--stdlib" || argument.startsWith("--stdlib=") -> rejectGlobalOptionAfterCommand(argument)
            argument == "--backend" || argument.startsWith("--backend=") -> rejectGlobalOptionAfterCommand(argument)
            argument == "--frontend" || argument.startsWith("--frontend=") -> rejectGlobalOptionAfterCommand(argument)
            argument.startsWith("-") && argument != "-o" ->
                throw IllegalArgumentException("$argument is a global compiler option and must appear before the command")
        }
    }

    private val globalPairedCompilerFlags = setOf(
        "-l", "-L", "-F", "-I", "-D", "-U", "-include", "-isystem", "-iquote", "-isysroot",
        "--sysroot", "-sysroot", "--target", "-target", "-arch", "-framework", "-Xlinker", "-Xclang"
    )

    private fun lsp(arguments: List<String>): Int {
        var tracePath: Path? = null
        var index = 0
        while (index < arguments.size) {
            when (val argument = arguments[index]) {
                "--trace" -> {
                    if (index + 1 >= arguments.size) throw IllegalArgumentException("lsp --trace requires a file path")
                    tracePath = Path(arguments[index + 1]).toAbsolutePath().normalize()
                    index += 2
                }
                else -> if (argument.startsWith("--trace=")) {
                    val value = argument.substringAfter('=')
                    if (value.isBlank()) throw IllegalArgumentException("lsp --trace requires a file path")
                    tracePath = Path(value).toAbsolutePath().normalize()
                    index++
                } else {
                    throw IllegalArgumentException("unknown lsp option '$argument'; expected --trace PATH")
                }
            }
        }
        CPlusLspServer(tracePath = tracePath).serve()
        return 0
    }

    private fun parse(arguments: List<String>): Int {
        if (arguments.isEmpty()) throw IllegalArgumentException("parse requires a source file or --stdin")
        var source: Path? = null
        var sourceHint: Path? = null
        var readStdin = false
        var destination: Path? = null
        var parserBackend = cliParserBackend
        var index = 0
        while (index < arguments.size) {
            when (val argument = arguments[index]) {
                "--stdin" -> {
                    if (readStdin) throw IllegalArgumentException("parse accepts --stdin only once")
                    readStdin = true
                    index++
                }
                "--source" -> {
                    if (index + 1 >= arguments.size) throw IllegalArgumentException("parse --source requires a filename")
                    if (sourceHint != null) throw IllegalArgumentException("parse accepts --source only once")
                    sourceHint = Path(arguments[index + 1]).toAbsolutePath().normalize()
                    index += 2
                }
                "--backend" -> {
                    if (index + 1 >= arguments.size) throw IllegalArgumentException("parse --backend requires 'legacy' or 'tree-sitter'")
                    parserBackend = parseBackend(arguments[index + 1])
                    index += 2
                }
                "-o" -> {
                    if (index + 1 >= arguments.size) throw IllegalArgumentException("parse -o requires a JSON output path")
                    destination = Path(arguments[index + 1]).toAbsolutePath().normalize()
                    index += 2
                }
                else -> if (argument.startsWith("--backend=")) {
                    parserBackend = parseBackend(argument.substringAfter('='))
                    index++
                } else {
                    rejectGlobalOptionAfterCommandIfNeeded(argument)
                    if (source != null) throw IllegalArgumentException("parse accepts one source file, got '$argument'")
                    source = Path(argument).toAbsolutePath().normalize()
                    index++
                }
            }
        }
        if (readStdin && source != null) throw IllegalArgumentException("parse --stdin cannot be combined with a source file argument")
        if (!readStdin && sourceHint != null) throw IllegalArgumentException("parse --source is only valid with --stdin")
        if (!readStdin && source == null) throw IllegalArgumentException("parse requires a source file")
        val sourcePath = source
        if (sourcePath != null && !Files.isRegularFile(sourcePath)) {
            throw IllegalArgumentException("source file does not exist: $sourcePath")
        }
        val manager = SourceManager()
        val snapshot = if (readStdin) {
            val sourceId = sourceHint?.let(SourceId::fromPath) ?: SourceId.named("<stdin>")
            manager.open(sourceId, stdinText())
        } else {
            manager.load(sourcePath!!)
        }
        val parser = CPlusParserService(
            backends = listOf(LegacyCPlusParserBackend(), cplus.parser.TreeSitterCPlusParserBackend()),
            defaultBackend = ParserBackendId.TREE_SITTER
        )
        val result = parser.parse(snapshot, parserBackend, CPlusParseOptions(editorMode = true))
        val json = cplus.parser.CPlusParseJson.encode(result)
        if (destination == null) {
            output.append(json).append('\n')
        } else {
            writeText(destination, json + "\n")
        }
        return if (result.diagnostics.any { it.severity == ParserDiagnosticSeverity.ERROR }) 1 else 0
    }

    private fun parseBackend(value: String): ParserBackendId = when (value.trim().lowercase()) {
        "legacy" -> ParserBackendId.LEGACY
        "tree-sitter", "treesitter" -> ParserBackendId.TREE_SITTER
        else -> throw IllegalArgumentException("unknown parser backend '$value'; expected 'legacy' or 'tree-sitter'")
    }

    private fun importGraph(arguments: List<String>): Int {
        if (arguments.isEmpty()) throw IllegalArgumentException("graph requires a .cp or .c+ source file")
        var source: Path? = null
        var destination: Path? = null
        var index = 0
        while (index < arguments.size) {
            when (val argument = arguments[index]) {
                "-o" -> {
                    if (index + 1 >= arguments.size) throw IllegalArgumentException("graph -o requires a JSON output path")
                    destination = Path(arguments[index + 1]).toAbsolutePath().normalize()
                    index += 2
                }
                else -> {
                    if (source != null) throw IllegalArgumentException("graph accepts one source file, got '$argument'")
                    source = Path(argument).toAbsolutePath().normalize()
                    index++
                }
            }
        }
        val sourcePath = source ?: throw IllegalArgumentException("graph requires a source file")
        if (!Files.isRegularFile(sourcePath)) throw IllegalArgumentException("source file does not exist: $sourcePath")
        val transcoded = transpiler.transpile(
            readSource(sourcePath),
            sourcePath.toString(),
            activeLogger,
            importPathsFor(sourcePath)
        )
        val json = CPlusImportGraphJson.encode(transcoded)
        if (destination == null) output.append(json).append('\n') else writeText(destination, json + "\n")
        return 0
    }

    private fun transcode(arguments: List<String>): Int {
        val parsed = parseFileCommand(arguments)
        validateTranscodeTargetOptions(parsed.passthrough)
        printTranscoderVersion()
        val destination = parsed.output ?: defaultTranscodedPath(parsed.source)
        val sourcePath = parsed.source.toAbsolutePath().normalize()
        val importPaths = importPathsFor(sourcePath)
        val source = activeLogger.pass("read-source") { readSource(sourcePath) }
        val transcoded = transpileWithFrontend(
            source,
            sourcePath.toString(),
            activeLogger,
            importPaths,
            CPlusTarget.osFromCompilerOptions(parsed.passthrough),
            parsed.frontend
        )
        printAllocationDiagnostics(transcoded)
        activeLogger.pass("write-c") { writeText(destination, transcoded.code) }
        return 0
    }

    private fun compile(arguments: List<String>, runAfter: Boolean): Int {
        val parsed = parseFileCommand(arguments)
        printTranscoderVersion()
        val destination = parsed.output ?: defaultExecutablePath(parsed.source)
        val sourcePath = parsed.source.toAbsolutePath().normalize()
        val importPaths = importPathsFor(sourcePath)
        val source = activeLogger.pass("read-source") { readSource(sourcePath) }
        val transcoded = transpileWithFrontend(
            source,
            sourcePath.toString(),
            activeLogger,
            importPaths,
            CPlusTarget.osFromCompilerOptions(parsed.passthrough),
            parsed.frontend
        )
        printAllocationDiagnostics(transcoded)
        val options = buildList {
            sourcePath.parent?.let { add("-I${it}") }
            add("-I${Path("").toAbsolutePath().normalize()}")
            importPaths.moduleRoots.forEach { add("-I$it") }
            importPaths.standardLibraryRoots.forEach { add("-I$it") }
            addAll(parsed.passthrough)
        }

        var result = compiler.compileExecutable(transcoded, destination, options, activeLogger)
        if (result.exitCode != 0 && parsed.frontend == CompilationFrontend.AUTO) {
            activeLogger.info(
                "frontend fallback: requested=auto selected=legacy reason=host compiler rejected AST output " +
                    compilerFailureSummary(result)
            )
            val legacy = transpiler.transpile(
                source, sourcePath.toString(), activeLogger, importPaths,
                CPlusTarget.osFromCompilerOptions(parsed.passthrough)
            )
            printAllocationDiagnostics(legacy)
            result = compiler.compileExecutable(legacy, destination, options, activeLogger)
        }
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
            val transpiled = transpileTestsWithFrontend(
                readSource(path), path.toString(), activeLogger, importPaths,
                CPlusTarget.osFromCompilerOptions(parsed.compilerFlags), parsed.frontend
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
            val testSource = transpileTestsWithFrontend(
                source, path.toString(), activeLogger, importPaths,
                CPlusTarget.osFromCompilerOptions(parsed.compilerFlags), parsed.frontend
            )
            printAllocationDiagnostics(testSource.source)
            TestSource(path, testSource, importPaths)
        }

        if (parsed.mode == TestMode.COMPILE) {
            val compiled = compiledSources.single()
            val destination = parsed.output ?: defaultExecutablePath(compiled.path)
            var result = compiler.compileExecutable(
                compiled.transcoded.source,
                destination,
                testCompilerOptions(compiled, parsed.compilerFlags),
                activeLogger
            )
            if (result.exitCode != 0 && parsed.frontend == CompilationFrontend.AUTO) {
                activeLogger.info(
                    "frontend fallback: requested=auto selected=legacy reason=host compiler rejected AST test output " +
                        compilerFailureSummary(result)
                )
                val legacy = transpiler.transpileTests(
                    readSource(compiled.path), compiled.path.toString(), activeLogger,
                    compiled.importPaths, CPlusTarget.osFromCompilerOptions(parsed.compilerFlags)
                )
                result = compiler.compileExecutable(
                    legacy.source,
                    destination,
                    testCompilerOptions(TestSource(compiled.path, legacy, compiled.importPaths), parsed.compilerFlags),
                    activeLogger
                )
            }
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
                var result = compiler.compileExecutable(compiled.transcoded.source, executable, options, activeLogger)
                if (result.exitCode != 0 && parsed.frontend == CompilationFrontend.AUTO) {
                    activeLogger.info(
                        "frontend fallback: requested=auto selected=legacy reason=host compiler rejected AST test output " +
                            compilerFailureSummary(result)
                    )
                    val legacy = transpiler.transpileTests(
                        readSource(compiled.path), compiled.path.toString(), activeLogger,
                        compiled.importPaths, CPlusTarget.osFromCompilerOptions(parsed.compilerFlags)
                    )
                    result = compiler.compileExecutable(
                        legacy.source,
                        executable,
                        testCompilerOptions(TestSource(compiled.path, legacy, compiled.importPaths), parsed.compilerFlags),
                        activeLogger
                    )
                }
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

    /**
     * Selects the complete compilation frontend. The default is AST-first: Tree-sitter gets
     * the first attempt, while the legacy frontend remains a compatibility fallback for
     * constructs not lowered by the migrating frontend yet.
     */
    private fun transpileWithFrontend(
        source: String,
        sourceName: String,
        logger: CompilationLogger,
        importPaths: CPlusImportPaths,
        targetOs: String,
        frontend: CompilationFrontend
    ): TranscodedSource = when (frontend) {
        CompilationFrontend.LEGACY -> transpiler.transpile(
            source, sourceName, logger, importPaths, targetOs
        )
        CompilationFrontend.AUTO -> try {
            transpileWithFrontend(source, sourceName, logger, importPaths, targetOs, CompilationFrontend.TREE_SITTER)
        } catch (error: CPlusSyntaxException) {
            logger.info(
                "frontend fallback: requested=auto selected=legacy reason=tree-sitter lowering failed " +
                    "at ${error.sourceSpan?.startLine ?: "?"}:${error.sourceSpan?.startColumn ?: "?"}: " +
                    (error.message ?: "unknown lowering failure")
            )
            transpiler.transpile(source, sourceName, logger, importPaths, targetOs)
        }
        CompilationFrontend.TREE_SITTER -> logger.pass("tree-sitter-transpile") {
            val manager = SourceManager()
            val snapshot = manager.open(SourceId.named(sourceName), source)
            val result = cplus.parser.TreeSitterCPlusPrototypeTranspiler(
                backend = cplus.parser.TreeSitterCPlusParserBackend(),
                sourceManager = manager,
                targetOs = targetOs,
                importPaths = importPaths,
                targetArch = targetArchitecture()
            ).transpile(snapshot)
            result.transcodedSource ?: run {
                val diagnostic = result.loweringDiagnostics.firstOrNull()
                if (diagnostic != null) {
                    throw CPlusSyntaxException(diagnostic.message, diagnostic.span)
                }
                val parserDiagnostic = result.parserDiagnostics.firstOrNull()
                if (parserDiagnostic != null) {
                    throw CPlusSyntaxException(parserDiagnostic.message, parserDiagnostic.span)
                }
                val unsupported = result.unsupportedNodes.firstOrNull()
                if (unsupported != null) {
                    throw CPlusSyntaxException(
                        "unsupported Tree-sitter construct '${unsupported.syntaxKind}'",
                        unsupported.span
                    )
                }
                throw CPlusSyntaxException("Tree-sitter frontend did not produce C output")
            }
        }
    }

    /**
     * Selects the test frontend. The AST runtime path feeds
     * extracted fixture bodies through the established harness bridge until fixture-body AST
     * lowering has its own promotion gate.
     */
    private fun transpileTestsWithFrontend(
        source: String,
        sourceName: String,
        logger: CompilationLogger,
        importPaths: CPlusImportPaths,
        targetOs: String,
        frontend: CompilationFrontend
    ): TranscodedTestSource = when (frontend) {
        CompilationFrontend.LEGACY -> transpiler.transpileTests(
            source, sourceName, logger, importPaths, targetOs
        )
        CompilationFrontend.AUTO -> try {
            transpileTestsWithFrontend(source, sourceName, logger, importPaths, targetOs, CompilationFrontend.TREE_SITTER)
        } catch (error: CPlusSyntaxException) {
            logger.info(
                "frontend fallback: requested=auto selected=legacy reason=tree-sitter test lowering failed " +
                    "at ${error.sourceSpan?.startLine ?: "?"}:${error.sourceSpan?.startColumn ?: "?"}: " +
                    (error.message ?: "unknown lowering failure")
            )
            transpiler.transpileTests(source, sourceName, logger, importPaths, targetOs)
        }
        CompilationFrontend.TREE_SITTER -> logger.pass("tree-sitter-test-transpile") {
            val manager = SourceManager()
            val snapshot = manager.open(SourceId.named(sourceName), source)
            val result = cplus.parser.TreeSitterCPlusPrototypeTranspiler(
                backend = cplus.parser.TreeSitterCPlusParserBackend(),
                sourceManager = manager,
                targetOs = targetOs,
                importPaths = importPaths,
                targetArch = targetArchitecture()
            ).transpile(snapshot)
            val runtime = result.cSource
            if (runtime == null || !result.successful) {
                val diagnostic = result.loweringDiagnostics.firstOrNull()
                if (diagnostic != null) throw CPlusSyntaxException(diagnostic.message, diagnostic.span)
                val parserDiagnostic = result.parserDiagnostics.firstOrNull()
                if (parserDiagnostic != null) throw CPlusSyntaxException(parserDiagnostic.message, parserDiagnostic.span)
                val unsupported = result.unsupportedNodes.firstOrNull()
                if (unsupported != null) {
                    throw CPlusSyntaxException(
                        "unsupported Tree-sitter construct '${unsupported.syntaxKind}'",
                        unsupported.span
                    )
                }
                throw CPlusSyntaxException("Tree-sitter frontend did not produce test C output")
            }
            // Runtime and fixture-body structural lowering is AST-owned. This entry point only
            // supplies the established assertion/test harness; it must not rerun legacy textual
            // method, defer, or try/catch lowerers on already-lowered fixture bodies.
            val bridged = transpiler.emitExtractedTestHarness(runtime, result.testFixtures, logger)
            bridged.copy(
                source = bridged.source.copy(
                    allocationAnalysis = result.allocationAnalysis,
                    compilerOptions = result.compilerOptions,
                    sourceOrder = result.sourceOrder,
                    sourceImports = result.sourceImports,
                    frontendPasses = result.runtimePasses + bridged.source.frontendPasses
                )
            )
        }
    }

    private fun targetArchitecture(): String = CPlusTarget.hostArch()

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
        val flags = cliCompilerFlags.toMutableList()
        val frontend = cliFrontend ?: defaultCompilationFrontend()
        var outputPath: Path? = null
        var index = start
        while (index < arguments.size) {
            val value = arguments[index]
            when {
                value == "--frontend" -> {
                    rejectGlobalOptionAfterCommand(value)
                }
                value.startsWith("--frontend=") -> {
                    rejectGlobalOptionAfterCommand(value)
                }
                value == "-o" -> {
                    if (index + 1 >= arguments.size) throw IllegalArgumentException("-o requires an output path")
                    outputPath = Path(arguments[index + 1]); index += 2
                }
                isCPlusSource(value) -> { sources += value; index++ }
                value.startsWith("-") -> {
                    rejectGlobalOptionAfterCommand(value)
                }
                else -> { testNames += value; index++ }
            }
        }
        if (sources.isEmpty()) throw IllegalArgumentException("test requires one or more .cp or .c+ source files")
        return ParsedTestCommand(mode, sources, outputPath, flags, testNames, frontend)
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

    private fun parseFileCommand(arguments: List<String>): ParsedCommand {
        val source = arguments.firstOrNull()?.let(::Path)
            ?: throw IllegalArgumentException("missing input filename")
        var outputPath: Path? = null
        val frontend = cliFrontend ?: defaultCompilationFrontend()
        val passthrough = cliCompilerFlags.toMutableList()
        var index = 1
        while (index < arguments.size) {
            when (val argument = arguments[index]) {
                "-o" -> {
                    if (index + 1 >= arguments.size) throw IllegalArgumentException("-o requires an output path")
                    outputPath = Path(arguments[index + 1])
                    index += 2
                }
                "--frontend" -> {
                    rejectGlobalOptionAfterCommand("--frontend")
                }
                else -> {
                    if (argument.startsWith("--frontend=")) {
                        rejectGlobalOptionAfterCommand(argument)
                    } else {
                        rejectGlobalOptionAfterCommandIfNeeded(argument)
                        throw IllegalArgumentException("unexpected argument '$argument'; compiler options must precede the command")
                    }
                }
            }
        }
        return ParsedCommand(source, outputPath, passthrough, frontend)
    }

    private fun parseFrontend(value: String): CompilationFrontend = when (value.trim().lowercase()) {
        "auto", "default" -> CompilationFrontend.AUTO
        "legacy" -> CompilationFrontend.LEGACY
        "tree-sitter", "treesitter", "ast" -> CompilationFrontend.TREE_SITTER
        else -> throw IllegalArgumentException(
            "unknown compilation frontend '$value'; expected 'auto', 'legacy', or 'tree-sitter'"
        )
    }

    private fun defaultCompilationFrontend(): CompilationFrontend =
        System.getProperty("cplus.frontend")?.takeIf(String::isNotBlank)?.let(::parseFrontend)
            ?: System.getenv("CPLUS_FRONTEND")?.takeIf(String::isNotBlank)?.let(::parseFrontend)
            ?: CompilationFrontend.AUTO

    private fun compilerFailureSummary(result: TccCompilationResult): String = result.diagnostics
        .asSequence()
        .filter { it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.UNKNOWN }
        .map { it.message.trim() }
        .filter(String::isNotEmpty)
        .take(3)
        .joinToString(" | ")
        .ifBlank { "no diagnostic text" }

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
            val file = span.file?.replace(Regex("#tree-sitter-\\d+$"), "")
            val location = file?.let { "$it:${span.startLine}:${span.startColumn}" } ?: "<c-plus-input>"
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
  cplus [global options] help
  cplus [global options] version
  cplus [global options] parse filename.cp [--backend legacy|tree-sitter] [-o ast.json]
  cplus [global options] parse --stdin [--source filename.cp] [--backend legacy|tree-sitter] [-o ast.json]
  cplus [global options] lsp [--trace path]
  cplus [global options] graph filename.cp [-o imports.json]
  cplus [global options] transcode filename.cp [-o some_file_name.c]
  cplus [global options] compile filename.cp [-o executable]
  cplus [global options] run filename.cp [-o executable]
  cplus [global options] test filename.cp [filename2.cp ...] [test name ...]
  cplus [global options] test [run|compile|transcode] [-o output] filename.cp ... [test name ...]
  cplus [global options] new project_name|.

global options:
  --stdlib directory    use this standard-library root (also settable with CPLUS_STDLIB)
  --frontend auto|legacy|tree-sitter
                        select the compilation frontend
  --target TRIPLE       select the compiler target (forwarded to the host compiler)
  compiler flags        compiler/linker flags such as -I, -D, -l, -L, and --sysroot
  -v0                   silence all C-plus messages
  -v1                   show errors only (default)
  -v2                   show passes, compiler details, and test output

defaults:
  transcode: filename.cp -> filename.c
  parse: emits the versioned cplus.parse.v1 normalized AST and diagnostics as JSON; --stdin parses unsaved text
  graph: resolves C-plus imports and emits cplus.imports.v1 dependency-order/edge JSON
  compile/run: filename.cp -> filename
  compiler: bundled TinyCC, then TCC, system tcc on PATH, then compiler from CC
  frontend: AST-first AUTO by default with legacy compatibility fallback; use --frontend=legacy for rollback or --frontend=tree-sitter for strict AST mode
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
        val passthrough: List<String>,
        val frontend: CompilationFrontend
    )

    private data class GlobalOptions(
        val backend: ParserBackendId?,
        val frontend: CompilationFrontend?,
        val stdlib: Path?,
        val verbosity: Int,
        val compilerFlags: List<String>
    )

    private enum class CompilationFrontend { AUTO, LEGACY, TREE_SITTER }

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
        val testNames: List<String>,
        val frontend: CompilationFrontend
    )

    private data class TestFileReport(
        val path: Path,
        val fixtureCount: Int,
        val assertionCount: Int,
        val status: String
    )
}
