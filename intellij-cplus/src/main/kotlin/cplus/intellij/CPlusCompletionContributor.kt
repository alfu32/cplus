package cplus.intellij

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.util.ProcessingContext

class CPlusCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, com.intellij.patterns.PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(
                parameters: CompletionParameters,
                context: ProcessingContext,
                result: com.intellij.codeInsight.completion.CompletionResultSet
            ) {
                val text = parameters.editor.document.text
                val prefix = text.substring(0, parameters.editor.caretModel.offset)
                val receiver = Regex("([A-Za-z_]\\w*)\\s*\\.\\s*[A-Za-z_]*$").find(prefix)?.groupValues?.get(1)
                val type = receiver?.let { variableType(text, it) } ?: receiver?.takeIf { it.endsWith("_t") }
                if (type != null) {
                    fields(text, type).forEach { result.addElement(LookupElementBuilder.create(it).withTypeText("C-plus field")) }
                    methods(text, type).forEach { result.addElement(LookupElementBuilder.create(it).withTypeText("C-plus method")) }
                    return
                }

                if (prefix.matches(Regex("(?s).*\\bcomptime\\s+[A-Za-z_]*$"))) {
                    listOf("type", "variable", "function", "code", "import", "string", "int", "float", "void").forEach {
                        result.addElement(LookupElementBuilder.create(it).withTypeText("C-plus comptime form"))
                    }
                    return
                }

                if (prefix.matches(Regex("(?s).*@[A-Za-z_]*$"))) {
                    listOf("@import", "@if", "@else", "@for", "@type", "@var", "@fn").forEach {
                        result.addElement(LookupElementBuilder.create(it).withTypeText("C-plus comptime"))
                    }
                    Regex("@([A-Za-z_]\\w*)").findAll(text).map { it.groupValues[1] }.distinct().forEach {
                        result.addElement(LookupElementBuilder.create("@$it").withTypeText("comptime symbol"))
                    }
                } else {
                    annotations.forEach { result.addElement(LookupElementBuilder.create(it).withTypeText("C-plus annotation")) }
                    Regex("\\b[A-Za-z_]\\w*_t\\b").findAll(text).map { it.value }.distinct().forEach {
                        result.addElement(LookupElementBuilder.create(it).withTypeText("C-plus type"))
                    }
                    Regex("\\b(?:pub\\s+|priv\\s+|static\\s+)?[A-Za-z_]\\w*(?:\\s*\\*)?\\s+([A-Za-z_]\\w*)\\s*\\(")
                        .findAll(text).map { it.groupValues[1] }.distinct().forEach {
                            result.addElement(LookupElementBuilder.create(it).withTypeText("C-plus function"))
                        }
                    Regex("(?m)^\\s*#\\s*define\\s+([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*$")
                        .findAll(text).map { it.groupValues[2] to it.groupValues[1] }.distinct().forEach { (alias, target) ->
                            result.addElement(LookupElementBuilder.create(alias).withTypeText("C preprocessor alias for $target"))
                        }
                }
            }
        })
    }

    private fun variableType(text: String, variable: String): String? =
        Regex("\\b([A-Za-z_]\\w*_t)\\s+" + Regex.escape(variable) + "\\b").find(text)?.groupValues?.get(1)

    private fun fields(text: String, type: String): List<String> {
        val body = structBody(text, type) ?: return emptyList()
        return Regex("\\b([A-Za-z_]\\w*)\\s*(?:\\[[^]]*])?\\s*;")
            .findAll(body)
            .map { it.groupValues[1] }
            .filterNot { it in annotations }
            .distinct()
            .toList()
    }

    private fun methods(text: String, type: String): List<String> {
        val body = structBody(text, type) ?: return emptyList()
        return Regex("\\b([A-Za-z_]\\w*)\\s*\\(")
            .findAll(body)
            .map { it.groupValues[1] }
            .filterNot { it in controlKeywords }
            .distinct()
            .toList()
    }

    private fun structBody(text: String, type: String): String? =
        Regex("typedef\\s+struct\\s+@?" + Regex.escape(type) + "\\s*\\{([\\s\\S]*?)\\}\\s*" + Regex.escape(type) + "\\s*;")
            .find(text)?.groupValues?.get(1)

    companion object {
        private val annotations = listOf("pub", "priv", "mut", "borrowed", "owned", "stat")
        private val controlKeywords = setOf("if", "for", "while", "switch")
    }
}
