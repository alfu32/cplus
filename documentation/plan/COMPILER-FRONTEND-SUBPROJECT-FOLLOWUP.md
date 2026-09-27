# Compiler Frontend Migration — Subproject Follow-up

This file turns the open rows in [`COMPILER-FRONTEND-IMPLEMENTATION.md`](COMPILER-FRONTEND-IMPLEMENTATION.md) into executable subprojects. The legacy frontend remains the production path until the relevant migration gates close. A subproject is complete only when its listed acceptance evidence exists; implementation code alone is not completion evidence.

## Tracking rules

- `DONE` means the named task has passing repository evidence.
- `IN PROGRESS` means implementation or evidence is active but incomplete.
- `OPEN` means planned work has not started or lacks acceptance evidence.
- `EXTERNAL` means the repository is configured but a supported CI host or release environment must provide the evidence.
- New edge cases stay under the nearest task unless they introduce a new invariant. That rule keeps the scope finite.
- Every completed task records its command, fixture, source-map/diagnostic expectation where applicable, and date in the follow-up log.

## Subproject charter

This is a bounded migration subproject, not an attempt to enumerate every possible C program. Its objective is to make the Tree-sitter frontend promotion-ready while the legacy frontend remains a selectable rollback path.

### In scope

- A versioned grammar and normalized AST contract for the documented C-plus subset.
- Deterministic comptime expansion, semantic resolution, mapped C emission, and fixed-point/import behavior.
- Receiver/declarator coverage, C ABI boundaries, compiler compatibility, and legacy differential evidence.
- Repository fixtures, generated-C compile/run checks, source-map diagnostics, CI evidence, and phase documentation.

### Out of scope until separately specified

- Exhaustive Cartesian testing of all C syntax, types, targets, and pass combinations.
- Replacing the C compiler's type checker or defining unsupported C ABI behavior by guesswork.
- Removing the legacy frontend before its independent promotion and rollback gates close.
- IDE presentation polish that is unrelated to parser-contract correctness.

### Subproject exit criteria

The subproject closes only when every local `OPEN` row below is either `DONE` or explicitly classified as unsupported with a mapped diagnostic, every `OPEN-EXTERNAL` row has CI evidence, and the differential corpus shows no unexplained accepted-input, output, option, source-map, or diagnostic mismatch. Phase 8 editor work consumes the stable contract; it does not silently expand this compiler scope.

### Execution board

| Work package | Depends on | Current state | Exit artifact |
|---|---|---:|---|
| WP-A: grammar/runtime and packaged parser evidence | none | EXTERNAL tail | 100-case grammar gate plus six-host JNI/resource report |
| WP-B: comptime reflection and scalar semantics | WP-A contract | IN PROGRESS | ABI/encoding specification, positive/negative fixtures, mapped materialization |
| WP-C: declarators, scopes, and target compiler matrix | grammar + semantic model | IN PROGRESS | receiver/declarator matrix, target support table, compile/link evidence |
| WP-D: differential migration and lowerer retirement | WP-B + WP-C | OPEN | finite overlap report, per-pass feature toggles, rollback decision |

Work proceeds left to right, except WP-A's external host jobs, which may run in parallel. A work package may report `DONE` only from its exit artifact; “implemented” is not a completion state.

## Numbered task breakdown

These are the subproject-level tasks used for progress reporting. The detailed acceptance units below are the work items that close them; a parent task does not close because one happy-path fixture passes.

| Task | Work package | Dependency | Status | Completion condition |
|---|---|---|---:|---|
| FE-SUB-01 | Freeze the parser contract and local baseline | none | DONE | Versioned grammar, normalized AST contract, 100/100 local grammar gate, and reproducible generated artifacts |
| FE-SUB-02 | Close packaged parser evidence | FE-SUB-01 | EXTERNAL | Six-host JNI/resource smoke tests, manifests, checksums, and size report pass in CI |
| FE-SUB-03 | Finish bounded comptime reflection | FE-SUB-01 | IN PROGRESS | Structured annotations and iterable rules are specified, materialized, bounded, and mapped; unsupported forms fail closed |
| FE-SUB-04 | Define typed scalar/ABI semantics | FE-SUB-03 | OPEN | Integer widths, promotions, conversions, suffixes, and encoding boundaries have one target-aware contract and tests |
| FE-SUB-05 | Finish declarator and scope resolution | FE-SUB-01 | IN PROGRESS | Complex declarators, calling conventions, lexical comptime scope, and mapped ambiguity/cycle diagnostics are covered |
| FE-SUB-06 | Establish the supported compiler/ABI matrix | FE-SUB-05 | OPEN | Declared host/target combinations compile, link, run, or are explicitly unsupported with diagnostics |
| FE-SUB-07 | Build the finite differential corpus | FE-SUB-03, FE-SUB-05 | OPEN | Accepted overlap compares output, options, runtime, maps, and diagnostics; AST-only forms have normative fixtures |
| FE-SUB-08 | Retire textual lowerers one at a time | FE-SUB-07 | OPEN | Each lowerer has an independent toggle, parity gate, rollback window, and removal decision |

