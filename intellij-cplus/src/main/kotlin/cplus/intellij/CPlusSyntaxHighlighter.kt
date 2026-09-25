package cplus.intellij

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

class CPlusSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = CPlusLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        CPlusTokenTypes.ANNOTATION -> pack(ANNOTATION)
        CPlusTokenTypes.COMPTIME_KEYWORD -> pack(COMPTIME_KEYWORD)
        CPlusTokenTypes.COMPTIME_BUILTIN -> pack(COMPTIME_BUILTIN)
        CPlusTokenTypes.BUILTIN_MACRO -> pack(BUILTIN_MACRO)
        CPlusTokenTypes.COMPTIME_VALUE -> pack(COMPTIME_VALUE)
        CPlusTokenTypes.COMPTIME_PROPERTY -> pack(COMPTIME_PROPERTY)
        CPlusTokenTypes.COMMENT -> pack(DefaultLanguageHighlighterColors.LINE_COMMENT)
        CPlusTokenTypes.PREPROCESSOR -> pack(DefaultLanguageHighlighterColors.METADATA)
        CPlusTokenTypes.FUNCTION -> pack(DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
        CPlusTokenTypes.KEYWORD -> pack(DefaultLanguageHighlighterColors.KEYWORD)
        CPlusTokenTypes.TYPE -> pack(DefaultLanguageHighlighterColors.CLASS_NAME)
        CPlusTokenTypes.NUMBER -> pack(DefaultLanguageHighlighterColors.NUMBER)
        CPlusTokenTypes.STRING -> pack(DefaultLanguageHighlighterColors.STRING)
        CPlusTokenTypes.IDENTIFIER -> pack(DefaultLanguageHighlighterColors.IDENTIFIER)
        CPlusTokenTypes.SELF -> pack(DefaultLanguageHighlighterColors.PARAMETER)
        else -> emptyArray()
    }

    companion object {
        private val ANNOTATION = TextAttributesKey.createTextAttributesKey(
            "CPLUS_ANNOTATION", DefaultLanguageHighlighterColors.METADATA
        )
        private val COMPTIME_KEYWORD = TextAttributesKey.createTextAttributesKey(
            "CPLUS_COMPTIME_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD
        )
        private val COMPTIME_BUILTIN = TextAttributesKey.createTextAttributesKey(
            "CPLUS_COMPTIME_BUILTIN", DefaultLanguageHighlighterColors.KEYWORD
        )
        private val BUILTIN_MACRO = TextAttributesKey.createTextAttributesKey(
            "CPLUS_BUILTIN_MACRO", DefaultLanguageHighlighterColors.METADATA
        )
        private val COMPTIME_VALUE = TextAttributesKey.createTextAttributesKey(
            "CPLUS_COMPTIME_VALUE", DefaultLanguageHighlighterColors.CONSTANT
        )
        private val COMPTIME_PROPERTY = TextAttributesKey.createTextAttributesKey(
            "CPLUS_COMPTIME_PROPERTY", DefaultLanguageHighlighterColors.INSTANCE_FIELD
        )
    }
}

abstract class SyntaxHighlighterBase : SyntaxHighlighter {
    protected fun pack(key: TextAttributesKey): Array<TextAttributesKey> = arrayOf(key)
}

class CPlusSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        CPlusSyntaxHighlighter()
}
