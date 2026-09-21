package cplus.intellij

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.util.ProcessingContext

class CPlusCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(
                parameters: CompletionParameters,
                context: ProcessingContext,
                result: com.intellij.codeInsight.completion.CompletionResultSet
            ) {
                annotations.forEach { result.addElement(LookupElementBuilder.create(it).withTypeText("C-plus annotation")) }
                val methods = Regex("\\b(?:static\\s+)?(?:pub|priv)?\\s*[A-Za-z_]\\w*(?:\\s*\\*)?\\s+([A-Za-z_]\\w*)\\s*\\(")
                    .findAll(parameters.editor.document.text)
                    .map { it.groupValues[1] }
                    .distinct()
                methods.forEach { result.addElement(LookupElementBuilder.create(it).withTypeText("C-plus method")) }
            }
        })
    }

    companion object {
        private val annotations = listOf("pub", "priv", "mut", "borrowed", "owned", "stat")
    }
}
