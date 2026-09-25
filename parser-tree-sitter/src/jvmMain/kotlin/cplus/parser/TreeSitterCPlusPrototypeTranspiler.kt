package cplus.parser

import cplus.CPlusAstAdapter
import cplus.CPlusDeferLoweringPass
import cplus.CPlusLoweringDiagnostic
import cplus.CPlusMethodCallLoweringPass
import cplus.CPlusParserBackend
import cplus.CPlusSemanticAnalyzer
import cplus.CPlusStructMethodLoweringPass
import cplus.CPlusSyntaxNode
import cplus.MappedText
import cplus.MappedTextBuilder
import cplus.ParserDiagnostic
import cplus.SourceId
import cplus.SourceSpan
import cplus.SourceManager
import cplus.SourceSnapshot

data class TreeSitterPrototypeResult(
    val cSource: MappedText?,
    val parserDiagnostics: List<ParserDiagnostic>,
    val loweringDiagnostics: List<CPlusLoweringDiagnostic>,
    val unsupportedNodes: List<TreeSitterUnsupportedConstruct>
) {
    val successful: Boolean
        get() = cSource != null && parserDiagnostics.isEmpty() && loweringDiagnostics.isEmpty() && unsupportedNodes.isEmpty()
}

data class TreeSitterUnsupportedConstruct(val syntaxKind: String, val span: cplus.SourceSpan)

/**
 * Experimental phase-7 vertical slice. The production CPlusTranspiler remains authoritative.
 * This pipeline lowers ordinary C plus struct methods, explicit receiver calls, and simple defer.
 * Comptime, tests, and try/catch intentionally fail closed until their AST passes are ready.
 */
class TreeSitterCPlusPrototypeTranspiler(
    private val backend: CPlusParserBackend = TreeSitterCPlusParserBackend(),
    private val sourceManager: SourceManager = SourceManager()
) {
    fun transpile(source: SourceSnapshot): TreeSitterPrototypeResult {
        var revision = 0
        fun snapshotFor(text: String): SourceSnapshot = sourceManager.open(
            SourceId.named("${source.id.value}#tree-sitter-${revision++}"),
            text
        )

        var snapshot = source
        var parsed = backend.parse(snapshot)
        if (parsed.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, parsed.diagnostics.map { it.withMappedSpan(mappedIdentity(source), it.span) }, emptyList(), emptyList())
        }
        var ast = CPlusAstAdapter().adapt(parsed)
        val unsupported = unsupportedConstructs(parsed.root)
        if (unsupported.isNotEmpty()) return TreeSitterPrototypeResult(null, emptyList(), emptyList(), unsupported)

        var mapped = MappedText.identity(source.sourceFile)
        val deferred = CPlusDeferLoweringPass().lower(ast, mapped)
        if (deferred.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, emptyList(), deferred.diagnostics.map { it.withMappedSpan(mapped, it.span) }, emptyList())
        }
        mapped = deferred.source

        snapshot = snapshotFor(mapped.text)
        parsed = backend.parse(snapshot)
        if (parsed.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, parsed.diagnostics.map { it.withMappedSpan(mapped, it.span) }, emptyList(), emptyList())
        }
        ast = CPlusAstAdapter().adapt(parsed)
        val semantics = CPlusSemanticAnalyzer().analyze(ast)
        val calls = CPlusMethodCallLoweringPass().lower(ast, mapped, semantics)
        if (calls.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, emptyList(), calls.diagnostics.map { it.withMappedSpan(mapped, it.span) }, emptyList())
        }
        mapped = calls.source

        snapshot = snapshotFor(mapped.text)
        parsed = backend.parse(snapshot)
        if (parsed.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, parsed.diagnostics.map { it.withMappedSpan(mapped, it.span) }, emptyList(), emptyList())
        }
        ast = CPlusAstAdapter().adapt(parsed)
        val methods = CPlusStructMethodLoweringPass().lower(ast, mapped)
        if (methods.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, emptyList(), methods.diagnostics.map { it.withMappedSpan(mapped, it.span) }, emptyList())
        }
        mapped = methods.source

        val preamble = """
            #ifndef CPLUS_ANNOTATIONS_DEFINED
            #define CPLUS_ANNOTATIONS_DEFINED
            #define pub
            #define priv
            #define mut
            #define borrowed
            #define owned
            #define stat
            #define scratch
            #define hot
            #define warm
            #define cold
            #endif

        """.trimIndent() + "\n"
        val output = MappedTextBuilder()
        output.appendGenerated(preamble, cplus.SourceOrigin(source.sourceFile, 0))
        output.append(mapped)
        return TreeSitterPrototypeResult(output.build(), emptyList(), emptyList(), emptyList())
    }

    private fun unsupportedConstructs(root: CPlusSyntaxNode): List<TreeSitterUnsupportedConstruct> {
        val unsupportedKinds = setOf(
            "cplus_comptime_declaration", "cplus_comptime_block", "cplus_comptime_function_definition",
            "cplus_comptime_invocation", "cplus_comptime_value", "cplus_comptime_import", "cplus_comptime_flags",
            "cplus_comptime_expression", "cplus_code_fragment", "cplus_at_call_expression",
            "cplus_test_declaration", "cplus_throws_annotation", "cplus_throws_annotated_method",
            "cplus_try_statement", "cplus_catch_clause", "cplus_at_import"
        )
        val nodes = sequenceOf(root) + root.children.asSequence().flatMap { descendants(it) }
        return nodes.filter { it.kind in unsupportedKinds }
            .map { TreeSitterUnsupportedConstruct(it.kind, it.span) }
            .distinctBy { it.syntaxKind to it.span.startOffset }
            .toList()
    }

    private fun descendants(node: CPlusSyntaxNode): Sequence<CPlusSyntaxNode> =
        sequenceOf(node) + node.children.asSequence().flatMap { descendants(it) }

    private fun mappedIdentity(source: SourceSnapshot): MappedText = MappedText.identity(source.sourceFile)

    private fun ParserDiagnostic.withMappedSpan(mapped: MappedText, span: SourceSpan): ParserDiagnostic =
        copy(span = mapped.toOriginalSpan(span))

    private fun CPlusLoweringDiagnostic.withMappedSpan(mapped: MappedText, span: SourceSpan): CPlusLoweringDiagnostic =
        copy(span = mapped.toOriginalSpan(span))

    private fun MappedText.toOriginalSpan(span: SourceSpan): SourceSpan {
        val start = originAt(span.startOffset)
            ?: (span.startOffset - 1).takeIf { it >= 0 }?.let(::originAt)
            ?: return span
        if (span.endOffset <= span.startOffset) return start.file.span(start.offset, start.offset)
        val lastIndex = (span.endOffset - 1).coerceAtLeast(span.startOffset)
        val end = originAt(lastIndex)
        val mappedEnd = if (end?.file === start.file && end.offset >= start.offset) end.offset + 1 else start.offset + 1
        return start.file.span(start.offset, mappedEnd)
    }
}
