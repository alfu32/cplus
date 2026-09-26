package cplus.parser

import cplus.CPlusLoweringDiagnostic
import cplus.CPlusParseResult
import cplus.CPlusSyntaxNode
import cplus.MappedText
import cplus.MappedTextBuilder

data class TreeSitterFlagsLoweringResult(
    val source: MappedText,
    val compilerOptions: List<String>,
    val diagnostics: List<CPlusLoweringDiagnostic>
)

/** Extracts compiler arguments from AST-recognized active module-scope `comptime flags` nodes. */
class TreeSitterComptimeFlagsLowering {
    fun lower(parsed: CPlusParseResult, source: MappedText): TreeSitterFlagsLoweringResult {
        require(parsed.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }

        data class Directive(val node: CPlusSyntaxNode, val replacement: CPlusSyntaxNode, val arguments: List<String>)
        val directives = mutableListOf<Directive>()
        val invalidScope = mutableListOf<CPlusSyntaxNode>()
        fun collect(node: CPlusSyntaxNode, parent: CPlusSyntaxNode?, dormant: Boolean, moduleScope: Boolean) {
            val isDormant = dormant || node.kind in DORMANT_REGIONS
            if (!isDormant && node.kind == "cplus_comptime_flags") {
                if (!moduleScope) {
                    invalidScope += node
                } else {
                    val args = parseArguments(node, parsed.source.text)
                    val replacement = parent?.takeIf { it.kind == "cplus_comptime_declaration" } ?: node
                    directives += Directive(node, replacement, args)
                }
            }
            val nestedModuleScope = moduleScope && node.kind in MODULE_SCOPE_NODES
            node.children.forEach { collect(it, node, isDormant, nestedModuleScope) }
        }
        collect(parsed.root, null, false, true)
        if (invalidScope.isNotEmpty()) {
            return TreeSitterFlagsLoweringResult(
                source,
                emptyList(),
                invalidScope.map {
                    CPlusLoweringDiagnostic(
                        "CPLUS_COMPTIME_FLAGS_SCOPE",
                        "comptime flags are only supported at module scope in this prototype",
                        it.span
                    )
                }
            )
        }

        val options = linkedSetOf<String>()
        directives.forEach { directive ->
            if (directive.arguments.isEmpty()) {
                return TreeSitterFlagsLoweringResult(
                    source,
                    emptyList(),
                    listOf(CPlusLoweringDiagnostic(
                        "CPLUS_COMPTIME_FLAGS_EMPTY",
                        "comptime flags must contain at least one compiler argument",
                        directive.node.span
                    ))
                )
            }
            options += directive.arguments
        }
        if (directives.isEmpty()) return TreeSitterFlagsLoweringResult(source, emptyList(), emptyList())

        val output = MappedTextBuilder()
        var cursor = 0
        directives.map { it.replacement }.distinctBy { it.span.startOffset }
            .sortedBy { it.span.startOffset }.forEach { replacement ->
                if (replacement.span.startOffset < cursor) {
                    return TreeSitterFlagsLoweringResult(
                        source,
                        emptyList(),
                        listOf(CPlusLoweringDiagnostic(
                            "CPLUS_COMPTIME_FLAGS_OVERLAP",
                            "overlapping comptime flags source ranges cannot be materialized",
                            replacement.span
                        ))
                    )
                }
                output.append(source, cursor, replacement.span.startOffset)
                cursor = replacement.span.endOffset
            }
        output.append(source, cursor, source.text.length)
        return TreeSitterFlagsLoweringResult(output.build(), options.toList(), emptyList())
    }

    private fun parseArguments(node: CPlusSyntaxNode, source: String): List<String> {
        val leaves = mutableListOf<CPlusSyntaxNode>()
        fun collectLeaves(current: CPlusSyntaxNode) {
            if (current.kind == "comment") return
            if (current.kind == "string_literal" || current.children.isEmpty()) {
                leaves += current
            } else {
                current.children.forEach(::collectLeaves)
            }
        }
        collectLeaves(node)
        val flagsEnd = leaves.firstOrNull {
            source.substring(it.span.startOffset, it.span.endOffset) == "flags"
        }?.span?.endOffset ?: return emptyList()
        val tokens = leaves.asSequence()
            .filter { it.span.startOffset >= flagsEnd }
            .filterNot { it.kind == ";" || it.kind == "comment" }
            .sortedBy { it.span.startOffset }
            .toList()
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var previousEnd = flagsEnd
        fun flush() {
            if (token.isNotEmpty()) result += token.toString()
            token.setLength(0)
        }
        tokens.forEach { leaf ->
            val gap = source.substring(previousEnd.coerceAtMost(source.length), leaf.span.startOffset.coerceAtMost(source.length))
            if (gap.any(Char::isWhitespace) || "/*" in gap || "//" in gap) flush()
            val raw = source.substring(leaf.span.startOffset, leaf.span.endOffset)
            if (leaf.kind == "string_literal") {
                flush()
                result += unquote(raw)
            } else {
                token.append(raw)
            }
            previousEnd = leaf.span.endOffset
        }
        flush()
        return result.filter { it.isNotEmpty() && it.none(Char::isISOControl) }
    }

    private fun unquote(raw: String): String {
        if (raw.length < 2 || raw.first() !in setOf('\'', '"') || raw.last() != raw.first()) return raw
        val output = StringBuilder()
        var index = 1
        while (index < raw.lastIndex) {
            val character = raw[index]
            if (character == '\\' && index + 1 < raw.lastIndex && raw[index + 1] in setOf(raw.first(), '\\')) {
                output.append(raw[index + 1])
                index += 2
            } else {
                output.append(character)
                index++
            }
        }
        return output.toString()
    }

    private companion object {
        val DORMANT_REGIONS = setOf(
            "cplus_comptime_function_definition", "cplus_legacy_function_generator",
            "cplus_legacy_type_generator", "cplus_comptime_block", "cplus_code_fragment"
        )
        val MODULE_SCOPE_NODES = setOf(
            "translation_unit", "cplus_comptime_declaration", "preproc_if", "preproc_else", "preproc_elif",
            "preproc_ifdef", "preproc_ifndef", "preproc_elifdef", "preproc_elifndef"
        )
    }
}
