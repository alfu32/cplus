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
                CPlusTokenTypes.ANNOTATION
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

    private fun isIdentifierPart(character: Char): Boolean = character.isLetterOrDigit() || character == '_'

    companion object {
        private val comptimeKeywords = setOf(
            "comptime", "import", "type", "var", "fn", "variable", "function", "code", "test", "defer"
        )
        private val keywords = setOf(
            "typedef", "struct", "enum", "union", "const", "void", "char", "short", "int",
            "long", "float", "double", "signed", "unsigned", "return", "if", "else", "for",
            "while", "do", "switch", "case", "default", "break", "continue", "static",
            "string", "size_t"
        )
        private val annotations = setOf(
            "pub", "priv", "mut", "borrowed", "owned", "stat", "scratch", "hot", "warm", "cold"
        )
    }
}
