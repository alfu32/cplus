package cplus

/** One ordered transformation over the current normalized AST and its mapped source. */
data class CPlusAstLoweringStep(
    val id: String,
    val transform: (CPlusAst, MappedText) -> CPlusLoweringResult
)

/** Observable evidence of the exact AST/source revisions seen by a lowering pipeline. */
data class CPlusAstLoweringTrace(
    val stepId: String,
    val inputLength: Int,
    val outputLength: Int,
    val sourceChanged: Boolean
)

data class CPlusAstLoweringPipelineResult(
    val source: MappedText,
    val ast: CPlusAst,
    val loweringDiagnostics: List<CPlusLoweringDiagnostic>,
    val parserDiagnostics: List<ParserDiagnostic>,
    val trace: List<CPlusAstLoweringTrace>,
    /** Declarations synthesized by structured passes, retained across reparses. */
    val synthesizedDeclarations: List<CPlusSynthesizedDeclaration> = emptyList(),
    /**
     * Normalized declaration nodes corresponding to [synthesizedDeclarations]
     * in the latest successfully reparsed AST. These nodes are semantic
     * handles for downstream consumers; callers must use the mapped source
     * when translating their generated spans back to the original source.
     */
    val synthesizedNodes: List<CPlusAstNode> = emptyList()
) {
    val successful: Boolean
        get() = loweringDiagnostics.isEmpty() && parserDiagnostics.isEmpty()
}

/**
 * Executes AST transformations in declared order, reparsing after every source-changing pass.
 * The input tree is never reused against a different source revision. Any lowering or parse
 * failure stops subsequent passes and leaves the legacy production compiler unaffected.
 */
class CPlusAstLoweringPipeline {
    fun run(
        initialAst: CPlusAst,
        initialSource: MappedText,
        steps: List<CPlusAstLoweringStep>,
        reparse: (MappedText) -> CPlusParseResult
    ): CPlusAstLoweringPipelineResult {
        require(initialAst.source.text == initialSource.text) {
            "initial AST and mapped source must contain the same snapshot text"
        }
        require(steps.map { it.id }.distinct().size == steps.size) {
            "lowering step IDs must be unique"
        }

        var ast = initialAst
        var source = initialSource
        val trace = mutableListOf<CPlusAstLoweringTrace>()
        val synthesizedDeclarations = mutableListOf<CPlusSynthesizedDeclaration>()

        fun synthesizedNodesIn(currentAst: CPlusAst): List<CPlusAstNode> {
            return currentAst.synthesizedDeclarationNodes(synthesizedDeclarations, source)
        }

        for (step in steps) {
            val inputLength = source.text.length
            val lowered = step.transform(ast, source)
            trace += CPlusAstLoweringTrace(
                stepId = step.id,
                inputLength = inputLength,
                outputLength = lowered.source.text.length,
                sourceChanged = lowered.source.text != source.text
            )
            if (lowered.diagnostics.isNotEmpty()) {
                return CPlusAstLoweringPipelineResult(
                    source,
                    ast,
                    lowered.diagnostics,
                    emptyList(),
                    trace,
                    synthesizedDeclarations,
                    synthesizedNodesIn(ast)
                )
            }
            synthesizedDeclarations += lowered.synthesizedDeclarations.map { declaration ->
                declaration.copy(sourceSpan = source.toOriginalSpan(declaration.sourceSpan))
            }

            if (lowered.source.text != source.text) {
                source = lowered.source
                val parsed = reparse(source)
                if (parsed.diagnostics.isNotEmpty()) {
                    return CPlusAstLoweringPipelineResult(
                        source,
                        ast,
                        emptyList(),
                        parsed.diagnostics.map { diagnostic ->
                            diagnostic.copy(span = source.toOriginalSpan(diagnostic.span))
                        },
                        trace,
                        synthesizedDeclarations,
                        synthesizedNodesIn(ast)
                    )
                }
                ast = CPlusAstAdapter().adapt(parsed)
            } else {
                source = lowered.source
            }
        }

        return CPlusAstLoweringPipelineResult(
            source,
            ast,
            emptyList(),
            emptyList(),
            trace,
            synthesizedDeclarations,
            synthesizedNodesIn(ast)
        )
    }

