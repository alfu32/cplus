package cplus.parser

import cplus.CPlusLoweringDiagnostic
import cplus.CPlusParseResult
import cplus.CPlusSyntaxNode
import cplus.CPlusTarget
import cplus.MappedText
import cplus.MappedTextBuilder

data class TreeSitterConditionalLoweringResult(
    val source: MappedText,
    val diagnostics: List<CPlusLoweringDiagnostic>
)

/**
 * Materializes the deliberately small, structural `@if (os == "...")` subset for the
 * experimental parser pipeline. Conditions are recognized from parser nodes; unsupported forms
 * fail closed. Only outermost conditionals are replaced per pass so selected nested branches are
 * reparsed and lowered on the next pass.
 */
class TreeSitterComptimeConditionalLowering {
    fun lower(parsed: CPlusParseResult, source: MappedText, targetOs: String): TreeSitterConditionalLoweringResult {
        require(parsed.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }

        val conditionals = mutableListOf<CPlusSyntaxNode>()
        fun collect(node: CPlusSyntaxNode, insideConditional: Boolean = false) {
            val isConditional = node.kind == CONDITIONAL_KIND
            if (isConditional && !insideConditional) conditionals += node
            node.children.forEach { collect(it, insideConditional || isConditional) }
        }
        collect(parsed.root)
        if (conditionals.isEmpty()) return TreeSitterConditionalLoweringResult(source, emptyList())

        val diagnostics = mutableListOf<CPlusLoweringDiagnostic>()
        val replacements = conditionals.mapNotNull { conditional ->
            val branches = conditional.children.filter { it.fieldName == "body" }
            val conditions = conditional.children.filter { it.fieldName == "condition" }
            if (branches.isEmpty() || conditions.size > branches.size || branches.size > conditions.size + 1) {
                diagnostics += CPlusLoweringDiagnostic(
                    "CPLUS_COMPTIME_CONDITIONAL_SHAPE",
                    "malformed comptime conditional branch structure",
                    conditional.span
                )
                return@mapNotNull null
            }

            val matches = conditions.map { condition ->
                evaluateOsCondition(condition, parsed.source.text, targetOs).also { supported ->
                    if (supported == null) {
                        diagnostics += CPlusLoweringDiagnostic(
                            "CPLUS_COMPTIME_CONDITION_UNSUPPORTED",
                            "prototype currently supports only os == \"value\" and os != \"value\" comptime conditions",
                            condition.span
                        )
                    }
                }
            }
            if (diagnostics.isNotEmpty()) return@mapNotNull null
            val selectedIndex = matches.indexOfFirst { it == true }.takeIf { it >= 0 }
                ?: conditions.size.takeIf { branches.size > conditions.size }
            val selected = selectedIndex?.let(branches::getOrNull)

            val content = selected?.let { body ->
                if (body.kind != "compound_statement" || body.span.endOffset - body.span.startOffset < 2 ||
                    parsed.source.text[body.span.startOffset] != '{' ||
                    parsed.source.text[body.span.endOffset - 1] != '}'
                ) {
                    diagnostics += CPlusLoweringDiagnostic(
                        "CPLUS_COMPTIME_CONDITIONAL_BODY",
                        "comptime conditional branches must be compound statements",
                        body.span
                    )
                    return@mapNotNull null
                }
                source.slice(body.span.startOffset + 1, body.span.endOffset - 1)
            } ?: MappedText.generated("")
            Replacement(conditional.span.startOffset, conditional.span.endOffset, content)
        }
        if (diagnostics.isNotEmpty()) return TreeSitterConditionalLoweringResult(source, diagnostics)

        val output = MappedTextBuilder()
        var cursor = 0
        replacements.sortedBy { it.start }.forEach { replacement ->
            if (replacement.start < cursor) {
                diagnostics += CPlusLoweringDiagnostic(
                    "CPLUS_COMPTIME_CONDITIONAL_OVERLAP",
                    "overlapping comptime conditional ranges cannot be materialized",
                    parsed.source.sourceFile.span(replacement.start, replacement.end)
                )
                return@forEach
            }
            output.append(source, cursor, replacement.start)
            output.append(replacement.content)
            cursor = replacement.end
        }
        if (diagnostics.isNotEmpty()) return TreeSitterConditionalLoweringResult(source, diagnostics)
        output.append(source, cursor, source.text.length)
        return TreeSitterConditionalLoweringResult(output.build(), emptyList())
    }

    private fun evaluateOsCondition(condition: CPlusSyntaxNode, text: String, targetOs: String): Boolean? {
        if (condition.kind != "binary_expression") return null
        val operands = condition.children.filter { it.named }
        if (operands.size != 2 || operands[0].kind != "identifier" || operands[1].kind != "string_literal") return null
        if (text.substring(operands[0].span.startOffset, operands[0].span.endOffset) != "os") return null
        val operator = text.substring(operands[0].span.endOffset, operands[1].span.startOffset).trim()
        val literal = text.substring(operands[1].span.startOffset, operands[1].span.endOffset)
        if (literal.length < 2 || literal.first() != '"' || literal.last() != '"') return null
        val value = literal.substring(1, literal.length - 1)
        if (value.any { it == '\\' || it == '"' }) return null
        return when (operator) {
            "==" -> CPlusTarget.normalizeOs(value) == CPlusTarget.normalizeOs(targetOs)
            "!=" -> CPlusTarget.normalizeOs(value) != CPlusTarget.normalizeOs(targetOs)
            else -> null
        }
    }

    private data class Replacement(val start: Int, val end: Int, val content: MappedText)

    private companion object {
        const val CONDITIONAL_KIND = "cplus_comptime_conditional"
    }
}
