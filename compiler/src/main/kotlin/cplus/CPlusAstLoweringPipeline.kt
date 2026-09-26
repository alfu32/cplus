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
    val trace: List<CPlusAstLoweringTrace>
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
                    trace
                )
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
                        trace
                    )
                }
                ast = CPlusAstAdapter().adapt(parsed)
            } else {
                source = lowered.source
            }
        }

        return CPlusAstLoweringPipelineResult(source, ast, emptyList(), emptyList(), trace)
    }
}
