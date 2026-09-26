package cplus.parser

import cplus.CPlusAstAdapter
import cplus.CPlusAstLoweringPipeline
import cplus.CPlusAstLoweringStep
import cplus.CPlusAstCEmitter
import cplus.CPlusComptimeIndexer
import cplus.CPlusImportPaths
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
    val compilerOptions: List<String> = emptyList(),
    val sourceOrder: List<SourceId> = emptyList(),
    val sourceImports: List<cplus.SourceImportEdge> = emptyList()
) {
    val successful: Boolean
        get() = cSource != null && parserDiagnostics.isEmpty() && loweringDiagnostics.isEmpty() && unsupportedNodes.isEmpty()
}

data class TreeSitterUnsupportedConstruct(val syntaxKind: String, val span: cplus.SourceSpan)

/**
 * Experimental phase-7 vertical slice. The production CPlusTranspiler remains authoritative.
 * This pipeline lowers ordinary C plus struct methods, explicit receiver calls, simple defer, and
 * extracts/removes @throws declarations and @test fixtures, and lowers statement-oriented
 * @try/@catch plus AST-lowered C imports and C-plus module import graphs.
 */
class TreeSitterCPlusPrototypeTranspiler(
    private val backend: CPlusParserBackend = TreeSitterCPlusParserBackend(),
    private val sourceManager: SourceManager = SourceManager(),
    private val targetOs: String = cplus.CPlusTarget.hostOs(),
    private val importPaths: CPlusImportPaths = CPlusImportPaths()
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
        var mapped = MappedText.identity(source.sourceFile)
        var ast = CPlusAstAdapter().adapt(parsed)
        var testFixtures: List<cplus.CPlusExtractedTestFixture> = emptyList()
        var allocationAnalysis = cplus.AllocationAnalysisResult()
        var sourceOrder: List<SourceId> = emptyList()
        var sourceImports: List<cplus.SourceImportEdge> = emptyList()
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
                    testFixtures = testFixtures
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
                    testFixtures = testFixtures
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
                testFixtures = testFixtures
            )
        }

        val imports = TreeSitterComptimeImportLowering(backend, sourceManager, importPaths, targetOs)
            .lower(parsed, mapped)
        if (imports.parserDiagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, imports.parserDiagnostics, emptyList(), emptyList())
        }
        if (imports.loweringDiagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, emptyList(), imports.loweringDiagnostics, emptyList())
        }
        mapped = imports.source ?: error("successful import expansion must produce mapped source")
        sourceOrder = imports.sourceOrder
        sourceImports = imports.sourceImports
        snapshot = snapshotFor(mapped.text)
        parsed = backend.parse(snapshot)
        if (parsed.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, parsed.diagnostics.map { it.withMappedSpan(mapped, it.span) }, emptyList(), emptyList())
        }

        ast = CPlusAstAdapter().adapt(parsed)
        val cImports = TreeSitterCImportLowering(importPaths).lower(ast, mapped)
        if (cImports.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, emptyList(), cImports.diagnostics, emptyList())
        }
        if (cImports.source.text != mapped.text) {
            mapped = cImports.source
            snapshot = snapshotFor(mapped.text)
            parsed = backend.parse(snapshot)
            if (parsed.diagnostics.isNotEmpty()) {
                return TreeSitterPrototypeResult(null, parsed.diagnostics.map { it.withMappedSpan(mapped, it.span) }, emptyList(), emptyList())
            }
            ast = CPlusAstAdapter().adapt(parsed)
        }

        val comptimeResolution = cplus.CPlusComptimeResolver().resolve(CPlusComptimeIndexer().index(ast))
        if (comptimeResolution.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(
                null,
                emptyList(),
                comptimeResolution.diagnostics.map { diagnostic ->
                    CPlusLoweringDiagnostic(
                        diagnostic.code,
                        diagnostic.message,
                        mapped.toOriginalSpan(diagnostic.span)
                    )
                },
                emptyList()
            )
        }

        allocationAnalysis = TreeSitterAllocationIntentAnalyzer().analyze(ast)
        val extractedTests = CPlusTestExtractionPass().extract(ast, mapped)
        if (extractedTests.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, emptyList(), extractedTests.diagnostics, emptyList())
        }
        testFixtures = extractedTests.fixtures
        mapped = extractedTests.source
        snapshot = snapshotFor(mapped.text)
        parsed = backend.parse(snapshot)
        if (parsed.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, parsed.diagnostics.map { it.withMappedSpan(mapped, it.span) }, emptyList(), emptyList())
        }

        val scalarMaterialized = TreeSitterComptimeScalarLowering().lower(parsed, mapped)
        if (scalarMaterialized.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(
                null,
                emptyList(),
                scalarMaterialized.diagnostics.map { it.withMappedSpan(mapped, it.span) },
                emptyList(),
                testFixtures = testFixtures
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
                    testFixtures = testFixtures
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
                testFixtures = testFixtures
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
                    testFixtures = testFixtures
                )
            }
        }
        ast = CPlusAstAdapter().adapt(parsed)
        val unsupported = unsupportedConstructs(parsed.root).map { it.copy(span = mapped.toOriginalSpan(it.span)) }
        if (unsupported.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, emptyList(), emptyList(), unsupported, testFixtures = testFixtures)
        }
        var throwingFunctions: Map<String, CPlusThrowingFunction> = emptyMap()
        val runtimeLowering = CPlusAstLoweringPipeline().run(
            ast,
            mapped,
            listOf(
                CPlusAstLoweringStep("extract-throws") { stageAst, stageSource ->
                    val result = CPlusThrowsLoweringPass().lower(stageAst, stageSource)
                    throwingFunctions = result.functions
                    cplus.CPlusLoweringResult(
                        result.source,
                        result.diagnostics.map { it.withMappedSpan(stageSource, it.span) }
                    )
                },
                CPlusAstLoweringStep("lower-try-catch") { stageAst, stageSource ->
                    val result = CPlusTryCatchLoweringPass().lower(stageAst, stageSource, throwingFunctions)
                    cplus.CPlusLoweringResult(
                        result.source,
                        result.diagnostics.map { it.withMappedSpan(stageSource, it.span) }
                    )
                },
                CPlusAstLoweringStep("lower-defer") { stageAst, stageSource ->
                    val result = CPlusDeferLoweringPass().lower(stageAst, stageSource)
                    cplus.CPlusLoweringResult(
                        result.source,
                        result.diagnostics.map { it.withMappedSpan(stageSource, it.span) }
                    )
                },
                CPlusAstLoweringStep("validate-semantics") { stageAst, stageSource ->
                    val semantics = CPlusSemanticAnalyzer().analyze(stageAst)
                    cplus.CPlusLoweringResult(
                        stageSource,
                        semantics.diagnostics.map { diagnostic ->
                            CPlusLoweringDiagnostic(
                                diagnostic.code,
                                diagnostic.message,
                                stageSource.toOriginalSpan(diagnostic.span)
                            )
                        }
                    )
                },
                CPlusAstLoweringStep("lower-method-calls") { stageAst, stageSource ->
                    val semantics = CPlusSemanticAnalyzer().analyze(stageAst)
                    val result = CPlusMethodCallLoweringPass().lower(stageAst, stageSource, semantics)
                    cplus.CPlusLoweringResult(
                        result.source,
                        result.diagnostics.map { it.withMappedSpan(stageSource, it.span) }
                    )
                },
                CPlusAstLoweringStep("lower-struct-methods") { stageAst, stageSource ->
                    val result = CPlusStructMethodLoweringPass().lower(stageAst, stageSource)
                    cplus.CPlusLoweringResult(
                        result.source,
                        result.diagnostics.map { it.withMappedSpan(stageSource, it.span) }
                    )
                }
            )
        ) { stageSource ->
            backend.parse(snapshotFor(stageSource.text))
        }
        if (runtimeLowering.parserDiagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(
                null,
                runtimeLowering.parserDiagnostics,
                emptyList(),
                emptyList(),
                throwingFunctions,
                testFixtures,
                allocationAnalysis = allocationAnalysis
            )
        }
        if (runtimeLowering.loweringDiagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(
                null,
                emptyList(),
                runtimeLowering.loweringDiagnostics,
                emptyList(),
                throwingFunctions,
                testFixtures,
                allocationAnalysis = allocationAnalysis
            )
        }
        mapped = runtimeLowering.source
        ast = runtimeLowering.ast

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
                throwingFunctions,
                testFixtures
            )
        }
        val cEmission = CPlusAstCEmitter().emit(CPlusAstAdapter().adapt(parsed), emittedSource)
        if (cEmission.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(
                null,
                emptyList(),
                cEmission.diagnostics.map { it.withMappedSpan(emittedSource, it.span) },
                emptyList(),
                throwingFunctions,
                testFixtures
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
                throwingFunctions,
                testFixtures
            )
        }
        val transcoded = MappedEmitter(source.sourceFile)
            .emit(generatedC, "", allocationAnalysis, compilerOptions)
            .copy(sourceOrder = sourceOrder, sourceImports = sourceImports)
        return TreeSitterPrototypeResult(
            generatedC,
            emptyList(),
            emptyList(),
            emptyList(),
            throwingFunctions,
            testFixtures,
            transcoded,
            allocationAnalysis,
            compilerOptions,
            sourceOrder,
            sourceImports
        )
    }

    private fun unsupportedConstructs(root: CPlusSyntaxNode): List<TreeSitterUnsupportedConstruct> {
        val unsupportedKinds = setOf(
            "cplus_comptime_declaration", "cplus_comptime_block", "cplus_comptime_function_definition",
            "cplus_comptime_invocation", "cplus_comptime_value", "cplus_comptime_import", "cplus_comptime_flags",
            "cplus_comptime_expression", "cplus_comptime_conditional", "cplus_code_fragment", "cplus_at_call_expression"
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

    private companion object {
        const val MAX_COMPTIME_CONDITIONAL_PASSES = 64
    }
}
