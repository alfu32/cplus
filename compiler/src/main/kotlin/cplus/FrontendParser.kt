package cplus

enum class ParserBackendId {
    LEGACY,
    TREE_SITTER
}

enum class ParseCoverage {
    /** The backend recognizes the full grammar represented by this translation unit. */
    STRUCTURAL,
    /** Only explicitly listed constructs have syntax nodes; remaining text is opaque. */
    PARTIAL,
    /** The backend preserved the file as text and did not parse its syntax. */
    OPAQUE
}

enum class ParserDiagnosticSeverity {
    ERROR,
    WARNING
}

data class ParserDiagnostic(
    val code: String,
    val message: String,
    val severity: ParserDiagnosticSeverity,
    val span: SourceSpan
)

/** Generator-neutral syntax node. Offsets in [span] follow the source snapshot's UTF-16 convention. */
data class CPlusSyntaxNode(
    val kind: String,
    val span: SourceSpan,
    val children: List<CPlusSyntaxNode> = emptyList(),
    val fieldName: String? = null,
    val named: Boolean = true,
    val isError: Boolean = false,
    val isMissing: Boolean = false,
    val opaque: Boolean = false
)

data class CPlusParseOptions(
    val editorMode: Boolean = false,
    val targetOs: String = CPlusTarget.hostOs()
)

data class CPlusParseResult(
    val source: SourceSnapshot,
    val backend: ParserBackendId,
    val coverage: ParseCoverage,
    val root: CPlusSyntaxNode,
    val diagnostics: List<ParserDiagnostic> = emptyList(),
    val limitations: List<String> = emptyList()
)

/** Parser implementation boundary; generated-parser node types must not escape this interface. */
interface CPlusParserBackend {
    val id: ParserBackendId

    fun parse(source: SourceSnapshot, options: CPlusParseOptions = CPlusParseOptions()): CPlusParseResult
}

/** Registry/selector for parser implementations. Selection is explicit and defaults to legacy. */
class CPlusParserService(
    backends: Collection<CPlusParserBackend> = listOf(LegacyCPlusParserBackend()),
    val defaultBackend: ParserBackendId = ParserBackendId.LEGACY
) {
    private val backendsById = backends.associateBy(CPlusParserBackend::id)

    init {
        require(backendsById.size == backends.size) { "parser backend IDs must be unique" }
        require(defaultBackend in backendsById) { "default parser backend $defaultBackend is not registered" }
    }

    val availableBackends: Set<ParserBackendId>
        get() = backendsById.keys

    fun parse(
        source: SourceSnapshot,
        backend: ParserBackendId = defaultBackend,
        options: CPlusParseOptions = CPlusParseOptions()
    ): CPlusParseResult = (backendsById[backend]
        ?: throw IllegalArgumentException("parser backend $backend is not available (registered: ${availableBackends.joinToString()})"))
        .parse(source, options)
}

data class CPlusParserShadowReport(
    /** This result is authoritative and is never altered by running the shadow backend. */
    val authoritative: CPlusParseResult,
    val shadow: CPlusParseResult,
    val coverageMatches: Boolean,
    val diagnosticsMatch: Boolean,
    /** True when both backends report errors on intersecting ranges/insert points (or both are clean). */
    val errorLocationsAlign: Boolean,
    /** Equality for the deliberately shared comptime/test syntax subset only. */
    val recognizedConstructsMatch: Boolean,
    val authoritativeRecognizedConstructs: List<String>,
    val shadowRecognizedConstructs: List<String>,
    val authoritativeOnlyNodeCount: Int,
    val shadowOnlyNodeCount: Int,
    val authoritativeOnlyNodeSamples: List<String>,
    val shadowOnlyNodeSamples: List<String>
)

