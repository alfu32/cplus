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
        return CPlusParserShadowReport(
            authoritative = authoritative,
            shadow = shadow,
            coverageMatches = authoritative.coverage == shadow.coverage,
            diagnosticsMatch = authoritative.diagnostics.map(diagnosticSignature) == shadow.diagnostics.map(diagnosticSignature),
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
}

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
