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
        CPlusTokenTypes.COMMENT -> pack(DefaultLanguageHighlighterColors.LINE_COMMENT)
        CPlusTokenTypes.PREPROCESSOR -> pack(DefaultLanguageHighlighterColors.METADATA)
        CPlusTokenTypes.KEYWORD -> pack(DefaultLanguageHighlighterColors.KEYWORD)
        CPlusTokenTypes.TYPE -> pack(DefaultLanguageHighlighterColors.CLASS_NAME)
        CPlusTokenTypes.NUMBER -> pack(DefaultLanguageHighlighterColors.NUMBER)
        CPlusTokenTypes.STRING -> pack(DefaultLanguageHighlighterColors.STRING)
        CPlusTokenTypes.IDENTIFIER -> pack(DefaultLanguageHighlighterColors.IDENTIFIER)
        else -> emptyArray()
    }

    companion object {
        private val ANNOTATION = TextAttributesKey.createTextAttributesKey(
            "CPLUS_ANNOTATION", DefaultLanguageHighlighterColors.METADATA
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
