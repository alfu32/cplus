package cplus.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CPlusParserExternalAnnotatorTest {
    @Test
    fun decodesVersionedParserDiagnosticsAndUtf16Offsets() {
        val diagnostics = CPlusParserJsonDiagnostics.decode(
            """{"schema":"cplus.parse.v1","diagnostics":[{"code":"TS_ERROR_NODE","message":"bad syntax","severity":"error","span":{"startOffset":4,"endOffset":7,"startLine":1,"startColumn":5,"endLine":1,"endColumn":8}}]}"""
        )

        assertEquals(listOf(CPlusParserDiagnostic(4, 7, "bad syntax", warning = false)), diagnostics)
    }

    @Test
    fun rejectsUnknownParserJsonSchema() {
        assertThrows(IllegalArgumentException::class.java) {
            CPlusParserJsonDiagnostics.decode("""{"schema":"cplus.parse.v0","diagnostics":[]}""")
        }
    }
}
