package cplus

/**
 * Shared mapping diagnostics for tooling. Unknown fragments are warnings, not
 * a request to stop emission. Parser errors retain their original severity.
 * Descendants covered by an unknown parent do not produce duplicate warnings.
 */
fun CPlusAst.toolingMappingDiagnostics(): List<ParserDiagnostic> {
    val diagnostics = mutableListOf<ParserDiagnostic>()
    fun visit(node: CPlusAstNode, covered: Boolean) {
        val unsupported = node.named && (node.kind == CPlusAstKind.OTHER || node.opaque)
        if (unsupported && !covered) {
            diagnostics += ParserDiagnostic(
                "CPLUS_UNSUPPORTED_AST",
                "AST fragment '${node.syntaxKind}' has no compiler mapping; code generation continues",
                ParserDiagnosticSeverity.WARNING,
                node.span
            )
        }
        node.children.forEach { visit(it, covered || unsupported) }
    }
    visit(root, false)
    return diagnostics
}

/** Lossless parser-neutral identifier traversal, including lexical ancestry. */
data class CPlusAstIdentifierUse(val node: CPlusAstNode, val ancestors: List<CPlusAstNode>)

fun CPlusAstNode.identifierUses(): Sequence<CPlusAstIdentifierUse> = sequence {
    suspend fun SequenceScope<CPlusAstIdentifierUse>.visit(node: CPlusAstNode, parents: List<CPlusAstNode>) {
        if (node.kind == CPlusAstKind.IDENTIFIER) yield(CPlusAstIdentifierUse(node, parents))
        node.children.forEach { visit(it, parents + node) }
    }
    visit(this@identifierUses, emptyList())
}

/**
 * Declarator names owned by one declaration. Do not descend into initializer
 * expressions, aggregate bodies, or callback parameters: their identifiers
 * belong to different declarations. Supports C's comma-separated declarators.
 */
fun CPlusAstNode.declaredIdentifiers(): List<CPlusAstNode> {
    fun name(declarator: CPlusAstNode): CPlusAstNode? {
        if (declarator.kind == CPlusAstKind.IDENTIFIER) return declarator
        val nested = declarator.children.firstOrNull { it.fieldName == "declarator" }
            ?: declarator.children.firstOrNull {
                it.kind == CPlusAstKind.DECLARATOR || it.kind == CPlusAstKind.IDENTIFIER
            }
        return nested?.let(::name)
    }
    return children.filter { it.fieldName == "declarator" }.mapNotNull(::name)
}