    internal companion object {
        val SYNTHESIZED_DECLARATION_KINDS = setOf(
            CPlusAstKind.STRUCT_DECLARATION,
            CPlusAstKind.UNION_DECLARATION,
            CPlusAstKind.ENUM_DECLARATION,
            CPlusAstKind.TYPE_ALIAS,
            CPlusAstKind.FUNCTION_DECLARATION,
            CPlusAstKind.VARIABLE_DECLARATION
        )
    }
}

/**
 * Return normalized declaration nodes whose declaration header names one of the generated
 * symbols and whose identifier origin belongs to the declaration's original source span. The
 * header boundary is important: a function body may call a generated function, but that does
 * not make the enclosing function a synthesized declaration. Origin matching also prevents an
 * unrelated pre-existing prototype with the same generated name from being reported as new.
 */
fun CPlusAst.synthesizedDeclarationNodes(
    declarations: List<CPlusSynthesizedDeclaration>,
    mappedSource: MappedText
): List<CPlusAstNode> {
    if (declarations.isEmpty()) return emptyList()
    val source = source.text
    val candidates = root.descendantsAndSelf()
        .filter { it.kind in CPlusAstLoweringPipeline.SYNTHESIZED_DECLARATION_KINDS }
        .toList()
    return declarations.mapNotNull { declaration ->
        candidates.firstOrNull { node ->
            if (node.kind != declaration.kind) return@firstOrNull false
            val declarationBodyStart = node.descendantsAndSelf()
                .firstOrNull { it.syntaxKind in setOf("compound_statement", "cplus_block") }
                ?.span?.startOffset
                ?: node.span.endOffset
            node.descendantsAndSelf()
                .filter { it.kind == CPlusAstKind.IDENTIFIER && it.span.startOffset < declarationBodyStart }
                .any { identifier ->
                    source.substring(identifier.span.startOffset, identifier.span.endOffset) == declaration.generatedName &&
                        originOverlaps(
                            mappedSource,
                            identifier.span,
                            listOf(declaration.sourceSpan) + declaration.originSpans
                        )
                }
        }
}.distinctBy { it.span.startOffset to it.span.endOffset }
}

/**
 * Return normalized aggregate-member nodes owned by synthesized struct/union declarations.
 * These handles use the same successful pre-hygiene AST revision as declaration handles.
 */
fun CPlusAst.synthesizedMemberNodes(synthesizedDeclarations: List<CPlusAstNode>): List<CPlusAstNode> =
    synthesizedDeclarations
        .filter { it.kind in setOf(CPlusAstKind.STRUCT_DECLARATION, CPlusAstKind.UNION_DECLARATION) }
        .flatMap { aggregate ->
            aggregate.descendantsAndSelf()
                .filter { it.kind in setOf(CPlusAstKind.FIELD_DECLARATION, CPlusAstKind.METHOD_DECLARATION) }
                .toList()
        }
        .distinctBy { it.span.startOffset to it.span.endOffset }

/**
 * Generated identifiers may combine copied template text with generated text.  Looking only at
 * the first character's origin loses declarations such as `box__int_box_t`, whose `box` prefix
 * comes from the generator and whose specialization suffix comes from the invocation.  Any
 * mapped overlap with the declaration's recorded provenance is sufficient, while an entirely
 * unrelated same-named prototype remains excluded.
 */
private fun originOverlaps(
    mappedSource: MappedText,
    identifier: SourceSpan,
    origins: List<SourceSpan>
): Boolean {
    if (origins.isEmpty()) return false
    for (offset in identifier.startOffset until identifier.endOffset) {
        val origin = mappedSource.originAt(offset) ?: continue
        if (origins.any { span ->
                origin.file.name == span.file && origin.offset in span.startOffset until span.endOffset
            }) return true
    }
    return false
}

private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
    sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }
