package cplus.parser

import cplus.ParseCoverage
import cplus.ParserBackendId
import cplus.SourceId
import cplus.SourceManager
import cplus.CPlusAstAdapter
import cplus.CPlusAstCEmitter
import cplus.CPlusAstKind
import cplus.CPlusSemanticAnalyzer
import cplus.CPlusSymbolKind
import cplus.CPlusThrowsConvention
import cplus.CPlusDeferLoweringPass
import cplus.CPlusComptimeIndexer
import cplus.CPlusMethodCallLoweringPass
import cplus.CPlusStructMethodLoweringPass
import cplus.CPlusParserShadowRunner
import cplus.CPlusSyntaxNode
import cplus.CPlusParseResult
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

class TreeSitterCPlusParserBackendTest {
    private val backend = TreeSitterCPlusParserBackend()
    private val sources = SourceManager()

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

        assertEquals(4, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(result.diagnostics.any { it.message.contains("'mismatch' is declared warm") })
        assertTrue(result.diagnostics.any { it.message.contains("'reassigned' is declared warm") })
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
                int main(void) { char* value = 0; fill(&value); fill_matching(&value); return 0; }
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

        assertEquals(2, result.diagnostics.size, result.diagnostics.toString())
        assertTrue(result.diagnostics.any { it.message.contains("'from_assignment' is declared warm") })
        assertTrue(result.diagnostics.any { it.message.contains("'from_comma' is declared warm") })
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
        assertTrue(
            report.recognizedConstructsMatch,
            "legacy=${report.authoritativeRecognizedConstructs}; tree-sitter=${report.shadowRecognizedConstructs}; diagnostics=${report.shadow.diagnostics}; root=${report.shadow.root}"
        )
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
            assertTrue(
                treeDiagnostics.isNotEmpty() && treeRecovery.isNotEmpty(),
                "$marker was not rejected with Tree-sitter recovery diagnostics: diagnostics=$treeDiagnostics; recovery=$treeRecovery"
            )
            assertTrue(
                legacyDiagnostics.all { it.span.file == source.id.value && it.span.startOffset >= malformedStart },
                "$marker legacy diagnostic escaped its source/construct: $legacyDiagnostics"
            )
            assertTrue(
                treeDiagnostics.all { it.span.file == source.id.value && it.span.startOffset >= malformedStart },
                "$marker Tree-sitter diagnostic escaped its source/construct: $treeDiagnostics"
            )
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
                Files.walk(temporaryDirectory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
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
        assertFalse("defer" in lowered.source.text)
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
            int main(void) { work(0); return trace != 231; }
        """.trimIndent()
        val snapshot = sources.open(SourceId.named("conditional-defer.cp"), text)
        val ast = CPlusAstAdapter().adapt(backend.parse(snapshot))

        val lowered = CPlusDeferLoweringPass().lower(ast, MappedText.identity(snapshot.sourceFile))

        assertTrue(lowered.diagnostics.isEmpty(), lowered.diagnostics.toString())
        assertFalse("defer" in lowered.source.text)
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
        assertTrue(methods.text.contains("pub int counter__increment(borrowed mut counter_t *self, int amount)"), methods.text)
        assertTrue(methods.text.contains("static pub int counter__zero(void)"), methods.text)
        assertTrue(methods.text.contains("static pub counter_t *counter__create(void)"), methods.text)
        assertTrue(methods.text.contains("pub int counter__read(borrowed counter_t *self);"), methods.text)
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
                Files.walk(temporaryDirectory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
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

    private fun compileAndRunC(code: String) {
        val compilers = listOf("cc", "gcc", "clang").distinct().filter { compiler ->
            runCatching { ProcessBuilder(compiler, "--version").start().waitFor() == 0 }.getOrDefault(false)
        }
        if (compilers.isEmpty()) return
        compilers.forEach { compiler ->
            val temporaryDirectory = Files.createTempDirectory("cplus-tree-sitter-c-compat")
            try {
                val executableName = "compat-${compiler}" +
                    if (System.getProperty("os.name").startsWith("Windows", true)) ".exe" else ""
                val executable = temporaryDirectory.resolve(executableName)
                val compile = ProcessBuilder(
                    compiler, "-std=c11", "-x", "c", "-", "-o", executable.toString()
                ).redirectErrorStream(true).start()
                compile.outputStream.bufferedWriter().use { it.write(code) }
                val output = compile.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, compile.waitFor(), "$compiler rejected C11 compatibility fixture:\n$output\n$code")
                val run = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
                val runtimeOutput = run.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, run.waitFor(), "$compiler-built fixture failed:\n$runtimeOutput")
            } finally {
                Files.walk(temporaryDirectory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun assertC11Syntax(code: String, label: String) {
        val compilers = listOf("cc", "gcc", "clang").distinct().filter { compiler ->
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
