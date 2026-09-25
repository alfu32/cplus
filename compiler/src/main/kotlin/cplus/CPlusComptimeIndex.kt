package cplus

/** A source-spanned comptime construct extracted from parser-produced syntax, not text scanning. */
data class CPlusComptimeConstruct(
    val syntaxKind: String,
    val span: SourceSpan,
    val symbol: String?,
    val bodySpan: SourceSpan?,
    val activeThisPass: Boolean
)

data class CPlusComptimeIndex(
    val constructs: List<CPlusComptimeConstruct>,
    val imports: List<SourceSpan>,
    val tests: List<SourceSpan>
)

/** Indexes comptime declarations/invocations for later evaluator migration. No evaluation occurs. */
class CPlusComptimeIndexer {
    fun index(ast: CPlusAst): CPlusComptimeIndex {
        val nodes = mutableListOf<Pair<CPlusAstNode, Boolean>>()
        fun collect(node: CPlusAstNode, dormant: Boolean = false) {
            nodes += node to dormant
            node.children.forEach { child ->
                val bodyOfGenerator = node.syntaxKind in setOf("cplus_comptime_function_definition", "cplus_legacy_type_generator") &&
                    (child.fieldName == "body" || child.syntaxKind == "compound_statement")
                collect(child, dormant || bodyOfGenerator)
            }
        }
        collect(ast.root)
        val constructs = nodes.asSequence()
            .filter { it.first.syntaxKind in COMPTIME_NODES }
            .map { (node, dormant) ->
                val symbolNode = when (node.syntaxKind) {
                    "cplus_comptime_function_definition", "cplus_legacy_type_generator" ->
                        node.children.firstOrNull { it.fieldName == "name" }
                    "cplus_comptime_invocation" -> node.children.firstOrNull { it.fieldName == "generator" }
                    "cplus_comptime_value" -> node.children.firstOrNull { it.fieldName == "name" }
                    else -> null
                }
                val body = node.children.firstOrNull { it.fieldName == "body" }
                    ?: node.children.firstOrNull { it.syntaxKind == "compound_statement" }
                CPlusComptimeConstruct(
                    syntaxKind = node.syntaxKind,
                    span = node.span,
                    symbol = symbolNode?.text(ast.source.text)?.removePrefix("@"),
                    bodySpan = body?.span,
                    activeThisPass = !dormant
                )
            }.toList()
        return CPlusComptimeIndex(
            constructs,
            nodes.filter { it.first.syntaxKind in IMPORT_NODES && !it.second }.map { it.first.span },
            nodes.filter { it.first.syntaxKind == "cplus_test_declaration" && !it.second }.map { it.first.span }
        )
    }

    private fun CPlusAstNode.text(source: String): String = source.substring(span.startOffset, span.endOffset)

    private companion object {
        val COMPTIME_NODES = setOf(
            "cplus_comptime_function_definition", "cplus_legacy_type_generator", "cplus_comptime_import", "cplus_comptime_flags",
            "cplus_comptime_invocation", "cplus_comptime_value", "cplus_comptime_block",
            "cplus_comptime_conditional"
        )
        val IMPORT_NODES = setOf("cplus_comptime_import", "cplus_at_import")
    }
}
