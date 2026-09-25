package cplus.parser

import cplus.ParseCoverage
import cplus.SourceId
import cplus.SourceManager
import cplus.CPlusAstAdapter
import cplus.CPlusSemanticAnalyzer
import cplus.CPlusSymbolKind
import cplus.CPlusThrowsConvention
import cplus.CPlusDeferLoweringPass
import cplus.CPlusComptimeIndexer
import cplus.CPlusMethodCallLoweringPass
import cplus.CPlusStructMethodLoweringPass
import cplus.CPlusParserShadowRunner
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
    }

    @Test
    fun normalizesCommonCDeclarationAndStatementKinds() {
        val text = """
            #include <stddef.h>
            typedef struct record_t { int field; } record_t;
            union payload { int number; char byte; };
            enum state { STATE_OFF, STATE_ON };
            int global_value;
            int prototype(int value);
            int main(void) { while (global_value) { global_value--; } return 0; }
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
        assertTrue(cplus.CPlusAstKind.UNION_DECLARATION in kinds)
        assertTrue(cplus.CPlusAstKind.ENUM_DECLARATION in kinds)
        assertTrue(cplus.CPlusAstKind.FIELD_DECLARATION in kinds)
        assertTrue(cplus.CPlusAstKind.VARIABLE_DECLARATION in kinds)
        assertTrue(cplus.CPlusAstKind.FUNCTION_DECLARATION in kinds)
        assertTrue(cplus.CPlusAstKind.PREPROCESSOR in kinds)
        assertTrue(cplus.CPlusAstKind.CONTROL_FLOW in kinds)
        assertTrue(cplus.CPlusAstKind.LITERAL in kinds)
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
            "comptime typedef dynamic_list(int) int_list_t;" to "comptime_invocation",
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
    fun shadowParserMatchesLegacyRecognizedConstructsAcrossRepresentativeStdlibFiles() {
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
            "stdlib/tests/containers.cp"
        )
        val legacy = LegacyCPlusParserBackend()
        val runner = CPlusParserShadowRunner(legacy, backend)

        modules.forEach { relativePath ->
            val text = Files.readString(repository.resolve(relativePath))
            val snapshot = sources.open(SourceId.named(relativePath), text)
            val report = runner.parse(snapshot)

            assertEquals(legacy.parse(snapshot).root, report.authoritative.root, "$relativePath authoritative result changed")
            assertTrue(
                report.recognizedConstructsMatch,
                "$relativePath: legacy=${report.authoritativeRecognizedConstructs}; tree-sitter=${report.shadowRecognizedConstructs}; diagnostics=${report.shadow.diagnostics}"
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
        assertEquals("cplus_legacy_type_generator", parsed.root.descendants().single { it.kind == "cplus_legacy_type_generator" }.kind)
        assertEquals("box", index.constructs.single().symbol)
        assertTrue(index.constructs.single().activeThisPass, "the top-level generator is registered this pass")
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
    fun keepsFunctionPointerTypedefsAndConditionalPlatformAttributesValidC() {
        val text = """
            #include <stddef.h>
            typedef int (*callback_t)(const char *value, size_t length);
            typedef const char *(*format_callback_t)(const char *format, ...);
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
            int main(void) { return apply(count_chars, "ok", 2) != 2; }
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
            int use_widget(widget_handle_t *widget) {
                widget->refresh();
                const_widget_t const_widget;
                widget_pointer_t pointer;
                const_widget.refresh();
                pointer->refresh();
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
        assertEquals(3, index.resolvedCalls.size, "qualified/pointer typedefs resolve while the int local shadows the parameter")
        assertTrue(index.resolvedCalls.all { it.methodName == "refresh" })
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
    fun prototypeTranspilerFailsClosedOnComptimeButExtractsTestFixtures() {
        val source = sources.open(
            SourceId.named("prototype-unsupported.cp"),
            "comptime int answer = 42; @test answer { return 0; }"
        )

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful)
        assertEquals(null, result.cSource)
        assertTrue(result.unsupportedNodes.any { it.syntaxKind == "cplus_comptime_declaration" }, result.unsupportedNodes.toString())
        assertTrue(result.unsupportedNodes.all { it.span.file == source.id.value })
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
        val cc = runCatching { ProcessBuilder("cc", "--version").start().waitFor() == 0 }.getOrDefault(false)
        if (!cc) return
        val temporaryDirectory = Files.createTempDirectory("cplus-tree-sitter-throws")
        try {
            val executableName = "throws-prototype" + if (System.getProperty("os.name").startsWith("Windows", true)) ".exe" else ""
            val executable = temporaryDirectory.resolve(executableName)
            val compile = ProcessBuilder("cc", "-std=c11", "-x", "c", "-", "-o", executable.toString()).start()
            compile.outputStream.bufferedWriter().use { it.write(code) }
            val errors = compile.errorStream.bufferedReader().use { it.readText() }
            assertEquals(0, compile.waitFor(), errors + "\n" + code)
            val run = ProcessBuilder(executable.toString()).start()
            assertEquals(0, run.waitFor(), run.errorStream.bufferedReader().use { it.readText() })
        } finally {
            Files.walk(temporaryDirectory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
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
        assertEquals("staged_detail", index.constructs[1].symbol)
        assertFalse(index.constructs[1].activeThisPass, "nested generator-body declarations stay dormant until materialization")
        assertTrue(index.constructs[0].activeThisPass)
        assertEquals("list", index.constructs[2].symbol)
        assertEquals(1, index.imports.size)
        assertEquals(1, index.tests.size)
        assertTrue(index.constructs.first().bodySpan != null)
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
    fun prototypeFailsClosedOnPlatformConditionalsUntilComptimeMaterializationIsIntegrated() {
        val text = "@if (os == \"linux\") { comptime flags -lX11; }"
        val source = sources.open(SourceId.named("unsupported-comptime-conditional.cp"), text)

        val result = TreeSitterCPlusPrototypeTranspiler(backend, sources).transpile(source)

        assertFalse(result.successful)
        val unsupported = result.unsupportedNodes.single { it.syntaxKind == "cplus_comptime_conditional" }
        assertEquals("cplus_comptime_conditional", unsupported.syntaxKind)
        assertEquals(text.indexOf("@if"), unsupported.span.startOffset)
        assertEquals(source.id.value, unsupported.span.file)
        assertEquals(null, result.cSource)
    }

    private fun cplus.CPlusSyntaxNode.descendants(): List<cplus.CPlusSyntaxNode> =
        listOf(this) + children.flatMap { it.descendants() }

    private fun cplus.CPlusAstNode.descendantsAndSelf(): Sequence<cplus.CPlusAstNode> =
        sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }
}
