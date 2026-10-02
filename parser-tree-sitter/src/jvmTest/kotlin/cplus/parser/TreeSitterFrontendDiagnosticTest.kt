package cplus.parser

import cplus.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TreeSitterFrontendDiagnosticTest {
    @Test
    fun parserWarningsSurviveLoweringAndDoNotSuppressEmission() {
        val native = TreeSitterCPlusParserBackend()
        val backend = object : CPlusParserBackend {
            override val id = native.id
            override fun parse(source: SourceSnapshot, options: CPlusParseOptions): CPlusParseResult =
                native.parse(source, options).let { parsed ->
                    parsed.copy(diagnostics = parsed.diagnostics + ParserDiagnostic("ADVISORY", "tooling advice",
                        ParserDiagnosticSeverity.WARNING, source.sourceFile.span(0, 0)))
                }
        }
        val source = SourceManager().open(SourceId("diagnostic.cp"), "int main(void) { return 0; }")
        val result = TreeSitterCPlusPrototypeTranspiler(backend = backend).transpile(source)
        assertTrue(result.successful, result.toString())
        val emitted = assertNotNull(result.transcodedSource)
        assertTrue(result.parserDiagnostics.any { it.code == "ADVISORY" })
        assertTrue(emitted.frontendDiagnostics.any { it.code == "ADVISORY" })
    }

    @Test
    fun cliRecoveryPreservesTheSourceStreamAndMappedErrorsWhileStrictModeStillRejectsIt() {
        val text = "int retained;\nint broken( {\n"
        val source = SourceManager().open(SourceId("broken.cp"), text)
        val strict = TreeSitterCPlusPrototypeTranspiler().transpile(source)
        assertFalse(strict.successful)
        assertEquals(null, strict.cSource)
        val recover = TreeSitterCPlusPrototypeTranspiler(recoverDiagnostics = true).transpile(source)
        assertEquals(text, recover.cSource?.text)
        val emitted = assertNotNull(recover.transcodedSource)
        assertTrue(emitted.frontendDiagnostics.any { it.severity == ParserDiagnosticSeverity.ERROR })
        assertTrue(emitted.code.contains("int retained;"))
    }

    @Test
    fun nativeOrInternalCrashesAreNotDisguisedAsRecoverableDiagnostics() {
        val backend = object : CPlusParserBackend {
            override val id = ParserBackendId.TREE_SITTER
            override fun parse(source: SourceSnapshot, options: CPlusParseOptions): CPlusParseResult =
                throw IllegalStateException("native boundary unavailable")
        }
        val source = SourceManager().open(SourceId("crash.cp"), "int retained;")
        assertFailsWith<IllegalStateException> {
            TreeSitterCPlusPrototypeTranspiler(backend = backend, recoverDiagnostics = true).transpile(source)
        }
    }
}
