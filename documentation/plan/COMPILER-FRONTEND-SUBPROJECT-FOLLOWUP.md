# Compiler Frontend Migration — Subproject Follow-up

This file turns the open rows in [`COMPILER-FRONTEND-IMPLEMENTATION.md`](COMPILER-FRONTEND-IMPLEMENTATION.md) into executable subprojects. The legacy frontend remains the production path until the relevant migration gates close. A subproject is complete only when its listed acceptance evidence exists; implementation code alone is not completion evidence.

## Tracking rules

- `DONE` means the named task has passing repository evidence.
- `IN PROGRESS` means implementation or evidence is active but incomplete.
- `OPEN` means planned work has not started or lacks acceptance evidence.
- `EXTERNAL` means the repository is configured but a supported CI host or release environment must provide the evidence.
- New edge cases stay under the nearest task unless they introduce a new invariant. That rule keeps the scope finite.
- Every completed task records its command, fixture, source-map/diagnostic expectation where applicable, and date in the follow-up log.

## Portfolio follow-up

| Subproject | Parent rows | Status | Next task | Exit evidence |
|---|---|---:|---|---|
| SP-2 packaged parser matrix | P2-07, P2-08 | EXTERNAL | Run the six-host JNI/resource jobs | Six host logs, manifests, checksums, and size report |
| SP-4.1 aggregate comptime reflection | P4-14 | OPEN | Define aggregate layout representation | Materialization, mapped failures, and host C compile/run |
| SP-4.2 scalar ABI and encodings | P4-15 | OPEN | Specify width/promotion rules | Target-width fixtures and encoding boundary diagnostics |
| SP-4.3 normative and differential corpus | P4-16 | OPEN | Inventory versioned specification examples | Frozen expected-output/diagnostic corpus and comparison report |
| SP-4.4 AST comptime migration | P4-17 | OPEN | Inventory scanner entry points | AST/legacy selection report and fixed-point parity |
| SP-5.1 declarators and comptime scope | P5-10 | IN PROGRESS | Add nested qualified declarator cases | Binding-shape index, scope diagnostics, and C11 fixtures |
| SP-5.2 compiler/ABI matrix | P5-11 | OPEN | Define supported driver/target matrix | Cross-driver compile/run and diagnostic report |
| SP-6.1 expression sequencing | P6-09 | OPEN | Classify sequencing guarantees | Conservative flow fixtures and host C behavior |
| SP-6.2 migration differential | P6-10 | OPEN | Define per-pass overlap records | Output/options/maps/diagnostics comparison report |
| SP-6.3 textual-lowerer retirement | P6-11 | OPEN | Close the method lowerer corpus gate | Independent rollback-safe retirement of each lowerer |
| SP-6.4 receiver/declarator coverage | P6-12 | IN PROGRESS | Cover calling conventions and remaining complex declarators | Complete receiver-shape matrix with mapped failures |

## Dependency and execution order

1. SP-2 can run independently in CI; it does not block local semantic work.
2. SP-4.1 and SP-4.2 define comptime values consumed by SP-4.3; their unsupported boundaries must be stable before corpus freezing.
3. SP-4.3 must freeze the accepted overlap before SP-4.4 retires scanner inputs.
4. SP-5.1 depends on the normalized declarator categories from Phases 2–3 and feeds SP-6.4.
5. SP-5.2 can run in parallel with local work, but its target results are required by the Phase 5 gate.
6. SP-6.1 must establish side-effect invariants before SP-6.2 compares allocation/lowering behavior.
7. SP-6.2 must pass before SP-6.3 removes any textual lowerer. SP-6.4 can proceed independently but is required by method-lowering retirement.

## SP-2 — packaged parser matrix

### Tasks

| ID | Task | Status | Evidence |
|---|---|---:|---|
| SP2-01 | Execute Linux x86_64 and Linux arm64 packaged JNI loads | EXTERNAL | CI job logs and parser smoke output |
| SP2-02 | Execute macOS x86_64 and macOS arm64 packaged JNI loads | EXTERNAL | CI job logs and parser smoke output |
| SP2-03 | Execute Windows x86_64 and Windows arm64 packaged JNI loads | EXTERNAL | CI job logs and parser smoke output |
| SP2-04 | Compare native resource manifests and detect duplicate payloads | EXTERNAL | Generated manifest artifact |
| SP2-05 | Record package sizes and generated-parser checksums | EXTERNAL | CI report attached to the workflow run |
| SP2-06 | Update the Phase 2 row only after all six hosts pass | OPEN | Follow-up entry with workflow URL/commit |

