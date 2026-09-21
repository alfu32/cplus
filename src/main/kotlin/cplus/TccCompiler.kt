package cplus

import org.tinycc.DiagnosticListener
import org.tinycc.TinyCC
import java.nio.file.Files
import java.nio.file.Path

/** Uses the bundled TinyCC JNI API without invoking a shell or external tcc executable. */
class TccCompiler {
    fun compileExecutable(source: String, output: Path, options: List<String>): Int {
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        val diagnostics = DiagnosticListener { message -> System.err.print(message) }
        val optionString = options.joinToString(" ", transform = ::quoteOption)
        return TinyCC.compile(
            source,
            TinyCC.OutputType.EXECUTABLE,
            output,
            optionString,
            diagnostics
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
