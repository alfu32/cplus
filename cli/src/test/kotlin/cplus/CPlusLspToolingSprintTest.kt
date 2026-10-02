package cplus

import cplus.lsp.CPlusLspServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Finite cross-feature acceptance fixtures for the tooling implementation batch. */
class CPlusLspToolingSprintTest {
    @Test
    fun semanticTokensClassifyTypedefDeclarationsAndUsesAtOriginalUtf16Offsets() {
        val uri = "file:///semantic.cp"
        val source = "typedef int count_t;\ncount_t value;\n// count_t is not code\n"
        val output = replay(open(uri, source), request(2, "textDocument/semanticTokens/full", uri))
        assertTrue(output.contains("semanticTokensProvider"), output)
        // deltaLine, deltaColumn, UTF-16 length, type legend index, declaration mask
        assertTrue(response(output, 2).contains("[0,12,7,0,1,1,0,7,0,0,0,8,5,7,1]"), output)
    }

    @Test
    fun servesSymbolsSignaturesFoldsAndTestsFromTheSameSnapshot() {
        val uri = "file:///tooling.cp"
        val source = """
            typedef enum state_t { READY, DONE } state_t;
            int sum(int left, int right) {
                int first, second;
                return left + right;
            }
            @test "sum works" { @assertEquals(3, sum(1, 2)); }
            int main(void) { return sum(1, 2); }
        """.trimIndent()
        val output = replay(open(uri, source), request(2, "textDocument/documentSymbol", uri),
            request(3, "textDocument/foldingRange", uri), request(4, "textDocument/codeLens", uri),
            request(5, "textDocument/signatureHelp", uri, 6, source.lineSequence().last().indexOf("2)") + 1))
        val symbols = response(output, 2)
        listOf("READY", "DONE", "left", "right", "first", "second", "sum", "main").forEach {
            assertTrue(symbols.contains("\"name\":\"$it\""), "$it absent: $symbols")
        }
        assertTrue(symbols.contains("\"selectionRange\":"), symbols)
        assertTrue(symbols.contains("\"children\":[{"), symbols)
        assertTrue(response(output, 3).contains("startLine"), output)
        assertTrue(response(output, 4).contains("sum works"), output)
        assertTrue(response(output, 5).contains("\"activeParameter\":1"), output)
    }

    @Test
    fun importedHoverUsesUsageRangeAndHighlightsStayInTheRequestedFile(@TempDir directory: Path) {
        Files.writeString(directory.resolve("dep.cp"), "\n\n\ntypedef int number_t;\n")
        val uri = directory.resolve("root.cp").toUri().toString()
        val source = "comptime import \"dep.cp\";\nnumber_t value;\n"
        val output = replay(open(uri, source), request(2, "textDocument/hover", uri, 1, 2),
            request(3, "textDocument/documentHighlight", uri, 1, 2),
            request(4, "textDocument/references", uri, 1, 2))
        assertTrue(response(output, 2).contains("\"line\":1"), output)
        assertFalse(response(output, 2).contains("\"line\":3"), output)
        assertTrue(response(output, 3).contains("\"line\":1"), output)
        assertFalse(response(output, 3).contains("\"line\":3"), output)
        assertTrue(response(output, 4).contains("dep.cp"), output)
        assertTrue(response(output, 4).contains("root.cp"), output)
    }