/** Runs a second parser for observation only; callers continue compiling from [authoritative]. */
class CPlusParserShadowRunner(
    private val authoritativeBackend: CPlusParserBackend,
    private val shadowBackend: CPlusParserBackend,
    private val sampleLimit: Int = 32
) {
    init {
        require(authoritativeBackend.id != shadowBackend.id) { "shadow parser must differ from authoritative backend" }
        require(sampleLimit >= 0) { "sampleLimit cannot be negative" }
    }

    fun parse(
        source: SourceSnapshot,
        options: CPlusParseOptions = CPlusParseOptions()
    ): CPlusParserShadowReport {
        val authoritative = authoritativeBackend.parse(source, options)
        val shadow = shadowBackend.parse(source, options)
        val primaryNodes = fingerprints(authoritative.root)
        val shadowNodes = fingerprints(shadow.root)
        val primarySet = primaryNodes.toSet()
        val shadowSet = shadowNodes.toSet()
        val primaryOnly = primaryNodes.filterNot(shadowSet::contains)
        val shadowOnly = shadowNodes.filterNot(primarySet::contains)
        val diagnosticSignature: (ParserDiagnostic) -> List<Any?> = { diagnostic ->
            listOf(diagnostic.code, diagnostic.message, diagnostic.severity, diagnostic.span)
        }
        val authoritativeErrors = authoritative.diagnostics.filter { it.severity == ParserDiagnosticSeverity.ERROR }
        val shadowErrors = shadow.diagnostics.filter { it.severity == ParserDiagnosticSeverity.ERROR }
        fun locationsAlign(left: SourceSpan, right: SourceSpan): Boolean {
            if (left.file != right.file) return false
            if (left.startOffset == left.endOffset) {
                return left.startOffset in right.startOffset..right.endOffset
            }
            if (right.startOffset == right.endOffset) {
                return right.startOffset in left.startOffset..left.endOffset
            }
            return left.startOffset < right.endOffset && right.startOffset < left.endOffset
        }
        val errorsAlign = when {
            authoritativeErrors.isEmpty() && shadowErrors.isEmpty() -> true
            authoritativeErrors.isEmpty() || shadowErrors.isEmpty() -> false
            else -> authoritativeErrors.all { left -> shadowErrors.any { right -> locationsAlign(left.span, right.span) } } &&
                shadowErrors.all { right -> authoritativeErrors.any { left -> locationsAlign(left.span, right.span) } }
        }
        val authoritativeRecognized = recognizedConstructs(authoritative)
        val shadowRecognized = recognizedConstructs(shadow)
        return CPlusParserShadowReport(
            authoritative = authoritative,
            shadow = shadow,
            coverageMatches = authoritative.coverage == shadow.coverage,
            diagnosticsMatch = authoritative.diagnostics.map(diagnosticSignature) == shadow.diagnostics.map(diagnosticSignature),
            errorLocationsAlign = errorsAlign,
            recognizedConstructsMatch = authoritativeRecognized == shadowRecognized,
            authoritativeRecognizedConstructs = authoritativeRecognized,
            shadowRecognizedConstructs = shadowRecognized,
            authoritativeOnlyNodeCount = primaryOnly.size,
            shadowOnlyNodeCount = shadowOnly.size,
            authoritativeOnlyNodeSamples = primaryOnly.take(sampleLimit),
            shadowOnlyNodeSamples = shadowOnly.take(sampleLimit)
        )
    }

    private fun fingerprints(root: CPlusSyntaxNode): List<String> = buildList {
        fun visit(node: CPlusSyntaxNode) {
            add("${node.kind}@${node.span.startOffset}:${node.span.endOffset}:${node.fieldName.orEmpty()}:${node.isError}:${node.isMissing}:${node.opaque}")
            node.children.forEach(::visit)
        }
        visit(root)
    }

    private fun recognizedConstructs(result: CPlusParseResult): List<String> = buildList {
        fun visit(node: CPlusSyntaxNode) {
            val kind = if (result.backend == ParserBackendId.LEGACY) {
                node.kind.takeIf { it in LEGACY_RECOGNIZED }
            } else {
                when (node.kind) {
                    "cplus_test_declaration" -> "test_declaration"
                    "cplus_at_import" -> "c_import_expression"
                    "cplus_comptime_block" -> "comptime_block"
                    "cplus_comptime_declaration" -> when (node.children.firstOrNull()?.kind) {
                        "cplus_comptime_value" -> "comptime_value_declaration"
                        "cplus_comptime_flags" -> "comptime_flags"
                        "cplus_comptime_import" -> "comptime_import"
                        "cplus_comptime_invocation" -> "comptime_invocation"
                        "cplus_legacy_type_generator" -> "comptime_type_declaration"
                        "cplus_legacy_function_generator" -> "comptime_function_declaration"
                        // The legacy parser models `comptime type @name(...)` as a function
                        // declaration with resultKind=type; retain that normalized category.
                        "cplus_comptime_function_definition" -> "comptime_function_declaration"
                        else -> null
                    }
                    "cplus_comptime_type_definition" -> "comptime_invocation"
                    "expression_statement" -> node.takeIf {
                        it.children.count { child -> child.named } == 1 &&
                            it.children.single { child -> child.named }.let { expression ->
                                expression.kind == "cplus_interpolated_identifier" &&
                                    expression.containsKind("cplus_at_call_expression")
                            }
                    }?.let { "comptime_invocation" }
                    else -> null
                }
            }
            kind?.takeIf { it in LEGACY_RECOGNIZED }?.let {
                var endOffset = node.span.endOffset
                if (result.backend == ParserBackendId.LEGACY) {
                    while (endOffset > node.span.startOffset && result.source.text[endOffset - 1].isWhitespace()) {
                        endOffset--
                    }
                }
                add("$it@${node.span.startOffset}:$endOffset")
            }
            // The legacy frontend treats a comptime block/function body as one expansion unit;
            // declarations inside it are not independently active top-level constructs here.
            if (node.kind !in setOf("cplus_comptime_declaration", "cplus_comptime_block") &&
                !(node.kind == "expression_statement" && kind == "comptime_invocation")
            ) {
                node.children.forEach(::visit)
            }
        }
        result.root.children.forEach(::visit)
    }.sorted()

    private companion object {
        val LEGACY_RECOGNIZED = setOf(
            "comptime_import", "c_import_expression", "comptime_flags", "comptime_block",
            "test_declaration", "comptime_value_declaration", "comptime_function_declaration",
            "comptime_type_declaration", "comptime_struct_declaration", "comptime_invocation",
            "comptime_reference"
        )
    }
}

