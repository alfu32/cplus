package cplus.intellij

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

class CPlusFileType private constructor() : LanguageFileType(CPlusLanguage) {
    override fun getName(): String = "C-plus"
    override fun getDescription(): String = "C-plus source file"
    override fun getDefaultExtension(): String = "cp"
    override fun getIcon(): Icon = IconLoader.getIcon("/icons/cplus.svg", CPlusFileType::class.java)

    companion object {
        @JvmField
        val INSTANCE = CPlusFileType()
    }
}
