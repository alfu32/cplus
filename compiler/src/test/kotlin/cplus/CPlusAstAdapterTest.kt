package cplus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CPlusAstAdapterTest {
    @Test
    fun preservesSyntaxSpansAndNormalizesCPlusMethodCalls() {
        val source = SourceManager().open(
            SourceId.named("model.cp"),
            "typedef struct model_t { pub int get(borrowed *self); } model_t; int f(void) { obj.get(); }"
        )
        val file = source.sourceFile
        val methodStart = source.text.indexOf("pub int")
        val callStart = source.text.indexOf("obj.get")
        val method = CPlusSyntaxNode(
            kind = "cplus_method_definition",
            span = file.span(methodStart, source.text.indexOf(';', methodStart) + 1),
            children = listOf(CPlusSyntaxNode("parameter_declaration", file.span(methodStart, methodStart + 1)))
        )
        val call = CPlusSyntaxNode(
            kind = "call_expression",
            span = file.span(callStart, callStart + 12),
            children = listOf(CPlusSyntaxNode("field_expression", file.span(callStart, callStart + 7)))
        )
        val parsed = CPlusParseResult(
            source,
            ParserBackendId.TREE_SITTER,
            ParseCoverage.STRUCTURAL,
            CPlusSyntaxNode("translation_unit", file.span(0, source.text.length), listOf(method, call))
        )

        val ast = CPlusAstAdapter().adapt(parsed)

        assertEquals(CPlusAstKind.TRANSLATION_UNIT, ast.root.kind)
        assertEquals(CPlusAstKind.METHOD_DECLARATION, ast.root.children[0].kind)
        assertEquals(CPlusAstKind.PARAMETER, ast.root.children[0].children.single().kind)
        assertEquals(CPlusAstKind.CALL_EXPRESSION, ast.root.children[1].kind)
        assertEquals(methodStart, ast.root.children[0].span.startOffset)
        assertTrue(ast.structurallyComplete)
        assertTrue(ast.dump().contains("method_declaration <cplus_method_definition>"))
        assertTrue(ast.dump().contains("call_expression <call_expression>"))
    }

    @Test
    fun retainsRecoveredNodesAsErrorsInsteadOfDroppingThem() {
        val source = SourceManager().open(SourceId.named("broken.cp"), "broken")
        val error = CPlusSyntaxNode("ERROR", source.sourceFile.span(0, 6), isError = true)
        val parsed = CPlusParseResult(
            source,
            ParserBackendId.TREE_SITTER,
            ParseCoverage.PARTIAL,
            CPlusSyntaxNode("translation_unit", source.sourceFile.span(0, 6), listOf(error))
        )

        val ast = CPlusAstAdapter().adapt(parsed)

        assertEquals(CPlusAstKind.ERROR, ast.root.children.single().kind)
        assertTrue(ast.root.children.single().recovered)
        assertFalse(ast.structurallyComplete)
    }

    @Test
    fun classifiesExplicitTestAssertionsInStableAst() {
        val source = SourceManager().open(SourceId.named("assertion.cp"), "@assert(value)")
        val assertionStart = source.text.indexOf('@')
        val assertion = CPlusSyntaxNode(
            "cplus_test_assertion_statement",
            source.sourceFile.span(assertionStart, source.text.length)
        )
        val parsed = CPlusParseResult(
            source,
            ParserBackendId.TREE_SITTER,
            ParseCoverage.STRUCTURAL,
            CPlusSyntaxNode("translation_unit", source.sourceFile.span(0, source.text.length), listOf(assertion))
        )

        val node = CPlusAstAdapter().adapt(parsed).root.children.single()

        assertEquals(CPlusAstKind.TEST_ASSERTION, node.kind)
        assertEquals("cplus_test_assertion_statement", node.syntaxKind)
        assertEquals(assertionStart, node.span.startOffset)
    }

    @Test
    fun normalizesComptimeGeneratorsInvocationsExpressionsAndCodeFragments() {
        val source = SourceManager().open(SourceId.named("comptime-shapes.cp"), "comptime type T;")
        val wholeSpan = source.sourceFile.span(0, source.text.length)
        val syntaxKinds = listOf(
            "cplus_legacy_type_generator" to CPlusAstKind.COMPTIME_DECLARATION,
            "cplus_comptime_function_definition" to CPlusAstKind.COMPTIME_DECLARATION,
            "cplus_comptime_invocation" to CPlusAstKind.COMPTIME_INVOCATION,
            "cplus_comptime_expression" to CPlusAstKind.COMPTIME_EXPRESSION,
            "cplus_code_fragment" to CPlusAstKind.CODE_FRAGMENT,
            "cplus_interpolated_identifier" to CPlusAstKind.INTERPOLATED_IDENTIFIER
        )
        val parsed = CPlusParseResult(
            source,
            ParserBackendId.TREE_SITTER,
            ParseCoverage.STRUCTURAL,
            CPlusSyntaxNode("translation_unit", wholeSpan, syntaxKinds.map { (kind, _) -> CPlusSyntaxNode(kind, wholeSpan) })
        )

        val normalized = CPlusAstAdapter().adapt(parsed).root.children

        assertEquals(syntaxKinds.map { it.second }, normalized.map { it.kind })
        assertEquals(syntaxKinds.map { it.first }, normalized.map { it.syntaxKind })
    }

    @Test
    fun emitsDeterministicAstDumpGolden() {
        val source = SourceManager().open(SourceId.named("golden.cp"), "x")
        val parsed = CPlusParseResult(
            source,
            ParserBackendId.TREE_SITTER,
            ParseCoverage.STRUCTURAL,
            CPlusSyntaxNode("translation_unit", source.sourceFile.span(0, 1))
        )

        assertEquals("translation_unit <translation_unit> @1:1-1:2\n", CPlusAstAdapter().adapt(parsed).dump())
    }

    @Test
    fun mappedAstEmitterPreservesUnchangedAndGeneratedOrigins() {
        val source = SourceManager().open(SourceId.named("emit.cp"), "int value;\n")
        val parse = CPlusParseResult(
            source,
            ParserBackendId.TREE_SITTER,
            ParseCoverage.STRUCTURAL,
            CPlusSyntaxNode("translation_unit", source.sourceFile.span(0, source.text.length))
        )
        val ast = CPlusAstAdapter().adapt(parse)
        val original = MappedText.identity(source.sourceFile)
        val replaceStart = source.text.indexOf("value")
        val generatedOrigin = SourceOrigin(source.sourceFile, replaceStart)

        val emitted = CPlusMappedAstEmitter().emit(
            ast,
            original,
            listOf(CPlusMappedEdit(source.sourceFile.span(replaceStart, replaceStart + 5), MappedText.generated("result", generatedOrigin)))
        )

        assertEquals("int result;\n", emitted.text)
        assertEquals(0, emitted.originAt(0)?.offset)
        assertEquals(replaceStart, emitted.originAt(replaceStart)?.offset)
        assertNull(emitted.originAt(emitted.text.length))
        assertNull(emitted.originAt(-1))
        assertEquals(source.text.length - replaceStart - 5, emitted.text.length - replaceStart - "result".length)
    }
}
