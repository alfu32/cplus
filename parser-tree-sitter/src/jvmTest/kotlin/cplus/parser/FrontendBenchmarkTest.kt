package cplus.parser

import cplus.SourceId
import cplus.SourceManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/** Explicitly invoked performance probe; excluded from the normal correctness suite. */
class FrontendBenchmarkTest {
    @Test
    @Tag("frontend-benchmark")
    fun benchmarkFreshParseTranscodeAndIncrementalEdit() {
        val repository = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("stdlib")) && Files.isDirectory(it.resolve("examples")) }
            ?: error("could not locate repository sources from ${Path.of("").toAbsolutePath()}")
        val corpora = listOf("stdlib", "examples").associateWith { directory ->
            Files.walk(repository.resolve(directory)).use { paths ->
                paths.filter { it.toString().endsWith(".cp") || it.toString().endsWith(".c+") }
                    .sorted()
                    .map { path -> path to Files.readString(path) }
                    .toList()
            }
        }
        assertTrue(corpora.values.all { it.isNotEmpty() }, "stdlib and examples should both have C-plus sources")

        corpora.forEach { (name, sources) ->
            val totalBytes = sources.sumOf { it.second.toByteArray(Charsets.UTF_8).size.toLong() }
            val timings = measureMedian(iterations = 3, warmups = 1) {
                val backend = TreeSitterCPlusParserBackend()
                val manager = SourceManager()
                sources.forEachIndexed { index, (path, text) ->
                    val snapshot = manager.open(SourceId.named("benchmark/$name/$index/${path.fileName}"), text)
                    val result = backend.parse(snapshot)
                    check(result.diagnostics.isEmpty()) { "parse failed for $path: ${result.diagnostics}" }
                }
            }
            report("fresh-parse-$name", timings, sources.size, totalBytes)
        }

        val generatedC = buildString {
            append("#include <stddef.h>\n")
            repeat(800) { index ->
                append("int generated_function_").append(index)
                    .append("(int value) { return value + ").append(index).append("; }\n")
            }
        }
        benchmarkParse("fresh-parse-generated-c", generatedC, iterations = 5)

        val generatedCPlus = buildString {
            repeat(180) { index ->
                append("typedef struct generated_type_").append(index).append("_t {\n")
                    .append("    int value;\n")
                    .append("    pub int read(borrowed *self) { return self->value; }\n")
                    .append("} generated_type_").append(index).append("_t;\n")
            }
        }
        val transcodeTimings = measureMedian(iterations = 3, warmups = 1) {
            val manager = SourceManager()
            val result = TreeSitterCPlusPrototypeTranspiler(sourceManager = manager).transpile(
                manager.open(SourceId.named("benchmark/generated-large.cp"), generatedCPlus)
            )
            check(result.successful) {
                "generated C-plus transcode failed: parser=${result.parserDiagnostics}; " +
                    "lowering=${result.loweringDiagnostics}; unsupported=${result.unsupportedNodes}"
            }
        }
        report("transcode-generated-cplus", transcodeTimings, 1, generatedCPlus.toByteArray(Charsets.UTF_8).size.toLong())

        val realCandidates = listOf(
            "stdlib/encodings/rune.cp",
            "stdlib/http/protocol.cp",
            "stdlib/io/file.cp",
            "stdlib/memory/xmem.cp",
            "examples/annotated.cp",
            "examples/basic.cp"
        )
            .map(repository::resolve)
            .filter(Files::isRegularFile)
        realCandidates.forEach { path ->
            val text = Files.readString(path)
            fun transcode(): cplus.parser.TreeSitterPrototypeResult {
                val manager = SourceManager()
                return TreeSitterCPlusPrototypeTranspiler(sourceManager = manager).transpile(
                    manager.open(SourceId.named("benchmark/real/${path.fileName}"), text)
                )
            }
            val probe = transcode()
            if (!probe.successful) {
                println(
                        "[frontend-benchmark] transcode-real-rejected: source=${repository.relativize(path)} " +
                            "parser_errors=${probe.parserDiagnostics.size} lowering_errors=${probe.loweringDiagnostics.size} " +
                            "diagnostic_codes=${probe.loweringDiagnostics.map { it.code }.distinct()} " +
                            "first_diagnostics=${probe.loweringDiagnostics.take(3).map { diagnostic ->
                                "${diagnostic.code}@${diagnostic.span.startLine}:${diagnostic.span.startColumn}:${diagnostic.message}"
                            }} " +
                            "unsupported=${probe.unsupportedNodes.map { it.syntaxKind }.distinct()}"
                )
            } else {
                val bytes = text.toByteArray(Charsets.UTF_8).size.toLong()
                val timings = measureMedian(iterations = 3, warmups = 1) {
                    val result = transcode()
                    check(result.successful) { "${repository.relativize(path)} stopped transpiling: ${result.loweringDiagnostics}" }
                }
                report("transcode-${repository.relativize(path)}", timings, 1, bytes)
            }
        }

        val editSource = corpora.values.flatten().maxBy { it.second.length }
        val editedText = editSource.second.replaceFirst("\n", "\n/* benchmark edit */\n")
        val editedBytes = editedText.toByteArray(Charsets.UTF_8).size.toLong()
        val editTimings = measureMedian(iterations = 5, warmups = 1) {
            val manager = SourceManager()
            val backend = TreeSitterCPlusParserBackend()
            val editedSnapshot = manager.open(SourceId.named("benchmark/edit/${editSource.first.fileName}"), editedText)
            val result = backend.parse(editedSnapshot)
            check(result.diagnostics.isEmpty()) { "edited full reparse failed: ${result.diagnostics}" }
        }
        report("fresh-full-reparse-after-edit", editTimings, 1, editedBytes)

        val incrementalSourceId = SourceId.named("benchmark/incremental/${editSource.first.fileName}")
        var changedRangeCount = 0
        fun incrementalUpdateNanos(): Long {
            val manager = SourceManager()
            val backend = TreeSitterCPlusParserBackend()
            val original = manager.open(incrementalSourceId, editSource.second)
            val session = backend.openIncrementalSession(original)
            val edited = manager.open(incrementalSourceId, editedText)
            val started = System.nanoTime()
            val result = session.update(edited)
            val elapsed = System.nanoTime() - started
            check(result.reusedPreviousTree) { "incremental edit did not reuse the prior tree" }
            check(result.parseResult.diagnostics.isEmpty()) {
                "incremental edit parse failed: ${result.parseResult.diagnostics}"
            }
            changedRangeCount = result.changedRanges.size
            return elapsed
        }
        repeat(1) { incrementalUpdateNanos() }
        val incrementalTimings = List(5) { incrementalUpdateNanos() }.sorted()
        val medianMillis = incrementalTimings[incrementalTimings.size / 2] / 1_000_000.0
        val insertionBytes = (
            editedText.toByteArray(Charsets.UTF_8).size - editSource.second.toByteArray(Charsets.UTF_8).size
            ).coerceAtLeast(0)
        println(
            "[frontend-benchmark] incremental-tree-edit: source_bytes=${editSource.second.toByteArray(Charsets.UTF_8).size} " +
                "inserted_bytes=$insertionBytes median_ms=${"%.3f".format(medianMillis)} " +
                "reused_tree=true changed_ranges=$changedRangeCount samples=${incrementalTimings.size}"
        )
    }

    private fun benchmarkParse(name: String, text: String, iterations: Int) {
        val bytes = text.toByteArray(Charsets.UTF_8).size.toLong()
        val timings = measureMedian(iterations, warmups = 1) {
            val source = SourceManager().open(SourceId.named("benchmark/$name.c"), text)
            val parsed = TreeSitterCPlusParserBackend().parse(source)
            check(parsed.diagnostics.isEmpty()) { "$name failed to parse: ${parsed.diagnostics}" }
        }
        report(name, timings, 1, bytes)
    }

    private fun measureMedian(iterations: Int, warmups: Int, block: () -> Unit): List<Long> {
        repeat(warmups) { block() }
        return List(iterations) {
            val started = System.nanoTime()
            block()
            System.nanoTime() - started
        }.sorted()
    }

    private fun report(name: String, sortedTimings: List<Long>, files: Int, bytes: Long) {
        val medianNanos = sortedTimings[sortedTimings.size / 2]
        val millis = medianNanos / 1_000_000.0
        val mebibytesPerSecond = if (medianNanos == 0L) 0.0 else bytes * 1_000_000_000.0 / medianNanos / (1024.0 * 1024.0)
        println(
            "[frontend-benchmark] $name: files=$files bytes=$bytes median_ms=${"%.3f".format(millis)} " +
                "MiB_per_s=${"%.2f".format(mebibytesPerSecond)} samples=${sortedTimings.size}"
        )
    }
}
