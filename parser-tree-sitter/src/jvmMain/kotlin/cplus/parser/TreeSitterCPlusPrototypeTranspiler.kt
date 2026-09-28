package cplus.parser

import cplus.CPlusAstAdapter
import cplus.CPlusAstLoweringPipeline
import cplus.CPlusAstLoweringStep
import cplus.CPlusAstCEmitter
import cplus.CPlusComptimeIndexer
import cplus.CPlusImportPaths
import cplus.CPlusFrontendPassIds
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
import cplus.rewriteCPlusAnnotationMacros

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
    val sourceImports: List<cplus.SourceImportEdge> = emptyList(),
    /** Runtime AST passes actually executed, in order, for migration reports. */
    val runtimePasses: List<String> = emptyList(),
    /** Runtime AST passes that changed mapped source; semantic-only passes are absent. */
    val runtimePassesChanged: Set<String> = emptySet()
) {
    val successful: Boolean
        get() = cSource != null && parserDiagnostics.isEmpty() && loweringDiagnostics.isEmpty() && unsupportedNodes.isEmpty()
}

data class TreeSitterUnsupportedConstruct(val syntaxKind: String, val span: cplus.SourceSpan)

/**
 * Syntax nodes that belong to the comptime frontend boundary.  Keeping this inventory at
 * module scope lets both the prototype and migration reports apply the same rollback contract.
 */
internal val TREE_SITTER_COMPTIME_ROLLBACK_NODES = setOf(
    "cplus_comptime_declaration", "cplus_comptime_block", "cplus_comptime_function_definition",
    "cplus_comptime_invocation", "cplus_comptime_value", "cplus_comptime_import", "cplus_comptime_flags",
    "cplus_comptime_expression", "cplus_comptime_conditional", "cplus_code_fragment", "cplus_at_call_expression",
    "cplus_legacy_type_generator", "cplus_legacy_function_generator", "cplus_legacy_comptime_invocation"
)


/**
 * Per-pass migration switch for the AST runtime pipeline. Disabled passes are not silently
 * emulated: their C-plus nodes reach the final unsupported-node check, which gives callers a
 * deterministic rollback/negative-test result instead of partially applying semantics.
 */
