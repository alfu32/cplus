package cplus.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CPlusLexerTest {
    @Test
    fun `finds named fixtures and ignores braces in comments and strings`() {
        val source = """
            @test "first fixture" {
                const char *text = "}";
                /* { ignored } */
                if (1) { }
            }
            @test second fixture { // }
                run();
            }
        """.trimIndent()
        val fixtures = CPlusTestFixtures.find(source)
        assertEquals(listOf("first fixture", "second fixture"), fixtures.map { it.name })
        val firstBody = source.substring(fixtures[0].start, fixtures[0].end)
        assertTrue(firstBody.startsWith("@test \"first fixture\" {"))
        assertTrue(firstBody.contains("if (1) { }") && firstBody.endsWith("}"))
    }

    @Test
    fun `highlights comptime syntax and built-in macros distinctly`() {
        val source = """
            pub borrowed mut owned stat scratch hot warm cold
            self comptime type function defer
            comptime flags -lraylib
            os T.fields field.type
            @import @if @else @for @type @var @fn @code @test @assert @assertEquals @throws @try @catch @custom
            CPLUS_TEST_ASSERT CPLUS_TEST_ASSERT_EQUALS CPLUS_TEST_FAIL
            sizeof _Bool while
        """.trimIndent()
        val tokens = lex(source)

        listOf("pub", "borrowed", "mut", "owned", "stat", "scratch", "hot", "warm", "cold")
            .forEach { assertToken(tokens, it, CPlusTokenTypes.ANNOTATION) }
        assertToken(tokens, "self", CPlusTokenTypes.SELF)
        listOf("comptime", "type", "function", "defer", "flags")
            .forEach { assertToken(tokens, it, CPlusTokenTypes.COMPTIME_KEYWORD) }
        assertToken(tokens, "os", CPlusTokenTypes.COMPTIME_VALUE)
        listOf("fields", "type").forEach { property ->
            assertEquals(1, tokens.count { it.first == property && it.second == CPlusTokenTypes.COMPTIME_PROPERTY })
        }
        listOf("@import", "@if", "@else", "@for", "@type", "@var", "@fn", "@code", "@test", "@assert", "@assertEquals", "@throws", "@try", "@catch")
            .forEach { assertToken(tokens, it, CPlusTokenTypes.COMPTIME_BUILTIN) }
        assertToken(tokens, "@custom", CPlusTokenTypes.ANNOTATION)
        listOf("CPLUS_TEST_ASSERT", "CPLUS_TEST_ASSERT_EQUALS", "CPLUS_TEST_FAIL")
            .forEach { assertToken(tokens, it, CPlusTokenTypes.BUILTIN_MACRO) }
        listOf("sizeof", "_Bool", "while").forEach { assertToken(tokens, it, CPlusTokenTypes.KEYWORD) }
    }

    private fun assertToken(tokens: List<Pair<String, com.intellij.psi.tree.IElementType>>, text: String, expected: com.intellij.psi.tree.IElementType) {
        assertTrue(tokens.any { it.first == text && it.second == expected }, "token '$text' should use $expected")
    }

    private fun lex(source: String): List<Pair<String, com.intellij.psi.tree.IElementType>> {
        val lexer = CPlusLexer()
        lexer.start(source, 0, source.length, 0)
        val tokens = mutableListOf<Pair<String, com.intellij.psi.tree.IElementType>>()
        while (lexer.tokenType != null) {
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            tokens += source.substring(start, end) to lexer.tokenType!!
            lexer.advance()
        }
        return tokens
    }
}
