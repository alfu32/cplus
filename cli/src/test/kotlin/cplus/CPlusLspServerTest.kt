package cplus

import cplus.lsp.CPlusLspServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CPlusLspServerTest {
    @Test
    fun servesInitializeDiagnosticsAndShutdownOverStdio() {
        val uri = "file:///fixture.cp"
        val messages = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"languageId\":\"cplus\",\"version\":1,\"text\":\"int main( {\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\",\"params\":null}"
        ).joinToString("") { frame(it) }
        val output = ByteArrayOutputStream()

        CPlusLspServer(
            ByteArrayInputStream(messages.toByteArray(StandardCharsets.UTF_8)),
            output
        ).serve()

        val response = output.toString(StandardCharsets.UTF_8)
        assertTrue(response.contains("\"id\":1,\"result\":{\"capabilities\":"))
        assertTrue(response.contains("\"method\":\"textDocument/publishDiagnostics\""))
        assertTrue(response.contains("\"source\":\"c-plus\""))
        assertTrue(response.contains("\"id\":2,\"result\":null"))
    }

    private fun frame(message: String): String {
        val length = message.toByteArray(StandardCharsets.UTF_8).size
        return "Content-Length: $length\r\n\r\n$message"
    }
}
