package cplus.intellij

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

class CPlusGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val file = sourceElement?.containingFile ?: return null
        val text = file.text
        val word = wordAt(text, offset) ?: return null
        val declaration = Regex(
            "(?:typedef\\s+struct\\s+@?|\\b(?:pub|priv|static)\\s+|\\b)([A-Za-z_]\\w*)\\s*(?:\\{|\\(|;)"
        ).findAll(text).firstOrNull { it.groupValues[1] == word } ?: return null
        return file.findElementAt(declaration.range.first)?.let { arrayOf(it) }
    }

    private fun wordAt(text: String, offset: Int): String? {
        if (offset < 0 || offset > text.length) return null
        var start = offset
        var end = offset
        while (start > 0 && isWord(text[start - 1])) start--
        while (end < text.length && isWord(text[end])) end++
        return text.substring(start, end).takeIf { it.isNotEmpty() }
    }

    private fun isWord(character: Char): Boolean = character.isLetterOrDigit() || character == '_'
}