### Definition of done

All six hosts load the packaged parser, parse the smoke fixture without recovery, and expose the same resource contract. The manifest and size report are retained as CI artifacts. Local Linux success cannot close this subproject.

## SP-4.1 — aggregate comptime reflection

### Tasks

| ID | Task | Status | Evidence |
|---|---|---:|---|
| SP41-01 | Specify aggregate field order, alignment, padding, and offset semantics | OPEN | Versioned comptime specification section |
| SP41-02 | Expose aggregate `size`, `align`, and field offsets through the target ABI provider | OPEN | Materialization unit tests on supported targets |
| SP41-03 | Materialize structured annotation values with deterministic member access | OPEN | Positive and malformed annotation fixtures |
| SP41-04 | Define the iterable protocol for reflected collections and arbitrary expressions | OPEN | Evaluator tests and explicit unsupported diagnostics |
| SP41-05 | Apply total-expansion and nested-iteration limits to all new collections | OPEN | Limit/no-progress source-map tests |
| SP41-06 | Compile and run generated aggregates with padding-sensitive assertions | OPEN | Host C11 compile/run fixtures |

### Existing bounded evidence

Primitive/pointer/fixed-array reflection, field/annotation iteration, bitfields, widths, and mapped field origins are already covered. They remain prerequisites, not substitutes for aggregate layout or structured values.

### Definition of done

The specification defines the layout contract; every supported aggregate value has materialization and C compile/run evidence; unsupported layouts and annotation forms fail at their original `.cp` spans; no arbitrary iterable can bypass the expansion budget.

## SP-4.2 — scalar ABI and encodings

### Tasks

| ID | Task | Status | Evidence |
|---|---|---:|---|
| SP42-01 | Define the comptime integer model and its mapping to C integer types | OPEN | Specification table and evaluator tests |
| SP42-02 | Implement C promotions, conversions, comparison result types, and casts | OPEN | Positive and overflow/conversion diagnostics |
| SP42-03 | Verify width/suffix behavior across target ABIs | OPEN | Linux, macOS, and Windows target fixtures |
| SP42-04 | Define ordinary, UTF-8, wide, and UTF-16/32 literal boundaries | OPEN | Encoding/rune materialization fixtures |
| SP42-05 | Preserve source maps for width, encoding, and conversion errors | OPEN | Mapped diagnostic assertions |

### Existing bounded evidence

Checked signed-64 literals/operators, short-circuiting, division boundaries, shifts, and selected string interpolation are implemented. They intentionally do not define the complete C ABI or encoding model.

### Definition of done

The evaluator and specification agree on type width, promotion, conversion, and encoding behavior for every supported target; unsupported combinations are diagnosed rather than silently coerced.

## SP-4.3 — normative and differential corpus

### Tasks

| ID | Task | Status | Evidence |
|---|---|---:|---|
| SP43-01 | Extract every normative comptime example from language/compiler specs | OPEN | Versioned inventory with source links |
| SP43-02 | Add expected C-plus materialization and generated-C outputs | OPEN | Golden/materialization fixtures |
| SP43-03 | Add malformed, unsupported, cycle, limit, and source-map boundaries | OPEN | Negative fixtures with exact spans |
| SP43-04 | Freeze the finite legacy-overlap corpus and normalization rules | OPEN | Corpus manifest and comparison utility |
| SP43-05 | Compare output, options, runtime status, and diagnostics where both paths accept | OPEN | Differential report |
| SP43-06 | Record intentional AST-only syntax and retire no case silently | OPEN | Compatibility decision log |

### Definition of done

Every documented supported form has an expected result; every documented unsupported boundary has a stable diagnostic; the overlap corpus is finite and versioned; legacy differences are either resolved or explicitly classified.

## SP-4.4 — AST comptime migration

### Tasks

| ID | Task | Status | Evidence |
|---|---|---:|---|
| SP44-01 | Inventory scanner/evaluator entry points by feature and pass | OPEN | Checked migration inventory |
| SP44-02 | Move imports, OS conditions, flags, and scalar declarations to AST input | OPEN | AST-only fixtures and fixed-point traces |
| SP44-03 | Move entity/code materialization and generated imports to AST input | OPEN | Dependency/source-map/compile-run fixtures |
| SP44-04 | Preserve no-progress, recursion, output, and expansion limits | OPEN | Failure matrix with mapped origins |
| SP44-05 | Keep an explicit legacy fallback and compare the overlap corpus | OPEN | CLI/backend selection tests |
| SP44-06 | Remove scanner entry points only after the Phase 4 gate closes | OPEN | Full suite, rollback test, and documentation update |

