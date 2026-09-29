package cplus

import cplus.lsp.CPlusLspServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CPlusLspServerTest {
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

        repeat(32) { seed ->
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
    fun resolvesAReceiverMethodAgainstTheDeclaredStructType() {
        val uri = "file:///receiver.cp"
        val source = "typedef struct counter_t {\n" +
            "    pub int get(borrowed *self) { return self->value; }\n" +
            "    int value;\n" +
            "} counter_t;\n" +
            "typedef counter_t counter_alias_t;\n" +
            "int main(void) { counter_alias_t c; return c.get(); }\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/definition\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":5,\"character\":46}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)), output).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":2,\"result\":[{\"uri\":\"$uri\""), response)
        assertTrue(response.contains("\"start\":{\"line\":1,\"character\":12}"))
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
        assertTrue(response.contains("\"id\":3,\"result\":null"))
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
        assertTrue(response.contains("\"id\":2,\"result\":[{\"name\":\"imported_t\""))
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
        assertTrue(response.contains("\"id\":2,\"result\":[{\"name\":\"removable_t\""))
        assertTrue(response.contains("\"id\":3,\"result\":[]"))
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
        assertTrue(response.contains("\"id\":2,\"result\":[{\"name\":\"manifest_import_t\""))
        assertTrue(response.contains("\"uri\":\"${dependency.toUri()}\""), response)
        assertTrue(response.contains("\"id\":4,\"result\":[{\"uri\":\"${dependency.toUri()}\""))
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
}
