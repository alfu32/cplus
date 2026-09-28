package cplus.parser

import cplus.CPlusImportPaths
import cplus.CPlusLegacyPassSelection
import cplus.CPlusSyntaxException
import cplus.CPlusTarget
import cplus.CPlusTranspiler
import cplus.SourceSpan
import cplus.SourceSnapshot
import cplus.TranscodedSource
import cplus.TranscodedTestSource
import cplus.TranscodedTestFixture

/** A finite, migration-only comparison between the legacy and AST compilation paths. */
data class TreeSitterDifferentialReport(
    val legacy: TranscodedSource,
    val treeSitter: TreeSitterPrototypeResult,
    val normalizedTokensMatch: Boolean,
    val compilerOptionsMatch: Boolean,
    val sourceOrderMatch: Boolean,
    val allocationDiagnosticsMatch: Boolean,
    /** Requires the AST map to cover every source line covered by the legacy map. */
    val sourceMapCoverageMatch: Boolean,
    val frontendPassesMatch: Boolean,
    val frontendPassChangesMatch: Boolean,
    val frontendPassOrderMatch: Boolean,
    val tokenDifference: String? = null
) {
    val successful: Boolean
        get() = treeSitter.successful && normalizedTokensMatch && compilerOptionsMatch &&
            sourceOrderMatch && allocationDiagnosticsMatch && frontendPassesMatch && frontendPassChangesMatch
            && sourceMapCoverageMatch
}

/** Result of disabling one named runtime pass in both migration frontends. */
data class TreeSitterPassRollbackReport(
    val passId: String,
    val legacy: TranscodedSource?,
    val legacyFailure: String?,
    val treeSitter: TreeSitterPrototypeResult,
    val legacyPassDisabled: Boolean,
    val treeSitterPassDisabled: Boolean
) {
    /** True only when both selectors accepted the pass and omitted it from their traces. */
    val selectorContractHolds: Boolean
        get() = legacy != null && legacyFailure == null &&
            legacyPassDisabled && treeSitterPassDisabled &&
            passId !in legacy.frontendPasses && passId !in treeSitter.runtimePasses
}

/**
 * Bounded test-mode comparison. Fixture discovery, mapped assertion reachability, and the
 * normalized generated harness body are compared independently from source formatting.
 */
data class TreeSitterTestDifferentialReport(
    val legacy: TranscodedTestSource,
    val treeSitter: TreeSitterPrototypeResult,
    val astHarness: TranscodedTestSource?,
    val fixtureNamesMatch: Boolean,
    val assertionCountsMatch: Boolean,
    val harnessFixtureNamesMatch: Boolean,
    val harnessAssertionCountsMatch: Boolean,
    /** Generated fixture function bodies match after removing formatting and compiler-only line directives. */
    val harnessTokensMatch: Boolean,
    val harnessTokenDifference: String? = null,
    /** Assertion source lines remain reachable through AST extraction. */
    val assertionSourceLinesMatch: Boolean,
    /** Source-declared compiler options survive test-harness materialization as a logical set. */
    val compilerOptionsMatch: Boolean,
    /** The AST harness retains every source line belonging to extracted assertions. */
    val sourceMapCoverageMatch: Boolean,
    val sourceMapCoverageMissing: Set<String> = emptySet()
) {
    val successful: Boolean
        get() = treeSitter.successful && astHarness != null && fixtureNamesMatch && assertionCountsMatch &&
            harnessFixtureNamesMatch && harnessAssertionCountsMatch && harnessTokensMatch && assertionSourceLinesMatch &&
            compilerOptionsMatch && sourceMapCoverageMatch
}

/** Rollback evidence for disabling fixture extraction in both migration frontends. */
data class TreeSitterTestExtractionRollbackReport(
    val legacyFailure: String?,
    val legacyFailureLine: Int?,
    val treeSitter: TreeSitterPrototypeResult,
    val treeSitterBoundaryRetained: Boolean
) {
    val selectorContractHolds: Boolean
        get() = legacyFailure != null && legacyFailureLine != null &&
            !treeSitter.successful && treeSitterBoundaryRetained
}

