package cplus.parser

import cplus.ParseCoverage
import cplus.ParserBackendId
import cplus.SourceId
import cplus.SourceManager
import cplus.CPlusAstAdapter
import cplus.CPlusAstCEmitter
import cplus.CPlusAstLoweringPipeline
import cplus.CPlusAstLoweringStep
import cplus.CPlusAstKind
import cplus.CPlusImportPaths
import cplus.CPlusSemanticAnalyzer
import cplus.CPlusSymbolKind
import cplus.CPlusDeclaratorLayer
import cplus.CPlusThrowsConvention
import cplus.CPlusDeferLoweringPass
import cplus.CPlusComptimeIndexer
import cplus.CPlusComptimeResolver
import cplus.CPlusMethodCallLoweringPass
import cplus.CPlusStructMethodLoweringPass
import cplus.CPlusTranspiler
import cplus.CPlusLegacyPassSelection
import cplus.CPlusParserShadowRunner
import cplus.CPlusSyntaxNode
import cplus.CPlusParseResult
import cplus.CPlusParseOptions
import cplus.CPlusParserBackend
import cplus.ParserDiagnostic
import cplus.ParserDiagnosticSeverity
import cplus.SourceSnapshot
import cplus.CPlusLoweringDiagnostic
import cplus.CPlusLoweringResult
import cplus.AllocationIntent
import cplus.AllocationOwnership
import cplus.AllocationSymbolKind
import cplus.LegacyCPlusParserBackend
import cplus.MappedText
import cplus.dump
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TreeSitterCPlusParserBackendTest {
    private val backend = TreeSitterCPlusParserBackend()
    private val sources = SourceManager()

    private fun deleteRecursively(path: Path) {
        repeat(10) { attempt ->
            try {
                if (Files.exists(path)) {
                    Files.walk(path).use { entries ->
                        entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                    }
                }
                return
            } catch (_: Exception) {
                if (attempt < 9) Thread.sleep(100)
            }
        }
    }

    @Test
    fun everyNamedGrammarNodeHasAnExplicitStableAstCategory() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("parser-tree-sitter/upstream/src")) }
            ?: error("could not locate the pinned Tree-sitter grammar from ${Path.of("").toAbsolutePath()}")
        val nodeTypes = Files.readString(repository.resolve("parser-tree-sitter/upstream/src/node-types.json"))
        val syntaxKinds = Regex("""\{\s*"type"\s*:\s*"([^"]+)"\s*,\s*"named"\s*:\s*true""")
            .findAll(nodeTypes)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(syntaxKinds.isNotEmpty(), "the pinned grammar must expose named syntax nodes")

        val source = sources.open(SourceId.named("grammar-node-category-audit.c"), "")
        val adapter = CPlusAstAdapter()
        val unclassified = syntaxKinds.filter { syntaxKind ->
            val parsed = CPlusParseResult(
                source = source,
                backend = ParserBackendId.TREE_SITTER,
                coverage = ParseCoverage.STRUCTURAL,
                root = CPlusSyntaxNode(syntaxKind, source.sourceFile.span(0, 0))
            )
            adapter.adapt(parsed).root.kind == CPlusAstKind.OTHER
        }.sorted()

        assertTrue(unclassified.isEmpty(), "named Tree-sitter nodes mapped to OTHER: $unclassified")
    }

    @Test
    fun parsesRepresentativeRepositoryCPlusModulesWithoutRecovery() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib")) }
            ?: error("could not locate repository stdlib from ${Path.of("").toAbsolutePath()}")
        val modules = listOf(
            "stdlib/containers/dynamic_list.cp",
            "stdlib/containers/dynamic_map.cp",
            "stdlib/strings/string.cp",
            "stdlib/concurrency/thread_pool.cp",
            "stdlib/http/client.cp",
            "stdlib/http/server.cp",
            "stdlib/memory/xmem.cp",
            "stdlib/tests/containers.cp",
        )

        modules.forEach { relativePath ->
            val path = repository.resolve(relativePath)
            val snapshot = sources.open(SourceId.named(relativePath), Files.readString(path))
            val parsed = backend.parse(snapshot)
            val recovery = parsed.root.descendants().filter { it.isError || it.isMissing }
                .map { node ->
                    val excerpt = snapshot.text.substring(node.span.startOffset, node.span.endOffset)
                    "${node.kind} '$excerpt' at ${node.span.startLine}:${node.span.startColumn}"
                }.toList()

            assertTrue(parsed.diagnostics.isEmpty(), "$relativePath: ${parsed.diagnostics}; recovery=$recovery")
            assertFalse(
                parsed.root.descendants().any { it.isError || it.isMissing },
                "$relativePath contains a Tree-sitter recovery node"
            )
        }
    }

    @Test
    fun parsesEveryStandardLibraryCPlusSourceWithoutRecovery() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib")) }
            ?: error("could not locate repository stdlib from ${Path.of("").toAbsolutePath()}")
        val modules = Files.walk(repository.resolve("stdlib")).use { paths ->
            paths.filter { it.toString().endsWith(".cp") || it.toString().endsWith(".c+") }
                .sorted()
                .toList()
        }
        assertTrue(modules.isNotEmpty(), "stdlib C-plus corpus must not be empty")

        modules.forEach { path ->
            val relativePath = repository.relativize(path).toString()
            val snapshot = sources.open(SourceId.named(relativePath), Files.readString(path))
            val parsed = backend.parse(snapshot)
            val recovered = parsed.root.descendants()
                .filter { it.isError || it.isMissing }
                .map { node ->
                    val fragment = snapshot.text.substring(node.span.startOffset, node.span.endOffset)
                    "${node.kind} '$fragment' at ${node.span.startLine}:${node.span.startColumn}"
                }.toList()
            assertTrue(
                parsed.diagnostics.isEmpty() && recovered.isEmpty(),
                "$relativePath: diagnostics=${parsed.diagnostics}; recovery=$recovered"
            )
        }
    }

    @Test
    fun provesLiveCPlusSyntaxNodeInventoryForGrammarProof() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib")) && Files.isDirectory(it.resolve("examples")) }
            ?: error("could not locate repository sources from ${Path.of("").toAbsolutePath()}")
        val modules = Files.walk(repository).use { paths ->
            paths.filter { path ->
                (path.toString().endsWith(".cp") || path.toString().endsWith(".c+")) &&
                    (path.startsWith(repository.resolve("stdlib")) || path.startsWith(repository.resolve("examples")))
            }.sorted().toList()
        }
        val kinds = linkedSetOf<String>()
        modules.forEach { path ->
            val relativePath = repository.relativize(path).toString()
            val snapshot = sources.open(SourceId.named(relativePath), Files.readString(path))
            val parsed = backend.parse(snapshot)
            assertTrue(parsed.diagnostics.isEmpty(), "$relativePath: ${parsed.diagnostics}")
            assertTrue(
                parsed.root.descendants().none { it.isError || it.isMissing },
                "$relativePath contains a recovery node"
            )
            parsed.root.descendants().mapTo(kinds) { it.kind }
        }
        val expectedLiveKinds = setOf(
            "cplus_access_modifier",
            "cplus_at_call_expression",
            "cplus_at_import",
            "cplus_comptime_block",
            "cplus_comptime_body",
            "cplus_comptime_conditional",
            "cplus_comptime_declaration",
            "cplus_comptime_expression",
            "cplus_comptime_flags",
            "cplus_comptime_function_definition",
            "cplus_comptime_import",
            "cplus_comptime_invocation",
            "cplus_comptime_return_declaration",
            "cplus_comptime_result_type",
            "cplus_comptime_type_definition",
            "cplus_comptime_value",
            "cplus_defer_statement",
            "cplus_function_declaration",
            "cplus_generic_type_parameter",
            "cplus_interpolated_identifier",
            "cplus_legacy_comptime_invocation",
            "cplus_legacy_function_generator",
            "cplus_legacy_generic_type_parameter",
            "cplus_legacy_returned_function",
            "cplus_legacy_type_generator",
            "cplus_method_declarator",
            "cplus_method_definition",
            "cplus_parameter_annotation",
            "cplus_parameter_declaration",
            "cplus_result_annotation",
            "cplus_static_modifier",
            "cplus_test_assertion_statement",
            "cplus_test_declaration",
            "cplus_type_argument",
            "cplus_type_reference",
        )
        val actualLiveKinds = kinds.filter { it.startsWith("cplus_") }.toSet()
        assertEquals(expectedLiveKinds, actualLiveKinds)
        assertEquals(62, modules.size)
    }

    @Test
    fun frontendCorpusManifestIsCompleteAndReferencesExecutableEvidence() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("documentation/corpus/frontend-v1.tsv")) }
            ?: error("could not locate frontend corpus from ${Path.of("").toAbsolutePath()}")
        val manifest = repository.resolve("documentation/corpus/frontend-v1.tsv")
        val contract = repository.resolve("documentation/corpus/frontend-v1-contract.tsv")
        assertTrue(Files.isRegularFile(contract), "frontend corpus result contract is missing")
        val testSource = Files.readString(
            repository.resolve("parser-tree-sitter/src/jvmTest/kotlin/cplus/parser/TreeSitterCPlusParserBackendTest.kt")
        )
        val rows = Files.readAllLines(manifest)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapIndexed { index, line ->
                val fields = line.split('\t')
                assertEquals(5, fields.size, "manifest line ${index + 1} must have five tab-separated fields: $line")
                fields
            }
        val contractRows = Files.readAllLines(contract)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapIndexed { index, line ->
                val fields = line.split('\t')
                assertEquals(3, fields.size, "contract line ${index + 1} must have three tab-separated fields: $line")
                fields
            }

        assertEquals(71, rows.size, "frontend-v1.tsv case count changed; update the frozen count intentionally")
        assertEquals(71, contractRows.size, "frontend-v1-contract.tsv case count changed; update the frozen count intentionally")
        assertEquals(rows.size, rows.map { it[0] }.toSet().size, "frontend corpus IDs must be unique")
        assertEquals(
            contractRows.size,
            contractRows.map { it[0] }.toSet().size,
            "frontend corpus contract IDs must be unique"
        )
        val manifestById = rows.associateBy { it[0] }
        val contractById = contractRows.associateBy { it[0] }
        assertEquals(rows.map { it[0] }.toSet(), contractById.keys, "manifest and result contract IDs must match")
        assertEquals(
            mapOf("normative" to 29, "boundary" to 26, "overlap" to 16),
            rows.groupingBy { it[1] }.eachCount(),
            "frontend corpus categories changed; update the frozen counts intentionally"
        )
        val expectedPrefixes = mapOf("normative" to "N", "boundary" to "B", "overlap" to "O")
        rows.forEach { (id, category, specification, marker, evidence) ->
            assertTrue(id.matches(Regex("[NBO][0-9]{3}")), "invalid corpus ID '$id'")
            assertTrue(id.startsWith(expectedPrefixes.getValue(category)), "$id has the wrong category prefix")
            val specPath = repository.resolve(specification)
            assertTrue(Files.isRegularFile(specPath), "$id references missing specification $specification")
            assertTrue(marker in Files.readString(specPath), "$id marker '$marker' is absent from $specification")
            assertTrue(
                Regex("""\bfun\s+${Regex.escape(evidence)}\s*\(""").containsMatchIn(testSource),
                "$id references missing executable evidence method '$evidence'"
            )
        }
        contractRows.forEach { (id, category, expectedResult) ->
            assertEquals(category, manifestById.getValue(id)[1], "$id contract category does not match the manifest")
            val expected = when (category) {
                "normative" -> "materialize"
                "boundary" -> "mapped-diagnostic"
                "overlap" -> "differential"
                else -> error("unhandled frontend corpus category '$category'")
            }
            assertEquals(expected, expectedResult, "$id has an invalid expected result contract")
        }
    }

    @Test
    fun prototypeTranscodesMemoryAndFileModulesToHostC11() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib")) }
            ?: error("could not locate repository stdlib from ${Path.of("").toAbsolutePath()}")
        listOf("stdlib/io/file.cp", "stdlib/memory/xmem.cp").forEach { relativePath ->
            val source = sources.open(SourceId.named(relativePath), Files.readString(repository.resolve(relativePath)))
            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
            assertTrue(
                result.successful,
                "$relativePath: parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}"
            )
            val generatedC = result.cSource!!.text
            if (relativePath == "stdlib/memory/xmem.cp") {
                assertTrue("xmem__init(&xmem)" in generatedC, generatedC.takeLast(5_000))
                assertFalse("xmem.init(" in generatedC, generatedC.takeLast(5_000))
            }
            assertC11Syntax(generatedC, relativePath)
        }
    }

    @Test
    fun prototypeTranscodesSnakeExampleAndExtractsItsModelFixture() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib")) && Files.isDirectory(it.resolve("examples")) }
            ?: error("could not locate repository sources from ${Path.of("").toAbsolutePath()}")
        val example = repository.resolve("stdlib/examples/raylib/snake.cp")
        val stdlibRoot = repository.resolve("stdlib").toAbsolutePath().normalize()
        val source = sources.open(SourceId.named(example.toRealPath().toString()), Files.readString(example))

        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            importPaths = CPlusImportPaths(standardLibraryRoots = listOf(stdlibRoot))
        ).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.cSource ?: error("Snake example should produce mapped C")
        assertTrue("snake_app__step" in generated.text, generated.text)
        assertFalse(".step(" in generated.text, "implicit receiver calls should be lowered")
        assertEquals(listOf("-lraylib", "-lGL", "-lm", "-lpthread", "-ldl", "-lrt", "-lX11", "-lXrandr", "-lXinerama", "-lXcursor", "-lXi"), result.compilerOptions)
        assertEquals(listOf("snake model and render"), result.testFixtures.map { it.name })
        assertTrue(result.testFixtures.single().assertions.isNotEmpty(), "model behavior must remain testable as a fixture")
        assertTrue(result.sourceOrder.first() != source.id, "Raylib modules should precede the importing example")
        assertEquals(source.id, result.sourceOrder.last())
        val facade = SourceId.fromPath(stdlibRoot.resolve("graphics/raylib.cp"))
        val core = SourceId.fromPath(stdlibRoot.resolve("graphics/raylib/core.cp"))
        val guardedFacadeEdge = result.sourceImports.singleOrNull {
            it.importer == facade && it.imported == core
        } ?: error("guarded Raylib facade import edge is missing: ${result.sourceImports}")
        assertEquals(facade.value, guardedFacadeEdge.location.file)
        result.sourceImports.forEach { edge ->
            val importerIndex = result.sourceOrder.indexOf(edge.importer)
            val importedIndex = result.sourceOrder.indexOf(edge.imported)
            assertTrue(
                importedIndex >= 0 && importerIndex > importedIndex,
                "dependency must precede importer in source order: $edge; ${result.sourceOrder}"
            )
            assertEquals(edge.importer.value, edge.location.file, "import span should retain its requesting source")
        }
    }

    @Test
    fun prototypeTranscodesTheStandardLibraryAndRaylibFixtureCorpus() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib/tests")) && Files.isDirectory(it.resolve("stdlib/examples/raylib")) }
            ?: error("could not locate repository acceptance sources from ${Path.of("").toAbsolutePath()}")
        val stdlibRoot = repository.resolve("stdlib").toAbsolutePath().normalize()
        val inputs = listOf(repository.resolve("stdlib/tests"), repository.resolve("stdlib/examples/raylib"))
            .flatMap { directory ->
                Files.walk(directory).use { paths ->
                    paths.filter { it.toString().endsWith(".cp") || it.toString().endsWith(".c+") }
                        .sorted()
                        .toList()
                }
            }
        val failures = mutableListOf<String>()

        inputs.forEach { path ->
            val source = sources.open(SourceId.named(path.toRealPath().toString()), Files.readString(path))
            val result = TreeSitterCPlusPrototypeTranspiler(
                backend = backend,
                sourceManager = sources,
                targetOs = "linux",
                importPaths = CPlusImportPaths(standardLibraryRoots = listOf(stdlibRoot))
            ).transpile(source)
            if (!result.successful) {
                failures += buildString {
                    append(path.toString().removePrefix(repository.toString()).trimStart('/'))
                    append(": parser=").append(result.parserDiagnostics)
                    append("; lowering=").append(result.loweringDiagnostics)
                    append("; unsupported=").append(result.unsupportedNodes)
                }
            } else if (result.testFixtures.isEmpty()) {
                failures += "${path.toString().removePrefix(repository.toString()).trimStart('/')} produced no AST @test fixtures"
            } else {
                val generatedC = result.cSource!!.text
                if (!requiresRaylib(generatedC, result.compilerOptions) || raylibIsAvailable(result.compilerOptions)) {
                    validateCCompiles(
                        generatedC,
                        includeDirectories = listOf(path.parent),
                        compilerOptions = result.compilerOptions,
                        context = path.toString().removePrefix(repository.toString()).trimStart('/')
                    )
                }
                if (generatedC.contains("comptime function") || generatedC.contains("@name(")) {
                    failures += "${path.toString().removePrefix(repository.toString()).trimStart('/')} retained unresolved comptime generator syntax"
                }
                if (path == repository.resolve("stdlib/tests/containers.cp")) {
                    val expectedEntities = listOf(
                        "list_map__named_value_t__to__int",
                        "list_map__int__to__int",
                        "list_fold__int__with__long",
                        "list_group_by__named_value_t__by__int",
                        "list_zip__named_value_t__with__int"
                    )
                    expectedEntities.filterNot(generatedC::contains).forEach { missing ->
                        failures += "stdlib/tests/containers.cp did not materialize expected generic entity '$missing'"
                    }
                    if (expectedEntities.all(generatedC::contains)) {
                        compileAndRunC(
                            generatedC + "\n#undef main\nint main(void) { return 0; }\n",
                            includeDirectories = listOf(path.parent),
                            compilerOptions = listOf("-Dmain=cplus_ast_fixture_main")
                        )
                    }
                }
            }
        }

        assertTrue(
            failures.isEmpty(),
            "AST frontend did not transcode the legacy-accepted test/example corpus (${inputs.size} sources):\n" +
                failures.joinToString("\n")
        )
    }

    @Test
    fun prototypeTranscodesEveryRepositoryCPlusSourceWithoutActiveComptimeSyntax() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib")) && Files.isDirectory(it.resolve("examples")) }
            ?: error("could not locate repository C-plus sources from ${Path.of("").toAbsolutePath()}")
        val stdlibRoot = repository.resolve("stdlib").toAbsolutePath().normalize()
        val inputs = sequenceOf(repository.resolve("stdlib"), repository.resolve("examples"))
            .flatMap { directory ->
                Files.walk(directory).use { paths ->
                    paths.filter { it.toString().endsWith(".cp") || it.toString().endsWith(".c+") }
                        .sorted()
                        .toList()
                        .asSequence()
                }
            }
            .toList()
        assertTrue(inputs.isNotEmpty(), "repository C-plus corpus must not be empty")
        val failures = mutableListOf<String>()

        inputs.forEach { path ->
            val relative = repository.relativize(path).toString()
            try {
                val source = sources.open(SourceId.named(path.toRealPath().toString()), Files.readString(path))
                val result = TreeSitterCPlusPrototypeTranspiler(
                    backend = backend,
                    sourceManager = sources,
                    targetOs = hostTargetOs(),
                    targetArch = hostTargetArch(),
                    importPaths = CPlusImportPaths(standardLibraryRoots = listOf(stdlibRoot))
                ).transpile(source)
                if (!result.successful) {
                    failures += "$relative: parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
                } else if (result.cSource?.text.orEmpty().contains("comptime function") ||
                    result.cSource?.text.orEmpty().contains("@name(")
                ) {
                    failures += "$relative retained active comptime generator syntax"
                } else {
                    val generatedC = result.cSource?.text ?: error("successful AST result has no C output for $relative")
                    if (!requiresRaylib(generatedC, result.compilerOptions) || raylibIsAvailable(result.compilerOptions)) {
                        validateCCompilesWithAvailableDrivers(
                            code = generatedC,
                            includeDirectories = listOf(path.parent, stdlibRoot),
                            compilerOptions = result.compilerOptions,
                            context = relative
                        )
                    }
                }
            } catch (failure: Throwable) {
                failures += "$relative: ${failure.message ?: failure::class.simpleName}"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "AST frontend did not transcode the repository C-plus corpus (${inputs.size} sources):\n" +
                failures.joinToString("\n")
        )
    }

    @Test
    fun prototypeExecutesStandardLibraryFixturesAfterAstRuntimeLowering() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib/tests")) }
            ?: error("could not locate repository standard-library tests from ${Path.of("").toAbsolutePath()}")
        val stdlibRoot = repository.resolve("stdlib").toAbsolutePath().normalize()
        val inputs = Files.walk(repository.resolve("stdlib/tests")).use { paths ->
            paths.filter { it.toString().endsWith(".cp") || it.toString().endsWith(".c+") }
                .sorted()
                .toList()
        }
        val failures = mutableListOf<String>()

        inputs.forEach { path ->
            val relative = repository.relativize(path).toString()
            try {
                val source = sources.open(SourceId.named(path.toRealPath().toString()), Files.readString(path))
                val result = TreeSitterCPlusPrototypeTranspiler(
                    backend = backend,
                    sourceManager = sources,
                    targetOs = hostTargetOs(),
                    targetArch = hostTargetArch(),
                    importPaths = CPlusImportPaths(standardLibraryRoots = listOf(stdlibRoot))
                ).transpile(source)
                if (!result.successful) {
                    failures += "$relative: parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
                } else {
                    val harness = CPlusTranspiler().emitExtractedTestHarness(
                        result.cSource ?: error("successful AST result has no C output"),
                        result.testFixtures
                    )
                    if (!requiresRaylib(harness.source.code, result.compilerOptions) || raylibIsAvailable(result.compilerOptions)) {
                        compileAndRunC(
                            harness.source.code,
                            includeDirectories = listOf(path.parent, stdlibRoot),
                            compilerOptions = result.compilerOptions
                        )
                    }
                }
            } catch (failure: Throwable) {
                failures += "$relative: ${failure.message ?: failure::class.simpleName}"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "AST-lowered standard-library fixtures did not execute successfully:\n" +
                failures.joinToString("\n")
        )
    }

    @Test
    fun standardLibraryTestFixturesMeetHarnessDifferentialContract() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib/tests")) }
            ?: error("could not locate repository standard-library tests from ${Path.of("").toAbsolutePath()}")
        val stdlibRoot = repository.resolve("stdlib").toAbsolutePath().normalize()
        val inputs = Files.walk(repository.resolve("stdlib/tests")).use { paths ->
            paths.filter { it.toString().endsWith(".cp") || it.toString().endsWith(".c+") }
                .sorted()
                .toList()
        }
        val failures = mutableListOf<String>()
        inputs.forEach { path ->
            val relative = repository.relativize(path).toString()
            val source = sources.open(SourceId.named(path.toRealPath().toString()), Files.readString(path))
            val report = TreeSitterCPlusDifferentialRunner(backend, sources).compareTests(
                source,
                targetOs = "linux",
                importPaths = CPlusImportPaths(standardLibraryRoots = listOf(stdlibRoot))
            )
            if (!report.successful) {
                failures += "$relative: missing-map=${report.sourceMapCoverageMissing}; " +
                    (report.harnessTokenDifference ?: report.toString())
            }
        }
        assertTrue(
            failures.isEmpty(),
            "standard-library fixture harness differential failures (${inputs.size} sources):\n" +
                failures.joinToString("\n")
        )
    }

    @Test
    fun standardLibraryTestExtractionSubstitutionMeetsIndependentContract() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib/tests")) }
            ?: error("could not locate repository standard-library tests from ${Path.of("").toAbsolutePath()}")
        val stdlibRoot = repository.resolve("stdlib").toAbsolutePath().normalize()
        val inputs = Files.walk(repository.resolve("stdlib/tests")).use { paths ->
            paths.filter { it.toString().endsWith(".cp") || it.toString().endsWith(".c+") }
                .sorted()
                .toList()
        }
        val failures = mutableListOf<String>()
        inputs.forEach { path ->
            val relative = repository.relativize(path).toString()
            val source = sources.open(SourceId.named(path.toRealPath().toString()), Files.readString(path))
            val report = TreeSitterCPlusDifferentialRunner(backend, sources)
                .compareTestExtractionOnlySubstitution(
                    source,
                    targetOs = "linux",
                    importPaths = CPlusImportPaths(standardLibraryRoots = listOf(stdlibRoot))
                )
            if (!report.successful) {
                failures += "$relative: $report"
            }
        }
        assertTrue(
            failures.isEmpty(),
            "standard-library extraction substitution failures (${inputs.size} sources):\n" +
                failures.joinToString("\n")
        )
    }

    @Test
    fun prototypeExecutesFiniteRepositoryExamplesAfterAstLowering() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib")) && Files.isDirectory(it.resolve("examples")) }
            ?: error("could not locate repository examples from ${Path.of("").toAbsolutePath()}")
        val stdlibRoot = repository.resolve("stdlib").toAbsolutePath().normalize()
        val relativeInputs = listOf(
            "examples/basic.cp",
            "examples/generic_list.cp",
            "stdlib/examples/allocator_debug.cp",
            "stdlib/examples/allocators.cp",
            "stdlib/examples/containers.cp",
            "stdlib/examples/thread_pool.cp"
        )
        val failures = mutableListOf<String>()

        relativeInputs.forEach { relative ->
            val path = repository.resolve(relative)
            try {
                val source = sources.open(SourceId.named(path.toRealPath().toString()), Files.readString(path))
                val result = TreeSitterCPlusPrototypeTranspiler(
                    backend = backend,
                    sourceManager = sources,
                    targetOs = hostTargetOs(),
                    targetArch = hostTargetArch(),
                    importPaths = CPlusImportPaths(standardLibraryRoots = listOf(stdlibRoot))
                ).transpile(source)
                if (!result.successful) {
                    failures += "$relative: parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
                } else {
                    compileAndRunC(
                        result.cSource?.text ?: error("successful AST result has no C output for $relative"),
                        includeDirectories = listOf(path.parent, stdlibRoot),
                        compilerOptions = result.compilerOptions
                    )
                }
            } catch (failure: Throwable) {
                failures += "$relative: ${failure.message ?: failure::class.simpleName}"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "finite repository examples did not execute successfully through AST lowering:\n" +
                failures.joinToString("\n")
        )
    }

    @Test
    fun prototypeLowersEveryRaylibFixtureBeforeAstHarnessGeneration() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib/examples/raylib")) }
            ?: error("could not locate repository Raylib examples from ${Path.of("").toAbsolutePath()}")
        val stdlibRoot = repository.resolve("stdlib").toAbsolutePath().normalize()
        val inputs = Files.walk(repository.resolve("stdlib/examples/raylib")).use { paths ->
            paths.filter { it.toString().endsWith(".cp") || it.toString().endsWith(".c+") }
                .sorted()
                .toList()
        }
        assertEquals(8, inputs.size, "the Raylib example fixture corpus should remain explicit")

        inputs.forEach { path ->
            val relative = repository.relativize(path).toString()
            val sourceText = Files.readString(path)
            val source = sources.open(SourceId.named(path.toRealPath().toString()), sourceText)
            val result = TreeSitterCPlusPrototypeTranspiler(
                backend = backend,
                sourceManager = sources,
                targetOs = "linux",
                importPaths = CPlusImportPaths(standardLibraryRoots = listOf(stdlibRoot))
            ).transpile(source)

            assertTrue(
                result.successful,
                "$relative: parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
            )
            assertEquals(1, result.testFixtures.size, "$relative should expose exactly one model fixture")

            val fixtureBody = result.testFixtures.single().body.text
            assertFalse(
                Regex("(?m)^(?!\\s*//).*\\bdefer\\b").containsMatchIn(fixtureBody),
                "$relative fixture still contains a defer statement"
            )
            assertFalse("@try" in fixtureBody || "@catch" in fixtureBody, "$relative fixture still contains checked-error syntax")

            val methodNames = Regex(
                """(?m)^\s*(?:static\s+)?(?:pub|priv)\s+[^{;()]+\s+([A-Za-z_]\w*)\s*\("""
            ).findAll(sourceText).map { it.groupValues[1] }.toSet()
            methodNames.forEach { methodName ->
                assertFalse(
                    Regex("""(?m)^(?!\s*//)[^\n]*(?:\.|->)\s*${Regex.escape(methodName)}\s*\(""").containsMatchIn(fixtureBody),
                    "$relative fixture still contains an implicit C-plus receiver call for $methodName"
                )
            }

            // Exercise the same harness-only API used by the AST standard-library execution
            // gate.  Do not link or launch Raylib here: the source-corpus test owns C syntax
            // validation, while this test isolates the AST lowering/extraction boundary.
            val harness = CPlusTranspiler().emitExtractedTestHarness(
                result.cSource ?: error("successful AST result has no C output for $relative"),
                result.testFixtures
            )
            assertTrue(harness.source.code.contains("cplus_test_0"), "$relative harness was not generated")
            val harnessCodeWithoutLineComments = harness.source.code
                .lineSequence()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")
            val leftoverAssertions = Regex("@assert(?:Equals)?\\s*\\(")
                .findAll(harnessCodeWithoutLineComments).map { it.range.first }.toList()
            assertFalse(
                leftoverAssertions.isNotEmpty(),
                "$relative harness retained @assert annotations at $leftoverAssertions; extracted=${result.testFixtures.single().assertions.size}"
            )
            assertFalse("@test" in harness.source.code, "$relative harness retained a test declaration")
        }
    }

    @Test
    fun socketModuleSelectsPlatformAppropriateFeatureAndLinkFlags() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib/net")) }
            ?: error("could not locate repository stdlib from ${Path.of("").toAbsolutePath()}")
        val stdlibRoot = repository.resolve("stdlib").toAbsolutePath().normalize()
        val text = "comptime import \"stdlib:/net/socket.cp\";\nint main(void) { return 0; }\n"

        for ((targetOs, expectedFlags) in listOf(
            "linux" to listOf("-D_POSIX_C_SOURCE=200112L"),
            "windows" to listOf("-lws2_32")
        )) {
            val source = sources.open(SourceId.named("socket-flags-$targetOs.cp"), text)
            val result = TreeSitterCPlusPrototypeTranspiler(
                backend = backend,
                sourceManager = sources,
                targetOs = targetOs,
                importPaths = CPlusImportPaths(standardLibraryRoots = listOf(stdlibRoot))
            ).transpile(source)
            assertTrue(
                result.successful,
                "$targetOs parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
            )
            assertEquals(expectedFlags, result.compilerOptions, "$targetOs socket options")
        }
    }

    @Test
    fun prototypeLowersAstCImportsToMappedAbsoluteIncludes() {
        val directory = Files.createTempDirectory("cplus-ast-c-import")
        try {
            val cFile = directory.resolve("helper.c")
            val cpFile = directory.resolve("main.cp")
            Files.writeString(cFile, "int imported_value(void) { return 41; }\n")
            val text = """
                @import("helper.c");
                int main(void) { return imported_value() != 41; }
            """.trimIndent()
            val source = sources.open(SourceId.named(cpFile.toString()), text)

            val result = TreeSitterCPlusPrototypeTranspiler(
                backend = backend,
                sourceManager = sources,
                importPaths = CPlusImportPaths()
            ).transpile(source)

            assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
            val generated = result.cSource ?: error("successful C import lowering must emit C")
            // Windows paths contain backslashes, which must be escaped in the
            // generated C string literal.  Compare the emitted source rather
            // than the raw filesystem spelling.
            val escapedPath = cFile.toRealPath().toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            val include = "#include \"$escapedPath\""
            assertTrue(include in generated.text, generated.text)
            assertFalse("@import" in generated.text, generated.text)
            val generatedOffset = generated.text.indexOf(include)
            val origin = generated.originAt(generatedOffset)
            assertEquals(source.id.value, origin?.file?.name)
            assertEquals(text.indexOf("@import"), origin?.offset)
            compileAndRunC(generated.text)

            val generatedImportText = """
                comptime code @emit_c_import() {
                    return @code { @import("helper.c"); };
                }
                comptime emit_c_import();
                int main(void) { return imported_value() != 41; }
            """.trimIndent()
            val generatedImportSource = sources.open(SourceId.named(directory.resolve("generated.cp").toString()), generatedImportText)
            val generatedImportResult = TreeSitterCPlusPrototypeTranspiler(
                backend = backend,
                sourceManager = sources,
                importPaths = CPlusImportPaths()
            ).transpile(generatedImportSource)
            assertTrue(
                generatedImportResult.successful,
                "parser=${generatedImportResult.parserDiagnostics}; lowering=${generatedImportResult.loweringDiagnostics}"
            )
            val generatedImport = generatedImportResult.cSource ?: error("generated C import should lower")
            val generatedInclude = "#include \"$escapedPath\""
            assertTrue(generatedInclude in generatedImport.text, generatedImport.text)
            assertFalse("@import" in generatedImport.text, generatedImport.text)
            assertEquals(
                generatedImportText.indexOf("@import"),
                generatedImport.originAt(generatedImport.text.indexOf(generatedInclude))?.offset
            )
            compileAndRunC(generatedImport.text)

            val namespacedText = """
                @import("stdlib:/helper.c");
                int main(void) { return imported_value() != 41; }
            """.trimIndent()
            val namespacedSource = sources.open(SourceId.named(directory.resolve("namespaced.cp").toString()), namespacedText)
            val namespacedResult = TreeSitterCPlusPrototypeTranspiler(
                backend = backend,
                sourceManager = sources,
                importPaths = CPlusImportPaths(standardLibraryRoots = listOf(directory))
            ).transpile(namespacedSource)
            assertTrue(
                namespacedResult.successful,
                "parser=${namespacedResult.parserDiagnostics}; lowering=${namespacedResult.loweringDiagnostics}; unsupported=${namespacedResult.unsupportedNodes}"
            )
            compileAndRunC(namespacedResult.cSource!!.text)
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun prototypeMapsMissingCImportDiagnosticsToTheImportingSource() {
        val directory = Files.createTempDirectory("cplus-ast-c-import-missing")
        try {
            val text = "@import(\"missing.c\");\nint main(void) { return 0; }"
            val source = sources.open(SourceId.named(directory.resolve("main.cp").toString()), text)

            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

            val diagnostic = result.loweringDiagnostics.single()
            assertEquals("CPLUS_IMPORT_RESOLUTION", diagnostic.code)
            assertEquals(source.id.value, diagnostic.span.file)
            assertTrue(text.substring(diagnostic.span.startOffset, diagnostic.span.endOffset).startsWith("@"))
            assertTrue(result.cSource == null)
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun prototypeExpandsComptimeModulesOnceInDependencyOrderWithMappedOrigins() {
        val directory = Files.createTempDirectory("cplus-ast-module-import")
        try {
            val helper = directory.resolve("helper.cp")
            val child = directory.resolve("child.cp")
            val rootPath = directory.resolve("main.cp")
            Files.writeString(
                helper,
                "comptime int imported_answer = 42;\n" +
                    "comptime code @emit_value(int value) { return @code { int generated_from_block = @value; }; }\n" +
                    "int imported_value(void) { return comptime imported_answer; }\n" +
                    "@test \"helper fixture\" { int value = comptime imported_answer; @assertEquals(42, value); }\n"
            )
            Files.writeString(
                child,
                "comptime import \"stdlib:/helper.cp\";\nint child_value(void) { return imported_value(); }\n"
            )
            val text = """
                comptime import "child.cp";
                @import("child.cp");
                comptime {
                    comptime int @block_value = imported_answer + 1;
                    comptime emit_value(block_value);
                }
                int main(void) { return child_value() != 42 || generated_from_block != 43; }
            """.trimIndent()
            val source = sources.open(SourceId.named(rootPath.toString()), text)

            val result = TreeSitterCPlusPrototypeTranspiler(
                backend,
                sources,
                importPaths = CPlusImportPaths(standardLibraryRoots = listOf(directory))
            ).transpile(source)

            assertTrue(
                result.successful,
                "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
            )
            val generated = result.cSource ?: error("successful module import expansion must emit C")
            assertEquals(2, Regex("imported_value").findAll(generated.text).count())
            assertFalse("comptime import" in generated.text, generated.text)
            assertFalse("@import" in generated.text, generated.text)
            assertEquals(listOf("helper fixture"), result.testFixtures.map { it.name })
            assertEquals(helper.toRealPath().toString(), result.testFixtures.single().span.file)
            assertEquals(helper.toRealPath().toString(), result.testFixtures.single().assertions.single().span.file)
            val fixtureBody = result.testFixtures.single().body
            assertTrue("int value = 42;" in fixtureBody.text, fixtureBody.text)
            assertEquals(helper.toRealPath().toString(), fixtureBody.originAt(fixtureBody.text.indexOf("42"))?.file?.name)
            val generatedValueOffset = generated.text.indexOf("generated_from_block = 43") + "generated_from_block = ".length
            assertTrue(generatedValueOffset >= "generated_from_block = ".length, generated.text)
            assertEquals(rootPath.toString(), generated.originAt(generatedValueOffset)?.file?.name)
            assertEquals(text.indexOf("block_value);"), generated.originAt(generatedValueOffset)?.offset)
            assertEquals(3, result.sourceOrder.size)
            assertEquals(SourceId.fromPath(helper), result.sourceOrder[0])
            assertEquals(SourceId.fromPath(child), result.sourceOrder[1])
            assertEquals(SourceId.fromPath(rootPath), result.sourceOrder.last())
            assertEquals(2, result.sourceImports.size, result.sourceImports.toString())
            assertEquals(SourceId.fromPath(rootPath), result.sourceImports[0].importer)
            assertEquals(SourceId.fromPath(child), result.sourceImports[0].imported)
            assertEquals(SourceId.fromPath(child), result.sourceImports[1].importer)
            assertEquals(SourceId.fromPath(helper), result.sourceImports[1].imported)
            val helperOffset = generated.text.indexOf("int imported_value")
            assertEquals(helper.toRealPath().toString(), generated.originAt(helperOffset)?.file?.name)
            assertEquals(result.sourceOrder, result.transcodedSource?.sourceOrder)
            assertEquals(result.sourceImports, result.transcodedSource?.sourceImports)
            compileAndRunC(generated.text)
            compileAndRunC(CPlusTranspiler().transpileExtractedTests(generated, result.testFixtures).source.code)
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun prototypeExpandsComptimeModuleImportEmittedByCodeBeforeResolvingImportedGenerator() {
        val directory = Files.createTempDirectory("cplus-ast-generated-module-import")
        try {
            val helper = directory.resolve("generated_helper.cp")
            val rootPath = directory.resolve("generated_root.cp")
            Files.writeString(
                helper,
                """
                    comptime function @make_imported_value() {
                        return int generated_imported_value(void) { return 42; }
                    }
                    comptime make_imported_value();
                """.trimIndent()
            )
            val text = """
                comptime code @emit_import() {
                    return @code { comptime import "generated_helper.cp"; };
                }
                comptime emit_import();
                int main(void) { return generated_imported_value() == 42 ? 0 : 1; }
            """.trimIndent()
            val source = sources.open(SourceId.named(rootPath.toString()), text)

            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

            assertTrue(
                result.successful,
                "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
            )
            val generated = result.cSource ?: error("generated module import should materialize before C emission")
            assertTrue("int generated_imported_value(void)" in generated.text, generated.text)
            assertFalse("comptime import" in generated.text, generated.text)
            assertEquals(listOf(SourceId.fromPath(helper), SourceId.fromPath(rootPath)), result.sourceOrder)
            assertEquals(SourceId.fromPath(rootPath), result.sourceImports.single().importer)
            assertEquals(SourceId.fromPath(helper), result.sourceImports.single().imported)
            val declarationOffset = generated.text.indexOf("generated_imported_value")
            assertEquals(helper.toRealPath().toString(), generated.originAt(declarationOffset)?.file?.name)
            compileAndRunC(generated.text)
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun prototypeResolvesGeneratedModuleImportRelativeToItsImportedModuleOrigin() {
        val directory = Files.createTempDirectory("cplus-ast-generated-nested-module-import")
        try {
            val modules = Files.createDirectories(directory.resolve("modules"))
            val dependency = modules.resolve("dependency.cp")
            val helper = modules.resolve("helper.cp")
            val rootPath = directory.resolve("root.cp")
            Files.writeString(dependency, "int dependency_value(void) { return 42; }\n")
            Files.writeString(
                helper,
                """
                    comptime code @emit_dependency_import() {
                        return @code { comptime import "dependency.cp"; };
                    }
                    comptime emit_dependency_import();
                    int imported_module_value(void) { return dependency_value(); }
                """.trimIndent()
            )
            val text = """
                comptime import "modules/helper.cp";
                int main(void) { return imported_module_value() == 42 ? 0 : 1; }
            """.trimIndent()
            val source = sources.open(SourceId.named(rootPath.toString()), text)

            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

            assertTrue(
                result.successful,
                "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
            )
            val generated = result.cSource ?: error("nested generated module import should materialize")
            assertTrue("int dependency_value(void)" in generated.text, generated.text)
            assertTrue("int imported_module_value(void)" in generated.text, generated.text)
            assertEquals(
                listOf(SourceId.fromPath(dependency), SourceId.fromPath(helper), SourceId.fromPath(rootPath)),
                result.sourceOrder
            )
            assertEquals(
                listOf(SourceId.fromPath(rootPath) to SourceId.fromPath(helper), SourceId.fromPath(helper) to SourceId.fromPath(dependency)),
                result.sourceImports.map { it.importer to it.imported }
            )
            compileAndRunC(generated.text)
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun prototypeDiagnosesCyclesCreatedByGeneratedModuleImportsAtTheOriginatingImport() {
        val directory = Files.createTempDirectory("cplus-ast-generated-module-cycle")
        try {
            val modules = Files.createDirectories(directory.resolve("modules"))
            val dependency = modules.resolve("dependency.cp")
            val helper = modules.resolve("helper.cp")
            val rootPath = directory.resolve("root.cp")
            Files.writeString(dependency, "comptime import \"helper.cp\";\nint dependency_value(void) { return 42; }\n")
            val helperText = """
                comptime code @emit_dependency_import() {
                    return @code { comptime import "dependency.cp"; };
                }
                comptime emit_dependency_import();
                int imported_module_value(void) { return dependency_value(); }
            """.trimIndent()
            Files.writeString(helper, helperText)
            val root = sources.open(
                SourceId.named(rootPath.toString()),
                "comptime import \"modules/helper.cp\";\nint main(void) { return imported_module_value(); }"
            )

            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(root)

            assertFalse(result.successful, "generated module-import cycles must fail closed")
            val diagnostic = result.loweringDiagnostics.single()
            assertEquals("CPLUS_IMPORT_CYCLE", diagnostic.code)
            assertEquals(dependency.toRealPath().toString(), diagnostic.span.file)
            assertEquals(Files.readString(dependency).indexOf("comptime import"), diagnostic.span.startOffset)
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun prototypeRejectsComptimeImportCyclesAtTheRequestingSourceSpan() {
        val directory = Files.createTempDirectory("cplus-ast-module-cycle")
        try {
            val rootPath = directory.resolve("root.cp")
            val childPath = directory.resolve("child.cp")
            val rootText = "comptime import \"child.cp\";\nint main(void) { return 0; }"
            val childText = "comptime import \"root.cp\";\nint child_value(void) { return 1; }"
            Files.writeString(rootPath, rootText)
            Files.writeString(childPath, childText)
            val root = sources.open(SourceId.named(rootPath.toString()), rootText)

            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(root)

            val diagnostic = result.loweringDiagnostics.single()
            assertEquals("CPLUS_IMPORT_CYCLE", diagnostic.code)
            assertEquals(childPath.toRealPath().toString(), diagnostic.span.file)
            assertEquals(childText.indexOf("comptime import"), diagnostic.span.startOffset)
            assertTrue(diagnostic.message.contains("root.cp") && diagnostic.message.contains("child.cp"))
            assertEquals(null, result.cSource)
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun prototypeResolvesOnlyImportsInTheSelectedPlatformBranch() {
        val directory = Files.createTempDirectory("cplus-ast-conditional-import")
        try {
            Files.writeString(directory.resolve("linux.cp"), "int selected_value(void) { return 42; }\n")
            val text = """
                @if (os == "windows") { comptime import "missing-windows.cp"; }
                @else { comptime import "linux.cp"; }
                int main(void) { return selected_value() != 42; }
            """.trimIndent()
            val source = sources.open(SourceId.named(directory.resolve("main.cp").toString()), text)

            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources, targetOs = "linux").transpile(source)

            assertTrue(
                result.successful,
                "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
            )
            assertEquals(2, result.sourceOrder.size)
            assertTrue("selected_value" in result.cSource!!.text)
            assertFalse("missing-windows" in result.cSource.text)
            compileAndRunC(result.cSource.text)
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun prototypeRejectsCPlusModuleImportsOutsideModuleScope() {
        val directory = Files.createTempDirectory("cplus-ast-module-import-scope")
        try {
            val text = "int main(void) { comptime import \"missing.cp\"; return 0; }"
            val source = sources.open(SourceId.named(directory.resolve("main.cp").toString()), text)

            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

            val diagnostic = result.loweringDiagnostics.single()
            assertEquals("CPLUS_IMPORT_SCOPE", diagnostic.code)
            assertEquals(source.id.value, diagnostic.span.file)
            assertTrue(text.substring(diagnostic.span.startOffset, diagnostic.span.endOffset).contains("comptime import"))
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun prototypeKeepsGeneratorBodyImportsDormantUntilMaterialization() {
        val directory = Files.createTempDirectory("cplus-ast-dormant-import")
        try {
            val text = """
                comptime function @deferred(int value) {
                    comptime import "missing-generator-dependency.cp";
                    return value;
                }
                int main(void) { return 0; }
            """.trimIndent()
            val source = sources.open(SourceId.named(directory.resolve("main.cp").toString()), text)

            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

            assertTrue(result.cSource == null)
            assertFalse(result.loweringDiagnostics.any { it.code == "CPLUS_IMPORT_RESOLUTION" })
            assertTrue(result.unsupportedNodes.any { it.syntaxKind == "cplus_comptime_declaration" })
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun parsesEveryRunnableExampleWithoutRecovery() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("examples")) }
            ?: error("could not locate repository examples from ${Path.of("").toAbsolutePath()}")
        val examples = Files.walk(repository.resolve("examples")).use { paths ->
            paths.filter { it.toString().endsWith(".cp") || it.toString().endsWith(".c+") }
                .sorted()
                .toList()
        }
        assertTrue(examples.isNotEmpty(), "runnable C-plus examples must not be empty")

        examples.forEach { path ->
            val relativePath = repository.relativize(path).toString()
            val snapshot = sources.open(SourceId.named(relativePath), Files.readString(path))
            val parsed = backend.parse(snapshot)
            val recovered = parsed.root.descendants()
                .filter { it.isError || it.isMissing }
                .map { node ->
                    val fragment = snapshot.text.substring(node.span.startOffset, node.span.endOffset)
                    "${node.kind} '$fragment' at ${node.span.startLine}:${node.span.startColumn}"
                }.toList()
            assertTrue(
                parsed.diagnostics.isEmpty() && recovered.isEmpty(),
                "$relativePath: diagnostics=${parsed.diagnostics}; recovery=$recovered"
            )
        }
    }

    @Test
    fun parsesCAndCPlusMethodsIntoStableNodes() {
        val snapshot = sources.open(
            SourceId.named("parser-test.cp"),
            """
            typedef struct counter_t {
                int value;
                pub int increment(borrowed mut *self, int amount) { return amount; }
                static pub counter_t *create(int initial) { return 0; }
            } counter_t;
            int main(void) { counter_t counter; counter.increment(1); return 0; }
            """.trimIndent()
        )

        val result = backend.parse(snapshot)

        assertEquals(ParseCoverage.STRUCTURAL, result.coverage)
        assertEquals("translation_unit", result.root.kind)
        val methods = result.root.descendants().filter { it.kind == "cplus_method_definition" }
        assertEquals(2, methods.size)
        assertTrue(methods.all { snapshot.text.substring(it.span.startOffset, it.span.endOffset).contains("pub") })
        assertTrue(result.root.descendants().any { it.kind == "call_expression" })
        assertTrue(result.diagnostics.isEmpty())
        val stableKinds = CPlusAstAdapter().adapt(result).root.descendantsAndSelf().map { it.kind }.toList()
        assertTrue(cplus.CPlusAstKind.ANNOTATION in stableKinds)
    }

    @Test
    fun normalizesCommonCDeclarationAndStatementKinds() {
        val text = """
            #include <stddef.h>
            /* comments remain represented in the normalized tree */
            typedef struct record_t { int field; } record_t;
            struct flags_t { unsigned bits : 3; };
            union payload { int number; char byte; };
            enum state { STATE_OFF = 0, STATE_ON };
            extern "C" { int linked_function(int value); }
            int global_value;
            const char *joined = "front" "end";
            int initialized[2] = { [0] = 7 };
            __attribute__((unused)) int attributed;
            int prototype(int value);
            int variadic(const char *format, ...);
            int main(void) { [[likely]] if (global_value) { global_value--; } if (global_value) { global_value--; } else { global_value++; } while (global_value) { global_value--; } goto finish; finish: return 0; }
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("normalized-c.c"), text)
        val parsed = backend.parse(snapshot)
        val ast = CPlusAstAdapter().adapt(parsed)

        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val kinds = ast.root.descendantsAndSelf().map { it.kind }.toList()
        val editorJson = CPlusParseJson.encode(parsed)
        assertTrue(editorJson.contains("\"kind\":\"type_alias\""), editorJson)
        assertTrue(editorJson.contains("\"kind\":\"union_declaration\""), editorJson)
        assertTrue(editorJson.contains("\"kind\":\"control_flow\""), editorJson)
        assertTrue(cplus.CPlusAstKind.TYPE_ALIAS in kinds)
        assertTrue(cplus.CPlusAstKind.STRUCT_DECLARATION in kinds)
        assertTrue(cplus.CPlusAstKind.FIELD_LIST in kinds)
        assertTrue(cplus.CPlusAstKind.DECLARATOR in kinds)
        assertTrue(cplus.CPlusAstKind.UNION_DECLARATION in kinds)
        assertTrue(cplus.CPlusAstKind.LINKAGE_SPECIFICATION in kinds)
        assertTrue(cplus.CPlusAstKind.DECLARATION_LIST in kinds)
        assertTrue(cplus.CPlusAstKind.ENUM_DECLARATION in kinds)
        assertTrue(cplus.CPlusAstKind.FIELD_DECLARATION in kinds)
        assertEquals(1, kinds.count { it == cplus.CPlusAstKind.ENUMERATOR_LIST })
        assertEquals(2, kinds.count { it == cplus.CPlusAstKind.ENUMERATOR })
        assertTrue(kinds.count { it == cplus.CPlusAstKind.PARAMETER } >= 3)
        assertTrue(cplus.CPlusAstKind.DECLARATOR in kinds)
        assertTrue(cplus.CPlusAstKind.INITIALIZER in kinds)
        assertTrue(cplus.CPlusAstKind.DESIGNATOR in kinds)
        assertTrue(cplus.CPlusAstKind.ATTRIBUTE in kinds)
        assertTrue(cplus.CPlusAstKind.VARIABLE_DECLARATION in kinds)
        assertTrue(cplus.CPlusAstKind.FUNCTION_DECLARATION in kinds)
        assertTrue(cplus.CPlusAstKind.PREPROCESSOR in kinds)
        assertTrue(cplus.CPlusAstKind.CONTROL_FLOW in kinds)
        assertTrue(cplus.CPlusAstKind.COMMENT in kinds)
        assertTrue(cplus.CPlusAstKind.LITERAL in kinds)
        assertTrue(ast.root.descendantsAndSelf().any {
            it.syntaxKind == "statement_identifier" && it.kind == cplus.CPlusAstKind.IDENTIFIER
        }, ast.dump())
        assertTrue(ast.root.descendantsAndSelf().any {
            it.syntaxKind == "attributed_statement" && it.kind == cplus.CPlusAstKind.STATEMENT
        }, ast.dump())
        assertTrue(ast.root.descendantsAndSelf().any {
            it.syntaxKind == "concatenated_string" && it.kind == cplus.CPlusAstKind.LITERAL
        }, ast.dump())
    }

    @Test
    fun distinguishesFunctionPointerVariablesFromFunctionPrototypesInStableAst() {
        val text = """
            int callback(int value);
            int (*callback_pointer)(int value);
            int *returns_pointer(void);
            int (*factory(void))(int value);
            int mixed_function(void), (*mixed_callback)(void);
        """.trimIndent()
        val source = sources.open(SourceId.named("function-declaration-shapes.c"), text)
        val parsed = backend.parse(source)
        val ast = CPlusAstAdapter().adapt(parsed)

        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val declarations = ast.root.children.filter { it.syntaxKind == "declaration" }
        assertEquals(5, declarations.size)
        assertEquals(cplus.CPlusAstKind.FUNCTION_DECLARATION, declarations[0].kind)
        assertEquals(cplus.CPlusAstKind.VARIABLE_DECLARATION, declarations[1].kind, ast.dump())
        assertEquals(cplus.CPlusAstKind.FUNCTION_DECLARATION, declarations[2].kind, ast.dump())
        assertEquals(cplus.CPlusAstKind.FUNCTION_DECLARATION, declarations[3].kind, ast.dump())
        assertEquals(cplus.CPlusAstKind.VARIABLE_DECLARATION, declarations[4].kind, ast.dump())
    }

    @Test
    fun normalizesParameterAndArgumentListsAsStableAstContainers() {
        val source = sources.open(
            SourceId.named("ast-list-containers.c"),
            "int call(int value); int wrapper(void) { return call(7); }"
        )
        val parsed = backend.parse(source)
        val ast = CPlusAstAdapter().adapt(parsed)
        val nodes = ast.root.descendantsAndSelf().toList()

        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        assertEquals(2, nodes.count { it.kind == cplus.CPlusAstKind.PARAMETER_LIST })
        assertEquals(1, nodes.count { it.kind == cplus.CPlusAstKind.ARGUMENT_LIST })
        assertTrue(nodes.filter { it.kind in setOf(cplus.CPlusAstKind.PARAMETER_LIST, cplus.CPlusAstKind.ARGUMENT_LIST) }
            .all { it.span.startOffset >= 0 && it.span.endOffset <= source.text.length })
    }

    @Test
    fun analyzesDirectAllocationIntentMismatchFromAstAndMapsItsSpan() {
        val snapshot = sources.open(
            SourceId.named("allocation-shape.cp"),
            "int main(void) { hot char* value = alloc_cold(64); scratch char* aligned = alloc_scratch(32); return 0; }"
        )
        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(backend.parse(snapshot)))

        assertEquals(1, result.diagnostics.size)
        assertTrue(result.diagnostics.single().message.contains("'value' is declared hot but receives memory from alloc_cold()"))
        assertEquals(snapshot.sourceFile.span(snapshot.text.indexOf("value"), snapshot.text.indexOf("value") + 5), result.diagnostics.single().sourceSpan)
        val symbol = result.symbols.single { it.name == "value" }
        assertEquals(AllocationSymbolKind.VARIABLE, symbol.kind)
        assertEquals(AllocationIntent.HOT, symbol.intent)
        assertEquals(AllocationIntent.COLD, symbol.knownProvenance)
        val matching = result.symbols.single { it.name == "aligned" }
        assertEquals(AllocationIntent.SCRATCH, matching.intent)
        assertEquals(AllocationIntent.SCRATCH, matching.knownProvenance)
    }

    @Test
    fun exposesAstAllocationDiagnosticsThroughPrototypeAndTranscodedResult() {
        val snapshot = sources.open(
            SourceId.named("allocation-prototype.cp"),
            "int main(void) { hot char* value = alloc_cold(64); return value == 0; }"
        )
        val result = TreeSitterCPlusPrototypeTranspiler(sourceManager = sources).transpile(snapshot)

        assertTrue(result.successful, "diagnostics=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        assertEquals(1, result.allocationAnalysis.diagnostics.size)
        assertEquals(result.allocationAnalysis, result.transcodedSource?.allocationAnalysis)
        assertEquals(snapshot.text.indexOf("value"), result.allocationAnalysis.diagnostics.single().sourceSpan.startOffset)
    }

    @Test
    fun propagatesAllocationProvenanceThroughAliasesWithoutLeakingNestedScopes() {
        val snapshot = sources.open(
            SourceId.named("allocation-aliases.cp"),
            """
                int main(void) {
                    scratch char* source = alloc_scratch(32);
                    char* alias = source;
                    warm char* mismatch = alias;
                    warm char* reassigned = NULL;
                    reassigned = alias;
                    warm char* conditional = NULL;
                    int enabled = 1;
                    enabled && (conditional = alias);
                    warm char* after_conditional = conditional;
                    {
                        cold char* alias = alloc_cold(16);
                        cold char* local = alias;
                    }
                    warm char* outer_mismatch = alias;
                    consume(alias);
                    return 0;
                }
                void consume(borrowed warm char* value);
            """.trimIndent()
        )
        val ast = CPlusAstAdapter().adapt(backend.parse(snapshot))
        val result = TreeSitterAllocationIntentAnalyzer().analyze(ast)

        assertEquals(5, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(result.diagnostics.any { it.message.contains("'mismatch' is declared warm") })
        assertTrue(result.diagnostics.any { it.message.contains("'reassigned' is declared warm") })
        assertTrue(result.diagnostics.any { it.message.contains("'conditional' is declared warm") })
        assertTrue(result.diagnostics.any { it.message.contains("'outer_mismatch' is declared warm") })
        assertTrue(
            result.diagnostics.any { it.message.contains("argument for 'consume.value' is scratch but the parameter expects warm") },
            result.diagnostics.toString()
        )
        assertEquals(snapshot.text.lastIndexOf("alias"), result.diagnostics.single { it.message.contains("argument for 'consume.value'") }.sourceSpan.startOffset)
        val parameter = result.symbols.single { it.kind == AllocationSymbolKind.PARAMETER && it.name == "value" }
        assertEquals(AllocationIntent.WARM, parameter.intent)
        assertEquals(AllocationOwnership.BORROWED, parameter.ownership)
        assertFalse(result.diagnostics.any { it.message.contains("'local'") })
        assertFalse(result.diagnostics.any { it.message.contains("'after_conditional'") })
        assertEquals(AllocationIntent.SCRATCH, result.symbols.single { it.name == "alias" && it.sourceSpan.startLine == 3 }.knownProvenance)
        assertEquals(AllocationIntent.COLD, result.symbols.single { it.name == "alias" && it.sourceSpan.startLine == 12 }.knownProvenance)
    }

    @Test
    fun checksAnnotatedFunctionReturnAllocationIntentFromAst() {
        val snapshot = sources.open(
            SourceId.named("allocation-return.cp"),
            """
                owned warm char* make_name(void);
                char* make_name(void) { return alloc_cold(24); }
                owned cold char* make_cold(void);
                char* make_cold(void) { return alloc_cold(16); }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertEquals(1, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(result.diagnostics.single().message.contains("function 'make_name' is annotated warm but returns alloc_cold()"))
        val returned = result.symbols.single { it.kind == AllocationSymbolKind.FUNCTION_RETURN && it.name == "make_name" }
        assertEquals("make_name", returned.name)
        assertEquals(AllocationIntent.WARM, returned.intent)
        assertEquals(AllocationOwnership.OWNED, returned.ownership)
        assertEquals(AllocationIntent.COLD, result.symbols.single { it.kind == AllocationSymbolKind.FUNCTION_RETURN && it.name == "make_cold" }.intent)
    }

    @Test
    fun checksAnnotatedMethodReturnAllocationIntentFromAst() {
        val snapshot = sources.open(
            SourceId.named("allocation-method-return.cp"),
            """
                typedef struct factory_t {
                    pub owned warm char* make(borrowed *self) { return alloc_cold(24); }
                    pub owned cold char* make_matching(borrowed *self) { return alloc_cold(16); }
                } factory_t;
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertEquals(1, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(result.diagnostics.single().message.contains("method 'make' is annotated warm but returns alloc_cold()"))
        val returned = result.symbols.single { it.kind == AllocationSymbolKind.FUNCTION_RETURN && it.name == "make" }
        assertEquals(AllocationIntent.WARM, returned.intent)
        assertEquals(AllocationOwnership.OWNED, returned.ownership)
        assertEquals("alloc_cold(24)", snapshot.text.substring(
            result.diagnostics.single().sourceSpan.startOffset,
            result.diagnostics.single().sourceSpan.endOffset
        ))
    }

    @Test
    fun checksOwnedOutputPointerAllocationAndSkipsTheCallArgumentAsAnInput() {
        val snapshot = sources.open(
            SourceId.named("allocation-output.cp"),
            """
                int fill(owned warm char** out) { *out = alloc_cold(8); return 0; }
                int fill_matching(owned cold char** out) { *out = alloc_cold(8); return 0; }
                int main(void) {
                    char* value = alloc_cold(8);
                    fill(&value);
                    warm char* possible_warm_output = value;
                    fill_matching(&value);
                    cold char* possible_cold_output = value;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertEquals(1, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(result.diagnostics.single().message.contains("'out' is declared warm but receives memory from alloc_cold()"))
        assertFalse(result.diagnostics.single().message.contains("argument for"))
        val outputs = result.symbols.filter { it.kind == AllocationSymbolKind.PARAMETER && it.name == "out" }
        assertEquals(2, outputs.size)
        assertEquals(setOf(AllocationIntent.WARM, AllocationIntent.COLD), outputs.map { it.intent }.toSet())
        assertTrue(outputs.all { it.ownership == AllocationOwnership.OWNED })
        assertTrue(outputs.all { it.knownProvenance == AllocationIntent.NONE })
        assertFalse(result.diagnostics.any { it.message.contains("possible_warm_output") })
        assertFalse(result.diagnostics.any { it.message.contains("possible_cold_output") })
    }

    @Test
    fun checksExplicitTypeQualifiedInstanceCallArgumentsAfterTheReceiver() {
        val snapshot = sources.open(
            SourceId.named("allocation-explicit-instance-call.cp"),
            """
                typedef struct sink_t {
                    pub int check(borrowed *self, borrowed warm char* value) { return value == 0; }
                } sink_t;
                int main(void) {
                    sink_t sink;
                    scratch char* input = alloc_scratch(8);
                    sink_t.check(&sink, input);
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val ast = CPlusAstAdapter().adapt(parsed)
        val semanticCalls = CPlusSemanticAnalyzer().analyze(ast).resolvedCalls
        assertEquals(listOf("check"), semanticCalls.map { it.methodName }, semanticCalls.toString())
        val result = TreeSitterAllocationIntentAnalyzer().analyze(ast)

        assertEquals(1, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(result.diagnostics.single().message.contains("argument for 'sink_t.check.value'"))
        assertEquals(
            snapshot.text.indexOf("input", snapshot.text.indexOf("sink_t.check")),
            result.diagnostics.single().sourceSpan.startOffset
        )
    }

    @Test
    fun checksAllocationContractsOnResolvedInstanceAndStaticMethods() {
        val snapshot = sources.open(
            SourceId.named("allocation-methods.cp"),
            """
                typedef struct sink_t {
                    pub int consume(borrowed warm char* value) { return value == 0; }
                    static pub int validate(borrowed warm char* value) { return value == 0; }
                } sink_t;
                int main(void) {
                    scratch char* input = alloc_scratch(16);
                    warm char* compatible = alloc_warm(8);
                    sink_t sink;
                    sink.consume(input);
                    sink_t.validate(input);
                    sink.consume(compatible);
                    sink_t.validate(compatible);
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        val semanticCalls = CPlusSemanticAnalyzer().analyze(ast).resolvedCalls
        assertEquals(setOf("consume", "validate"), semanticCalls.map { it.methodName }.toSet())
        val result = TreeSitterAllocationIntentAnalyzer().analyze(ast)

        assertEquals(2, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(result.diagnostics.any { it.message.contains("argument for 'sink_t.consume.value'") })
        assertTrue(result.diagnostics.any { it.message.contains("argument for 'sink_t.validate.value'") })
        assertTrue(result.diagnostics.all { it.sourceSpan.startOffset == snapshot.text.indexOf("input", it.sourceSpan.startOffset) })
    }

    @Test
    fun propagatesAnnotatedFunctionAndMethodReturnProvenanceIntoCallArguments() {
        val snapshot = sources.open(
            SourceId.named("allocation-call-provenance.cp"),
            """
                owned scratch char* make_scratch(void) { return alloc_scratch(8); }
                void consume(borrowed cold char* value);
                typedef struct maker_t {
                    pub owned warm char* make(borrowed *self) { return alloc_warm(8); }
                } maker_t;
                int main(void) {
                    maker_t maker;
                    consume(make_scratch());
                    consume(maker.make());
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertEquals(2, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(result.diagnostics.any {
            it.message.contains("argument for 'consume.value' is scratch but the parameter expects cold")
        })
        assertTrue(result.diagnostics.any {
            it.message.contains("argument for 'consume.value' is warm but the parameter expects cold")
        })
        assertTrue(result.diagnostics.all {
            snapshot.text.substring(it.sourceSpan.startOffset, it.sourceSpan.endOffset)
                .startsWith(if (it.message.contains("scratch")) "make_scratch" else "maker.make")
        })
    }

    @Test
    fun mergesAllocationProvenanceOnlyWhenEveryIfBranchAgrees() {
        val snapshot = sources.open(
            SourceId.named("allocation-branch-merge.cp"),
            """
                int main(int flag) {
                    scratch char* scratch_source = alloc_scratch(8);
                    char* selected = alloc_warm(8);
                    if (flag) {
                        selected = scratch_source;
                    } else {
                        selected = alloc_scratch(16);
                    }
                    warm char* known_mismatch = selected;

                    char* uncertain = alloc_warm(8);
                    if (flag) uncertain = scratch_source;
                    warm char* not_proven = uncertain;

                    char* divergent = alloc_warm(8);
                    if (flag) {
                        divergent = alloc_scratch(8);
                    } else {
                        divergent = alloc_cold(8);
                    }
                    warm char* branch_disagreement = divergent;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        val result = TreeSitterAllocationIntentAnalyzer().analyze(ast)

        assertEquals(1, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(result.diagnostics.single().message.contains("'known_mismatch' is declared warm"))
        assertFalse(result.diagnostics.any { it.message.contains("'not_proven'") })
        assertFalse(result.diagnostics.any { it.message.contains("'branch_disagreement'") })
        assertEquals(snapshot.text.indexOf("known_mismatch"), result.diagnostics.single().sourceSpan.startOffset)
    }

    @Test
    fun infersTernaryAllocationProvenanceOnlyWhenBothArmsAgree() {
        val snapshot = sources.open(
            SourceId.named("allocation-conditional-expression.cp"),
            """
                int main(int flag) {
                    scratch char* scratch_value = alloc_scratch(8);
                    char* same_domain = flag ? scratch_value : alloc_scratch(16);
                    warm char* mismatch = same_domain;

                    char* different_domains = flag ? alloc_scratch(8) : alloc_warm(8);
                    warm char* unknown_domain = different_domains;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertEquals(1, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(result.diagnostics.single().message.contains("'mismatch' is declared warm"))
        assertFalse(result.diagnostics.any { it.message.contains("'unknown_domain'") })
    }

    @Test
    fun infersAllocationProvenanceFromAssignmentAndCommaExpressionResults() {
        val snapshot = sources.open(
            SourceId.named("allocation-expression-results.cp"),
            """
                int main(void) {
                    scratch char* target = alloc_scratch(8);
                    warm char* from_assignment = (target = alloc_cold(8));
                    warm char* from_comma = (0, alloc_cold(16));
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertEquals(3, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(result.diagnostics.any { it.message.contains("'target' is declared scratch") })
        assertTrue(result.diagnostics.any { it.message.contains("'from_assignment' is declared warm") })
        assertTrue(result.diagnostics.any { it.message.contains("'from_comma' is declared warm") })
    }

    @Test
    fun allocationFlowJoinsByDomainAndModelsShortCircuitAndSequencedAssignments() {
        val snapshot = sources.open(
            SourceId.named("allocation-expression-flow.cp"),
            """
                int main(int flag) {
                    scratch char* scratch_source = alloc_scratch(8);

                    char* agreeing = alloc_cold(8);
                    if (flag) agreeing = scratch_source;
                    else agreeing = alloc_scratch(16);
                    scratch char* agreed_domain = agreeing;

                    char* short_circuit = alloc_warm(8);
                    flag && (short_circuit = alloc_scratch(16));
                    scratch char* maybe_unknown = short_circuit;

                    char* sequenced = alloc_warm(8);
                    (flag, sequenced = alloc_cold(16));
                    warm char* sequence_mismatch = sequenced;

                    char* divergent = alloc_warm(8);
                    if (flag) divergent = alloc_scratch(8);
                    else divergent = alloc_cold(8);
                    scratch char* divergent_unknown = divergent;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertEquals(1, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(result.diagnostics.single().message.contains("'sequence_mismatch' is declared warm"))
        assertEquals(snapshot.text.indexOf("sequence_mismatch"), result.diagnostics.single().sourceSpan.startOffset)
        assertFalse(result.diagnostics.any { it.message.contains("agreed_domain") })
        assertFalse(result.diagnostics.any { it.message.contains("maybe_unknown") })
        assertFalse(result.diagnostics.any { it.message.contains("divergent_unknown") })
    }

    @Test
    fun allocationFlowPropagatesIndependentUnsequencedCallArgumentWrites() {
        val snapshot = sources.open(
            SourceId.named("allocation-call-argument-flow.cp"),
            """
                void consume(char* value);
                int main(void) {
                    char* changed_in_argument = alloc_warm(8);
                    consume((changed_in_argument = alloc_scratch(16)));
                    scratch char* no_stale_claim = changed_in_argument;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(
            AllocationIntent.SCRATCH,
            result.symbols.single { it.name == "no_stale_claim" }.knownProvenance
        )
    }

    @Test
    fun allocationFlowJoinsDistinctWritesAcrossUnsequencedCallArguments() {
        val snapshot = sources.open(
            SourceId.named("allocation-call-argument-join.cp"),
            """
                void consume(char* left, char* right);
                int main(void) {
                    char* left = alloc_warm(8);
                    char* right = alloc_warm(8);
                    consume((left = alloc_scratch(16)), (right = alloc_cold(16)));
                    scratch char* after_left = left;
                    cold char* after_right = right;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(AllocationIntent.SCRATCH, result.symbols.single { it.name == "after_left" }.knownProvenance)
        assertEquals(AllocationIntent.COLD, result.symbols.single { it.name == "after_right" }.knownProvenance)
    }

    @Test
    fun allocationFlowInvalidatesConflictingWritesAcrossUnsequencedCallArguments() {
        val snapshot = sources.open(
            SourceId.named("allocation-call-argument-conflict.cp"),
            """
                void consume(char* left, char* right);
                int main(void) {
                    char* value = alloc_warm(8);
                    consume((value = alloc_scratch(16)), (value = alloc_hot(16)));
                    scratch char* after_conflict = value;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertEquals(1, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(
            result.diagnostics.single().message.contains("unsequenced conflicting allocation writes"),
            result.diagnostics.toString()
        )
        assertEquals(
            AllocationIntent.NONE,
            result.symbols.single { it.name == "after_conflict" }.knownProvenance,
            "conflicting unsequenced writes must not invent a final allocation domain"
        )
    }

    @Test
    fun allocationFlowInvalidatesProvenanceAfterCompoundAssignmentsAndUpdates() {
        val snapshot = sources.open(
            SourceId.named("allocation-compound-update-flow.cp"),
            """
                int main(void) {
                    char* compound = alloc_warm(8);
                    compound += 1;
                    scratch char* after_compound = compound;

                    char* prefix = alloc_cold(8);
                    ++prefix;
                    warm char* after_prefix = prefix;

                    char* postfix = alloc_hot(8);
                    postfix--;
                    cold char* after_postfix = postfix;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        listOf("after_compound", "after_prefix", "after_postfix").forEach { name ->
            assertEquals(
                AllocationIntent.NONE,
                result.symbols.single { it.name == name }.knownProvenance,
                "provenance should be unknown after updating $name's source pointer"
            )
        }
    }

    @Test
    fun allocationFlowDoesNotAssumeInitializerElementEvaluationOrder() {
        val snapshot = sources.open(
            SourceId.named("allocation-initializer-order.cp"),
            """
                int main(void) {
                    char* value = alloc_warm(8);
                    char* values[2] = {
                        (value = alloc_scratch(8)),
                        (value = alloc_hot(8))
                    };
                    scratch char* after_initializer = value;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertEquals(1, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(
            result.diagnostics.single().message.contains("unsequenced conflicting allocation writes"),
            result.diagnostics.toString()
        )
        assertEquals(
            AllocationIntent.NONE,
            result.symbols.single { it.name == "after_initializer" }.knownProvenance,
            "initializer elements must not be treated as a deterministic left-to-right sequence"
        )
    }

    @Test
    fun allocationFlowDoesNotAssumeAssignmentOrSubscriptOperandOrder() {
        val snapshot = sources.open(
            SourceId.named("allocation-unsequenced-operands.cp"),
            """
                int main(void) {
                    char* value = alloc_warm(8);
                    char* target = NULL;
                    target = (value = alloc_scratch(8));
                    scratch char* after_assignment = value;

                    char* values[2] = { NULL, NULL };
                    values[(value = alloc_cold(8), 0)] = alloc_hot(8);
                    scratch char* after_subscript = value;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertEquals(1, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(result.diagnostics.single().message.contains("'after_subscript' is declared scratch"))
        listOf("after_assignment", "after_subscript").forEach { name ->
            val expected = if (name == "after_assignment") AllocationIntent.SCRATCH else AllocationIntent.COLD
            assertEquals(expected, result.symbols.single { it.name == name }.knownProvenance)
        }
    }

    @Test
    fun allocationFlowKeepsUnknownExpressionWrappersConservative() {
        val snapshot = sources.open(
            SourceId.named("allocation-unknown-expression-wrapper.cp"),
            """
                struct pair_t { char* first; char* second; };
                int main(void) {
                    char* value = alloc_warm(8);
                    struct pair_t pair = (struct pair_t){
                        (value = alloc_scratch(8)),
                        (value = alloc_hot(8))
                    };
                    scratch char* after_pair = value;
                    (void)pair;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        assertTrue(
            ast.root.descendantsAndSelf().any { it.syntaxKind == "compound_literal_expression" },
            "the regression must exercise the previously generic expression wrapper"
        )

        val result = TreeSitterAllocationIntentAnalyzer().analyze(ast)

        assertEquals(1, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(
            result.diagnostics.single().message.contains("unsequenced conflicting allocation writes"),
            result.diagnostics.toString()
        )
        assertEquals(
            AllocationIntent.NONE,
            result.symbols.single { it.name == "after_pair" }.knownProvenance
        )
    }

    @Test
    fun allocationFlowTreatsInlineAssemblyAsAnUnknownMemoryWrite() {
        val snapshot = sources.open(
            SourceId.named("allocation-inline-assembly.cp"),
            """
                int main(void) {
                    char* value = alloc_warm(8);
                    __asm__ volatile ("" : "+r"(value));
                    scratch char* after_asm = value;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        assertTrue(
            ast.root.descendantsAndSelf().any { it.syntaxKind == "gnu_asm_expression" },
            "the regression must exercise the GNU inline-assembly expression"
        )

        val result = TreeSitterAllocationIntentAnalyzer().analyze(ast)

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(
            AllocationIntent.NONE,
            result.symbols.single { it.name == "after_asm" }.knownProvenance
        )
    }

    @Test
    fun allocationFlowDoesNotEvaluateUnevaluatedSizeofOperands() {
        val snapshot = sources.open(
            SourceId.named("allocation-unevaluated-operands.cp"),
            """
                #include <stddef.h>
                int main(void) {
                    char* value = alloc_warm(8);
                    size_t size = sizeof(value = alloc_scratch(16));
                    scratch char* after_sizeof = value;
                    (void)size;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertEquals(1, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(result.diagnostics.single().message.contains("'after_sizeof' is declared scratch"))
        assertEquals(snapshot.text.indexOf("after_sizeof"), result.diagnostics.single().sourceSpan.startOffset)
    }

    @Test
    fun allocationFlowDoesNotEvaluateGenericControllingExpression() {
        val snapshot = sources.open(
            SourceId.named("allocation-generic-controlling-expression.cp"),
            """
                int main(void) {
                    char* value = alloc_warm(8);
                    int selected = _Generic(
                        (value = alloc_scratch(16)),
                        char*: 1,
                        default: 0
                    );
                    scratch char* after_generic = value;
                    (void)selected;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertEquals(1, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(result.diagnostics.single().message.contains("'after_generic' is declared scratch"))
        assertEquals(snapshot.text.indexOf("after_generic"), result.diagnostics.single().sourceSpan.startOffset)
    }

    @Test
    fun allocationFlowPreservesSwitchConditionEffectsInsideCases() {
        val snapshot = sources.open(
            SourceId.named("allocation-switch-condition.cp"),
            """
                int main(int selector) {
                    char* value = alloc_warm(8);
                    switch (selector = (value = alloc_scratch(16), selector)) {
                        case 1:
                            scratch char* in_case = value;
                            (void)in_case;
                            break;
                        default:
                            break;
                    }
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(
            AllocationIntent.SCRATCH,
            result.symbols.single { it.name == "in_case" }.knownProvenance,
            result.symbols.toString()
        )
    }

    @Test
    fun allocationFlowInvalidatesProvenanceForUnannotatedPointerOutputCalls() {
        val snapshot = sources.open(
            SourceId.named("allocation-unannotated-pointer-output.cp"),
            """
                void replace_pointer(char **out);
                int main(void) {
                    char* value = alloc_warm(8);
                    replace_pointer(&value);
                    scratch char* after_known_unannotated_write = value;

                    char* external_value = alloc_cold(8);
                    external_call(&external_value);
                    hot char* after_unknown_write = external_value;

                    char* loop_value = alloc_warm(8);
                    while (external_condition()) {
                        external_call(&loop_value);
                    }
                    cold char* after_loop_write = loop_value;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        listOf("after_known_unannotated_write", "after_unknown_write", "after_loop_write").forEach { name ->
            assertEquals(
                AllocationIntent.NONE,
                result.symbols.single { it.name == name }.knownProvenance,
                "pointer output through $name should invalidate allocator provenance"
            )
        }
    }

    @Test
    fun allocationFlowInvalidatesOuterPointerProvenanceAfterLoopWrites() {
        val snapshot = sources.open(
            SourceId.named("allocation-loop-flow.cp"),
            """
                int main(int flag) {
                    char* while_value = alloc_warm(8);
                    while (flag) while_value = alloc_cold(8);
                    cold char* after_while = while_value;

                    char* for_value = alloc_warm(8);
                    for (int index = 0; index < 1; index++) {
                        for_value = alloc_hot(8);
                    }
                    hot char* after_for = for_value;

                    char* do_value = alloc_warm(8);
                    do { do_value = alloc_scratch(8); } while (flag);
                    scratch char* after_do = do_value;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertTrue(result.symbols.any { it.name == "after_while" && it.knownProvenance == AllocationIntent.NONE })
        assertTrue(result.symbols.any { it.name == "after_for" && it.knownProvenance == AllocationIntent.NONE })
        assertTrue(result.symbols.any { it.name == "after_do" && it.knownProvenance == AllocationIntent.SCRATCH })
    }

    @Test
    fun allocationFlowConvergesLoopProvenanceWhenAllExitsAgree() {
        val snapshot = sources.open(
            SourceId.named("allocation-loop-fixed-point.cp"),
            """
                int main(int flag) {
                    char* while_value = alloc_warm(8);
                    while (flag) while_value = alloc_warm(8);
                    warm char* after_while = while_value;

                    char* for_value = alloc_cold(8);
                    for (int index = 0; flag; index++) for_value = alloc_cold(8);
                    cold char* after_for = for_value;

                    char* do_value = alloc_hot(8);
                    do { do_value = alloc_scratch(8); } while (flag);
                    scratch char* after_do = do_value;

                    char* divergent = alloc_warm(8);
                    while (flag) divergent = alloc_cold(8);
                    char* after_divergent = divergent;

                    char* early_exit = alloc_warm(8);
                    while (flag) { early_exit = alloc_cold(8); break; }
                    char* after_early_exit = early_exit;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(AllocationIntent.WARM, result.symbols.single { it.name == "after_while" }.knownProvenance)
        assertEquals(AllocationIntent.COLD, result.symbols.single { it.name == "after_for" }.knownProvenance)
        assertEquals(AllocationIntent.SCRATCH, result.symbols.single { it.name == "after_do" }.knownProvenance)
        assertEquals(AllocationIntent.NONE, result.symbols.single { it.name == "after_divergent" }.knownProvenance)
        assertEquals(AllocationIntent.NONE, result.symbols.single { it.name == "after_early_exit" }.knownProvenance)
    }

    @Test
    fun allocationFlowInvalidatesOuterPointerProvenanceAfterSwitchWrites() {
        val snapshot = sources.open(
            SourceId.named("allocation-switch-flow.cp"),
            """
                int replace_with_scratch(owned scratch char** out) {
                    *out = alloc_scratch(8);
                    return 0;
                }

                int main(int selector) {
                    char* value = alloc_warm(8);
                    switch (selector) {
                        case 0: replace_with_scratch(&value); break;
                        case 1: value = alloc_cold(8); break;
                        case 2: { warm char* case_local = value; break; }
                        default: break;
                    }
                    scratch char* after_switch = value;
                    return 0;
                }
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val result = TreeSitterAllocationIntentAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertTrue(result.symbols.any { it.name == "case_local" && it.knownProvenance == AllocationIntent.WARM })
        assertEquals(
            AllocationIntent.NONE,
            result.symbols.single { it.name == "after_switch" }.knownProvenance
        )
    }

    @Test
    fun astLoweringPipelineReparsesBetweenChangedPassesAndStopsOnFailures() {
        val initial = sources.open(SourceId.named("lowering-pipeline.cp"), "int original;\n")
        val initialAst = CPlusAstAdapter().adapt(backend.parse(initial))
        val initialMapped = MappedText.identity(initial.sourceFile)
        val passOrder = mutableListOf<String>()
        var revision = 0
        val pipeline = CPlusAstLoweringPipeline()

        val result = pipeline.run(
            initialAst,
            initialMapped,
            listOf(
                CPlusAstLoweringStep("first") { ast, source ->
                    assertEquals(source.text, ast.source.text)
                    passOrder += "first"
                    CPlusLoweringResult(
                        MappedText.generated("int intermediate;\n", source.originAt(0)),
                        emptyList()
                    )
                },
                CPlusAstLoweringStep("second") { ast, source ->
                    assertEquals("int intermediate;\n", ast.source.text)
                    assertEquals(source.text, ast.source.text)
                    passOrder += "second"
                    CPlusLoweringResult(
                        MappedText.generated("int final_value;\n", source.originAt(0)),
                        emptyList()
                    )
                }
            )
        ) { source ->
            backend.parse(sources.open(SourceId.named("lowering-pipeline-${revision++}.cp"), source.text))
        }

        assertTrue(result.successful, "${result.parserDiagnostics}; ${result.loweringDiagnostics}")
        assertEquals(listOf("first", "second"), passOrder)
        assertEquals(listOf("first", "second"), result.trace.map { it.stepId })
        assertTrue(result.trace.all { it.sourceChanged })
        assertEquals("int final_value;\n", result.source.text)
        assertEquals(result.source.text, result.ast.source.text)
        assertEquals(initial.id.value, result.source.originAt(0)?.file?.name)

        val failureOrder = mutableListOf<String>()
        val failed = pipeline.run(
            initialAst,
            initialMapped,
            listOf(
                CPlusAstLoweringStep("stop") { ast, source ->
                    failureOrder += "stop"
                    CPlusLoweringResult(
                        source,
                        listOf(CPlusLoweringDiagnostic("TEST_FAILURE", "intentional", ast.root.span))
                    )
                },
                CPlusAstLoweringStep("must-not-run") { _, source ->
                    failureOrder += "must-not-run"
                    CPlusLoweringResult(source, emptyList())
                }
            )
        ) { source -> backend.parse(sources.open(SourceId.named("lowering-pipeline-failure.cp"), source.text)) }

        assertFalse(failed.successful)
        assertEquals(listOf("stop"), failureOrder)
        assertEquals(listOf("stop"), failed.trace.map { it.stepId })

        val parserFailureOrder = mutableListOf<String>()
        val parseFailed = pipeline.run(
            initialAst,
            initialMapped,
            listOf(
                CPlusAstLoweringStep("emit-invalid-syntax") { _, source ->
                    parserFailureOrder += "emit-invalid-syntax"
                    CPlusLoweringResult(
                        MappedText.generated("int broken(;\n", source.originAt(0)),
                        emptyList()
                    )
                },
                CPlusAstLoweringStep("must-not-run-after-parse-error") { _, source ->
                    parserFailureOrder += "must-not-run-after-parse-error"
                    CPlusLoweringResult(source, emptyList())
                }
            )
        ) { source ->
            backend.parse(sources.open(SourceId.named("lowering-pipeline-invalid-${revision++}.cp"), source.text))
        }

        assertFalse(parseFailed.successful)
        assertTrue(parseFailed.parserDiagnostics.isNotEmpty())
        assertEquals(listOf("emit-invalid-syntax"), parserFailureOrder)
        assertEquals(initial.id.value, parseFailed.parserDiagnostics.first().span.file)
    }

    @Test
    fun shadowParsingReportsDifferencesWithoutReplacingTheLegacyResult() {
        val snapshot = sources.open(
            SourceId.named("shadow.cp"),
            "typedef struct item_t { pub int get(borrowed *self); } item_t;\ncomptime int answer = 42;"
        )
        val legacy = LegacyCPlusParserBackend()
        val report = CPlusParserShadowRunner(legacy, backend).parse(snapshot)

        assertEquals(cplus.ParserBackendId.LEGACY, report.authoritative.backend)
        assertEquals(legacy.parse(snapshot).root, report.authoritative.root)
        assertFalse(report.coverageMatches)
        assertTrue(report.shadowOnlyNodeCount > 0)
        assertTrue(report.authoritativeOnlyNodeSamples.any { it.contains("legacy_text_region") })
        assertTrue(report.errorLocationsAlign, "both parsers accept the shared fixture without errors")
        assertTrue(
            report.recognizedConstructsMatch,
            "legacy=${report.authoritativeRecognizedConstructs}; tree-sitter=${report.shadowRecognizedConstructs}; diagnostics=${report.shadow.diagnostics}; root=${report.shadow.root}"
        )
    }

    @Test
    fun shadowDiagnosticLocationMetricRequiresOneToOneErrorOverlap() {
        val source = sources.open(SourceId.named("shadow-diagnostics.cp"), "abcdefghijklmnopqrstuvwxyz012345")
        fun backend(
            id: ParserBackendId,
            errorRanges: List<IntRange>,
            diagnosticCode: String = "synthetic.error"
        ) = object : cplus.CPlusParserBackend {
            override val id: ParserBackendId = id

            override fun parse(source: cplus.SourceSnapshot, options: cplus.CPlusParseOptions): CPlusParseResult {
                val root = CPlusSyntaxNode("translation_unit", source.sourceFile.span(0, source.text.length))
                return CPlusParseResult(
                    source,
                    id,
                    ParseCoverage.PARTIAL,
                    root,
                    errorRanges.map { range ->
                        cplus.ParserDiagnostic(
                            diagnosticCode,
                            "synthetic parser error",
                            cplus.ParserDiagnosticSeverity.ERROR,
                            source.sourceFile.span(range.first, range.last + 1)
                        )
                    }
                )
            }
        }

        val disjoint = CPlusParserShadowRunner(
            backend(ParserBackendId.LEGACY, listOf(0..0)),
            backend(ParserBackendId.TREE_SITTER, listOf(2..2))
        )
            .parse(source)
        assertFalse(disjoint.errorLocationsAlign)
        assertFalse(disjoint.diagnosticsMatch)

        // Every error overlaps at least one error from the other backend, but the overlap graph
        // has a 2-to-1 component and therefore cannot represent one-to-one diagnostic parity.
        val ambiguous = CPlusParserShadowRunner(
            backend(ParserBackendId.LEGACY, listOf(1..2, 8..9, 20..30)),
            backend(ParserBackendId.TREE_SITTER, listOf(0..10, 21..22, 28..29))
        ).parse(source)
        assertFalse(ambiguous.errorLocationsAlign)

        val sameSpansDifferentCodes = CPlusParserShadowRunner(
            backend(ParserBackendId.LEGACY, listOf(4..5), "legacy.syntax"),
            backend(ParserBackendId.TREE_SITTER, listOf(4..5), "TS_ERROR_NODE")
        ).parse(source)
        assertTrue(sameSpansDifferentCodes.errorLocationsAlign)
        assertTrue(sameSpansDifferentCodes.errorSpansMatch)
        assertFalse(sameSpansDifferentCodes.diagnosticsMatch)
    }

    @Test
    fun shadowDifferentialGateMatchesAllSharedTopLevelCPlusConstructs() {
        val cases = listOf(
            "comptime int @answer = 42;" to "comptime_value_declaration",
            "comptime flags -lm;" to "comptime_flags",
            "comptime import \"constants.cp\";" to "comptime_import",
            "@import(\"fixture.c\");" to "c_import_expression",
            "comptime function @increment(int value) { return value + 1; }" to "comptime_function_declaration",
            "comptime type @box(type T) { return @code{ struct box_t { T value; }; }; }" to "comptime_function_declaration",
            "comptime function @name(type T) { return @code{ struct generated_@typename(T) { T value; }; }; }" to "comptime_function_declaration",
            "@type @box(@type T) { return struct { T value; }; }" to "comptime_type_declaration",
            "@fn @map(@type T) { return @fn int mapped(void) { return 1; }; }" to "comptime_function_declaration",
            "comptime typedef dynamic_list(int) int_list_t;" to "comptime_invocation",
            "typedef @dynamic_list(int) int_list_t;" to "comptime_invocation",
            "@map(int, float);" to "comptime_invocation",
            "@test \"answer is materialized\" { @assert(1); }" to "test_declaration"
        )
        val runner = CPlusParserShadowRunner(LegacyCPlusParserBackend(), backend)

        cases.forEachIndexed { index, (text, expectedKind) ->
            val snapshot = sources.open(SourceId.named("shadow-shared-$index.cp"), text)
            val report = runner.parse(snapshot)

            assertTrue(
                report.recognizedConstructsMatch,
                "$expectedKind: legacy=${report.authoritativeRecognizedConstructs}; tree-sitter=${report.shadowRecognizedConstructs}; diagnostics=${report.shadow.diagnostics}; root=${report.shadow.root}"
            )
            assertEquals(listOf(expectedKind), report.authoritativeRecognizedConstructs.map { it.substringBefore('@') })
            assertEquals(cplus.ParserBackendId.LEGACY, report.authoritative.backend)
        }
    }

    @Test
    fun shadowParsersBothRejectMalformedSharedComptimeConstructsAtTheirSourceLocation() {
        val cases = listOf(
            "comptime function @bad(type) { return 1; }" to "comptime function",
            "comptime flags ;" to "comptime flags",
            "comptime import ;" to "comptime import",
            "comptime {" to "comptime",
            "@test \"incomplete fixture\" {" to "@test",
            "@type @bad(@type T) { return struct { T value; };" to "@type"
        )
        val runner = CPlusParserShadowRunner(LegacyCPlusParserBackend(), backend)

        cases.forEachIndexed { index, (text, marker) ->
            val source = sources.open(SourceId.named("shadow-malformed-$index.cp"), text)
            val report = runner.parse(source)
            val legacyDiagnostics = report.authoritative.diagnostics.filter {
                it.severity == cplus.ParserDiagnosticSeverity.ERROR
            }
            val treeDiagnostics = report.shadow.diagnostics.filter {
                it.severity == cplus.ParserDiagnosticSeverity.ERROR
            }
            val treeRecovery = report.shadow.root.descendants().filter { it.isError || it.isMissing }.toList()
            val malformedStart = text.indexOf(marker)
            assertTrue(legacyDiagnostics.isNotEmpty(), "$marker was accepted by legacy parser: $report")
            if (marker == "comptime flags" || marker == "comptime import") {
                assertTrue(
                    treeDiagnostics.isNotEmpty() && treeRecovery.isEmpty(),
                    "$marker should produce a precise C-plus validation diagnostic without parser recovery: diagnostics=$treeDiagnostics; recovery=$treeRecovery"
                )
            } else {
                assertTrue(
                    treeDiagnostics.isNotEmpty() && treeRecovery.isNotEmpty(),
                    "$marker was not rejected with Tree-sitter recovery diagnostics: diagnostics=$treeDiagnostics; recovery=$treeRecovery"
                )
            }
            assertTrue(
                legacyDiagnostics.all { it.span.file == source.id.value && it.span.startOffset >= malformedStart },
                "$marker legacy diagnostic escaped its source/construct: $legacyDiagnostics"
            )
            assertTrue(
                treeDiagnostics.all { it.span.file == source.id.value && it.span.startOffset >= malformedStart },
                "$marker Tree-sitter diagnostic escaped its source/construct: $treeDiagnostics"
            )
            assertTrue(
                report.errorLocationsAlign,
                "$marker error locations do not align across backends: legacy=$legacyDiagnostics; tree-sitter=$treeDiagnostics"
            )
            if (marker in setOf("comptime function", "comptime flags", "comptime import", "comptime", "@test", "@type")) {
                assertTrue(
                    report.errorSpansMatch,
                    "$marker token span differs across backends: legacy=$legacyDiagnostics; tree-sitter=$treeDiagnostics"
                )
            }
            if (marker in setOf("comptime function", "comptime flags", "comptime import", "comptime", "@test", "@type")) {
                assertTrue(
                    report.diagnosticsMatch,
                    "$marker diagnostic signature differs across backends: legacy=$legacyDiagnostics; tree-sitter=$treeDiagnostics"
                )
            }
        }
    }

    @Test
    fun shadowParsersPreserveMultipleIndependentComptimeDiagnostics() {
        val text = """
            comptime flags ;
            comptime import ;
            comptime int @answer = 42;
            comptime {
        """.trimIndent()
        val source = sources.open(SourceId.named("shadow-multiple-errors.cp"), text)
        val report = CPlusParserShadowRunner(LegacyCPlusParserBackend(), backend).parse(source)
        val legacyErrors = report.authoritative.diagnostics.filter {
            it.severity == cplus.ParserDiagnosticSeverity.ERROR
        }
        val treeErrors = report.shadow.diagnostics.filter {
            it.severity == cplus.ParserDiagnosticSeverity.ERROR
        }

        assertEquals(3, legacyErrors.size, "legacy diagnostics=$legacyErrors")
        assertEquals(3, treeErrors.size, "Tree-sitter diagnostics=${report.shadow.diagnostics}; root=${report.shadow.root}")
        assertTrue(report.errorLocationsAlign, "diagnostic ranges do not pair: legacy=$legacyErrors; tree=$treeErrors")
        assertTrue(report.errorSpansMatch, "diagnostic spans differ: legacy=$legacyErrors; tree=$treeErrors")
        assertTrue(report.diagnosticsMatch, "diagnostic signatures differ: legacy=$legacyErrors; tree=$treeErrors")
        assertEquals(
            listOf("CPLUS_COMPTIME_FLAGS_EMPTY", "CPLUS_COMPTIME_IMPORT_PATH_MISSING", "CPLUS_COMPTIME_BLOCK_UNCLOSED"),
            legacyErrors.map { it.code }
        )
        val survivingValue = report.authoritative.root.descendants().single {
            it.kind == "comptime_value_declaration"
        }
        assertEquals("comptime int @answer = 42;", text.substring(
            survivingValue.span.startOffset,
            survivingValue.span.endOffset
        ).trim())
    }

    @Test
    fun shadowDiagnosticRecoveryIsStableAcrossMalformedConstructOrderings() {
        val constructs = listOf(
            "comptime flags ;" to "CPLUS_COMPTIME_FLAGS_EMPTY",
            "comptime import ;" to "CPLUS_COMPTIME_IMPORT_PATH_MISSING",
            "comptime int @answer = 42;" to null
        )
        val orderings = listOf(
            listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
            listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0)
        )
        val runner = CPlusParserShadowRunner(LegacyCPlusParserBackend(), backend)

        orderings.forEachIndexed { index, ordering ->
            val lines = ordering.map(constructs::get).map { it.first } + "comptime {"
            val text = lines.joinToString("\n")
            val source = sources.open(SourceId.named("shadow-recovery-order-$index.cp"), text)
            val report = runner.parse(source)
            val expectedCodes = ordering.mapNotNull { constructs[it].second } + "CPLUS_COMPTIME_BLOCK_UNCLOSED"
            val legacyErrors = report.authoritative.diagnostics.filter {
                it.severity == cplus.ParserDiagnosticSeverity.ERROR
            }

            assertEquals(expectedCodes, legacyErrors.map { it.code }, "ordering=$ordering diagnostics=$legacyErrors")
            assertEquals(3, report.shadow.diagnostics.count { it.severity == cplus.ParserDiagnosticSeverity.ERROR })
            assertTrue(report.errorSpansMatch, "ordering=$ordering spans differ: $report")
            assertTrue(report.diagnosticsMatch, "ordering=$ordering signatures differ: $report")
            val value = report.authoritative.root.descendants().single { it.kind == "comptime_value_declaration" }
            assertEquals(text.indexOf("comptime int @answer = 42;"), value.span.startOffset)
            assertEquals("comptime int @answer = 42;", text.substring(value.span.startOffset, value.span.endOffset).trim())
        }
    }

    @Test
    fun shadowParserMatchesLegacyRecognizedConstructsAcrossRepositoryCPlusFiles() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib")) }
            ?: error("could not locate repository stdlib from ${Path.of("").toAbsolutePath()}")
        val sourceRoots = listOf("stdlib", "examples")
            .map(repository::resolve)
            .filter(Files::isDirectory)
        val modules = sourceRoots.flatMap { root ->
            Files.walk(root).use { paths ->
                paths.filter { it.toString().endsWith(".cp") || it.toString().endsWith(".c+") }
                    .sorted()
                    .toList()
            }
        }
        assertTrue(modules.isNotEmpty(), "repository C-plus corpus must not be empty")
        val legacy = LegacyCPlusParserBackend()
        val runner = CPlusParserShadowRunner(legacy, backend)

        modules.forEach { path ->
            val relativePath = repository.relativize(path).toString()
            val text = Files.readString(path)
            val snapshot = sources.open(SourceId.named(relativePath), text)
            val report = runner.parse(snapshot)
            val unmatchedContext = if (report.recognizedConstructsMatch) "" else buildString {
                fun visit(node: CPlusSyntaxNode, offset: Int, chain: List<String>) {
                    if (offset !in node.span.startOffset until node.span.endOffset) return
                    val next = chain + "${node.kind}@${node.span.startOffset}:${node.span.endOffset}"
                    if (node.children.isEmpty()) append("\n  ").append(next.joinToString(" -> "))
                    else node.children.forEach { visit(it, offset, next) }
                }
                report.authoritativeRecognizedConstructs.forEach { construct ->
                    val offset = construct.substringAfter('@').substringBefore(':').toIntOrNull()
                    if (offset != null) visit(report.shadow.root, offset, emptyList())
                }
            }

            assertEquals(legacy.parse(snapshot).root, report.authoritative.root, "$relativePath authoritative result changed")
            val authoritativeErrors = report.authoritative.diagnostics.filter {
                it.severity == cplus.ParserDiagnosticSeverity.ERROR
            }
            val shadowErrors = report.shadow.diagnostics.filter {
                it.severity == cplus.ParserDiagnosticSeverity.ERROR
            }
            val recoveryNodes = report.shadow.root.descendants()
                .filter { it.isError || it.isMissing }
                .map { "${it.kind}@${it.span.startOffset}:${it.span.endOffset}" }
                .toList()
            assertTrue(authoritativeErrors.isEmpty(), "$relativePath legacy errors: $authoritativeErrors")
            assertTrue(shadowErrors.isEmpty(), "$relativePath Tree-sitter errors: $shadowErrors")
            assertTrue(recoveryNodes.isEmpty(), "$relativePath Tree-sitter recovery nodes: $recoveryNodes")
            assertTrue(report.errorLocationsAlign, "$relativePath parser error locations diverged")
            assertTrue(report.errorSpansMatch, "$relativePath parser error spans diverged")
            assertTrue(
                report.recognizedConstructsMatch,
                "$relativePath: legacy=${report.authoritativeRecognizedConstructs}; tree-sitter=${report.shadowRecognizedConstructs}; diagnostics=${report.shadow.diagnostics}; CST paths=$unmatchedContext"
            )
        }
    }

    @Test
    fun parsesLegacyAtTypeGeneratorDeclarationsAndIndexesTheirSymbols() {
        val text = "@type @box(@type T) { return struct { T value; }; }"
        val snapshot = sources.open(SourceId.named("legacy-type-generator.cp"), text)
        val parsed = backend.parse(snapshot)
        val ast = CPlusAstAdapter().adapt(parsed)
        val index = CPlusComptimeIndexer().index(ast)

        assertTrue(parsed.diagnostics.isEmpty(), "diagnostics=${parsed.diagnostics}; tree=${parsed.root}")
        assertTrue(ast.root.descendantsAndSelf().any { it.kind == cplus.CPlusAstKind.TYPE_PARAMETER }, ast.dump())
        assertEquals("cplus_legacy_type_generator", parsed.root.descendants().single { it.kind == "cplus_legacy_type_generator" }.kind)
        assertEquals("box", index.constructs.single().symbol)
        assertEquals(listOf("T"), index.constructs.single().parameters.map { it.name })
        assertEquals(listOf("type"), index.constructs.single().parameters.map { it.typeText })
        assertTrue(index.constructs.single().parameters.single().genericType)
        assertTrue(index.constructs.single().activeThisPass, "the top-level generator is registered this pass")
    }

    @Test
    fun materializesLegacyFunctionGeneratorFromRepositoryGenericListExample() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("examples")) }
            ?: error("could not locate repository examples from ${Path.of("").toAbsolutePath()}")
        val source = sources.open(
            SourceId.named(repository.resolve("examples/generic_list.cp").toString()),
            Files.readString(repository.resolve("examples/generic_list.cp"))
        )

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.cSource!!.text
        assertTrue("int list_map(" in generated, generated.takeLast(8_000))
        assertTrue("named_value_list_t *input" in generated, generated.takeLast(8_000))
        assertTrue("int(*callback)(CPLUS_BORROWED named_value_t *item, size_t index)" in generated, generated.takeLast(8_000))
        assertFalse("@InputList" in generated || "@OutputList" in generated || "@T" in generated || "@R" in generated, generated.takeLast(8_000))
        assertC11Syntax(generated, "examples/generic_list.cp")
        val compiler = cplusTestCompilers(listOf("cc", "gcc", "clang")).firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val (exitCode, stdout) = compileAndCaptureC(compiler, generated)
        assertEquals(0, exitCode, stdout)
        assertTrue("mapped rank=1" in stdout, stdout)
    }

    @Test
    fun comptimeScalarDifferentialMatchesLegacyAndAstFrontends() {
        val text = """
            comptime int @answer = 40 + 2;
            int main(void) { return comptime answer - 42; }
        """.trimIndent()
        val source = sources.open(SourceId.named("comptime-scalar-differential.cp"), text)
        val report = TreeSitterCPlusDifferentialRunner(backend, sources).compare(source)

        assertTrue(report.successful, report.toString())
        assertTrue(report.normalizedTokensMatch, report.tokenDifference.orEmpty())
        assertTrue(report.compilerOptionsMatch, report.toString())
        assertTrue(report.sourceMapCoverageMatch, report.toString())
        compileAndRunC(report.legacy.code, compilerOptions = report.legacy.compilerOptions)
        compileAndRunC(
            report.treeSitter.transcodedSource!!.code,
            compilerOptions = report.treeSitter.compilerOptions
        )
    }

    @Test
    fun comptimeStringDifferentialMatchesLegacyAndAstFrontends() {
        val text = """
            comptime string @generated_name() { return "generated_" + "answer"; }
            int main(void) {
                const char* value = comptime generated_name();
                return value[0] == 'g' && value[10] == 'a' && value[16] == '\0' ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("comptime-string-differential.cp"), text)
        val report = TreeSitterCPlusDifferentialRunner(backend, sources).compare(source)

        assertTrue(report.successful, report.toString())
        assertTrue(report.normalizedTokensMatch, report.tokenDifference.orEmpty())
        assertTrue(report.compilerOptionsMatch, report.toString())
        assertTrue(report.sourceMapCoverageMatch, report.toString())
        compileAndRunC(report.legacy.code, compilerOptions = report.legacy.compilerOptions)
        compileAndRunC(
            report.treeSitter.transcodedSource!!.code,
            compilerOptions = report.treeSitter.compilerOptions
        )
    }

    @Test
    fun prototypeErasesUnusedUniqueEntityGeneratorsBeforeCEmission() {
        val text = """
            comptime type @unused_box(type T) {
                return @code { struct unused_box { T value; }; };
            }
            comptime function @unused_function() {
                return int generated_but_unused(void) { return 42; }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-unused-entities.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("unused comptime entities should lower to C")
        assertFalse("unused_box" in generated.code, generated.code)
        assertFalse("generated_but_unused" in generated.code, generated.code)
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val run = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        assertEquals(0, run.first, run.second)
    }

    @Test
    fun unusedDuplicateEntityGeneratorsStillProduceDuplicateDiagnostic() {
        val text = """
            comptime type @box(type T) { return @code { struct box { T value; }; }; }
            comptime type @box(type T) { return @code { struct box { T value; }; }; }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-duplicate-unused-generators.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertEquals("CPLUS_COMPTIME_DUPLICATE_GENERATOR", result.loweringDiagnostics.single().code)
    }

    @Test
    fun indexesLegacyFunctionGeneratorsAndTypeSpecializationsAsComptimeOnly() {
        val text = """
            @fn @map(@type T, @type R) {
                return @fn pub R mapper(borrowed @T* value) { return (R)*value; };
            }
            typedef @dynamic_list(int) int_list_t;
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("legacy-generic-forms.cp"), text)
        val parsed = backend.parse(snapshot)
        val ast = CPlusAstAdapter().adapt(parsed)
        val comptime = CPlusComptimeIndexer().index(ast)
        val runtime = CPlusSemanticAnalyzer().analyze(ast)

        assertTrue(parsed.diagnostics.isEmpty(), "diagnostics=${parsed.diagnostics}; tree=${parsed.root}")
        assertEquals(
            listOf("map", "dynamic_list"),
            comptime.constructs.map { it.symbol }
        )
        assertEquals(listOf("T", "R"), comptime.constructs.first().parameters.map { it.name })
        assertTrue(comptime.constructs.first().parameters.all { it.genericType })
        assertEquals(listOf("int"), comptime.constructs.last().argumentSpans.map {
            text.substring(it.startOffset, it.endOffset)
        })
        assertEquals("int_list_t", comptime.constructs.last().alias)
        assertTrue(comptime.constructs.all { it.activeThisPass })
        assertFalse(runtime.symbols.any { it.name in setOf("mapper", "int_list_t") }, runtime.symbols.toString())
        assertEquals(CPlusAstKind.COMPTIME_DECLARATION, ast.root.descendantsAndSelf()
            .single { it.syntaxKind == "cplus_legacy_function_generator" }.kind)
        assertEquals(CPlusAstKind.COMPTIME_INVOCATION, ast.root.descendantsAndSelf()
            .single { it.syntaxKind == "cplus_comptime_type_definition" }.kind)
    }

    @Test
    fun parsesComptimeIdentifierSplicesAsStableAstNodes() {
        val text = """
            comptime string @typename(type T) { return T.name; }
            comptime type @list(type T) {
                return @code { struct list_of_@typename(T) { T* items; }; };
            }
            comptime function @mapper(type T, type R) {
                return @code {
                    R mapper__@typename(T)__to__@typename(R)(T item) { return item; }
                };
            }
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("comptime-splices.cp"), text)

        val parsed = backend.parse(snapshot)
        val ast = CPlusAstAdapter().adapt(parsed)
        val spliceNodes = ast.root.descendantsAndSelf()
            .filter { it.kind == cplus.CPlusAstKind.INTERPOLATED_IDENTIFIER }
            .toList()
        val json = CPlusParseJson.encode(parsed)

        assertTrue(parsed.diagnostics.isEmpty(), "diagnostics=${parsed.diagnostics}; tree=${parsed.root}")
        assertTrue(spliceNodes.isNotEmpty(), "tree=${parsed.root}")
        assertTrue(spliceNodes.any { snapshot.text.substring(it.span.startOffset, it.span.endOffset).contains("__to__") })
        assertTrue(json.contains("\"kind\":\"interpolated_identifier\""), json)
        assertTrue(json.contains("\"syntaxKind\":\"cplus_interpolated_identifier\""), json)
    }

    @Test
    fun mapsUtf8TreeSitterOffsetsBackToUtf16SourceSpans() {
        val text = "// 🪐 C-plus source\ntypedef struct sample_t { pub int f(borrowed mut *self); } sample_t;"
        val snapshot = sources.open(SourceId.named("unicode.cp"), text)

        val result = backend.parse(snapshot)
        val method = result.root.descendants().single { it.kind == "cplus_method_definition" }

        assertEquals("pub int f(borrowed mut *self);", text.substring(method.span.startOffset, method.span.endOffset))
    }

    @Test
    fun incrementalSessionReusesTreeAcrossUnicodeEditsAndMatchesFreshParse() {
        val id = SourceId.named("incremental-unicode.cp")
        val originalText = """
            // 🪐 source offsets must remain exact
            typedef struct sample_t {
                int value;
                pub int read(borrowed *self) { return self->value; }
            } sample_t;
        """.trimIndent()
        val original = sources.open(id, originalText)
        val session = backend.openIncrementalSession(original)
        assertEquals(
            CPlusAstAdapter().adapt(backend.parse(original)).dump(),
            CPlusAstAdapter().adapt(session.current()).dump()
        )

        val editedText = originalText.replace("int value;", "long value;")
        val edited = sources.open(id, editedText)
        val incremental = session.update(edited)
        assertTrue(incremental.reusedPreviousTree)
        assertTrue(incremental.changedRanges.isNotEmpty())
        assertTrue(incremental.changedRanges.all { it.file == id.value })
        assertEquals(
            CPlusAstAdapter().adapt(backend.parse(edited)).dump(),
            CPlusAstAdapter().adapt(incremental.parseResult).dump()
        )

        val malformedText = editedText.replace("return self->value;", "return self->value")
        val malformed = sources.open(id, malformedText)
        val malformedIncremental = session.update(malformed)
        val malformedFresh = backend.parse(malformed)
        assertTrue(malformedIncremental.reusedPreviousTree)
        assertEquals(
            CPlusAstAdapter().adapt(malformedFresh).dump(),
            CPlusAstAdapter().adapt(malformedIncremental.parseResult).dump()
        )
        assertEquals(malformedFresh.diagnostics, malformedIncremental.parseResult.diagnostics)
    }

    @Test
    fun reportsRecoveredSyntaxWithSourceMappedSpans() {
        val snapshot = sources.open(
            SourceId.named("broken.cp"),
            "typedef struct broken_t { pub int method(borrowed mut *self { return 0; } } broken_t;"
        )

        val result = backend.parse(snapshot)

        assertEquals(ParseCoverage.PARTIAL, result.coverage)
        assertFalse(result.diagnostics.isEmpty())
        assertTrue(result.diagnostics.all { it.span.file == snapshot.id.value })
    }

    @Test
    fun serializesEditorParseResultsWithStableUtf16SpansAndEscapedStrings() {
        val text = "// 🪐 \"editor\"\nint value;"
        val snapshot = sources.open(SourceId.named("editor-\"quoted\".cp"), text)
        val result = backend.parse(snapshot)

        val json = CPlusParseJson.encode(result)

        assertTrue(json.startsWith("{\"schema\":\"cplus.parse.v1\""), json)
        assertTrue(json.contains("\"offsetEncoding\":\"utf16\""), json)
        assertTrue(json.contains("\"startOffset\":"), json)
        assertTrue(json.contains("\\\"quoted\\\""), json)
        assertTrue(json.contains("\"syntaxKind\":\"translation_unit\""), json)
        val nodeAvailable = runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false)
        if (nodeAvailable) {
            val process = ProcessBuilder(
                "node", "-e",
                "const j=JSON.parse(require('fs').readFileSync(0,'utf8'));if(j.schema!=='cplus.parse.v1'||j.ast.span.endOffset!==${text.length})process.exit(1)"
            ).start()
            process.outputStream.bufferedWriter().use { it.write(json) }
            val errors = process.errorStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor(), errors)
        }
    }

    @Test
    fun parserJsonKeepsRecoveredDiagnosticsValidAndIncludesTheirSpans() {
        val snapshot = sources.open(SourceId.named("broken-\"source\".cp"), "int main( { return 0; }")
        val result = backend.parse(snapshot, cplus.CPlusParseOptions(editorMode = true))
        val json = CPlusParseJson.encode(result)

        assertTrue(result.diagnostics.isNotEmpty(), result.toString())
        assertTrue(json.contains("\"diagnostics\":[{\"code\":"), json)
        assertTrue(json.contains("\"severity\":\"error\",\"span\":{"), json)
        val nodeAvailable = runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false)
        if (nodeAvailable) {
            val process = ProcessBuilder(
                "node", "-e",
                "const j=JSON.parse(require('fs').readFileSync(0,'utf8'));if(j.diagnostics.length===0||j.diagnostics[0].span.startLine!==1)process.exit(1)"
            ).start()
            process.outputStream.bufferedWriter().use { it.write(json) }
            val errors = process.errorStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor(), errors)
        }
    }

    @Test
    fun indexesMethodsAndResolvesExplicitInstanceAndStaticReceivers() {
        val snapshot = sources.open(
            SourceId.named("semantic.cp"),
            """
            typedef struct widget_t {
                pub int value;
                pub int read(borrowed mut *self, owned char* output);
                static pub widget_t *create(void);
            } widget_t;
            int main(void) {
                widget_t item;
                item.read();
                widget_t.create();
                return 0;
            }
            """.trimIndent()
        )

        val parsed = backend.parse(snapshot)
        val ast = CPlusAstAdapter().adapt(parsed)
        val index = CPlusSemanticAnalyzer().analyze(ast)

        assertEquals(1, index.symbols.count { it.kind == CPlusSymbolKind.STRUCT && it.name == "widget_t" })
        assertEquals(2, index.symbols.count { it.kind in setOf(CPlusSymbolKind.INSTANCE_METHOD, CPlusSymbolKind.STATIC_METHOD) })
        assertEquals(2, index.resolvedCalls.size, "both explicit instance and static calls should resolve")
        assertEquals(setOf(false, true), index.resolvedCalls.map { it.staticCall }.toSet())
        val read = index.symbols.single { it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "read" }
        assertEquals("pub", read.access)
        assertTrue(read.parameters.first().receiver)
        assertEquals("self", read.parameters.first().name)
        assertEquals(setOf("borrowed", "mut"), read.parameters.first().annotations)
        assertEquals(setOf("owned"), read.parameters[1].annotations)
    }

    @Test
    fun resolvesMethodsOnAnonymousStructTypedefs() {
        val snapshot = sources.open(
            SourceId.named("anonymous-method-type.cp"),
            """
            typedef struct {
                int value;
                pub int read(borrowed *self) { return self->value; }
            } widget_t;
            int main(void) { widget_t item; return item.read(); }
            """.trimIndent()
        )

        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val index = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(index.symbols.any { it.kind == CPlusSymbolKind.STRUCT && it.name == "widget_t" }, index.symbols.toString())
        assertTrue(index.symbols.any { it.kind == CPlusSymbolKind.FIELD && it.ownerType == "widget_t" && it.name == "value" })
        assertEquals(listOf("read"), index.resolvedCalls.map { it.methodName })
    }

    @Test
    fun infersSelfReceiverTypeForCallsInsideInstanceMethods() {
        val snapshot = sources.open(
            SourceId.named("self-receiver-resolution.cp"),
            """
                typedef struct counter_t {
                    pub int value(borrowed *self) { return 1; }
                    pub int read_again(borrowed *self) { return self->value(); }
                } counter_t;
            """.trimIndent()
        )
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val index = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        val call = index.resolvedCalls.single()
        assertEquals("counter_t", call.ownerType)
        assertEquals("value", call.methodName)
        assertFalse(call.staticCall)
        assertEquals("self", snapshot.text.substring(call.receiverSpan.startOffset, call.receiverSpan.endOffset))
    }

    @Test
    fun resolvesAndLowersExplicitAddressOfReceiverWithoutTakingItsAddressAgain() {
        val text = """
            typedef struct box_t {
                int value;
                pub int read(borrowed *self) { return self->value; }
            } box_t;
            int main(void) {
                box_t box = { 37 };
                return (&box).read() == 37 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("address-receiver.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        val semantics = CPlusSemanticAnalyzer().analyze(ast)

        assertEquals(1, semantics.resolvedCalls.size, ast.dump())
        val resolved = semantics.resolvedCalls.single()
        assertEquals("read", resolved.methodName)
        assertEquals("box_t", resolved.ownerType)
        assertTrue(resolved.receiverAlreadyPointer)
        val lowered = CPlusMethodCallLoweringPass().lower(ast, MappedText.identity(source.sourceFile), semantics)
        assertTrue(lowered.diagnostics.isEmpty(), lowered.diagnostics.toString())
        assertTrue("box__read((&box))" in lowered.source.text, lowered.source.text)
        assertFalse("box__read(&((&box))" in lowered.source.text, lowered.source.text)

        val pipeline = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(pipeline.successful, "parser=${pipeline.parserDiagnostics}; lower=${pipeline.loweringDiagnostics}")
        compileAndRunC(pipeline.cSource!!.text)
    }

    @Test
    fun resolvesTypeQualifiedInstanceCallsWhenTheReceiverIsExplicit() {
        val text = """
            typedef struct box_t {
                int value;
                pub void set(borrowed mut *self, int value) { self->value = value; }
                static pub int marker(void) { return 7; }
            } box_t;
            int main(void) {
                box_t box = { 0 };
                box_t.set(&box, 35);
                return box.value == 35 && box_t.marker() == 7 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("explicit-type-receiver.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        val semantics = CPlusSemanticAnalyzer().analyze(ast)

        assertTrue(semantics.diagnostics.isEmpty(), semantics.diagnostics.toString())
        val explicitInstance = semantics.resolvedCalls.single { it.methodName == "set" }
        assertFalse(explicitInstance.staticCall)
        assertTrue(explicitInstance.explicitReceiver)
        assertEquals("&box", text.substring(
            explicitInstance.receiverSpan.startOffset,
            explicitInstance.receiverSpan.endOffset
        ))
        assertTrue(semantics.resolvedCalls.single { it.methodName == "marker" }.staticCall)

        val pipeline = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(pipeline.successful, "parser=${pipeline.parserDiagnostics}; lower=${pipeline.loweringDiagnostics}")
        assertTrue("box__set(&box, 35)" in pipeline.cSource!!.text, pipeline.cSource!!.text)
        compileAndRunC(pipeline.cSource!!.text)
    }

    @Test
    fun lowersNestedMethodCallsByReplacingTheirOwningAstNodes() {
        val text = """
            typedef struct leaf_t {
                int value;
                pub int get(borrowed *self) { return self->value; }
            } leaf_t;
            typedef struct wrapper_t {
                int marker;
                pub int accept(borrowed *self, int value) { return value; }
            } wrapper_t;
            int main(void) {
                leaf_t leaf = { 7 };
                wrapper_t wrapper = { 0 };
                return wrapper.accept(leaf.get()) == 7 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("nested-method-calls.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        val semantics = CPlusSemanticAnalyzer().analyze(ast)
        assertEquals(setOf("accept", "get"), semantics.resolvedCalls.map { it.methodName }.toSet())

        val lowered = CPlusMethodCallLoweringPass().lower(ast, MappedText.identity(source.sourceFile), semantics)
        assertTrue(lowered.diagnostics.isEmpty(), lowered.diagnostics.toString())
        assertTrue(
            "wrapper__accept(&wrapper, leaf__get(&leaf))" in lowered.source.text,
            lowered.source.text
        )

        val pipeline = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(pipeline.successful, "parser=${pipeline.parserDiagnostics}; lower=${pipeline.loweringDiagnostics}")
        compileAndRunC(pipeline.cSource!!.text)
    }

    @Test
    fun resolvesReceiversThroughDeclaredStructFieldTypes() {
        val text = """
            typedef struct child_t {
                int marker;
                pub int value(borrowed *self) { return 9; }
            } child_t;
            typedef struct holder_t {
                child_t child;
                pub int read_child(borrowed *self) { return self->child.value(); }
            } holder_t;
            int main(void) {
                holder_t holder = {0};
                return holder.child.value() == 9 && holder.read_child() == 9 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("field-receiver-resolution.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        val semantics = CPlusSemanticAnalyzer().analyze(ast)

        assertEquals(3, semantics.resolvedCalls.size)
        assertEquals(2, semantics.resolvedCalls.count { it.ownerType == "child_t" && it.methodName == "value" })
        assertEquals(1, semantics.resolvedCalls.count { it.ownerType == "holder_t" && it.methodName == "read_child" })
        val pipeline = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(pipeline.successful, "parser=${pipeline.parserDiagnostics}; lower=${pipeline.loweringDiagnostics}")
        compileAndRunC(pipeline.cSource!!.text)
    }

    @Test
    fun externalCompilersReportGeneratedMethodErrorsAtTheOriginalCPlusLine() {
        val text = """
            typedef struct broken_t {
                pub int fail(borrowed *self) {
                    return missing_cplus_symbol;
                }
            } broken_t;
        """.trimIndent()
        val source = sources.open(SourceId.named("mapped-method-error.cp"), text)
        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}")
        val generated = result.transcodedSource ?: error("prototype did not create mapped compiler input")

        listOf("tcc", "gcc", "clang").forEach { compiler ->
            val available = runCatching {
                ProcessBuilder(compiler, "--version").redirectErrorStream(true).start().let { process ->
                    process.inputStream.bufferedReader().use { it.readText() }
                    process.waitFor() == 0
                }
            }.getOrDefault(false)
            if (!available) return@forEach

            val temporaryDirectory = Files.createTempDirectory("cplus-mapped-diagnostic")
            try {
                val sourceFile = temporaryDirectory.resolve("generated.c")
                val objectFile = temporaryDirectory.resolve("generated.o")
                Files.writeString(sourceFile, generated.code)
                val process = ProcessBuilder(
                    compiler, "-c", sourceFile.toString(), "-o", objectFile.toString()
                ).redirectErrorStream(true).start()
                val diagnostics = process.inputStream.bufferedReader().use { it.readText() }
                assertTrue(process.waitFor() != 0, "$compiler unexpectedly accepted invalid generated C")
                assertTrue(diagnostics.contains("mapped-method-error.cp"), "$compiler diagnostic lost .cp origin:\n$diagnostics")
                assertTrue(
                    Regex("mapped-method-error\\.cp(?::3(?::\\d+)?|\\(3(?:,\\d+)?\\))").containsMatchIn(diagnostics),
                    "$compiler diagnostic did not point at source line 3:\n$diagnostics"
                )
            } finally {
                deleteRecursively(temporaryDirectory)
            }
        }
    }

    @Test
    fun acceptedAstOutputCompilesAndRunsWithEveryAvailableHostCCompiler() {
        val text = """
            #include <stdio.h>
            typedef int error_t;
            @throws() pub error_t status(int value) { return value; }
            typedef struct counter_t {
                int value;
                pub void add(borrowed mut *self, int amount) { self->value += amount; }
            } counter_t;
            int main(void) {
                counter_t counter = {1};
                defer counter.value += 1;
                @try { counter.add(40); status(0); }
                @catch (error_t error) { return error; }
                return counter.value == 41 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("host-compiler-matrix.cp"), text)
        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val code = result.transcodedSource?.code ?: error("AST frontend did not emit C")
        val compilers = cplusTestCompilers(listOf("cc", "gcc", "clang", "tcc")).distinct().filter { compiler ->
            runCatching {
                ProcessBuilder(compiler, "--version").start().let { process ->
                    process.inputStream.use { it.readBytes() }
                    process.waitFor() == 0
                }
            }.getOrDefault(false)
        }
        if (compilers.isEmpty()) return

        compilers.forEach { compiler ->
            val run = compileAndCaptureC(compiler, code)
            assertEquals(0, run.first, "$compiler rejected or failed the representative AST output:\n${run.second}\n$code")
        }
    }

    @Test
    fun delegatesOrdinaryCTypeCheckingToTheSelectedCompiler() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val text = """
            int main(void) {
                int value = "not an integer";
                return value;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("ordinary-c-type-error.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))
        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}")
        val (status, output) = compileAndCaptureC(
            compiler,
            result.cSource?.text ?: error("ordinary C output is missing"),
            compilerOptions = listOf("-Werror"),
            expectCompileSuccess = false
        )
        assertTrue(status != 0, "$compiler unexpectedly accepted invalid ordinary C:\n$output")
        assertTrue(
            output.contains("not an integer") || output.contains("incompatible") || output.contains("conversion"),
            "$compiler did not report the delegated type error:\n$output"
        )
    }

    @Test
    fun reportsAndSelectsAstRuntimePassesForMigrationRollback() {
        val source = sources.open(
            SourceId.named("runtime-pass-selection.cp"),
            "int main(void) { return 0; }"
        )
        val defaultResult = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(defaultResult.successful, defaultResult.loweringDiagnostics.toString())
        assertEquals(
            listOf(
                "extract-throws",
                "lower-try-catch",
                "lower-defer",
                "validate-semantics",
                "lower-method-calls",
                "lower-struct-methods"
            ),
            defaultResult.runtimePasses
        )

        val selectedResult = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            passSelection = TreeSitterPassSelection(
                disabledRuntimePasses = setOf("lower-try-catch", "lower-defer")
            )
        ).transpile(source)
        assertTrue(selectedResult.successful, selectedResult.loweringDiagnostics.toString())
        assertFalse("lower-try-catch" in selectedResult.runtimePasses)
        assertFalse("lower-defer" in selectedResult.runtimePasses)
        assertTrue("lower-method-calls" in selectedResult.runtimePasses)
    }

    @Test
    fun astComptimeRollbackFailsClosedAtTheOriginalSourceSpan() {
        val text = "comptime int @answer = 42; int main(void) { return comptime answer; }"
        val source = sources.open(SourceId.named("rollback-comptime.cp"), text)
        val report = TreeSitterCPlusDifferentialRunner(backend, sources).compareComptimeRollback(source)

        assertTrue(report.selectorContractHolds, report.toString())
        assertFalse(report.treeSitter.successful)
        assertNull(report.treeSitter.cSource)
        val boundary = report.treeSitterBoundary ?: error("missing AST comptime rollback boundary")
        assertEquals("cplus_comptime_declaration", boundary.syntaxKind)
        assertEquals(source.id.value, boundary.span.file)
        assertEquals(text.indexOf("comptime int"), boundary.span.startOffset)
    }

    @Test
    fun comptimeRollbackLeavesOrdinaryCPlusAvailable() {
        val text = "int main(void) { return 0; }"
        val source = sources.open(SourceId.named("rollback-ordinary.cp"), text)
        val report = TreeSitterCPlusDifferentialRunner(backend, sources).compareComptimeRollback(source)

        assertNull(report.legacyFailure, report.toString())
        assertNull(report.legacyFailureSpan)
        assertTrue(report.treeSitter.successful, report.toString())
        assertNull(report.treeSitterBoundary)
        assertTrue(report.treeSitter.cSource?.text?.contains("int main") == true)
    }

    @Test
    fun differentialRunnerProducesAStableOverlapReport() {
        val text = """
            #include <stdio.h>
            typedef struct counter_t {
                int value;
                pub int get(borrowed *self) { return self->value; }
            } counter_t;
            int main(void) {
                counter_t counter = {42};
                printf("%d\\n", counter.get());
                return counter.get() == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("differential-report.cp"), text)

        val report = TreeSitterCPlusDifferentialRunner(backend, sources).compare(source)

        assertTrue(report.treeSitter.successful, report.treeSitter.loweringDiagnostics.toString())
        assertTrue(report.normalizedTokensMatch, report.tokenDifference.orEmpty())
        assertTrue(report.compilerOptionsMatch)
        assertEquals(report.legacy.sourceOrder, report.treeSitter.sourceOrder)
        assertTrue(
            report.allocationDiagnosticsMatch,
            "legacy=${report.legacy.allocationAnalysis.diagnostics}; " +
                "tree-sitter=${report.treeSitter.allocationAnalysis.diagnostics}"
        )
        assertTrue(report.sourceMapCoverageMatch, report.toString())
        assertTrue(report.frontendPassesMatch)
        assertTrue(report.frontendPassChangesMatch)
        assertFalse(report.frontendPassOrderMatch)
        assertTrue(report.successful)
    }

    @Test
    fun importedModuleCompilerOptionsRetainLegacyDiscoveryOrder() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib")) }
            ?: error("could not locate repository stdlib from ${Path.of("").toAbsolutePath()}")
        val sourcePath = repository.resolve("stdlib/tests/http.cp").toRealPath()
        val stdlibRoot = repository.resolve("stdlib").toAbsolutePath().normalize()
        val source = sources.open(SourceId.named(sourcePath.toString()), Files.readString(sourcePath))

        val report = TreeSitterCPlusDifferentialRunner(backend, sources).compare(
            source,
            targetOs = "linux",
            importPaths = CPlusImportPaths(standardLibraryRoots = listOf(stdlibRoot))
        )

        assertTrue(report.treeSitter.successful, report.treeSitter.loweringDiagnostics.toString())
        assertTrue(
            report.compilerOptionsMatch,
            "legacy=${report.legacy.compilerOptions}; tree-sitter=${report.treeSitter.compilerOptions}"
        )
        assertEquals(listOf("-D_POSIX_C_SOURCE=200112L", "-lpthread"), report.treeSitter.compilerOptions)
    }

    @Test
    fun differentialRunnerComparesLegacyAndAstFixtureDiscovery() {
        val text = """
            @test "alpha" { @assert(1 == 1); @assertEquals(2, 2); }
            @test beta { @assert(3 == 3); }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("differential-tests.cp"), text)

        val report = TreeSitterCPlusDifferentialRunner(backend, sources).compareTests(source)

        assertTrue(report.treeSitter.successful, report.treeSitter.loweringDiagnostics.toString())
        assertTrue(report.astHarness != null)
        assertTrue(report.fixtureNamesMatch, report.toString())
        assertTrue(report.assertionCountsMatch, report.toString())
        assertTrue(report.harnessFixtureNamesMatch, report.toString())
        assertTrue(report.harnessAssertionCountsMatch, report.toString())
        assertTrue(report.harnessTokensMatch, report.harnessTokenDifference.orEmpty())
        assertTrue(report.assertionSourceLinesMatch, report.toString())
        assertTrue(report.compilerOptionsMatch, report.toString())
        assertTrue(report.sourceMapCoverageMatch, report.toString())
        assertTrue(report.successful, report.toString())
    }

    @Test
    fun differentialRunnerComparesAllocationDiagnosticsAtOriginalOrigins() {
        val text = """
            int main(void) {
                scratch char* source = alloc_scratch(8);
                warm char* mismatch = source;
                return mismatch == 0;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("differential-allocation.cp"), text)

        val report = TreeSitterCPlusDifferentialRunner(backend, sources).compare(source)

        assertTrue(report.treeSitter.successful, report.treeSitter.loweringDiagnostics.toString())
        assertTrue(report.normalizedTokensMatch, report.tokenDifference.orEmpty())
        assertTrue(
            report.allocationDiagnosticsMatch,
            "legacy=${report.legacy.allocationAnalysis.diagnostics}; " +
                "tree-sitter=${report.treeSitter.allocationAnalysis.diagnostics}"
        )
        assertEquals(1, report.legacy.allocationAnalysis.diagnostics.size)
        assertEquals(1, report.treeSitter.allocationAnalysis.diagnostics.size)
        assertEquals(
            text.indexOf("mismatch"),
            report.treeSitter.allocationAnalysis.diagnostics.single().sourceSpan.startOffset
        )
    }

    @Test
    fun perPassRollbackProbeDisablesEveryKnownFrontendPassInBothSelectors() {
        val source = sources.open(
            SourceId.named("per-pass-rollback.cp"),
            "int main(void) { return 0; }"
        )
        val runner = TreeSitterCPlusDifferentialRunner(backend, sources)

        CPlusLegacyPassSelection.KNOWN_PASSES.forEach { passId ->
            val report = runner.comparePassRollback(source, passId)
            assertTrue(report.selectorContractHolds, "$passId: $report")
            assertTrue(report.treeSitter.successful, "$passId: ${report.treeSitter.loweringDiagnostics}")
        }
    }

    @Test
    fun astMethodCallRollbackFailsClosedAtTheOriginalCPlusBoundary() {
        val text = """
            typedef struct counter_t {
                int value;
                pub int get(borrowed *self) { return self->value; }
            } counter_t;
            int main(void) {
                counter_t counter = {42};
                return counter.get() == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("rollback-method-call.cp"), text)
        val report = TreeSitterCPlusDifferentialRunner(backend, sources).comparePassRollback(
            source,
            CPlusLegacyPassSelection.LOWER_METHOD_CALLS
        )

        assertTrue(report.legacyPassDisabled)
        assertTrue(report.treeSitterPassDisabled)
        assertTrue(report.legacy != null, report.legacyFailure.orEmpty())
        assertFalse(report.treeSitter.successful)
        assertTrue(report.treeSitter.transcodedSource == null)
        assertTrue(
            report.treeSitter.unsupportedNodes.any { it.span.startLine == 7 },
            "expected the disabled receiver call to remain mapped at line 7: ${report.treeSitter.unsupportedNodes}"
        )
    }

    @Test
    fun astRuntimeRollbackBoundariesFailClosedForDeferTryAndStructMethods() {
        data class RollbackCase(val passId: String, val source: String, val kind: String)
        val cases = listOf(
            RollbackCase(
                CPlusLegacyPassSelection.EXTRACT_THROWS,
                """
                    @throws() pub int checked(void) { return 0; }
                    int main(void) { return checked(); }
                """.trimIndent(),
                "cplus_throws_annotation"
            ),
            RollbackCase(
                CPlusLegacyPassSelection.LOWER_DEFER,
                """
                    int main(void) {
                        defer puts("cleanup");
                        return 0;
                    }
                """.trimIndent(),
                "cplus_defer_statement"
            ),
            RollbackCase(
                CPlusLegacyPassSelection.LOWER_TRY_CATCH,
                """
                    typedef int error_t;
                    int main(void) {
                        @try { puts("body"); }
                        @catch (error_t error) { puts("caught"); }
                        return 0;
                    }
                """.trimIndent(),
                "cplus_try_statement"
            ),
            RollbackCase(
                CPlusLegacyPassSelection.LOWER_STRUCT_METHODS,
                """
                    typedef struct counter_t {
                        int value;
                        pub int get(borrowed *self) { return self->value; }
                    } counter_t;
                    int main(void) { return 0; }
                """.trimIndent(),
                "cplus_method_definition"
            )
        )

        cases.forEachIndexed { index, case ->
            val source = sources.open(SourceId.named("rollback-runtime-$index.cp"), case.source)
            val report = TreeSitterCPlusDifferentialRunner(backend, sources).comparePassRollback(
                source,
                case.passId
            )
            assertTrue(report.legacy != null, "${case.passId}: ${report.legacyFailure}")
            assertFalse(report.treeSitter.successful, "${case.passId} rollback unexpectedly succeeded")
            assertTrue(report.treeSitter.transcodedSource == null)
            assertTrue(
                report.treeSitter.unsupportedNodes.any { it.syntaxKind == case.kind },
                "${case.passId} did not retain ${case.kind}: ${report.treeSitter.unsupportedNodes}"
            )
        }
    }

    @Test
    fun exercisedPassParityReportsFullOutputAndRollbackForMethodLowering() {
        val text = """
            typedef struct counter_t {
                int value;
                pub int get(borrowed *self) { return self->value; }
            } counter_t;
            int main(void) {
                counter_t counter = {42};
                return counter.get() == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("pass-parity-method.cp"), text)

        val report = TreeSitterCPlusDifferentialRunner(backend, sources).comparePass(
            source,
            CPlusLegacyPassSelection.LOWER_METHOD_CALLS
        )

        assertTrue(report.passExercised, report.toString())
        assertTrue(report.full.successful, report.full.toString())
        assertTrue(report.rollback.selectorContractHolds, report.rollback.toString())
        assertFalse(report.rollback.treeSitter.successful)
        assertTrue(report.successful, report.toString())
    }

    @Test
    fun exercisedPassParityReportsTheRemainingRuntimeLowerers() {
        data class Case(val passId: String, val source: String)
        val cases = listOf(
            Case(
                CPlusLegacyPassSelection.EXTRACT_THROWS,
                """
                    typedef int error_t;
                    @throws() pub error_t checked(void) { return 0; }
                    int main(void) { return checked(); }
                """.trimIndent()
            ),
            Case(
                CPlusLegacyPassSelection.LOWER_DEFER,
                """
                    #include <stdio.h>
                    int main(void) {
                        defer puts("cleanup");
                        return 0;
                    }
                """.trimIndent()
            ),
            Case(
                CPlusLegacyPassSelection.LOWER_STRUCT_METHODS,
                """
                    typedef struct counter_t {
                        int value;
                        pub int get(borrowed *self) { return self->value; }
                    } counter_t;
                    int main(void) { return 0; }
                """.trimIndent()
            )
        )

        cases.forEachIndexed { index, case ->
            val source = sources.open(SourceId.named("pass-parity-runtime-$index.cp"), case.source)
            val report = TreeSitterCPlusDifferentialRunner(backend, sources).comparePass(source, case.passId)
            assertTrue(report.successful, "${case.passId}: $report")
        }
    }

    @Test
    fun passSubstitutionComparesEachIndependentRuntimeLowererWithDependencies() {
        data class Case(val passId: String, val source: String)
        val cases = listOf(
            Case(
                CPlusLegacyPassSelection.EXTRACT_THROWS,
                """
                    typedef int error_t;
                    @throws() pub error_t checked(void) { return 0; }
                    int main(void) { return checked(); }
                """.trimIndent()
            ),
            Case(
                CPlusLegacyPassSelection.EXTRACT_THROWS,
                """
                    typedef int error_t;
                    @throws(error) pub int create(borrowed mut error_t* error) {
                        *error = 0;
                        return 7;
                    }
                    int main(void) {
                        error_t error = 1;
                        return create(&error) == 7 && error == 0 ? 0 : 1;
                    }
                """.trimIndent()
            ),
            Case(
                CPlusLegacyPassSelection.LOWER_DEFER,
                """
                    #include <stdio.h>
                    int main(void) { defer puts("cleanup"); return 0; }
                """.trimIndent()
            ),
            Case(
                CPlusLegacyPassSelection.LOWER_DEFER,
                """
                    #include <stdio.h>
                    int main(void) {
                        if (1) {
                            defer puts("early cleanup");
                            return 0;
                        }
                        return 1;
                    }
                """.trimIndent()
            ),
            Case(
                CPlusLegacyPassSelection.LOWER_STRUCT_METHODS,
                """
                    typedef struct counter_t {
                        int value;
                        pub int get(borrowed *self) { return self->value; }
                    } counter_t;
                    int main(void) { return 0; }
                """.trimIndent()
            ),
            Case(
                CPlusLegacyPassSelection.LOWER_STRUCT_METHODS,
                """
                    typedef struct counter_t {
                        static pub int make(void) { return 42; }
                    } counter_t;
                    int main(void) { return 0; }
                """.trimIndent()
            ),
            Case(
                CPlusLegacyPassSelection.LOWER_METHOD_CALLS,
                """
                    typedef struct counter_t {
                        int value;
                        pub int get(borrowed *self) { return self->value; }
                    } counter_t;
                    int main(void) {
                        counter_t counter = {42};
                        return counter.get() == 42 ? 0 : 1;
                    }
                """.trimIndent()
            ),
            Case(
                CPlusLegacyPassSelection.LOWER_METHOD_CALLS,
                """
                    typedef struct counter_t {
                        int value;
                        pub int get(borrowed *self) { return self->value; }
                    } counter_t;
                    int main(void) {
                        counter_t counter = {42};
                        counter_t* pointer = &counter;
                        return pointer->get() == 42 ? 0 : 1;
                    }
                """.trimIndent()
            ),
            Case(
                CPlusLegacyPassSelection.LOWER_TRY_CATCH,
                """
                    #include <stdio.h>
                    typedef int error_t;
                    int main(void) {
                        @try { puts("body"); }
                        @catch (error_t error) { puts("caught"); }
                        return 0;
                    }
                """.trimIndent()
            ),
            Case(
                CPlusLegacyPassSelection.LOWER_TRY_CATCH,
                """
                    typedef int error_t;
                    enum { ERROR_BAD = 1, ERROR_IO = 2 };
                    @throws() pub error_t check(error_t code) { return code; }
                    int main(void) {
                        int value = 0;
                        @try {
                            @try { check(ERROR_IO); }
                            @catch (ERROR_BAD, error_t nested) { value = nested; }
                            value = 9;
                        }
                        @catch (ERROR_IO, error_t outer) { value = outer; }
                        @catch (error_t error) { return 1; }
                        return value == ERROR_IO ? 0 : 2;
                    }
                """.trimIndent()
            ),
            Case(
                CPlusLegacyPassSelection.LOWER_TRY_CATCH,
                """
                    #include <stdio.h>
                    typedef int error_t;
                    int main(void) {
                        @try { puts("first"); }
                        @catch (error_t error) { puts("first catch"); }
                        @try { puts("second"); }
                        @catch (error_t error) { puts("second catch"); }
                        return 0;
                    }
                """.trimIndent()
            )
        )

        cases.forEach { case ->
            val source = sources.open(SourceId.named("pass-substitution-${case.passId}.cp"), case.source)
            val report = TreeSitterCPlusDifferentialRunner(backend, sources)
                .comparePassSubstitution(source, case.passId)
            assertTrue(report.successful, "${case.passId}: $report")
            assertTrue(case.passId in report.enabledPasses)
            assertTrue(report.targetChangedBoth, report.toString())
            compileAndRunC(report.legacy!!.code)
            compileAndRunC(report.treeSitter.transcodedSource!!.code)
        }
    }

    @Test
    fun tryCatchPassParityMatchesTheLegacyGeneratedShape() {
        val text = """
            #include <stdio.h>
            typedef int error_t;
            int main(void) {
                @try { puts("body"); }
                @catch (error_t error) { puts("caught"); }
                return 0;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("pass-parity-try.cp"), text)

        val report = TreeSitterCPlusDifferentialRunner(backend, sources).comparePass(
            source,
            CPlusLegacyPassSelection.LOWER_TRY_CATCH
        )

        assertTrue(report.passExercised, report.toString())
        assertTrue(report.full.frontendPassChangesMatch, report.full.toString())
        assertTrue(report.full.normalizedTokensMatch, report.full.toString())
        assertTrue(report.full.sourceMapCoverageMatch, report.full.toString())
        assertEquals(null, report.full.tokenDifference)
        compileAndRunC(report.full.legacy.code)
        compileAndRunC(report.full.treeSitter.transcodedSource!!.code)
        assertTrue(report.rollback.selectorContractHolds, report.rollback.toString())
        assertTrue(report.successful, report.toString())
    }

    @Test
    fun astTestExtractionRollbackFailsClosedAtTheFixtureDeclaration() {
        val text = """
            @test "rollback fixture" { @assert(1 == 1); }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("rollback-test-extraction.cp"), text)
        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            passSelection = TreeSitterPassSelection(extractTests = false)
        ).transpile(source)

        assertFalse(result.successful)
        assertNull(result.transcodedSource)
        assertTrue(
            result.unsupportedNodes.any {
                it.syntaxKind == "cplus_test_declaration" && it.span.startLine == 1
            },
            "test declaration was not retained as a mapped rollback boundary: ${result.unsupportedNodes}"
        )
    }

    @Test
    fun testExtractionSubstitutionIsolatedFromRuntimeLowerers() {
        val text = """
            #include <stdio.h>
            int main(void) { return 0; }
            @test "extraction only" {
                int value = 41;
                value += 1;
                @assertEquals(42, value);
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("test-extraction-substitution.cp"), text)
        val report = TreeSitterCPlusDifferentialRunner(backend, sources)
            .compareTestExtractionSubstitution(source)

        assertTrue(report.successful, report.toString() + "\n" + report.harnessTokenDifference.orEmpty())
        assertEquals(emptyList<String>(), report.legacy.source.frontendPasses)
        assertEquals(emptyList<String>(), report.treeSitter.runtimePasses)
        assertEquals(listOf("extraction only"), report.legacy.testNames)
        assertEquals(listOf("extraction only"), report.treeSitter.testFixtures.map { it.name })
        assertEquals(listOf(1), report.legacy.fixtures.map { it.assertionCount })
        assertEquals(listOf(1), report.treeSitter.testFixtures.map { it.assertions.size })
        assertTrue(report.harnessTokensMatch, report.harnessTokenDifference.orEmpty())
        assertTrue(report.assertionSourceLinesMatch, report.toString())

        val rollback = TreeSitterCPlusDifferentialRunner(backend, sources)
            .compareTestExtractionRollback(source)
        assertTrue(rollback.selectorContractHolds, rollback.toString())
        assertEquals(3, rollback.legacyFailureLine)
        assertNull(rollback.treeSitter.transcodedSource)
        assertTrue(
            rollback.treeSitter.unsupportedNodes.any {
                it.syntaxKind == "cplus_test_declaration" && it.span.startLine == 3
            },
            "AST extraction rollback lost the fixture boundary: ${rollback.treeSitter.unsupportedNodes}"
        )
    }

    @Test
    fun testExtractionOnlySubstitutionAllowsSourcesWithoutFixtures() {
        val text = "int main(void) { return 0; }"
        val source = sources.open(SourceId.named("test-extraction-no-fixtures.cp"), text)
        val report = TreeSitterCPlusDifferentialRunner(backend, sources)
            .compareTestExtractionOnlySubstitution(source)

        assertTrue(report.successful, report.toString())
        assertTrue(report.legacy.testNames.isEmpty())
        assertTrue(report.treeSitter.testFixtures.isEmpty())
        assertTrue(report.fixtureNamesMatch)
        assertTrue(report.assertionCountsMatch)
        assertTrue(report.assertionSourceLinesMatch)
    }

    @Test
    fun allocationValidationRollbackDisablesAstAllocationDiagnostics() {
        val text = """
            int main(void) {
                scratch char* source = alloc_scratch(8);
                warm char* mismatch = source;
                return mismatch == 0;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("rollback-allocation-validation.cp"), text)
        val report = TreeSitterCPlusDifferentialRunner(backend, sources).comparePassRollback(
            source,
            CPlusLegacyPassSelection.VALIDATE_SEMANTICS
        )

        assertTrue(report.selectorContractHolds, report.toString())
        assertTrue(report.treeSitter.successful, report.treeSitter.loweringDiagnostics.toString())
        assertTrue(report.legacy?.allocationAnalysis?.diagnostics?.isEmpty() == true)
        assertTrue(report.treeSitter.allocationAnalysis.diagnostics.isEmpty())
    }

    @Test
    fun semanticPassParityComparesAllocationDiagnosticsAndRollback() {
        val text = """
            int main(void) {
                scratch char* source = alloc_scratch(8);
                warm char* mismatch = source;
                return mismatch == 0;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("semantic-pass-parity.cp"), text)

        val report = TreeSitterCPlusDifferentialRunner(backend, sources).compareSemanticPass(
            source,
            CPlusLegacyPassSelection.VALIDATE_SEMANTICS
        )

        assertTrue(report.diagnosticsMatch, report.toString())
        assertTrue(report.full.successful, report.full.toString())
        assertTrue(report.rollback.selectorContractHolds, report.rollback.toString())
        assertTrue(report.successful, report.toString())
    }

    @Test
    fun semanticPassSubstitutionComparesValidationWithoutRequiringSourceChanges() {
        val text = """
            int main(void) {
                scratch char* source = 0;
                warm char* mismatch = source;
                return mismatch != 0;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("semantic-pass-substitution.cp"), text)

        val report = TreeSitterCPlusDifferentialRunner(backend, sources)
            .compareSemanticPassSubstitution(source)

        assertTrue(report.successful, report.toString())
        assertEquals(setOf(CPlusLegacyPassSelection.VALIDATE_SEMANTICS), report.enabledPasses)
        assertTrue(report.diagnosticsMatch, report.toString())
        assertTrue(report.normalizedTokensMatch, report.tokenDifference.orEmpty())
        compileAndRunC(report.legacy!!.code)
        compileAndRunC(report.treeSitter.transcodedSource!!.code)
    }

    @Test
    fun semanticPassSubstitutionCoversAllocationFlowJoinsAndLoops() {
        val common = """
            #include <stddef.h>
            void* alloc_scratch(size_t size) { return 0; }
            void* alloc_hot(size_t size) { return 0; }
            void* alloc_warm(size_t size) { return 0; }
        """.trimIndent()
        val cases = listOf(
            """
                $common
                int main(void) {
                    int flag = 0;
                    scratch char* scratch_source = alloc_scratch(8);
                    char* merged = alloc_warm(8);
                    if (flag) merged = scratch_source;
                    else merged = alloc_scratch(16);
                    warm char* mismatch = merged;
                    return mismatch != 0;
                }
            """.trimIndent(),
            """
                $common
                int main(void) {
                    int flag = 0;
                    char* value = alloc_warm(8);
                    while (flag) value = alloc_warm(16);
                    warm char* stable = value;
                    return stable != 0;
                }
            """.trimIndent(),
            """
                $common
                int main(void) {
                    int flag = 0;
                    char* value = alloc_hot(8);
                    do value = alloc_scratch(16); while (flag);
                    warm char* mismatch = value;
                    return mismatch != 0;
                }
            """.trimIndent()
        )

        cases.forEachIndexed { index, text ->
            val source = sources.open(SourceId.named("semantic-flow-substitution-$index.cp"), text)
            val report = TreeSitterCPlusDifferentialRunner(backend, sources)
                .compareSemanticPassSubstitution(source)
            assertTrue(report.successful, "case $index: $report")
            assertTrue(report.diagnosticsMatch, report.toString())
            compileAndRunC(report.legacy!!.code)
            compileAndRunC(report.treeSitter.transcodedSource!!.code)
        }
    }

    @Test
    fun legacyAllocationFlowReportsKnownStaleProvenanceForIndependentCallWrites() {
        val text = """
            #include <stddef.h>
            void* alloc_scratch(size_t size) { return 0; }
            void* alloc_hot(size_t size) { return 0; }
            void* alloc_warm(size_t size) { return 0; }
            void consume(char* left, char* right) { (void)left; (void)right; }
            int main(void) {
                char* left = alloc_warm(8);
                char* right = alloc_warm(8);
                consume((left = alloc_scratch(16)), (right = alloc_hot(16)));
                scratch char* after_left = left;
                hot char* after_right = right;
                return 0;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("known-legacy-unsequenced-flow.cp"), text)

        val report = TreeSitterCPlusDifferentialRunner(backend, sources)
            .compareSemanticPassSubstitution(source)

        assertFalse(report.diagnosticsMatch, "legacy and AST results must expose the known correction")
        assertTrue(report.treeSitter.allocationAnalysis.diagnostics.isEmpty(), report.toString())
        assertTrue(
            report.legacy?.allocationAnalysis?.diagnostics.orEmpty()
                .any { "after_right" in it.message && "alloc_warm" in it.message },
            report.toString()
        )
        assertTrue(report.normalizedTokensMatch, report.tokenDifference.orEmpty())
        compileAndRunC(report.treeSitter.transcodedSource!!.code)
    }

    @Test
    fun legacyAndTreeSitterCompilerDiagnosticsAgreeAfterMethodLowering() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val name = "differential-method-error.cp"
        val text = """
            typedef struct broken_t {
                pub int fail(borrowed *self) {
                    return missing_cplus_symbol;
                }
            } broken_t;
        """.trimIndent()
        val source = sources.open(SourceId.named(name), text)
        val legacy = CPlusTranspiler().transpile(text, name)
        val experimental = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(
            experimental.successful,
            "parser=${experimental.parserDiagnostics}; lowering=${experimental.loweringDiagnostics}; unsupported=${experimental.unsupportedNodes}"
        )
        val experimentalC = experimental.transcodedSource ?: error("prototype has no mapped C output")
        val expectedLine = text.lines().indexOfFirst { "missing_cplus_symbol" in it } + 1
        val expectedColumn = text.lines()[expectedLine - 1].indexOf("missing_cplus_symbol") + 1
        val locationPattern = Regex("${Regex.escape(name)}:(\\d+):(\\d+)")

        val diagnostics = listOf(legacy, experimentalC).mapIndexed { index, generated ->
            val process = ProcessBuilder(compiler, "-std=c11", "-fsyntax-only", "-x", "c", "-")
                .redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(generated.code) }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertTrue(process.waitFor() != 0, "generated C $index unexpectedly compiled")
            assertTrue("missing_cplus_symbol" in output, "diagnostic lost the invalid identifier:\n$output")
            val location = locationPattern.find(output)
                ?: error("diagnostic did not report $name with a line and column:\n$output")
            val reportedLine = location.groupValues[1].toInt()
            assertEquals(expectedLine, reportedLine, "compiler diagnostic points to the wrong C-plus line:\n$output")
            val reportedColumn = location.groupValues[2].toInt()
            assertEquals(expectedColumn, reportedColumn, "compiler diagnostic points to the wrong C-plus column:\n$output")
            location.groupValues.drop(1) to output
        }
        assertEquals(
            diagnostics.first().first,
            diagnostics.last().first,
            "legacy and Tree-sitter diagnostic locations differ:\nlegacy=${diagnostics.first().second}\nTree-sitter=${diagnostics.last().second}"
                + "\nlegacy generated C:\n${legacy.code}\nTree-sitter generated C:\n${experimentalC.code}"
        )
    }

    @Test
    fun allAvailableHostCompilersPreserveMappedMethodDiagnosticLineAndColumn() {
        val text = """
            typedef struct broken_t {
                pub int fail(borrowed *self) {
                    return missing_cplus_symbol;
                }
            } broken_t;
        """.trimIndent()
        val name = "all-host-diagnostic-matrix.cp"
        val source = sources.open(SourceId.named(name), text)
        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}")
        val generated = result.transcodedSource ?: error("prototype did not create mapped compiler input")
        val expectedLine = text.lines().indexOfFirst { "missing_cplus_symbol" in it } + 1
        val expectedColumn = text.lines()[expectedLine - 1].indexOf("missing_cplus_symbol") + 1
        val compilers = cplusTestCompilers(listOf("cc", "gcc", "clang", "tcc")).distinct().filter { compiler ->
            runCatching { ProcessBuilder(compiler, "--version").start().waitFor() == 0 }.getOrDefault(false)
        }
        if (compilers.isEmpty()) return

        compilers.forEach { compiler ->
            val process = ProcessBuilder(compiler, "-std=c11", "-fsyntax-only", "-x", "c", "-")
                .redirectErrorStream(true)
                .start()
            process.outputStream.bufferedWriter().use { it.write(generated.code) }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertTrue(process.waitFor() != 0, "$compiler unexpectedly accepted invalid generated C")
            assertTrue("missing_cplus_symbol" in output, "$compiler lost the invalid identifier:\n$output")
            val location = Regex("${Regex.escape(name)}:(\\d+)(?::(\\d+))?").find(output)
                ?: error("$compiler did not report $name with a source line:\n$output")
            assertEquals(expectedLine, location.groupValues[1].toInt(), "$compiler reported the wrong C-plus line:\n$output")
            location.groupValues[2].takeIf(String::isNotEmpty)?.let { column ->
                assertEquals(expectedColumn, column.toInt(), "$compiler reported the wrong C-plus column:\n$output")
            }
        }
    }

    @Test
    fun keepsFunctionPointerTypedefsAndConditionalPlatformAttributesValidC() {
        val text = """
            #include <stddef.h>
            typedef int (*callback_t)(const char *value, size_t length);
            typedef const char *(*format_callback_t)(const char *format, ...);
            typedef int (*nested_callback_t)(
                int value,
                long (*transform)(const char *text, size_t length),
                void (*notify)(void *context)
            );
            typedef int (*(*factory_t)(int code))(const char *text);
            typedef int (*(*(*deep_factory_t)(int code))(const char *text))(long value);
            #if defined(_WIN32)
            __declspec(dllexport) int apply(callback_t callback, const char *value, size_t length);
            #else
            __attribute__((visibility("default"))) int apply(callback_t callback, const char *value, size_t length);
            #endif
            int apply(callback_t callback, const char *value, size_t length) {
                return callback(value, length);
            }
            static int count_chars(const char *value, size_t length) {
                (void)value;
                return (int)length;
            }
            static long transform_length(const char *text, size_t length) {
                (void)text;
                return (long)length;
            }
            static void notify_context(void *context) { (void)context; }
            static int first_character(const char *text) { return (unsigned char)text[0]; }
            static int (*make_callback(int code))(const char *text) {
                (void)code;
                return first_character;
            }
            static int invoke_nested(
                int value,
                long (*transform)(const char *text, size_t length),
                void (*notify)(void *context)
            ) {
                notify(NULL);
                return value + (int)transform("x", 1);
            }
            int main(void) {
                nested_callback_t nested = invoke_nested;
                factory_t factory = make_callback;
                return apply(count_chars, "ok", 2) != 2 ||
                    nested(5, transform_length, notify_context) != 6 ||
                    factory(0)("Z") != 'Z';
            }
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("c-dialect-compatibility.c"), text)

        val parsed = backend.parse(snapshot)

        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        assertTrue(ast.root.descendantsAndSelf().any { it.syntaxKind == "type_definition" })
        assertTrue(ast.root.descendantsAndSelf().any { it.syntaxKind == "preproc_if" })
        val semanticIndex = CPlusSemanticAnalyzer().analyze(ast)
        val callbackAlias = semanticIndex.symbols.firstOrNull {
            it.kind == CPlusSymbolKind.TYPE_ALIAS && it.name == "callback_t"
        }
        assertTrue(callbackAlias != null, "callback typedef not indexed; symbols=${semanticIndex.symbols}; ast=${ast.dump()}")
        val callbackType = callbackAlias?.functionType
        assertTrue(callbackType != null, "function-pointer typedef signature was not indexed")
        assertEquals("int", callbackType?.returnType)
        assertEquals(listOf("value", "length"), callbackType?.parameters?.map { it.name })
        assertEquals("char", callbackType?.parameters?.firstOrNull()?.typeName)
        assertEquals("const char *value", callbackType?.parameters?.firstOrNull()?.declarationText)
        assertEquals("size_t", callbackType?.parameters?.getOrNull(1)?.typeName)
        val formatType = semanticIndex.symbols.firstOrNull {
            it.kind == CPlusSymbolKind.TYPE_ALIAS && it.name == "format_callback_t"
        }?.functionType
        assertTrue(formatType != null, "variadic function pointer typedef signature was not indexed; symbols=${semanticIndex.symbols}; ast=${ast.dump()}")
        assertTrue(formatType?.variadic == true, formatType.toString())
        val formatAlias = semanticIndex.symbols.firstOrNull {
            it.kind == CPlusSymbolKind.TYPE_ALIAS && it.name == "format_callback_t"
        }
        assertEquals(
            "typedef const char *(*format_callback_t)(const char *format, ...);",
            formatAlias?.declarationText
        )
        val nestedType = semanticIndex.symbols.firstOrNull {
            it.kind == CPlusSymbolKind.TYPE_ALIAS && it.name == "nested_callback_t"
        }?.functionType
        assertTrue(nestedType != null, "nested function-pointer typedef was not indexed")
        val transformParameter = nestedType?.parameters?.getOrNull(1)
        assertEquals("transform", transformParameter?.name)
        assertEquals("long", transformParameter?.functionType?.returnType)
        assertEquals(listOf("text", "length"), transformParameter?.functionType?.parameters?.map { it.name })
        val notifyParameter = nestedType?.parameters?.getOrNull(2)
        assertEquals("notify", notifyParameter?.name)
        assertEquals("void", notifyParameter?.functionType?.returnType)
        assertEquals("void *context", notifyParameter?.functionType?.parameters?.singleOrNull()?.declarationText)
        val factoryType = semanticIndex.symbols.firstOrNull {
            it.kind == CPlusSymbolKind.TYPE_ALIAS && it.name == "factory_t"
        }?.functionType
        assertEquals(listOf("code"), factoryType?.parameters?.map { it.name })
        assertEquals("int", factoryType?.returnType)
        assertEquals("int", factoryType?.returnFunctionType?.returnType)
        assertEquals(listOf("text"), factoryType?.returnFunctionType?.parameters?.map { it.name })
        val deepFactoryType = semanticIndex.symbols.firstOrNull {
            it.kind == CPlusSymbolKind.TYPE_ALIAS && it.name == "deep_factory_t"
        }?.functionType
        assertEquals(listOf("code"), deepFactoryType?.parameters?.map { it.name })
        assertEquals(listOf("text"), deepFactoryType?.returnFunctionType?.parameters?.map { it.name })
        assertEquals(listOf("value"), deepFactoryType?.returnFunctionType?.returnFunctionType?.parameters?.map { it.name })
        compileAndRunC(text)
    }

    @Test
    fun lowersDeferredStatementsInReverseOrderAndKeepsMovedSourceOrigins() {
        val text = """
            void cleanup(int value);
            void work(void) {
                defer cleanup(1);
                defer { cleanup(2); cleanup(3); }
            }
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("defer.cp"), text)
        val ast = CPlusAstAdapter().adapt(backend.parse(snapshot))

        val lowered = CPlusDeferLoweringPass().lower(ast, MappedText.identity(snapshot.sourceFile))

        assertTrue(lowered.diagnostics.isEmpty(), lowered.diagnostics.toString())
        val deferredBlockPosition = lowered.source.text.indexOf("cleanup(2)")
        val deferredCallPosition = lowered.source.text.indexOf("cleanup(1)")
        assertTrue(deferredBlockPosition >= 0 && deferredBlockPosition < deferredCallPosition)
        assertFalse(Regex("\\bdefer\\b").containsMatchIn(lowered.source.text))
        val movedSourceOffset = text.indexOf("cleanup(1)")
        assertEquals(movedSourceOffset, lowered.source.originAt(deferredCallPosition)?.offset)
    }

    @Test
    fun movesNestedDeferToOwningFunctionEnd() {
        val text = """
            int trace;
            void mark(int value) { trace = trace * 10 + value; }
            void work(int ready) {
                if (ready) defer mark(1);
                defer { mark(2); mark(3); }
            }
            int main(void) {
                work(0);
                if (trace != 23) return 1;
                trace = 0;
                work(1);
                return trace != 231;
            }
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("conditional-defer.cp"), text)
        val ast = CPlusAstAdapter().adapt(backend.parse(snapshot))

        val lowered = CPlusDeferLoweringPass().lower(ast, MappedText.identity(snapshot.sourceFile))

        assertTrue(lowered.diagnostics.isEmpty(), lowered.diagnostics.toString())
        assertFalse(Regex("\\bdefer\\b").containsMatchIn(lowered.source.text))
        assertTrue(lowered.source.text.indexOf("mark(2)") < lowered.source.text.indexOf("mark(1)"), lowered.source.text)
        compileAndRunC(lowered.source.text)
    }

    @Test
    fun indexesThrowsMetadataAndTryCatchAstNodes() {
        val snapshot = sources.open(
            SourceId.named("throws.cp"),
            """
            @throws(error) pub int load(borrowed mut int *out_value, borrowed mut error_t *error);
            typedef struct file_t {
                @throws() pub error_t open(borrowed mut *self);
            } file_t;
            void run(void) {
                @try { load(&value); }
                @catch (ERROR_IO | ERROR_INVALID_ARGUMENT, error_t error) { report(error); }
                @catch (error_t error) { report(error); }
            }
            """.trimIndent()
        )

        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        val astNodes = ast.root.descendantsAndSelf().toList()
        assertTrue(astNodes.any { it.kind == cplus.CPlusAstKind.TRY })
        assertTrue(astNodes.any { it.kind == cplus.CPlusAstKind.CATCH })
        assertEquals(2, astNodes.count { it.kind == cplus.CPlusAstKind.THROWS_ANNOTATION })

        val index = CPlusSemanticAnalyzer().analyze(ast)
        val load = index.symbols.single { it.name == "load" }
        assertEquals("error", load.throwsParameter)
        assertEquals(CPlusThrowsConvention.ERROR_OUT_PARAMETER, load.throwsMetadata?.convention)
        assertEquals("error", load.throwsMetadata?.errorParameterName)
        assertEquals("error", load.parameters.last().name)
        val open = index.symbols.single { it.name == "open" }
        assertEquals("", open.throwsParameter)
        assertEquals(CPlusThrowsConvention.ERROR_RETURN, open.throwsMetadata?.convention)
        assertEquals(null, open.throwsMetadata?.errorParameterName)
        assertEquals(null, index.symbols.single { it.name == "run" }.throwsMetadata)
        assertEquals(listOf(listOf("ERROR_IO", "ERROR_INVALID_ARGUMENT"), null), index.catchBindings.map { it.codes })
        assertTrue(index.catchBindings.all { it.typeName == "error_t" && it.parameterName == "error" })
    }

    @Test
    fun resolvesReceiverMethodsThroughTypedefsAndTypedParametersWithoutLeakingShadowedLocals() {
        val snapshot = sources.open(
            SourceId.named("aliases.cp"),
            """
            typedef struct widget_t {
                pub int refresh(borrowed mut *self);
            } widget_alias_t;
            typedef widget_alias_t widget_handle_t;
            typedef const widget_handle_t const_widget_t;
            typedef widget_alias_t *widget_pointer_t;
            typedef widget_pointer_t *widget_pointer_pointer_t;
            typedef widget_alias_t **widget_direct_pointer_pointer_t;
            int use_widget(widget_handle_t *widget) {
                widget->refresh();
                const_widget_t const_widget;
                widget_pointer_t pointer;
                widget_pointer_pointer_t pointer_pointer;
                widget_direct_pointer_pointer_t direct_pointer_pointer;
                const_widget.refresh();
                pointer->refresh();
                pointer_pointer->refresh();
                (*pointer_pointer)->refresh();
                direct_pointer_pointer->refresh();
                {
                    int widget;
                    widget.refresh();
                }
                return 0;
            }
            """.trimIndent()
        )

        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val index = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(index.symbols.any { it.name == "widget_alias_t" }, index.symbols.toString())
        assertEquals("widget_t", index.symbols.single { it.name == "widget_alias_t" }.typeName, index.symbols.toString())
        assertEquals("widget_alias_t", index.symbols.single { it.name == "widget_handle_t" }.typeName, index.symbols.toString())
        assertEquals(1, index.symbols.single { it.name == "widget_pointer_pointer_t" }.pointerDepth)
        assertEquals(4, index.resolvedCalls.size, "single pointers and explicit dereferences resolve; double pointers do not")
        assertTrue(index.resolvedCalls.all { it.methodName == "refresh" })
    }

    @Test
    fun resolvesReceiverMethodsThroughQualifiedPointerAliases() {
        val text = """
            typedef struct widget_t {
                pub int ping(borrowed *self) { return 1; }
            } widget_t;
            typedef const volatile widget_t qualified_widget_t;
            typedef widget_t * restrict widget_pointer_t;
            typedef const widget_t * const const_widget_pointer_t;

            int use_qualified(
                qualified_widget_t value,
                widget_pointer_t pointer,
                const_widget_pointer_t const_pointer
            ) {
                value.ping();
                pointer->ping();
                const_pointer->ping();
                return 0;
            }

            int main(void) {
                qualified_widget_t value = { 0 };
                widget_t widget = { 0 };
                return use_qualified(value, &widget, &widget);
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("qualified-pointer-aliases.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val index = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(index.diagnostics.isEmpty(), index.diagnostics.toString())
        assertEquals(3, index.resolvedCalls.size, index.resolvedCalls.toString())
        assertTrue(index.resolvedCalls.all { it.ownerType == "widget_t" }, index.resolvedCalls.toString())

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(
            transpiled.successful,
            "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}"
        )
        compileAndRunC(transpiled.cSource?.text ?: error("qualified-alias output is missing"))
    }

    @Test
    fun retainsQualifiedMethodParameterDeclaratorsAndNestedCallbacks() {
        val text = """
            typedef struct widget_t {
                int value;
                pub const widget_t* transform(
                    borrowed mut *self,
                    borrowed const widget_t * restrict source,
                    borrowed int (*callback)(const widget_t *value)
                ) {
                    return callback(source) == self->value ? self : source;
                }
            } widget_t;

            int read_widget(const widget_t *value) {
                return value->value;
            }

            int main(void) {
                widget_t value = { 7 };
                return value.transform(&value, read_widget) == &value ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("qualified-method-parameters.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))
        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())

        val method = semantic.symbols.single {
            it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "transform"
        }
        assertEquals(listOf("self", "source", "callback"), method.parameters.map { it.name })
        assertEquals(
            "borrowed const widget_t * restrict source",
            method.parameters[1].declarationText
        )
        assertEquals(
            listOf(CPlusDeclaratorLayer.POINTER),
            method.parameters[1].declaratorLayers
        )
        val callback = method.parameters[2].functionType
        assertTrue(callback != null, method.parameters.toString())
        assertEquals("int", callback?.returnType)
        assertEquals(listOf("value"), callback?.parameters?.map { it.name })
        assertEquals("const widget_t *value", callback?.parameters?.single()?.declarationText)
        assertEquals(
            listOf(CPlusDeclaratorLayer.POINTER, CPlusDeclaratorLayer.FUNCTION),
            method.parameters[2].declaratorLayers
        )

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(
            transpiled.successful,
            "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}"
        )
        compileAndRunC(transpiled.cSource?.text ?: error("qualified method output is missing"))
    }

    @Test
    fun resolvesEveryObjectInCommaSeparatedFileAndBlockDeclarations() {
        val snapshot = sources.open(
            SourceId.named("multiple-declarators.cp"),
            """
            typedef struct widget_t {
                pub int ping(borrowed *self) { return 1; }
            } widget_t;
            widget_t global_value, *global_pointer;
            int use_widgets(widget_t *parameter) {
                widget_t local_value, *local_pointer;
                global_value.ping();
                global_pointer->ping();
                local_value.ping();
                local_pointer->ping();
                parameter->ping();
                return 0;
            }
            """.trimIndent()
        )

        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val index = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertEquals(5, index.resolvedCalls.size, "all comma-separated values and pointers should retain their declared type")
        assertTrue(index.resolvedCalls.all { it.methodName == "ping" }, index.resolvedCalls.toString())
    }

    @Test
    fun scopesForInitializerVariablesToTheLoopAndItsBody() {
        val snapshot = sources.open(
            SourceId.named("for-declarator-scope.cp"),
            """
            typedef struct widget_t {
                pub int ping(borrowed *self) { return 1; }
            } widget_t;
            int use_loop(void) {
                for (widget_t *item = NULL; item != NULL && item->ping(); item->ping()) {
                    item->ping();
                }
                item->ping();
                return 0;
            }
            """.trimIndent()
        )

        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        val loop = ast.root.descendantsAndSelf().single { it.syntaxKind == "for_statement" }
        val initializer = loop.descendantsAndSelf().firstOrNull { it.fieldName == "initializer" && it.syntaxKind == "declaration" }
        assertTrue(initializer != null, ast.dump())
        assertTrue(loop.children.any { it === initializer }, ast.dump())
        val index = CPlusSemanticAnalyzer().analyze(ast)

        assertEquals(
            listOf("ping", "ping", "ping"),
            index.resolvedCalls.map { it.methodName },
            "for-initializer pointer must be visible in condition, update, and body but not after the loop"
        )
    }

    @Test
    fun resolvesIndexedReceiversAcrossArrayPointerDeclaratorShapes() {
        val text = """
            typedef struct widget_t {
                int value;
            pub int ping(borrowed *self) { return self->value; }
            } widget_t;
            typedef struct holder_t {
                widget_t values[4];
                widget_t *pointers[4];
            } holder_t;
            int main(void) {
                widget_t values[4] = {{1}, {2}, {3}, {4}};
                widget_t *pointers[4] = {&values[1]};
                struct widget_t (*pointer_to_array)[4];
                holder_t holder = { .values = {{5}, {6}, {7}, {8}}, .pointers = {&values[3]} };
                pointer_to_array = &values;
                return values[0].ping() + pointers[0]->ping() + pointer_to_array[0][0].ping() +
                    holder.values[0].ping() + holder.pointers[0]->ping() == 13 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("indexed-receiver-declarators.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))
        assertEquals(
            5,
            semantic.resolvedCalls.size,
            "local/field arrays, arrays of pointers, and pointers to arrays should retain their C declarator shape: ${semantic.resolvedCalls.map { source.text.substring(it.span.startOffset, it.span.endOffset) }}\n${CPlusAstAdapter().adapt(parsed).dump()}"
        )

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        compileAndRunC(transpiled.cSource?.text ?: error("indexed receiver output is missing"))
    }

    @Test
    fun distinguishesDirectArrayDecayFromNestedArrayPointerReceivers() {
        val validText = """
            typedef struct widget_t {
                int value;
                pub int read(borrowed *self) { return self->value; }
            } widget_t;
            int main(void) {
                widget_t values[2] = {{1}, {2}};
                widget_t *pointers[2] = {&values[0], &values[1]};
                return values->read() + pointers[0]->read() == 2 ? 0 : 1;
            }
        """.trimIndent()
        val validSource = sources.open(SourceId.named("array-decay-receiver.cp"), validText)
        val validParse = backend.parse(validSource)
        assertTrue(validParse.diagnostics.isEmpty(), validParse.diagnostics.toString())
        val validSemantics = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(validParse))
        assertTrue(validSemantics.diagnostics.isEmpty(), validSemantics.diagnostics.toString())
        assertEquals(2, validSemantics.resolvedCalls.size, validSemantics.resolvedCalls.toString())
        val validOutput = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(validSource)
        assertTrue(validOutput.successful, "parser=${validOutput.parserDiagnostics}; lowering=${validOutput.loweringDiagnostics}")
        compileAndRunC(validOutput.cSource?.text ?: error("array-decay C output is missing"))

        val invalidText = """
            typedef struct widget_t {
                pub int read(borrowed *self) { return 1; }
            } widget_t;
            int use_receivers(widget_t values[2], widget_t *pointers[2], widget_t (*pointer_to_array)[2]) {
                pointers->read();
                pointer_to_array->read();
                return 0;
            }
        """.trimIndent()
        val invalidSource = sources.open(SourceId.named("nested-array-receiver.cp"), invalidText)
        val invalidParse = backend.parse(invalidSource)
        assertTrue(invalidParse.diagnostics.isEmpty(), invalidParse.diagnostics.toString())
        val invalidIndex = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(invalidParse))
        assertEquals(
            listOf("CPLUS_METHOD_RECEIVER_DECLARATOR_SHAPE", "CPLUS_METHOD_RECEIVER_DECLARATOR_SHAPE"),
            invalidIndex.diagnostics.map { it.code },
            invalidIndex.diagnostics.toString()
        )
        assertEquals(
            listOf("pointers->read()", "pointer_to_array->read()"),
            invalidIndex.diagnostics.map { invalidText.substring(it.span.startOffset, it.span.endOffset) }
        )
        val invalidOutput = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(invalidSource)
        assertFalse(invalidOutput.successful)
        assertEquals(invalidIndex.diagnostics.map { it.code }, invalidOutput.loweringDiagnostics.map { it.code })
        assertEquals(invalidIndex.diagnostics.map { it.span }, invalidOutput.loweringDiagnostics.map { it.span })
    }

    @Test
    fun resolvesMethodReceiversIntroducedByCastsAndCompoundLiterals() {
        val text = """
            typedef struct widget_t {
                int value;
                pub int read(borrowed *self) { return self->value; }
            } widget_t;
            int main(void) {
                widget_t value = { 41 };
                return ((widget_t *) &value)->read() + ((widget_t) { 1 }).read() == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("cast-and-compound-literal-receivers.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        assertEquals(2, semantic.resolvedCalls.size, semantic.resolvedCalls.toString())
        assertTrue(semantic.resolvedCalls.first().pointerAccess)
        assertFalse(semantic.resolvedCalls.last().pointerAccess)

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("cast receiver output is missing")
        assertTrue(
            Regex("""widget__read\(\(\(widget_t\s*\*\)\s*&\s*value\)""").containsMatchIn(generated),
            generated
        )
        assertTrue(
            Regex("""widget__read\(\s*&\s*\(\(\(widget_t\)""").containsMatchIn(generated),
            generated
        )
        compileAndRunC(generated)
    }

    @Test
    fun lowersMethodsReturningFunctionPointersWithoutFlatteningTheirDeclarator() {
        val text = """
            #include <stdio.h>
            static int increment(int value) { return value + 1; }
            typedef struct selector_t {
                pub int (*select(borrowed *self))(int) __attribute__((noinline)) { return increment; }
            } selector_t;
            int main(void) {
                selector_t selector;
                int (*callback)(int) = selector.select();
                printf("%d\\n", callback(41));
                return callback(41) == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("function-pointer-return-method.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        val semantic = CPlusSemanticAnalyzer().analyze(ast)

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        assertEquals(listOf("select"), semantic.resolvedCalls.map { it.methodName })
        val method = semantic.symbols.single { it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "select" }
        assertEquals(
            listOf(
                CPlusDeclaratorLayer.FUNCTION,
                CPlusDeclaratorLayer.POINTER,
                CPlusDeclaratorLayer.FUNCTION
            ),
            method.declaratorLayers
        )

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("function-pointer return method output is missing")
        assertTrue("selector__select" in generated, generated)
        assertTrue("(*selector__select" in generated, generated)
        assertTrue("__attribute__((noinline))" in generated, generated)
        compileAndRunC(generated)
    }

    @Test
    fun retainsCallingConventionQualifiersOnComplexMethodDeclarators() {
        val text = """
            typedef struct abi_selector_t {
                pub int (*select(borrowed *self))(int) __attribute__((sysv_abi)) { return 0; }
            } abi_selector_t;
            int main(void) {
                abi_selector_t selector;
                return selector.select() == 0 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("calling-convention-method.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        val method = semantic.symbols.single {
            it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "select"
        }
        assertEquals(listOf("attribute_specifier"), method.declaratorQualifiers.map { it.syntaxKind })
        assertEquals(
            listOf("__attribute__((sysv_abi))"),
            method.declaratorQualifiers.map { it.spelling }
        )

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("calling-convention method output is missing")
        assertTrue("__attribute__((sysv_abi))" in generated, generated)
        assertTrue("abi_selector__select" in generated, generated)
        compileAndRunC(generated)
    }

    @Test
    fun retainsMicrosoftCallingConventionModifiersOnMethods() {
        val text = """
            typedef struct windows_abi_t {
                pub int __stdcall read(borrowed *self) { return 7; }
            } windows_abi_t;
        """.trimIndent()
        val source = sources.open(SourceId.named("microsoft-calling-convention-method.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        val method = semantic.symbols.single {
            it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "read"
        }
        assertEquals(listOf("ms_call_modifier"), method.declaratorQualifiers.map { it.syntaxKind })
        assertEquals(listOf("__stdcall"), method.declaratorQualifiers.map { it.spelling })

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("calling-convention method output is missing")
        assertTrue("__stdcall" in generated, generated)
        assertTrue("windows_abi__read" in generated, generated)
    }

    @Test
    fun retainsMicrosoftCallingConventionModifiersOnCallableMethodDeclarators() {
        val text = """
            typedef struct windows_callable_t {
                pub int (__stdcall *select(borrowed *self))(int) { return 0; }
            } windows_callable_t;
        """.trimIndent()
        val source = sources.open(SourceId.named("microsoft-callable-method.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        val method = semantic.symbols.single {
            it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "select"
        }
        assertEquals(listOf("ms_call_modifier"), method.declaratorQualifiers.map { it.syntaxKind })
        assertEquals(listOf("__stdcall"), method.declaratorQualifiers.map { it.spelling })
        assertEquals(2, method.declaratorLayers.count { it == CPlusDeclaratorLayer.FUNCTION })
        assertEquals(1, method.declaratorLayers.count { it == CPlusDeclaratorLayer.POINTER })

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("callable method output is missing")
        assertTrue("__stdcall" in generated, generated)
        assertTrue("windows_callable__select" in generated, generated)
        compileCOnlyIfAvailable("x86_64-w64-mingw32-gcc", generated)
    }

    @Test
    fun retainsMicrosoftDeclarationSpecifiersOnMethods() {
        val text = """
            typedef struct windows_declspec_t {
                pub __declspec(noinline) int read(borrowed *self) { return 9; }
            } windows_declspec_t;
        """.trimIndent()
        val source = sources.open(SourceId.named("microsoft-declspec-method.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        val method = semantic.symbols.single {
            it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "read"
        }
        assertEquals(listOf("ms_declspec_modifier"), method.declaratorQualifiers.map { it.syntaxKind })
        assertEquals(listOf("__declspec(noinline)"), method.declaratorQualifiers.map { it.spelling })

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("declspec method output is missing")
        assertTrue("__declspec(noinline)" in generated, generated)
        assertTrue("windows_declspec__read" in generated, generated)
        compileCOnlyIfAvailable("x86_64-w64-mingw32-gcc", generated)
    }

    @Test
    fun retainsAllMicrosoftCallingConventionSpellingsOnCPlusMethods() {
        val modifiers = listOf("__cdecl", "__clrcall", "__stdcall", "__fastcall", "__thiscall", "__vectorcall", "WINAPI")
        val methods = modifiers.mapIndexed { index, modifier ->
            "pub int $modifier method$index(borrowed *self) { return $index; }"
        }.joinToString("\n                ")
        val text = """
            typedef struct windows_calling_conventions_t {
                $methods
            } windows_calling_conventions_t;
        """.trimIndent()
        val source = sources.open(SourceId.named("microsoft-calling-convention-spellings.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        val methodsByName = semantic.symbols
            .filter { it.kind == CPlusSymbolKind.INSTANCE_METHOD }
            .associateBy { it.name }
        modifiers.forEachIndexed { index, modifier ->
            val method = methodsByName["method$index"] ?: error("missing method$index: ${methodsByName.keys}")
            assertEquals(listOf("ms_call_modifier"), method.declaratorQualifiers.map { it.syntaxKind })
            assertEquals(listOf(modifier), method.declaratorQualifiers.map { it.spelling })
        }

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("calling-convention spelling output is missing")
        modifiers.forEach { modifier -> assertTrue(modifier in generated, generated) }
    }

    @Test
    fun compilesDocumentedMingwX64CallingConventionsWhenTheTargetCompilerIsAvailable() {
        val text = """
            #include <windows.h>
            typedef struct windows_supported_abi_t {
                pub int __cdecl cdecl_method(borrowed *self) { return 1; }
                pub int __stdcall stdcall_method(borrowed *self) { return 2; }
                pub int __fastcall fastcall_method(borrowed *self) { return 3; }
                pub int __thiscall thiscall_method(borrowed *self) { return 4; }
                pub int WINAPI winapi_method(borrowed *self) { return 5; }
                pub __declspec(noinline) int noinline_method(borrowed *self) { return 6; }
            } windows_supported_abi_t;
        """.trimIndent()
        val source = sources.open(SourceId.named("mingw-x64-supported-abi.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        val methods = semantic.symbols
            .filter { it.kind == CPlusSymbolKind.INSTANCE_METHOD }
            .associateBy { it.name }
        mapOf(
            "cdecl_method" to "__cdecl",
            "stdcall_method" to "__stdcall",
            "fastcall_method" to "__fastcall",
            "thiscall_method" to "__thiscall",
            "winapi_method" to "WINAPI"
        ).forEach { (name, spelling) ->
            assertEquals(listOf(spelling), methods.getValue(name).declaratorQualifiers.map { it.spelling })
        }
        assertEquals(
            listOf("__declspec(noinline)"),
            methods.getValue("noinline_method").declaratorQualifiers.map { it.spelling }
        )

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(
            transpiled.successful,
            "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}"
        )
        compileCOnlyIfAvailable(
            "x86_64-w64-mingw32-gcc",
            transpiled.cSource?.text ?: error("MinGW ABI output is missing")
        )
    }

    @Test
    fun compilesPortableDialectAttributesAndStandardHeadersWithEveryHostDriver() {
        val text = """
            #include <stddef.h>
            #include <stdint.h>
            typedef struct dialect_surface_t {
                int value;
                pub int __attribute__((noinline)) read(borrowed *self, size_t offset) {
                    return self->value + (int)offset;
                }
            } dialect_surface_t;
            int main(void) {
                dialect_surface_t value = {40};
                return value.read((size_t)2) == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("portable-dialect-surface.cp"), text)
        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.cSource?.text ?: error("portable dialect output is missing")
        assertTrue("__attribute__((noinline))" in generated, generated)
        assertTrue("CPLUS_COLD" in generated, generated)
        assertTrue("#define cold" !in generated, generated)
        assertTrue("dialect_surface__read" in generated, generated)
        compileAndRunC(generated)
    }

    @Test
    fun retainsMicrosoftCallingConventionOnNestedCallbackParameters() {
        val text = """
            typedef struct callback_abi_t {
                pub int invoke(borrowed *self, int (__stdcall *callback)(int value)) {
                    return callback(7);
                }
            } callback_abi_t;
        """.trimIndent()
        val source = sources.open(SourceId.named("microsoft-callback-parameter.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        val method = semantic.symbols.single {
            it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "invoke"
        }
        val callback = method.parameters.single { it.name == "callback" }
        assertEquals(listOf("ms_call_modifier"), callback.declaratorQualifiers.map { it.syntaxKind })
        assertEquals(listOf("__stdcall"), callback.declaratorQualifiers.map { it.spelling })
        assertEquals("int", callback.functionType?.returnType)
        assertEquals(listOf("value"), callback.functionType?.parameters?.map { it.name })

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("callback parameter output is missing")
        assertTrue("__stdcall" in generated, generated)
        assertTrue("callback_abi__invoke" in generated, generated)
        compileCOnlyIfAvailable("x86_64-w64-mingw32-gcc", generated)
    }

    @Test
    fun retainsMicrosoftPointerModifiersOnMethodAndNestedCallbackDeclarators() {
        val text = """
            typedef struct pointer_qualifier_abi_t {
                pub int read(borrowed *self, int * __restrict input) {
                    return *input;
                }
                pub int invoke(borrowed *self, int (** __restrict callback)(int value)) {
                    return (*callback)(7);
                }
            } pointer_qualifier_abi_t;
            static int increment(int value) { return value + 1; }
            int main(void) {
                pointer_qualifier_abi_t value;
                int input = 7;
                int (*callback)(int) = increment;
                return value.read(&input) == 7 && value.invoke(&callback) == 8 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("microsoft-pointer-modifiers.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        val read = semantic.symbols.single {
            it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "read"
        }
        val input = read.parameters.single { it.name == "input" }
        assertEquals(listOf("ms_pointer_modifier"), input.declaratorQualifiers.map { it.syntaxKind })
        assertEquals(listOf("__restrict"), input.declaratorQualifiers.map { it.spelling })
        val invoke = semantic.symbols.single {
            it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "invoke"
        }
        val callback = invoke.parameters.single { it.name == "callback" }
        assertEquals(listOf("ms_pointer_modifier"), callback.declaratorQualifiers.map { it.syntaxKind })
        assertEquals(listOf("__restrict"), callback.declaratorQualifiers.map { it.spelling })

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("pointer modifier output is missing")
        assertTrue("__restrict" in generated, generated)
        assertTrue("pointer_qualifier_abi__read" in generated, generated)
        assertTrue("pointer_qualifier_abi__invoke" in generated, generated)
        compileAndRunC(generated)
    }

    @Test
    fun retainsDirectPointerReturnLayersOnCPlusMethodSymbols() {
        val text = """
            typedef struct pointer_return_t {
                pub int *read(borrowed *self) {
                    static int value = 11;
                    return &value;
                }
                pub int **read_indirect(borrowed *self) {
                    static int value = 13;
                    static int *pointer = &value;
                    return &pointer;
                }
            } pointer_return_t;
            int main(void) {
                pointer_return_t value;
                return *value.read() == 11 && **value.read_indirect() == 13 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("pointer-return-method.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        val method = semantic.symbols.single {
            it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "read"
        }
        assertEquals(
            listOf(CPlusDeclaratorLayer.FUNCTION, CPlusDeclaratorLayer.POINTER),
            method.declaratorLayers
        )
        val indirect = semantic.symbols.single {
            it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "read_indirect"
        }
        assertEquals(
            listOf(
                CPlusDeclaratorLayer.FUNCTION,
                CPlusDeclaratorLayer.POINTER,
                CPlusDeclaratorLayer.POINTER
            ),
            indirect.declaratorLayers
        )

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("pointer-return method output is missing")
        compileAndRunC(generated)
        compileCOnlyIfAvailable("x86_64-w64-mingw32-gcc", generated)
    }

    @Test
    fun retainsPointerToArrayReturnLayersOnCPlusMethodSymbols() {
        val text = """
            typedef struct array_return_t {
                pub int (*row(borrowed *self))[2] {
                    static int values[2] = { 17, 25 };
                    return &values;
                }
            } array_return_t;
            int main(void) {
                array_return_t value;
                return value.row()[0][0] + value.row()[0][1] == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("pointer-to-array-return-method.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        val method = semantic.symbols.single {
            it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "row"
        }
        assertEquals(
            listOf(
                CPlusDeclaratorLayer.FUNCTION,
                CPlusDeclaratorLayer.POINTER,
                CPlusDeclaratorLayer.ARRAY
            ),
            method.declaratorLayers
        )

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("pointer-to-array method output is missing")
        assertTrue("array_return__row" in generated, generated)
        compileAndRunC(generated)
        compileCOnlyIfAvailable("x86_64-w64-mingw32-gcc", generated)
    }

    @Test
    fun retainsPointerToArrayParameterLayersOnCPlusMethodSymbols() {
        val text = """
            typedef struct array_consumer_t {
                pub int sum(borrowed *self, borrowed const int (* restrict values)[2]) {
                    return values[0][0] + values[0][1] + values[1][0] + values[1][1];
                }
            } array_consumer_t;
            int main(void) {
                array_consumer_t value;
                int values[2][2] = { { 10, 11 }, { 20, 21 } };
                return value.sum(values) == 62 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("pointer-to-array-parameter.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        val method = semantic.symbols.single {
            it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "sum"
        }
        assertEquals(
            listOf(CPlusDeclaratorLayer.POINTER, CPlusDeclaratorLayer.ARRAY),
            method.parameters.single { it.name == "values" }.declaratorLayers
        )
        assertEquals(
            "borrowed const int (* restrict values)[2]",
            method.parameters.single { it.name == "values" }.declarationText
        )

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("pointer-to-array parameter output is missing")
        assertTrue("array_consumer__sum" in generated, generated)
        compileAndRunC(generated)
        compileCOnlyIfAvailable("x86_64-w64-mingw32-gcc", generated)
    }

    @Test
    fun retainsArrayOfFunctionPointerParameterLayersOnCPlusMethodSymbols() {
        val text = """
            typedef struct callback_array_t {
                pub int invoke(
                    borrowed *self,
                    borrowed int (*callbacks[2])(int value),
                    int value
                ) {
                    return callbacks[0](value) + callbacks[1](value);
                }
            } callback_array_t;

            int plus_one(int value) { return value + 1; }
            int plus_two(int value) { return value + 2; }

            int main(void) {
                callback_array_t value;
                int (*callbacks[2])(int) = { plus_one, plus_two };
                return value.invoke(callbacks, 40) == 83 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("array-of-function-pointer-parameter.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        val method = semantic.symbols.single {
            it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "invoke"
        }
        val callbacks = method.parameters.single { it.name == "callbacks" }
        assertEquals(
            listOf(
                CPlusDeclaratorLayer.ARRAY,
                CPlusDeclaratorLayer.POINTER,
                CPlusDeclaratorLayer.FUNCTION
            ),
            callbacks.declaratorLayers
        )
        assertEquals("borrowed int (*callbacks[2])(int value)", callbacks.declarationText)
        assertEquals("int", callbacks.functionType?.returnType)
        assertEquals(listOf("value"), callbacks.functionType?.parameters?.map { it.name })

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(
            transpiled.successful,
            "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}"
        )
        val generated = transpiled.cSource?.text ?: error("array-of-function-pointer output is missing")
        assertTrue("callback_array__invoke" in generated, generated)
        compileAndRunC(generated)
        compileCOnlyIfAvailable("x86_64-w64-mingw32-gcc", generated)
    }

    @Test
    fun retainsMultidimensionalPointerToArrayReturnLayersOnCPlusMethodSymbols() {
        val text = """
            typedef struct matrix_return_t {
                pub int (*grid(borrowed *self))[2][2] {
                    static int values[2][2] = { { 1, 2 }, { 3, 4 } };
                    return &values;
                }
            } matrix_return_t;

            int main(void) {
                matrix_return_t value;
                return value.grid()[0][0][0] + value.grid()[0][1][1] == 5 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("multidimensional-pointer-to-array-return.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        val method = semantic.symbols.single {
            it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "grid"
        }
        assertEquals(
            listOf(
                CPlusDeclaratorLayer.FUNCTION,
                CPlusDeclaratorLayer.POINTER,
                CPlusDeclaratorLayer.ARRAY,
                CPlusDeclaratorLayer.ARRAY
            ),
            method.declaratorLayers
        )

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(
            transpiled.successful,
            "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}"
        )
        val generated = transpiled.cSource?.text ?: error("multidimensional pointer-to-array output is missing")
        assertTrue("matrix_return__grid" in generated, generated)
        compileAndRunC(generated)
        compileCOnlyIfAvailable("x86_64-w64-mingw32-gcc", generated)
    }

    @Test
    fun composesDeclaratorLayersRecursivelyAndEquallyForMethodsAndFunctions() {
        val methodDeclarations = (1..8).joinToString("\n") { depth ->
            val dimensions = "[2]".repeat(depth)
            "pub int (*grid_${depth}(borrowed *self))$dimensions;"
        }
        val functionDeclarations = (1..8).joinToString("\n") { depth ->
            val dimensions = "[2]".repeat(depth)
            "int (*plain_grid_${depth}(void))$dimensions { return 0; }"
        }
        val text = """
            typedef struct recursive_declarator_t {
                $methodDeclarations
            } recursive_declarator_t;
            $functionDeclarations
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("recursive-declarator-algebra.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        (1..8).forEach { depth ->
            val method = semantic.symbols.single {
                it.kind == CPlusSymbolKind.INSTANCE_METHOD && it.name == "grid_$depth"
            }
            val function = semantic.symbols.single {
                it.kind == CPlusSymbolKind.FUNCTION && it.name == "plain_grid_$depth"
            }
            val expected = listOf(CPlusDeclaratorLayer.FUNCTION, CPlusDeclaratorLayer.POINTER) +
                List(depth) { CPlusDeclaratorLayer.ARRAY }
            assertEquals(expected, method.declaratorLayers, "method depth $depth")
            assertEquals(expected, function.declaratorLayers, "function depth $depth")
        }

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(
            transpiled.successful,
            "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}"
        )
        compileAndRunC(transpiled.cSource?.text ?: error("recursive declarator output is missing"))
    }

    @Test
    fun resolvesMethodReceiversFromSameTypedConditionalAndCommaExpressions() {
        val text = """
            typedef struct widget_t {
                int value;
                pub int read(borrowed *self) { return self->value; }
            } widget_t;
            int main(void) {
                int flag = 0;
                widget_t left = { 20 };
                widget_t right = { 22 };
                return (flag ? &left : &right)->read() + (0, &left)->read() == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("composite-receiver-expressions.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        assertEquals(2, semantic.resolvedCalls.size, semantic.resolvedCalls.toString())
        assertTrue(semantic.resolvedCalls.all { it.pointerAccess })

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        compileAndRunC(transpiled.cSource?.text ?: error("composite receiver output is missing"))
    }

    @Test
    fun resolvesMethodReceiversFromConditionalsWithNullPointerConstants() {
        val text = """
            #include <stddef.h>
            typedef struct widget_t {
                int value;
                pub int read(borrowed *self) { return self->value; }
            } widget_t;
            int main(void) {
                widget_t value = { 42 };
                int enabled = 1;
                return (enabled ? &value : NULL)->read() == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("nullable-composite-receiver.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        assertEquals(listOf("read"), semantic.resolvedCalls.map { it.methodName })
        assertTrue(semantic.resolvedCalls.single().pointerAccess)

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        compileAndRunC(transpiled.cSource?.text ?: error("nullable composite receiver output is missing"))
    }

    @Test
    fun resolvesMethodReceiversFromKnownFunctionPointerResults() {
        val text = """
            typedef struct widget_t {
                int value;
                pub int add(borrowed mut *self, int amount) {
                    self->value += amount;
                    return self->value;
                }
            } widget_t;
            widget_t* make_widget(void) {
                static widget_t value = { 20 };
                return &value;
            }
            int main(void) {
                return make_widget()->add(22) == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("call-result-receiver.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        assertEquals(listOf("add"), semantic.resolvedCalls.map { it.methodName })
        assertTrue(semantic.resolvedCalls.single().pointerAccess)

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("call-result receiver output is missing")
        assertTrue("widget__add" in generated, generated)
        compileAndRunC(generated)
    }

    @Test
    fun resolvesMethodReceiversFromFunctionPointerVariables() {
        val text = """
            typedef struct widget_t {
                int value;
                pub int add(borrowed mut *self, int amount) {
                    self->value += amount;
                    return self->value;
                }
            } widget_t;
            typedef widget_t *widget_pointer_t;
            widget_t* make_widget(void) {
                static widget_t value = { 20 };
                return &value;
            }
            int main(void) {
                widget_pointer_t (*factory)(void) = make_widget;
                return factory()->add(22) == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("function-pointer-variable-receiver.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        val semantic = CPlusSemanticAnalyzer().analyze(ast)

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        assertEquals(listOf("add"), semantic.resolvedCalls.map { it.methodName })
        assertTrue(semantic.resolvedCalls.single().pointerAccess)

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("function-pointer variable receiver output is missing")
        assertTrue("widget__add" in generated, generated)
        compileAndRunC(generated)
    }

    @Test
    fun resolvesMethodReceiversFromIndexedFunctionPointerVariables() {
        val text = """
            typedef struct widget_t {
                int value;
                pub int add(borrowed mut *self, int amount) {
                    self->value += amount;
                    return self->value;
                }
            } widget_t;
            widget_t* make_widget(void) {
                static widget_t value = { 20 };
                return &value;
            }
            int main(void) {
                widget_t *(*factories[1])(void) = { make_widget };
                return factories[0]()->add(22) == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("indexed-function-pointer-variable-receiver.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        val semantic = CPlusSemanticAnalyzer().analyze(ast)

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        assertEquals(listOf("add"), semantic.resolvedCalls.map { it.methodName })
        assertTrue(semantic.resolvedCalls.single().pointerAccess)

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("indexed function-pointer receiver output is missing")
        assertTrue("widget__add" in generated, generated)
        compileAndRunC(generated)
    }

    @Test
    fun resolvesMethodReceiversFromFunctionPointerFields() {
        val text = """
            typedef struct widget_t {
                int value;
                pub int add(borrowed mut *self, int amount) {
                    self->value += amount;
                    return self->value;
                }
            } widget_t;
            widget_t* make_widget(void) {
                static widget_t value = { 20 };
                return &value;
            }
            typedef struct factory_t {
                widget_t *(*make)(void);
            } factory_t;
            int main(void) {
                factory_t factory = { make_widget };
                return factory.make()->add(22) == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("function-pointer-field-receiver.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        val semantic = CPlusSemanticAnalyzer().analyze(ast)

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        assertEquals(listOf("add"), semantic.resolvedCalls.map { it.methodName })
        assertTrue(semantic.resolvedCalls.single().pointerAccess)

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("function-pointer field receiver output is missing")
        assertTrue("widget__add" in generated, generated)
        compileAndRunC(generated)
    }

    @Test
    fun resolvesMethodReceiversFromFunctionPointerTypedefFields() {
        val text = """
            typedef struct widget_t {
                int value;
                pub int add(borrowed mut *self, int amount) {
                    self->value += amount;
                    return self->value;
                }
            } widget_t;
            widget_t* make_widget(void) {
                static widget_t value = { 20 };
                return &value;
            }
            typedef widget_t *(*widget_factory_callback_t)(void);
            typedef struct factory_t {
                widget_factory_callback_t make;
            } factory_t;
            int main(void) {
                factory_t factory = { make_widget };
                return factory.make()->add(22) == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("function-pointer-typedef-field-receiver.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        val semantic = CPlusSemanticAnalyzer().analyze(ast)

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        assertEquals(listOf("add"), semantic.resolvedCalls.map { it.methodName })
        assertTrue(semantic.resolvedCalls.single().pointerAccess)

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("function-pointer typedef field receiver output is missing")
        assertTrue("widget__add" in generated, generated)
        compileAndRunC(generated)
    }

    @Test
    fun resolvesMethodReceiversFromFunctionPointerTypedefVariables() {
        val text = """
            typedef struct widget_t {
                int value;
                pub int add(borrowed mut *self, int amount) {
                    self->value += amount;
                    return self->value;
                }
            } widget_t;
            widget_t* make_widget(void) {
                static widget_t value = { 20 };
                return &value;
            }
            typedef widget_t *(*widget_factory_callback_t)(void);
            int main(void) {
                widget_factory_callback_t factory = make_widget;
                return factory()->add(22) == 42 && (*factory)()->add(0) == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("function-pointer-typedef-variable-receiver.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)
        val semantic = CPlusSemanticAnalyzer().analyze(ast)

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        assertEquals(listOf("add", "add"), semantic.resolvedCalls.map { it.methodName })
        assertTrue(semantic.resolvedCalls.all { it.pointerAccess })

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("function-pointer typedef variable receiver output is missing")
        assertEquals(3, "widget__add".toRegex().findAll(generated).count(), generated)
        compileAndRunC(generated)
    }

    @Test
    fun resolvesMethodReceiversFromFunctionPointerTypedefParameters() {
        val text = """
            typedef struct widget_t {
                int value;
                pub int add(borrowed mut *self, int amount) {
                    self->value += amount;
                    return self->value;
                }
            } widget_t;
            widget_t* make_widget(void) {
                static widget_t value = { 20 };
                return &value;
            }
            typedef widget_t *(*widget_factory_callback_t)(void);
            int use_factory(widget_factory_callback_t factory) {
                return factory()->add(22);
            }
            int main(void) {
                return use_factory(make_widget) == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("function-pointer-typedef-parameter-receiver.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        assertEquals(listOf("add"), semantic.resolvedCalls.map { it.methodName })
        assertTrue(semantic.resolvedCalls.single().pointerAccess)

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("function-pointer typedef parameter receiver output is missing")
        assertTrue("widget__add(factory(), 22)" in generated, generated)
        compileAndRunC(generated)
    }

    @Test
    fun resolvesMethodReceiversFromDirectFunctionPointerParameters() {
        val text = """
            typedef struct widget_t {
                int value;
                pub int add(borrowed mut *self, int amount) {
                    self->value += amount;
                    return self->value;
                }
            } widget_t;
            widget_t* make_widget(void) {
                static widget_t value = { 20 };
                return &value;
            }
            int use_factory(widget_t *(*factory)(void)) {
                return factory()->add(22);
            }
            int main(void) {
                return use_factory(make_widget) == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("direct-function-pointer-parameter-receiver.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        assertEquals(listOf("add"), semantic.resolvedCalls.map { it.methodName })
        assertTrue(semantic.resolvedCalls.single().pointerAccess)

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("direct function-pointer parameter receiver output is missing")
        assertTrue("widget__add(factory(), 22)" in generated, generated)
        compileAndRunC(generated)
    }

    @Test
    fun resolvesMethodReceiversThroughNestedFunctionPointerReturns() {
        val text = """
            typedef struct widget_t {
                int value;
                pub int add(borrowed mut *self, int amount) {
                    self->value += amount;
                    return self->value;
                }
            } widget_t;
            widget_t* make_widget(void) {
                static widget_t value = { 20 };
                return &value;
            }
            typedef widget_t *(*widget_factory_t)(void);
            widget_factory_t get_factory(void) {
                return make_widget;
            }
            int main(void) {
                return get_factory()()->add(22) == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("nested-function-pointer-return-receiver.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        assertEquals(listOf("add"), semantic.resolvedCalls.map { it.methodName })
        assertTrue(semantic.resolvedCalls.single().pointerAccess)

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("nested function-pointer receiver output is missing")
        assertTrue("widget__add(get_factory()(), 22)" in generated, generated)
        compileAndRunC(generated)
    }

    @Test
    fun resolvesMethodReceiversThroughCallableFieldsArraysAndChainedReturns() {
        val text = """
            typedef struct widget_t {
                int value;
                pub int add(borrowed mut *self, int amount) {
                    self->value += amount;
                    return self->value;
                }
            } widget_t;
            widget_t* make_widget(void) {
                static widget_t value = { 20 };
                return &value;
            }
            typedef widget_t *(*widget_factory_t)(void);
            typedef struct factory_box_t {
                widget_factory_t callback;
                widget_factory_t callbacks[1];
                pub widget_factory_t get(borrowed *self) {
                    return self->callback;
                }
            } factory_box_t;
            int main(void) {
                factory_box_t box = { make_widget, { make_widget } };
                return box.callback()->add(22) == 42 &&
                    box.callbacks[0]()->add(0) == 42 &&
                    box.get()()->add(0) == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("callable-field-array-chain-receiver.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        assertEquals(3, semantic.resolvedCalls.count { it.methodName == "add" }, semantic.resolvedCalls.toString())
        assertEquals(1, semantic.resolvedCalls.count { it.methodName == "get" }, semantic.resolvedCalls.toString())
        assertTrue(semantic.resolvedCalls.filter { it.methodName == "add" }.all { it.pointerAccess })

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("callable field/array chain output is missing")
        assertTrue("factory_box__get(&box)()" in generated, generated)
        assertEquals(4, "widget__add".toRegex().findAll(generated).count(), generated)
        compileAndRunC(generated)
    }

    @Test
    fun resolvesMethodReceiversFromKnownStaticMethodResults() {
        val text = """
            typedef struct widget_t {
                int value;
                static pub widget_t* create(void) {
                    static widget_t value = { 20 };
                    return &value;
                }
                pub int add(borrowed mut *self, int amount) {
                    self->value += amount;
                    return self->value;
                }
            } widget_t;
            int main(void) {
                return widget_t.create()->add(22) == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("static-call-result-receiver.cp"), text)

        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertTrue(semantic.diagnostics.isEmpty(), semantic.diagnostics.toString())
        assertEquals(setOf("create", "add"), semantic.resolvedCalls.map { it.methodName }.toSet())
        assertTrue(semantic.resolvedCalls.single { it.methodName == "add" }.pointerAccess)

        val transpiled = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(transpiled.successful, "parser=${transpiled.parserDiagnostics}; lowering=${transpiled.loweringDiagnostics}")
        val generated = transpiled.cSource?.text ?: error("static call-result receiver output is missing")
        assertTrue("widget__create" in generated, generated)
        assertTrue("widget__add" in generated, generated)
        compileAndRunC(generated)
    }

    @Test
    fun semanticIndexDoesNotLeakSymbolsFromUnmaterializedComptimeBodies() {
        val snapshot = sources.open(
            SourceId.named("comptime-semantic-scope.cp"),
            """
            comptime type @generated(type T) {
                return @code {
                    typedef struct template_only_t {
                        pub int template_method(borrowed *self);
                    } template_only_t;
                };
            }
            typedef struct runtime_type_t {
                pub int runtime_method(borrowed *self);
            } runtime_type_t;
            int use_runtime_type(runtime_type_t *value) {
                value->runtime_method();
                return 0;
            }
            """.trimIndent()
        )

        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertFalse(semantic.symbols.any { it.name in setOf("template_only_t", "template_method") }, semantic.symbols.toString())
        assertTrue(semantic.symbols.any { it.name == "runtime_type_t" }, semantic.symbols.toString())
        assertTrue(semantic.symbols.any { it.name == "runtime_method" }, semantic.symbols.toString())
        assertEquals(listOf("runtime_method"), semantic.resolvedCalls.map { it.methodName })
    }

    @Test
    fun receiverResolutionPreservesPointerDepthAcrossStructFields() {
        val snapshot = sources.open(
            SourceId.named("field-pointer-depth.cp"),
            """
            typedef struct leaf_t {
                pub int read(borrowed *self);
            } leaf_t;
            typedef struct root_t {
                leaf_t *child;
                leaf_t **children;
            } root_t;
            int use_root(root_t *root) {
                root->child->read();
                root->children->read();
                (*root->children)->read();
                return 0;
            }
            """.trimIndent()
        )

        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val semantic = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertEquals(2, semantic.resolvedCalls.size, "single-pointer fields and explicit pointer-to-pointer dereferences resolve")
        assertTrue(semantic.resolvedCalls.all { it.methodName == "read" })
    }

    @Test
    fun lowersResolvedValuePointerAndStaticCallsAndPreservesOrigins() {
        val text = """
            typedef struct counter_t {
                pub int add(borrowed mut *self, int amount);
                static pub counter_t *create(int initial);
            } counter_t;
            int run(void) {
                counter_t counter;
                counter_t *pointer = &counter;
                counter.add(3);
                pointer->add(4);
                counter_t.create(5);
                return 0;
            }
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("method-lowering.cp"), text)
        val ast = CPlusAstAdapter().adapt(backend.parse(snapshot))
        val semantics = CPlusSemanticAnalyzer().analyze(ast)

        val lowered = CPlusMethodCallLoweringPass().lower(ast, MappedText.identity(snapshot.sourceFile), semantics)

        assertTrue(lowered.diagnostics.isEmpty(), lowered.diagnostics.toString())
        assertTrue(lowered.source.text.contains("counter__add(&counter, 3)"), lowered.source.text)
        assertTrue(lowered.source.text.contains("counter__add(pointer, 4)"), lowered.source.text)
        assertTrue(lowered.source.text.contains("counter__create(5)"), lowered.source.text)
        val generated = lowered.source.text.indexOf("counter__add")
        assertEquals(text.indexOf("add(3)"), lowered.source.originAt(generated)?.offset)
    }

    @Test
    fun prototypeExposesCompilerMappedEmissionForLoweredC() {
        val text = """
            typedef struct counter_t {
                int value;
                pub int get(borrowed *self) { return self->value; }
            } counter_t;
            int main(void) { counter_t counter = {42}; return counter.get() != 42; }
        """.trimIndent()
        val source = sources.open(SourceId.named("mapped-emission.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val emitted = result.transcodedSource ?: error("successful prototype result must expose mapped C emission")
        assertTrue(emitted.code.contains("counter__get(&counter)"), emitted.code)
        val generatedLine = emitted.code.lines().indexOfFirst { "counter__get" in it } + 1
        val mappedSpan = emitted.sourceMap.sourceForGeneratedLine(generatedLine)
        assertEquals(text.lines().indexOfFirst { "get(borrowed" in it } + 1, mappedSpan?.startLine)
        compileAndRunC(emitted.code)
    }

    @Test
    fun legacyAndTreeSitterMethodLoweringProduceTheSameRuntimeBehavior() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val text = """
            #include <stdio.h>
            typedef struct counter_t {
                int value;
                pub void add(borrowed mut *self, int amount) { self->value += amount; }
                pub int get(borrowed *self) { return self->value; }
            } counter_t;
            int main(void) {
                counter_t counter = {2};
                counter.add(3);
                char line_end = '\n';
                printf("%s:%d%c", "counter" ":" "ready", counter.get(), line_end);
                return counter.get() == 5 ? 0 : 1;
            }
        """.trimIndent()
        val name = "differential-methods.cp"
        val source = sources.open(SourceId.named(name), text)

        val legacy = CPlusTranspiler().transpile(text, name)
        val experimental = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            experimental.successful,
            "parser=${experimental.parserDiagnostics}; lowering=${experimental.loweringDiagnostics}; unsupported=${experimental.unsupportedNodes}"
        )
        val experimentalC = experimental.transcodedSource ?: error("prototype has no mapped C output")
        val expectedMethodLine = text.lines().indexOfFirst { "add(borrowed" in it } + 1
        listOf(legacy, experimentalC).forEach { generated ->
            val methodLine = generated.code.lines().indexOfFirst { "counter__add(" in it } + 1
            assertTrue(methodLine > 0, generated.code)
            val original = generated.sourceMap.sourceForGeneratedLine(methodLine)
            assertEquals(name, original?.file)
            assertEquals(expectedMethodLine, original?.startLine)
        }

        val legacyRun = compileAndCaptureC(compiler, legacy.code)
        val experimentalRun = compileAndCaptureC(compiler, experimentalC.code)
        assertEquals(0, legacyRun.first, "legacy executable failed: ${legacyRun.second}")
        assertEquals(0, experimentalRun.first, "Tree-sitter executable failed: ${experimentalRun.second}")
        assertEquals("counter:ready:5\n", legacyRun.second)
        assertEquals(legacyRun, experimentalRun)
    }

    @Test
    fun legacyAndTreeSitterAgreeOnStaticFactoriesAndPointerReceivers() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val text = """
            #include <stdio.h>
            typedef struct counter_t {
                int value;
                static pub counter_t create(int initial) {
                    return (counter_t){ .value = initial };
                }
                pub void add(borrowed mut *self, int amount) { self->value += amount; }
                pub int get(borrowed *self) { return self->value; }
            } counter_t;
            int main(void) {
                counter_t counter = counter_t.create(40);
                counter_t *pointer = &counter;
                pointer->add(2);
                printf("%d\n", pointer->get());
                return pointer->get() == 42 ? 0 : 1;
            }
        """.trimIndent()
        val name = "differential-static-and-pointer-methods.cp"
        val source = sources.open(SourceId.named(name), text)
        val legacy = CPlusTranspiler().transpile(text, name)
        val experimental = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            experimental.successful,
            "parser=${experimental.parserDiagnostics}; lowering=${experimental.loweringDiagnostics}; " +
                "unsupported=${experimental.unsupportedNodes}"
        )
        val experimentalC = experimental.transcodedSource ?: error("prototype has no mapped C output")
        listOf(
            "counter__create" to "static pub counter_t create",
            "counter__add" to "pub void add",
            "counter__get" to "pub int get"
        ).forEach { (generatedName, declarationText) ->
            listOf(legacy, experimentalC).forEach { generated ->
                val generatedLine = generated.code.lines().indexOfFirst { "$generatedName(" in it } + 1
                assertTrue(generatedLine > 0, "$generatedName missing from generated C:\n${generated.code}")
                val origin = generated.sourceMap.sourceForGeneratedLine(generatedLine)
                assertEquals(name, origin?.file)
                assertEquals(text.lines().indexOfFirst { declarationText in it } + 1, origin?.startLine)
            }
        }

        val legacyRun = compileAndCaptureC(compiler, legacy.code)
        val experimentalRun = compileAndCaptureC(compiler, experimentalC.code)
        assertEquals(0, legacyRun.first, "legacy executable failed: ${legacyRun.second}")
        assertEquals(0, experimentalRun.first, "Tree-sitter executable failed: ${experimentalRun.second}")
        assertEquals("42\n", legacyRun.second)
        assertEquals(legacyRun, experimentalRun)
    }

    @Test
    fun legacyAndTreeSitterPreserveCommonCFunctionPointerAndControlFlowBehavior() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val text = """
            #include <stddef.h>
            #include <stdio.h>
            typedef int (*transform_fn)(int value);
            typedef struct sample_t { int value; } sample_t;
            static int increment(int value) { return value + 1; }
            static int accumulate(const sample_t *items, size_t count, transform_fn transform) {
                int total = 0;
                for (size_t index = 0; index < count; ++index) {
                    if (items[index].value < 0) continue;
                    total += transform(items[index].value);
                }
                return total;
            }
            int main(void) {
                const sample_t values[] = {{1}, {-9}, {3}};
                int result = accumulate(values, sizeof values / sizeof values[0], increment);
                switch (result) {
                    case 6: puts("sum=6"); break;
                    default: puts("unexpected"); return 1;
                }
            }
        """.trimIndent()
        val name = "differential-common-c.cp"
        val source = sources.open(SourceId.named(name), text)

        val legacy = CPlusTranspiler().transpile(text, name)
        val experimental = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            experimental.successful,
            "parser=${experimental.parserDiagnostics}; lowering=${experimental.loweringDiagnostics}; unsupported=${experimental.unsupportedNodes}"
        )
        val experimentalC = experimental.transcodedSource ?: error("prototype has no mapped C output")
        assertEquals(
            cTokenSpellings(legacy.code, "$name.legacy.c"),
            cTokenSpellings(experimentalC.code, "$name.tree-sitter.c"),
            "the two C emitters should preserve one C11 token stream apart from layout and #line directives"
        )
        val legacyRun = compileAndCaptureC(compiler, legacy.code)
        val experimentalRun = compileAndCaptureC(compiler, experimentalC.code)
        assertEquals(0, legacyRun.first, "legacy executable failed: ${legacyRun.second}")
        assertEquals(0, experimentalRun.first, "Tree-sitter executable failed: ${experimentalRun.second}")
        assertEquals("sum=6\n", legacyRun.second)
        assertEquals(legacyRun, experimentalRun)
    }

    @Test
    fun legacyAndTreeSitterPreserveC11DeclarationAndInitializerBehavior() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val text = """
            #include <stdio.h>
            #define APPLY(function, value) ((function)((value)))
            typedef enum operation_t { OP_DOUBLE, OP_INCREMENT } operation_t;
            typedef union payload_t { int integer; const char *text; } payload_t;
            typedef struct item_t { unsigned kind : 2; payload_t payload; } item_t;
            typedef int (*unary_fn)(int value);
            static int double_value(int value) { return value * 2; }
            static int increment(int value) { return value + 1; }
            int main(void) {
                item_t item = { .kind = 1, .payload = { .integer = 20 } };
                int values[3] = { [0] = 1, [2] = 3 };
                unary_fn operations[2] = { [OP_DOUBLE] = double_value, [OP_INCREMENT] = increment };
                int result = APPLY(operations[item.kind - 1], item.payload.integer) + values[0] + values[2];
                switch (_Generic(result, int: OP_DOUBLE, default: OP_INCREMENT)) {
                    case OP_DOUBLE:
                        printf("result=%d\n", result);
                        return result == 44 ? 0 : 1;
                    default:
                        puts("wrong generic type");
                        return 2;
                }
            }
        """.trimIndent()
        val name = "differential-c11-initializers.cp"
        val source = sources.open(SourceId.named(name), text)

        val legacy = CPlusTranspiler().transpile(text, name)
        val experimental = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertTrue(
            experimental.successful,
            "parser=${experimental.parserDiagnostics}; lowering=${experimental.loweringDiagnostics}; " +
                "unsupported=${experimental.unsupportedNodes}"
        )
        val experimentalC = experimental.transcodedSource ?: error("prototype has no mapped C output")
        assertEquals(
            cTokenSpellings(legacy.code, "$name.legacy.c"),
            cTokenSpellings(experimentalC.code, "$name.tree-sitter.c"),
            "the two C emitters should preserve the C11 declaration/initializer token stream"
        )
        val legacyRun = compileAndCaptureC(compiler, legacy.code)
        val experimentalRun = compileAndCaptureC(compiler, experimentalC.code)
        assertEquals(0, legacyRun.first, "legacy executable failed: ${legacyRun.second}")
        assertEquals(0, experimentalRun.first, "Tree-sitter executable failed: ${experimentalRun.second}")
        assertEquals("result=44\n", legacyRun.second)
        assertEquals(legacyRun, experimentalRun)
    }

    @Test
    fun legacyAndTreeSitterOutputsForSupportedRepositoryModulesCompileAsC11() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib")) && Files.isDirectory(it.resolve("examples")) }
            ?: error("could not locate repository sources from ${Path.of("").toAbsolutePath()}")
        val modules = listOf(
            "stdlib/encodings/rune.cp",
            "stdlib/http/protocol.cp",
            "stdlib/io/file.cp",
            "stdlib/memory/xmem.cp",
            "examples/basic.cp"
        )

        modules.forEach { relativePath ->
            val path = repository.resolve(relativePath)
            val text = Files.readString(path)
            val name = path.toRealPath().toString()
            val source = sources.open(SourceId.named(name), text)
            val legacy = CPlusTranspiler().transpile(text, name)
            val experimental = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
            assertTrue(
                experimental.successful,
                "$relativePath: parser=${experimental.parserDiagnostics}; lowering=${experimental.loweringDiagnostics}; " +
                    "unsupported=${experimental.unsupportedNodes}"
            )
            val treeSitterC = experimental.transcodedSource ?: error("$relativePath has no mapped C output")

            listOf("legacy" to legacy.code, "tree-sitter" to treeSitterC.code).forEach { (frontend, generatedC) ->
                val compile = ProcessBuilder(compiler, "-std=c11", "-fsyntax-only", "-x", "c", "-")
                    .redirectErrorStream(true)
                    .start()
                compile.outputStream.bufferedWriter().use { it.write(generatedC) }
                val compilerOutput = compile.inputStream.bufferedReader().use { it.readText() }
                assertEquals(
                    0,
                    compile.waitFor(),
                    "$compiler rejected $frontend output for $relativePath:\n$compilerOutput\n$generatedC"
                )
            }
        }
    }

    @Test
    fun legacyAndTreeSitterDeferLoweringPreserveReverseExecutionOrder() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val text = """
            #include <stdio.h>
            int main(void) {
                defer { puts("last registered"); }
                defer { puts("first executed"); }
                puts("body");
            }
        """.trimIndent()
        val name = "differential-defer.cp"
        val source = sources.open(SourceId.named(name), text)

        val legacy = CPlusTranspiler().transpile(text, name)
        val experimental = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            experimental.successful,
            "parser=${experimental.parserDiagnostics}; lowering=${experimental.loweringDiagnostics}; unsupported=${experimental.unsupportedNodes}"
        )
        val experimentalC = experimental.transcodedSource ?: error("prototype has no mapped C output")
        val legacyRun = compileAndCaptureC(compiler, legacy.code)
        val experimentalRun = compileAndCaptureC(compiler, experimentalC.code)
        assertEquals(0, legacyRun.first, "legacy executable failed: ${legacyRun.second}")
        assertEquals(0, experimentalRun.first, "Tree-sitter executable failed: ${experimentalRun.second}")
        assertEquals("body\nfirst executed\nlast registered\n", legacyRun.second)
        assertEquals(legacyRun, experimentalRun)
    }

    @Test
    fun legacyAndTreeSitterCheckedCallsAgreeOnSuccessAndCaughtFailure() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val text = """
            #include <stdio.h>
            typedef int error_t;
            enum { ERROR_BAD = 2 };
            @throws() pub error_t read_value(int input, borrowed mut int *out) {
                if (input < 0) return ERROR_BAD;
                *out = input + 1;
                return 0;
            }
            int main(void) {
                int value = 0;
                @try {
                    read_value(4, &value);
                    puts("success");
                }
                @catch (error_t error) { puts("unexpected success error"); }
                @try {
                    read_value(-1, &value);
                    puts("unreachable");
                }
                @catch (ERROR_BAD, error_t error) { puts("caught"); }
                @catch (error_t error) { puts("unexpected failure error"); }
                printf("%d\n", value);
                return value == 5 ? 0 : 1;
            }
        """.trimIndent()
        val name = "differential-checked-errors.cp"
        val source = sources.open(SourceId.named(name), text)

        val legacy = CPlusTranspiler().transpile(text, name)
        val experimental = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            experimental.successful,
            "parser=${experimental.parserDiagnostics}; lowering=${experimental.loweringDiagnostics}; unsupported=${experimental.unsupportedNodes}"
        )
        val experimentalC = experimental.transcodedSource ?: error("prototype has no mapped C output")
        val legacyRun = compileAndCaptureC(compiler, legacy.code)
        val experimentalRun = compileAndCaptureC(compiler, experimentalC.code)
        assertEquals(0, legacyRun.first, "legacy executable failed: ${legacyRun.second}")
        assertEquals(0, experimentalRun.first, "Tree-sitter executable failed: ${experimentalRun.second}")
        assertEquals("success\ncaught\n5\n", legacyRun.second)
        assertEquals(legacyRun, experimentalRun)
    }

    @Test
    fun legacyAndTreeSitterAgreeOnErrorOutFunctionsAndMethods() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val text = """
            #include <stdio.h>
            typedef int error_t;
            enum { ERROR_NONE = 0, ERROR_BAD = 2 };
            @throws(error) pub int decode(int input, borrowed mut error_t *error) {
                if (input == 0) { *error = ERROR_BAD; return -1; }
                *error = ERROR_NONE;
                return input * 2;
            }
            typedef struct counter_t {
                int value;
                @throws(error) pub int read(borrowed *self, borrowed mut error_t *error) {
                    *error = ERROR_NONE;
                    return self->value;
                }
            } counter_t;
            int main(void) {
                int value = 0;
                counter_t counter = {42};
                @try { value = decode(21); }
                @catch (error_t error) { return 1; }
                printf("decoded=%d\n", value);
                @try { value = counter.read(); }
                @catch (error_t error) { return 2; }
                printf("read=%d\n", value);
                @try {
                    value = decode(0);
                    puts("unreachable");
                }
                @catch (ERROR_BAD, error_t caught) { printf("caught=%d\n", caught); }
                @catch (error_t error) { return 3; }
                return value == -1 ? 0 : 4;
            }
        """.trimIndent()
        val name = "differential-error-out.cp"
        val source = sources.open(SourceId.named(name), text)
        val legacy = CPlusTranspiler().transpile(text, name)
        val experimental = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            experimental.successful,
            "parser=${experimental.parserDiagnostics}; lowering=${experimental.loweringDiagnostics}; " +
                "unsupported=${experimental.unsupportedNodes}"
        )
        val experimentalC = experimental.transcodedSource ?: error("prototype has no mapped C output")
        listOf("decode" to "@throws(error) pub int decode", "counter__read" to "@throws(error) pub int read")
            .forEach { (generatedName, declarationText) ->
                listOf(legacy, experimentalC).forEach { generated ->
                    val generatedLine = generated.code.lines().indexOfFirst { "$generatedName(" in it } + 1
                    assertTrue(generatedLine > 0, "$generatedName missing from generated C:\n${generated.code}")
                    assertEquals(name, generated.sourceMap.sourceForGeneratedLine(generatedLine)?.file)
                    assertEquals(
                        text.lines().indexOfFirst { declarationText in it } + 1,
                        generated.sourceMap.sourceForGeneratedLine(generatedLine)?.startLine
                    )
                }
            }

        val legacyRun = compileAndCaptureC(compiler, legacy.code)
        val experimentalRun = compileAndCaptureC(compiler, experimentalC.code)
        assertEquals(0, legacyRun.first, "legacy executable failed: ${legacyRun.second}")
        assertEquals(0, experimentalRun.first, "Tree-sitter executable failed: ${experimentalRun.second}")
        assertEquals("decoded=42\nread=42\ncaught=2\n", legacyRun.second)
        assertEquals(legacyRun, experimentalRun)
    }

    @Test
    fun legacyAndTreeSitterRuntimePassesComposeAcrossMethodsCheckedCallsAndDefer() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val text = """
            #include <stdio.h>
            typedef int error_t;
            enum { ERROR_BAD = 2 };
            typedef struct state_t {
                int total;
                pub void add(borrowed mut *self, int amount) { self->total += amount; }
            } state_t;
            @throws() pub error_t update(int input, borrowed mut int *out) {
                if (input < 0) return ERROR_BAD;
                *out = input;
                return 0;
            }
            int main(void) {
                state_t state = {0};
                int value = 0;
                defer { puts("cleanup"); }
                @try {
                    state.add(4);
                    update(3, &value);
                    printf("body:%d:%d\n", state.total, value);
                }
                @catch (error_t error) { puts("unexpected error"); }
                @try { update(-1, &value); }
                @catch (ERROR_BAD, error_t error) { puts("caught"); }
                @catch (error_t error) { puts("unexpected failure"); }
                printf("end:%d\n", value);
            }
        """.trimIndent()
        val name = "differential-composed-runtime-passes.cp"
        val source = sources.open(SourceId.named(name), text)

        val legacy = CPlusTranspiler().transpile(text, name)
        val experimental = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            experimental.successful,
            "parser=${experimental.parserDiagnostics}; lowering=${experimental.loweringDiagnostics}; unsupported=${experimental.unsupportedNodes}"
        )
        val experimentalC = experimental.transcodedSource ?: error("prototype has no mapped C output")
        listOf(legacy, experimentalC).forEach { generated ->
            val methodLine = generated.code.lines().indexOfFirst { "state__add(" in it } + 1
            assertTrue(methodLine > 0, generated.code)
            val origin = generated.sourceMap.sourceForGeneratedLine(methodLine)
            assertEquals(name, origin?.file)
            assertEquals(text.lines().indexOfFirst { "add(borrowed" in it } + 1, origin?.startLine)
        }

        val legacyRun = compileAndCaptureC(compiler, legacy.code)
        val experimentalRun = compileAndCaptureC(compiler, experimentalC.code)
        assertEquals(0, legacyRun.first, "legacy executable failed: ${legacyRun.second}")
        assertEquals(0, experimentalRun.first, "Tree-sitter executable failed: ${experimentalRun.second}")
        assertEquals("body:4:3\ncaught\nend:3\ncleanup\n", legacyRun.second)
        assertEquals(legacyRun, experimentalRun)
    }

    @Test
    fun legacyAndTreeSitterModuleImportsAgreeOnRuntimeAndSourceOrigins() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val directory = Files.createTempDirectory("cplus-differential-imports")
        try {
            val helper = directory.resolve("helper.cp")
            val child = directory.resolve("child.cp")
            val root = directory.resolve("main.cp")
            Files.writeString(helper, "comptime int imported_answer = 41;\nint imported_value(void) { return comptime imported_answer; }\n")
            Files.writeString(child, "comptime import \"helper.cp\";\nint child_value(void) { return imported_value() + 1; }\n")
            val text = """
                #include <stdio.h>
                comptime import "child.cp";
                int main(void) { printf("%d\n", child_value()); return child_value() == 42 ? 0 : 1; }
            """.trimIndent()
            Files.writeString(root, text)
            val name = root.toRealPath().toString()
            val source = sources.open(SourceId.named(name), text)

            val legacy = CPlusTranspiler().transpile(text, name)
            val experimental = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
            assertTrue(
                experimental.successful,
                "parser=${experimental.parserDiagnostics}; lowering=${experimental.loweringDiagnostics}; unsupported=${experimental.unsupportedNodes}"
            )
            val experimentalC = experimental.transcodedSource ?: error("prototype has no mapped C output")
            val expectedOrder = listOf(SourceId.fromPath(helper), SourceId.fromPath(child), SourceId.fromPath(root))
            assertEquals(expectedOrder, legacy.sourceOrder)
            assertEquals(expectedOrder, experimentalC.sourceOrder)
            listOf(legacy, experimentalC).forEach { generated ->
                val definitionLine = generated.code.lines().indexOfFirst {
                    Regex("\\bint\\s+imported_value\\s*\\(").containsMatchIn(it)
                } + 1
                assertTrue(definitionLine > 0, generated.code)
                val original = generated.sourceMap.sourceForGeneratedLine(definitionLine)
                assertEquals(helper.toRealPath().toString(), original?.file)
                assertEquals(2, original?.startLine)
            }
            val legacyRun = compileAndCaptureC(compiler, legacy.code)
            val experimentalRun = compileAndCaptureC(compiler, experimentalC.code)
            assertEquals(0, legacyRun.first, "legacy executable failed: ${legacyRun.second}")
            assertEquals(0, experimentalRun.first, "Tree-sitter executable failed: ${experimentalRun.second}")
            assertEquals("42\n", legacyRun.second)
            assertEquals(legacyRun, experimentalRun)
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun legacyAndTreeSitterImportedCompilerErrorsNameTheOriginalModuleLine() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val directory = Files.createTempDirectory("cplus-differential-import-error")
        try {
            val helper = directory.resolve("helper.cp")
            val root = directory.resolve("main.cp")
            Files.writeString(helper, "comptime int imported_answer = 41;\nint imported_value(void) { return missing_identifier; }\n")
            val text = "comptime import \"helper.cp\";\nint main(void) { return imported_value(); }\n"
            Files.writeString(root, text)
            val name = root.toRealPath().toString()
            val source = sources.open(SourceId.named(name), text)

            val legacy = CPlusTranspiler().transpile(text, name)
            val experimental = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
            assertTrue(
                experimental.successful,
                "parser=${experimental.parserDiagnostics}; lowering=${experimental.loweringDiagnostics}; unsupported=${experimental.unsupportedNodes}"
            )
            val experimentalC = experimental.transcodedSource ?: error("prototype has no mapped C output")
            val expectedLocation = "${helper.toRealPath()}:2:"
            listOf(legacy, experimentalC).forEach { generated ->
                val compile = ProcessBuilder(compiler, "-std=c11", "-fsyntax-only", "-x", "c", "-")
                    .redirectErrorStream(true).start()
                compile.outputStream.bufferedWriter().use { it.write(generated.code) }
                val output = compile.inputStream.bufferedReader().use { it.readText() }
                assertTrue(compile.waitFor() != 0, "invalid imported C unexpectedly compiled:\n${generated.code}")
                assertTrue(expectedLocation in output, "$compiler error must name $expectedLocation:\n$output")
                assertTrue("missing_identifier" in output, output)
            }
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun legacyAndTreeSitterMethodErrorsMapToTheSameOriginalSourceLine() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val text = """
            typedef struct broken_t {
                int value;
                pub int read(borrowed *self) { return self->missing; }
            } broken_t;
            int main(void) { broken_t value = {0}; return value.read(); }
        """.trimIndent()
        val name = "differential-method-error.cp"
        val source = sources.open(SourceId.named(name), text)
        val legacy = CPlusTranspiler().transpile(text, name)
        val experimental = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            experimental.successful,
            "parser=${experimental.parserDiagnostics}; lowering=${experimental.loweringDiagnostics}; " +
                "unsupported=${experimental.unsupportedNodes}"
        )
        val experimentalC = experimental.transcodedSource ?: error("prototype has no mapped C output")
        val expectedLine = text.lines().indexOfFirst { "self->missing" in it } + 1
        val diagnosticLocation = Regex("${Regex.escape(name)}:(\\d+):\\d+:")
        listOf("legacy" to legacy.code, "tree-sitter" to experimentalC.code).forEach { (frontend, generatedC) ->
            val compile = ProcessBuilder(compiler, "-std=c11", "-fsyntax-only", "-x", "c", "-")
                .redirectErrorStream(true)
                .start()
            compile.outputStream.bufferedWriter().use { it.write(generatedC) }
            val compilerOutput = compile.inputStream.bufferedReader().use { it.readText() }
            assertTrue(compile.waitFor() != 0, "$frontend unexpectedly compiled invalid member access:\n$generatedC")
            val reportedLine = diagnosticLocation.find(compilerOutput)?.groupValues?.get(1)?.toIntOrNull()
            assertEquals(
                expectedLine,
                reportedLine,
                "$frontend diagnostic must map to $name:$expectedLine:\n$compilerOutput"
            )
            assertTrue("missing" in compilerOutput, "$frontend diagnostic lost the invalid field name:\n$compilerOutput")
        }
    }

    @Test
    fun astCEmitterWritesNormalizedTokensWithOriginsAndVerbatimPreprocessorRegions() {
        val text = """#include <stddef.h>
#define CPLUS_ANSWER() 42
int main ( void ) { int values[3]={40,1,1}; int value=values[0]+2; // token-emitted comment
    for (int i=0;i<3;i++) value += i;
    return value==CPLUS_ANSWER()+3?0:1;
}"""
        val source = sources.open(SourceId.named("ast-c-emitter.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val ast = CPlusAstAdapter().adapt(parsed)

        val emission = CPlusAstCEmitter().emit(ast, MappedText.identity(source.sourceFile))

        assertTrue(emission.diagnostics.isEmpty(), emission.diagnostics.toString())
        val generated = emission.source ?: error("complete AST should emit C")
        assertTrue("int main(void)" in generated.text, generated.text)
        assertTrue("#define CPLUS_ANSWER() 42" in generated.text, generated.text)
        assertTrue("// token-emitted comment" in generated.text, generated.text)
        assertFalse("int main ( void )" in generated.text, generated.text)
        assertTrue("int main(void) {\n    int values[3] = {" in generated.text, generated.text)
        assertTrue("int value = values[0] + 2;" in generated.text, generated.text)
        assertTrue("for (int i = 0; i < 3; i ++)" in generated.text, generated.text)
        assertTrue("return value == CPLUS_ANSWER() + 3 ? 0 : 1;" in generated.text, generated.text)
        val generatedMacroUse = generated.text.indexOf("CPLUS_ANSWER", generated.text.indexOf("int main"))
        assertEquals(text.indexOf("CPLUS_ANSWER", text.indexOf("int main")), generated.originAt(generatedMacroUse)?.offset)
        val reparsed = backend.parse(sources.open(SourceId.named("ast-c-emitter-output.c"), generated.text))
        assertTrue(reparsed.diagnostics.isEmpty(), reparsed.diagnostics.toString())
        fun terminalSpellings(root: CPlusSyntaxNode, sourceText: String, into: MutableList<String>) {
            if (root.kind in setOf("preproc_if", "preproc_ifdef", "preproc_include", "preproc_def", "preproc_function_def", "preproc_call")) {
                into += sourceText.substring(root.span.startOffset, root.span.endOffset)
            } else if (root.children.isEmpty()) {
                if (root.span.endOffset > root.span.startOffset) {
                    into += sourceText.substring(root.span.startOffset, root.span.endOffset)
                }
            } else {
                root.children.sortedBy { it.span.startOffset }.forEach { terminalSpellings(it, sourceText, into) }
            }
        }
        val originalTokens = mutableListOf<String>()
        val emittedTokens = mutableListOf<String>()
        terminalSpellings(parsed.root, text, originalTokens)
        terminalSpellings(reparsed.root, generated.text, emittedTokens)
        assertEquals(originalTokens, emittedTokens, generated.text)
        compileAndRunC(generated.text)
    }

    @Test
    fun astCEmitterRejectsRecoveredSyntaxInsteadOfEmittingPartialC() {
        val source = sources.open(SourceId.named("incomplete-ast-emitter.cp"), "int main( {")
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isNotEmpty(), "fixture must contain parser recovery")

        val emission = CPlusAstCEmitter().emit(
            CPlusAstAdapter().adapt(parsed),
            MappedText.identity(source.sourceFile)
        )

        assertEquals(null, emission.source)
        assertEquals("CPLUS_EMIT_INCOMPLETE_AST", emission.diagnostics.single().code)
    }

    @Test
    fun astCEmitterRejectsUnmaterializedComptimeNodes() {
        val source = sources.open(SourceId.named("unlowered-ast-emitter.cp"), "comptime int @generated_value = 0;")
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val emission = CPlusAstCEmitter().emit(
            CPlusAstAdapter().adapt(parsed),
            MappedText.identity(source.sourceFile)
        )

        assertEquals(null, emission.source)
        assertEquals("CPLUS_EMIT_UNLOWERED_CONSTRUCT", emission.diagnostics.single().code)
        assertEquals(source.id.value, emission.diagnostics.single().span.file)
    }

    @Test
    fun extractsMethodsAndCompilesTheResultingPlainCWithSystemCcWhenAvailable() {
        val text = """
            typedef struct counter_t {
                int value;
                pub int increment(borrowed mut *self, int amount) {
                    self->value += amount;
                    return self->value;
                }
                static pub int zero(void) { return 0; }
                static pub counter_t *create(void) { return 0; }
                pub int read(borrowed *self);
            } counter_t;
            int main(void) {
                counter_t counter = {0};
                counter_t *pointer = &counter;
                return counter.increment(2) == 2 && pointer->increment(0) == 2 && counter_t.zero() == 0 ? 0 : 1;
            }
        """.trimIndent()
        val firstSnapshot = sources.open(SourceId.named("method-pipeline.cp"), text)
        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(firstSnapshot)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val methods = result.cSource!!
        assertTrue(methods.text.contains("CPLUS_PUB int counter__increment(CPLUS_BORROWED CPLUS_MUT counter_t *self, int amount)"), methods.text)
        assertTrue(methods.text.contains("static CPLUS_PUB int counter__zero(void)"), methods.text)
        assertTrue(methods.text.contains("static CPLUS_PUB counter_t *counter__create(void)"), methods.text)
        assertTrue(methods.text.contains("CPLUS_PUB int counter__read(CPLUS_BORROWED counter_t *self);"), methods.text)
        assertTrue(methods.text.contains("counter__increment(&counter, 2)"), methods.text)
        assertTrue(methods.text.contains("counter__increment(pointer, 0)"), methods.text)
        assertTrue(methods.text.contains("counter__zero()"), methods.text)
        assertFalse(Regex("struct counter_t \\{[^}]*increment").containsMatchIn(methods.text))
        assertEquals(text.indexOf("increment"), methods.originAt(methods.text.indexOf("counter__increment"))?.offset)

        val cc = runCatching { ProcessBuilder("cc", "--version").start().waitFor() == 0 }.getOrDefault(false)
        if (cc) {
            val temporaryDirectory = Files.createTempDirectory("cplus-tree-sitter-c")
            try {
                val executableName = "prototype" + if (System.getProperty("os.name").startsWith("Windows", true)) ".exe" else ""
                val executable = temporaryDirectory.resolve(executableName)
                val process = ProcessBuilder("cc", "-std=c11", "-x", "c", "-", "-o", executable.toString()).start()
                process.outputStream.bufferedWriter().use {
                    it.write(methods.text)
                }
                val errors = process.errorStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), errors + "\n" + methods.text)
                val run = ProcessBuilder(executable.toString()).start()
                assertEquals(0, run.waitFor(), run.errorStream.bufferedReader().use { it.readText() })
            } finally {
                deleteRecursively(temporaryDirectory)
            }
        }
    }

    @Test
    fun diagnosesInstanceMethodsWithoutTheRequiredSelfReceiver() {
        val text = "typedef struct invalid_t { pub int method(int value); } invalid_t;"
        val snapshot = sources.open(SourceId.named("invalid-receiver.cp"), text)
        val ast = CPlusAstAdapter().adapt(backend.parse(snapshot))

        val result = CPlusStructMethodLoweringPass().lower(ast, MappedText.identity(snapshot.sourceFile))

        assertEquals("CPLUS_METHOD_RECEIVER_NAME", result.diagnostics.single().code)
        assertEquals(text, result.source.text)
    }

    @Test
    fun diagnosesStaticAndInstanceCallsWithTheWrongReceiverKind() {
        val text = """
            typedef struct counter_t {
                pub int get(borrowed *self);
                static pub counter_t* create(void);
            } counter_t;
            int main(void) {
                counter_t value;
                value.create();
                counter_t.get();
                value.get();
                counter_t.create();
                counter_t counter_t;
                counter_t.create();
                return 0;
            }
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("wrong-receiver-kind.cp"), text)
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val index = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertEquals(
            listOf(
                "CPLUS_STATIC_METHOD_REQUIRES_TYPE_RECEIVER",
                "CPLUS_INSTANCE_METHOD_REQUIRES_VALUE_RECEIVER",
                "CPLUS_STATIC_METHOD_REQUIRES_TYPE_RECEIVER"
            ),
            index.diagnostics.map { it.code }
        )
        assertEquals(listOf("value.create()", "counter_t.get()", "counter_t.create()"), index.diagnostics.map {
            text.substring(it.span.startOffset, it.span.endOffset)
        })
        assertEquals(listOf("get", "create"), index.resolvedCalls.map { it.methodName })

        val prototype = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(snapshot)
        assertFalse(prototype.successful)
        assertEquals(index.diagnostics.map { it.code }, prototype.loweringDiagnostics.map { it.code })
        assertEquals(listOf("value.create()", "counter_t.get()", "counter_t.create()"), prototype.loweringDiagnostics.map {
            text.substring(it.span.startOffset, it.span.endOffset)
        })
    }

    @Test
    fun validatesMethodAccessOperatorsAndSinglePointerReceiverDepth() {
        val text = """
            typedef struct receiver_t {
                int value;
                pub int read(borrowed *self) { return self->value; }
            } receiver_t;
            int use_receivers(receiver_t value, receiver_t *pointer, receiver_t **pointer_pointer) {
                int total = value.read() + pointer->read() + (&value).read() + (*pointer).read() +
                    (*pointer_pointer)->read();
                pointer.read();
                value->read();
                pointer_pointer->read();
                return total;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("receiver-access-operators.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val index = CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed))

        assertEquals(5, index.resolvedCalls.size, "valid value, pointer, explicit-address, dereference, and pointer-to-pointer-dereference calls should resolve")
        assertEquals(
            listOf(
                "CPLUS_METHOD_RECEIVER_ACCESS_OPERATOR",
                "CPLUS_METHOD_RECEIVER_ACCESS_OPERATOR",
                "CPLUS_METHOD_RECEIVER_POINTER_DEPTH"
            ),
            index.diagnostics.map { it.code }
        )
        assertEquals(
            listOf("pointer.read()", "value->read()", "pointer_pointer->read()"),
            index.diagnostics.map { text.substring(it.span.startOffset, it.span.endOffset) }
        )

        val prototype = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
        assertFalse(prototype.successful)
        assertEquals(index.diagnostics.map { it.code }, prototype.loweringDiagnostics.map { it.code })
        assertEquals(
            index.diagnostics.map { it.span },
            prototype.loweringDiagnostics.map { it.span },
            "receiver diagnostics must retain their original C-plus spans"
        )
    }

    @Test
    fun prototypeMaterializesScalarComptimeAndExtractsTestFixtures() {
        val source = sources.open(
            SourceId.named("prototype-unsupported.cp"),
            "comptime int answer = 42; @test answer { return 0; }"
        )

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        assertEquals("answer", result.testFixtures.single().name)
        assertFalse("comptime" in result.cSource?.text.orEmpty())
        assertFalse(result.unsupportedNodes.any { it.syntaxKind == "cplus_comptime_value" })
    }

    @Test
    fun prototypeMaterializesScalarExpressionsInsideExtractedTests() {
        val text = """
            comptime int answer = 40 + 2;
            @test "scalar value in fixture" {
                int observed = comptime answer;
                @assertEquals(42, observed);
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-test-fixture.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val fixture = result.testFixtures.single()
        assertFalse("comptime" in fixture.body.text, fixture.body.text)
        assertTrue("int observed = 42;" in fixture.body.text, fixture.body.text)
        val generatedValue = fixture.body.text.indexOf("42")
        assertEquals(text.indexOf("comptime answer"), fixture.body.originAt(generatedValue)?.offset)
        val testProgram = CPlusTranspiler().transpileExtractedTests(result.cSource!!, result.testFixtures)
        compileAndRunC(testProgram.source.code)
    }

    @Test
    fun prototypeExtractsNamedTestsWithoutEmittingThemIntoProgramC() {
        val text = """
            int main(void) { return 0; }
            @test "string fixture" { int value = 42; const char *literal = "@assert(fake)"; @assert(value == 42); @assertEquals(42, value); }
            @test identifier_fixture { return; }
        """.trimIndent()
        val source = sources.open(SourceId.named("tests-extraction.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        assertEquals(listOf("string fixture", "identifier_fixture"), result.testFixtures.map { it.name })
        assertTrue(result.cSource!!.text.contains("int main(void)"), result.cSource.text)
        assertFalse(result.cSource.text.contains("string fixture"), result.cSource.text)
        assertFalse(result.cSource.text.contains("identifier_fixture"), result.cSource.text)
        val fixtureBody = result.testFixtures.first().body
        assertTrue(fixtureBody.text.contains("value = 42"), fixtureBody.text)
        assertEquals(text.indexOf("int value"), fixtureBody.originAt(fixtureBody.text.indexOf("int value"))?.offset)
        assertTrue(result.testFixtures.all { it.span.file == source.id.value })
        assertEquals(2, result.testFixtures.first().assertions.size)
        assertEquals(listOf("value == 42"), result.testFixtures.first().assertions.first().arguments)
        assertEquals(listOf("42", "value"), result.testFixtures.first().assertions.last().arguments)

        val testProgram = cplus.CPlusTranspiler().transpileExtractedTests(result.cSource!!, result.testFixtures)
        assertEquals(listOf("string fixture", "identifier_fixture"), testProgram.testNames)
        assertTrue(testProgram.source.code.contains("========== BEGIN TEST"), testProgram.source.code)
        assertTrue(testProgram.source.code.contains("CPLUS_TEST_ASSERT_AT(1, 2, value == 42)"), testProgram.source.code)
        assertTrue(testProgram.source.code.contains("CPLUS_TEST_ASSERT_EQUALS_AT(2, 2, 42, value)"), testProgram.source.code)
        compileAndRunC(testProgram.source.code)
    }

    @Test
    fun legacyAndTreeSitterTestDiscoveryProduceTheSameRunnableFixtures() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val text = """
            #include <stdio.h>
            typedef struct score_t {
                int value;
                pub void add(borrowed mut *self, int amount) { self->value += amount; }
            } score_t;
            int main(void) { return 0; }
            @test "method fixture" {
                score_t score = {40};
                score.add(2);
                @assertEquals(42, score.value);
                @assert(score.value > 0);
            }
            @test second_fixture {
                const char *text = "@assert(ignored)";
                @assertEquals(5, 2 + 3);
            }
        """.trimIndent()
        val name = "differential-test-discovery.cp"
        val source = sources.open(SourceId.named(name), text)
        val legacy = CPlusTranspiler().transpileTests(text, name)
        val experimental = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            experimental.successful,
            "parser=${experimental.parserDiagnostics}; lowering=${experimental.loweringDiagnostics}; " +
                "unsupported=${experimental.unsupportedNodes}"
        )
        val treeSitterC = experimental.cSource ?: error("prototype has no mapped C output")
        val treeSitterTests = CPlusTranspiler().transpileExtractedTests(treeSitterC, experimental.testFixtures)
        val firstFixture = experimental.testFixtures.first()
        val methodCallOffset = text.indexOf("score.add(2)")
        val methodNameOffset = text.indexOf("add(2)", methodCallOffset)
        val assertionOffset = text.indexOf("@assertEquals(42, score.value)")
        val loweredMethodCallOffset = firstFixture.body.text.indexOf("score__add(&score, 2)")
        assertTrue(loweredMethodCallOffset >= 0, firstFixture.body.text)
        assertEquals(methodNameOffset, firstFixture.body.originAt(loweredMethodCallOffset)?.offset)
        assertEquals(name, firstFixture.assertions.first().span.file)
        assertEquals(assertionOffset, firstFixture.assertions.first().span.startOffset)
        val generatedTestCode = treeSitterTests.source.code
        val generatedAssertionOffset = generatedTestCode.indexOf("CPLUS_TEST_ASSERT_EQUALS_AT(1, 2, 42, score.value)")
        assertTrue(generatedAssertionOffset >= 0, generatedTestCode)
        val generatedAssertionLine = generatedTestCode.take(generatedAssertionOffset).count { it == '\n' } + 1
        val expectedAssertionLine = text.take(assertionOffset).count { it == '\n' } + 1
        assertEquals(
            expectedAssertionLine,
            treeSitterTests.source.sourceMap.sourceForGeneratedLine(generatedAssertionLine)?.startLine
        )
        assertEquals(legacy.testNames, treeSitterTests.testNames)
        assertEquals(listOf("method fixture", "second_fixture"), treeSitterTests.testNames)
        assertEquals(legacy.fixtures.map { it.assertionCount }, treeSitterTests.fixtures.map { it.assertionCount })
        assertEquals(listOf(2, 1), treeSitterTests.fixtures.map { it.assertionCount })

        val differential = TreeSitterCPlusDifferentialRunner(backend, sources).compareTests(source)
        assertTrue(differential.harnessTokensMatch, differential.harnessTokenDifference.orEmpty())
        assertTrue(differential.assertionSourceLinesMatch, differential.toString())

        val legacyRun = compileAndCaptureC(compiler, legacy.source.code)
        val treeSitterRun = compileAndCaptureC(compiler, treeSitterTests.source.code)
        assertEquals(0, legacyRun.first, "legacy test harness failed: ${legacyRun.second}")
        assertEquals(0, treeSitterRun.first, "Tree-sitter test harness failed: ${treeSitterRun.second}")
        assertEquals(legacyRun, treeSitterRun)
        assertTrue(legacyRun.second.contains("TEST SUMMARY: 2 selected, 0 failed"), legacyRun.second)
    }

    @Test
    fun testHarnessBodyParityCoversDeferAndCheckedCalls() {
        val text = """
            #include <stdio.h>
            typedef int error_t;
            enum { ERROR_BAD = 2 };
            typedef struct state_t {
                int total;
                pub void add(borrowed mut *self, int amount) { self->total += amount; }
            } state_t;
            @throws() pub error_t update(int input, borrowed mut int *out) {
                if (input < 0) return ERROR_BAD;
                *out = input;
                return 0;
            }
            int main(void) { return 0; }
            @test "composed fixture" {
                state_t state = {0};
                int value = 0;
                defer { state.total += 1; }
                @try {
                    state.add(4);
                    update(3, &value);
                }
                @catch (error_t error) { return error; }
                @assertEquals(4, state.total);
                @assertEquals(3, value);
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("differential-test-composed.cp"), text)
        val report = TreeSitterCPlusDifferentialRunner(backend, sources).compareTests(source)

        assertTrue(report.successful, report.toString() + "\n" + report.harnessTokenDifference.orEmpty())
        assertTrue(report.harnessTokensMatch, report.harnessTokenDifference.orEmpty())
        assertTrue(report.assertionSourceLinesMatch, report.toString())
    }

    @Test
    fun testHarnessBodyParityCoversNestedControlFlowAssertions() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val text = """
            int main(void) { return 0; }
            @test "nested control flow" {
                int score = 0;
                if (1) {
                    score += 1;
                    @assert(score == 1);
                }
                for (int index = 0; index < 2; index++) {
                    score += 1;
                }
                @assertEquals(3, score);
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("nested-control-flow-test.cp"), text)
        val report = TreeSitterCPlusDifferentialRunner(backend, sources).compareTests(source)

        assertTrue(report.successful, report.toString() + "\n" + report.harnessTokenDifference.orEmpty())
        assertEquals(listOf("nested control flow"), report.treeSitter.testFixtures.map { it.name })
        assertEquals(listOf(2), report.treeSitter.testFixtures.map { it.assertions.size })
        assertTrue(report.harnessTokensMatch, report.harnessTokenDifference.orEmpty())
        val astHarness = report.astHarness ?: error("AST harness was not generated")
        val run = compileAndCaptureC(compiler, astHarness.source.code)
        assertEquals(0, run.first, "nested-control-flow AST harness failed: ${run.second}")
        assertTrue("TEST SUMMARY: 1 selected, 0 failed" in run.second, run.second)
    }

    @Test
    fun astTestHarnessRunsDeferAfterFailedAssertion() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val text = """
            #include <stdio.h>
            int main(void) { return 0; }
            @test "failure cleanup" {
                defer puts("cleanup");
                @assert(0);
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-test-defer-failure.cp"), text)
        val report = TreeSitterCPlusDifferentialRunner(backend, sources).compareTests(source)
        assertTrue(report.successful, report.toString() + "\n" + report.harnessTokenDifference.orEmpty())
        val astHarness = report.astHarness ?: error("AST harness was not generated")
        val astRun = compileAndCaptureC(compiler, astHarness.source.code)
        assertEquals(1, astRun.first, "a failed assertion must fail the AST fixture: ${astRun.second}")
        assertTrue("cleanup" in astRun.second, "defer cleanup must run after assertion failure: ${astRun.second}")
    }

    @Test
    fun prototypeDiagnosesBlankTestFixtureNamesAtTheirSourceLocation() {
        val text = "@test \"\" { return; }"
        val source = sources.open(SourceId.named("blank-test-name.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful)
        assertEquals("CPLUS_TEST_NAME", result.loweringDiagnostics.single().code)
        assertEquals(source.id.value, result.loweringDiagnostics.single().span.file)
        assertTrue(result.testFixtures.isEmpty())
    }

    @Test
    fun prototypeDiagnosesMalformedAssertionArgumentsAtTheirSourceLocation() {
        val text = "@test \"bad assertion\" { @assertEquals(1); }"
        val source = sources.open(SourceId.named("bad-test-assertion.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful)
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_TEST_ASSERT_ARGUMENTS", diagnostic.code)
        assertEquals(text.indexOf("@assertEquals"), diagnostic.span.startOffset)
        assertEquals(source.id.value, diagnostic.span.file)
    }

    @Test
    fun prototypeExtractsThrowsConventionsAndEmitsAnnotatedFunctionsAsC() {
        val text = """
            typedef int error_t;
            @throws() pub error_t status(void);
            @throws() pub error_t status(void) { return 0; }
            typedef struct counter_t {
                @throws(error) pub int load(borrowed mut *self, borrowed mut error_t *error) {
                    *error = 0;
                    return 9;
                }
            } counter_t;
            int main(void) {
                counter_t counter = {0};
                error_t error = -1;
                return status() != 0 || counter.load(&error) != 9 || error != 0;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("throws-prototype.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        assertFalse("@throws" in result.cSource!!.text, result.cSource.text)
        assertEquals(cplus.CPlusThrowsConvention.ERROR_RETURN, result.throwsFunctions["status"]?.convention)
        assertEquals(cplus.CPlusThrowsConvention.ERROR_OUT_PARAMETER, result.throwsFunctions["counter__load"]?.convention)
        assertEquals("error", result.throwsFunctions["counter__load"]?.errorParameterName)
        compileAndRunC(result.cSource.text)
    }

    @Test
    fun astThrowsPassRejectsInvalidReturnAndErrorOutSignatures() {
        val text = """
            typedef int error_t;
            @throws() pub int invalid_return(void);
            @throws(error) pub int invalid_out(int value, borrowed mut int *error);
        """.trimIndent()
        val source = sources.open(SourceId.named("bad-throws.cp"), text)
        val ast = CPlusAstAdapter().adapt(backend.parse(source))

        val result = cplus.CPlusThrowsLoweringPass().lower(ast, MappedText.identity(source.sourceFile))

        assertEquals(
            setOf("CPLUS_THROWS_RETURN_TYPE", "CPLUS_THROWS_ERROR_PARAMETER"),
            result.diagnostics.map { it.code }.toSet()
        )
        assertEquals(text, result.source.text)
    }

    @Test
    fun prototypeLowersCheckedCallsAndNestedTryCatchWithRuntimeErrorPropagation() {
        val text = """
            typedef int error_t;
            enum { ERROR_NONE = 0, ERROR_BAD = 1, ERROR_IO = 2 };
            @throws() pub error_t check(error_t code) { return code; }
            @throws(error) pub int make(int value, borrowed mut error_t *error) {
                *error = value < 0 ? ERROR_IO : ERROR_NONE;
                return value;
            }
            typedef struct meter_t {
                @throws(error) pub int read(borrowed mut *self, borrowed mut error_t *error) {
                    *error = ERROR_IO;
                    return 11;
                }
            } meter_t;
            int main(void) {
                int value = 0;
                @try {
                    value = make(7);
                    /* retained trivia inside the lowered try body */
                    check(ERROR_BAD);
                    value = 99;
                }
                @catch (ERROR_BAD, error_t error) { value = error; }
                @catch (error_t error) { return 3; }
                if (value != ERROR_BAD) return 4;

                @try {
                    @try { check(ERROR_IO); }
                    @catch (ERROR_BAD, error_t nested) { return 5; }
                    value = make(9);
                }
                @catch (ERROR_IO, error_t outer) { value = outer; }
                @catch (error_t error) { return 6; }
                if (value != ERROR_IO) return 7;

                meter_t meter = {0};
                @try { value = meter.read(); }
                @catch (ERROR_IO, error_t method_error) { value = method_error; }
                @catch (error_t error) { return 8; }
                return value == ERROR_IO ? 0 : 9;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("try-catch-prototype.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        assertFalse("@try" in result.cSource!!.text, result.cSource.text)
        assertFalse("@catch" in result.cSource.text, result.cSource.text)
        assertTrue(result.cSource.text.contains("goto cplus_catch_"), result.cSource.text)
        val preservedComment = "/* retained trivia inside the lowered try body */"
        val generatedComment = result.cSource.text.indexOf(preservedComment)
        assertTrue(generatedComment >= 0, result.cSource.text)
        assertEquals(text.indexOf(preservedComment), result.cSource.originAt(generatedComment)?.offset)
        val preservedCatchStatement = "value = error;"
        val generatedCatchStatement = result.cSource.text.indexOf(preservedCatchStatement)
        assertTrue(generatedCatchStatement >= 0, result.cSource.text)
        assertEquals(text.indexOf(preservedCatchStatement), result.cSource.originAt(generatedCatchStatement)?.offset)
        compileAndRunC(result.cSource.text)
    }

    @Test
    fun astTryLoweringRejectsEmbeddedCheckedCallExpressionsWithOriginalSpan() {
        val text = """
            typedef int error_t;
            @throws() pub error_t checked(void) { return 0; }
            int main(void) {
                @try { if (checked()) return 1; }
                @catch (error_t error) { return 0; }
                return 0;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("unsupported-checked-expression.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_TRY_CALL_CONTEXT", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertTrue(text.substring(diagnostic.span.startOffset, diagnostic.span.endOffset).contains("checked()"))
    }

    @Test
    fun astTestFixtureRejectsUnsupportedCheckedExpressionAtTheFixtureSourceSpan() {
        val text = """
            typedef int error_t;
            @throws() pub error_t checked(void) { return 0; }
            int main(void) { return 0; }
            @test "unsupported checked expression" {
                @try { if (checked()) return; }
                @catch (error_t error) { return; }
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("unsupported-checked-test-expression.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful, "unsupported fixture body must fail closed")
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_TRY_CALL_CONTEXT", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("checked()"), diagnostic.span.startOffset)
        assertTrue(text.substring(diagnostic.span.startOffset, diagnostic.span.endOffset).contains("checked()"))
        assertTrue(result.testFixtures.isEmpty(), "a rejected fixture must not be materialized into a harness")
        assertNull(result.transcodedSource, "a rejected fixture must not emit C")
    }

    @Test
    fun astTestFixtureRejectsEveryUnsupportedCheckedCallStatementShapeAtItsCallSpan() {
        val bodies = listOf(
            "int value = checked();",
            "return checked();",
            "checked() + 1;"
        )

        bodies.forEachIndexed { index, body ->
            val text = """
                typedef int error_t;
                @throws() pub error_t checked(void) { return 0; }
                int main(void) { return 0; }
                @test "unsupported checked shape $index" {
                    @try { $body }
                    @catch (error_t error) { return; }
                }
            """.trimIndent()
            val source = sources.open(SourceId.named("unsupported-checked-test-shape-$index.cp"), text)

            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

            assertFalse(result.successful, "unsupported fixture shape unexpectedly succeeded: $body")
            val diagnostic = result.loweringDiagnostics.single()
            assertEquals("CPLUS_TRY_CALL_CONTEXT", diagnostic.code)
            assertEquals(source.id.value, diagnostic.span.file)
            val callOffset = text.indexOf("checked()")
            assertEquals(callOffset, diagnostic.span.startOffset, "wrong mapped span for: $body")
            assertTrue(result.testFixtures.isEmpty(), "rejected fixture must not be extracted: $body")
            assertNull(result.transcodedSource, "rejected fixture must not emit C: $body")
        }
    }

    private fun requiresRaylib(code: String, compilerOptions: List<String>): Boolean =
        Regex("#\\s*include\\s*[<\\\"](?:raylib|raymath|rlgl)\\.h[>\\\"]").containsMatchIn(code) ||
            compilerOptions.any { it == "-lraylib" || it == "raylib" }

    private fun raylibIsAvailable(compilerOptions: List<String>): Boolean {
        val compiler = cplusTestCompilers(listOf("clang")).firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return false
        val temporaryDirectory = Files.createTempDirectory("cplus-raylib-probe")
        try {
            val executable = temporaryDirectory.resolve(
                "raylib-probe" + if (System.getProperty("os.name").startsWith("Windows", true)) ".exe" else ""
            )
            val command = buildList {
                addAll(listOf(compiler, "-std=c11", "-x", "c", "-"))
                addAll(compilerOptions)
                addAll(listOf("-o", executable.toString()))
            }
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use {
                it.write("#include <raylib.h>\nint main(void) { return 0; }\n")
            }
            process.inputStream.bufferedReader().use { it.readText() }
            return process.waitFor() == 0
        } catch (_: Exception) {
            return false
        } finally {
            deleteRecursively(temporaryDirectory)
        }
    }

    private fun cplusTestCompilers(defaults: List<String>): List<String> {
        val configured = System.getenv("CPLUS_TEST_COMPILER")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.takeIf { it.isNotEmpty() }
        return configured ?: defaults
    }

    private fun hostTargetOs(): String = when {
        System.getProperty("os.name").startsWith("Windows", true) -> "windows"
        System.getProperty("os.name").startsWith("Mac", true) -> "macos"
        else -> "linux"
    }

    private fun hostTargetArch(): String = when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "arm64"
        else -> "x86_64"
    }

    private fun compileAndCaptureC(
        compiler: String,
        code: String,
        compilerOptions: List<String> = emptyList(),
        expectCompileSuccess: Boolean = true
    ): Pair<Int, String> {
        val temporaryDirectory = Files.createTempDirectory("cplus-frontend-differential")
        try {
            val executable = temporaryDirectory.resolve(
                "parity" + if (System.getProperty("os.name").startsWith("Windows", true)) ".exe" else ""
            )
            val compile = ProcessBuilder(
                listOf(compiler) + compilerOptions + listOf("-std=c11", "-x", "c", "-", "-o", executable.toString())
            ).redirectErrorStream(true).start()
            compile.outputStream.bufferedWriter().use { it.write(code) }
            val compilerOutput = compile.inputStream.bufferedReader().use { it.readText() }
            val compileStatus = compile.waitFor()
            if (compileStatus != 0) {
                if (expectCompileSuccess) assertEquals(0, compileStatus, "$compiler rejected differential C:\n$compilerOutput\n$code")
                else return compileStatus to compilerOutput
            }

            val run = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
            val runtimeOutput = run.inputStream.bufferedReader().use { it.readText() }
                .replace("\r\n", "\n")
            return run.waitFor() to runtimeOutput
        } finally {
            deleteRecursively(temporaryDirectory)
        }
    }

    private fun cTokenSpellings(code: String, sourceName: String): List<String> {
        // #line is compiler-facing mapping metadata, not part of the generated C token stream.
        // Tree-sitter's grammar can recover differently when these directives split a declarator
        // or initializer, so remove only those directives before lexical-token comparison.
        val syntaxCode = code.replace(Regex("(?m)^[ \\t]*#line[^\\r\\n]*(?:\\r?\\n|$)"), "")
        val snapshot = sources.open(SourceId.named(sourceName), syntaxCode)
        val parsed = backend.parse(snapshot)
        if (parsed.diagnostics.isNotEmpty()) {
            val snippets = parsed.diagnostics.map { diagnostic ->
                val start = (diagnostic.span.startOffset - 80).coerceAtLeast(0)
                val end = (diagnostic.span.endOffset + 80).coerceAtMost(syntaxCode.length)
                "${diagnostic.span.startLine}:${diagnostic.span.startColumn}: " + syntaxCode.substring(start, end)
            }
            assertTrue(false, "$sourceName parser diagnostics: ${parsed.diagnostics}\n${snippets.joinToString("\n---\n")}")
        }
        val preprocessorNodes = setOf(
            "preproc_if", "preproc_ifdef", "preproc_include", "preproc_def", "preproc_function_def", "preproc_call"
        )
        return buildList {
            fun visit(node: CPlusSyntaxNode) {
                if (node.kind in preprocessorNodes) {
                    add(syntaxCode.substring(node.span.startOffset, node.span.endOffset))
                } else if (node.kind == "comment") {
                    return
                } else if (node.children.isEmpty()) {
                    if (node.span.endOffset > node.span.startOffset) {
                        add(syntaxCode.substring(node.span.startOffset, node.span.endOffset))
                    }
                } else {
                    node.children.sortedBy { it.span.startOffset }.forEach(::visit)
                }
            }
            visit(parsed.root)
        }
    }

    private fun compileAndRunC(
        code: String,
        includeDirectories: List<Path> = emptyList(),
        compilerOptions: List<String> = emptyList()
    ) {
        val compilers = cplusTestCompilers(listOf("cc", "gcc", "clang")).distinct().filter { compiler ->
            runCatching { ProcessBuilder(compiler, "--version").start().waitFor() == 0 }.getOrDefault(false)
        }
        if (compilers.isEmpty()) return
        compilers.forEach { compiler ->
            val temporaryDirectory = Files.createTempDirectory("cplus-tree-sitter-c-compat")
            try {
                val executableName = "compat-${compiler}" +
                    if (System.getProperty("os.name").startsWith("Windows", true)) ".exe" else ""
                val executable = temporaryDirectory.resolve(executableName)
                val command = buildList {
                    addAll(listOf(compiler, "-std=c11", "-x", "c", "-"))
                    addAll(compilerOptions)
                    includeDirectories.forEach { directory -> addAll(listOf("-I", directory.toString())) }
                    addAll(listOf("-o", executable.toString()))
                }
                val compile = ProcessBuilder(command).redirectErrorStream(true).start()
                compile.outputStream.bufferedWriter().use { it.write(code) }
                val output = compile.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, compile.waitFor(), "$compiler rejected C11 compatibility fixture:\n$output\n$code")
                val run = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
                val runtimeOutput = run.inputStream.bufferedReader().use { it.readText() }
                    .replace("\r\n", "\n")
                assertEquals(0, run.waitFor(), "$compiler-built fixture failed:\n$runtimeOutput\n$code")
            } finally {
                deleteRecursively(temporaryDirectory)
            }
        }
    }

    private fun compileCOnlyIfAvailable(compiler: String, code: String) {
        if (runCatching { ProcessBuilder(compiler, "--version").start().waitFor() != 0 }.getOrDefault(true)) return
        val temporaryDirectory = Files.createTempDirectory("cplus-tree-sitter-cross-c")
        try {
            val objectFile = temporaryDirectory.resolve("compat.o")
            val compile = ProcessBuilder(
                compiler, "-std=c11", "-x", "c", "-c", "-", "-o", objectFile.toString()
            ).redirectErrorStream(true).start()
            compile.outputStream.bufferedWriter().use { it.write(code) }
            val output = compile.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, compile.waitFor(), "$compiler rejected cross-target C11 fixture:\n$output\n$code")
        } finally {
            deleteRecursively(temporaryDirectory)
        }
    }

    private fun validateCCompiles(
        code: String,
        includeDirectories: List<Path>,
        compilerOptions: List<String>,
        context: String
    ) {
        val compiler = cplusTestCompilers(listOf("cc", "gcc", "clang")).firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val command = buildList {
            addAll(listOf(compiler, "-std=c11", "-fsyntax-only", "-x", "c", "-"))
            addAll(compilerOptions)
            includeDirectories.forEach { directory -> addAll(listOf("-I", directory.toString())) }
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(code) }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), "$compiler rejected AST-generated C for $context:\n$output\n$code")
    }

    private fun validateCCompilesWithAvailableDrivers(
        code: String,
        includeDirectories: List<Path>,
        compilerOptions: List<String>,
        context: String
    ) {
        val compilers = cplusTestCompilers(listOf("cc", "gcc", "clang")).distinct().filter { compiler ->
            runCatching { ProcessBuilder(compiler, "--version").start().waitFor() == 0 }.getOrDefault(false)
        }
        if (compilers.isEmpty()) return
        compilers.forEach { compiler ->
            val command = buildList {
                addAll(listOf(compiler, "-std=c11", "-fsyntax-only", "-x", "c", "-"))
                addAll(compilerOptions)
                includeDirectories.forEach { directory -> addAll(listOf("-I", directory.toString())) }
            }
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(code) }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor(), "$compiler rejected AST-generated C for $context:\n$output\n$code")
        }
    }

    private fun assertC11Syntax(code: String, label: String) {
        val compilers = cplusTestCompilers(listOf("cc", "gcc", "clang")).distinct().filter { compiler ->
            runCatching { ProcessBuilder(compiler, "--version").start().waitFor() == 0 }.getOrDefault(false)
        }
        if (compilers.isEmpty()) return
        compilers.forEach { compiler ->
            val process = ProcessBuilder(compiler, "-std=c11", "-fsyntax-only", "-x", "c", "-")
                .redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(code) }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor(), "$compiler rejected prototype output for $label:\n$output")
        }
    }

    @Test
    fun prototypeLoweringDiagnosticsMapBackToOriginalSourceAfterReparse() {
        val text = "typedef struct invalid_t { pub int method(int value); } invalid_t;"
        val source = sources.open(SourceId.named("mapped-diagnostic.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_METHOD_RECEIVER_NAME", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertTrue(diagnostic.span.startOffset in text.indices)
        assertTrue(text.substring(diagnostic.span.startOffset, diagnostic.span.endOffset).contains("int value"))
    }

    @Test
    fun comptimeIndexRetainsNestedGeneratorOwnershipAndDefersInnerDeclarations() {
        val text = """
            comptime code @outer() {
                return @code {
                    comptime code @inner() { return @code { int generated_value; }; }
                    comptime inner();
                };
            }
            comptime outer();
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("nested-comptime-index.cp"), text)
        val parsed = backend.parse(snapshot)

        assertTrue(parsed.diagnostics.isEmpty(), "diagnostics=${parsed.diagnostics}; tree=${parsed.root}")
        val index = CPlusComptimeIndexer().index(CPlusAstAdapter().adapt(parsed))
        val outer = index.constructs.single { it.symbol == "outer" && it.syntaxKind == "cplus_comptime_function_definition" }
        val inner = index.constructs.single { it.symbol == "inner" && it.syntaxKind == "cplus_comptime_function_definition" }
        val innerCall = index.constructs.single { it.symbol == "inner" && it.syntaxKind == "cplus_comptime_invocation" }
        val outerCall = index.constructs.single { it.symbol == "outer" && it.syntaxKind == "cplus_comptime_invocation" }

        assertTrue(outer.activeThisPass)
        assertTrue(outerCall.activeThisPass)
        assertFalse(inner.activeThisPass, "inner generator is inert inside the outer generator result")
        assertFalse(innerCall.activeThisPass, "inner invocation is deferred until outer materialization")
        assertEquals(outer.span, inner.enclosingGeneratorSpan)
        assertEquals(outer.span, innerCall.enclosingGeneratorSpan)
        assertEquals(null, outerCall.enclosingGeneratorSpan)
    }

    @Test
    fun indexesComptimeDeclarationsCallsImportsAndTestsFromAst() {
        val snapshot = sources.open(
            SourceId.named("comptime-index.cp"),
            """
            comptime type @list(type T) {
                comptime int @staged_detail = 7;
                return @code { struct list_t { T *items; }; };
            }
            comptime typedef list(int) int_list_t;
            comptime import "types.cp";
            @test "list starts empty" { @assert(1 == 1); }
            """.trimIndent()
        )

        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val index = CPlusComptimeIndexer().index(CPlusAstAdapter().adapt(parsed))

        assertEquals(listOf("cplus_comptime_function_definition", "cplus_comptime_value", "cplus_comptime_invocation", "cplus_comptime_import"),
            index.constructs.map { it.syntaxKind })
        assertEquals("list", index.constructs[0].symbol)
        assertEquals("type", index.constructs[0].resultKind)
        assertEquals(listOf("T"), index.constructs[0].parameters.map { it.name })
        assertEquals(listOf("type"), index.constructs[0].parameters.map { it.typeText })
        assertEquals("staged_detail", index.constructs[1].symbol)
        assertFalse(index.constructs[1].activeThisPass, "nested generator-body declarations stay dormant until materialization")
        assertTrue(index.constructs[0].activeThisPass)
        assertEquals("list", index.constructs[2].symbol)
        assertEquals("int_list_t", index.constructs[2].alias)
        assertEquals(listOf("int"), index.constructs[2].argumentSpans.map {
            snapshot.text.substring(it.startOffset, it.endOffset)
        })
        assertEquals(1, index.imports.size)
        assertEquals(1, index.tests.size)
        assertTrue(index.constructs.first().bodySpan != null)
    }

    @Test
    fun indexesComptimeFunctionParametersAndMultipleCallArguments() {
        val text = """
            comptime function @combine(type T, int count) { return count; }
            comptime combine(int, 3);
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("comptime-signature-index.cp"), text)
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), "diagnostics=${parsed.diagnostics}; tree=${parsed.root}")
        val constructs = CPlusComptimeIndexer().index(CPlusAstAdapter().adapt(parsed)).constructs
        val generator = constructs.single { it.syntaxKind == "cplus_comptime_function_definition" }
        val call = constructs.single { it.syntaxKind == "cplus_comptime_invocation" }

        assertEquals("function", generator.resultKind)
        assertEquals(listOf("T", "count"), generator.parameters.map { it.name })
        assertEquals(listOf("type", "int"), generator.parameters.map { it.typeText })
        assertEquals(listOf(true, false), generator.parameters.map { it.genericType })
        assertEquals(listOf("int", "3"), call.argumentSpans.map { text.substring(it.startOffset, it.endOffset) })
    }

    @Test
    fun resolvesActiveComptimeInvocationsByNameAndArity() {
        val text = """
            comptime function @combine(type T, int count) { return count; }
            comptime combine(int, 3);
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("comptime-binding.cp"), text)
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val index = CPlusComptimeIndexer().index(CPlusAstAdapter().adapt(parsed))
        val resolution = CPlusComptimeResolver().resolve(index)

        assertTrue(resolution.diagnostics.isEmpty(), resolution.diagnostics.toString())
        val binding = resolution.bindings.single()
        assertEquals("combine", binding.symbol)
        assertEquals("function", binding.resultKind)
        assertEquals(index.constructs.first { it.syntaxKind == "cplus_comptime_function_definition" }.span, binding.declarationSpan)
        assertEquals(index.constructs.single { it.syntaxKind == "cplus_comptime_invocation" }.span, binding.invocationSpan)
    }

    @Test
    fun resolvesGenericTypeSpecializationToItsComptimeTypeGenerator() {
        val text = """
            comptime type @dynamic_list(type T) {
                return @code { struct generated_list_t { T *items; }; };
            }
            comptime typedef dynamic_list(int) int_list_t;
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("comptime-type-binding.cp"), text)
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val resolution = CPlusComptimeResolver().resolve(
            CPlusComptimeIndexer().index(CPlusAstAdapter().adapt(parsed))
        )

        assertTrue(resolution.diagnostics.isEmpty(), resolution.diagnostics.toString())
        val binding = resolution.bindings.single()
        assertEquals("dynamic_list", binding.symbol)
        assertEquals("type", binding.resultKind)
    }

    @Test
    fun materializesComptimeGeneratorOverloadsUsingResolvedArity() {
        val text = """
            comptime function @make(type T) {
                return int generated_one(void) { return 1; }
            }
            comptime function @make(type T, int @value) {
                return int generated_two(void) { return @value; }
            }
            comptime make(int);
            comptime make(int, 2);
            int main(void) {
                return generated_one() + generated_two() == 3 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("comptime-overloaded-generators.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("overloaded comptime generators should materialize")
        assertTrue("int generated_one(void)" in generated.code, generated.code)
        assertTrue("int generated_two(void)" in generated.code, generated.code)
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val run = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        assertEquals(0, run.first, run.second)
    }

    @Test
    fun resolvesScalarComptimeOverloadsByArityAndLetsParametersShadowModuleValues() {
        val text = """
            comptime int @value = 100;
            comptime int @select(int value) { return value + 1; }
            comptime int @select(int value, int increment) { return value + increment; }
            int selected_one = comptime select(2);
            int selected_two = comptime select(40, 2);
            int module_value = comptime value;
            int main(void) {
                return selected_one == 3 && selected_two == 42 && module_value == 100 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-overload-scope.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("scalar overloads should materialize")
        assertTrue("int selected_one = 3" in generated.code, generated.code)
        assertTrue("int selected_two = 42" in generated.code, generated.code)
        assertTrue("int module_value = 100" in generated.code, generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun diagnosesDuplicateComptimeParameterNamesAtTheSecondDeclaration() {
        val text = """
            comptime function @make(type T, type T) { return T; }
            comptime make(int, int);
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("comptime-duplicate-parameter.cp"), text)
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val resolution = CPlusComptimeResolver().resolve(
            CPlusComptimeIndexer().index(CPlusAstAdapter().adapt(parsed))
        )

        val diagnostic = resolution.diagnostics.single {
            it.code == "CPLUS_COMPTIME_DUPLICATE_PARAMETER"
        }
        val first = text.indexOf("type T")
        val second = text.indexOf("type T", first + 1)
        assertEquals(second, diagnostic.span.startOffset)
        assertEquals(first, diagnostic.relatedSpan?.startOffset)
        assertTrue(resolution.bindings.isNotEmpty(), "binding remains observable beside the fail-closed diagnostic")
    }

    @Test
    fun diagnosesDuplicateAndAmbiguousComptimeGeneratorSignatures() {
        val text = """
            comptime function @choose(type T) { return T; }
            comptime function @choose(int value) { return value; }
            comptime choose(int);
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("comptime-ambiguous-binding.cp"), text)
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val resolution = CPlusComptimeResolver().resolve(
            CPlusComptimeIndexer().index(CPlusAstAdapter().adapt(parsed))
        )

        assertEquals(
            listOf("CPLUS_COMPTIME_DUPLICATE_GENERATOR", "CPLUS_COMPTIME_AMBIGUOUS_GENERATOR"),
            resolution.diagnostics.map { it.code }
        )
        assertTrue(resolution.diagnostics.all { it.relatedSpan != null })
    }

    @Test
    fun diagnosesUnresolvedAndWrongArityComptimeInvocationsAtTheirSpans() {
        val text = """
            comptime function @known(type T) { return T; }
            comptime known();
            comptime missing(int);
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("comptime-binding-errors.cp"), text)
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val index = CPlusComptimeIndexer().index(CPlusAstAdapter().adapt(parsed))

        val resolution = CPlusComptimeResolver().resolve(index)

        assertEquals(
            listOf("CPLUS_COMPTIME_ARGUMENT_COUNT", "CPLUS_COMPTIME_UNRESOLVED_GENERATOR"),
            resolution.diagnostics.map { it.code }
        )
        resolution.diagnostics.forEach { diagnostic ->
            assertEquals(snapshot.id.value, diagnostic.span.file)
            assertTrue(diagnostic.span.startOffset in text.indices)
        }
    }

    @Test
    fun ignoresDormantComptimeGeneratorDeclarationsAndInvocationsDuringBinding() {
        val text = """
            comptime code @outer() {
                return @code {
                    comptime function @inner(type T) { return T; }
                    comptime not_yet_visible(int);
                };
            }
            comptime outer();
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("comptime-binding-dormant.cp"), text)
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val index = CPlusComptimeIndexer().index(CPlusAstAdapter().adapt(parsed))

        val resolution = CPlusComptimeResolver().resolve(index)

        assertTrue(resolution.diagnostics.isEmpty(), resolution.diagnostics.toString())
        assertEquals(listOf("outer"), resolution.bindings.map { it.symbol })
    }

    @Test
    fun comptimeGeneratorBindingsRespectModuleAndRuntimeFunctionScopes() {
        val text = """
            comptime function @module_generator(type T) { return T; }
            int runtime_function(void) {
                comptime function @local_generator(type T) { return T; }
                comptime module_generator(int);
                comptime local_generator(int);
                return 0;
            }
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("comptime-binding-scope.cp"), text)
        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val index = CPlusComptimeIndexer().index(CPlusAstAdapter().adapt(parsed))

        val resolution = CPlusComptimeResolver().resolve(index)

        assertTrue(resolution.bindings.isEmpty(), resolution.bindings.toString())
        val localDeclaration = index.constructs.single {
            it.syntaxKind == "cplus_comptime_function_definition" && it.symbol == "local_generator"
        }
        assertFalse(localDeclaration.moduleScope)
        assertEquals(
            listOf(
                "CPLUS_COMPTIME_DECLARATION_SCOPE",
                "CPLUS_COMPTIME_INVOCATION_SCOPE",
                "CPLUS_COMPTIME_INVOCATION_SCOPE"
            ),
            resolution.diagnostics.map { it.code }
        )
        assertTrue(resolution.diagnostics.all { it.message.contains("module scope") })
    }

    @Test
    fun prototypeRejectsEntityComptimeInvocationInsideRuntimeFunctionAtItsSourceSpan() {
        val text = """
            comptime function @make(type T) {
                return int generated(void) { return 42; }
            }
            int runtime(void) {
                comptime make(int);
                return 0;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("runtime-comptime-entity-scope.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful, "runtime-scope comptime invocations must fail closed")
        assertEquals("CPLUS_COMPTIME_INVOCATION_SCOPE", result.loweringDiagnostics.single().code)
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("comptime make(int)"), diagnostic.span.startOffset)
    }

    @Test
    fun prototypeRejectsComptimeGeneratorDeclarationInsideRuntimeFunctionAtItsSourceSpan() {
        val text = """
            int runtime(void) {
                comptime function @local(type T) { return T; }
                return 0;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("runtime-comptime-generator-scope.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful, "runtime-scope comptime declarations must fail closed")
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_DECLARATION_SCOPE", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("comptime function"), diagnostic.span.startOffset)
    }

    @Test
    fun prototypeRejectsComptimeConditionalInsideRuntimeFunctionAtItsSourceSpan() {
        val text = """
            int runtime(void) {
                @if (os == "linux") { return 1; }
                @else { return 2; }
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("runtime-comptime-conditional-scope.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful, "runtime-scope comptime conditionals must fail closed")
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_CONDITIONAL_SCOPE", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("@if"), diagnostic.span.startOffset)
    }

    @Test
    fun prototypeReportsUnresolvedComptimeGeneratorAsMappedDiagnostic() {
        val text = "comptime absent(int);"
        val source = sources.open(SourceId.named("unresolved-comptime.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertEquals("CPLUS_COMPTIME_UNRESOLVED_GENERATOR", result.loweringDiagnostics.single().code)
        assertEquals(source.id.value, result.loweringDiagnostics.single().span.file)
        assertTrue(
            text.substring(result.loweringDiagnostics.single().span.startOffset, result.loweringDiagnostics.single().span.endOffset)
                .contains("absent")
        )
    }

    @Test
    fun indexesPlatformConditionalsAsComptimeConstructs() {
        val snapshot = sources.open(
            SourceId.named("comptime-platform-flags.cp"),
            "@if (os == \"linux\") { comptime flags -lX11; } @else { comptime flags -framework Cocoa; }"
        )

        val parsed = backend.parse(snapshot)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        val index = CPlusComptimeIndexer().index(CPlusAstAdapter().adapt(parsed))

        assertEquals(1, index.constructs.count { it.syntaxKind == "cplus_comptime_conditional" })
        assertEquals(2, index.constructs.count { it.syntaxKind == "cplus_comptime_flags" })
    }

    @Test
    fun prototypeMaterializesPlatformConditionalAndPreservesSelectedBranchMapping() {
        val text = """
            @if (os == "linux") { int selected_linux = 1; }
            @else if (os == "windows") { int selected_windows = 2; }
            @else { int selected_other = 3; }
        """.trimIndent()
        val source = sources.open(SourceId.named("materialized-comptime-conditional.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources, targetOs = "windows").transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.cSource ?: error("successful prototype result must contain C")
        assertTrue("selected_windows" in generated.text)
        assertFalse("selected_linux" in generated.text)
        assertFalse("selected_other" in generated.text)
        val mapped = generated.originAt(generated.text.indexOf("selected_windows"))
        assertEquals(source.sourceFile, mapped?.file)
        assertEquals(text.indexOf("selected_windows"), mapped?.offset)
    }

    @Test
    fun legacyAndTreeSitterAgreeOnPlatformConditionalCodeAndCompilerFlags() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val text = """
            comptime {
                @if (os == "linux") {
                    comptime int @selected_platform = 11;
                    comptime flags -DEXPECTED_PLATFORM=11;
                } @else {
                    comptime int @selected_platform = 22;
                    comptime flags -DEXPECTED_PLATFORM=22;
                }
            }
            int selected_runtime = comptime selected_platform;
            int main(void) { return selected_runtime == EXPECTED_PLATFORM ? 0 : 1; }
        """.trimIndent()
        val name = "differential-platform-flags.cp"
        for ((targetOs, expectedOption) in listOf("linux" to "-DEXPECTED_PLATFORM=11", "windows" to "-DEXPECTED_PLATFORM=22")) {
            val source = sources.open(SourceId.named(name), text)
            val legacy = CPlusTranspiler().transpile(text, name, targetOs = targetOs)
            val experimental = TreeSitterCPlusPrototypeTranspiler(backend, sources, targetOs = targetOs).transpile(source)
            assertTrue(
                experimental.successful,
                "$targetOs parser=${experimental.parserDiagnostics}; lowering=${experimental.loweringDiagnostics}; " +
                    "unsupported=${experimental.unsupportedNodes}"
            )
            val treeSitterC = experimental.transcodedSource ?: error("$targetOs prototype has no mapped C output")
            assertEquals(listOf(expectedOption), legacy.compilerOptions)
            assertEquals(legacy.compilerOptions, treeSitterC.compilerOptions)
            val legacyRun = compileAndCaptureC(compiler, legacy.code, legacy.compilerOptions)
            val treeSitterRun = compileAndCaptureC(compiler, treeSitterC.code, treeSitterC.compilerOptions)
            assertEquals(0, legacyRun.first, "$targetOs legacy output failed: ${legacyRun.second}")
            assertEquals(0, treeSitterRun.first, "$targetOs Tree-sitter output failed: ${treeSitterRun.second}")
            assertEquals(legacyRun, treeSitterRun, "$targetOs runtime output differs")
        }
    }

    @Test
    fun prototypeFailsClosedOnRuntimeStatementsInsideComptimeBlocks() {
        val text = "comptime { int runtime_value = 7; }"
        val source = sources.open(SourceId.named("unsupported-comptime-block.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful)
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_BLOCK_UNSUPPORTED", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("int runtime_value"), diagnostic.span.startOffset)
    }

    @Test
    fun prototypeRejectsComptimeBlockInsideRuntimeFunctionAtItsSourceSpan() {
        val text = """
            int runtime(void) {
                comptime { 1 + 2; }
                return 0;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("runtime-comptime-block-scope.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful, "runtime-scope comptime blocks must fail closed")
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_BLOCK_SCOPE", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("comptime {"), diagnostic.span.startOffset)
    }

    @Test
    fun prototypeEvaluatesAndDiscardsScalarExpressionStatementsInsideComptimeBlocks() {
        val text = """
            comptime {
                1 + 2;
                true && false;
                comptime int @block_value = 40;
                block_value + 2;
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("comptime-block-scalar-statements.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("scalar-only block should materialize")
        assertFalse("1 + 2" in generated.code, generated.code)
        assertFalse("true && false" in generated.code, generated.code)
        assertFalse("block_value + 2" in generated.code, generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeReportsErrorsFromDiscardedComptimeBlockScalarExpressions() {
        val text = "comptime { 1 / 0; }\nint main(void) { return 0; }"
        val source = sources.open(SourceId.named("comptime-block-invalid-scalar-statement.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful, "discarded scalar expressions must still be evaluated")
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_SCALAR_ARITHMETIC", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("1 / 0"), diagnostic.span.startOffset)
        assertTrue(result.transcodedSource == null, "invalid comptime block must not emit C")
    }

    @Test
    fun prototypeResolvesScalarDeclarationsInsideTopLevelComptimeBlocks() {
        val text = """
            comptime int @base = 40;
            comptime code @emit_value(int value) {
                return @code { int block_generated = @value; };
            }
            comptime {
                comptime int @inside = base + 2;
                comptime emit_value(inside);
            }
            int main(void) { return block_generated == 42 ? 0 : 1; }
        """.trimIndent()
        val source = sources.open(SourceId.named("top-level-comptime-block-scalar.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("block-local comptime value should materialize")
        assertTrue("int block_generated = 42" in generated.code, generated.code)
        assertFalse("@inside" in generated.code, generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeDoesNotTreatTypeNamesAsScalarComptimeValues() {
        val text = """
            typedef int scalar_t;
            comptime int @identity(int value) { return value; }
            comptime int @invalid = identity(scalar_t);
        """.trimIndent()
        val source = sources.open(SourceId.named("type-name-is-not-comptime-value.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful)
        val diagnostic = result.loweringDiagnostics.single {
            it.code == "CPLUS_COMPTIME_SCALAR_NAME"
        }
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("scalar_t);"), diagnostic.span.startOffset)
        assertTrue(result.transcodedSource == null, "a type-only symbol must not be materialized as a scalar value")
    }

    @Test
    fun prototypeMaterializesNestedAndNotEqualPlatformConditionsAcrossPasses() {
        val text = """
            @if (os != "windows") {
                @if (os == "linux") { int selected_linux = 1; }
                @else { int selected_non_windows = 2; }
            } @else { int selected_windows = 3; }
        """.trimIndent()
        val source = sources.open(SourceId.named("nested-comptime-conditional.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources, targetOs = "linux").transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.cSource ?: error("successful prototype result must contain C")
        assertTrue("selected_linux" in generated.text)
        assertFalse("selected_non_windows" in generated.text)
        assertFalse("selected_windows" in generated.text)
    }

    @Test
    fun prototypeRejectsUnsupportedPlatformConditionalExpressionsWithMappedDiagnostic() {
        val text = "@if (os == \"linux\") { int selected = 1; } @else if (arch == \"arm64\") { int other = 2; }"
        val source = sources.open(SourceId.named("unsupported-comptime-expression.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources, targetOs = "linux").transpile(source)

        assertFalse(result.successful)
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_CONDITION_UNSUPPORTED", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("arch"), diagnostic.span.startOffset)
        assertTrue(result.unsupportedNodes.isEmpty())
    }

    @Test
    fun prototypeMaterializesScalarComptimeValuesFromAstAndPreservesOrigins() {
        val text = """
            comptime int @answer = base * 2 + 1;
            comptime int @base = 6;
            comptime int @guarded = 0 && (1 / 0);
            int runtime_answer = comptime answer;
            int guarded_answer = comptime guarded;
            int main(void) { return runtime_answer == 13 && guarded_answer == 0 ? 0 : 1; }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-values.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.cSource ?: error("successful prototype result must contain C")
        assertFalse("comptime" in generated.text)
        assertFalse("@base" in generated.text)
        assertTrue("int runtime_answer = 13;" in generated.text)
        assertTrue("int guarded_answer = 0;" in generated.text)
        val mapped = generated.originAt(generated.text.indexOf("13"))
        assertEquals(source.sourceFile, mapped?.file)
        assertEquals(text.indexOf("comptime answer"), mapped?.offset)
        compileAndRunC(generated.text)
    }

    @Test
    fun prototypeEvaluatesPureScalarComptimeFunctionsFromAst() {
        val text = """
            comptime int @twice(int value) { return value * 2; }
            comptime int @named_answer = twice(42);
            int runtime_answer = comptime named_answer;
            int inline_answer = comptime twice(21);
            int main(void) { return runtime_answer == 84 && inline_answer == 42 ? 0 : 1; }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-functions.cp"), text)
        val parsed = backend.parse(source)
        val indexedFunctions = CPlusComptimeIndexer().index(CPlusAstAdapter().adapt(parsed)).constructs
            .filter { it.syntaxKind == "cplus_comptime_function_definition" }
        assertEquals(listOf("twice"), indexedFunctions.map { it.symbol })
        assertEquals(listOf("int"), indexedFunctions.single().parameters.map { it.typeText })

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.cSource ?: error("successful prototype result must contain C")
        assertFalse("comptime" in generated.text)
        assertFalse("twice" in generated.text)
        assertTrue("comptime int @answer" !in generated.text)
        assertTrue("int runtime_answer = 84;" in generated.text, generated.text)
        assertTrue("int inline_answer = 42;" in generated.text, generated.text)
        compileAndRunC(generated.text)
    }

    @Test
    fun prototypeAcceptsLiteralAndParenthesizedLeadingInlineScalarExpressions() {
        val text = """
            int literal_leading = comptime 5 + 2;
            int parenthesized_leading = comptime (3 * 4);
            int unary_leading = comptime -6;
            int main(void) {
                return literal_leading == 7 && parenthesized_leading == 12 && unary_leading == -6 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-inline-leading.cp"), text)

        val legacy = CPlusTranspiler().transpile(text, source.id.value)
        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.transcodedSource ?: error("inline scalar expressions should materialize")
        assertTrue("int literal_leading = 7;" in generated.code, generated.code)
        assertTrue("int parenthesized_leading = 12;" in generated.code, generated.code)
        assertTrue("int unary_leading = -6;" in generated.code, generated.code)
        assertTrue("int literal_leading = 7;" in legacy.code, legacy.code)
        assertTrue("int parenthesized_leading = 12;" in legacy.code, legacy.code)
        assertTrue("int unary_leading = -6;" in legacy.code, legacy.code)
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val legacyRun = compileAndCaptureC(compiler, legacy.code)
        val run = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        assertEquals(0, run.first, run.second)
        assertEquals(0, legacyRun.first, legacyRun.second)
        assertEquals(legacyRun, run)
    }

    @Test
    fun prototypeEvaluatesComptimeStringLiteralsAndConcatenationInline() {
        val text = """
            comptime string @generated_name() {
                return "generated_" + "answer\n";
            }
            int main(void) {
                const char* actual = comptime generated_name();
                return actual[0] != 'g' || actual[10] != 'a' || actual[16] != '\n' || actual[17] != '\0';
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-string-inline.cp"), text)

        val legacy = CPlusTranspiler().transpile(text, source.id.value)
        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.transcodedSource ?: error("string expression should materialize")
        assertTrue("const char *actual = \"generated_answer\\n\";" in generated.code, generated.code)
        assertTrue(Regex("const char\\s*\\*\\s*actual = \\\"generated_answer\\\\n\\\";").containsMatchIn(legacy.code), legacy.code)
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val astRun = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        val legacyRun = compileAndCaptureC(compiler, legacy.code, legacy.compilerOptions)
        assertEquals(0, astRun.first, astRun.second)
        assertEquals(0, legacyRun.first, legacyRun.second)
        assertEquals(legacyRun, astRun)
    }

    @Test
    fun prototypeMaterializesComptimeStringsAsTargetStableUtf8Bytes() {
        val text = """
            comptime string @utf8_value() {
                return "hé" + "🌍";
            }
            int main(void) {
                const unsigned char* actual = (const unsigned char*)comptime utf8_value();
                const unsigned char expected[] = { 'h', 0xc3, 0xa9, 0xf0, 0x9f, 0x8c, 0x8d, 0 };
                for (int index = 0; index < 8; index++) {
                    if (actual[index] != expected[index]) return index + 1;
                }
                return 0;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-string-utf8.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("UTF-8 comptime string should materialize")
        assertTrue("\"h\\303\\251\\360\\237\\214\\215\"" in generated.code, generated.code)
        assertFalse("é" in generated.code, "non-ASCII source characters must be emitted as stable UTF-8 bytes")
        assertFalse("🌍" in generated.code, "supplementary source characters must be emitted as stable UTF-8 bytes")
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeRejectsUnsupportedComptimeStringEscapesAtTheirSourceSpan() {
        val text = """
            comptime string @bad_name() { return "bad\x41"; }
            const char* value = comptime bad_name();
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-string-escape.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_SCALAR_STRING", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("\"bad\\x41\""), diagnostic.span.startOffset)
        assertTrue(result.transcodedSource == null, "unsupported escape must stop C emission")
    }

    @Test
    fun prototypeRejectsUnsupportedPrefixedComptimeStringLiteralsAtTheirSourceSpans() {
        listOf("L", "u8", "u", "U").forEach { prefix ->
            val literal = "${prefix}\"value\""
            val text = "const char* value = comptime $literal;"
            val source = sources.open(SourceId.named("scalar-comptime-string-prefix-$prefix.cp"), text)

            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

            val diagnostic = result.loweringDiagnostics.single()
            assertEquals("CPLUS_COMPTIME_SCALAR_STRING", diagnostic.code)
            assertEquals(source.id.value, diagnostic.span.file)
            assertEquals(text.indexOf(literal), diagnostic.span.startOffset)
            assertTrue(result.transcodedSource == null, "unsupported $prefix string must stop C emission")
        }
    }

    @Test
    fun prototypeScalarOperatorsMatchHostCResults() {
        val text = """
            comptime int @unary_plus = +5;
            comptime int @unary_minus = -5;
            comptime int @logical_not = !0;
            comptime int @bitwise_not = ~0;
            comptime int @add = 5 + 3;
            comptime int @subtract = 5 - 3;
            comptime int @multiply = 5 * 3;
            comptime int @divide = 7 / 2;
            comptime int @remainder = 7 % 4;
            comptime bool @equal = 3 == 3;
            comptime bool @not_equal = 3 != 4;
            comptime bool @less = 3 < 4;
            comptime bool @less_equal = 4 <= 4;
            comptime bool @greater = 4 > 3;
            comptime bool @greater_equal = 4 >= 4;
            comptime int @logical_and = 1 && 2;
            comptime int @logical_or = 0 || 2;
            comptime int @bitwise_and = 6 & 3;
            comptime int @bitwise_or = 6 | 3;
            comptime int @bitwise_xor = 6 ^ 3;
            comptime int @left_shift = 3 << 2;
            comptime int @right_shift = 12 >> 2;
            int main(void) {
                int actual_unary_plus = comptime unary_plus;
                int actual_unary_minus = comptime unary_minus;
                int actual_logical_not = comptime logical_not;
                int actual_bitwise_not = comptime bitwise_not;
                int actual_add = comptime add;
                int actual_subtract = comptime subtract;
                int actual_multiply = comptime multiply;
                int actual_divide = comptime divide;
                int actual_remainder = comptime remainder;
                int actual_equal = comptime equal;
                int actual_not_equal = comptime not_equal;
                int actual_less = comptime less;
                int actual_less_equal = comptime less_equal;
                int actual_greater = comptime greater;
                int actual_greater_equal = comptime greater_equal;
                int actual_logical_and = comptime logical_and;
                int actual_logical_or = comptime logical_or;
                int actual_bitwise_and = comptime bitwise_and;
                int actual_bitwise_or = comptime bitwise_or;
                int actual_bitwise_xor = comptime bitwise_xor;
                int actual_left_shift = comptime left_shift;
                int actual_right_shift = comptime right_shift;
                return actual_unary_plus != +5 || actual_unary_minus != -5 || actual_logical_not != !0 || actual_bitwise_not != ~0 ||
                    actual_add != 5 + 3 || actual_subtract != 5 - 3 || actual_multiply != 5 * 3 || actual_divide != 7 / 2 || actual_remainder != 7 % 4 ||
                    actual_equal != (3 == 3) || actual_not_equal != (3 != 4) || actual_less != (3 < 4) || actual_less_equal != (4 <= 4) ||
                    actual_greater != (4 > 3) || actual_greater_equal != (4 >= 4) || actual_logical_and != (1 && 2) || actual_logical_or != (0 || 2) ||
                    actual_bitwise_and != (6 & 3) || actual_bitwise_or != (6 | 3) || actual_bitwise_xor != (6 ^ 3) ||
                    actual_left_shift != (3 << 2) || actual_right_shift != (12 >> 2);
            }
        """.trimIndent()
        val name = "scalar-operator-matrix.cp"
        val source = sources.open(SourceId.named(name), text)
        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("scalar operator matrix should materialize")
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val astRun = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        assertEquals(0, astRun.first, "AST-generated C failed: ${astRun.second}")
    }

    @Test
    fun prototypeConvertsComptimeIntegerDeclarationsUsingTargetWidths() {
        val text = """
            comptime unsigned char @byte_value = -1;
            comptime bool @truth_value = 7;
            comptime short @short_value = 32767;
            comptime long @long_value = 4294967296;
            int main(void) {
                return comptime byte_value != 255 ||
                    comptime truth_value != 1 ||
                    comptime short_value != 32767 ||
                    comptime long_value != 4294967296L ? 1 : 0;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-integer-widths.cp"), text)

        val linux = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            targetArch = "x86_64"
        ).transpile(source)

        assertTrue(linux.successful, "parser=${linux.parserDiagnostics}; lowering=${linux.loweringDiagnostics}")
        val generated = linux.transcodedSource ?: error("typed scalar declarations should materialize")
        assertTrue("return 0 || 0 || 0 || 0 ? 1 : 0;" in generated.code, generated.code)
        compileAndRunC(generated.code)

        val windows = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "windows",
            targetArch = "x86_64"
        ).transpile(source)
        assertFalse(windows.successful, "32-bit Windows long must reject the out-of-range declaration")
        assertEquals("CPLUS_COMPTIME_SCALAR_WIDTH", windows.loweringDiagnostics.single().code)
        assertTrue(windows.loweringDiagnostics.single().message.contains("long"))
        assertEquals(source.id.value, windows.loweringDiagnostics.single().span.file)
    }

    @Test
    fun prototypeReportsSignedComptimeIntegerWidthOverflowAtTheDeclaration() {
        val text = """
            comptime signed char @too_large = 128;
            int main(void) { return comptime too_large; }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-signed-width.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            targetArch = "x86_64"
        ).transpile(source)

        assertFalse(result.successful, "signed char overflow must not be silently materialized")
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_SCALAR_WIDTH", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertTrue(diagnostic.span.startOffset <= text.indexOf("signed char"))
    }

    @Test
    fun prototypeUsesCUsualIntegerConversionsForComptimeComparisons() {
        val text = """
            comptime unsigned int @maximum = 4294967295;
            comptime unsigned char @small = 255;
            int main(void) {
                int unsigned_vs_negative = comptime (maximum > -1);
                int promoted_small_vs_negative = comptime (small < -1);
                int equal_after_promotion = comptime (small == 255);
                return unsigned_vs_negative != 0 ||
                    promoted_small_vs_negative != 0 ||
                    equal_after_promotion != 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-comparisons.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            targetArch = "x86_64"
        ).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}")
        val generated = result.transcodedSource ?: error("typed comparisons should materialize")
        assertTrue("int unsigned_vs_negative = 0;" in generated.code, generated.code)
        assertTrue("int promoted_small_vs_negative = 0;" in generated.code, generated.code)
        assertTrue("int equal_after_promotion = 1;" in generated.code, generated.code)
        compileAndRunC(generated.code)

        val windows = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "windows",
            targetArch = "x86_64"
        ).transpile(source)
        assertTrue(windows.successful, "parser=${windows.parserDiagnostics}; lowering=${windows.loweringDiagnostics}")
        val windowsGenerated = windows.transcodedSource ?: error("Windows typed comparisons should materialize")
        assertTrue("int unsigned_vs_negative = 0;" in windowsGenerated.code, windowsGenerated.code)
    }

    @Test
    fun prototypeUsesCommonIntegerTypeForComptimeArithmetic() {
        val text = """
            comptime unsigned int @maximum = 4294967295;
            comptime unsigned char @small = 255;
            int main(void) {
                int unsigned_wrap = comptime (maximum + 1);
                int promoted_small = comptime (small + 1);
                return unsigned_wrap != 0 || promoted_small != 256;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-arithmetic-types.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            targetArch = "x86_64"
        ).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}")
        val generated = result.transcodedSource ?: error("typed arithmetic should materialize")
        assertTrue("int unsigned_wrap = 0;" in generated.code, generated.code)
        assertTrue("int promoted_small = 256;" in generated.code, generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeReportsComptimeArithmeticOverflowForTheCommonSignedType() {
        val text = """
            int main(void) {
                return comptime (2147483647 + 1);
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-arithmetic-overflow.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            targetArch = "x86_64"
        ).transpile(source)

        assertFalse(result.successful, "signed int arithmetic overflow must fail closed")
        val diagnostic = result.loweringDiagnostics.first { it.code == "CPLUS_COMPTIME_SCALAR_ARITHMETIC" }
        assertEquals("CPLUS_COMPTIME_SCALAR_ARITHMETIC", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
    }

    @Test
    fun prototypeSelectsCIntegerLiteralTypesForRadixAndSuffix() {
        val text = """
            comptime unsigned int @all_bits = 0xffffffff;
            int main(void) {
                unsigned int hex_sum = comptime (0xffffffff + 1);
                int hex_compare = comptime (0xffffffff > -1);
                long long decimal_wide = comptime (4294967295 + 1);
                unsigned int suffixed_sum = comptime (4294967295u + 1u);
                unsigned int unary_minus = comptime (-1u);
                unsigned int unary_complement = comptime (~0u);
                unsigned int shifted_left = comptime (all_bits << 1);
                unsigned int shifted_right = comptime (all_bits >> 1);
                return hex_sum != 0 ||
                    hex_compare != 0 ||
                    decimal_wide != 4294967296LL ||
                    suffixed_sum != 0 ||
                    unary_minus != 4294967295U ||
                    unary_complement != 4294967295U ||
                    shifted_left != 4294967294U ||
                    shifted_right != 2147483647U;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-literal-types.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            targetArch = "x86_64"
        ).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}")
        val generated = result.transcodedSource ?: error("literal typing should materialize")
        assertTrue("unsigned int hex_sum = 0;" in generated.code, generated.code)
        assertTrue("int hex_compare = 0;" in generated.code, generated.code)
        assertTrue("long long decimal_wide = 4294967296;" in generated.code, generated.code)
        assertTrue("unsigned int suffixed_sum = 0;" in generated.code, generated.code)
        assertTrue("unsigned int unary_minus = 4294967295;" in generated.code, generated.code)
        assertTrue("unsigned int unary_complement = 4294967295;" in generated.code, generated.code)
        assertTrue("unsigned int shifted_left = 4294967294;" in generated.code, generated.code)
        assertTrue("unsigned int shifted_right = 2147483647;" in generated.code, generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeSelectsLongIntegerLiteralSuffixesPerTargetAbi() {
        val text = """
            int main(void) {
                unsigned long long lower_l = comptime (0xffffffffL + 1);
                unsigned long long lower_ul = comptime (0xffffffffUL + 1);
                unsigned long long wide_ll = comptime (0xffffffffLL + 1);
                unsigned long long wide_ull = comptime (0xffffffffULL + 1);
                return lower_l != EXPECTED_LOWER_L ||
                    lower_ul != EXPECTED_LOWER_UL ||
                    wide_ll != 4294967296ULL ||
                    wide_ull != 4294967296ULL;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-long-suffixes.cp"), text)

        listOf(
            Triple("linux", "x86_64", "4294967296" to "4294967296"),
            Triple("linux", "arm64", "4294967296" to "4294967296"),
            Triple("macos", "x86_64", "4294967296" to "4294967296"),
            Triple("macos", "arm64", "4294967296" to "4294967296"),
            Triple("windows", "x86_64", "0" to "0")
        ).forEach { (os, arch, expected) ->
            val targetSource = text
                .replace("EXPECTED_LOWER_L", expected.first)
                .replace("EXPECTED_LOWER_UL", expected.second)
            val target = sources.open(SourceId.named("scalar-comptime-long-suffixes-$os-$arch.cp"), targetSource)
            val result = TreeSitterCPlusPrototypeTranspiler(
                backend = backend,
                sourceManager = sources,
                targetOs = os,
                targetArch = arch
            ).transpile(target)

            assertTrue(result.successful, "$os/$arch parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}")
            val generated = result.transcodedSource ?: error("long suffixes should materialize for $os/$arch")
            assertTrue("lower_l = ${expected.first};" in generated.code, generated.code)
            assertTrue("lower_ul = ${expected.second};" in generated.code, generated.code)
            assertTrue("wide_ll = 4294967296;" in generated.code, generated.code)
            assertTrue("wide_ull = 4294967296;" in generated.code, generated.code)
            compileAndRunC(generated.code)
        }
    }

    @Test
    fun prototypeUsesTargetAwarePlainCharSignedness() {
        val text = """
            comptime char @minus_one = -1;
            comptime char @maximum = 127;
            int main(void) {
                int is_negative = comptime (minus_one < 0);
                int maximum = comptime maximum;
                return is_negative != EXPECTED_SIGNED || maximum != 127 ? 1 : 0;
            }
        """.trimIndent()
        listOf(
            Triple("linux", "x86_64", 1),
            Triple("linux", "arm64", 0),
            Triple("macos", "x86_64", 1),
            Triple("macos", "arm64", 1),
            Triple("windows", "x86_64", 1),
            Triple("windows", "arm64", 1)
        ).forEach { (os, arch, expectedSigned) ->
            val targetSource = text.replace("EXPECTED_SIGNED", expectedSigned.toString())
            val source = sources.open(SourceId.named("scalar-comptime-plain-char-$os-$arch.cp"), targetSource)
            val result = TreeSitterCPlusPrototypeTranspiler(
                backend = backend,
                sourceManager = sources,
                targetOs = os,
                targetArch = arch
            ).transpile(source)

            assertTrue(result.successful, "$os/$arch parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}")
            val generated = result.transcodedSource ?: error("plain char should materialize for $os/$arch")
            assertTrue("int is_negative = $expectedSigned;" in generated.code, generated.code)
            assertTrue("int maximum = 127;" in generated.code, generated.code)
            compileAndRunC(generated.code)
        }
    }

    @Test
    fun prototypeRejectsPlainCharWhenTargetSignednessIsUnknown() {
        val text = """
            comptime char @value = -1;
            int main(void) { return comptime value; }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-plain-char-unknown-target.cp"), text)
        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "freebsd",
            targetArch = "x86_64"
        ).transpile(source)

        assertFalse(result.successful, "unknown plain-char ABI must fail closed")
        val diagnostic = result.loweringDiagnostics.single { it.code == "CPLUS_COMPTIME_SCALAR_TYPE" }
        assertEquals(source.id.value, diagnostic.span.file)
        assertTrue(diagnostic.message.contains("plain char signedness"), diagnostic.toString())
        assertTrue(diagnostic.span.startOffset <= text.indexOf("char"), diagnostic.toString())
    }

    @Test
    fun prototypeAppliesTargetAwareIntegerCasts() {
        val text = """
            int main(void) {
                unsigned char byte_value = comptime (unsigned char) 300;
                unsigned int unsigned_value = comptime (unsigned int) -1;
                int signed_value = comptime (int) 255U;
                int bool_value = comptime (_Bool) 9;
                return byte_value != 44 ||
                    unsigned_value != 4294967295U ||
                    signed_value != 255 ||
                    bool_value != 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-casts.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            targetArch = "x86_64"
        ).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}")
        val generated = result.transcodedSource ?: error("integer casts should materialize")
        assertTrue("unsigned char byte_value = 44;" in generated.code, generated.code)
        assertTrue("unsigned int unsigned_value = 4294967295;" in generated.code, generated.code)
        assertTrue("int signed_value = 255;" in generated.code, generated.code)
        assertTrue("int bool_value = 1;" in generated.code, generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeRejectsUnsupportedComptimeScalarCastsWithMappedDiagnostics() {
        val text = "int main(void) { return comptime (int*) 1; }"
        val source = sources.open(SourceId.named("scalar-comptime-pointer-cast.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            targetArch = "x86_64"
        ).transpile(source)

        assertFalse(result.successful, "pointer casts must remain outside scalar comptime")
        val diagnostic = result.loweringDiagnostics.first { it.code == "CPLUS_COMPTIME_SCALAR_CAST" }
        assertEquals(source.id.value, diagnostic.span.file)
        assertTrue(diagnostic.span.startOffset <= text.indexOf("int*"), diagnostic.toString())
    }

    @Test
    fun prototypeConvertsScalarComptimeFunctionParametersAndReturns() {
        val text = """
            comptime int @bump(unsigned char value) {
                return value + 1;
            }
            comptime bool @truth(unsigned char value) {
                return value;
            }
            comptime int @accept(unsigned int value) {
                return value == 4294967295U;
            }
            int main(void) {
                int bumped = comptime bump(255);
                int truth = comptime truth(2);
                int accepted = comptime accept(-1);
                return bumped != 256 || truth != 1 || accepted != 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-function-conversions.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            targetArch = "x86_64"
        ).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}")
        val generated = result.transcodedSource ?: error("scalar function conversions should materialize")
        assertTrue("int bumped = 256;" in generated.code, generated.code)
        assertTrue("int truth = 1;" in generated.code, generated.code)
        assertTrue("int accepted = 1;" in generated.code, generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeRejectsOutOfRangeScalarComptimeFunctionConversionWithMappedDiagnostics() {
        val text = """
            comptime int @needs_signed(signed char value) {
                return value;
            }
            int main(void) { return comptime needs_signed(300); }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-function-conversion-error.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            targetArch = "x86_64"
        ).transpile(source)

        assertFalse(result.successful, "out-of-range signed parameter conversion must fail closed")
        val diagnostic = result.loweringDiagnostics.first { it.code == "CPLUS_COMPTIME_SCALAR_FUNCTION_CONVERSION" }
        assertEquals(source.id.value, diagnostic.span.file)
        assertTrue(diagnostic.span.startOffset <= text.indexOf("300"), diagnostic.toString())
    }

    @Test
    fun prototypeMaterializesNonGenericFunctionAndVariableEntitiesWithMappedOrigins() {
        val text = """
            comptime variable @make_limit(int @value) {
                return int generated_limit = @value;
            }
            comptime function @make_answer() {
                return int generated_answer(void) { return generated_limit + 1; }
            }
            comptime make_limit(42);
            comptime make_answer();
            int main(void) { return generated_answer() == 43 ? 0 : 1; }
        """.trimIndent()
        val name = "ast-comptime-entity.cp"
        val source = sources.open(SourceId.named(name), text)
        val legacy = CPlusTranspiler().transpile(text, name)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.transcodedSource ?: error("successful AST entity materialization must expose TranscodedSource")
        assertFalse("comptime variable" in generated.code || "comptime function" in generated.code, generated.code)
        assertTrue("int generated_limit = 42;" in generated.code, generated.code)
        assertTrue("int generated_answer(void)" in generated.code, generated.code)
        val mappedText = result.cSource ?: error("successful prototype result must retain mapped source")
        val declarationOffset = mappedText.text.indexOf("generated_answer(void)")
        assertEquals(source.sourceFile, mappedText.originAt(declarationOffset)?.file)
        assertEquals(text.indexOf("generated_answer(void)"), mappedText.originAt(declarationOffset)?.offset)
        val substitutedOffset = mappedText.text.indexOf("generated_limit = 42") + "generated_limit = ".length
        assertEquals(source.sourceFile, mappedText.originAt(substitutedOffset)?.file)
        assertEquals(text.indexOf("42", text.indexOf("comptime make_limit(42)")), mappedText.originAt(substitutedOffset)?.offset)

        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val astRun = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        val legacyRun = compileAndCaptureC(compiler, legacy.code, legacy.compilerOptions)
        assertEquals(0, astRun.first, astRun.second)
        assertEquals(0, legacyRun.first, legacyRun.second)
        assertEquals(legacyRun, astRun)
    }

    @Test
    fun prototypeIndexesEveryNameInGeneratedMultiDeclaratorEntities() {
        val text = """
            comptime variable @make_values() {
                return int first_generated = 20, second_generated = 22;
            }
            comptime make_values();
            int main(void) { return first_generated + second_generated == 42 ? 0 : 1; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-multiple-variable-entities.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.transcodedSource ?: error("generated declaration should materialize")
        assertTrue("first_generated = 20, second_generated = 22" in generated.code, generated.code)
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val run = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        assertEquals(0, run.first, run.second)
    }

    @Test
    fun prototypeRejectsUnsupportedEntityGeneratorParameterTypesAtTheirSourceSpan() {
        val text = """
            comptime variable @make_value(double @value) {
                return int generated_value = 1;
            }
            comptime make_value(1.5);
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-entity-unsupported.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_ENTITY_PARAMETER", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("comptime variable"), diagnostic.span.startOffset)
    }

    @Test
    fun prototypeMaterializesGenericFunctionEntityFromAstTypeReferences() {
        val text = """
            typedef int value_t;
            comptime function @make_identity(type T) {
                return T generated_identity(T value) { return value; }
            }
            comptime function @make_wrapped(type U) {
                return U generated_wrapped(U value) { return value; }
            }
            comptime make_identity(int);
            comptime make_wrapped(value_t);
            int main(void) {
                return generated_identity(42) == 42 && generated_wrapped(17) == 17 ? 0 : 1;
            }
        """.trimIndent()
        val name = "ast-comptime-generic-function.cp"
        val source = sources.open(SourceId.named(name), text)
        val legacy = CPlusTranspiler().transpile(text, name)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.transcodedSource ?: error("successful generic entity materialization must expose TranscodedSource")
        assertTrue(Regex("\\bint generated_identity\\s*\\(\\s*int value\\s*\\)").containsMatchIn(generated.code), generated.code)
        assertTrue(Regex("\\bvalue_t generated_wrapped\\s*\\(\\s*value_t value\\s*\\)").containsMatchIn(generated.code), generated.code)
        val mappedText = result.cSource ?: error("successful generic entity materialization must retain mapped source")
        val generatedNameOffset = mappedText.text.indexOf("generated_identity")
        assertEquals(text.indexOf("generated_identity"), mappedText.originAt(generatedNameOffset)?.offset)
        val substitutedTypeOffset = mappedText.text.indexOf("int generated_identity")
        assertEquals(source.sourceFile, mappedText.originAt(substitutedTypeOffset)?.file)
        assertEquals(text.indexOf("int", text.indexOf("comptime make_identity(int)")), mappedText.originAt(substitutedTypeOffset)?.offset)

        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val astRun = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        val legacyRun = compileAndCaptureC(compiler, legacy.code, legacy.compilerOptions)
        assertEquals(0, astRun.first, astRun.second)
        assertEquals(0, legacyRun.first, legacyRun.second)
        assertEquals(legacyRun, astRun)
    }

    @Test
    fun prototypeMaterializesPointerQualifiedGenericTypeArgument() {
        val text = """
            comptime function @make_pointer_identity(type P) {
                return P generated_pointer_identity(P value) { return value; }
            }
            comptime make_pointer_identity(const char*);
            int main(void) {
                return generated_pointer_identity("x")[0] == 'x' ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-pointer-type.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.transcodedSource ?: error("successful pointer generic materialization must expose TranscodedSource")
        assertTrue(Regex("const char\\s*\\*\\s*generated_pointer_identity\\s*\\(\\s*const char\\s*\\*\\s*value\\s*\\)").containsMatchIn(generated.code), generated.code)
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val run = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        assertEquals(0, run.first, run.second)
    }

    @Test
    fun prototypeMaterializesAndRunsTheStandardLibraryDynamicList() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib")) }
            ?: error("could not locate repository stdlib from ${Path.of("").toAbsolutePath()}")
        val stdlibRoot = repository.resolve("stdlib").toAbsolutePath()
        val module = stdlibRoot.resolve("containers/dynamic_list.cp")
        val text = Files.readString(module) + """

            typedef @dynamic_list(int) int_list_t;

            int main(void) {
                int_list_t values;
                values.init();
                int item = 73;
                if (values.push(&item) != 0) return 1;
                int* found = values.get(0);
                int failed = found == NULL || *found != 73;
                values.destroy();
                return failed;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named(module.toString()), text)
        val result = TreeSitterCPlusPrototypeTranspiler(
            backend,
            sources,
            importPaths = CPlusImportPaths(standardLibraryRoots = listOf(stdlibRoot))
        ).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("stdlib generic list must expose mapped C output")
        assertTrue(generated.code.contains("typedef struct int_list_t"), generated.code)
        assertTrue(generated.code.contains("int_list__push"), generated.code)
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val run = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        assertEquals(0, run.first, run.second)
    }

    @Test
    fun prototypeMaterializesAndRunsTheStandardLibraryDynamicMap() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib")) }
            ?: error("could not locate repository stdlib from ${Path.of("").toAbsolutePath()}")
        val stdlibRoot = repository.resolve("stdlib").toAbsolutePath()
        val module = stdlibRoot.resolve("containers/dynamic_map.cp")
        val text = Files.readString(module) + """

            typedef @dynamic_map(int, int) int_map_t;

            static int integer_keys_equal(const int* left, const int* right) {
                return *left == *right;
            }

            int main(void) {
                int_map_t values;
                if (values.init(integer_keys_equal) != 0) return 1;
                int key = 19;
                int value = 73;
                if (values.put(&key, &value) != 0) return 2;
                int* found = values.get(&key);
                if (found == NULL || *found != 73 || !values.contains(&key)) return 3;
                if (values.remove(&key) != 0 || values.contains(&key)) return 4;
                values.destroy();
                return 0;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named(module.toString()), text)
        val result = TreeSitterCPlusPrototypeTranspiler(
            backend,
            sources,
            importPaths = CPlusImportPaths(standardLibraryRoots = listOf(stdlibRoot))
        ).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("stdlib generic map must expose mapped C output")
        assertTrue(generated.code.contains("typedef struct int_map_t"), generated.code)
        assertTrue(generated.code.contains("int_map__put"), generated.code)
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val run = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        assertEquals(0, run.first, run.second)
    }

    @Test
    fun prototypeKeepsFunctionPointerFieldsAsCallableCExpressions() {
        val text = """
            typedef int (*callback_t)(int value);
            typedef struct dispatch_t {
                callback_t callback;
                pub int invoke(borrowed *self, int value) {
                    return self->callback(value);
                }
            } dispatch_t;

            static int double_value(int value) { return value * 2; }

            int main(void) {
                dispatch_t value = { double_value };
                return value.invoke(21) == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-callable-function-field.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("callable-field fixture must expose mapped C output")
        assertTrue("self->callback(value)" in generated.code, generated.code)
        assertFalse("dispatch__callback" in generated.code, generated.code)
        assertTrue("dispatch__invoke(&value, 21)" in generated.code, generated.code)

        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val run = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        assertEquals(0, run.first, run.second)
    }

    @Test
    fun extractedFixtureBridgeDoesNotRelowerCallableFieldsInAstRuntime() {
        val text = """
            typedef int (*callback_t)(int value);
            typedef struct dispatch_t {
                callback_t callback;
                pub int invoke(borrowed *self, int value) {
                    return self->callback(value);
                }
            } dispatch_t;

            static int double_value(int value) { return value * 2; }

            @test "callable field fixture" {
                dispatch_t value = { double_value };
                @assert(value.invoke(21) == 42);
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-callable-field-fixture.cp"), text)
        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val testProgram = CPlusTranspiler().transpileExtractedTests(
            result.cSource ?: error("callable-field fixture must expose mapped C output"),
            result.testFixtures
        )
        compileAndRunC(testProgram.source.code)
    }

    @Test
    fun prototypeMaterializesMixedGenericTypeAndScalarParameters() {
        val text = """
            comptime variable @make_seed(type T, int @seed) {
                return T generated_seed = @seed;
            }
            comptime make_seed(int, 73);
            int main(void) { return generated_seed == 73 ? 0 : 1; }
        """.trimIndent()
        val name = "ast-comptime-mixed-parameters.cp"
        val source = sources.open(SourceId.named(name), text)
        val legacy = CPlusTranspiler().transpile(text, name)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.transcodedSource ?: error("successful mixed entity materialization must expose TranscodedSource")
        assertTrue("int generated_seed = 73;" in generated.code, generated.code)
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val astRun = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        val legacyRun = compileAndCaptureC(compiler, legacy.code, legacy.compilerOptions)
        assertEquals(0, astRun.first, astRun.second)
        assertEquals(0, legacyRun.first, legacyRun.second)
        assertEquals(legacyRun, astRun)
    }

    @Test
    fun prototypeMaterializesGenericStructTypeAndSpecializationAlias() {
        val text = """
            comptime type @box(type T) {
                return @code { struct box { T value; }; };
            }
            comptime typedef box(int) int_box_t;
            int main(void) {
                int_box_t value = { 42 };
                return value.value == 42 ? 0 : 1;
            }
        """.trimIndent()
        val name = "ast-comptime-generic-struct.cp"
        val source = sources.open(SourceId.named(name), text)
        val legacy = CPlusTranspiler().transpile(text, name)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.transcodedSource ?: error("successful comptime type materialization must expose TranscodedSource")
        assertTrue("typedef struct box__int_box_t" in generated.code, generated.code)
        assertTrue("int value;" in generated.code, generated.code)
        assertTrue("int_box_t;" in generated.code, generated.code)
        val mappedText = result.cSource ?: error("successful comptime type materialization must retain mapped source")
        val structureOffset = mappedText.text.indexOf("struct box__int_box_t")
        assertEquals(text.indexOf("struct box"), mappedText.originAt(structureOffset)?.offset)
        val specializedTagOffset = mappedText.text.indexOf("__int_box_t")
        assertEquals(text.indexOf("comptime typedef box(int)"), mappedText.originAt(specializedTagOffset)?.offset)
        val substitutedTypeOffset = mappedText.text.indexOf("int value;")
        assertEquals(text.indexOf("comptime typedef box(int)"), mappedText.originAt(substitutedTypeOffset)?.offset)

        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val astRun = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        val legacyRun = compileAndCaptureC(compiler, legacy.code, legacy.compilerOptions)
        assertEquals(0, astRun.first, astRun.second)
        assertEquals(0, legacyRun.first, legacyRun.second)
        assertEquals(legacyRun, astRun)
    }

    @Test
    fun prototypeMaterializesMultipleSpecializationsWithDistinctStructTags() {
        val text = """
            comptime type @box(type T) {
                return @code { struct box { T value; }; };
            }
            comptime typedef box(int) int_box_t;
            comptime typedef box(float) float_box_t;
            int main(void) {
                int_box_t integer = { 42 };
                float_box_t decimal = { 3.5f };
                return integer.value == 42 && decimal.value == 3.5f ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-multiple-specializations.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.transcodedSource ?: error("successful specializations must expose mapped C output")
        assertTrue("struct box__int_box_t" in generated.code, generated.code)
        assertTrue("struct box__float_box_t" in generated.code, generated.code)
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val run = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        assertEquals(0, run.first, run.second)
    }

    @Test
    fun prototypeMaterializesQualifiedAndMultiwordGenericTypes() {
        val text = """
            comptime type @box(type T) {
                return @code { struct box { T value; }; };
            }
            comptime typedef box(unsigned long) ulong_box_t;
            comptime typedef box(const char*) string_box_t;
            int main(void) {
                ulong_box_t number = { 42UL };
                string_box_t text = { "c-plus" };
                return number.value == 42UL && text.value[0] == 'c' ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-qualified-types.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.transcodedSource ?: error("successful qualified type materialization must expose C output")
        assertTrue("unsigned long value" in generated.code, generated.code)
        assertTrue("const char *value" in generated.code, generated.code)
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val run = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        assertEquals(0, run.first, run.second)
    }

    @Test
    fun prototypeMaterializesPointerToTaggedGenericTypeArguments() {
        val text = """
            typedef struct payload_t { int value; } payload_t;
            comptime type @box(type T) {
                return @code { struct box { T value; }; };
            }
            comptime typedef box(struct payload_t*) payload_pointer_box_t;
            int main(void) {
                payload_t payload = { 42 };
                payload_pointer_box_t box = { &payload };
                return box.value->value == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-tagged-pointer-type.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.transcodedSource ?: error("tagged pointer specialization should materialize")
        assertTrue("struct payload_t *value" in generated.code || "struct payload_t* value" in generated.code, generated.code)
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val run = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        assertEquals(0, run.first, run.second)
    }

    @Test
    fun prototypeMaterializesNestedCodeFragmentsAcrossThreeMappedPasses() {
        val text = """
            comptime code @emit_stage() {
                return @code {
                    comptime code @emit_inner(type T) {
                        return @code {
                            comptime function @make_value(type U) {
                                return U staged_value(void) { return sizeof(U); }
                            }
                            comptime make_value(T);
                        };
                    }
                    comptime emit_inner(int);
                    comptime int @generated_answer() { return 40 + 2; }
                };
            }
            comptime emit_stage();
            int main(void) { return staged_value() == sizeof(int) && comptime generated_answer() == 42 ? 0 : 1; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-code-mapped-passes.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.transcodedSource ?: error("nested comptime materialization should emit C")
        assertFalse("comptime " in generated.code, generated.code)
        assertTrue("int staged_value(void)" in generated.code, generated.code)
        val mapped = result.cSource ?: error("fixed-point output should preserve a source map")
        val generatedNameOffset = mapped.text.indexOf("staged_value(void)")
        assertEquals(source.sourceFile, mapped.originAt(generatedNameOffset)?.file)
        assertEquals(text.indexOf("staged_value(void)"), mapped.originAt(generatedNameOffset)?.offset)
        val substitutedTypeOffset = mapped.text.indexOf("int staged_value")
        assertEquals(source.sourceFile, mapped.originAt(substitutedTypeOffset)?.file)
        assertEquals(
            text.indexOf("int", text.indexOf("comptime emit_inner(int)")),
            mapped.originAt(substitutedTypeOffset)?.offset
        )
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val run = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        assertEquals(0, run.first, run.second)
    }

    @Test
    fun prototypeMapsCompilerErrorsFromMaterializedCodeBackToFragmentSource() {
        val text = """
            comptime code @emit_invalid_top_level() {
                return @code { return 1; };
            }
            comptime emit_invalid_top_level();
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-generated-parse-error.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "structurally valid code fragments should materialize: ${result.parserDiagnostics}; ${result.loweringDiagnostics}")
        val generated = result.transcodedSource ?: error("code fragment should be emitted")
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val compile = compileAndCaptureC(compiler, generated.code, generated.compilerOptions, expectCompileSuccess = false)
        assertTrue(compile.first != 0, "module-scope return is invalid C")
        assertTrue(source.id.value in compile.second, compile.second)
        assertTrue(":2:" in compile.second, "compiler should report the original fragment line: ${compile.second}")
    }

    @Test
    fun prototypeMapsUnresolvedGeneratorFromNestedMaterializedCodeToOriginalFragment() {
        val text = """
            comptime code @emit_outer() {
                return @code {
                    comptime code @emit_inner() {
                        return @code { comptime missing_generator(); };
                    }
                    comptime emit_inner();
                };
            }
            comptime emit_outer();
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-nested-unresolved.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful, "an unresolved generator emitted by materialized code must fail")
        val diagnostic = result.loweringDiagnostics.singleOrNull()
            ?: error("expected one mapped unresolved-generator diagnostic: ${result.loweringDiagnostics}")
        assertEquals("CPLUS_COMPTIME_UNRESOLVED_GENERATOR", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        val originalInvocation = "comptime missing_generator();"
        assertEquals(text.indexOf(originalInvocation), diagnostic.span.startOffset)
        assertEquals(text.indexOf(originalInvocation) + originalInvocation.length, diagnostic.span.endOffset)
    }

    @Test
    fun prototypeMapsParserDiagnosticsFromARejectedMaterializedRevisionToItsTemplate() {
        val text = """
            comptime code @emit() { return @code { int generated_marker = 42; }; }
            comptime emit();
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-generated-parser-diagnostic.cp"), text)
        val rejectingBackend = object : CPlusParserBackend {
            override val id = ParserBackendId.TREE_SITTER

            override fun parse(source: SourceSnapshot, options: CPlusParseOptions): CPlusParseResult {
                val parsed = backend.parse(source, options)
                if (source.text == text || "generated_marker" !in source.text) return parsed
                val offset = source.text.indexOf("generated_marker")
                return parsed.copy(diagnostics = listOf(ParserDiagnostic(
                    code = "TEST_GENERATED_SYNTAX_ERROR",
                    message = "injected rejection for materialized source-map verification",
                    severity = ParserDiagnosticSeverity.ERROR,
                    span = source.sourceFile.span(offset, offset + "generated_marker".length)
                )))
            }
        }

        val result = TreeSitterCPlusPrototypeTranspiler(rejectingBackend, sources).transpile(source)

        assertFalse(result.successful, "a parser rejection in a materialized revision must stop transpilation")
        val diagnostic = result.parserDiagnostics.singleOrNull()
            ?: error("expected one mapped generated parser diagnostic: ${result.parserDiagnostics}")
        assertEquals("TEST_GENERATED_SYNTAX_ERROR", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        val generatedTokenOffset = text.indexOf("generated_marker")
        assertEquals(generatedTokenOffset, diagnostic.span.startOffset)
        assertEquals(generatedTokenOffset + "generated_marker".length, diagnostic.span.endOffset)
        assertTrue(result.transcodedSource == null, "invalid materialized revisions must not reach C emission")
    }

    @Test
    fun prototypeReportsMalformedModuleImportedByGeneratedCodeAtTheDependencySpan() {
        val directory = Files.createTempDirectory("cplus-ast-generated-malformed-import")
        try {
            val rootPath = directory.resolve("root.cp")
            val brokenPath = directory.resolve("broken.cp")
            val brokenText = "int broken( {\n"
            Files.writeString(brokenPath, brokenText)
            val text = """
                comptime code @emit_import() {
                    return @code { comptime import "broken.cp"; };
                }
                comptime emit_import();
                int main(void) { return 0; }
            """.trimIndent()
            val source = sources.open(SourceId.named(rootPath.toString()), text)
            assertTrue(
                backend.parse(sources.open(SourceId.named(brokenPath.toString()), brokenText)).diagnostics.isNotEmpty(),
                "the dependency fixture must genuinely contain malformed C-plus syntax"
            )

            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

            assertFalse(result.successful, "a generated import of malformed C-plus must fail before emission")
            val diagnostic = result.parserDiagnostics.singleOrNull()
                ?: error("expected dependency parser diagnostics: ${result.parserDiagnostics}; ${result.loweringDiagnostics}")
            assertEquals("TS_ERROR_NODE", diagnostic.code)
            assertEquals(brokenPath.toRealPath().toString(), diagnostic.span.file)
            assertEquals(brokenText.indexOf("int broken"), diagnostic.span.startOffset)
            assertTrue(diagnostic.span.endOffset > diagnostic.span.startOffset)
            assertTrue(result.transcodedSource == null, "malformed imported source must not reach C emission")
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun entityMaterializerEnforcesItsGeneratedSourceSizeLimitAtTheInvocation() {
        val text = """
            comptime code @emit() { return @code { int generated = 42; }; }
            comptime emit();
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-output-limit.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val result = TreeSitterComptimeEntityLowering(maxMaterializedSourceChars = 8)
            .lower(parsed, MappedText.identity(source.sourceFile))

        val diagnostic = result.diagnostics.single()
        assertEquals("CPLUS_COMPTIME_ENTITY_OUTPUT_LIMIT", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("comptime emit()"), diagnostic.span.startOffset)
    }

    @Test
    fun prototypeDiagnosesBoundEntityInvocationWhenMaterializationMakesNoProgress() {
        val text = """
            comptime void @no_entity_result() { return; }
            comptime no_entity_result();
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-entity-no-progress.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful, "a bound but unsupported entity result must not pass through silently")
        val diagnostic = result.loweringDiagnostics.singleOrNull()
            ?: error("expected a no-progress diagnostic: ${result.loweringDiagnostics}")
        assertEquals("CPLUS_COMPTIME_ENTITY_NO_PROGRESS", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        val invocation = "comptime no_entity_result();"
        assertEquals(text.indexOf(invocation), diagnostic.span.startOffset)
        assertEquals(text.indexOf(invocation) + invocation.length, diagnostic.span.endOffset)
    }

    @Test
    fun prototypeMaterializesTypeNameReflectionInStringComptimeFunctions() {
        val text = """
            #include <string.h>
            typedef unsigned long word_t;
            comptime string @type_name(type T) { return T.name; }
            const char *primitive_name = comptime type_name(int);
            const char *alias_name = comptime type_name(word_t);
            int main(void) {
                return strcmp(primitive_name, "int") != 0 || strcmp(alias_name, "word_t") != 0;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-type-name-reflection.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}")
        val generated = result.transcodedSource ?: error("type-name reflection should materialize before C emission")
        assertTrue("const char *primitive_name = \"int\";" in generated.code, generated.code)
        assertTrue("const char *alias_name = \"word_t\";" in generated.code, generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeMaterializesStringComptimeCallsInsideTypeAndFunctionIdentifiers() {
        val text = """
            comptime string @typename(type T) { return T.name; }
            comptime type @make_list(type T) {
                return @code { struct list_of_@typename(T) { T value; }; };
            }
            comptime typedef make_list(int) list_of_int_t;
            comptime code @emit_mapper(type T) {
                return @code { int mapper__@typename(T)(int value) { return value + 1; } };
            }
            comptime emit_mapper(int);
            int main(void) {
                list_of_int_t list = { 41 };
                return mapper__int(list.value) == 42 ? 0 : 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-identifier-splice.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.transcodedSource ?: error("identifier splices should be materialized before C emission")
        assertTrue("struct list_of_int__list_of_int_t" in generated.code, generated.code)
        assertTrue("int mapper__int(int value)" in generated.code, generated.code)
        val mapped = result.cSource ?: error("identifier splice output should retain source mapping")
        val generatedTagOffset = mapped.text.indexOf("list_of_int__list_of_int_t") + "list_of_".length
        assertEquals(source.sourceFile, mapped.originAt(generatedTagOffset)?.file)
        assertEquals(text.indexOf("@typename(T)"), mapped.originAt(generatedTagOffset)?.offset)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeRejectsIdentifierSplicesThatReturnNonStringValues() {
        val text = """
            comptime int @not_a_name(type T) { return 7; }
            struct type_@not_a_name(int) { int value; };
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-invalid-identifier-splice.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful, "identifier splice functions must return strings")
        val diagnostic = result.loweringDiagnostics.singleOrNull()
            ?: error("expected one mapped identifier-splice diagnostic: ${result.loweringDiagnostics}")
        assertEquals("CPLUS_COMPTIME_IDENTIFIER_SPLICE_TYPE", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("@not_a_name(int)"), diagnostic.span.startOffset)
        assertEquals(text.indexOf("@not_a_name(int)") + "@not_a_name(int)".length, diagnostic.span.endOffset)
    }

    @Test
    fun prototypeMapsSplicedOrdinaryAndTagNamespaceCollisionsThroughGeneratedC() {
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val fixtures = listOf(
            "ast-comptime-splice-function-collision.cp" to """
                comptime string @typename(type T) { return T.name; }
                int mapper__int(int value) { return value; }
                int mapper__@typename(int)(int value) { return value + 1; }
                int main(void) { return 0; }
            """.trimIndent(),
            "ast-comptime-splice-tag-collision.cp" to """
                comptime string @typename(type T) { return T.name; }
                struct widget_int { int first; };
                struct widget_@typename(int) { int second; };
                int main(void) { return 0; }
            """.trimIndent()
        )

        fixtures.forEach { (fileName, text) ->
            val source = sources.open(SourceId.named(fileName), text)
            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

            assertTrue(
                result.successful,
                "$fileName parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
            )
            val generated = result.transcodedSource ?: error("$fileName should produce C before C semantic diagnostics")
            val compile = compileAndCaptureC(compiler, generated.code, expectCompileSuccess = false)
            assertTrue(compile.first != 0, "$fileName collision should be rejected by the C compiler")
            assertTrue(
                compile.second.contains("$fileName:3:"),
                "$fileName collision diagnostic should use the original splice location:\n${compile.second}"
            )
        }
    }

    @Test
    fun prototypeMapsUnknownTypeReflectionPropertiesToTheirFieldName() {
        val text = """
            comptime string @invalid_name(type T) { return T.missing; }
            const char *name = comptime invalid_name(int);
            int main(void) { return name == 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-invalid-type-reflection.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful, "unknown reflection properties must be rejected")
        val diagnostic = result.loweringDiagnostics.singleOrNull()
            ?: error("expected one mapped reflection diagnostic: ${result.loweringDiagnostics}")
        assertEquals("CPLUS_COMPTIME_REFLECTION_PROPERTY", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("missing"), diagnostic.span.startOffset)
        assertEquals(text.indexOf("missing") + "missing".length, diagnostic.span.endOffset)
    }

    @Test
    fun prototypeReflectsPrimitiveSizeAndAlignmentUsingExplicitTargetAbi() {
        val text = """
            typedef unsigned long word_t;
            typedef const char *char_pointer_t;
            comptime int @type_size(type T) { return T.size; }
            comptime int @type_alignment(type T) { return T.align; }
            int reflected_int_size = comptime type_size(int);
            int reflected_long_size = comptime type_size(long);
            int reflected_alias_size = comptime type_size(word_t);
            int reflected_pointer_size = comptime type_size(char_pointer_t);
            int reflected_pointer_alignment = comptime type_alignment(char_pointer_t);
            int main(void) {
                return reflected_int_size != sizeof(int) ||
                    reflected_long_size != sizeof(long) ||
                    reflected_alias_size != sizeof(word_t) ||
                    reflected_pointer_size != sizeof(char_pointer_t) ||
                    reflected_pointer_alignment != _Alignof(char_pointer_t);
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-size-alignment-reflection.cp"), text)
        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            targetArch = "x86_64"
        ).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}")
        val generated = result.transcodedSource ?: error("target ABI reflection should materialize")
        assertTrue("reflected_long_size = 8" in generated.code, generated.code)
        assertTrue("reflected_pointer_size = 8" in generated.code, generated.code)
        assertTrue("reflected_alias_size = 8" in generated.code, generated.code)
        // The fixture materializes the Linux x86_64 ABI. Windows uses LLP64
        // (`long` is 32-bit), so execute it only on an LP64 host.
        if (hostTargetOs() != "windows") {
            compileAndRunC(generated.code)
        }

        val windowsSource = sources.open(SourceId.named("ast-comptime-size-alignment-windows.cp"), text)
        val windows = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "windows",
            targetArch = "x86_64"
        ).transpile(windowsSource)
        assertTrue(windows.successful, "parser=${windows.parserDiagnostics}; lowering=${windows.loweringDiagnostics}")
        assertTrue("reflected_long_size = 4" in windows.transcodedSource?.code.orEmpty(), windows.transcodedSource?.code.orEmpty())
        assertTrue("reflected_pointer_size = 8" in windows.transcodedSource?.code.orEmpty(), windows.transcodedSource?.code.orEmpty())
    }

    @Test
    fun prototypeRejectsLayoutReflectionForUnknownTargetArchitecture() {
        val text = """
            comptime int @type_size(type T) { return T.size; }
            int reflected_size = comptime type_size(int);
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-unknown-abi-reflection.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            targetArch = "mystery64"
        ).transpile(source)

        assertFalse(result.successful, "layout must not be guessed for an unknown architecture")
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_REFLECTION_LAYOUT", diagnostic.code)
        assertEquals(text.indexOf("T.size") + 2, diagnostic.span.startOffset)
    }

    @Test
    fun prototypeReflectsAggregateLayoutWithPaddingAndNestedFields() {
        val text = """
            typedef struct inner_t { char tag; int value; } inner_t;
            typedef struct aggregate_t {
                char prefix;
                inner_t inner;
                int *pointer;
                short suffix;
            } aggregate_t;
            comptime int @type_size(type T) { return T.size; }
            comptime int @type_alignment(type T) { return T.align; }
            int reflected_size = comptime type_size(aggregate_t);
            int reflected_alignment = comptime type_alignment(aggregate_t);
            int main(void) {
                return reflected_size != sizeof(aggregate_t) ||
                    reflected_alignment != _Alignof(aggregate_t);
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-aggregate-layout.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            targetArch = "x86_64"
        ).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}")
        val generated = result.transcodedSource ?: error("aggregate layout should materialize")
        assertTrue("reflected_size = 32" in generated.code, generated.code)
        assertTrue("reflected_alignment = 8" in generated.code, generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeReflectsAggregateLayoutAcrossSupportedTargetAbis() {
        val text = """
            typedef struct target_layout_t { char tag; long value; int *pointer; } target_layout_t;
            comptime int @type_size(type T) { return T.size; }
            comptime int @type_alignment(type T) { return T.align; }
            int reflected_size = comptime type_size(target_layout_t);
            int reflected_alignment = comptime type_alignment(target_layout_t);
            int main(void) {
                return reflected_size != sizeof(target_layout_t) ||
                    reflected_alignment != _Alignof(target_layout_t);
            }
        """.trimIndent()

        fun materialize(targetOs: String, targetArch: String): String {
            val source = sources.open(
                SourceId.named("ast-comptime-target-aggregate-$targetOs-$targetArch.cp"),
                text
            )
            val result = TreeSitterCPlusPrototypeTranspiler(
                backend = backend,
                sourceManager = sources,
                targetOs = targetOs,
                targetArch = targetArch
            ).transpile(source)
            assertTrue(
                result.successful,
                "$targetOs/$targetArch parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}"
            )
            return result.transcodedSource?.code ?: error("aggregate layout output is missing for $targetOs/$targetArch")
        }

        val linuxX64 = materialize("linux", "x86_64")
        assertTrue("reflected_size = 24" in linuxX64, linuxX64)
        assertTrue("reflected_alignment = 8" in linuxX64, linuxX64)
        if (hostTargetOs() == "linux" && hostTargetArch() == "x86_64") {
            compileAndRunC(linuxX64)
        }

        val linuxArm64 = materialize("linux", "arm64")
        assertTrue("reflected_size = 24" in linuxArm64, linuxArm64)
        assertTrue("reflected_alignment = 8" in linuxArm64, linuxArm64)

        val macosArm64 = materialize("macos", "arm64")
        assertTrue("reflected_size = 24" in macosArm64, macosArm64)
        assertTrue("reflected_alignment = 8" in macosArm64, macosArm64)

        val windowsX64 = materialize("windows", "x86_64")
        assertTrue("reflected_size = 16" in windowsX64, windowsX64)
        assertTrue("reflected_alignment = 8" in windowsX64, windowsX64)
    }

    @Test
    fun prototypeReflectsFieldOffsetsSizesAndAlignmentInFieldLoops() {
        val text = """
            typedef struct aggregate_t {
                char prefix;
                int value;
                int *pointer;
                short suffix;
                char bytes[3];
            } aggregate_t;
            comptime code @check_layout(string @name, int offset, int size, int align) {
                return @code {
                    _Static_assert(@offset >= 0, @name);
                    _Static_assert(@size > 0, @name);
                    _Static_assert(@align > 0, @name);
                };
            }
            comptime {
                @for field in aggregate_t.fields {
                    comptime check_layout(field.name, field.offset, field.size, field.align);
                }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-field-layout.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            targetArch = "x86_64"
        ).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}")
        val generated = result.transcodedSource ?: error("field layout reflection should materialize")
        assertTrue("_Static_assert(0 >= 0, \"prefix\")" in generated.code, generated.code)
        assertTrue("_Static_assert(4 >= 0, \"value\")" in generated.code, generated.code)
        assertTrue("_Static_assert(8 >= 0, \"pointer\")" in generated.code, generated.code)
        assertTrue("_Static_assert(16 >= 0, \"suffix\")" in generated.code, generated.code)
        assertTrue("_Static_assert(18 >= 0, \"bytes\")" in generated.code, generated.code)
        assertTrue("_Static_assert(1 > 0, \"prefix\")" in generated.code, generated.code)
        assertTrue("_Static_assert(4 > 0, \"value\")" in generated.code, generated.code)
        assertTrue("_Static_assert(8 > 0, \"pointer\")" in generated.code, generated.code)
        assertTrue("_Static_assert(2 > 0, \"suffix\")" in generated.code, generated.code)
        assertTrue("_Static_assert(3 > 0, \"bytes\")" in generated.code, generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeRejectsPackedAggregateLayoutWithoutGuessing() {
        val text = """
            typedef struct __attribute__((packed)) packed_t { char tag; int value; } packed_t;
            comptime int @type_size(type T) { return T.size; }
            int reflected_size = comptime type_size(packed_t);
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-packed-aggregate-layout.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            targetArch = "x86_64"
        ).transpile(source)

        assertFalse(result.successful, "packed aggregate layout must remain fail-closed")
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_REFLECTION_LAYOUT", diagnostic.code)
        assertEquals(text.indexOf("size", text.indexOf("T.size")), diagnostic.span.startOffset)
        assertTrue(result.transcodedSource == null, "unsupported packed layout must stop C emission")
    }

    @Test
    fun prototypeRejectsUnsupportedAggregateLayoutShapesWithMappedDiagnostics() {
        val cases = listOf(
            """
                typedef union union_layout_t { char tag; int value; } union_layout_t;
                comptime int @type_size(type T) { return T.size; }
                int reflected_size = comptime type_size(union_layout_t);
            """.trimIndent(),
            """
                typedef struct bitfield_layout_t { unsigned value : 3; } bitfield_layout_t;
                comptime int @type_size(type T) { return T.size; }
                int reflected_size = comptime type_size(bitfield_layout_t);
            """.trimIndent(),
            """
                typedef struct flexible_layout_t { int length; int values[]; } flexible_layout_t;
                comptime int @type_size(type T) { return T.size; }
                int reflected_size = comptime type_size(flexible_layout_t);
            """.trimIndent(),
            """
                typedef struct incomplete_layout_t { missing_layout_t value; } incomplete_layout_t;
                comptime int @type_size(type T) { return T.size; }
                int reflected_size = comptime type_size(incomplete_layout_t);
            """.trimIndent()
        )

        cases.forEachIndexed { index, text ->
            val source = sources.open(SourceId.named("ast-comptime-unsupported-layout-$index.cp"), text)
            val result = TreeSitterCPlusPrototypeTranspiler(
                backend = backend,
                sourceManager = sources,
                targetOs = "linux",
                targetArch = "x86_64"
            ).transpile(source)

            assertFalse(result.successful, "unsupported aggregate layout $index must fail closed")
            val diagnostic = result.loweringDiagnostics.singleOrNull { it.code == "CPLUS_COMPTIME_REFLECTION_LAYOUT" }
                ?: error("unsupported aggregate layout $index produced ${result.loweringDiagnostics}")
            assertEquals(source.id.value, diagnostic.span.file)
            assertEquals(text.indexOf("T.size") + 2, diagnostic.span.startOffset)
            assertTrue(result.transcodedSource == null, "unsupported aggregate layout $index must not emit C")
        }
    }

    @Test
    fun prototypeIteratesReflectedStructFieldsAndMaterializesTypedCodeEntities() {
        val text = """
            typedef struct user_t {
                int id, age;
                borrowed char* name;
            } user_t;
            comptime code @emit_field(type T, string @name) {
                return @code { _Static_assert(sizeof(T) > 0, @name); };
            }
            comptime {
                @for field in user_t.fields {
                    comptime emit_field(field.type, field.name);
                }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-reflected-fields.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("reflected field output should be emitted")
        assertTrue("_Static_assert(sizeof (int) > 0, \"id\")" in generated.code, generated.code)
        assertTrue("_Static_assert(sizeof (int) > 0, \"age\")" in generated.code, generated.code)
        assertTrue("_Static_assert(sizeof (char *) > 0, \"name\")" in generated.code, generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeReflectsFieldAnnotationsInDeclarationOrder() {
        val text = """
            typedef struct ownership_t {
                borrowed mut char* input;
                owned char* output;
                int count;
            } ownership_t;
            comptime code @emit_annotation(type T, string @annotations) {
                return @code { _Static_assert(sizeof(T) > 0, @annotations); };
            }
            comptime {
                @for field in ownership_t.fields {
                    comptime emit_annotation(field.type, field.annotationsText);
                }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-field-annotations.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("field annotation metadata should be materialized")
        assertTrue("_Static_assert(sizeof (char *) > 0, \"borrowed mut\")" in generated.code, generated.code)
        assertTrue("_Static_assert(sizeof (char *) > 0, \"owned\")" in generated.code, generated.code)
        assertTrue("_Static_assert(sizeof (int) > 0, \"\")" in generated.code, generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeReflectsBitfieldStatusAndWidthWithMappedExpressionOrigins() {
        val text = """
            typedef struct flags_t {
                unsigned int low:3;
                unsigned int high:1 + 2;
                unsigned int :1;
                int ordinary;
            } flags_t;
            comptime code @assert_reflected_integer(int @value) {
                return @code {
                    _Static_assert(@value >= 0 && @value <= 3, "reflected bitfield integer");
                };
            }
            comptime code @assert_bitfield_flag(int @value) {
                return @code {
                    _Static_assert(@value == 0 || @value == 1, "bitfield flag");
                };
            }
            comptime {
                @for field in flags_t.fields {
                    comptime assert_bitfield_flag(field.flag);
                    comptime assert_reflected_integer(field.width);
                }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-bitfield-width-reflection.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("bitfield metadata should be materialized")
        assertTrue("3 >= 0 && 3 <= 3" in generated.code, generated.code)
        assertTrue("0 >= 0 && 0 <= 3" in generated.code, generated.code)
        assertTrue("1 >= 0 && 1 <= 3" in generated.code, generated.code)
        assertTrue("0 == 0 || 0 == 1" in generated.code, generated.code)
        assertTrue("1 == 0 || 1 == 1" in generated.code, generated.code)

        val parsed = backend.parse(source)
        val loopResult = TreeSitterComptimeFieldLoopLowering().lower(parsed, MappedText.identity(source.sourceFile))
        assertTrue(loopResult.diagnostics.isEmpty(), loopResult.diagnostics.toString())
        val afterLoop = backend.parse(sources.open(SourceId.named("ast-comptime-bitfield-width-loop.cp"), loopResult.source.text))
        val entityResult = TreeSitterComptimeEntityLowering().lower(afterLoop, loopResult.source)
        assertTrue(entityResult.diagnostics.isEmpty(), entityResult.diagnostics.toString())
        val firstMaterializedWidth = entityResult.source.text.indexOf("_Static_assert(3 >= 0")
        assertTrue(firstMaterializedWidth >= 0, entityResult.source.text)
        val materializedWidth = entityResult.source.text.indexOf("_Static_assert(3 >= 0", firstMaterializedWidth + 1)
        assertTrue(materializedWidth >= 0, entityResult.source.text)
        assertEquals(text.indexOf("1 + 2"), entityResult.source.originAt(materializedWidth + "_Static_assert(".length)?.offset)
        val expressionText = """
            struct expression_width_t { unsigned int value:1 + 2; };
            comptime { @for field in expression_width_t.fields { comptime consume(field.width); } }
        """.trimIndent()
        val expressionSource = sources.open(SourceId.named("ast-comptime-bitfield-expression-width.cp"), expressionText)
        val expressionLowering = TreeSitterComptimeFieldLoopLowering().lower(
            backend.parse(expressionSource),
            MappedText.identity(expressionSource.sourceFile)
        )
        assertTrue(expressionLowering.diagnostics.isEmpty(), expressionLowering.diagnostics.toString())
        assertTrue("comptime consume(1 + 2)" in expressionLowering.source.text, expressionLowering.source.text)
        val loweredWidth = expressionLowering.source.text.lastIndexOf("1 + 2")
        assertTrue(loweredWidth >= 0, expressionLowering.source.text)
        assertEquals(expressionText.indexOf("1 + 2"), expressionLowering.source.originAt(loweredWidth)?.offset)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeRejectsOverflowInComptimeEntityScalarArgumentsAtTheExpression() {
        val text = """
            comptime function @make_value(int @value) {
                return int generated_value(void) { return @value; }
            }
            comptime make_value(9223372036854775807 + 1);
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-entity-scalar-overflow.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful, "overflowing compile-time argument must fail closed")
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_SCALAR_ARITHMETIC", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("9223372036854775807 + 1"), diagnostic.span.startOffset)
        assertEquals(text.indexOf("9223372036854775807 + 1") + "9223372036854775807 + 1".length, diagnostic.span.endOffset)
    }

    @Test
    fun prototypeEvaluatesEntityArgumentsUsingEarlierComptimeValuesAndFunctions() {
        val text = """
            comptime int @base_value = 40;
            comptime int @double_value(int @value) { return value * 2; }
            comptime function @make_value(int @value) {
                return int generated_value(void) { return @value; }
            }
            comptime make_value(double_value(base_value) + 2);
            int main(void) { return generated_value() != 82; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-entity-scalar-environment.cp"), text)
        val parsed = backend.parse(source)
        val invocation = CPlusComptimeIndexer().index(CPlusAstAdapter().adapt(parsed)).constructs
            .single { it.syntaxKind == "cplus_comptime_invocation" && it.symbol == "make_value" }
        assertEquals("double_value(base_value) + 2", text.substring(
            invocation.argumentSpans.single().startOffset,
            invocation.argumentSpans.single().endOffset
        ))

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.cSource ?: error("entity scalar environment should materialize")
        assertTrue("return 82;" in generated.text, generated.text)
        compileAndRunC(generated.text)
    }

    @Test
    fun prototypeEvaluatesOnlyTheSelectedScalarConditionalBranch() {
        val text = """
            comptime variable @emit_first(int @value) {
                return int generated_value = @value;
            }
            comptime variable @emit_second(int @value) {
                return int generated_second = @value;
            }
            comptime emit_first(1 ? 40 + 2 : 1 / 0);
            comptime emit_second(0 ? 1 / 0 : 40 + 3);
            int main(void) { return generated_value == 42 && generated_second == 43 ? 0 : 1; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-scalar-conditional.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.transcodedSource ?: error("conditional scalar arguments should materialize")
        assertTrue("int generated_value = 42;" in generated.code, generated.code)
        assertTrue("int generated_second = 43;" in generated.code, generated.code)
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val run = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        assertEquals(0, run.first, run.second)
    }

    @Test
    fun prototypeRejectsNonScalarConditionalConditions() {
        val text = """
            comptime string @type_name(type T) { return T.name; }
            comptime variable @emit_value(int @value) {
                return int generated_value = @value;
            }
            comptime emit_value(type_name(int) ? 1 : 2);
            int main(void) { return generated_value; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-scalar-conditional-invalid.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_SCALAR_UNSUPPORTED", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("type_name(int)"), diagnostic.span.startOffset)
        assertTrue(result.transcodedSource == null, "invalid scalar condition must stop C emission")
    }

    @Test
    fun prototypeIteratesReflectedFieldAnnotationsAsMappedNestedComptimeLoop() {
        val text = """
            typedef struct ownership_t {
                borrowed mut char* input;
                owned char* output;
                int count;
            } ownership_t;
            comptime code @check_annotation(string @annotation) {
                return @code { _Static_assert(1, @annotation); };
            }
            comptime {
                @for field in ownership_t.fields {
                    @for annotation in field.annotations {
                        comptime check_annotation(annotation.name);
                    }
                }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-field-annotation-loop.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("annotation iteration should be materialized")
        assertTrue("_Static_assert(1, \"borrowed\")" in generated.code, generated.code)
        assertTrue("_Static_assert(1, \"mut\")" in generated.code, generated.code)
        assertTrue("_Static_assert(1, \"owned\")" in generated.code, generated.code)
        assertFalse("_Static_assert(1, \"borrowed mut\")" in generated.code, generated.code)
        val parsed = backend.parse(source)
        val loopResult = TreeSitterComptimeFieldLoopLowering().lower(parsed, MappedText.identity(source.sourceFile))
        assertTrue(loopResult.diagnostics.isEmpty(), loopResult.diagnostics.toString())
        val loweredAnnotation = loopResult.source.text.indexOf("comptime check_annotation(\"mut\")") +
            "comptime check_annotation(\"".length
        assertEquals(text.indexOf("mut char"), loopResult.source.originAt(loweredAnnotation)?.offset)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeRejectsUnknownStructuredAnnotationMembersAtTheirSourceSpan() {
        val text = """
            typedef struct ownership_t { borrowed char* input; } ownership_t;
            comptime {
                @for field in ownership_t.fields {
                    @for annotation in field.annotations {
                        comptime missing(annotation.value);
                    }
                }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-annotation-property-invalid.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful)
        val diagnostic = result.loweringDiagnostics.single {
            it.code == "CPLUS_COMPTIME_ANNOTATION_PROPERTY"
        }
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("value"), diagnostic.span.startOffset)
        assertEquals(text.indexOf("value") + "value".length, diagnostic.span.endOffset)
        assertTrue(result.transcodedSource == null, "invalid annotation reflection must not emit C")
    }

    @Test
    fun prototypeRejectsScalarAnnotationProjectionAsAnIterable() {
        val text = """
            typedef struct ownership_t { borrowed char* input; } ownership_t;
            comptime {
                @for field in ownership_t.fields {
                    @for annotation in field.annotationsText {
                        comptime missing(annotation);
                    }
                }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-annotation-text-iterable-invalid.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful)
        val diagnostic = result.loweringDiagnostics.single {
            it.code == "CPLUS_COMPTIME_FIELD_LOOP_NESTED_ITERABLE"
        }
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("field.annotationsText"), diagnostic.span.startOffset)
        assertTrue(result.transcodedSource == null, "unsupported iterable must not emit C")
    }

    @Test
    fun prototypeExpandsNestedReflectedFieldLoopsWithIndependentScopes() {
        val text = """
            typedef struct left_t {
                int first;
                int second;
            } left_t;
            typedef struct right_t {
                int third;
                int fourth;
            } right_t;
            comptime code @emit_pair(string @left, string @right) {
                return @code { _Static_assert(sizeof(@left) > 0, @right); };
            }
            comptime {
                @for left in left_t.fields {
                    @for right in right_t.fields {
                        comptime emit_pair(left.name, right.name);
                    }
                }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-nested-field-loops.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("nested reflected-field loops should materialize")
        assertEquals(
            4,
            Regex("""_Static_assert\s*\(\s*sizeof\s*\(\s*\"(first|second)\"\s*\)\s*>\s*0""")
                .findAll(generated.code)
                .count(),
            generated.code
        )
        assertTrue("\"third\"" in generated.code, generated.code)
        assertTrue("\"fourth\"" in generated.code, generated.code)
        compileAndRunC(generated.code)

        val parsed = backend.parse(source)
        val loopResult = TreeSitterComptimeFieldLoopLowering().lower(parsed, MappedText.identity(source.sourceFile))
        assertTrue(loopResult.diagnostics.isEmpty(), loopResult.diagnostics.toString())
        val generatedInvocation = loopResult.source.text.indexOf("comptime emit_pair(\"first\", \"third\")")
        assertTrue(generatedInvocation >= 0, loopResult.source.text)
        assertEquals(text.indexOf("first"), loopResult.source.originAt(generatedInvocation + "comptime emit_pair(\"".length)?.offset)
    }

    @Test
    fun nestedReflectedFieldBindingsShadowAndRestoreTheOuterBinding() {
        val text = """
            typedef struct outer_t { int outer_name; } outer_t;
            typedef struct inner_t { int inner_name; } inner_t;
            comptime code @emit_name(string @scope, string @name) {
                return @code { _Static_assert(sizeof(@name) > 0, @scope); };
            }
            comptime {
                @for field in outer_t.fields {
                    @for field in inner_t.fields {
                        comptime emit_name("inner", field.name);
                    }
                    comptime emit_name("outer", field.name);
                }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-nested-field-shadow.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("shadowed reflected bindings should materialize")
        assertTrue(Regex("""sizeof\s*\(\s*"inner_name"\s*\)\s*>\s*0\s*,\s*"inner"""").containsMatchIn(generated.code), generated.code)
        assertTrue(Regex("""sizeof\s*\(\s*"outer_name"\s*\)\s*>\s*0\s*,\s*"outer"""").containsMatchIn(generated.code), generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeRejectsUnsupportedNestedFieldLoopIterableAtItsSourceSpan() {
        val text = """
            typedef struct ownership_t { borrowed char* input; } ownership_t;
            comptime {
                @for field in ownership_t.fields {
                    @for part in field.name { comptime missing(part); }
                }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-field-loop-invalid-nested-iterable.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful)
        val diagnostic = result.loweringDiagnostics.single {
            it.code == "CPLUS_COMPTIME_FIELD_LOOP_NESTED_ITERABLE"
        }
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("field.name"), diagnostic.span.startOffset)
    }

    @Test
    fun prototypeBoundsNestedReflectedFieldExpansion() {
        val leftFields = (0 until 257).joinToString("\n") { "int left_$it;" }
        val rightFields = (0 until 257).joinToString("\n") { "int right_$it;" }
        val text = """
            typedef struct left_t {
                $leftFields
            } left_t;
            typedef struct right_t {
                $rightFields
            } right_t;
            comptime {
                @for left in left_t.fields {
                    @for right in right_t.fields {
                        comptime emit_pair(left.name, right.name);
                    }
                }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-field-loop-limit.cp"), text)
        val parsed = backend.parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())

        val result = TreeSitterComptimeFieldLoopLowering().lower(parsed, MappedText.identity(source.sourceFile))

        val limitDiagnostics = result.diagnostics.filter { it.code == "CPLUS_COMPTIME_FIELD_LOOP_LIMIT" }
        assertEquals(1, limitDiagnostics.size, result.diagnostics.toString())
        val diagnostic = limitDiagnostics.single()
        assertTrue(diagnostic.message.contains("65536-item limit"), diagnostic.message)
        assertEquals(text.indexOf("@for right"), diagnostic.span.startOffset)
        assertEquals(text, result.source.text, "a bounded expansion failure must not emit a partial source")
    }

    @Test
    fun prototypeReflectsArrayAndPointerArrayFieldTypes() {
        val text = """
            typedef struct callbacks_t {
                int samples[4];
                int *pointers[4];
            } callbacks_t;
            comptime code @check_array(type T) {
                return @code { _Static_assert(sizeof(T) >= sizeof(int) * 4, "array field type"); };
            }
            comptime {
                @for field in callbacks_t.fields {
                    comptime check_array(field.type);
                }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-array-field-types.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("complex field types should be reflected")
        assertFalse("@for" in generated.code, generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeAcceptsFunctionPointerAbstractTypeArguments() {
        val text = """
            comptime code @check_callback(type T) {
                return @code {
                    _Static_assert(_Generic((T)0, int (*)(int): 1, default: 0), "callback type");
                };
            }
            comptime check_callback(int (*)(int));
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-function-pointer-type.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("function pointer type argument should materialize")
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeParsesFunctionPointerFieldsInOrdinaryCStructs() {
        val text = """
            typedef struct callbacks_t {
                int (*transform)(int value);
            } callbacks_t;
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-function-pointer-field-parse.cp"), text)

        val parsed = backend.parse(source)

        assertTrue(parsed.diagnostics.isEmpty(), "parser=${parsed.diagnostics}; root=${parsed.root}")
    }

    @Test
    fun prototypeReflectsFunctionPointerFieldType() {
        val text = """
            typedef struct callbacks_t {
                int (*transform)(int value);
            } callbacks_t;
            comptime code @check_callback(type T) {
                return @code {
                    _Static_assert(_Generic((T)0, int (*)(int): 1, default: 0), "callback type");
                };
            }
            comptime {
                @for field in callbacks_t.fields {
                    comptime check_callback(field.type);
                }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-function-pointer-field.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("function-pointer field type should materialize")
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeReflectsNamedBitfieldType() {
        val text = """
            typedef struct flags_t {
                unsigned int enabled:1;
                signed int count:7;
            } flags_t;
            comptime code @check_bitfield(type T, string @name) {
                return @code {
                    _Static_assert(sizeof(T) == sizeof(int), @name);
                };
            }
            comptime {
                @for field in flags_t.fields {
                    comptime check_bitfield(field.type, field.name);
                }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-named-bitfield.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("named bitfield types should be reflected")
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeReflectsUnnamedBitfieldAsAnEmptyNamedFieldEntry() {
        val text = """
            typedef struct flags_t {
                unsigned int :1;
                int value;
            } flags_t;
            comptime code @assert_field(type T, string @name) {
                return @code { _Static_assert(sizeof(T) > 0, @name); };
            }
            comptime {
                @for field in flags_t.fields {
                    comptime assert_field(field.type, field.name);
                }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-unnamed-bitfield.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("unnamed bitfield metadata should be emitted")
        assertTrue(", \"\")" in generated.code, generated.code)
        assertTrue(", \"value\")" in generated.code, generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeDefersReflectedFieldLoopsInsideGeneratorsUntilMaterialization() {
        val text = """
            typedef struct point_t { int x; } point_t;
            comptime code @generate_field_checks() {
                return @code {
                    comptime code @assert_field(type T, string @name) {
                        return @code { _Static_assert(sizeof(T) > 0, @name); };
                    }
                    comptime {
                        @for field in point_t.fields {
                            comptime assert_field(field.type, field.name);
                        }
                    }
                };
            }
            comptime generate_field_checks();
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-generated-field-loop.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("generated reflected-field checks should be emitted")
        assertTrue("_Static_assert(sizeof (int) > 0, \"x\")" in generated.code, generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeRejectsReflectedFieldIterationOutsideComptimeBlock() {
        val text = "@for field in user_t.fields { int value; }"
        val source = sources.open(SourceId.named("ast-comptime-field-loop-scope.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful)
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_FIELD_LOOP_SCOPE", diagnostic.code)
        assertEquals(text.indexOf("@for"), diagnostic.span.startOffset)
    }

    @Test
    fun prototypeMapsUnknownReflectedFieldPropertyToItsToken() {
        val text = """
            typedef struct point_t { int x; } point_t;
            comptime { @for field in point_t.fields { int value = field.missing; } }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-unknown-field-property.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful)
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_REFLECTION_PROPERTY", diagnostic.code)
        assertEquals(text.indexOf("missing"), diagnostic.span.startOffset)
        assertEquals(source.id.value, diagnostic.span.file)
    }

    @Test
    fun prototypeReflectsMultidimensionalArrayFieldType() {
        val text = """
            typedef struct point_t { int values[2][4]; } point_t;
            comptime code @check_array(type T, string @name) {
                return @code { _Static_assert(sizeof(T) == sizeof(int) * 8, @name); };
            }
            comptime {
                @for field in point_t.fields {
                    comptime check_array(field.type, field.name);
                }
            }
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-array-field.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("multidimensional array field should materialize")
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeMapsRecursiveGeneratedInvocationAfterItsGeneratorIsConsumed() {
        val text = """
            comptime code @stage_a() {
                return @code {
                    comptime code @stage_b() { return @code { comptime stage_a(); }; }
                    comptime stage_b();
                };
            }
            comptime stage_a();
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-repeated-source-state.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful, "recursive generated code must fail closed after its generator is consumed")
        val diagnostic = result.loweringDiagnostics.singleOrNull()
            ?: error("expected an unresolved generated invocation: ${result.parserDiagnostics}; ${result.loweringDiagnostics}")
        assertEquals("CPLUS_COMPTIME_UNRESOLVED_GENERATOR", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        val invocation = "comptime stage_a();"
        val generatedInvocationOffset = text.indexOf(invocation)
        assertEquals(generatedInvocationOffset, diagnostic.span.startOffset)
        assertEquals(generatedInvocationOffset + invocation.length, diagnostic.span.endOffset)
    }

    @Test
    fun prototypeStopsNestedCodeExpansionAtTheConfiguredPassLimit() {
        val lastStage = 130
        fun generator(stage: Int): String {
            val emitted = if (stage == lastStage) {
                "int materialized_after_limit = 42;"
            } else {
                "${generator(stage + 1)} comptime step${stage + 1}();"
            }
            return "comptime code @step$stage() { return @code { $emitted }; }"
        }
        val text = "${generator(0)}\ncomptime step0();\nint main(void) { return 0; }"
        val source = sources.open(SourceId.named("ast-comptime-pass-limit.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful, "a generated declaration chain beyond the pass bound must fail closed")
        val diagnostic = result.loweringDiagnostics.singleOrNull()
            ?: error("pass exhaustion should have one diagnostic: parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}")
        assertEquals("CPLUS_COMPTIME_ENTITY_LIMIT", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
    }

    @Test
    fun prototypeRejectsNonTypeExpressionForGenericStructParameterAtArgumentSpan() {
        val text = """
            comptime type @box(type T) {
                return @code { struct box { T value; }; };
            }
            comptime typedef box(1 + 2) invalid_box_t;
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-generic-struct-argument.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_ENTITY_ARGUMENT", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals("1 + 2", text.substring(diagnostic.span.startOffset, diagnostic.span.endOffset))
    }

    @Test
    fun prototypeDiagnosesGenericStructTypedefAndSharedTagNamespaceCollisions() {
        val cases = listOf(
            """
                typedef int int_box_t;
                comptime type @box(type T) { return @code { struct box { T value; }; }; }
                comptime typedef box(int) int_box_t;
            """.trimIndent() to "comptime typedef box(int)",
            """
                struct box__int_box_t { long existing; };
                comptime type @box(type T) { return @code { struct box { T value; }; }; }
                comptime typedef box(int) int_box_t;
            """.trimIndent() to "comptime typedef box(int)",
            """
                union box__int_box_t { long existing; };
                comptime type @box(type T) { return @code { struct box { T value; }; }; }
                comptime typedef box(int) int_box_t;
            """.trimIndent() to "comptime typedef box(int)",
            """
                enum box__int_box_t { EXISTING_TAG };
                comptime type @box(type T) { return @code { struct box { T value; }; }; }
                comptime typedef box(int) int_box_t;
            """.trimIndent() to "comptime typedef box(int)"
        )

        cases.forEachIndexed { index, (text, collisionSite) ->
            val source = sources.open(SourceId.named("ast-comptime-collision-$index.cp"), text)
            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

            val diagnostic = result.loweringDiagnostics.singleOrNull()
                ?: error("collision case $index produced no lowering diagnostic: parser=${result.parserDiagnostics}; output=${result.transcodedSource?.code}")
            assertEquals("CPLUS_COMPTIME_ENTITY_COLLISION", diagnostic.code)
            assertEquals(source.id.value, diagnostic.span.file)
            assertEquals(text.indexOf(collisionSite), diagnostic.span.startOffset)
        }
    }

    @Test
    fun prototypeDoesNotTreatBlockScopeTagsAsFileScopeGeneratorCollisions() {
        val text = """
            static void local_tag(void) {
                union box__int_box_t { long local_value; };
                enum local_kind { LOCAL_KIND };
            }
            comptime type @box(type T) { return @code { struct box { T value; }; }; }
            comptime typedef box(int) int_box_t;
            int main(void) { int_box_t value = { 42 }; return value.value == 42 ? 0 : 1; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-local-tag-scope.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.transcodedSource ?: error("valid file-scope specialization should materialize")
        assertTrue("struct box__int_box_t" in generated.code, generated.code)
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val run = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        assertEquals(0, run.first, run.second)
    }

    @Test
    fun prototypeCompletesCompatibleFileScopeStructForwardTag() {
        val text = """
            struct box__int_box_t;
            comptime type @box(type T) { return @code { struct box { T value; }; }; }
            comptime typedef box(int) int_box_t;
            int main(void) { int_box_t value = { 42 }; return value.value == 42 ? 0 : 1; }
        """.trimIndent()
        val source = sources.open(SourceId.named("ast-comptime-forward-tag.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        val generated = result.transcodedSource ?: error("the generated struct should complete its forward tag")
        val compiler = listOf("cc", "gcc", "clang").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return
        val run = compileAndCaptureC(compiler, generated.code, generated.compilerOptions)
        assertEquals(0, run.first, run.second)
    }

    @Test
    fun prototypeRejectsGeneratedEntitiesCollidingWithOrdinaryCIdentifiers() {
        val cases = listOf(
            """
                int int_box_t(void) { return 0; }
                comptime type @box(type T) { return @code { struct box { T value; }; }; }
                comptime typedef box(int) int_box_t;
            """.trimIndent() to "comptime typedef box(int)",
            """
                int int_box_t;
                comptime type @box(type T) { return @code { struct box { T value; }; }; }
                comptime typedef box(int) int_box_t;
            """.trimIndent() to "comptime typedef box(int)",
            """
                enum { int_box_t };
                comptime type @box(type T) { return @code { struct box { T value; }; }; }
                comptime typedef box(int) int_box_t;
            """.trimIndent() to "comptime typedef box(int)",
            """
                int generated_value(void);
                comptime function @make() {
                    return int generated_value(void) { return 42; }
                }
                comptime make();
            """.trimIndent() to "comptime make()",
            """
                int generated_value;
                comptime function @make() {
                    return int generated_value(void) { return 42; }
                }
                comptime make();
            """.trimIndent() to "comptime make()",
            """
                comptime type @box(type T) { return @code { struct box { T value; }; }; }
                comptime function @make() {
                    return int int_box_t(void) { return 42; }
                }
                comptime typedef box(int) int_box_t;
                comptime make();
            """.trimIndent() to "comptime make()",
            """
                int second_generated;
                comptime variable @make() {
                    return int first_generated = 1, second_generated = 2;
                }
                comptime make();
            """.trimIndent() to "comptime make()"
        )

        cases.forEachIndexed { index, (text, invocationText) ->
            val source = sources.open(SourceId.named("ast-comptime-ordinary-name-collision-$index.cp"), text)
            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)
            val diagnostic = result.loweringDiagnostics.singleOrNull()
                ?: error("collision case $index produced no lowering diagnostic: parser=${result.parserDiagnostics}; output=${result.transcodedSource?.code}")

            assertEquals("CPLUS_COMPTIME_ENTITY_COLLISION", diagnostic.code)
            assertEquals(text.indexOf(invocationText), diagnostic.span.startOffset)
        }
    }

    @Test
    fun generatedComptimeDeclarationsEnterTheModuleScopeOnTheNextPass() {
        val text = """
            int generated_value;
            comptime code @outer() {
                return @code {
                    comptime function @inner() {
                        return int generated_value(void) { return 42; }
                    }
                    comptime inner();
                };
            }
            comptime outer();
        """.trimIndent()
        val source = sources.open(SourceId.named("generated-comptime-module-collision.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful, "generated module declarations must share the module namespace")
        val diagnostic = result.loweringDiagnostics.single {
            it.code == "CPLUS_COMPTIME_ENTITY_COLLISION"
        }
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("comptime inner()"), diagnostic.span.startOffset)
        assertTrue(result.transcodedSource == null, "a generated-name collision must fail before C emission")
    }

    @Test
    fun prototypeRejectsRecursiveAndWrongArityScalarComptimeFunctionCalls() {
        val cases = listOf(
            """
                comptime int @loop(int value) { return loop(value); }
                int result = comptime loop(1);
            """ to "CPLUS_COMPTIME_SCALAR_FUNCTION_RECURSION",
            """
                comptime int @twice(int value) { return value * 2; }
                int result = comptime twice();
            """ to "CPLUS_COMPTIME_SCALAR_FUNCTION_ARITY"
        )

        cases.forEachIndexed { index, (textBlock, expectedCode) ->
            val text = textBlock.trimIndent()
            val source = sources.open(SourceId.named("scalar-comptime-function-error-$index.cp"), text)
            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

            assertFalse(result.successful, "case $index should fail closed")
            val diagnostic = result.loweringDiagnostics.singleOrNull { it.code == expectedCode }
                ?: error("case $index expected $expectedCode; parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
            assertEquals(source.id.value, diagnostic.span.file)
            assertTrue(diagnostic.span.startOffset in text.indices)
        }
    }

    @Test
    fun prototypeReportsScalarComptimeErrorsAtTheirAstSourceLocations() {
        val text = "comptime int @bad = 12 / 0;\nint value = comptime bad;"
        val source = sources.open(SourceId.named("scalar-comptime-error.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful)
        assertEquals(1, result.loweringDiagnostics.size, result.loweringDiagnostics.toString())
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_SCALAR_ARITHMETIC", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("12 / 0"), diagnostic.span.startOffset)
    }

    @Test
    fun prototypeRejectsScalarComptimeSignedOverflowAndUnsafeShiftCases() {
        val cases = listOf(
            "comptime long long @bad = 9223372036854775807LL + 1LL;" to "CPLUS_COMPTIME_SCALAR_ARITHMETIC",
            "comptime long long @min = -9223372036854775807LL - 1LL; comptime long long @bad = min / -1LL;" to "CPLUS_COMPTIME_SCALAR_ARITHMETIC",
            "comptime long long @bad = -1LL << 1;" to "CPLUS_COMPTIME_SCALAR_ARITHMETIC",
            "comptime long long @bad = -1LL >> 1;" to "CPLUS_COMPTIME_SCALAR_ARITHMETIC",
            "comptime long long @bad = 1LL << 63;" to "CPLUS_COMPTIME_SCALAR_ARITHMETIC",
            "comptime long long @bad = 0x8000000000000000ULL;" to "CPLUS_COMPTIME_SCALAR_WIDTH"
        )

        cases.forEachIndexed { index, (text, expectedCode) ->
            val source = sources.open(SourceId.named("scalar-comptime-boundary-$index.cp"), text)
            val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

            assertFalse(result.successful, "case $index should fail closed")
            val diagnostic = result.loweringDiagnostics.singleOrNull { it.code == expectedCode }
                ?: error("case $index expected $expectedCode; parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}")
            assertEquals(source.id.value, diagnostic.span.file)
            assertTrue(diagnostic.span.startOffset in text.indices)
        }
    }

    @Test
    fun prototypeMaterializesTheFullUnsignedLongLongDomain() {
        val text = """
            comptime unsigned long long @maximum = 18446744073709551615ULL;
            comptime unsigned long long @wrapped = maximum + 1ULL;
            comptime unsigned long long @high_bit = 1ULL << 63;
            comptime unsigned long long @casted = (unsigned long long)-1;
            comptime bool @ordered = maximum > 9223372036854775807LL;
            comptime unsigned long long @identity(unsigned long long value) { return value; }
            unsigned long long runtime_maximum = comptime identity(maximum);
            unsigned long long runtime_wrapped = comptime wrapped;
            unsigned long long runtime_high_bit = comptime high_bit;
            unsigned long long runtime_casted = comptime casted;
            int runtime_ordered = comptime ordered;
            int main(void) {
                return runtime_maximum != 18446744073709551615ULL ||
                    runtime_wrapped != 0ULL ||
                    runtime_high_bit != 9223372036854775808ULL ||
                    runtime_casted != 18446744073709551615ULL ||
                    runtime_ordered != 1;
            }
        """.trimIndent()
        val source = sources.open(SourceId.named("scalar-comptime-unsigned-long-long-domain.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sources,
            targetOs = "linux",
            targetArch = "x86_64"
        ).transpile(source)

        assertTrue(
            result.successful,
            "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
        )
        val generated = result.transcodedSource ?: error("unsigned 64-bit values should materialize")
        assertTrue("18446744073709551615ULL" in generated.code, generated.code)
        assertTrue("9223372036854775808ULL" in generated.code, generated.code)
        compileAndRunC(generated.code)
    }

    @Test
    fun prototypeRejectsIntegerLiteralsBeyondUnsignedLongLongAtTheirSourceSpan() {
        val literal = "18446744073709551616ULL"
        val text = "comptime unsigned long long @bad = $literal;"
        val source = sources.open(SourceId.named("scalar-comptime-beyond-unsigned-long-long.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful)
        val diagnostic = result.loweringDiagnostics.single { it.code == "CPLUS_COMPTIME_SCALAR_LITERAL" }
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf(literal), diagnostic.span.startOffset)
        assertEquals(text.indexOf(literal) + literal.length, diagnostic.span.endOffset)
        assertTrue(result.transcodedSource == null)
    }

    @Test
    fun prototypeRejectsComptimeScalarTypesOutsideTheImplementedAstSubset() {
        val text = "comptime double @ratio = 1;"
        val source = sources.open(SourceId.named("unsupported-comptime-scalar-type.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful)
        val diagnostic = result.loweringDiagnostics.single { it.code == "CPLUS_COMPTIME_SCALAR_TYPE" }
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("comptime"), diagnostic.span.startOffset)
    }

    @Test
    fun prototypeRejectsScalarComptimeDeclarationsInsideRuntimeFunctions() {
        val text = "int f(void) { comptime int @local = 1; return 0; }"
        val source = sources.open(SourceId.named("local-comptime-value.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful)
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_VALUE_SCOPE", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("comptime"), diagnostic.span.startOffset)
    }

    @Test
    fun prototypeCollectsUniqueCompilerFlagsFromTheSelectedTargetBranch() {
        val text = """
            @if (os == "linux") { comptime flags -lraylib -lm "-Wl,custom"; }
            @else { comptime flags -framework Cocoa; }
            comptime flags -lm -pthread;
            int main(void) { return 0; }
        """.trimIndent()
        val source = sources.open(SourceId.named("comptime-flags-prototype.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources, targetOs = "linux").transpile(source)

        assertTrue(result.successful, "parser=${result.parserDiagnostics}; lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}")
        assertEquals(listOf("-lraylib", "-lm", "-Wl,custom", "-pthread"), result.compilerOptions)
        assertFalse("comptime flags" in result.cSource?.text.orEmpty())
    }

    @Test
    fun prototypeRejectsCompilerFlagsInsideRuntimeFunctionBodies() {
        val text = "int main(void) { comptime flags -lm; return 0; }"
        val source = sources.open(SourceId.named("local-comptime-flags.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful)
        val diagnostic = result.loweringDiagnostics.single()
        assertEquals("CPLUS_COMPTIME_FLAGS_SCOPE", diagnostic.code)
        assertEquals(source.id.value, diagnostic.span.file)
        assertEquals(text.indexOf("comptime flags"), diagnostic.span.startOffset)
    }

    private fun cplus.CPlusSyntaxNode.descendants(): List<cplus.CPlusSyntaxNode> =
        listOf(this) + children.flatMap { it.descendants() }

    private fun cplus.CPlusAstNode.descendantsAndSelf(): Sequence<cplus.CPlusAstNode> =
        sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }
}
