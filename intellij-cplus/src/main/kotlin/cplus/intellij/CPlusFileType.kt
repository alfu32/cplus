package cplus.intellij

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

class CPlusFileType private constructor() : LanguageFileType(CPlusLanguage) {
    override fun getName(): String = "C-plus"
    override fun getDescription(): String = "C-plus source file"
    override fun getDefaultExtension(): String = "cp"
    override fun getIcon(): Icon? = null

    companion object {
        @JvmField
        val INSTANCE = CPlusFileType()
    }
}
