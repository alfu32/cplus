package cplus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrontendParserTest {
    @Test
    fun `legacy backend explicitly reports ordinary C as opaque`() {
        val snapshot = SourceManager().open(SourceId.named("legacy.c"), "int main(void) { return 0; }")
        val result = CPlusParserService().parse(snapshot)

        assertEquals(ParserBackendId.LEGACY, result.backend)
        assertEquals(ParseCoverage.OPAQUE, result.coverage)
        assertEquals("translation_unit", result.root.kind)
        assertEquals(listOf("legacy_text_region"), result.root.children.map { it.kind })
        assertTrue(result.root.children.single().opaque)
        assertTrue(result.limitations.single().contains("ordinary C/C-plus syntax is opaque"))
    }

    @Test
    fun `legacy backend exposes only the comptime forms it recognizes`() {
        val source = """
            comptime int @answer = 42;
            comptime flags -lm
            @test "answer is ready" { @assert(1); }
            int runtime_answer = 42;
        """.trimIndent()
        val snapshot = SourceManager().open(SourceId.named("partial.cp"), source)
        val result = CPlusParserService().parse(snapshot)

        assertEquals(ParseCoverage.PARTIAL, result.coverage)
        assertEquals(
            listOf("comptime_value_declaration", "comptime_flags", "test_declaration"),
            result.root.children.filterNot { it.opaque }.map { it.kind }
        )
        assertTrue(result.root.children.any { it.opaque && source.substring(it.span.startOffset, it.span.endOffset).contains("runtime_answer") })
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `backend service selects implementations without exposing implementation nodes`() {
        val snapshot = SourceManager().open(SourceId.named("swap.cp"), "int value;")
        val replacement = object : CPlusParserBackend {
            override val id = ParserBackendId.TREE_SITTER
            override fun parse(source: SourceSnapshot, options: CPlusParseOptions) = CPlusParseResult(
                source,
                id,
                ParseCoverage.STRUCTURAL,
                CPlusSyntaxNode("translation_unit", source.sourceFile.span(0, source.text.length))
            )
        }
        val service = CPlusParserService(listOf(LegacyCPlusParserBackend(), replacement))

        assertEquals(setOf(ParserBackendId.LEGACY, ParserBackendId.TREE_SITTER), service.availableBackends)
        assertEquals(ParseCoverage.OPAQUE, service.parse(snapshot).coverage)
        assertEquals(ParseCoverage.STRUCTURAL, service.parse(snapshot, ParserBackendId.TREE_SITTER).coverage)
        assertFalse(service.parse(snapshot).backend == ParserBackendId.TREE_SITTER)
    }

    @Test
    fun `unregistered parser selection fails clearly`() {
        val snapshot = SourceManager().open(SourceId.named("not-installed.cp"), "int value;")
        val error = assertThrows(IllegalArgumentException::class.java) {
            CPlusParserService().parse(snapshot, ParserBackendId.TREE_SITTER)
        }

        assertTrue(error.message.orEmpty().contains("TREE_SITTER is not available"))
    }
}
