package cplus.intellij

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType

object CPlusTokenTypes {
    @JvmField val FILE = IFileElementType(CPlusLanguage)
    @JvmField val ANNOTATION = IElementType("CPLUS_ANNOTATION", CPlusLanguage)
    @JvmField val COMMENT = IElementType("CPLUS_COMMENT", CPlusLanguage)
    @JvmField val KEYWORD = IElementType("CPLUS_KEYWORD", CPlusLanguage)
    @JvmField val TYPE = IElementType("CPLUS_TYPE", CPlusLanguage)
    @JvmField val NUMBER = IElementType("CPLUS_NUMBER", CPlusLanguage)
    @JvmField val STRING = IElementType("CPLUS_STRING", CPlusLanguage)
    @JvmField val IDENTIFIER = IElementType("CPLUS_IDENTIFIER", CPlusLanguage)
    @JvmField val PREPROCESSOR = IElementType("CPLUS_PREPROCESSOR", CPlusLanguage)
}
