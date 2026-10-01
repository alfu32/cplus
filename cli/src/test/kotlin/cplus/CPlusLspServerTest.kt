package cplus

import cplus.lsp.CPlusLspServer
import cplus.lsp.unsupportedAstDiagnostics
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CPlusLspServerTest {
    @Test
    fun recordsOptInLifecycleStateForHostRestartProbes(@TempDir directory: Path) {
        val lifecycle = directory.resolve("lsp.lifecycle")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }

        CPlusLspServer(
            ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)),
            ByteArrayOutputStream(),
            lifecyclePath = lifecycle
        ).serve()

        val marker = Files.readString(lifecycle)
        assertTrue(Regex("pid=\\d+ state=stopped").matches(marker.trim()), marker)
    }

    @Test
    fun classifiesTopLevelUnmappedAstFragmentsAsMappedWarnings() {
        val source = SourceManager().open(SourceId.named("file:///synthetic-unsupported.cp"), "future_syntax")
        val fragment = CPlusAstNode(
            kind = CPlusAstKind.OTHER,
            syntaxKind = "future_syntax",
            span = source.sourceFile.span(0, source.text.length),
            fieldName = null,
            children = emptyList(),
            named = true,
            opaque = false,
            recovered = false
        )
        val ast = CPlusAst(
            source = source,
            root = CPlusAstNode(
                kind = CPlusAstKind.TRANSLATION_UNIT,
                syntaxKind = "translation_unit",
                span = source.sourceFile.span(0, source.text.length),
                fieldName = null,
                children = listOf(fragment),
                named = true,
                opaque = false,
                recovered = false
            ),
            diagnostics = emptyList(),
            structurallyComplete = true
        )

        val diagnostic = ast.unsupportedAstDiagnostics().single()
        assertTrue(diagnostic.code == "CPLUS_UNSUPPORTED_AST")
        assertTrue(diagnostic.severity == ParserDiagnosticSeverity.WARNING)
        assertTrue(diagnostic.span == fragment.span)
    }

    @Test
    fun reportsUnmappedNamedAstFragmentsAsWarningsWithoutBlockingTheSession() {
        val uri = "file:///unsupported-ast.cp"
        val source = "int value = 1;\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(
            ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)),
            output,
            astDiagnosticProvider = { ast ->
                listOf(
                    ParserDiagnostic(
                        code = "CPLUS_UNSUPPORTED_AST",
                        message = "synthetic unsupported fragment",
                        severity = ParserDiagnosticSeverity.WARNING,
                        span = ast.source.sourceFile.span(0, 3)
                    )
                )
            }
        ).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"code\":\"CPLUS_UNSUPPORTED_AST\""), response)
        assertTrue(response.contains("\"severity\":2"), response)
        assertTrue(response.contains("\"id\":2,\"result\":null"), response)
    }

    @Test
    fun publishesAnErrorForProvenAmbiguousCallableCalls() {
        val uri = "file:///ambiguous-call.cp"
        val source = """
            int choose(int value);
            int choose(int value);
            int main(void) { return choose(1); }
        """.trimIndent() + "\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(
            ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)),
            output
        ).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"code\":\"CPLUS_AMBIGUOUS_CALL\""), response)
        assertTrue(response.contains("\"severity\":1"), response)
        assertTrue(response.contains("ambiguous call to 'choose'"), response)
        assertTrue(response.contains("\"id\":2,\"result\":null"), response)
    }

    @Test
    fun publishesAnErrorForProvenAmbiguousReceiverCalls() {
        val uri = "file:///ambiguous-receiver-call.cp"
        val source = """
            typedef struct box_t {
                pub int choose(borrowed *self, int value);
                pub int choose(borrowed *self, int value);
            } box_t;
            int main(void) { box_t box; return box.choose(1); }
        """.trimIndent() + "\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(
            ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)),
            output
        ).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"code\":\"CPLUS_AMBIGUOUS_CALL\""), response)
        assertTrue(response.contains("ambiguous call to 'choose'"), response)
        assertTrue(response.contains("\"id\":2,\"result\":null"), response)
    }

    @Test
    fun servesSymbolsCompletionHoverDefinitionDiagnosticsAndShutdownOverStdio() {
        val uri = "file:///fixture.cp"
        val validSource = "typedef struct counter_t { int value; } counter_t;\n" +
            "int increment(counter_t* self) { return self->value; }\n"
        val encodedSource = validSource.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"initialized\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"languageId\":\"cplus\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/documentSymbol\",\"params\":{\"textDocument\":{\"uri\":\"$uri\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/completion\",\"params\":{\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":0,\"character\":18}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"textDocument/hover\",\"params\":{\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":0,\"character\":18}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"textDocument/definition\",\"params\":{\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":1,\"character\":17}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"workspace/symbol\",\"params\":{\"query\":\"counter\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"textDocument/completion\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"id\":10,\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didChange\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":2},\"contentChanges\":[{\"text\":\"int main( {\"}]}}",
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"notImplemented\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(
            ChunkedInputStream(messages.toByteArray(StandardCharsets.UTF_8), 3),
            output
        ).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":1,\"result\":{\"capabilities\":"))
        assertTrue(response.contains("\"workspaceSymbolProvider\":true"))
        assertTrue(response.contains("\"id\":2,\"result\":["))
        assertTrue(response.contains("\"name\":\"counter_t\""))
        assertTrue(response.contains("\"method\":\"textDocument/publishDiagnostics\""))
        assertTrue(response.contains("\"id\":3,\"result\":{\"isIncomplete\":false"))
        assertTrue(response.contains("\"label\":\"counter_t\""))
        assertTrue(response.contains("\"id\":4,\"result\":{\"contents\""))
        assertTrue(response.contains("\"id\":5,\"result\":[{\"uri\":\"$uri\""))
        assertTrue(response.contains("\"id\":8,\"result\":[{\"name\":\"counter_t\""))
        assertTrue(response.contains("\"id\":9,\"error\":{\"code\":-32602"))
        assertTrue(response.contains("\"id\":10,\"error\":{\"code\":-32600"))
        assertTrue(response.contains("\"source\":\"c-plus\""))
        assertTrue(response.contains("\"id\":7,\"error\":{\"code\":-32601"))
        assertTrue(response.contains("\"id\":6,\"result\":null"))
    }

    @Test
    fun exposesAdvisoryAccessAndOwnershipMetadataThroughSymbolsAndHover() {
        val uri = "file:///ownership-metadata.cp"
        val source = "typedef struct box_t {\n" +
            "    pub int get(borrowed mut *self, owned char* output) { return 0; }\n" +
            "} box_t;\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val methodPosition = source.lines()[1].indexOf("get")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/documentSymbol\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/hover\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":1,\"character\":$methodPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"cplusAccess\":\"pub\""), response)
        assertTrue(response.contains("\"cplusAnnotations\":[\"borrowed\",\"mut\",\"owned\"]"), response)
        assertTrue(response.contains("access=pub"), response)
        assertTrue(response.contains("annotations=borrowed,mut,owned"), response)
    }

    @Test
    fun survivesDeterministicArbitraryProtocolFragmentation() {
        val uri = "file:///fragmented.cp"
        val source = "typedef struct fragmented_t { int value; } fragmented_t;\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/documentSymbol\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val bytes = messages.toByteArray(StandardCharsets.UTF_8)

        repeat(128) { seed ->
            val output = ByteArrayOutputStream()
            CPlusLspServer(
                ChunkedInputStream(bytes, Random(seed).nextInt(1, 19)),
                output
            ).serve()
            val response = output.toString(StandardCharsets.UTF_8)
            assertTrue(response.contains("\"id\":1,\"result\":{"), "initialize failed for seed $seed")
            assertTrue(response.contains("\"id\":2,\"result\":["), "symbols failed for seed $seed")
            assertTrue(response.contains("\"name\":\"fragmented_t\""), "symbol missing for seed $seed")
            assertTrue(response.contains("\"id\":3,\"result\":null"), "shutdown failed for seed $seed")
        }
    }

    @Test
    fun recoversFromMalformedFramesUnderArbitraryFragmentation() {
        val uri = "file:///fragmented-recovery.cp"
        val source = "int main(void) { return 0; }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            frame("{malformed"),
            frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"),
            frame("{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}"),
            frame("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/documentSymbol\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"}}}"),
            frame("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}")
        ).joinToString("")
        val bytes = messages.toByteArray(StandardCharsets.UTF_8)

        repeat(64) { seed ->
            val output = ByteArrayOutputStream()
            CPlusLspServer(
                ChunkedInputStream(bytes, Random(seed + 10_000).nextInt(1, 23)),
                output
            ).serve()
            val response = output.toString(StandardCharsets.UTF_8)
            assertTrue(response.contains("\"code\":-32700"), "parse error missing for seed $seed")
            assertTrue(response.contains("\"id\":1,\"result\":{"), "initialize failed for seed $seed")
            assertTrue(response.contains("\"id\":2,\"result\":["), "symbols failed for seed $seed")
            assertTrue(response.contains("\"id\":3,\"result\":null"), "shutdown failed for seed $seed")
        }
    }

    @Test
    fun preservesUtf16DefinitionPositionsAfterSupplementaryCharacters() {
        val uri = "file:///utf16.cp"
        val source = "int main(void) { /* 🌍 */ int value = 1; return value; }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val declaration = source.indexOf("value")
        val reference = source.lastIndexOf("value")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":0,\"character\":$reference}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{"), response)
        assertTrue(response.contains("\"start\":{\"line\":0,\"character\":$declaration}"), response)
    }

    @Test
    fun recoversAfterMalformedJsonFrameAndContinuesTheSession() {
        val messages = listOf(
            frame("{not-json"),
            frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"),
            frame("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\",\"params\":null}")
        ).joinToString("")
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":1,\"result\":{"), response)
        assertTrue(response.contains("\"id\":2,\"result\":null"), response)
    }

    @Test
    fun writesAnOptInMethodTraceWithoutContaminatingProtocolOutput(@TempDir tempDir: Path) {
        val uri = "file:///trace.cp"
        val source = "int main(void) { return 0; }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()
        val trace = tempDir.resolve("lsp.trace")

        CPlusLspServer(
            ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)),
            output,
            tracePath = trace
        ).serve()

        val protocol = output.toString(StandardCharsets.UTF_8)
        val events = Files.readString(trace)
        assertTrue(protocol.contains("\"id\":1,\"result\":{"), protocol)
        assertTrue(protocol.contains("\"id\":2,\"result\":null"), protocol)
        assertTrue(events.contains("in method=initialize"), events)
        assertTrue(events.contains("in method=textDocument/didOpen"), events)
        assertTrue(events.contains("out method=textDocument/publishDiagnostics"), events)
    }

    @Test
    fun survivesDeterministicUnicodeAndMalformedEditMatrix() {
        /*
         * textDocumentSync is deliberately full-document in the current
         * protocol contract.  Exercise that contract with several generated
         * edit sequences: valid snapshots, malformed recovery snapshots, and
         * valid snapshots whose declarations move after supplementary Unicode
         * characters.  Every request is tied to the revision immediately
         * before it, so an accepted response must describe the current text.
         */
        repeat(32) { seed ->
            val uri = "file:///edit-matrix-$seed.cp"
            val snapshots = buildList {
                repeat(6) { step ->
                    if ((seed + step) % 3 == 1) {
                        add("/* seed $seed 🌍 step $step */\ntypedef struct broken_${seed}_${step}_t {")
                    } else {
                        val name = "matrix_${seed}_${step}_t"
                        add(
                            "/* seed $seed 🌍 step $step */\n" +
                                "typedef struct $name { int value; } $name;\n" +
                                "int read_${seed}_${step}($name* self) { return self->value; }\n"
                        )
                    }
                }
                val step = 6
                val name = "matrix_${seed}_final_t"
                add(
                    "/* seed $seed 🌍 step $step */\n" +
                        "typedef struct $name { int value; } $name;\n" +
                        "int read_${seed}_final($name* self) { return self->value; }\n"
                )
            }
            fun encode(value: String): String = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")

            val messages = buildString {
                append(frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                append(frame("{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                    "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"${encode(snapshots.first())}\"}}}"))
                snapshots.drop(1).forEachIndexed { index, snapshot ->
                    append(frame("{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didChange\",\"params\":{" +
                        "\"textDocument\":{\"uri\":\"$uri\",\"version\":${index + 2}}," +
                        "\"contentChanges\":[{\"text\":\"${encode(snapshot)}\"}]}}"))
                }
                val finalSource = snapshots.last()
                val finalName = "matrix_${seed}_final_t"
                append(frame("{\"jsonrpc\":\"2.0\",\"id\":100,\"method\":\"textDocument/documentSymbol\",\"params\":{" +
                    "\"textDocument\":{\"uri\":\"$uri\"}}}"))
                append(frame("{\"jsonrpc\":\"2.0\",\"id\":999,\"method\":\"shutdown\",\"params\":null}"))
            }

            val output = ByteArrayOutputStream()
            CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()
            val response = output.toString(StandardCharsets.UTF_8)

            assertTrue(response.contains("\"id\":1,\"result\":{"), "initialize failed for seed $seed")
            assertTrue(response.contains("\"id\":100,\"result\":[{\"name\":\"matrix_${seed}_final_t\""))
            assertTrue(response.contains("\"code\":\"TS_ERROR_NODE\""), "malformed edit diagnostics missing for seed $seed")
            assertTrue(response.contains("\"id\":999,\"result\":null"), "shutdown failed for seed $seed")
        }
    }

    @Test
    fun rejectsValidNonObjectJsonAndContinuesTheSession() {
        val messages = listOf(
            frame("[]"),
            frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"),
            frame("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\",\"params\":null}")
        ).joinToString("")
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":null,\"error\":{\"code\":-32600"), response)
        assertTrue(response.contains("\"id\":1,\"result\":{"), response)
        assertTrue(response.contains("\"id\":2,\"result\":null"), response)
    }

    @Test
    fun resolvesTheNearestLexicalDeclarationForAReference() {
        val uri = "file:///scope.cp"
        val source = "int f(void) {\n" +
            "    int value = 1;\n" +
            "    { int value = 2;\n" +
            "      return value; }\n" +
            "}\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":3,\"character\":14}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\""))
        assertTrue(response.contains("\"start\":{\"line\":2,\"character\":10}"))
    }

    @Test
    fun resolvesAnOverloadedFunctionByCallArity() {
        val uri = "file:///overload.cp"
        val source = "int choose(int value) { return value; }\n" +
            "int choose(int first, int second) { return first + second; }\n" +
            "int main(void) { return choose(1, 2); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val callPosition = source.lines()[2].indexOf("choose")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$callPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":1,"), response)
    }

    @Test
    fun resolvesAnOverloadedFunctionByPrimitiveArgumentType() {
        val uri = "file:///overload-types.cp"
        val source = "int choose(int value) { return value; }\n" +
            "int choose(const char* value) { return value[0]; }\n" +
            "int main(void) { return choose(\"x\"); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val callPosition = source.lines()[2].indexOf("choose")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$callPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":1,"), response)
    }

    @Test
    fun resolvesAnOverloadForDereferencedPointerArgument() {
        val uri = "file:///overload-dereference.cp"
        val source = "int choose(int value) { return value; }\n" +
            "int choose(const char* value) { return value[0]; }\n" +
            "int main(void) { int value = 1; int* pointer = &value; return choose(*pointer); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val callPosition = source.lines()[2].indexOf("choose")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$callPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":0,"), response)
    }

    @Test
    fun resolvesAnOverloadForUnaryNumericArgument() {
        val uri = "file:///overload-unary.cp"
        val source = "int choose(int value) { return value; }\n" +
            "int choose(const char* value) { return value[0]; }\n" +
            "int main(void) { return choose(-1); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val callPosition = source.lines()[2].indexOf("choose")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$callPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":0,"), response)
    }

    @Test
    fun resolvesAnOverloadForExplicitCastArgument() {
        val uri = "file:///overload-cast.cp"
        val source = "int choose(int value) { return value; }\n" +
            "int choose(const char* value) { return value[0]; }\n" +
            "int main(void) { const char* text = \"x\"; return choose((const char*)text); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val callPosition = source.lines()[2].indexOf("choose")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$callPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":1,"), response)
    }

    @Test
    fun resolvesOverloadsForCIntegerAndFloatingLiteralSuffixes() {
        val uri = "file:///overload-literal-suffixes.cp"
        val source = "int choose(int value) { return value; }\n" +
            "int choose(long value) { return (int)value; }\n" +
            "int choose(float value) { return (int)value; }\n" +
            "int main(void) { return choose(1L) + choose(1.0f); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val line = source.lines()[3]
        val longPosition = line.indexOf("choose(1L")
        val floatPosition = line.indexOf("choose(1.0f")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":3,\"character\":$longPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":3,\"character\":$floatPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":1,"), response)
        assertTrue(response.contains("\"id\":3,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":2,"), response)
    }

    @Test
    fun resolvesCIntegerPromotionsBeforeWiderOverloads() {
        val uri = "file:///overload-integer-promotions.cp"
        val source = "int choose(int value) { return value; }\n" +
            "long choose(long value) { return value; }\n" +
            "double choose(double value) { return value; }\n" +
            "int main(void) { char c = 1; short s = 2; _Bool b = 1; return choose(c) + choose(s) + choose(b); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val line = source.lines()[3]
        val charPosition = line.indexOf("choose(c")
        val shortPosition = line.indexOf("choose(s")
        val boolPosition = line.indexOf("choose(b")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":3,\"character\":$charPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":3,\"character\":$shortPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":3,\"character\":$boolPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        val expectedIntDefinition = "\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":0,"
        assertEquals(3, response.split(expectedIntDefinition).size - 1, response)
    }

    @Test
    fun ranksClosestNumericConversionWhenNoExactOverloadExists() {
        val uri = "file:///overload-numeric.cp"
        val source = "long choose(long value) { return value; }\n" +
            "double choose(double value) { return value; }\n" +
            "int main(void) { return choose(1); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val callPosition = source.lines()[2].indexOf("choose")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$callPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":0"), response)
    }

    @Test
    fun failsClosedForEquallyRankedCallableCandidates() {
        val uri = "file:///overload-ambiguous.cp"
        val source = "int choose(long value) { return (int)value; }\n" +
            "int choose(long value) { return (int)value + 1; }\n" +
            "int main(void) { return choose(1); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val callPosition = source.lines()[2].indexOf("choose")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$callPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[]"), response)
    }

    @Test
    fun keepsFreeFunctionSelectionSeparateFromSameNamedMethods() {
        val uri = "file:///free-method-name.cp"
        val source = "typedef struct holder_t {\n" +
            "    pub int choose(borrowed *self, int value) { return value; }\n" +
            "} holder_t;\n" +
            "int choose(int value) { return value + 1; }\n" +
            "int main(void) { return choose(1); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val callPosition = source.lines()[4].indexOf("choose")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":4,\"character\":$callPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":3"), response)
    }

    @Test
    fun resolvesOverloadedInstanceMethodByExplicitArgumentType() {
        val uri = "file:///overload-methods.cp"
        val source = "typedef struct chooser_t {\n" +
            "    pub int choose(borrowed *self, int value) { return value; }\n" +
            "    pub int choose(borrowed *self, const char* value) { return value[0]; }\n" +
            "} chooser_t;\n" +
            "int main(void) { chooser_t chooser; return chooser.choose(\"x\"); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val callPosition = source.lines()[4].lastIndexOf("choose")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":4,\"character\":$callPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\""), response)
        assertTrue(response.contains("\"start\":{\"line\":2"), response)
    }

    @Test
    fun resolvesOverloadedFunctionReturnReceiverByExplicitArgumentType() {
        val uri = "file:///overload-return.cp"
        val source = "typedef struct int_box_t { int value; } int_box_t;\n" +
            "typedef struct text_box_t { int value; } text_box_t;\n" +
            "int_box_t make(int value) { int_box_t box; return box; }\n" +
            "text_box_t make(const char* value) { text_box_t box; return box; }\n" +
            "int main(void) { return make(\"x\").value; }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val callPosition = source.lines()[4].lastIndexOf("make")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":4,\"character\":$callPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":3"), response)
    }

    @Test
    fun resolvesOverloadedMethodReturnReceiverByExplicitArgumentType() {
        val uri = "file:///overload-method-return.cp"
        val source = "typedef struct int_box_t { int value; } int_box_t;\n" +
            "typedef struct text_box_t { int value; } text_box_t;\n" +
            "typedef struct maker_t {\n" +
            "    pub int_box_t make(borrowed *self, int value) { int_box_t box; return box; }\n" +
            "    pub text_box_t make(borrowed *self, const char* value) { text_box_t box; return box; }\n" +
            "} maker_t;\n" +
            "int main(void) { maker_t maker; return maker.make(\"x\").value; }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val callPosition = source.lines()[6].lastIndexOf("make")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":6,\"character\":$callPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":4"), response)
    }

    @Test
    fun failsClosedForAmbiguousOverloadedFunctionReturnReceivers() {
        val uri = "file:///ambiguous-return-receiver.cp"
        val source = "typedef struct int_box_t { int value; } int_box_t;\n" +
            "typedef struct text_box_t { char* text; } text_box_t;\n" +
            "int_box_t make(long value) { int_box_t box; return box; }\n" +
            "text_box_t make(long value) { text_box_t box; return box; }\n" +
            "int main(void) { return make(1).; }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val line = source.lines()[4]
        val position = line.indexOf("make(1).") + "make(1).".length
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":4,\"character\":$position}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":{\"isIncomplete\":false,\"items\":[]}"), response)
    }

    @Test
    fun doesNotInferReceiverFromKnownWrongCallArity() {
        val uri = "file:///wrong-return-arity.cp"
        val source = "typedef struct box_t { int value; } box_t;\n" +
            "box_t make(int value) { box_t box; return box; }\n" +
            "int main(void) { return make(1, 2).value; }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val dotPosition = source.lines()[2].lastIndexOf('.') + 1
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$dotPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":{\"isIncomplete\":false,\"items\":[]}"), response)
    }

    @Test
    fun resolvesAnArrayArgumentAgainstAPointerOverload() {
        val uri = "file:///overload-arrays.cp"
        val source = "int choose(int* values) { return values[0]; }\n" +
            "int choose(int value) { return value; }\n" +
            "int main(void) { int values[1] = { 7 }; return choose(values); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val callPosition = source.lines()[2].indexOf("choose")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$callPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":0,"), response)
    }

    @Test
    fun ranksPointerQualificationWithoutSelectingAnUnsafeConstConversion() {
        val uri = "file:///overload-qualified-pointers.cp"
        val source = "int choose(const int* values) { return values[0]; }\n" +
            "int choose(int* values) { return values[0]; }\n" +
            "int main(void) { int value = 1; const int fixed = 2; return choose(&value) + choose(&fixed); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val firstCall = source.lines()[2].indexOf("choose")
        val secondCall = source.lines()[2].lastIndexOf("choose")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$firstCall}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$secondCall}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":1,"), response)
        assertTrue(response.contains("\"id\":3,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":0,"), response)
    }

    @Test
    fun rejectsAnIncompatiblePointerOverload() {
        val uri = "file:///overload-incompatible-pointers.cp"
        val source = "int choose(int* values) { return values[0]; }\n" +
            "int main(void) { double value = 1.0; return choose(&value); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val callPosition = source.lines()[1].indexOf("choose")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":1,\"character\":$callPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[]"), response)
    }

    @Test
    fun ranksPointerVolatileQualificationWithoutSelectingAnUnsafeConversion() {
        val uri = "file:///overload-volatile-pointers.cp"
        val source = "int choose(volatile int* values) { return values[0]; }\n" +
            "int choose(int* values) { return values[0]; }\n" +
            "int main(void) { int value = 1; volatile int signal = 2; return choose(&value) + choose(&signal); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val firstCall = source.lines()[2].indexOf("choose")
        val secondCall = source.lines()[2].lastIndexOf("choose")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$firstCall}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$secondCall}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":1,"), response)
        assertTrue(response.contains("\"id\":3,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":0,"), response)
    }

    @Test
    fun resolvesAnObjectPointerAgainstVoidPointerParameter() {
        val uri = "file:///overload-void-pointer.cp"
        val source = "int inspect(void* value) { return value != 0; }\n" +
            "int main(void) { int value = 1; return inspect(&value); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val callPosition = source.lines()[1].indexOf("inspect")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":1,\"character\":$callPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":0,"), response)
    }

    @Test
    fun resolvesAFunctionPointerOverloadAgainstAFunctionValue() {
        val uri = "file:///overload-callable.cp"
        val source = "int increment(int value) { return value + 1; }\n" +
            "int choose(int value) { return value; }\n" +
            "int choose(int (*callback)(int)) { return callback(1); }\n" +
            "int main(void) { return choose(increment); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val callPosition = source.lines()[3].indexOf("choose")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":3,\"character\":$callPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\",\"range\":{\"start\":{\"line\":2,"), response)
    }

    @Test
    fun returnsAstReferencesWithoutMatchingCommentsOrStringLiterals() {
        val uri = "file:///references.cp"
        val source = "int increment(int value) { return value + 1; }\n" +
            "int main(void) { /* increment */ const char* text = \"increment\"; return increment(1) + increment(2); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val reference = source.lastIndexOf("increment(1)") - source.indexOf('\n') - 1
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/references\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":1,\"character\":$reference}," +
                "\"context\":{\"includeDeclaration\":true}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/references\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":1,\"character\":$reference}," +
                "\"context\":{\"includeDeclaration\":false}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        // Both requests may complete on different executor workers; count the two
        // result sets without depending on their response order.
        assertTrue(response.windowed("\"uri\":\"$uri\"".length)
            .count { it == "\"uri\":\"$uri\"" } >= 5, response)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\""), response)
        assertTrue(response.contains("\"id\":3,\"result\":[{\"uri\":\"$uri\""), response)
    }

    @Test
    fun resolvesReferencesToTheNearestShadowedVariable() {
        val uri = "file:///shadowed-references.cp"
        val source = "int target(int value) { return value; }\n" +
            "int main(void) { int target = 7; return target; }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val reference = source.lastIndexOf("target;") - source.indexOf('\n') - 1
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/references\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":1,\"character\":$reference}," +
                "\"context\":{\"includeDeclaration\":true}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\""), response)
        assertFalse(response.contains("\"start\":{\"line\":0,\"character\":4}"), response)
        assertTrue(response.contains("\"start\":{\"line\":1,\"character\":21}"), response)
        assertTrue(response.contains("\"start\":{\"line\":1,\"character\":40}"), response)
    }

    @Test
    fun classifiesDocumentHighlightsAsReadsAndWrites() {
        val uri = "file:///highlight-effects.cp"
        val source = "int counter = 0;\n" +
            "int bump(int value) { return value + 1; }\n" +
            "int main(void) {\n" +
            "    counter = bump(counter);\n" +
            "    counter++;\n" +
            "    return counter;\n" +
            "}\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/documentHighlight\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":0,\"character\":5}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"documentHighlightProvider\":true"), response)
        assertTrue(response.contains("\"id\":2,\"result\":["), response)
        assertTrue(response.contains("\"kind\":3"), response)
        assertTrue(response.contains("\"kind\":2"), response)
    }

    @Test
    fun classifiesIndirectPointerAssignmentsAsPointerReads() {
        val uri = "file:///highlight-pointer-dereference.cp"
        val source = "int main(void) {\n" +
            "    int value = 0;\n" +
            "    int *pointer = &value;\n" +
            "    *pointer = 7;\n" +
            "    pointer = &value;\n" +
            "    return *pointer;\n" +
            "}\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/documentHighlight\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":9}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        val result = response.substringAfter("\"id\":2,\"result\":[").substringBefore("]}")
        assertTrue(result.contains("\"kind\":3"), response)
        assertTrue(result.contains("\"kind\":2"), response)
        assertTrue(result.count { it == '{' } >= 4, response)
    }

    @Test
    fun resolvesReferencesToTheReceiverMethodOwner() {
        val uri = "file:///method-references.cp"
        val source = "typedef struct left_t { pub int get(borrowed *self) { return 1; } } left_t;\n" +
            "typedef struct right_t { pub int get(borrowed *self) { return 2; } } right_t;\n" +
            "int main(void) { left_t left; right_t right; return left.get() + right.get(); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val call = source.indexOf("left.get") + "left.".length
        val character = call - source.lastIndexOf('\n', call) - 1
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/references\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$character}," +
                "\"context\":{\"includeDeclaration\":true}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        val uriOccurrences = response.windowed("\"uri\":\"$uri\"".length)
            .count { it == "\"uri\":\"$uri\"" }
        assertTrue(uriOccurrences == 3, response)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\""), response)
    }

    @Test
    fun resolvesStaticMethodReferencesByTypeOwner() {
        val uri = "file:///static-method-references.cp"
        val source = "typedef struct left_t { static pub int make(void) { return 1; } } left_t;\n" +
            "typedef struct right_t { static pub int make(void) { return 2; } } right_t;\n" +
            "int main(void) { return left_t.make() + right_t.make(); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val call = source.indexOf("left_t.make") + "left_t.".length
        val character = call - source.lastIndexOf('\n', call) - 1
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/references\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$character}," +
                "\"context\":{\"includeDeclaration\":true}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        val uriOccurrences = response.windowed("\"uri\":\"$uri\"".length)
            .count { it == "\"uri\":\"$uri\"" }
        assertTrue(uriOccurrences == 3, response)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\""), response)
    }

    @Test
    fun resolvesFieldReferencesByReceiverOwner() {
        val uri = "file:///field-references.cp"
        val source = "typedef struct left_t { int value; } left_t;\n" +
            "typedef struct right_t { int value; } right_t;\n" +
            "int main(void) { left_t left; right_t right; return left.value + right.value; }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val field = source.indexOf("left.value") + "left.".length
        val character = field - source.lastIndexOf('\n', field) - 1
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/references\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$character}," +
                "\"context\":{\"includeDeclaration\":true}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        val uriOccurrences = response.windowed("\"uri\":\"$uri\"".length)
            .count { it == "\"uri\":\"$uri\"" }
        assertTrue(uriOccurrences == 3, response)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\""), response)
    }

    @Test
    fun resolvesFunctionReferencesUsedAsCallbackValues() {
        val uri = "file:///callback-references.cp"
        val source = "int increment(int value) { return value + 1; }\n" +
            "int apply(int (*callback)(int), int value) { return callback(value); }\n" +
            "int main(void) { int (*callback)(int) = increment; return apply(increment, 1); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val assignment = source.lastIndexOf("increment", source.indexOf("return apply"))
        val character = assignment - source.lastIndexOf('\n', assignment) - 1
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/references\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":2,\"character\":$character}," +
                "\"context\":{\"includeDeclaration\":true}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        val uriOccurrences = response.windowed("\"uri\":\"$uri\"".length)
            .count { it == "\"uri\":\"$uri\"" }
        assertTrue(uriOccurrences == 4, response)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\""), response)
    }

    @Test
    fun classifiesCallbackFunctionHighlightsAcrossAssignmentAndArgumentUse() {
        val uri = "file:///callback-highlights.cp"
        val source = "int increment(int value) { return value + 1; }\n" +
            "int apply(int (*callback)(int), int value) { return callback(value); }\n" +
            "int main(void) { int (*callback)(int) = increment; return apply(increment, 1); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/documentHighlight\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":0,\"character\":5}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        val result = response.substringAfter("\"id\":2,\"result\":[").substringBefore("]}")
        assertTrue(result.contains("\"kind\":3"), response)
        assertTrue(result.split("\"kind\":2").size - 1 >= 2, response)
    }

    @Test
    fun resolvesAReceiverMethodAgainstTheDeclaredStructType() {
        val uri = "file:///receiver.cp"
        val source = "typedef struct counter_t {\n" +
            "    pub int get(borrowed *self) { return self->value; }\n" +
            "    int value;\n" +
            "} counter_t;\n" +
            "typedef counter_t counter_alias_t;\n" +
            "int main(void) { counter_alias_t c; const counter_alias_t *const pointer = &c; return c.get() + c.value + pointer->get() + pointer->value; }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val mainLine = source.lines()[5]
        val methodPosition = mainLine.indexOf('.') + 1
        val fieldPosition = mainLine.indexOf("value") + 2
        val methodReference = mainLine.indexOf("get")
        val pointerMethodReference = mainLine.lastIndexOf("get")
        val pointerFieldPosition = mainLine.lastIndexOf("value") + 2
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":5,\"character\":$methodReference}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":5,\"character\":$methodPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":5,\"character\":$fieldPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":5,\"character\":$pointerMethodReference}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":5,\"character\":$pointerFieldPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\""), response)
        assertTrue(response.contains("\"start\":{\"line\":1,\"character\":12}"))
        assertTrue(response.contains("\"id\":4,\"result\":{\"isIncomplete\":false"), response)
        assertTrue(response.contains("\"label\":\"get\""), response)
        assertTrue(!response.contains("\"label\":\"return\""), response)
        assertTrue(response.contains("\"id\":5,\"result\":{\"isIncomplete\":false"), response)
        assertTrue(response.contains("\"label\":\"value\""), response)
        assertTrue(response.contains("\"id\":6,\"result\":[{\"uri\":\"$uri\""), response)
        assertTrue(response.contains("\"id\":7,\"result\":{\"isIncomplete\":false"), response)
    }

    @Test
    fun rejectsMemberOperatorMismatchesDuringReceiverResolution() {
        val uri = "file:///receiver-operators.cp"
        val source = "typedef struct counter_t { int value; } counter_t;\n" +
            "int main(void) {\n" +
            "    counter_t value;\n" +
            "    counter_t *pointer = &value;\n" +
            "    value->\n" +
            "    pointer.\n" +
            "}\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val lines = source.lines()
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":4,\"character\":${lines[4].length}}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":5,\"character\":${lines[5].length}}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":{\"isIncomplete\":false,\"items\":[]}"), response)
        assertTrue(response.contains("\"id\":3,\"result\":{\"isIncomplete\":false,\"items\":[]}"), response)
    }

    @Test
    fun resolvesChainedValueAndPointerFieldReceivers() {
        val uri = "file:///chained-receivers.cp"
        val source = "typedef struct inner_t {\n" +
            "    int value;\n" +
            "} inner_t;\n" +
            "typedef struct outer_t {\n" +
            "    inner_t inner;\n" +
            "    inner_t *ptr;\n" +
            "} outer_t;\n" +
            "int main(void) {\n" +
            "    outer_t outer;\n" +
            "    return outer.inner.value + outer.ptr->value;\n" +
            "}\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val lines = source.lines()
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":9,\"character\":${lines[9].indexOf("outer.inner.") + "outer.inner.".length}}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":9,\"character\":${lines[9].indexOf("outer.ptr->") + "outer.ptr->".length}}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":{\"isIncomplete\":false"), response)
        assertTrue(response.contains("\"id\":3,\"result\":{\"isIncomplete\":false"), response)
        assertTrue(response.contains("\"label\":\"value\""), response)
    }

    @Test
    fun resolvesParenthesizedAddressAndDereferenceReceivers() {
        val uri = "file:///parenthesized-receivers.cp"
        val source = "typedef struct box_t {\n" +
            "    int value;\n" +
            "} box_t;\n" +
            "int main(void) {\n" +
            "    box_t box;\n" +
            "    box_t* pointer = &box;\n" +
            "    return (&box).value + (*pointer).value;\n" +
            "}\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val line = source.lines()[6]
        val firstPosition = line.indexOf("(&box).") + "(&box).".length
        val secondPosition = line.indexOf("(*pointer).") + "(*pointer).".length
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":6,\"character\":$firstPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":6,\"character\":$secondPosition}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":{\"isIncomplete\":false"), response)
        assertTrue(response.contains("\"id\":3,\"result\":{\"isIncomplete\":false"), response)
        assertTrue(response.contains("\"label\":\"value\""), response)
    }

    @Test
    fun resolvesMethodReturnReceiversForChainedMemberCompletion() {
        val uri = "file:///method-return-receiver.cp"
        val source = "typedef struct inner_t {\n" +
            "    int value;\n" +
            "} inner_t;\n" +
            "typedef struct factory_t {\n" +
            "    pub inner_t *get(borrowed *self) { return &self->inner; }\n" +
            "    inner_t inner;\n" +
            "} factory_t;\n" +
            "int main(void) {\n" +
            "    factory_t factory;\n" +
            "    return factory.get()->value;\n" +
            "}\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val lines = source.lines()
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":9,\"character\":${lines[9].indexOf("factory.get()->") + "factory.get()->".length}}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":{\"isIncomplete\":false"), response)
        assertTrue(response.contains("\"label\":\"value\""), response)
    }

    @Test
    fun indexesComptimeMaterializedDeclarationsWithOriginalRanges() {
        val uri = "file:///generated.cp"
        val source = "comptime string @typename(type T) { return T.name; }\n" +
            "comptime type @list(type T) {\n" +
            "    return @code { struct list_of_@typename(T) { T buffer[4]; }; };\n" +
            "}\n" +
            "comptime typedef list(int) int_list_t;\n" +
            "int main(void) { int_list_t values; return 0; }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"workspace/symbol\",\"params\":{\"query\":\"int_list_t\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":5,\"character\":20}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"name\":\"int_list_t\""), response)
        assertTrue(response.contains("\"id\":3,\"result\":[{\"uri\":\"$uri\""), response)
        assertTrue(response.contains("\"uri\":\"$uri\""))
    }

    @Test
    fun resolvesReceiverCompletionThroughAComptimeMaterializedAlias() {
        val uri = "file:///generated-receiver.cp"
        val source = "comptime string @typename(type T) { return T.name; }\n" +
            "comptime type @list(type T) {\n" +
            "    return @code { struct list_of_@typename(T) { T buffer[4]; }; };\n" +
            "}\n" +
            "comptime typedef list(int) int_list_t;\n" +
            "int main(void) {\n" +
            "    int_list_t values;\n" +
            "    return values.;\n" +
            "}\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":7,\"character\":${source.lines()[7].length}}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":{\"isIncomplete\":false"), response)
        assertTrue(response.contains("\"label\":\"buffer\""), response)
    }

    @Test
    fun resolvesGeneratedComptimeMethodsThroughAReceiverAlias() {
        val uri = "file:///generated-method-receiver.cp"
        val source = "comptime string @typename(type T) { return T.name; }\n" +
            "comptime type @box(type T) {\n" +
            "    return @code { struct box { T value; pub int get(borrowed *self) { return self->value; } }; };\n" +
            "}\n" +
            "comptime typedef box(int) int_box_t;\n" +
            "int main(void) { int_box_t value; return value.; }\n" +
            "int use(int_box_t value) { return value.get(); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val line = source.lines().indexOfLast { "return value.;" in it }
        val definitionLine = source.lines().indexOfLast { "value.get()" in it }
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":$line,\"character\":${source.lines()[line].length - 1}}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":$definitionLine,\"character\":${source.lines()[definitionLine].indexOf("get")}}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":{\"isIncomplete\":false"), response)
        assertTrue(response.contains("\"label\":\"get\""), response)
        assertTrue(response.contains("\"label\":\"value\""), response)
        assertTrue(response.contains("\"id\":3,\"result\":[{\"uri\":\"$uri\""), response)
    }

    @Test
    fun invalidatesGeneratedComptimeMembersAfterDocumentChange() {
        val uri = "file:///generated-method-invalidation.cp"
        val source = "comptime type @box(type T) {\n" +
            "    return @code { struct box { T value; pub int get(borrowed *self) { return self->value; } }; };\n" +
            "}\n" +
            "comptime typedef box(int) int_box_t;\n" +
            "int main(void) { int_box_t value; return value.; }\n"
        val changed = "int main(void) { int_box_t value; return value.; }\n"
        fun encode(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        val completionLine = source.lines().indexOfLast { "return value.;" in it }
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"${encode(source)}\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":$completionLine,\"character\":${source.lines()[completionLine].length - 1}}}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didChange\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":2},\"contentChanges\":[{\"text\":\"${encode(changed)}\"}]}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":0,\"character\":${changed.lines().first().length - 1}}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":{\"isIncomplete\":false"), response)
        assertTrue(response.contains("\"id\":2,\"result\".*\"label\":\"get\"".toRegex()), response)
        val updatedCompletion = response.substringAfter("\"id\":3,\"result\":")
            .substringBefore("Content-Length")
        assertFalse(updatedCompletion.contains("\"label\":\"get\""), response)
        assertFalse(updatedCompletion.contains("\"label\":\"int_box_t\""), response)
    }

    @Test
    fun referencesGeneratedComptimeAliasThroughItsOriginalSourceLocation() {
        val uri = "file:///generated-alias-references.cp"
        val source = "comptime type @box(type T) {\n" +
            "    return @code { struct box { T value; }; };\n" +
            "}\n" +
            "comptime typedef box(int) int_box_t;\n" +
            "int read(int_box_t value) { return value.value; }\n" +
            "int main(void) { int_box_t value = {42}; return read(value) == 42 ? 0 : 1; }\n"
        fun encode(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        val usageLine = source.lines().indexOfLast { "int_box_t value =" in it }
        val usageCharacter = source.lines()[usageLine].indexOf("int_box_t") + 2
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"${encode(source)}\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/references\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":$usageLine,\"character\":$usageCharacter}," +
                "\"context\":{\"includeDeclaration\":true}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/documentHighlight\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":$usageLine,\"character\":$usageCharacter}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        val references = response.substringAfter("\"id\":2,\"result\":")
            .substringBefore("Content-Length")
        assertTrue(references.contains("\"uri\":\"$uri\""), response)
        assertTrue(references.count { it == '{' } >= 2, response)
        val highlights = response.substringAfter("\"id\":3,\"result\":")
            .substringBefore("Content-Length")
        assertTrue(highlights.contains("\"kind\":2"), response)
    }

    @Test
    fun resolvesGeneratedComptimeDeclarationsThroughAnImportedDocument(@TempDir directory: Path) {
        val dependency = directory.resolve("dependency.cp")
        Files.writeString(
            dependency,
            "comptime type @box(type T) {\n" +
                "    return @code { struct box { T value; }; };\n" +
                "}\n" +
                "comptime typedef box(int) imported_box_t;\n"
        )
        val root = directory.resolve("main.cp")
        val uri = root.toUri().toString()
        val source = "comptime import \"dependency.cp\";\n" +
            "int main(void) { imported_box_t value; return value.; }\n"
        fun encode(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        val completionLine = source.lines().indexOfLast { "return value.;" in it }
        val aliasCharacter = source.lines()[completionLine].indexOf("imported_box_t") + 2
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"${directory.toUri()}\"}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"${encode(source)}\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"workspace/symbol\",\"params\":{\"query\":\"imported_box_t\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":$completionLine,\"character\":$aliasCharacter}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":$completionLine,\"character\":${source.lines()[completionLine].length - 1}}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertResponseContains(response, "\"id\":2,\"result\":[{\"name\":\"imported_box_t\"")
        assertResponseContains(response, "\"id\":3,\"result\":[{\"uri\":\"${dependency.toUri()}\"")
        val completion = response.substringAfter("\"id\":4,\"result\":")
            .substringBefore("Content-Length")
        assertTrue(completion.contains("\"label\":\"value\""), response)
    }

    @Test
    fun ignoresStaleChangesAndClearsDiagnosticsOnClose() {
        val uri = "file:///stale.cp"
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":2,\"text\":\"int main( {\"}}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didChange\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":3},\"contentChanges\":[{\"text\":\"int main(void) { return 0; }\"}]}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didChange\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":2},\"contentChanges\":[{\"text\":\"int main( {\"}]}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didClose\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(
            ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)),
            output
        ).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"uri\":\"$uri\",\"diagnostics\":[{"))
        assertTrue(response.contains("\"uri\":\"$uri\",\"diagnostics\":[]"))
        assertTrue(response.contains("\"id\":2,\"result\":null"))
    }

    @Test
    fun suppressesARequestCanceledBeforeDispatch() {
        val uri = "file:///cancel.cp"
        val source = "int main(void) { return 0; }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"$/cancelRequest\",\"params\":{\"id\":2}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/documentSymbol\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(!response.contains("\"id\":2,\"result\":"), response)
        assertTrue(response.contains("\"id\":3,\"result\":null"), response)
    }

    @Test
    fun suppressesARequestCanceledWhileParsing() {
        val uri = "file:///cancel-during-parse.cp"
        val source = buildString {
            repeat(20_000) { append("int value_$it = $it;\n") }
            append("int main(void) { return value_19999; }\n")
        }
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/documentSymbol\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"}}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"$/cancelRequest\",\"params\":{\"id\":2}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertFalse(response.contains("\"id\":2,\"result\":"), response)
        assertTrue(response.contains("\"id\":3,\"result\":null"))
    }

    @Test
    fun reusingARequestIdDoesNotCancelACompletedFuture() {
        val uri = "file:///reused-request-id.cp"
        val source = "int answer = 42;\nint main(void) { return answer; }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/documentSymbol\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":1,\"character\":25}}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"$/cancelRequest\",\"params\":{\"id\":2}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":["), response)
        assertTrue(response.contains("\"id\":3,\"result\":null"))
    }

    @Test
    fun preCancellationIsConsumedAndDoesNotPoisonAReusedRequestId() {
        val uri = "file:///unknown-cancel.cp"
        val source = "typedef struct unknown_cancel_t { int value; } unknown_cancel_t;\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"$/cancelRequest\",\"params\":{\"id\":77}}",
            "{\"jsonrpc\":\"2.0\",\"id\":77,\"method\":\"textDocument/documentSymbol\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":77,\"method\":\"textDocument/documentSymbol\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":77,\"result\":["), response)
        assertTrue(response.contains("\"name\":\"unknown_cancel_t\""), response)
        assertTrue(response.contains("\"id\":2,\"result\":null"), response)
    }

    @Test
    fun indexesRelativeComptimeImportsForWorkspaceSymbols(@TempDir directory: Path) {
        val dependency = directory.resolve("dependency.cp")
        Files.writeString(dependency, "typedef struct imported_t { int value; } imported_t;\n")
        val root = directory.resolve("main.cp")
        val uri = root.toUri().toString()
        val source = "comptime import \"dependency.cp\";\nint main(void) { return 0; }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"${directory.toUri()}\"}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"workspace/symbol\",\"params\":{\"query\":\"imported_t\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"name\":\"imported_t\""), response)
        assertTrue(response.contains("\"uri\":\"${dependency.toUri()}\""), response)
    }

    @Test
    fun preservesLexicalImportUriThroughSymlinkedWorkspace(@TempDir directory: Path) {
        val realRoot = directory.resolve("real-root")
        Files.createDirectories(realRoot)
        val lexicalRoot = directory.resolve("lexical-root")
        val linked = runCatching { Files.createSymbolicLink(lexicalRoot, realRoot) }.isSuccess
        assumeTrue(linked, "symbolic links are unavailable on this host")

        val dependency = lexicalRoot.resolve("dependency.cp")
        Files.writeString(dependency, "typedef struct lexical_import_t { int value; } lexical_import_t;\n")
        val root = lexicalRoot.resolve("main.cp")
        val uri = root.toUri().toString()
        val source = "comptime import \"dependency.cp\";\nint main(void) { return 0; }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"${lexicalRoot.toUri()}\"}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"workspace/symbol\",\"params\":{\"query\":\"lexical_import_t\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"name\":\"lexical_import_t\""), response)
        assertTrue(response.contains("\"uri\":\"${dependency.toUri()}\""), response)
    }

    @Test
    fun invalidatesAndPrunesImportsWhenAnOpenDocumentChanges(@TempDir directory: Path) {
        val dependency = directory.resolve("dependency.cp")
        Files.writeString(dependency, "typedef struct removable_t { int value; } removable_t;\n")
        val root = directory.resolve("main.cp")
        val uri = root.toUri().toString()
        val source = "comptime import \"dependency.cp\";\nint main(void) { return 0; }\n"
        val changed = "int main(void) { return 0; }\n"
        fun encode(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"${directory.toUri()}\"}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"${encode(source)}\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"workspace/symbol\",\"params\":{\"query\":\"removable_t\"}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didChange\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":2},\"contentChanges\":[{\"text\":\"${encode(changed)}\"}]}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"workspace/symbol\",\"params\":{\"query\":\"removable_t\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"name\":\"removable_t\""), response)
        assertTrue(response.contains("\"id\":3,\"result\":[]"), response)
    }

    @Test
    fun indexesModuleImportsUsingProjectManifest(@TempDir directory: Path) {
        val modules = directory.resolve("modules")
        Files.createDirectories(modules)
        Files.writeString(directory.resolve("cplus.toml"), """
            name = "fixture"
            source = "src"
            stdlib = ""
            module-paths = ["modules"]
        """.trimIndent() + "\n")
        val dependency = modules.resolve("module_value.cp")
        Files.writeString(dependency, "typedef struct manifest_import_t { int value; } manifest_import_t;\n")
        val root = directory.resolve("main.cp")
        val uri = root.toUri().toString()
        val source = "comptime import \"module:/module_value.cp\";\nmanifest_import_t value;\nint main(void) { return value.value; }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"${directory.toUri()}\"}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"workspace/symbol\",\"params\":{\"query\":\"manifest_import_t\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":1,\"character\":5}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertResponseContains(response, "\"id\":2,\"result\":[{\"name\":\"manifest_import_t\"")
        assertResponseContains(response, "\"uri\":\"${dependency.toUri()}\"")
        assertResponseContains(response, "\"id\":4,\"result\":[{\"uri\":\"${dependency.toUri()}\"")
    }

    @Test
    fun resolvesCompletionAndDefinitionOnlyThroughTheOpenDocumentImportClosure(@TempDir directory: Path) {
        val dependency = directory.resolve("dependency.cp")
        Files.writeString(dependency, "int imported_function(int value) { return value + 1; }\n")
        val unrelated = directory.resolve("unrelated.cp")
        Files.writeString(unrelated, "int hidden_function(void) { return 0; }\n")
        val root = directory.resolve("main.cp")
        val uri = root.toUri().toString()
        val source = "comptime import \"dependency.cp\";\nint main(void) { return imported_function(1); }\n"
        fun encode(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val reference = source.indexOf("imported_function", source.indexOf("int main")) - source.indexOf('\n') - 1
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"${directory.toUri()}\"}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"${encode(source)}\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":1,\"character\":$reference}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/completion\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":1,\"character\":$reference}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertResponseContains(response, "\"id\":2,\"result\":[{\"uri\":\"${dependency.toUri()}\"")
        assertResponseContains(response, "\"id\":3,\"result\":{\"isIncomplete\":false")
        assertResponseContains(response, "\"label\":\"imported_function\"")
        assertFalse(response.contains("\"label\":\"hidden_function\""), response)
    }

    private class ChunkedInputStream(
        bytes: ByteArray,
        private val chunkSize: Int
    ) : ByteArrayInputStream(bytes) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, minOf(length, chunkSize))
    }

    private fun frame(message: String): String {
        val length = message.toByteArray(StandardCharsets.UTF_8).size
        return "Content-Length: $length\r\n\r\n$message"
    }

    private fun assertResponseContains(response: String, expected: String) {
        if (!response.contains(expected)) {
            System.err.println("LSP_RESPONSE_DIAGNOSTIC expected=$expected response=$response")
        }
        assertTrue(response.contains(expected), response)
    }
}
