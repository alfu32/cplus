package cplus

/** Stable identifiers shared by migration selectors, traces, and differential reports. */
object CPlusFrontendPassIds {
    const val EXTRACT_THROWS = "extract-throws"
    const val LOWER_DEFER = "lower-defer"
    const val LOWER_METHOD_CALLS = "lower-method-calls"
    const val LOWER_STRUCT_METHODS = "lower-struct-methods"
    const val LOWER_TRY_CATCH = "lower-try-catch"
    const val VALIDATE_SEMANTICS = "validate-semantics"

    val ALL: Set<String> = linkedSetOf(
        EXTRACT_THROWS,
        LOWER_DEFER,
        LOWER_METHOD_CALLS,
        LOWER_STRUCT_METHODS,
        LOWER_TRY_CATCH,
        VALIDATE_SEMANTICS
    )
}
