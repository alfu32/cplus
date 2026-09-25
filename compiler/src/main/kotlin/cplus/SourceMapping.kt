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
    private var mappedOrigins: MappedText? = null

    internal constructor(text: String, name: String?, mappedOrigins: MappedText) : this(text, name) {
        this.mappedOrigins = mappedOrigins
    }

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
        mappedSpan(start, end)?.let { return it }
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

    private fun mappedSpan(start: Int, end: Int): SourceSpan? {
        val mapping = mappedOrigins ?: return null
        val first = mapping.originAt(start)
            ?: (if (start > 0) mapping.originAt(start - 1)?.let { SourceOrigin(it.file, it.offset + 1) }
            else null)
            ?: return null
        if (first.file === this) return null
        val last = if (end > start) mapping.originAt(end - 1) else null
        val mappedEnd = if (last?.file === first.file && last.offset >= first.offset) {
            last.offset + 1
        } else {
            first.offset + 1
        }
        return first.file.span(first.offset, mappedEnd)
    }
}

data class SourceOrigin(
    val file: SourceFile,
    val offset: Int
)

/** Text whose every character can be traced back to an offset in the original C-plus source. */
class MappedText internal constructor(
    val text: String,
    private val origins: Array<SourceOrigin?>
) {
    init {
        require(text.length == origins.size) { "mapped text and origin arrays must have equal lengths" }
    }

    fun originAt(index: Int): SourceOrigin? = origins[index]

    fun slice(start: Int, end: Int): MappedText = MappedText(
        text.substring(start, end),
        origins.copyOfRange(start, end)
    )

    fun firstOrigin(start: Int = 0, end: Int = text.length): SourceOrigin? {
        for (index in start until end) {
            originAt(index)?.let { return it }
        }
        return null
    }

    companion object {
        fun identity(sourceFile: SourceFile): MappedText = MappedText(
            sourceFile.text,
            Array(sourceFile.text.length) { SourceOrigin(sourceFile, it) }
        )

        fun identity(source: String): MappedText = identity(SourceFile(source))

        fun generated(text: String, origin: SourceOrigin? = null): MappedText = MappedText(
            text,
            Array(text.length) { origin }
        )
    }
}

class MappedTextBuilder {
    private val text = StringBuilder()
    private val origins = ArrayList<SourceOrigin?>()

    fun append(mapped: MappedText) {
        append(mapped, 0, mapped.text.length)
    }

    fun append(mapped: MappedText, start: Int, end: Int) {
        text.append(mapped.text, start, end)
        for (index in start until end) origins += mapped.originAt(index)
    }

    fun appendGenerated(value: String, origin: SourceOrigin? = null) {
        text.append(value)
        repeat(value.length) { origins += origin }
    }

    fun build(): MappedText = MappedText(text.toString(), origins.toTypedArray())
}

data class SourceMapEntry(
    val generatedLine: Int,
    val source: SourceSpan
)

enum class AllocationIntent {
    NONE,
    SCRATCH,
    HOT,
    WARM,
    COLD
}

enum class AllocationOwnership {
    NONE,
    BORROWED,
    OWNED
}

enum class AllocationSymbolKind {
    VARIABLE,
    PARAMETER,
    FUNCTION_RETURN
}

data class AllocationSymbol(
    val name: String,
    val kind: AllocationSymbolKind,
    val intent: AllocationIntent,
    val ownership: AllocationOwnership,
    val knownProvenance: AllocationIntent,
    val sourceSpan: SourceSpan
)

data class CPlusDiagnostic(
    val message: String,
    val sourceSpan: SourceSpan
)

data class AllocationAnalysisResult(
    val symbols: List<AllocationSymbol> = emptyList(),
    val diagnostics: List<CPlusDiagnostic> = emptyList()
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
    val sourceMap: SourceMap,
    val allocationAnalysis: AllocationAnalysisResult = AllocationAnalysisResult(),
    val compilerOptions: List<String> = emptyList(),
    /** Resolved C-plus imports in dependency-first emission order, including the root source. */
    val sourceOrder: List<SourceId> = emptyList(),
    /** Canonical edges retain the source location that requested each import. */
    val sourceImports: List<SourceImportEdge> = emptyList()
)

/** Emits C-plus text and inserts compiler-visible source locations at mapped line boundaries. */
class MappedEmitter(private val sourceFile: SourceFile) {
    fun emit(
        mapped: MappedText,
        prelude: String,
        allocationAnalysis: AllocationAnalysisResult = AllocationAnalysisResult(),
        compilerOptions: List<String> = emptyList()
    ): TranscodedSource {
        val output = StringBuilder(prelude)
        val uniqueCompilerOptions = CompilerOptions.distinct(compilerOptions)
        if (uniqueCompilerOptions.isNotEmpty()) {
            output.append("/* cplus compiler flags: ")
                .append(uniqueCompilerOptions.joinToString(" ", transform = ::commentSafeOption))
                .append(" */\n")
        }
        val entries = mutableListOf<SourceMapEntry>()
        var physicalLine = output.count { it == '\n' } + 1
        var previousSourceLine: Int? = null
        var previousSourceFile: String? = null
        var cursor = 0

        while (cursor < mapped.text.length) {
            val newline = mapped.text.indexOf('\n', cursor)
            val end = if (newline < 0) mapped.text.length else newline + 1
            val firstOrigin = mapped.firstOrigin(cursor, end)
            val sourceSpan = firstOrigin?.file?.span(firstOrigin.offset)
            if (sourceSpan != null && (
                    sourceSpan.file != previousSourceFile ||
                        sourceSpan.startLine != previousSourceLine?.plus(1)
                    )
            ) {
                output.append("#line ")
                    .append(sourceSpan.startLine)
                    .append(" \"")
                    .append(escapeFile(sourceSpan.file ?: sourceFile.name ?: "<c-plus-input>"))
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
            previousSourceFile = sourceSpan?.file
            if (sourceSpan == null) {
                previousSourceLine = null
                previousSourceFile = null
            }
            cursor = end
        }

        return TranscodedSource(
            output.toString(), sourceFile, SourceMap(entries), allocationAnalysis, uniqueCompilerOptions
        )
    }

    private fun commentSafeOption(option: String): String = buildString {
        append('"')
        option.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '/' -> if (lastOrNull() == '*') append("\\/") else append(character)
                else -> append(character)
            }
        }
        append('"')
    }

    private fun escapeFile(file: String): String = file
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

interface CompilationLogger {
    fun passStarted(name: String)
    fun passFinished(name: String, detail: String? = null)
    fun info(message: String) = Unit
}

object SilentCompilationLogger : CompilationLogger {
    override fun passStarted(name: String) = Unit
    override fun passFinished(name: String, detail: String?) = Unit
}

class ConsoleCompilationLogger(private val output: Appendable = System.err) : CompilationLogger {
    override fun info(message: String) {
        output.append("[cplus] ").append(message).append('\n')
    }

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
