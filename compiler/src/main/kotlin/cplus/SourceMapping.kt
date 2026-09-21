package cplus

import java.lang.StringBuilder

data class SourceSpan(
    val file: String?,
    val startOffset: Int,
    val endOffset: Int,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int
)

class SourceFile(
    val text: String,
    val name: String? = null
) {
    private val lineStarts = buildList {
        add(0)
        text.forEachIndexed { index, character ->
            if (character == '\n') add(index + 1)
        }
    }

    fun lineOf(offset: Int): Int {
        val clamped = offset.coerceIn(0, text.length)
        var low = 0
        var high = lineStarts.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lineStarts[middle] <= clamped) low = middle + 1 else high = middle - 1
        }
        return high + 1
    }

    fun columnOf(offset: Int): Int {
        val clamped = offset.coerceIn(0, text.length)
        return clamped - lineStarts[lineOf(clamped) - 1] + 1
    }

    fun span(startOffset: Int, endOffset: Int = startOffset + 1): SourceSpan {
        val start = startOffset.coerceIn(0, text.length)
        val end = endOffset.coerceIn(start, text.length)
        return SourceSpan(
            file = name,
            startOffset = start,
            endOffset = end,
            startLine = lineOf(start),
            startColumn = columnOf(start),
            endLine = lineOf(end),
            endColumn = columnOf(end)
        )
    }
}

/** Text whose every character can be traced back to an offset in the original C-plus source. */
class MappedText internal constructor(
    val text: String,
    private val origins: IntArray
) {
    init {
        require(text.length == origins.size) { "mapped text and origin arrays must have equal lengths" }
    }

    fun originAt(index: Int): Int? = origins[index].takeIf { it >= 0 }

    fun slice(start: Int, end: Int): MappedText = MappedText(
        text.substring(start, end),
        origins.copyOfRange(start, end)
    )

    fun firstOrigin(start: Int = 0, end: Int = text.length): Int? {
        for (index in start until end) {
            originAt(index)?.let { return it }
        }
        return null
    }

    companion object {
        fun identity(source: String): MappedText = MappedText(source, IntArray(source.length) { it })

        fun generated(text: String, origin: Int? = null): MappedText = MappedText(
            text,
            IntArray(text.length) { origin ?: -1 }
        )
    }
}

class MappedTextBuilder {
    private val text = StringBuilder()
    private val origins = ArrayList<Int>()

    fun append(mapped: MappedText) {
        append(mapped, 0, mapped.text.length)
    }

    fun append(mapped: MappedText, start: Int, end: Int) {
        text.append(mapped.text, start, end)
        for (index in start until end) origins += mapped.originAt(index) ?: -1
    }

    fun appendGenerated(value: String, origin: Int? = null) {
        text.append(value)
        repeat(value.length) { origins += origin ?: -1 }
    }

    fun build(): MappedText = MappedText(text.toString(), origins.toIntArray())
}

data class SourceMapEntry(
    val generatedLine: Int,
    val source: SourceSpan
)

class SourceMap internal constructor(
    val entries: List<SourceMapEntry>
) {
    fun sourceForGeneratedLine(line: Int): SourceSpan? = entries.firstOrNull { it.generatedLine == line }?.source

    fun sourceForSourceLine(line: Int): SourceSpan? = entries.firstOrNull { it.source.startLine == line }?.source
}

data class TranscodedSource(
    val code: String,
    val sourceFile: SourceFile,
    val sourceMap: SourceMap
)

/** Emits C-plus text and inserts compiler-visible source locations at mapped line boundaries. */
class MappedEmitter(private val sourceFile: SourceFile) {
    fun emit(mapped: MappedText, prelude: String): TranscodedSource {
        val output = StringBuilder(prelude)
        val entries = mutableListOf<SourceMapEntry>()
        var physicalLine = prelude.count { it == '\n' } + 1
        var previousSourceLine: Int? = null
        var cursor = 0

        while (cursor < mapped.text.length) {
            val newline = mapped.text.indexOf('\n', cursor)
            val end = if (newline < 0) mapped.text.length else newline + 1
            val firstOrigin = mapped.firstOrigin(cursor, end)
            val sourceSpan = firstOrigin?.let { sourceFile.span(it) }
            if (sourceSpan != null && sourceSpan.startLine != previousSourceLine?.plus(1)) {
                output.append("#line ")
                    .append(sourceSpan.startLine)
                    .append(" \"")
                    .append(escapeFile(sourceFile.name ?: "<c-plus-input>"))
                    .append("\"\n")
                physicalLine++
            }

            if (sourceSpan != null) {
                entries += SourceMapEntry(physicalLine, sourceSpan)
            }
            output.append(mapped.text, cursor, end)
            physicalLine += mapped.text.substring(cursor, end).count { it == '\n' }
            previousSourceLine = sourceSpan?.startLine?.plus(mapped.text.substring(cursor, end).count { it == '\n' })
                ?.minus(if (end > cursor && mapped.text[end - 1] == '\n') 1 else 0)
            if (sourceSpan == null) previousSourceLine = null
            cursor = end
        }

        return TranscodedSource(output.toString(), sourceFile, SourceMap(entries))
    }

    private fun escapeFile(file: String): String = file
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

interface CompilationLogger {
    fun passStarted(name: String)
    fun passFinished(name: String, detail: String? = null)
}

object SilentCompilationLogger : CompilationLogger {
    override fun passStarted(name: String) = Unit
    override fun passFinished(name: String, detail: String?) = Unit
}

class ConsoleCompilationLogger(private val output: Appendable = System.err) : CompilationLogger {
    override fun passStarted(name: String) {
        output.append("[cplus] pass: ").append(name).append("...\n")
    }

    override fun passFinished(name: String, detail: String?) {
        output.append("[cplus] pass: ").append(name).append(" complete")
        if (!detail.isNullOrBlank()) output.append(" (").append(detail).append(')')
        output.append('\n')
    }
}

inline fun <T> CompilationLogger.pass(name: String, block: () -> T): T {
    passStarted(name)
    return try {
        block().also { passFinished(name) }
    } catch (error: Exception) {
        passFinished(name, "failed")
        throw error
    }
}
