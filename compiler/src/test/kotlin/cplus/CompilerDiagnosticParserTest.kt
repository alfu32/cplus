package cplus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CompilerDiagnosticParserTest {
    @Test
    fun parsesDriverDiagnosticsWithOptionalColumnsAndMultilineMessages() {
        val source = mappedSource("diagnostic.cp")
        val diagnostics = CompilerDiagnosticParser.parse(
            """
            diagnostic.cp:4:7: error: first line
              continuation detail
            diagnostic.cp:5: warning: line-only warning
            """.trimIndent(),
            source
        )

        assertEquals(2, diagnostics.size)
        assertEquals(DiagnosticSeverity.ERROR, diagnostics[0].severity)
        assertEquals("diagnostic.cp", diagnostics[0].file)
        assertEquals(4, diagnostics[0].line)
        assertEquals(7, diagnostics[0].column)
        assertEquals("first line\n  continuation detail", diagnostics[0].message)
        assertEquals(DiagnosticSeverity.WARNING, diagnostics[1].severity)
        assertEquals(5, diagnostics[1].line)
        assertNull(diagnostics[1].column)
    }

    @Test
    fun mapsGeneratedStringLocationsBackToOriginalSourceSpans() {
        val sourceFile = SourceFile("int main(void) { return missing; }\n", "original.cp")
        val generated = MappedEmitter(sourceFile).emit(MappedText.identity(sourceFile), "")
        val diagnostics = CompilerDiagnosticParser.parse(
            "<string>:1:25: error: missing",
            generated
        )

        assertEquals(1, diagnostics.size)
        assertEquals(DiagnosticSeverity.ERROR, diagnostics.single().severity)
        assertEquals("original.cp", diagnostics.single().file)
        assertEquals(1, diagnostics.single().line)
        assertEquals(1, diagnostics.single().column)
    }

    @Test
    fun preservesUnrecognizedCompilerOutputAsUnknownDiagnostic() {
        val source = mappedSource("diagnostic.cp")
        val diagnostics = CompilerDiagnosticParser.parse("fatal compiler startup failure", source)

        assertEquals(1, diagnostics.size)
        assertEquals(DiagnosticSeverity.UNKNOWN, diagnostics.single().severity)
        assertEquals("fatal compiler startup failure", diagnostics.single().message)
        assertNull(diagnostics.single().file)
    }

    private fun mappedSource(name: String): TranscodedSource {
        val file = SourceFile("int main(void) { return 0; }\n", name)
        return MappedEmitter(file).emit(MappedText.identity(file), "")
    }
}