### Definition of done

The AST evaluator handles the complete normative comptime subset, the legacy evaluator remains selectable until the migration gate closes, and no scanner is deleted merely because a happy-path fixture passes.

## SP-5.1 — declarators and comptime scope

### Tasks

| ID | Task | Status | Evidence |
|---|---|---:|---|
| SP51-01 | Retain qualifiers/restrict aliases and ordinary nested callback metadata | DONE | Qualified-alias and callback tests |
| SP51-02 | Normalize attributes and calling-convention declarators | OPEN | AST shape and C compile fixtures |
| SP51-03 | Cover nested pointer/array/function binding order | OPEN | Semantic index and receiver tests |
| SP51-04 | Resolve comptime names through lexical scopes and generated declarations | OPEN | Scope/collision/materialization tests |
| SP51-05 | Diagnose cycles, ambiguity, and unsupported declarator shapes at mapped spans | OPEN | Negative semantic fixtures |

### Definition of done

The semantic model preserves the complete binding shape needed by all consuming passes, scopes generated and runtime symbols correctly, and rejects unsupported forms without text-based guesses.

## SP-5.2 — compiler and ABI matrix

### Tasks

| ID | Task | Status | Evidence |
|---|---|---:|---|
| SP52-01 | Run accepted C fixtures through locally available `cc`, `gcc`, and `clang` | DONE | Local compile/run report |
| SP52-02 | Define shipped host/target compiler and ABI combinations | OPEN | Supported-target matrix in compiler docs |
| SP52-03 | Verify platform headers, attributes, calling conventions, and flags | OPEN | Target fixtures and collected diagnostics |
| SP52-04 | Normalize compiler diagnostics while preserving original source maps | OPEN | Cross-driver diagnostic tests |
| SP52-05 | Execute the matrix in CI and attach results to the Phase 5 gate | OPEN | Workflow artifacts |

### Definition of done

The supported matrix is explicit rather than inferred from available local binaries, and every declared target has compile/run and diagnostic evidence or is explicitly unsupported.

## SP-6.1 — expression sequencing and side effects

### Tasks

| ID | Task | Status | Evidence |
|---|---|---:|---|
| SP61-01 | Classify sequencing guarantees for comma, conditional, and short-circuit expressions | OPEN | Semantic rules and fixtures |
| SP61-02 | Preserve ordered side effects through nested expressions | OPEN | Allocation-flow tests |
| SP61-03 | Model unsequenced operands conservatively without inventing order | OPEN | Invalidated-provenance tests |
| SP61-04 | Cover calls, assignments, subscripts, initializers, loops, and switches | OPEN | Flow join matrix |
| SP61-05 | Compile/run the accepted subset with host C compilers | OPEN | Runtime C fixtures |

### Definition of done

The analyzer never turns an unspecified C evaluation order into a guaranteed order, preserves known sequencing where the language guarantees it, and reports conservative results with mapped spans.

## SP-6.2 — migration differential

### Tasks

| ID | Task | Status | Evidence |
|---|---|---:|---|
| SP62-01 | Define overlap fixtures for methods, receivers, defer, try/catch, tests, and allocation | OPEN | Versioned corpus manifest |
| SP62-02 | Compare normalized/generated output and compiler options | OPEN | Canonical comparison report |
| SP62-03 | Compare source-map origins, `#line`, and generated-to-source ranges | OPEN | Map comparison tests |
| SP62-04 | Compare diagnostics, failure ordering, and unsupported-node behavior | OPEN | Diagnostic comparison report |
| SP62-05 | Compare pass-order interactions and preserve legacy fallback | OPEN | End-to-end differential suite |

### Definition of done

Each pass has its own parity evidence and can be promoted or rolled back independently. The comparison normalizes irrelevant formatting but never hides semantic, option, map, or diagnostic differences.

## SP-6.3 — textual-lowerer retirement

### Tasks

| ID | Task | Status | Evidence |
|---|---|---:|---|
| SP63-01 | Close the method/receiver lowerer corpus gate and add a feature toggle | OPEN | AST/legacy compile/run and rollback tests |
| SP63-02 | Close the nested/early-return defer gate and add a feature toggle | OPEN | Ordering/control-flow/map tests |
| SP63-03 | Close the checked-call/try/catch gate and add a feature toggle | OPEN | Nested error-flow and diagnostic tests |
| SP63-04 | Close the AST test/assertion extraction gate and add a feature toggle | OPEN | Harness parity and fixture-span tests |
| SP63-05 | Close the allocation semantic-flow gate and add a feature toggle | OPEN | Provenance/side-effect parity tests |
| SP63-06 | Remove each textual implementation only after its independent rollback window | OPEN | Release notes and migration audit |

