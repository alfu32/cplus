package cplus.parser

import cplus.CPlusLoweringDiagnostic
import cplus.CPlusParseResult
import cplus.CPlusSyntaxNode
import cplus.MappedText
import cplus.MappedTextBuilder

data class TreeSitterComptimeBlockLoweringResult(
    val source: MappedText,
    val diagnostics: List<CPlusLoweringDiagnostic>
)

/** Unwraps top-level comptime blocks whose materialized contents are flags-only directives. */
class TreeSitterComptimeBlockLowering {
    fun lower(parsed: CPlusParseResult, source: MappedText): TreeSitterComptimeBlockLoweringResult {
        require(parsed.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }

        val blocks = mutableListOf<CPlusSyntaxNode>()
        fun collect(node: CPlusSyntaxNode, moduleScope: Boolean) {
            if (node.kind == BLOCK_KIND) {
                if (moduleScope) blocks += node
                return
            }
            val childrenAreModuleScope = moduleScope &&
                (node.kind == "translation_unit" || node.kind in PREPROCESSOR_CONTAINERS)
            node.children.forEach { child -> collect(child, childrenAreModuleScope) }
        }
        collect(parsed.root, true)
        if (blocks.isEmpty()) return TreeSitterComptimeBlockLoweringResult(source, emptyList())

        val diagnostics = mutableListOf<CPlusLoweringDiagnostic>()
        val replacements = blocks.mapNotNull { block ->
            val body = block.children.singleOrNull { it.kind == "compound_statement" }
            if (body == null || body.span.endOffset - body.span.startOffset < 2 ||
                parsed.source.text[body.span.startOffset] != '{' ||
                parsed.source.text[body.span.endOffset - 1] != '}'
            ) {
                diagnostics += CPlusLoweringDiagnostic(
                    "CPLUS_COMPTIME_BLOCK_SHAPE",
                    "comptime block must have a compound body",
                    block.span
                )
                return@mapNotNull null
            }

            val statements = body.children.filter { child ->
                child.named && child.kind !in setOf("comment")
            }
            val unsupported = statements.firstOrNull { statement ->
                when (statement.kind) {
                    "cplus_comptime_flags", "cplus_comptime_value" -> false
                    "cplus_comptime_declaration" -> statement.children.none {
                        it.kind in setOf("cplus_comptime_flags", "cplus_comptime_value")
                    }
                    BLOCK_KIND -> false // Nested blocks are unwrapped on a later fixed-point pass.
                    else -> true
                }
            }
            if (unsupported != null) {
                diagnostics += CPlusLoweringDiagnostic(
                    "CPLUS_COMPTIME_BLOCK_UNSUPPORTED",
                    "prototype comptime blocks currently materialize compiler flags, scalar values, and nested comptime blocks",
                    unsupported.span
                )
                return@mapNotNull null
            }
            Replacement(
                block.span.startOffset,
                block.span.endOffset,
                source.slice(body.span.startOffset + 1, body.span.endOffset - 1)
            )
        }
        if (diagnostics.isNotEmpty()) return TreeSitterComptimeBlockLoweringResult(source, diagnostics)

        val output = MappedTextBuilder()
        var cursor = 0
        replacements.sortedBy { it.start }.forEach { replacement ->
            if (replacement.start < cursor) {
                diagnostics += CPlusLoweringDiagnostic(
                    "CPLUS_COMPTIME_BLOCK_OVERLAP",
                    "overlapping comptime blocks cannot be materialized in one pass",
                    parsed.source.sourceFile.span(replacement.start, replacement.end)
                )
                return@forEach
            }
            output.append(source, cursor, replacement.start)
            output.append(replacement.content)
            cursor = replacement.end
        }
        if (diagnostics.isNotEmpty()) return TreeSitterComptimeBlockLoweringResult(source, diagnostics)
        output.append(source, cursor, source.text.length)
        return TreeSitterComptimeBlockLoweringResult(output.build(), emptyList())
    }

    private data class Replacement(val start: Int, val end: Int, val content: MappedText)

    private companion object {
        const val BLOCK_KIND = "cplus_comptime_block"
        val PREPROCESSOR_CONTAINERS = setOf(
            "preproc_if", "preproc_ifdef", "preproc_else", "preproc_elif", "preproc_elifdef",
            "preproc_elifndef", "preproc_endif"
        )
    }
}
