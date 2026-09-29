package cplus

import cplus.lsp.CPlusLspServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.round
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Opt-in protocol latency baseline; excluded from the ordinary CLI test task. */
@Tag("lsp-benchmark")
class CPlusLspBenchmarkTest {
    @Test
    fun measuresColdProtocolSessionLatency() {
        val source = "typedef struct benchmark_t { int value; } benchmark_t;\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val uri = "file:///lsp-benchmark.cp"
        val input = listOf(
            frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"),
            frame("{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}"),
            frame("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/documentSymbol\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\"}}}"),
            frame("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":null}")
        ).joinToString("").toByteArray(StandardCharsets.UTF_8)

        repeat(2) { runSession(input) }
        val samples = List(7) {
            val start = System.nanoTime()
            repeat(3) { runSession(input) }
            (System.nanoTime() - start) / 3_000_000.0
        }.sorted()
        val median = samples[samples.lastIndex / 2]
        val average = samples.average()
        println(
            "lsp_benchmark sessions=3 samples=7 median_ms=${format(median)} " +
                "average_ms=${format(average)} fixture_bytes=${input.size}"
        )
    }

    private fun runSession(input: ByteArray) {
        val output = ByteArrayOutputStream()
        CPlusLspServer(ByteArrayInputStream(input), output).serve()
        val response = output.toString(StandardCharsets.UTF_8)
        check("\"id\":1,\"result\":{" in response) { "initialize response missing" }
        check("\"id\":2,\"result\":[" in response) { "symbol response missing" }
        check("\"id\":3,\"result\":null" in response) { "shutdown response missing" }
    }

    private fun frame(message: String): String {
        val length = message.toByteArray(StandardCharsets.UTF_8).size
        return "Content-Length: $length\r\n\r\n$message"
    }

    private fun format(value: Double): String = "%.3f".format(java.util.Locale.ROOT, round(value * 1000.0) / 1000.0)
}