/** Rollback evidence for disabling comptime resolution in both migration frontends. */
data class TreeSitterComptimeRollbackReport(
    val legacyFailure: String?,
    val legacyFailureSpan: SourceSpan?,
    val treeSitter: TreeSitterPrototypeResult,
    val treeSitterBoundary: TreeSitterUnsupportedConstruct?
) {
    val selectorContractHolds: Boolean
        get() = legacyFailure != null && legacyFailureSpan != null &&
            treeSitterBoundary != null && treeSitterBoundary.span.file == legacyFailureSpan.file &&
            treeSitterBoundary.span.startOffset == legacyFailureSpan.startOffset &&
            treeSitterBoundary.span.endOffset == legacyFailureSpan.endOffset
}

/**
 * Extraction-only substitution report. Runtime C-plus lowerers are intentionally disabled, so
 * this contract compares fixture metadata and source locations without requiring a complete C
 * emission from the surrounding module.
 */
data class TreeSitterTestExtractionOnlySubstitutionReport(
    val legacy: TranscodedTestSource,
    val treeSitter: TreeSitterPrototypeResult,
    val fixtureNamesMatch: Boolean,
    val assertionCountsMatch: Boolean,
    val assertionSourceLinesMatch: Boolean
) {
    val successful: Boolean
        get() = treeSitter.parserDiagnostics.isEmpty() && fixtureNamesMatch &&
            assertionCountsMatch && assertionSourceLinesMatch
}

/**
 * Reports one exercised pass as a full-output differential plus a fail-closed rollback check.
 * This is stronger than a neutral selector probe, but it is still not a retirement decision:
 * callers must supply a representative corpus and runtime/compiler evidence separately.
 */
data class TreeSitterPassParityReport(
    val passId: String,
    val full: TreeSitterDifferentialReport,
    val rollback: TreeSitterPassRollbackReport
) {
    val passExercised: Boolean
        get() = passId in full.legacy.frontendPassesChanged && passId in full.treeSitter.runtimePassesChanged

    val successful: Boolean
        get() = full.successful && passExercised && rollback.selectorContractHolds && !rollback.treeSitter.successful
}

/**
 * Compares one transformation with only its documented migration dependencies enabled. This is
 * stronger than disabling one pass in the complete pipeline, while still allowing receiver-call
 * lowering to retain struct-method lowering as a prerequisite for semantic indexing.
 */
data class TreeSitterPassSubstitutionReport(
    val passId: String,
    val enabledPasses: Set<String>,
    val legacy: TranscodedSource?,
    val legacyFailure: String?,
    val treeSitter: TreeSitterPrototypeResult,
    val normalizedTokensMatch: Boolean,
    val compilerOptionsMatch: Boolean,
    val sourceMapCoverageMatch: Boolean,
    val targetChangedBoth: Boolean,
    val tokenDifference: String? = null
) {
    val successful: Boolean
        get() = legacy != null && legacyFailure == null && treeSitter.successful &&
            normalizedTokensMatch && compilerOptionsMatch && sourceMapCoverageMatch && targetChangedBoth
}

/** Parity report for analysis-only passes whose contract is diagnostics, not source edits. */
data class TreeSitterSemanticPassParityReport(
    val passId: String,
    val full: TreeSitterDifferentialReport,
    val rollback: TreeSitterPassRollbackReport
) {
    val diagnosticsMatch: Boolean
        get() = full.allocationDiagnosticsMatch

    val successful: Boolean
        get() = full.successful && diagnosticsMatch && rollback.selectorContractHolds
}

/** Substitution report for an analysis-only pass whose output contract is diagnostics. */
data class TreeSitterSemanticPassSubstitutionReport(
    val passId: String,
    val enabledPasses: Set<String>,
    val legacy: TranscodedSource?,
    val legacyFailure: String?,
    val treeSitter: TreeSitterPrototypeResult,
    val diagnosticsMatch: Boolean,
    val normalizedTokensMatch: Boolean,
    val compilerOptionsMatch: Boolean,
    val sourceMapCoverageMatch: Boolean,
    val tokenDifference: String? = null
) {
    val successful: Boolean
        get() = legacy != null && legacyFailure == null && treeSitter.successful && diagnosticsMatch &&
            normalizedTokensMatch && compilerOptionsMatch && sourceMapCoverageMatch
}