private fun CPlusSyntaxNode.containsKind(expected: String): Boolean =
    kind == expected || children.any { it.containsKind(expected) }

/**
 * Adapter documenting what the legacy scanner actually recognizes. It emits nodes only for
 * comptime constructs; C declarations and runtime statements deliberately remain opaque.
 */
class LegacyCPlusParserBackend : CPlusParserBackend {
    override val id: ParserBackendId = ParserBackendId.LEGACY

    @Suppress("UNUSED_PARAMETER")
    override fun parse(source: SourceSnapshot, options: CPlusParseOptions): CPlusParseResult {
        val scan = parseLegacyComptimeSyntax(source)
        val children = mutableListOf<CPlusSyntaxNode>()
        var cursor = 0
        for (node in scan.nodes.sortedBy { it.span.startOffset }) {
            if (cursor < node.span.startOffset) {
                children += CPlusSyntaxNode(
                    "legacy_text_region",
                    source.sourceFile.span(cursor, node.span.startOffset),
                    opaque = true
                )
            }
            children += node
            cursor = node.span.endOffset
        }
        if (cursor < source.text.length || children.isEmpty()) {
            children += CPlusSyntaxNode(
                "legacy_text_region",
                source.sourceFile.span(cursor, source.text.length),
                opaque = true
            )
        }
        val root = CPlusSyntaxNode("translation_unit", source.sourceFile.span(0, source.text.length), children)
        return CPlusParseResult(
            source = source,
            backend = id,
            coverage = if (scan.nodes.isEmpty()) ParseCoverage.OPAQUE else ParseCoverage.PARTIAL,
            root = root,
            diagnostics = scan.diagnostics,
            limitations = listOf("legacy backend only parses recognized comptime constructs; ordinary C/C-plus syntax is opaque")
        )
    }
}