    @Test
    fun importedUnsavedGeneratorChangesRebuildTheRootAndCloseRestoresDisk(@TempDir directory: Path) {
        val dependency = directory.resolve("dependency.cp")
        fun generator(field: String) = "comptime type @box(type T) { return @code { struct box { T $field; }; }; }"
        Files.writeString(dependency, generator("disk_field"))
        val dependencyUri = dependency.toUri().toString()
        val uri = directory.resolve("root.cp").toUri().toString()
        val root = "comptime import \"dependency.cp\";\ncomptime typedef box(int) box_t;\nbox_t value;\nint main(void) { return value.disk_field; }\n"
        val output = replay(open(uri, root), open(dependencyUri, generator("unsaved_field")),
            request(2, "textDocument/completion", uri, 3, 30),
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"workspace/symbol\",\"params\":{\"query\":\"unsaved_field\"}}",
            close(dependencyUri),
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"workspace/symbol\",\"params\":{\"query\":\"unsaved_field\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"workspace/symbol\",\"params\":{\"query\":\"disk_field\"}}")
        assertTrue(response(output, 3).contains("unsaved_field"), output)
        assertFalse(response(output, 4).contains("unsaved_field"), output)
        assertTrue(response(output, 5).contains("disk_field"), output)
        assertEquals(generator("disk_field"), Files.readString(dependency))
    }

    @Test
    fun unsavedNewImportsResolveWithoutCreatingFiles(@TempDir directory: Path) {
        val dependency = directory.resolve("new.cp")
        val root = directory.resolve("root.cp").toUri().toString()
        val output = replay(open(dependency.toUri().toString(), "typedef struct fresh_t { int member; } fresh_t;"),
            open(root, "comptime import \"new.cp\";\nfresh_t value;\nint main(void) { return value.member; }\n"),
            request(2, "textDocument/definition", root, 1, 2))
        assertTrue(response(output, 2).contains("new.cp"), output)
        assertFalse(Files.exists(dependency))
    }

    @Test
    fun materializationFailuresAreVisibleAndDoNotKillFeatureRequests() {
        val uri = "file:///missing-import.cp"
        val output = replay(open(uri, "comptime import \"absent.cp\";\nint surviving;"),
            request(2, "textDocument/documentSymbol", uri))
        assertTrue(output.contains("CPLUS_TOOL_MATERIALIZATION"), output)
        assertTrue(response(output, 2).contains("surviving"), output)
    }

    @Test
    fun commentedImportsDoNotLeakIntoTheClosure(@TempDir directory: Path) {
        Files.writeString(directory.resolve("hidden.cp"), "int hidden_value;")
        Files.writeString(directory.resolve("active.cp"), "int active_value;")
        val uri = directory.resolve("root.cp").toUri().toString()
        val source = "// comptime import \"hidden.cp\";\ncomptime import \"active.cp\";\nint main(void) { return 0; }"
        val output = replay(open(uri, source), request(2, "textDocument/completion", uri, 2, source.lines()[2].length), targetOs = "linux")
        assertFalse(response(output, 2).contains("hidden_value"), output)
        assertTrue(response(output, 2).contains("active_value"), output)
    }

    private fun replay(vararg messages: String, targetOs: String = CPlusTarget.hostOs()): String {
        val all = listOf("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}") + messages +
            "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"shutdown\",\"params\":null}"
        val bytes = all.joinToString("") { "Content-Length: ${it.toByteArray(UTF_8).size}\r\n\r\n$it" }.toByteArray(UTF_8)
        val output = ByteArrayOutputStream()
        CPlusLspServer(ByteArrayInputStream(bytes), output, configuredTargetOs = targetOs).serve()
        return output.toString(UTF_8)
    }

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    private fun open(uri: String, text: String) = "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{\"textDocument\":{\"uri\":${quote(uri)},\"version\":1,\"text\":${quote(text)}}}}"
    private fun close(uri: String) = "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didClose\",\"params\":{\"textDocument\":{\"uri\":${quote(uri)}}}}"
    private fun request(id: Int, method: String, uri: String, line: Int? = null, character: Int = 0): String =
        "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"$method\",\"params\":{\"textDocument\":{\"uri\":${quote(uri)}}" +
            (line?.let { ",\"position\":{\"line\":$it,\"character\":$character}" } ?: "") + "}}"
    private fun response(output: String, id: Int): String = output.split("Content-Length:").firstOrNull {
        it.contains("\"id\":$id,")
    } ?: error("No response $id: $output")
}
