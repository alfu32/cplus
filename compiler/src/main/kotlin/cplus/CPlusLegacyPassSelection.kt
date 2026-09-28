package cplus

/**
 * Selects the legacy textual lowering passes used by [CPlusTranspiler].
 *
 * The legacy frontend remains the default production path.  This switch exists only for
 * migration and rollback tests: disabling a pass leaves its C-plus syntax in the mapped source
 * so the compiler fails closed instead of silently changing semantics.
 */
data class CPlusLegacyPassSelection(
    val disabledPasses: Set<String> = emptySet(),
    /** Test-mode extraction is selected independently from runtime lowering passes. */
    val extractTests: Boolean = true,
    /** Migration-only rollback switch for legacy comptime resolution. */
    val resolveComptime: Boolean = true
) {
    init {
        require(disabledPasses.all { it in KNOWN_PASSES }) {
            "unknown legacy frontend pass in selection: ${disabledPasses - KNOWN_PASSES}"
        }
    }

    fun enabled(passId: String): Boolean = passId !in disabledPasses

    companion object {
        const val EXTRACT_THROWS = CPlusFrontendPassIds.EXTRACT_THROWS
        const val LOWER_DEFER = CPlusFrontendPassIds.LOWER_DEFER
        const val LOWER_METHOD_CALLS = CPlusFrontendPassIds.LOWER_METHOD_CALLS
        const val LOWER_STRUCT_METHODS = CPlusFrontendPassIds.LOWER_STRUCT_METHODS
        const val LOWER_TRY_CATCH = CPlusFrontendPassIds.LOWER_TRY_CATCH
        const val VALIDATE_SEMANTICS = CPlusFrontendPassIds.VALIDATE_SEMANTICS

        val KNOWN_PASSES: Set<String> = CPlusFrontendPassIds.ALL
    }
}