data class TreeSitterPassSelection(
    val disabledRuntimePasses: Set<String> = emptySet(),
    /** Migration-only test-mode switch; runtime lowering remains independently selectable. */
    val extractTests: Boolean = true,
    /** Migration-only rollback switch for the AST comptime/materialization frontend. */
    val resolveComptime: Boolean = true
) {
    init {
        require(disabledRuntimePasses.all { it in KNOWN_RUNTIME_PASSES }) {
            "unknown Tree-sitter runtime pass in selection: ${disabledRuntimePasses - KNOWN_RUNTIME_PASSES}"
        }
    }

    fun enabled(passId: String): Boolean = passId !in disabledRuntimePasses

    private companion object {
        val KNOWN_RUNTIME_PASSES = CPlusFrontendPassIds.ALL
    }
}

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
    private val importPaths: CPlusImportPaths = CPlusImportPaths(),
    private val targetArch: String = cplus.CPlusTarget.hostArch(),
    private val passSelection: TreeSitterPassSelection = TreeSitterPassSelection()
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
        var compilerOptionOrder: List<SourceId> = emptyList()
        var sourceImports: List<cplus.SourceImportEdge> = emptyList()
        if (!passSelection.resolveComptime) {
            val comptimeNode = descendants(parsed.root).firstOrNull { it.kind in TREE_SITTER_COMPTIME_ROLLBACK_NODES }
            if (comptimeNode != null) {
                return TreeSitterPrototypeResult(
                    null,
                    emptyList(),
                    emptyList(),
                    listOf(TreeSitterUnsupportedConstruct(comptimeNode.kind, comptimeNode.span)),
                    testFixtures = testFixtures
                )
            }
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
        compilerOptionOrder = imports.compilerOptionOrder
        sourceImports = imports.sourceImports
        snapshot = snapshotFor(mapped.text)
        parsed = backend.parse(snapshot)
        if (parsed.diagnostics.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, parsed.diagnostics.map { it.withMappedSpan(mapped, it.span) }, emptyList(), emptyList())
        }

        // Expand imports first so reflected metadata can refer to declarations from imported modules.
        val comptimeBlockPass = TreeSitterComptimeBlockLowering()
        val reflectedFieldPass = TreeSitterComptimeFieldLoopLowering(targetOs, targetArch)
        var comptimeBlockPassCount = 0
        while (comptimeBlockPassCount < MAX_COMPTIME_CONDITIONAL_PASSES) {
            val reflectedFields = reflectedFieldPass.lower(parsed, mapped)
            if (reflectedFields.diagnostics.isNotEmpty()) {
                return TreeSitterPrototypeResult(
                    null,
                    emptyList(),
                    reflectedFields.diagnostics.map { it.withMappedSpan(mapped, it.span) },
                    emptyList(),
                    testFixtures = testFixtures
                )
            }
            if (reflectedFields.source.text != mapped.text) {
                comptimeBlockPassCount++
                mapped = reflectedFields.source
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
                continue
            }

            val materialized = comptimeBlockPass.lower(parsed, mapped)
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
            comptimeBlockPassCount++
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
        val activeComptimeBlocks = CPlusComptimeIndexer().index(CPlusAstAdapter().adapt(parsed)).constructs
            .filter { it.syntaxKind == "cplus_comptime_block" && it.activeThisPass }
        val remainingComptimeBlock = activeComptimeBlocks.firstOrNull()?.span?.let { span ->
            descendants(parsed.root).firstOrNull { it.kind == "cplus_comptime_block" && it.span == span }
        }
        if (remainingComptimeBlock != null) {
            return TreeSitterPrototypeResult(
                null,
                emptyList(),
                listOf(CPlusLoweringDiagnostic(
                    "CPLUS_COMPTIME_BLOCK_LIMIT",
                    "nested comptime blocks exceeded the materialization pass limit",
                    mapped.toOriginalSpan(remainingComptimeBlock.span)
                )),
                emptyList(),
                testFixtures = testFixtures
            )
        }

        ast = CPlusAstAdapter().adapt(parsed)

        // Entity/code materialization can reveal new generators and C-plus module imports.
        // Resolve imports after each expansion layer, then reparse before binding declarations
        // from the newly imported dependency on the next iteration.
        var entityPassCount = 0
        val seenEntityStates = mutableSetOf(mapped.text)
        while (entityPassCount < MAX_COMPTIME_CONDITIONAL_PASSES) {
            var changedThisPass = false
            val generatedFieldLoops = reflectedFieldPass.lower(parsed, mapped)
            if (generatedFieldLoops.diagnostics.isNotEmpty()) {
                return TreeSitterPrototypeResult(
                    null,
                    emptyList(),
                    generatedFieldLoops.diagnostics.map { it.withMappedSpan(mapped, it.span) },
                    emptyList(),
                    testFixtures = testFixtures
                )
            }
            if (generatedFieldLoops.source.text != mapped.text) {
                mapped = generatedFieldLoops.source
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
                ast = CPlusAstAdapter().adapt(parsed)
                changedThisPass = true
            }
            val generatedBlocks = comptimeBlockPass.lower(parsed, mapped)
            if (generatedBlocks.diagnostics.isNotEmpty()) {
                return TreeSitterPrototypeResult(
                    null,
                    emptyList(),
                    generatedBlocks.diagnostics.map { it.withMappedSpan(mapped, it.span) },
                    emptyList(),
                    testFixtures = testFixtures
                )
            }
            if (generatedBlocks.source.text != mapped.text) {
                mapped = generatedBlocks.source
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
                ast = CPlusAstAdapter().adapt(parsed)
                changedThisPass = true
            }
            val entities = TreeSitterComptimeEntityLowering(targetOs = targetOs, targetArch = targetArch).lower(parsed, mapped)
            if (entities.diagnostics.isNotEmpty()) {
                return TreeSitterPrototypeResult(
                    null,
                    emptyList(),
                    entities.diagnostics.map { it.withMappedSpan(mapped, it.span) },
                    emptyList(),
                    testFixtures = testFixtures
                )
            }
            if (entities.source.text != mapped.text) {
                if (!seenEntityStates.add(entities.source.text)) {
                    val activeInvocation = descendants(parsed.root).firstOrNull {
                        it.kind in setOf("cplus_comptime_invocation", "cplus_comptime_type_definition", "cplus_legacy_comptime_invocation")
                    }
                    return TreeSitterPrototypeResult(
                        null,
                        emptyList(),
                        listOf(CPlusLoweringDiagnostic(
                            "CPLUS_COMPTIME_ENTITY_CYCLE",
                            "comptime entity expansion repeated a previous source state",
                            activeInvocation?.span?.let(mapped::toOriginalSpan) ?: mapped.toOriginalSpan(parsed.source.sourceFile.span(0, 0))
                        )),
                        emptyList(),
                        testFixtures = testFixtures
                    )
                }
                mapped = entities.source
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
                ast = CPlusAstAdapter().adapt(parsed)
                changedThisPass = true
            }

            if (descendants(parsed.root).any { it.kind in CPLUS_MODULE_IMPORT_NODES }) {
                val expandedImports = TreeSitterComptimeImportLowering(backend, sourceManager, importPaths, targetOs)
                    .lower(parsed, mapped, source.sourceFile, compilerOptionOrder.toSet())
                if (expandedImports.parserDiagnostics.isNotEmpty()) {
                    return TreeSitterPrototypeResult(null, expandedImports.parserDiagnostics, emptyList(), emptyList())
                }
                if (expandedImports.loweringDiagnostics.isNotEmpty()) {
                    return TreeSitterPrototypeResult(null, emptyList(), expandedImports.loweringDiagnostics, emptyList())
                }
                val expandedSource = expandedImports.source ?: error("successful generated import expansion must produce mapped source")
                if (expandedSource.text != mapped.text) {
                    if (!seenEntityStates.add(expandedSource.text)) {
                        val importNode = descendants(parsed.root).firstOrNull { it.kind in CPLUS_MODULE_IMPORT_NODES }
                        return TreeSitterPrototypeResult(
                            null,
                            emptyList(),
                            listOf(CPlusLoweringDiagnostic(
                                "CPLUS_COMPTIME_ENTITY_CYCLE",
                                "comptime import expansion repeated a previous source state",
                                importNode?.span?.let(mapped::toOriginalSpan) ?: mapped.toOriginalSpan(parsed.source.sourceFile.span(0, 0))
                            )),
                            emptyList(),
                            testFixtures = testFixtures
                        )
                    }
                    val newDependencies = expandedImports.sourceOrder.dropLast(1)
                    sourceOrder = (newDependencies + sourceOrder).distinct()
                    val newCompilerOptionDependencies = expandedImports.compilerOptionOrder.dropLast(1)
                    compilerOptionOrder = (newCompilerOptionDependencies + compilerOptionOrder).distinct()
                    sourceImports = (sourceImports + expandedImports.sourceImports).distinct()
                    mapped = expandedSource
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
                    ast = CPlusAstAdapter().adapt(parsed)
                    changedThisPass = true
                }
            }

            if (!changedThisPass) {
                val index = CPlusComptimeIndexer().index(ast)
                val activeInvocation = index.constructs.firstOrNull {
                    it.activeThisPass && it.moduleScope && it.syntaxKind in setOf(
                        "cplus_comptime_invocation", "cplus_comptime_type_definition", "cplus_legacy_comptime_invocation"
                    )
                }
                if (activeInvocation != null && cplus.CPlusComptimeResolver().resolve(index).diagnostics.isEmpty()) {
                    return TreeSitterPrototypeResult(
                        null,
                        emptyList(),
                        listOf(CPlusLoweringDiagnostic(
                            "CPLUS_COMPTIME_ENTITY_NO_PROGRESS",
                            "bound comptime entity invocation '${activeInvocation.symbol}' did not materialize any source",
                            mapped.toOriginalSpan(activeInvocation.span)
                        )),
                        emptyList(),
                        testFixtures = testFixtures
                    )
                }
                break
            }
            entityPassCount++
        }
        val finalEntityIndex = CPlusComptimeIndexer().index(ast)
        val remainingModuleImport = descendants(parsed.root).firstOrNull { it.kind in CPLUS_MODULE_IMPORT_NODES }
        if (remainingModuleImport != null && entityPassCount >= MAX_COMPTIME_CONDITIONAL_PASSES) {
            return TreeSitterPrototypeResult(
                null,
                emptyList(),
                listOf(CPlusLoweringDiagnostic(
                    "CPLUS_COMPTIME_IMPORT_LIMIT",
                    "generated C-plus imports exceeded the materialization pass limit",
                    mapped.toOriginalSpan(remainingModuleImport.span)
                )),
                emptyList(),
                testFixtures = testFixtures
            )
        }
        val remainingEntityInvocation = descendants(parsed.root).firstOrNull {
            it.kind in setOf("cplus_comptime_invocation", "cplus_comptime_type_definition", "cplus_legacy_comptime_invocation") && it.children.firstOrNull { child -> child.fieldName == "generator" }
                ?.let { generator -> mapped.text.substring(generator.span.startOffset, generator.span.endOffset) }
                ?.let { name ->
                    finalEntityIndex.constructs.any { construct ->
                        construct.activeThisPass && construct.moduleScope &&
                            construct.syntaxKind in setOf(
                                "cplus_comptime_function_definition", "cplus_legacy_type_generator", "cplus_legacy_function_generator"
                            ) &&
                            (construct.resultKind in setOf("function", "variable", "type", "code") ||
                                construct.syntaxKind in setOf("cplus_legacy_type_generator", "cplus_legacy_function_generator")) &&
                            construct.symbol == name
                    }
                } == true
        }
        if (remainingEntityInvocation != null && entityPassCount >= MAX_COMPTIME_CONDITIONAL_PASSES) {
            return TreeSitterPrototypeResult(
                null,
                emptyList(),
                listOf(CPlusLoweringDiagnostic(
                    "CPLUS_COMPTIME_ENTITY_LIMIT",
                    "entity generator expansion exceeded the materialization pass limit",
                    mapped.toOriginalSpan(remainingEntityInvocation.span)
                )),
                emptyList(),
                testFixtures = testFixtures
            )
        }

        // Code fragments can introduce C imports. Resolve them only after entity expansion has
        // reached a fixed point, then reparse because the mapped source and AST spans changed.
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

        allocationAnalysis = if (passSelection.enabled(CPlusFrontendPassIds.VALIDATE_SEMANTICS)) {
            TreeSitterAllocationIntentAnalyzer().analyze(ast)
        } else {
            cplus.AllocationAnalysisResult()
        }
        val scalarMaterialized = TreeSitterComptimeScalarLowering(targetOs, targetArch).lower(parsed, mapped)
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
        ast = CPlusAstAdapter().adapt(parsed)
        val flagsMaterialized = TreeSitterComptimeFlagsLowering().lower(parsed, mapped, compilerOptionOrder)
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
        // Extraction-only migration probes need fixture metadata even when runtime lowerers are
        // deliberately disabled and the surrounding source must fail closed at C-plus nodes.
        // Do not replace `mapped` here: the normal pipeline still lowers fixture bodies after
        // runtime passes, while this probe only records the extraction boundary.
        if (passSelection.extractTests &&
            passSelection.disabledRuntimePasses == CPlusFrontendPassIds.ALL
        ) {
            val extractionProbe = CPlusTestExtractionPass().extract(ast, mapped)
            if (extractionProbe.diagnostics.isNotEmpty()) {
                return TreeSitterPrototypeResult(
                    null,
                    emptyList(),
                    extractionProbe.diagnostics,
                    emptyList(),
                    testFixtures = testFixtures
                )
            }
            testFixtures = extractionProbe.fixtures
        }
        val disabledRuntimeKinds = buildSet {
            if (!passSelection.enabled(CPlusFrontendPassIds.EXTRACT_THROWS)) {
                add("cplus_throws_annotation")
                add("cplus_throws_annotated_method")
            }
            if (!passSelection.enabled(CPlusFrontendPassIds.LOWER_DEFER)) add("cplus_defer_statement")
            if (!passSelection.enabled(CPlusFrontendPassIds.LOWER_TRY_CATCH)) {
                add("cplus_try_statement")
                add("cplus_catch_clause")
            }
            if (!passSelection.enabled(CPlusFrontendPassIds.LOWER_STRUCT_METHODS)) {
                add("cplus_method_definition")
                add("cplus_throws_annotated_method")
            }
            if (!passSelection.extractTests) {
                add("cplus_test_declaration")
                add("cplus_test_assertion_statement")
            }
        }
        val unsupported = unsupportedConstructs(parsed.root, disabledRuntimeKinds).toMutableList()
        if (!passSelection.enabled(CPlusFrontendPassIds.LOWER_METHOD_CALLS)) {
            // A disabled receiver pass must not be mistaken for ordinary C field-call syntax.
            // Resolve the remaining member calls and retain them as an explicit C-plus boundary
            // so migration rollback fails closed instead of emitting invalid C silently.
            CPlusSemanticAnalyzer().analyze(CPlusAstAdapter().adapt(parsed)).resolvedCalls
                .forEach { call ->
                    unsupported += TreeSitterUnsupportedConstruct("cplus_method_invocation", call.span)
                }
        }
        val mappedUnsupported = unsupported
            .distinctBy { it.syntaxKind to it.span.startOffset }
            .map { it.copy(span = mapped.toOriginalSpan(it.span)) }
        if (mappedUnsupported.isNotEmpty()) {
            return TreeSitterPrototypeResult(null, emptyList(), emptyList(), mappedUnsupported, testFixtures = testFixtures)
        }
        var throwingFunctions: Map<String, CPlusThrowingFunction> = emptyMap()
        val runtimeSteps = buildList {
            if (passSelection.enabled(CPlusFrontendPassIds.EXTRACT_THROWS)) add(
                CPlusAstLoweringStep(CPlusFrontendPassIds.EXTRACT_THROWS) { stageAst, stageSource ->
                    val result = CPlusThrowsLoweringPass().lower(stageAst, stageSource)
                    throwingFunctions = result.functions
                    cplus.CPlusLoweringResult(
                        result.source,
                        result.diagnostics.map { it.withMappedSpan(stageSource, it.span) }
                    )
                }
            )
            if (passSelection.enabled(CPlusFrontendPassIds.LOWER_TRY_CATCH)) add(
                CPlusAstLoweringStep(CPlusFrontendPassIds.LOWER_TRY_CATCH) { stageAst, stageSource ->
                    val result = CPlusTryCatchLoweringPass().lower(stageAst, stageSource, throwingFunctions)
                    cplus.CPlusLoweringResult(
                        result.source,
                        result.diagnostics.map { it.withMappedSpan(stageSource, it.span) }
                    )
                }
            )
            if (passSelection.enabled(CPlusFrontendPassIds.LOWER_DEFER)) add(
                CPlusAstLoweringStep(CPlusFrontendPassIds.LOWER_DEFER) { stageAst, stageSource ->
                    val result = CPlusDeferLoweringPass().lower(stageAst, stageSource)
                    cplus.CPlusLoweringResult(
                        result.source,
                        result.diagnostics.map { it.withMappedSpan(stageSource, it.span) }
                    )
                }
            )
            if (passSelection.enabled(CPlusFrontendPassIds.VALIDATE_SEMANTICS)) add(
                CPlusAstLoweringStep(CPlusFrontendPassIds.VALIDATE_SEMANTICS) { stageAst, stageSource ->
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
                }
            )
            if (passSelection.enabled(CPlusFrontendPassIds.LOWER_METHOD_CALLS)) add(
                CPlusAstLoweringStep(CPlusFrontendPassIds.LOWER_METHOD_CALLS) { stageAst, stageSource ->
                    val semantics = CPlusSemanticAnalyzer().analyze(stageAst)
                    val result = CPlusMethodCallLoweringPass().lower(stageAst, stageSource, semantics)
                    cplus.CPlusLoweringResult(
                        result.source,
                        result.diagnostics.map { it.withMappedSpan(stageSource, it.span) }
                    )
                }
            )
            if (passSelection.enabled(CPlusFrontendPassIds.LOWER_STRUCT_METHODS)) add(
                CPlusAstLoweringStep(CPlusFrontendPassIds.LOWER_STRUCT_METHODS) { stageAst, stageSource ->
                    val result = CPlusStructMethodLoweringPass().lower(stageAst, stageSource)
                    cplus.CPlusLoweringResult(
                        result.source,
                        result.diagnostics.map { it.withMappedSpan(stageSource, it.span) }
                    )
                }
            )
        }
        val runtimeLowering = CPlusAstLoweringPipeline().run(
            ast,
            mapped,
            runtimeSteps
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

        // Lower fixture bodies through the same AST pipeline as the runtime source before
        // extracting them.  The extracted-test bridge is intentionally retained for harness
        // generation, but it must not be the first compiler pass that sees C-plus method calls,
        // defer, or try/catch inside a fixture.  Extracting earlier made fixture code depend on
        // the legacy textual lowerers and could move an unresolved receiver out of its scope.
        if (passSelection.extractTests) {
            val extractedTests = CPlusTestExtractionPass().extract(ast, mapped)
            if (extractedTests.diagnostics.isNotEmpty()) {
                return TreeSitterPrototypeResult(null, emptyList(), extractedTests.diagnostics, emptyList())
            }
            testFixtures = extractedTests.fixtures
            mapped = extractedTests.source
            snapshot = snapshotFor(mapped.text)
            parsed = backend.parse(snapshot)
            if (parsed.diagnostics.isNotEmpty()) {
                return TreeSitterPrototypeResult(
                    null,
                    parsed.diagnostics.map { it.withMappedSpan(mapped, it.span) },
                    emptyList(),
                    emptyList(),
                    throwingFunctions,
                    testFixtures,
                    allocationAnalysis = allocationAnalysis
                )
            }
            ast = CPlusAstAdapter().adapt(parsed)
        }

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
        val hygienicGeneratedC = rewriteCPlusAnnotationMacros(generatedC)
        // Validate the emitter's C-plus-shaped intermediate with the C-plus
        // grammar; the hygienic macro names are applied only to final C output.
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
            .emit(hygienicGeneratedC, "", allocationAnalysis, compilerOptions)
            .copy(
                sourceOrder = sourceOrder,
                sourceImports = sourceImports,
                frontendPasses = runtimeLowering.trace.map { it.stepId },
                frontendPassesChanged = runtimeLowering.trace
                    .filter { it.sourceChanged }
                    .map { it.stepId }
                    .toSet()
            )
        return TreeSitterPrototypeResult(
            hygienicGeneratedC,
            emptyList(),
            emptyList(),
            emptyList(),
            throwingFunctions,
            testFixtures,
            transcoded,
            allocationAnalysis,
            compilerOptions,
            sourceOrder,
            sourceImports,
            runtimeLowering.trace.map { it.stepId },
            runtimeLowering.trace
                .filter { it.sourceChanged }
                .map { it.stepId }
                .toSet()
        )
    }

    private fun unsupportedConstructs(
        root: CPlusSyntaxNode,
        additionalUnsupportedKinds: Set<String> = emptySet()
    ): List<TreeSitterUnsupportedConstruct> {
        val unsupportedKinds = setOf(
            "cplus_comptime_declaration", "cplus_comptime_block", "cplus_comptime_function_definition",
            "cplus_comptime_invocation", "cplus_comptime_value", "cplus_comptime_import", "cplus_comptime_flags",
            "cplus_comptime_expression", "cplus_comptime_conditional", "cplus_code_fragment", "cplus_at_call_expression"
        ) + additionalUnsupportedKinds
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
        const val MAX_COMPTIME_CONDITIONAL_PASSES = 128
        val CPLUS_MODULE_IMPORT_NODES = setOf("cplus_comptime_import", "cplus_at_import")
    }
}
