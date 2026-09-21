package cplus

import org.tinycc.DiagnosticListener
import org.tinycc.TinyCC
import java.nio.file.Files
import java.nio.file.Path

enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
    NOTE,
    UNKNOWN
}

data class CompilerDiagnostic(
    val severity: DiagnosticSeverity,
    val message: String,
    val file: String?,
    val line: Int?,
    val column: Int?,
    val raw: String
)

data class TccCompilationResult(
    val exitCode: Int,
    val diagnostics: List<CompilerDiagnostic>
)

/** Uses the bundled TinyCC JNI API without invoking a shell or external tcc executable. */
class TccCompiler {
    fun compileExecutable(
        source: TranscodedSource,
        output: Path,
        options: List<String>,
        logger: CompilationLogger = SilentCompilationLogger
    ): TccCompilationResult = logger.pass("tcc-compile") {
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        val rawDiagnostics = StringBuilder()
        val diagnostics = DiagnosticListener { message -> rawDiagnostics.append(message) }
        val optionString = options.joinToString(" ", transform = ::quoteOption)
        val exitCode = TinyCC.compile(
            source.code,
            TinyCC.OutputType.EXECUTABLE,
            output,
            optionString,
            diagnostics
        )
        TccCompilationResult(
            exitCode = exitCode,
            diagnostics = TccDiagnosticParser.parse(rawDiagnostics.toString(), source)
        )
    }

    private fun quoteOption(option: String): String {
        if (option.isEmpty()) return "\"\""
        if (option.none { it.isWhitespace() || it == '"' || it == '\\' }) return option
        return buildString {
            append('"')
            option.forEach { character ->
                if (character == '"' || character == '\\') append('\\')
                append(character)
            }
            append('"')
        }
    }
}

private object TccDiagnosticParser {
    private val diagnosticStart = Regex(
        """(?<![A-Za-z0-9_./\\-])(?:<[^>\n]+>|[^:\n]+):\d+(?::\d+)?:\s*(?:warning|error|note|fatal error):"""
    )
    private val diagnosticLine = Regex(
        "^(.+?):(\\d+)(?::(\\d+))?:\\s*(?:(warning|error|note|fatal error):\\s*)?(.*)$"
    )

    fun parse(raw: String, source: TranscodedSource): List<CompilerDiagnostic> {
        if (raw.isBlank()) return emptyList()
        val normalized = raw.replace("\r", "")
        val starts = diagnosticStart.findAll(normalized).toList()
        if (starts.isEmpty()) return listOf(
            CompilerDiagnostic(DiagnosticSeverity.UNKNOWN, normalized.trim(), null, null, null, normalized)
        )
        return starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1)?.range?.first ?: normalized.length
            parseRecord(normalized.substring(start.range.first, end).trim(), source)
        }
    }

    private fun parseRecord(record: String, source: TranscodedSource): CompilerDiagnostic {
        val match = diagnosticLine.matchEntire(record)
        if (match == null) {
            return CompilerDiagnostic(DiagnosticSeverity.UNKNOWN, record, null, null, null, record)
        }

        val file = match.groupValues[1]
        val line = match.groupValues[2].toIntOrNull()
        val column = match.groupValues[3].toIntOrNull()
        val severity = when (match.groupValues[4]) {
            "warning" -> DiagnosticSeverity.WARNING
            "error", "fatal error" -> DiagnosticSeverity.ERROR
            "note" -> DiagnosticSeverity.NOTE
            else -> DiagnosticSeverity.UNKNOWN
        }
        val mapped = if (file == "<string>" && line != null) {
            source.sourceMap.sourceForGeneratedLine(line) ?: source.sourceMap.sourceForSourceLine(line)
        } else null
        return CompilerDiagnostic(
            severity = severity,
            message = match.groupValues[5].trim(),
            file = mapped?.file ?: file,
            line = mapped?.startLine ?: line,
            column = mapped?.startColumn ?: column,
            raw = record
        )
    }
}
