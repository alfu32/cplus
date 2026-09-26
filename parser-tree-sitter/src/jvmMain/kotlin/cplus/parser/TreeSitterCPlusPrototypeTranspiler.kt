package cplus.parser

import cplus.CPlusAstAdapter
import cplus.CPlusAstCEmitter
import cplus.CPlusDeferLoweringPass
import cplus.CPlusLoweringDiagnostic
import cplus.CPlusMethodCallLoweringPass
import cplus.CPlusParserBackend
import cplus.CPlusSemanticAnalyzer
import cplus.CPlusStructMethodLoweringPass
import cplus.CPlusThrowingFunction
import cplus.CPlusThrowsLoweringPass
import cplus.CPlusTestExtractionPass
import cplus.CPlusTryCatchLoweringPass
import cplus.CPlusSyntaxNode
import cplus.MappedText
import cplus.MappedTextBuilder
import cplus.MappedEmitter
import cplus.ParserDiagnostic
import cplus.SourceId
import cplus.SourceSpan
import cplus.SourceManager
import cplus.SourceSnapshot
import cplus.TranscodedSource

data class TreeSitterPrototypeResult(
    val cSource: MappedText?,
    val parserDiagnostics: List<ParserDiagnostic>,
    val loweringDiagnostics: List<CPlusLoweringDiagnostic>,
    val unsupportedNodes: List<TreeSitterUnsupportedConstruct>,
    val throwsFunctions: Map<String, CPlusThrowingFunction> = emptyMap(),
    val testFixtures: List<cplus.CPlusExtractedTestFixture> = emptyList(),
    /** Compiler-facing mapped emission for consumers that need the established source-map facade. */
    val transcodedSource: TranscodedSource? = null,
    val allocationAnalysis: cplus.AllocationAnalysisResult = cplus.AllocationAnalysisResult(),
    val compilerOptions: List<String> = emptyList()
) {
    val successful: Boolean
        get() = cSource != null && parserDiagnostics.isEmpty() && loweringDiagnostics.isEmpty() && unsupportedNodes.isEmpty()
}

data class TreeSitterUnsupportedConstruct(val syntaxKind: String, val span: cplus.SourceSpan)

/**
 * Experimental phase-7 vertical slice. The production CPlusTranspiler remains authoritative.
 * This pipeline lowers ordinary C plus struct methods, explicit receiver calls, simple defer, and
 * extracts/removes @throws declarations and @test fixtures, and lowers statement-oriented
 * @try/@catch. Comptime and C-plus imports still fail closed.
 */
