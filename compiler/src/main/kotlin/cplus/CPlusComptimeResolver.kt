package cplus

/** A comptime invocation bound to the generator declaration selected by name and arity. */
data class CPlusComptimeBinding(
    val invocationSpan: SourceSpan,
    val declarationSpan: SourceSpan,
    val symbol: String,
    val resultKind: String?
)

data class CPlusComptimeResolutionDiagnostic(
    val code: String,
    val message: String,
    val span: SourceSpan,
    val relatedSpan: SourceSpan? = null
)

data class CPlusComptimeResolution(
    val bindings: List<CPlusComptimeBinding>,
    val diagnostics: List<CPlusComptimeResolutionDiagnostic>
)

/**
 * Resolves active comptime invocation nodes against active generator declarations.
 * This is name/arity binding only; argument evaluation and overload type checking remain
 * responsibilities of the comptime evaluator and are deliberately not approximated here.
 */
class CPlusComptimeResolver {
    fun resolve(index: CPlusComptimeIndex): CPlusComptimeResolution {
        val declarations = index.constructs.filter {
            it.activeThisPass && it.moduleScope && it.syntaxKind in GENERATOR_KINDS && !it.symbol.isNullOrBlank()
        }
        val invocations = index.constructs.filter {
            it.activeThisPass && it.syntaxKind in INVOCATION_KINDS && !it.symbol.isNullOrBlank()
        }
        val diagnostics = mutableListOf<CPlusComptimeResolutionDiagnostic>()

        val declarationsBySignature = declarations.groupBy { Signature(it.symbol!!, it.parameters.size) }
        declarationsBySignature.values.filter { it.size > 1 }.forEach { duplicates ->
            val first = duplicates.first()
            duplicates.drop(1).forEach { duplicate ->
                diagnostics += CPlusComptimeResolutionDiagnostic(
                    "CPLUS_COMPTIME_DUPLICATE_GENERATOR",
                    "comptime generator '${first.symbol}' is declared more than once with ${first.parameters.size} parameter(s)",
                    duplicate.span,
                    first.span
                )
            }
        }

        val declarationsByName = declarations.groupBy { it.symbol!! }
        val bindings = mutableListOf<CPlusComptimeBinding>()
        invocations.forEach { invocation ->
            val name = invocation.symbol!!
            val named = declarationsByName[name].orEmpty()
            if (named.isEmpty()) {
                diagnostics += CPlusComptimeResolutionDiagnostic(
                    "CPLUS_COMPTIME_UNRESOLVED_GENERATOR",
                    "no active comptime generator named '$name' is visible",
                    invocation.span
                )
                return@forEach
            }
            val candidates = named.filter { it.parameters.size == invocation.argumentSpans.size }
            when (candidates.size) {
                0 -> diagnostics += CPlusComptimeResolutionDiagnostic(
                    "CPLUS_COMPTIME_ARGUMENT_COUNT",
                    "comptime generator '$name' expects ${named.map { it.parameters.size }.distinct().sorted().joinToString(" or ")} argument(s), but received ${invocation.argumentSpans.size}",
                    invocation.span,
                    named.first().span
                )
                1 -> bindings += CPlusComptimeBinding(
                    invocation.span,
                    candidates.single().span,
                    name,
                    candidates.single().resultKind
                )
                else -> diagnostics += CPlusComptimeResolutionDiagnostic(
                    "CPLUS_COMPTIME_AMBIGUOUS_GENERATOR",
                    "comptime invocation '$name' matches multiple active generators with ${invocation.argumentSpans.size} parameter(s)",
                    invocation.span,
                    candidates.first().span
                )
            }
        }
        return CPlusComptimeResolution(bindings, diagnostics)
    }

    private data class Signature(val name: String, val arity: Int)

    private companion object {
        val GENERATOR_KINDS = setOf(
            "cplus_comptime_function_definition",
            "cplus_legacy_type_generator",
            "cplus_legacy_function_generator"
        )
        val INVOCATION_KINDS = setOf("cplus_comptime_invocation", "cplus_comptime_type_definition")
    }
}