### Definition of done

No textual lowerer is removed because another pass happens to cover it. Each lowerer has a named gate, a selectable fallback, parity evidence, and a documented removal decision.

## SP-6.4 — receiver and complex declarator coverage

### Tasks

| ID | Task | Status | Evidence |
|---|---|---:|---|
| SP64-01 | Resolve direct/typedef callback variables and explicit dereference calls | DONE | `resolvesMethodReceiversFromFunctionPointerTypedefVariables` |
| SP64-02 | Resolve direct/typedef callback parameters | DONE | `resolvesMethodReceiversFromFunctionPointerTypedefParameters` and direct-parameter fixture |
| SP64-03 | Resolve inline and typedef callback fields without relowering ordinary C calls | DONE | Function-pointer field fixtures and bridge test |
| SP64-04 | Resolve nested callable returns through arrays, fields, and chained calls | DONE | Semantic/lowering/C11 runtime fixture |
| SP64-05-A | Preserve GNU attributes on complex function-pointer-return methods | DONE | `lowersMethodsReturningFunctionPointersWithoutFlatteningTheirDeclarator` preserves `__attribute__((noinline))` and compiles/runs with C11 drivers |
| SP64-05 | Preserve calling conventions and remaining complex method/function declarators | OPEN | Declarator-shape, calling-convention, and emitted-header fixtures |
| SP64-06 | Verify invalid operators/shapes retain exact mapped diagnostics | DONE | Receiver-access and array-shape diagnostics |

### Definition of done

All supported receiver expressions have a resolved type and exact C lowering; ordinary C callable expressions remain ordinary C; unsupported or ambiguous shapes fail closed with original source spans.

## Follow-up log

| Date | Change | Evidence | Effect on plan |
|---|---|---|---|
| 2026-09-27 | Added the finite parent-case and subcase ledgers | `COMPILER-FRONTEND-IMPLEMENTATION.md` | Replaced vague Phase 2/4–6 prose with trackable gates |
| 2026-09-27 | Replaced the uncalibrated engineer-day estimate with relative points and measurement rules | Implementation follow-up and user-reported elapsed-time baseline | No absolute forecast is claimed until three subcases are timed |
| 2026-09-27 | Fixed callable result-layer derivation for typedef variables | Focused typedef-variable test and full `:parser-tree-sitter:jvmTest` | SP-6.4-01 is complete; P6-12 remains in progress |
| 2026-09-27 | Applied callable result-layer derivation to parameters | Focused typedef/direct callback parameter tests | SP-6.4-02 is complete; next is SP-6.4-04 |
| 2026-09-27 | Propagated callable result layers through function-return metadata | `resolvesMethodReceiversThroughNestedFunctionPointerReturns` compiles and executes; focused JVM test passes | SP-6.4-04 is in progress; arrays and field/chained variants remain |
| 2026-09-27 | Prevented nested method calls from being resolved twice and covered callable field arrays/chained returns | `resolvesMethodReceiversThroughCallableFieldsArraysAndChainedReturns` compiles and executes; full parser suite remains green | SP-6.4-04 is complete; next is SP-6.4-05 |
| 2026-09-27 | Relocated declarator-owned GNU attributes before extracted function declarators and retained ABI qualifiers in the semantic model | `lowersMethodsReturningFunctionPointersWithoutFlatteningTheirDeclarator` and `retainsCallingConventionQualifiersOnComplexMethodDeclarators` preserve `__attribute__` spellings, expose source-spanned `CPlusDeclaratorQualifier` metadata, and compile/execute through the focused JVM tests | SP-6.4-05-A is complete; target-specific validation and less-common declarator shapes remain open |
| 2026-09-27 | Verified the qualifier model against the repository test gates | `./gradlew :parser-tree-sitter:jvmTest --no-daemon --max-workers=1` and `./gradlew test --no-daemon --max-workers=1` pass; the grammar gate reports 96/96 parses | No parent gate closes; SP-6.4 remains active for target-specific and uncommon declarator coverage |

### Next update protocol

For each next task: record the source fixture, implementation commit/change, exact Gradle/CLI command, result, elapsed engineering time, and whether the parent acceptance condition changed. Then update both this log and the parent row in `COMPILER-FRONTEND-IMPLEMENTATION.md`.