class TreeSitterCPlusPrototypeTranspiler(
    private val backend: CPlusParserBackend = TreeSitterCPlusParserBackend(),
    private val sourceManager: SourceManager = SourceManager(),
    private val targetOs: String = cplus.CPlusTarget.hostOs()
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
        val allocationAnalysis = TreeSitterAllocationIntentAnalyzer().analyze(ast)
        var mapped = MappedText.identity(source.sourceFile)
        val extractedTests = CPlusTestExtractionPass().extract(ast, mapped)
        if (extractedTests.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, emptyList(), extractedTests.diagnostics, emptyList())
        }
        mapped = extractedTests.source
        snapshot = snapshotFor(mapped.text)
        parsed = backend.parse(snapshot)
        if (parsed.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, parsed.diagnostics.map { it.withMappedSpan(mapped, it.span) }, emptyList(), emptyList())
        }
        val conditionalPass = TreeSitterComptimeConditionalLowering()
        var conditionalPassCount = 0
        while (conditionalPassCount < MAX_COMPTIME_CONDITIONAL_PASSES) {
            val materialized = conditionalPass.lower(parsed, mapped, targetOs)
            if (materialized.diagnostics.isNotEmpty()) {
                return TreeSitterPrototypeResult(
                    null,
                    emptyList(),
                    materialized.diagnostics.map { it.withMappedSpan(mapped, it.span) },
                    emptyList(),
                    testFixtures = extractedTests.fixtures
                )
            }
            if (materialized.source.text == mapped.text) break
            conditionalPassCount++
            mapped = materialized.source
            snapshot = snapshotFor(mapped.text)
            parsed = backend.parse(snapshot)
            if (parsed.diagnostics.isNotEmpty()) {
                return TreeSitterPrototypeResult(
                    null,
                    parsed.diagnostics.map { it.withMappedSpan(mapped, it.span) },
                    emptyList(),
                    emptyList(),
                    testFixtures = extractedTests.fixtures
                )
            }
        }
        val remainingConditional = descendants(parsed.root).firstOrNull { it.kind == "cplus_comptime_conditional" }
        if (remainingConditional != null) {
            return TreeSitterPrototypeResult(
                null,
                emptyList(),
                listOf(CPlusLoweringDiagnostic(
                    "CPLUS_COMPTIME_CONDITIONAL_LIMIT",
                    "nested comptime conditionals exceeded the materialization pass limit",
                    mapped.toOriginalSpan(remainingConditional.span)
                )),
                emptyList(),
                testFixtures = extractedTests.fixtures
            )
        }
        val scalarMaterialized = TreeSitterComptimeScalarLowering().lower(parsed, mapped)
        if (scalarMaterialized.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(
                null,
                emptyList(),
                scalarMaterialized.diagnostics.map { it.withMappedSpan(mapped, it.span) },
                emptyList(),
                testFixtures = extractedTests.fixtures
            )
        }
        if (scalarMaterialized.source.text != mapped.text) {
            mapped = scalarMaterialized.source
            snapshot = snapshotFor(mapped.text)
            parsed = backend.parse(snapshot)
            if (parsed.diagnostics.isNotEmpty()) {
                return TreeSitterPrototypeResult(
                    null,
                    parsed.diagnostics.map { it.withMappedSpan(mapped, it.span) },
                    emptyList(),
                    emptyList(),
                    testFixtures = extractedTests.fixtures
                )
            }
        }
        val flagsMaterialized = TreeSitterComptimeFlagsLowering().lower(parsed, mapped)
        if (flagsMaterialized.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(
                null,
                emptyList(),
                flagsMaterialized.diagnostics.map { it.withMappedSpan(mapped, it.span) },
                emptyList(),
                testFixtures = extractedTests.fixtures
            )
        }
        val compilerOptions = flagsMaterialized.compilerOptions
        if (flagsMaterialized.source.text != mapped.text) {
            mapped = flagsMaterialized.source
            snapshot = snapshotFor(mapped.text)
            parsed = backend.parse(snapshot)
            if (parsed.diagnostics.isNotEmpty()) {
                return TreeSitterPrototypeResult(
                    null,
                    parsed.diagnostics.map { it.withMappedSpan(mapped, it.span) },
                    emptyList(),
                    emptyList(),
                    testFixtures = extractedTests.fixtures
                )
            }
        }
        ast = CPlusAstAdapter().adapt(parsed)
        val unsupported = unsupportedConstructs(parsed.root).map { it.copy(span = mapped.toOriginalSpan(it.span)) }
        if (unsupported.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, emptyList(), emptyList(), unsupported, testFixtures = extractedTests.fixtures)
        }
        val throws = CPlusThrowsLoweringPass().lower(ast, mapped)
        if (throws.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, emptyList(), throws.diagnostics, emptyList())
        }
        mapped = throws.source

        snapshot = snapshotFor(mapped.text)
        parsed = backend.parse(snapshot)
        if (parsed.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, parsed.diagnostics.map { it.withMappedSpan(mapped, it.span) }, emptyList(), emptyList())
        }
        ast = CPlusAstAdapter().adapt(parsed)

        val tryCatch = CPlusTryCatchLoweringPass().lower(ast, mapped, throws.functions)
        if (tryCatch.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, emptyList(), tryCatch.diagnostics.map { it.withMappedSpan(mapped, it.span) }, emptyList(), throws.functions)
        }
        mapped = tryCatch.source

        snapshot = snapshotFor(mapped.text)
        parsed = backend.parse(snapshot)
        if (parsed.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, parsed.diagnostics.map { it.withMappedSpan(mapped, it.span) }, emptyList(), emptyList(), throws.functions)
        }
        ast = CPlusAstAdapter().adapt(parsed)

        val deferred = CPlusDeferLoweringPass().lower(ast, mapped)
        if (deferred.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, emptyList(), deferred.diagnostics.map { it.withMappedSpan(mapped, it.span) }, emptyList(), throws.functions)
        }
        mapped = deferred.source

        snapshot = snapshotFor(mapped.text)
        parsed = backend.parse(snapshot)
        if (parsed.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, parsed.diagnostics.map { it.withMappedSpan(mapped, it.span) }, emptyList(), emptyList())
        }
        ast = CPlusAstAdapter().adapt(parsed)
        val semantics = CPlusSemanticAnalyzer().analyze(ast)
        if (semantics.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(
                null,
                emptyList(),
                semantics.diagnostics.map { diagnostic ->
                    CPlusLoweringDiagnostic(
                        diagnostic.code,
                        diagnostic.message,
                        mapped.toOriginalSpan(diagnostic.span)
                    )
                },
                emptyList(),
                throws.functions,
                extractedTests.fixtures,
                allocationAnalysis = allocationAnalysis
            )
        }
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
        val emittedSource = output.build()
        snapshot = snapshotFor(emittedSource.text)
        parsed = backend.parse(snapshot)
        if (parsed.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(
                null,
                parsed.diagnostics.map { it.withMappedSpan(emittedSource, it.span) },
                emptyList(),
                emptyList(),
                throws.functions,
                extractedTests.fixtures
            )
        }
        val cEmission = CPlusAstCEmitter().emit(CPlusAstAdapter().adapt(parsed), emittedSource)
        if (cEmission.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(
                null,
                emptyList(),
                cEmission.diagnostics.map { it.withMappedSpan(emittedSource, it.span) },
                emptyList(),
                throws.functions,
                extractedTests.fixtures
            )
        }
        val generatedC = cEmission.source ?: error("successful AST C emission must contain output")
        val emittedParse = backend.parse(snapshotFor(generatedC.text))
        if (emittedParse.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(
                null,
                emittedParse.diagnostics.map { it.withMappedSpan(generatedC, it.span) },
                emptyList(),
                emptyList(),
                throws.functions,
                extractedTests.fixtures
            )
        }
        return TreeSitterPrototypeResult(
            generatedC,
            emptyList(),
            emptyList(),
            emptyList(),
            throws.functions,
            extractedTests.fixtures,
            MappedEmitter(source.sourceFile).emit(generatedC, "", allocationAnalysis),
            allocationAnalysis,
            compilerOptions
        )
    }

    private fun unsupportedConstructs(root: CPlusSyntaxNode): List<TreeSitterUnsupportedConstruct> {
        val unsupportedKinds = setOf(
            "cplus_comptime_declaration", "cplus_comptime_block", "cplus_comptime_function_definition",
            "cplus_comptime_invocation", "cplus_comptime_value", "cplus_comptime_import", "cplus_comptime_flags",
            "cplus_comptime_expression", "cplus_comptime_conditional", "cplus_code_fragment", "cplus_at_call_expression",
            "cplus_at_import"
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

    private companion object {
        const val MAX_COMPTIME_CONDITIONAL_PASSES = 64
    }
}
