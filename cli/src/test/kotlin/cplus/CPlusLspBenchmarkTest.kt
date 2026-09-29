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

    @Test
    fun measuresConcurrentDocumentRequestBurst() {
        val source = "typedef struct burst_t { int value; } burst_t;\n"
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val uri = "file:///lsp-burst.cp"
        val requestCount = 64
        val input = buildString {
            append(frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
            append(frame("{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}"))
            for (id in 2..(requestCount + 1)) {
                append(frame("{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"textDocument/documentSymbol\",\"params\":{" +
                    "\"textDocument\":{\"uri\":\"$uri\"}}}"))
            }
            append(frame("{\"jsonrpc\":\"2.0\",\"id\":100,\"method\":\"shutdown\",\"params\":null}"))
        }.toByteArray(StandardCharsets.UTF_8)

        repeat(2) { runBurst(input, requestCount) }
        val samples = List(5) {
            val start = System.nanoTime()
            runBurst(input, requestCount)
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        val median = samples[samples.lastIndex / 2]
        val average = samples.average()
        println(
            "lsp_burst requests=$requestCount samples=5 median_ms=${format(median)} " +
                "average_ms=${format(average)} fixture_bytes=${input.size}"
        )
    }

    @Test
    fun measuresBoundedQueueOverloadAndRecovery() {
        // Make each request materially more expensive than the reader loop so
        // this exercises the finite queue rather than merely measuring a fast
        // all-accepted burst. The benchmark is opt-in and therefore allowed to
        // use a deliberately broad source fixture.
        val source = buildString {
            repeat(256) { index ->
                append("typedef struct overload_${index}_t { int value; } overload_${index}_t;\n")
            }
        }
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val uri = "file:///lsp-overload.cp"
        val requestCount = 256
        val input = buildString {
            append(frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
            append(frame("{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                "\"textDocument\":{\"uri\":\"$uri\",\"version\":1,\"text\":\"$encodedSource\"}}}"))
            for (id in 2..(requestCount + 1)) {
                append(frame("{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"textDocument/documentSymbol\",\"params\":{" +
                    "\"textDocument\":{\"uri\":\"$uri\"}}}"))
            }
            append(frame("{\"jsonrpc\":\"2.0\",\"id\":1000,\"method\":\"shutdown\",\"params\":null}"))
        }.toByteArray(StandardCharsets.UTF_8)

        val output = ByteArrayOutputStream()
        val start = System.nanoTime()
        CPlusLspServer(ByteArrayInputStream(input), output).serve()
        val elapsed = (System.nanoTime() - start) / 1_000_000.0
        val response = output.toString(StandardCharsets.UTF_8)
        val rejected = response.countOccurrences("\"error\":{\"code\":-32001")
        val completed = response.countOccurrences("\"result\":[")
        check(rejected > 0) { "bounded queue did not report overload" }
        check(completed + rejected == requestCount) {
            "request accounting mismatch: completed=$completed rejected=$rejected total=$requestCount"
        }
        check("\"id\":1000,\"result\":null" in response) { "shutdown did not drain accepted work" }
        println(
            "lsp_overload requests=$requestCount completed=$completed rejected=$rejected " +
                "elapsed_ms=${format(elapsed)} fixture_bytes=${input.size}"
        )
    }

    @Test
    fun measuresSustainedLoadCancellationFairnessAndHeapObservation() {
        val durationSeconds = System.getProperty("cplus.lsp.benchmark.seconds", "3")
            .toLongOrNull()?.coerceIn(1, 300) ?: 3
        val enforceBudget = System.getProperty("cplus.lsp.benchmark.enforce", "false")
            .toBooleanStrictOrNull() ?: false
        val minimumRoundsPerSecond = System.getProperty(
            "cplus.lsp.benchmark.min_rounds_per_second", "1.0"
        ).toDoubleOrNull()?.coerceAtLeast(0.0) ?: 1.0
        val maximumHeapDeltaBytes = System.getProperty(
            "cplus.lsp.benchmark.max_heap_delta_bytes", (64L * 1024L * 1024L).toString()
        ).toLongOrNull()?.coerceAtLeast(0L) ?: (64L * 1024L * 1024L)
        val source = buildString {
            repeat(128) { index ->
                append("typedef struct sustained_${index}_t { int value; } sustained_${index}_t;\n")
            }
        }
        val encodedSource = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val uri = "file:///lsp-sustained.cp"
        val deadline = System.nanoTime() + durationSeconds * 1_000_000_000L
        val before = stabilizedUsedHeap()
        var rounds = 0
        var completed = 0
        var cancellationCandidates = 0

        while (System.nanoTime() < deadline || rounds == 0) {
            val input = buildString {
                append(frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                append(frame("{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{" +
                    "\"textDocument\":{\"uri\":\"$uri\",\"version\":${rounds + 1},\"text\":\"$encodedSource\"}}}"))
                for (id in 2..33) {
                    append(frame("{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"textDocument/documentSymbol\",\"params\":{" +
                        "\"textDocument\":{\"uri\":\"$uri\"}}}"))
                    if (id % 3 == 0) {
                        append(frame("{\"jsonrpc\":\"2.0\",\"method\":\"\$/cancelRequest\",\"params\":{\"id\":$id}}"))
                        cancellationCandidates++
                    }
                }
                // A request after the cancellation burst proves that the
                // executor remains live and cancellation does not starve the
                // tail of the queue.
                append(frame("{\"jsonrpc\":\"2.0\",\"id\":2000,\"method\":\"textDocument/documentSymbol\",\"params\":{" +
                    "\"textDocument\":{\"uri\":\"$uri\"}}}"))
                append(frame("{\"jsonrpc\":\"2.0\",\"id\":3000,\"method\":\"shutdown\",\"params\":null}"))
            }.toByteArray(StandardCharsets.UTF_8)
            val output = ByteArrayOutputStream()
            CPlusLspServer(ByteArrayInputStream(input), output).serve()
            val response = output.toString(StandardCharsets.UTF_8)
            check("\"id\":2000,\"result\":[" in response) { "tail request starved in round $rounds" }
            check("\"id\":3000,\"result\":null" in response) { "shutdown missing in round $rounds" }
            completed += (2..33).count { id -> "\"id\":$id,\"result\":[" in response }
            rounds++
        }

        val after = stabilizedUsedHeap()
        check(rounds > 0)
        check(completed >= rounds) { "no document request completed across $rounds rounds" }
        val heapDelta = after - before
        val roundsPerSecond = rounds.toDouble() / durationSeconds
        if (enforceBudget) {
            check(roundsPerSecond >= minimumRoundsPerSecond) {
                "sustained throughput below budget: rounds_per_second=$roundsPerSecond " +
                    "minimum=$minimumRoundsPerSecond"
            }
            check(heapDelta <= maximumHeapDeltaBytes) {
                "sustained live heap growth above budget: delta_bytes=$heapDelta " +
                    "maximum=$maximumHeapDeltaBytes"
            }
        }
        println(
            "lsp_sustained seconds=$durationSeconds rounds=$rounds completed=$completed " +
                "cancellation_candidates=$cancellationCandidates live_heap_before_bytes=$before " +
                "live_heap_after_bytes=$after live_heap_delta_bytes=$heapDelta " +
                "rounds_per_second=${format(roundsPerSecond)} enforce_budget=$enforceBudget"
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

    private fun runBurst(input: ByteArray, requestCount: Int) {
        val output = ByteArrayOutputStream()
        CPlusLspServer(ByteArrayInputStream(input), output).serve()
        val response = output.toString(StandardCharsets.UTF_8)
        check("\"id\":1,\"result\":{" in response) { "initialize response missing" }
        for (id in 2..(requestCount + 1)) {
            check("\"id\":$id,\"result\":[" in response) { "document-symbol response $id missing" }
        }
        check("\"id\":100,\"result\":null" in response) { "shutdown response missing" }
    }

    private fun stabilizedUsedHeap(): Long {
        System.gc()
        Thread.sleep(25)
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun frame(message: String): String {
        val length = message.toByteArray(StandardCharsets.UTF_8).size
        return "Content-Length: $length\r\n\r\n$message"
    }

    private fun format(value: Double): String = "%.3f".format(java.util.Locale.ROOT, round(value * 1000.0) / 1000.0)

    private fun String.countOccurrences(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var offset = 0
        while (true) {
            val found = indexOf(needle, offset)
            if (found < 0) return count
            count++
            offset = found + needle.length
        }
    }
}
