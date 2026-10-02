package cplus

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CPlusAstToolingTest {
    @Test
    fun materializesUnsavedImportsWithoutWritingThemToDisk(@TempDir directory: Path) {
        val dependency = directory.resolve("dependency.cp")
        val root = SourceFile("comptime import \"dependency.cp\"; comptime typedef box(int) box_t;", directory.resolve("root.cp").toString())
        val overlay = SourceFile("comptime type @box(type T) { return @code { struct box { T fresh; }; }; }", dependency.toString())
        val result = materializeCPlusForTools(root, sourceProvider = { if (it == dependency) overlay else null })
        assertTrue(result.source.text.contains("fresh"), result.source.text)
        assertFalse(java.nio.file.Files.exists(dependency))
        assertEquals(1, result.imports.size)
    }

    @Test
    fun warningDoesNotMakeAnOtherwiseCompleteAstIncomplete() {
        val snapshot = SourceManager().open(SourceId("warning.cp"), "int value;")
        val span = snapshot.sourceFile.span(0, snapshot.text.length)
        val parsed = CPlusParseResult(snapshot, ParserBackendId.TREE_SITTER, ParseCoverage.STRUCTURAL,
            CPlusSyntaxNode("translation_unit", span), listOf(ParserDiagnostic("FUTURE", "extension warning", ParserDiagnosticSeverity.WARNING, span)))
        val ast = CPlusAstAdapter().adapt(parsed)
        assertTrue(ast.structurallyComplete)
        val result = CPlusAstLoweringPipeline().run(ast, MappedText.identity(snapshot.sourceFile), emptyList()) { parsed }
        assertTrue(result.successful)
        assertEquals("FUTURE", result.parserDiagnostics.single().code)
    }

    @Test
    fun recoveredDescendantsRemainIncompleteEvenWithoutParserDiagnostics() {
        val snapshot = SourceManager().open(SourceId("recovered.cp"), "broken")
        val span = snapshot.sourceFile.span(0, snapshot.text.length)
        val parsed = CPlusParseResult(snapshot, ParserBackendId.TREE_SITTER, ParseCoverage.STRUCTURAL,
            CPlusSyntaxNode("translation_unit", span, listOf(CPlusSyntaxNode("ERROR", span, isError = true))))
        assertFalse(CPlusAstAdapter().adapt(parsed).structurallyComplete)
    }
}