### Immediate execution queue

Work on one queue item at a time unless it is marked external. Update this queue after every completed acceptance unit.

1. `P6-12-E`: finish the remaining complex declarator cases and target-specific evidence.
2. `P4-15-B`: specify and test typed integer widths, promotions, and comparison results.
3. `P4-14-D` / `P4-14-E`: implement structured annotation values and the bounded iterable protocol.
4. `P4-16-A` through `P4-16-D`: freeze the normative and legacy-overlap corpus.
5. `P6-09`, `P6-10`, and `P6-11`: establish sequencing parity, differential reports, and independent lowerer retirement.

Do not add a new queue item for a spelling variant. Add it under the nearest acceptance unit unless it introduces a new semantic invariant, evidence class, or source-map rule.

### Follow-up record format

Every update records: date, task/subcase ID, source fixture or implementation area, exact verification command, result, active execution time, and the next dependency. Test runtime and CI queue time are recorded separately from implementation time. A status changes to `DONE` only when the named evidence is reproducible; otherwise it remains `IN PROGRESS`, `OPEN`, or `EXTERNAL`.

## Portfolio follow-up

| Subproject | Parent rows | Status | Next task | Exit evidence |
|---|---|---:|---|---|
| SP-2 packaged parser matrix | P2-07, P2-08 | EXTERNAL | Run the six-host JNI/resource jobs | Six host logs, manifests, checksums, and size report |
| SP-4.1 aggregate comptime reflection | P4-14 | IN PROGRESS | Define structured annotations and iterable boundaries | Shared ABI layout model, materialization, mapped failures, and host C compile/run |
| SP-4.2 scalar ABI and encodings | P4-15 | OPEN | Specify width/promotion rules | Target-width fixtures and encoding boundary diagnostics |
| SP-4.3 normative and differential corpus | P4-16 | OPEN | Inventory versioned specification examples | Frozen expected-output/diagnostic corpus and comparison report |
| SP-4.4 AST comptime migration | P4-17 | OPEN | Inventory scanner entry points | AST/legacy selection report and fixed-point parity |
| SP-5.1 declarators and comptime scope | P5-10 | IN PROGRESS | Add nested qualified declarator cases | Binding-shape index, scope diagnostics, and C11 fixtures |
| SP-5.2 compiler/ABI matrix | P5-11 | OPEN | Define supported driver/target matrix | Cross-driver compile/run and diagnostic report |
| SP-6.1 expression sequencing | P6-09 | OPEN | Classify sequencing guarantees | Conservative flow fixtures and host C behavior |
| SP-6.2 migration differential | P6-10 | OPEN | Define per-pass overlap records | Output/options/maps/diagnostics comparison report |
| SP-6.3 textual-lowerer retirement | P6-11 | OPEN | Close the method lowerer corpus gate | Independent rollback-safe retirement of each lowerer |
| SP-6.4 receiver/declarator coverage | P6-12 | IN PROGRESS | Cover remaining complex declarators and target validation | Complete receiver-shape matrix with mapped failures |

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
| SP41-01 | Specify aggregate field order, alignment, padding, and offset semantics for the supported standard-layout subset | DONE | `SPEC.comptime.md`; nested/padded layout fixture |
| SP41-02 | Expose aggregate `size`, `align`, and field offsets through one shared target ABI model | DONE | `TreeSitterAbiLayout.kt`; scalar and field-loop materialization tests |
| SP41-03 | Materialize structured annotation values with deterministic member access | OPEN | Positive and malformed annotation fixtures |
| SP41-04 | Define the iterable protocol for reflected collections and arbitrary expressions | OPEN | Evaluator tests and explicit unsupported diagnostics |
| SP41-05 | Apply total-expansion and nested-iteration limits to all new collections | DONE | Existing nested reflected-loop limit/source-map fixtures |
| SP41-06 | Compile and run generated aggregates with padding-sensitive assertions | DONE | Aggregate size/alignment and field layout C11 compile/run fixtures |
| SP41-07 | Reject unsupported aggregate layouts and reflected iterable shapes with original spans | DONE | `prototypeRejectsUnsupportedAggregateLayoutShapesWithMappedDiagnostics`, packed/unknown-target, unsupported-iterable, and expansion-limit fixtures |

