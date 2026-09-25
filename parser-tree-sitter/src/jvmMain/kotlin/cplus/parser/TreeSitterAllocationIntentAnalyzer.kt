package cplus.parser

import cplus.AllocationAnalysisResult
import cplus.AllocationIntent
import cplus.AllocationOwnership
import cplus.AllocationSymbol
import cplus.AllocationSymbolKind
import cplus.CPlusAst
import cplus.CPlusAstNode
import cplus.CPlusDiagnostic

/**
 * AST-backed allocation-intent analysis for the prototype frontend.
 *
 * This first slice reports explicitly annotated variables initialized directly by a
 * domain-specific allocator. Alias/data-flow analysis remains with the legacy analyzer
 * until an AST scope/data-flow model can preserve its behavior.
 */
class TreeSitterAllocationIntentAnalyzer {
    fun analyze(ast: CPlusAst): AllocationAnalysisResult {
        val symbols = mutableListOf<AllocationSymbol>()
        val diagnostics = mutableListOf<CPlusDiagnostic>()
        val source = ast.source.text

        ast.root.descendantsAndSelf().filter { it.syntaxKind == "declaration" }.forEach { declaration ->
            val annotations = declaration.children
                .filter { it.syntaxKind == "cplus_result_annotation" }
                .map { text(source, it) }
            val intent = annotations.asSequence().map(::allocationIntent).firstOrNull { it != AllocationIntent.NONE }
                ?: AllocationIntent.NONE
            val ownership = annotations.asSequence().map(::allocationOwnership).firstOrNull { it != AllocationOwnership.NONE }
                ?: AllocationOwnership.NONE

            declaration.children.filter { it.fieldName == "declarator" }.forEach { declarator ->
                val initDeclarator = declarator.takeIf { it.syntaxKind == "init_declarator" }
                val nameDeclarator = initDeclarator?.children?.firstOrNull { it.fieldName == "declarator" } ?: declarator
                val nameNode = nameDeclarator.descendantsAndSelf()
                    .firstOrNull { it.syntaxKind == "identifier" }
                    ?: return@forEach
                val name = text(source, nameNode)
                val initializer = initDeclarator?.children?.firstOrNull { it.fieldName == "value" }
                val allocator = initializer?.takeIf { it.syntaxKind == "call_expression" }
                    ?.children?.firstOrNull { it.fieldName == "function" }
                    ?.let { text(source, it) }
                    ?.let(::allocatorIntent)

                symbols += AllocationSymbol(
                    name = name,
                    kind = AllocationSymbolKind.VARIABLE,
                    intent = intent,
                    ownership = ownership,
                    knownProvenance = allocator ?: AllocationIntent.NONE,
                    sourceSpan = ast.source.sourceFile.span(nameNode.span.startOffset, nameNode.span.endOffset)
                )

                if (intent != AllocationIntent.NONE && allocator != null && intent != allocator) {
                    diagnostics += CPlusDiagnostic(
                        "allocation intent mismatch: '$name' is declared ${intent.label()} but receives memory from alloc_${allocator.label()}()",
                        ast.source.sourceFile.span(nameNode.span.startOffset, nameNode.span.endOffset)
                    )
                }
            }
        }

        return AllocationAnalysisResult(
            symbols.distinctBy { listOf(it.name, it.kind, it.sourceSpan.file, it.sourceSpan.startOffset) },
            diagnostics.distinctBy { it.sourceSpan.file to it.sourceSpan.startOffset }
        )
    }

    private fun allocationIntent(token: String): AllocationIntent = when (token) {
        "scratch" -> AllocationIntent.SCRATCH
        "hot" -> AllocationIntent.HOT
        "warm" -> AllocationIntent.WARM
        "cold" -> AllocationIntent.COLD
        else -> AllocationIntent.NONE
    }

    private fun allocationOwnership(token: String): AllocationOwnership = when (token) {
        "borrowed" -> AllocationOwnership.BORROWED
        "owned" -> AllocationOwnership.OWNED
        else -> AllocationOwnership.NONE
    }

    private fun allocatorIntent(name: String): AllocationIntent? {
        val prefix = when {
            name.startsWith("alloc_") -> name.removePrefix("alloc_")
            name.startsWith("calloc_") -> name.removePrefix("calloc_")
            name.startsWith("realloc_") -> name.removePrefix("realloc_")
            else -> return null
        }.removeSuffix("_aligned")
        return allocationIntent(prefix).takeIf { it != AllocationIntent.NONE }
    }

    private fun text(source: String, node: CPlusAstNode): String =
        source.substring(node.span.startOffset, node.span.endOffset)

    private fun AllocationIntent.label(): String = name.lowercase()

    private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
        sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }
}
