package cplus.intellij

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType

class CPlusLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var position = 0
    private var tokenEnd = 0
    private var token: com.intellij.psi.tree.IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        position = startOffset
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenType() = token
    override fun getTokenStart(): Int = position
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    override fun advance() {
        position = tokenEnd
        if (position >= bufferEnd) {
            token = null
            tokenEnd = position
            return
        }

        val first = buffer[position]
        token = when {
            first.isWhitespace() -> {
                consume { it.isWhitespace() }
                TokenType.WHITE_SPACE
            }
            first == '/' && position + 1 < bufferEnd && buffer[position + 1] == '/' -> {
                consume { it != '\n' }
                CPlusTokenTypes.COMMENT
            }
            first == '/' && position + 1 < bufferEnd && buffer[position + 1] == '*' -> {
                tokenEnd = (position + 2 until bufferEnd - 1)
                    .firstOrNull { buffer[it] == '*' && buffer[it + 1] == '/' }
                    ?.plus(2) ?: bufferEnd
                CPlusTokenTypes.COMMENT
            }
            first == '#' && isPreprocessorStart(position) -> {
                consume { it != '\n' }
                CPlusTokenTypes.PREPROCESSOR
            }
            first == '"' || first == '\'' -> {
                val quote = first
                tokenEnd = position + 1
                while (tokenEnd < bufferEnd) {
                    if (buffer[tokenEnd] == '\\') tokenEnd++
                    if (tokenEnd < bufferEnd && buffer[tokenEnd] == quote) {
                        tokenEnd++
                        break
                    }
                    tokenEnd++
                }
                CPlusTokenTypes.STRING
            }
            first == '@' -> {
                tokenEnd = position + 1
                while (tokenEnd < bufferEnd && isIdentifierPart(buffer[tokenEnd])) tokenEnd++
                val name = buffer.subSequence(position, tokenEnd).toString()
                if (name in comptimeAtBuiltins) CPlusTokenTypes.COMPTIME_BUILTIN else CPlusTokenTypes.ANNOTATION
            }
            first.isDigit() -> {
                consume { it.isDigit() || it == '.' || it.lowercaseChar() in 'a'..'f' || it.lowercaseChar() == 'x' }
                CPlusTokenTypes.NUMBER
            }
            first.isLetter() || first == '_' -> {
                consume(::isIdentifierPart)
                val word = buffer.subSequence(position, tokenEnd).toString()
                when {
                    word == "self" -> CPlusTokenTypes.SELF
                    word in builtinTestMacros -> CPlusTokenTypes.BUILTIN_MACRO
                    word == "os" -> CPlusTokenTypes.COMPTIME_VALUE
                    word in comptimeProperties && previousNonWhitespace(position) == '.' -> CPlusTokenTypes.COMPTIME_PROPERTY
                    word == "flags" && previousHorizontalWord(position) == "comptime" -> CPlusTokenTypes.COMPTIME_KEYWORD
                    word in comptimeKeywords -> CPlusTokenTypes.COMPTIME_KEYWORD
                    word in keywords -> CPlusTokenTypes.KEYWORD
                    word in annotations -> CPlusTokenTypes.ANNOTATION
                    word.endsWith("_t") -> CPlusTokenTypes.TYPE
                    else -> CPlusTokenTypes.IDENTIFIER
                }
            }
            else -> {
                tokenEnd = position + 1
                TokenType.BAD_CHARACTER
            }
        }
    }

    private fun consume(predicate: (Char) -> Boolean) {
        tokenEnd = position
        while (tokenEnd < bufferEnd && predicate(buffer[tokenEnd])) tokenEnd++
    }

    private fun isPreprocessorStart(offset: Int): Boolean {
        var lineStart = offset - 1
        while (lineStart >= 0 && buffer[lineStart] != '\n') lineStart--
        return (lineStart + 1 until offset).all { buffer[it].isWhitespace() }
    }

    private fun previousNonWhitespace(offset: Int): Char? {
        var cursor = offset - 1
        while (cursor >= 0 && buffer[cursor].isWhitespace()) cursor--
        return buffer.getOrNull(cursor)
    }

    private fun previousHorizontalWord(offset: Int): String? {
        var cursor = offset - 1
        while (cursor >= 0 && (buffer[cursor] == ' ' || buffer[cursor] == '\t')) cursor--
        val end = cursor + 1
        while (cursor >= 0 && isIdentifierPart(buffer[cursor])) cursor--
        return if (end > cursor + 1) buffer.subSequence(cursor + 1, end).toString() else null
    }

    private fun isIdentifierPart(character: Char): Boolean = character.isLetterOrDigit() || character == '_'

    companion object {
        private val comptimeKeywords = setOf(
            "comptime", "import", "type", "var", "fn", "variable", "function", "code", "test", "defer", "string"
        )
        private val comptimeAtBuiltins = setOf(
            "@import", "@if", "@else", "@for", "@type", "@var", "@fn", "@code", "@test",
            "@assert", "@assertEquals", "@throws", "@try", "@catch"
        )
        private val builtinTestMacros = setOf("CPLUS_TEST_ASSERT", "CPLUS_TEST_ASSERT_EQUALS", "CPLUS_TEST_FAIL")
        private val comptimeProperties = setOf("name", "size", "align", "fields", "type")
        private val keywords = setOf(
            "auto", "break", "case", "char", "const", "continue", "default", "do", "double",
            "else", "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long",
            "register", "restrict", "return", "short", "signed", "sizeof", "static", "struct",
            "switch", "typedef", "union", "unsigned", "void", "volatile", "while",
            "_Alignas", "_Alignof", "_Atomic", "_Bool", "_Complex", "_Generic", "_Imaginary",
            "_Noreturn", "_Static_assert", "_Thread_local", "bool", "size_t", "ptrdiff_t", "wchar_t",
            "char16_t", "char32_t"
        )
        private val annotations = setOf(
            "pub", "priv", "mut", "borrowed", "owned", "stat", "scratch", "hot", "warm", "cold"
        )
    }
}