### Existing bounded evidence

Primitive/pointer/fixed-array reflection, field/annotation iteration, bitfields, widths, mapped field origins, and the standard-layout aggregate subset are covered. The shared ABI model handles scalar, pointer, nested-struct, and fixed-array fields on x86_64/arm64 Linux, Windows, and macOS targets. Packed/aligned layouts, unions, bitfields, flexible arrays, incomplete fields, structured annotations, and arbitrary iterable expressions remain outside this subproject's completed boundary.

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
| SP64-05-B | Accept and retain Microsoft calling-convention modifiers on C-plus methods | DONE | `cplus-methods.txt`, `retainsMicrosoftCallingConventionModifiersOnMethods`, semantic `ms_call_modifier` metadata, and emitted `__stdcall` spelling |
| SP64-05-C | Retain Microsoft calling-convention modifiers inside callable method declarators | DONE | `cplus-methods.txt`, `retainsMicrosoftCallingConventionModifiersOnCallableMethodDeclarators`, nested function/pointer layer assertions, emitted `__stdcall` spelling, and optional `x86_64-w64-mingw32-gcc` object compilation |
| SP64-05-D | Retain Microsoft declaration specifiers on C-plus methods | DONE | `cplus-methods.txt`, `retainsMicrosoftDeclarationSpecifiersOnMethods`, declaration-side qualifier collection, emitted `__declspec(noinline)`, and optional MinGW object compilation |
| SP64-05-E | Retain every grammar-supported Microsoft calling-convention spelling | DONE | `retainsAllMicrosoftCallingConventionSpellingsOnCPlusMethods` covers `__cdecl`, `__clrcall`, `__stdcall`, `__fastcall`, `__thiscall`, `__vectorcall`, and `WINAPI` |
| SP64-05-F | Propagate calling-convention qualifiers into nested callback parameter symbols | DONE | `retainsMicrosoftCallingConventionOnNestedCallbackParameters`, callback function signature metadata, emitted `__stdcall`, and optional MinGW object compilation |
| SP64-05-G | Preserve Microsoft pointer-layer modifiers on method parameters and nested callback declarators | DONE | `retainsMicrosoftPointerModifiersOnMethodAndNestedCallbackDeclarators`, emitted `__restrict`, and host C11 compile/run |
| SP64-05-H | Preserve direct pointer-return layers on compact C-plus method declarators | DONE | `retainsDirectPointerReturnLayersOnCPlusMethodSymbols`, semantic `[POINTER, FUNCTION]` metadata, emitted pointer return, and host C11 compile/run |
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
| 2026-09-27 | Prevented nested method calls from being resolved twice and covered callable field arrays/chained returns | `resolvesMethodReceiversThroughCallableFieldsArraysAndChainedReturns` compiles and executes; full parser suite remains green | SP-6.4-04 is complete; next is SP-6.4-05 |
| 2026-09-27 | Relocated declarator-owned GNU attributes before extracted function declarators and retained ABI qualifiers in the semantic model | `lowersMethodsReturningFunctionPointersWithoutFlatteningTheirDeclarator` and `retainsCallingConventionQualifiersOnComplexMethodDeclarators` preserve `__attribute__` spellings, expose source-spanned `CPlusDeclaratorQualifier` metadata, and compile/execute through the focused JVM tests | SP-6.4-05-A is complete; target-specific validation and less-common declarator shapes remain open |
| 2026-09-27 | Verified the qualifier model against the repository test gates | `./gradlew :parser-tree-sitter:jvmTest --no-daemon --max-workers=1` and `./gradlew test --no-daemon --max-workers=1` pass; the grammar gate reports 97/97 parses | No parent gate closes; SP-6.4 remains active for target-specific and uncommon declarator coverage |
| 2026-09-27 | Implemented the bounded aggregate layout slice and unified reflection consumers | `TreeSitterAbiLayout.kt` computes scalar, pointer, nested-struct, fixed-array, padding, and final-alignment metadata; `prototypeReflectsAggregateLayoutWithPaddingAndNestedFields`, `prototypeReflectsFieldOffsetsSizesAndAlignmentInFieldLoops`, and `prototypeRejectsPackedAggregateLayoutWithoutGuessing` pass | SP-4.1-01, SP-4.1-02, SP-4.1-05, and SP-4.1-06 are complete; SP-4.1 remains active for structured annotations and iterable semantics; P4-14-C is in progress |
| 2026-09-27 | Closed the supported aggregate ABI-layout case across target models | `prototypeReflectsAggregateLayoutAcrossSupportedTargetAbis` verifies Linux x86_64/arm64, macOS arm64, and Windows x86_64 size/alignment materialization; Linux x86_64 generated C compiles and runs | P4-14-C is DONE for scalar, pointer, nested-struct, fixed-array, padding, and target-dependent `long` layouts; unsupported packed/aligned, union, bitfield, flexible, and incomplete layouts remain fail-closed |
| 2026-09-27 | Added Microsoft calling-convention support to C-plus method declarators | `cplus-methods.txt` and `retainsMicrosoftCallingConventionModifiersOnMethods` pass; semantic metadata retains `ms_call_modifier` and emitted output retains `__stdcall` | SP-6.4-05-B is complete for parsing, semantic retention, and emission; Windows compiler/link validation remains open under the parent SP-6.4-05 gate |
| 2026-09-27 | Re-ran parser gates after the grammar extension | `./gradlew :parser-tree-sitter:testTreeSitterGrammar --no-daemon --max-workers=1` passes with 97/97 parses; focused JVM coverage also passes | The grammar change is regression-safe; the parent receiver/declarator subproject remains active for target compiler validation and uncommon declarators |
| 2026-09-27 | Added nested callable declarator coverage for Microsoft calling conventions | `cplus-methods.txt` and `retainsMicrosoftCallingConventionModifiersOnCallableMethodDeclarators` pass; nested `FUNCTION`/`POINTER`/`FUNCTION` layers and `ms_call_modifier` spelling are retained; when installed, `x86_64-w64-mingw32-gcc` compiles the emitted C to an object | SP-6.4-05-C is complete; the parent remains open for target compiler/link evidence and further unsupported complex forms |
| 2026-09-27 | Revalidated the full migration gates after the nested declarator increment | `./gradlew :parser-tree-sitter:jvmTest --no-daemon --max-workers=1` and `./gradlew test --no-daemon --max-workers=1` pass; the grammar gate reports 98/98 parses and both highlighting assertions remain green | The new subcase is regression-safe; no parent gate closes because six-host packaging, target compiler validation, broader ABI/comptime semantics, and differential retirement remain open |
| 2026-09-27 | Preserved Microsoft declaration-side qualifiers in semantic symbols | `retainsMicrosoftDeclarationSpecifiersOnMethods` passes; `ms_declspec_modifier` is retained from declaration specifiers, emitted `__declspec(noinline)` is checked, and optional MinGW object compilation is exercised | SP-6.4-05-D is complete; the parent remains open for broader target compiler/link validation and remaining complex declarators |
| 2026-09-27 | Re-ran the grammar gate after the declaration-specifier corpus fixture | `./gradlew :parser-tree-sitter:testTreeSitterGrammar --no-daemon --max-workers=1` passes with 99/99 parses and both highlighting assertions green | Current local grammar evidence is 99/99; packaged six-host parser evidence remains external |
| 2026-09-27 | Revalidated the repository after declaration-side qualifier collection changed the semantic model | `./gradlew test --no-daemon --max-workers=1` passes, including compiler, CLI, parser JVM, grammar, and highlighting gates; the grammar gate reports 99/99 parses | The qualifier increment is regression-safe; no migration parent closes because promotion, external hosts, broader semantics, differential parity, and retirement remain open |
| 2026-09-27 | Covered every grammar-supported Microsoft calling-convention spelling | `retainsAllMicrosoftCallingConventionSpellingsOnCPlusMethods` passes semantic retention and emitted-spelling assertions for all seven `ms_call_modifier` values | SP-6.4-05-E is complete; compiler-specific acceptance/link behavior remains under the parent target-validation gate |
| 2026-09-27 | Propagated declaration-side calling-convention qualifiers through nested callback signatures | `retainsMicrosoftCallingConventionOnNestedCallbackParameters` passes callback return/parameter metadata, `ms_call_modifier` retention, emitted spelling, and optional MinGW object compilation | SP-6.4-05-F is complete; the parent remains open for broader target/compiler and less-common declarator evidence |
| 2026-09-27 | Revalidated the full repository after nested callback qualifier propagation | `./gradlew test --no-daemon --max-workers=1` passes with 99/99 grammar parses and green compiler, CLI, parser JVM, and highlighting checks | The nested callback increment is regression-safe; P5/P6 parent declarator gates remain open only for broader C compatibility and target evidence |
| 2026-09-27 | Closed the unsupported aggregate-layout and expansion-boundary evidence case | `prototypeRejectsUnsupportedAggregateLayoutShapesWithMappedDiagnostics` covers unions, bitfields, flexible arrays, and incomplete fields with `CPLUS_COMPTIME_REFLECTION_LAYOUT` mapped to `T.size`; existing iterable and 65,536-item limit fixtures remain green | P4-14-F is DONE; SP-4.1 remains active for structured annotation values and arbitrary iterable semantics |
| 2026-09-27 | Revalidated the full repository after the Microsoft spelling matrix | `./gradlew test --no-daemon --max-workers=1` passes; parser/JVM, compiler, CLI, grammar, and highlighting checks are green with 99/99 grammar parses | The local declarator increment is complete; P6-12-E remains open only for the broader target/compiler and less-common declarator gate |
| 2026-09-27 | Revalidated the complete migration test gates after the aggregate/reflection updates | `./gradlew :parser-tree-sitter:jvmTest --no-daemon --max-workers=1` and `./gradlew test --no-daemon --max-workers=1` pass; Tree-sitter reports 99/99 parses and the grammar/highlighting checks are green | The aggregate slice is regression-safe; no Phase 4/5 parent row closes because structured annotations, iterable semantics, broader ABI rules, and target compiler validation remain open |
| 2026-09-27 | Added the numbered frontend-migration subproject board and immediate execution queue | Documentation review plus `git diff --check` | The open frontend work now has eight trackable subproject tasks, explicit dependencies, completion conditions, and a fixed next-work order; no implementation status was advanced by documentation alone |
| 2026-09-27 | Re-ran the complete repository test gate after the subproject-board update | `./gradlew test --no-daemon --max-workers=1` passes in 2m22s; all 99 grammar parses and both syntax-highlighting assertion groups pass | Documentation and the existing implementation work remain regression-safe; the next queue item is still P6-12-E |
| 2026-09-27 | Preserved Microsoft pointer-layer modifiers in declarator metadata | `cplus-methods.txt` and `retainsMicrosoftPointerModifiersOnMethodAndNestedCallbackDeclarators` pass; `__restrict` is retained on a method parameter and nested callback declarator, and the emitted C compiles/runs with host C11 drivers | SP-6.4-05-G is DONE; the parent P6-12-E/SP64-05 gate remains open for other complex declarators and target-specific validation |
| 2026-09-27 | Revalidated the grammar and focused JVM gates after the pointer-layer increment | `gradle :parser-tree-sitter:testTreeSitterGrammar --no-daemon --max-workers=1` passes with 100/100 parses; the focused JVM test passes | The new declarator slice is regression-safe; the next queue item remains P6-12-E's remaining target/declarator evidence |
| 2026-09-27 | Revalidated the complete repository with the project wrapper | `./gradlew test --no-daemon --max-workers=1` passes in 2m30s; compiler, CLI, parser JVM, grammar, and highlighting gates are green with 100/100 grammar parses | The pointer-layer increment is regression-safe; no parent migration gate closes |
| 2026-09-27 | Retained direct pointer-return layers on the compact C-plus method grammar branch | `retainsDirectPointerReturnLayersOnCPlusMethodSymbols` verifies semantic `[POINTER, FUNCTION]` binding metadata, emitted pointer-return syntax, and host C11 compile/run; the focused JVM gate passes | SP-6.4-05-H is DONE; P6-12-E remains open for other complex declarators and target-specific validation |

### Next update protocol

For each next task: record the source fixture, implementation commit/change, exact Gradle/CLI command, result, elapsed engineering time, and whether the parent acceptance condition changed. Then update both this log and the parent row in `COMPILER-FRONTEND-IMPLEMENTATION.md`.