/**
 * Runs the two frontends on one overlap-corpus source. It does not decide which frontend is
 * authoritative and it never changes the production selector. Formatting and `#line` metadata
 * are excluded from token comparison; options and import order remain exact comparisons.
 */
class TreeSitterCPlusDifferentialRunner(
    private val backend: cplus.CPlusParserBackend = TreeSitterCPlusParserBackend(),
    private val sourceManager: cplus.SourceManager = cplus.SourceManager()
    ) {
    /** Runs the comptime rollback boundary in both frontends without changing production defaults. */
    fun compareComptimeRollback(
        source: SourceSnapshot,
        targetOs: String = CPlusTarget.hostOs(),
        importPaths: CPlusImportPaths = CPlusImportPaths(),
        targetArch: String = CPlusTarget.hostArch()
    ): TreeSitterComptimeRollbackReport {
        val legacyResult = runCatching {
            CPlusTranspiler().transpile(
                source.text,
                source.id.value,
                importPaths = importPaths,
                targetOs = targetOs,
                legacyPassSelection = CPlusLegacyPassSelection(resolveComptime = false)
            )
        }
        val treeResult = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sourceManager,
            targetOs = targetOs,
            importPaths = importPaths,
            targetArch = targetArch,
            passSelection = TreeSitterPassSelection(resolveComptime = false)
        ).transpile(source)
        val legacyFailure = legacyResult.exceptionOrNull()
        val legacySpan = (legacyFailure as? CPlusSyntaxException)?.sourceSpan
        val boundary = treeResult.unsupportedNodes.firstOrNull {
            it.syntaxKind in TREE_SITTER_COMPTIME_ROLLBACK_NODES
        }
        return TreeSitterComptimeRollbackReport(
            legacyFailure = legacyFailure?.message,
            legacyFailureSpan = legacySpan,
            treeSitter = treeResult,
            treeSitterBoundary = boundary
        )
    }

    /**
     * Compare AST test extraction independently from every runtime lowering pass.
     *
     * The generated harness still uses the established compatibility bridge after extraction;
     * this operation isolates the extraction contract itself and leaves all runtime lowerers
     * disabled in both frontends. It is migration evidence, not a promotion decision.
     */
    fun compareTestExtractionSubstitution(
        source: SourceSnapshot,
        targetOs: String = CPlusTarget.hostOs(),
        importPaths: CPlusImportPaths = CPlusImportPaths(),
        targetArch: String = CPlusTarget.hostArch()
    ): TreeSitterTestDifferentialReport = compareTests(
        source = source,
        targetOs = targetOs,
        importPaths = importPaths,
        targetArch = targetArch,
        legacyPassSelection = CPlusLegacyPassSelection(
            disabledPasses = CPlusLegacyPassSelection.KNOWN_PASSES,
            extractTests = true
        ),
        treeSitterPassSelection = TreeSitterPassSelection(
            disabledRuntimePasses = CPlusLegacyPassSelection.KNOWN_PASSES,
            extractTests = true
        )
    )

    /**
     * Disable extraction in both frontends and require a mapped, fail-closed fixture boundary.
     * Neither frontend may silently emit a runtime C program with the fixture left behind.
     */
    fun compareTestExtractionRollback(
        source: SourceSnapshot,
        targetOs: String = CPlusTarget.hostOs(),
        importPaths: CPlusImportPaths = CPlusImportPaths(),
        targetArch: String = CPlusTarget.hostArch()
    ): TreeSitterTestExtractionRollbackReport {
        val disabledRuntimePasses = CPlusLegacyPassSelection.KNOWN_PASSES
        val legacyResult = runCatching {
            CPlusTranspiler().transpileTests(
                source.text,
                source.id.value,
                importPaths = importPaths,
                targetOs = targetOs,
                legacyPassSelection = CPlusLegacyPassSelection(
                    disabledPasses = disabledRuntimePasses,
                    extractTests = false
                )
            )
        }
        val legacyException = legacyResult.exceptionOrNull()
        val legacySyntaxException = legacyException as? CPlusSyntaxException
        val treeSitter = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sourceManager,
            targetOs = targetOs,
            importPaths = importPaths,
            targetArch = targetArch,
            passSelection = TreeSitterPassSelection(
                disabledRuntimePasses = disabledRuntimePasses,
                extractTests = false
            )
        ).transpile(source)
        return TreeSitterTestExtractionRollbackReport(
            legacyFailure = legacyException?.message,
            legacyFailureLine = legacySyntaxException?.sourceSpan?.startLine,
            treeSitter = treeSitter,
            treeSitterBoundaryRetained = treeSitter.unsupportedNodes.any {
                it.syntaxKind == "cplus_test_declaration"
            }
        )
    }

    /** Compare fixture discovery while all runtime lowerers remain disabled. */
    fun compareTestExtractionOnlySubstitution(
        source: SourceSnapshot,
        targetOs: String = CPlusTarget.hostOs(),
        importPaths: CPlusImportPaths = CPlusImportPaths(),
        targetArch: String = CPlusTarget.hostArch()
    ): TreeSitterTestExtractionOnlySubstitutionReport {
        val disabledRuntimePasses = CPlusLegacyPassSelection.KNOWN_PASSES
        val legacy = CPlusTranspiler().transpileTests(
            source.text,
            source.id.value,
            importPaths = importPaths,
            targetOs = targetOs,
            legacyPassSelection = CPlusLegacyPassSelection(
                disabledPasses = disabledRuntimePasses,
                extractTests = true
            )
        )
        val treeSitter = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sourceManager,
            targetOs = targetOs,
            importPaths = importPaths,
            targetArch = targetArch,
            passSelection = TreeSitterPassSelection(
                disabledRuntimePasses = disabledRuntimePasses,
                extractTests = true
            )
        ).transpile(source)
        return TreeSitterTestExtractionOnlySubstitutionReport(
            legacy = legacy,
            treeSitter = treeSitter,
            fixtureNamesMatch = legacy.testNames == treeSitter.testFixtures.map { it.name },
            assertionCountsMatch = legacy.fixtures.map { it.assertionCount } ==
                treeSitter.testFixtures.map { it.assertions.size },
            assertionSourceLinesMatch = legacy.fixtures.flatMap { fixture ->
                fixture.assertionSourceSpans.map { it.startLine }
            } == treeSitter.testFixtures.flatMap { fixture ->
                fixture.assertions.map { it.span.startLine }
            }
        )
    }

    fun compareTests(
        source: SourceSnapshot,
        targetOs: String = CPlusTarget.hostOs(),
        importPaths: CPlusImportPaths = CPlusImportPaths(),
        targetArch: String = CPlusTarget.hostArch(),
        legacyPassSelection: CPlusLegacyPassSelection = CPlusLegacyPassSelection(),
        treeSitterPassSelection: TreeSitterPassSelection = TreeSitterPassSelection()
    ): TreeSitterTestDifferentialReport {
        val legacy = CPlusTranspiler().transpileTests(
            source.text,
            source.id.value,
            importPaths = importPaths,
            targetOs = targetOs,
            legacyPassSelection = legacyPassSelection
        )
        val treeSitter = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sourceManager,
            targetOs = targetOs,
            importPaths = importPaths,
            targetArch = targetArch,
            passSelection = treeSitterPassSelection
        ).transpile(source)
        val legacyCounts = legacy.fixtures.map { it.name to it.assertionCount }
        val treeCounts = treeSitter.testFixtures.map { it.name to it.assertions.size }
        val astHarness = treeSitter.cSource?.let { runtime ->
            CPlusTranspiler().emitExtractedTestHarness(runtime, treeSitter.testFixtures)
        }
        val harnessCounts = astHarness?.fixtures?.map { it.name to it.assertionCount }
        val legacyHarnessTokens = fixtureHarnessTokens(legacy.source.code)
        val astHarnessTokens = astHarness?.let { fixtureHarnessTokens(it.source.code) }
        val harnessTokenDifference = if (astHarnessTokens == null || legacyHarnessTokens == astHarnessTokens) {
            null
        } else {
            firstDifference(legacyHarnessTokens, astHarnessTokens)
        }
        val legacyAssertionLines = legacy.fixtures.map { fixture ->
            fixture.assertionSourceSpans.map { it.startLine }
        }
        val treeAssertionLines = treeSitter.testFixtures.map { fixture ->
            fixture.assertions.map { it.span.startLine }
        }
        val requiredFixtureMap = fixtureAssertionSourceSignatures(legacy.fixtures)
        val actualFixtureMap = sourceMapCoverageSignature(astHarness?.source)
        return TreeSitterTestDifferentialReport(
            legacy = legacy,
            treeSitter = treeSitter,
            astHarness = astHarness,
            fixtureNamesMatch = legacy.testNames == treeSitter.testFixtures.map { it.name },
            assertionCountsMatch = legacyCounts == treeCounts,
            harnessFixtureNamesMatch = legacy.testNames == astHarness?.testNames,
            harnessAssertionCountsMatch = legacyCounts == harnessCounts,
            harnessTokensMatch = astHarnessTokens != null && legacyHarnessTokens == astHarnessTokens,
            harnessTokenDifference = harnessTokenDifference,
            assertionSourceLinesMatch = legacyAssertionLines == treeAssertionLines,
            compilerOptionsMatch = legacy.source.compilerOptions.sorted() == treeSitter.compilerOptions.sorted(),
            sourceMapCoverageMatch = astHarness != null && requiredFixtureMap.all { it in actualFixtureMap },
            sourceMapCoverageMissing = requiredFixtureMap - actualFixtureMap
        )
    }

    fun compare(
        source: SourceSnapshot,
        targetOs: String = CPlusTarget.hostOs(),
        importPaths: CPlusImportPaths = CPlusImportPaths(),
        targetArch: String = CPlusTarget.hostArch()
    ): TreeSitterDifferentialReport {
        val legacy = CPlusTranspiler().transpile(
            source.text,
            source.id.value,
            importPaths = importPaths,
            targetOs = targetOs
        )
        val treeSitter = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sourceManager,
            targetOs = targetOs,
            importPaths = importPaths,
            targetArch = targetArch
        ).transpile(source)
        val treeCode = treeSitter.transcodedSource?.code
        val legacyTokens = normalizeC(legacy.code)
        val treeTokens = treeCode?.let(::normalizeC)
        val tokenDifference = if (treeTokens == null || legacyTokens == treeTokens) {
            null
        } else {
            firstDifference(legacyTokens, treeTokens)
        }
        return TreeSitterDifferentialReport(
            legacy = legacy,
            treeSitter = treeSitter,
            normalizedTokensMatch = treeTokens != null && legacyTokens == treeTokens,
            compilerOptionsMatch = treeCode != null && legacy.compilerOptions == treeSitter.compilerOptions,
            sourceOrderMatch = treeCode != null && legacy.sourceOrder == treeSitter.sourceOrder,
            allocationDiagnosticsMatch = treeCode != null &&
                allocationDiagnosticsSignature(legacy.allocationAnalysis) ==
                    allocationDiagnosticsSignature(treeSitter.allocationAnalysis),
            sourceMapCoverageMatch = treeCode != null &&
                sourceMapCoverageSignature(legacy).all { it in sourceMapCoverageSignature(treeSitter.transcodedSource) },
            frontendPassesMatch = treeCode != null &&
                legacy.frontendPasses.toSet() == treeSitter.runtimePasses.toSet(),
            frontendPassChangesMatch = treeCode != null &&
                legacy.frontendPassesChanged == treeSitter.runtimePassesChanged,
            frontendPassOrderMatch = treeCode != null && legacy.frontendPasses == treeSitter.runtimePasses,
            tokenDifference = tokenDifference
        )
    }

    /**
     * Runs one migration rollback boundary in both frontends. This intentionally does not claim
     * semantic parity: the returned report is the evidence needed before making that claim.
     */
    fun comparePassRollback(
        source: SourceSnapshot,
        passId: String,
        targetOs: String = CPlusTarget.hostOs(),
        importPaths: CPlusImportPaths = CPlusImportPaths(),
        targetArch: String = CPlusTarget.hostArch()
    ): TreeSitterPassRollbackReport {
        require(passId in CPlusLegacyPassSelection.KNOWN_PASSES) {
            "unknown migration pass: $passId"
        }
        val legacyResult = runCatching {
            CPlusTranspiler().transpile(
                source.text,
                source.id.value,
                importPaths = importPaths,
                targetOs = targetOs,
                legacyPassSelection = CPlusLegacyPassSelection(setOf(passId))
            )
        }
        val treeResult = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sourceManager,
            targetOs = targetOs,
            importPaths = importPaths,
            targetArch = targetArch,
            passSelection = TreeSitterPassSelection(setOf(passId))
        ).transpile(source)
        return TreeSitterPassRollbackReport(
            passId = passId,
            legacy = legacyResult.getOrNull(),
            legacyFailure = legacyResult.exceptionOrNull()?.message,
            treeSitter = treeResult,
            legacyPassDisabled = legacyResult.getOrNull()?.frontendPasses?.none { it == passId } == true,
            treeSitterPassDisabled = passId !in treeResult.runtimePasses
        )
    }

    fun comparePass(
        source: SourceSnapshot,
        passId: String,
        targetOs: String = CPlusTarget.hostOs(),
        importPaths: CPlusImportPaths = CPlusImportPaths(),
        targetArch: String = CPlusTarget.hostArch()
    ): TreeSitterPassParityReport {
        require(passId in CPlusLegacyPassSelection.KNOWN_PASSES) {
            "unknown migration pass: $passId"
        }
        return TreeSitterPassParityReport(
            passId = passId,
            full = compare(source, targetOs, importPaths, targetArch),
            rollback = comparePassRollback(source, passId, targetOs, importPaths, targetArch)
        )
    }

    fun comparePassSubstitution(
        source: SourceSnapshot,
        passId: String,
        targetOs: String = CPlusTarget.hostOs(),
        importPaths: CPlusImportPaths = CPlusImportPaths(),
        targetArch: String = CPlusTarget.hostArch()
    ): TreeSitterPassSubstitutionReport {
        require(passId in CPlusLegacyPassSelection.KNOWN_PASSES) {
            "unknown migration pass: $passId"
        }
        require(passId != CPlusLegacyPassSelection.VALIDATE_SEMANTICS) {
            "analysis-only validation requires compareSemanticPass"
        }
        val enabled = setOf(passId) + when (passId) {
            CPlusLegacyPassSelection.LOWER_METHOD_CALLS -> setOf(CPlusLegacyPassSelection.LOWER_STRUCT_METHODS)
            CPlusLegacyPassSelection.LOWER_TRY_CATCH -> setOf(CPlusLegacyPassSelection.EXTRACT_THROWS)
            else -> emptySet()
        }
        val disabled = CPlusLegacyPassSelection.KNOWN_PASSES - enabled
        val legacyResult = runCatching {
            CPlusTranspiler().transpile(
                source.text,
                source.id.value,
                importPaths = importPaths,
                targetOs = targetOs,
                legacyPassSelection = CPlusLegacyPassSelection(disabled)
            )
        }
        val treeResult = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sourceManager,
            targetOs = targetOs,
            importPaths = importPaths,
            targetArch = targetArch,
            passSelection = TreeSitterPassSelection(disabledRuntimePasses = disabled)
        ).transpile(source)
        val legacy = legacyResult.getOrNull()
        val treeCode = treeResult.transcodedSource?.code
        val legacyTokens = legacy?.let { normalizeC(it.code) }
        val treeTokens = treeCode?.let(::normalizeC)
        val tokenDifference = if (legacyTokens == null || treeTokens == null || legacyTokens == treeTokens) {
            null
        } else {
            firstDifference(legacyTokens, treeTokens)
        }
        return TreeSitterPassSubstitutionReport(
            passId = passId,
            enabledPasses = enabled,
            legacy = legacy,
            legacyFailure = legacyResult.exceptionOrNull()?.message,
            treeSitter = treeResult,
            normalizedTokensMatch = legacyTokens != null && legacyTokens == treeTokens,
            compilerOptionsMatch = legacy != null && treeResult.compilerOptions == legacy.compilerOptions,
            sourceMapCoverageMatch = legacy != null && treeResult.transcodedSource != null &&
                sourceMapCoverageSignature(legacy).all {
                    it in sourceMapCoverageSignature(treeResult.transcodedSource)
                },
            targetChangedBoth = legacy?.frontendPassesChanged?.contains(passId) == true &&
                treeResult.runtimePassesChanged.contains(passId),
            tokenDifference = tokenDifference
        )
    }

    fun compareSemanticPass(
        source: SourceSnapshot,
        passId: String,
        targetOs: String = CPlusTarget.hostOs(),
        importPaths: CPlusImportPaths = CPlusImportPaths(),
        targetArch: String = CPlusTarget.hostArch()
    ): TreeSitterSemanticPassParityReport {
        require(passId == CPlusLegacyPassSelection.VALIDATE_SEMANTICS) {
            "only analysis-only pass parity is supported here: ${CPlusLegacyPassSelection.VALIDATE_SEMANTICS}"
        }
        return TreeSitterSemanticPassParityReport(
            passId = passId,
            full = compare(source, targetOs, importPaths, targetArch),
            rollback = comparePassRollback(source, passId, targetOs, importPaths, targetArch)
        )
    }

    fun compareSemanticPassSubstitution(
        source: SourceSnapshot,
        passId: String = CPlusLegacyPassSelection.VALIDATE_SEMANTICS,
        targetOs: String = CPlusTarget.hostOs(),
        importPaths: CPlusImportPaths = CPlusImportPaths(),
        targetArch: String = CPlusTarget.hostArch()
    ): TreeSitterSemanticPassSubstitutionReport {
        require(passId == CPlusLegacyPassSelection.VALIDATE_SEMANTICS) {
            "only analysis-only validation substitution is supported: $passId"
        }
        val enabled = setOf(passId)
        val disabled = CPlusLegacyPassSelection.KNOWN_PASSES - enabled
        val legacyResult = runCatching {
            CPlusTranspiler().transpile(
                source.text,
                source.id.value,
                importPaths = importPaths,
                targetOs = targetOs,
                legacyPassSelection = CPlusLegacyPassSelection(disabled)
            )
        }
        val treeResult = TreeSitterCPlusPrototypeTranspiler(
            backend = backend,
            sourceManager = sourceManager,
            targetOs = targetOs,
            importPaths = importPaths,
            targetArch = targetArch,
            passSelection = TreeSitterPassSelection(disabledRuntimePasses = disabled)
        ).transpile(source)
        val legacy = legacyResult.getOrNull()
        val treeCode = treeResult.transcodedSource?.code
        val legacyTokens = legacy?.let { normalizeC(it.code) }
        val treeTokens = treeCode?.let(::normalizeC)
        val tokenDifference = if (legacyTokens == null || treeTokens == null || legacyTokens == treeTokens) {
            null
        } else {
            firstDifference(legacyTokens, treeTokens)
        }
        return TreeSitterSemanticPassSubstitutionReport(
            passId = passId,
            enabledPasses = enabled,
            legacy = legacy,
            legacyFailure = legacyResult.exceptionOrNull()?.message,
            treeSitter = treeResult,
            diagnosticsMatch = legacy != null &&
                allocationDiagnosticsSignature(legacy.allocationAnalysis) ==
                allocationDiagnosticsSignature(treeResult.allocationAnalysis),
            normalizedTokensMatch = legacyTokens != null && legacyTokens == treeTokens,
            compilerOptionsMatch = legacy != null && treeResult.compilerOptions == legacy.compilerOptions,
            sourceMapCoverageMatch = legacy != null && treeResult.transcodedSource != null &&
                sourceMapCoverageSignature(legacy).all {
                    it in sourceMapCoverageSignature(treeResult.transcodedSource)
                },
            tokenDifference = tokenDifference
        )
    }

    private fun firstDifference(left: List<String>, right: List<String>): String {
        val common = minOf(left.size, right.size)
        val index = (0 until common).firstOrNull { left[it] != right[it] } ?: common
        return "token[$index]: legacy=${left.getOrNull(index)} tree-sitter=${right.getOrNull(index)} " +
            "(legacyCount=${left.size}, treeSitterCount=${right.size})"
    }

    /**
     * Compare the semantic portion of allocation metadata. The legacy scanner also reports
     * unannotated C declarations, while the AST analyzer intentionally reports only symbols
     * carrying ownership/intent/provenance. Source revisions created by AST reparsing are
     * normalized back to the originating file before comparison.
     */
    private fun allocationDiagnosticsSignature(result: cplus.AllocationAnalysisResult): List<String> = buildList {
        result.diagnostics.forEach { diagnostic ->
            add(
                listOf(
                    "diagnostic",
                    diagnostic.message,
                    (diagnostic.sourceSpan.file ?: "<unknown>").substringBefore("#tree-sitter-"),
                    diagnostic.sourceSpan.startLine,
                    diagnostic.sourceSpan.startColumn
                ).joinToString("|")
            )
        }
    }.sorted()

    /**
     * Compare mapping coverage, not generated line numbers or columns. The two emitters may choose
     * different layout while still mapping the same source lines. Generated-only text and the AST's
     * synthetic revision suffix are intentionally excluded from this bounded migration check.
     */
    private fun sourceMapCoverageSignature(source: TranscodedSource?): Set<String> = source?.sourceMap?.entries
        ?.map { entry ->
            val file = (entry.source.file ?: "<unknown>").substringBefore("#tree-sitter-")
            "$file:${entry.source.startLine}"
        }
        ?.toSet()
        ?: emptySet()

    private fun fixtureAssertionSourceSignatures(fixtures: List<TranscodedTestFixture>): Set<String> = fixtures
        .flatMap { fixture -> fixture.assertionSourceSpans }
        .map { span -> "${span.file ?: "<unknown>"}:${span.startLine}" }
        .toSet()

    private fun normalizeC(code: String): List<String> {
        val withoutLine = code.replace(Regex("(?m)^[ \\t]*#line[^\\r\\n]*(?:\\r?\\n|$)"), "")
        val withoutComments = withoutLine
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("//[^\\r\\n]*"), " ")
        val token = Regex(
            "(?:\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|" +
                "[A-Za-z_][A-Za-z0-9_]*|0[xX][0-9A-Fa-f]+|[0-9]+(?:\\.[0-9]+)?|" +
                ">>=|<<=|->|\\+\\+|--|&&|\\|\\||==|!=|<=|>=|<<|>>|\\+=|-=|\\*=|/=|%=|&=|\\|=|\\^=|[^\\s])"
        )
        return token.findAll(withoutComments).map { it.value }.toList()
    }

    /**
     * Remove only harness-owned initialization statements whose relative position is
     * allowed to differ when a fixture pass has already inserted its cleanup state.
     * Fixture statements, lowering output, and assertion macros remain in the token
     * contract.
     */
    private fun normalizeTestHarness(code: String): List<String> {
        val withoutHarnessInitialization = code
            .replace(Regex("(?m)^[ \\t]*cplus_test_failure\\s*=\\s*0\\s*;[ \\t]*(?:\\r?\\n|$)"), "")
            .replace(Regex("(?m)^[ \\t]*int\\s+cplus_defer_active_[A-Za-z0-9_]+\\s*=\\s*0\\s*;[ \\t]*(?:\\r?\\n|$)"), "")
        return normalizeC(withoutHarnessInitialization)
    }

    private fun fixtureHarnessTokens(code: String): List<String> {
        val starts = Regex("(?m)^\\s*static void cplus_test_\\d+\\(void\\) \\{")
            .findAll(code)
            .map { it.range.first }
            .toList()
        if (starts.isEmpty()) return emptyList()
        val endMarker = code.indexOf("static int cplus_test_requested")
            .takeIf { it >= 0 }
            ?: code.length
        return starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: endMarker
            normalizeTestHarness(code.substring(start, end))
        }.flatten()
    }
}
