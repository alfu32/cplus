# Compiler Frontend Migration — Subproject Follow-up

This file turns the open rows in [`COMPILER-FRONTEND-IMPLEMENTATION.md`](COMPILER-FRONTEND-IMPLEMENTATION.md) into executable subprojects. The legacy frontend remains the production path until the relevant migration gates close. A subproject is complete only when its listed acceptance evidence exists; implementation code alone is not completion evidence.

The active remaining-work breakdown is maintained separately in [`COMPILER-FRONTEND-CLOSURE-SUBPROJECT.md`](COMPILER-FRONTEND-CLOSURE-SUBPROJECT.md). That file is the execution board for the current open-task subproject; this file retains the phase-level portfolio, historical evidence, and migration follow-up log.

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
| WP-A: grammar/runtime and packaged parser evidence | none | EXTERNAL tail | 104-case grammar gate plus six-host JNI/resource report |
| WP-B: comptime reflection and scalar semantics | WP-A contract | IN PROGRESS | ABI/encoding specification, positive/negative fixtures, mapped materialization |
| WP-C: declarators, scopes, and target compiler matrix | grammar + semantic model | IN PROGRESS | receiver/declarator matrix, target support table, compile/link evidence |
| WP-D: differential migration and lowerer retirement | WP-B + WP-C | OPEN | finite overlap report, per-pass feature toggles, rollback decision |

Work proceeds left to right, except WP-A's external host jobs, which may run in parallel. A work package may report `DONE` only from its exit artifact; “implemented” is not a completion state.

## Numbered task breakdown

These are the subproject-level tasks used for progress reporting. The detailed acceptance units below are the work items that close them; a parent task does not close because one happy-path fixture passes.

| Task | Work package | Dependency | Status | Completion condition |
|---|---|---|---:|---|
| FE-SUB-01 | Freeze the parser contract and local baseline | none | DONE | Versioned grammar, normalized AST contract, 104/104 focused grammar gate, 62/62 live-source gate with an exact 36-node inventory, and reproducible generated artifacts |
| FE-SUB-02 | Close packaged parser evidence | FE-SUB-01 | EXTERNAL | Six-host JNI/resource smoke tests, manifests, checksums, and size report pass in CI |
| FE-SUB-03 | Finish bounded comptime reflection | FE-SUB-01 | DONE | Structured annotations and iterable rules are specified, materialized, bounded, and mapped; unsupported forms fail closed |
| FE-SUB-04 | Define typed scalar/ABI semantics | FE-SUB-03 | IN PROGRESS | Integer widths, promotions, conversions, suffixes, and encoding boundaries have one target-aware contract and tests |
| FE-SUB-05 | Finish declarator and scope resolution | FE-SUB-01 | IN PROGRESS | Complex declarators, calling conventions, lexical comptime scope, and mapped ambiguity/cycle diagnostics are covered |
| FE-SUB-06 | Establish the supported compiler/ABI matrix | FE-SUB-05 | OPEN | Declared host/target combinations compile, link, run, or are explicitly unsupported with diagnostics |
| FE-SUB-07 | Build the finite differential corpus | FE-SUB-03, FE-SUB-05 | OPEN | Accepted overlap compares output, options, runtime, maps, and diagnostics; AST-only forms have normative fixtures |
| FE-SUB-08 | Retire textual lowerers one at a time | FE-SUB-07 | OPEN | Each lowerer has an independent toggle, parity gate, rollback window, and removal decision |

### Immediate execution queue

Work on one queue item at a time unless it is marked external. Update this queue after every completed acceptance unit.

1. `P6-12-E`: finish the remaining complex declarator cases and target-specific evidence.
2. `P4-15-B` / `P4-15-C`: continue the typed integer model with casts, parameter conversions, and the remaining literal/ABI boundaries after the completed declaration, promotion, and shift slices.
3. `P5-10-D` / `P5-10-E`: close comptime lexical scope and mapped ambiguity/cycle diagnostics.
4. `P4-16-A` through `P4-16-D`: freeze the normative and legacy-overlap corpus.
5. `P6-09`, `P6-10`, and `P6-11`: establish sequencing parity, differential reports, and independent lowerer retirement.

Do not add a new queue item for a spelling variant. Add it under the nearest acceptance unit unless it introduces a new semantic invariant, evidence class, or source-map rule.

### Follow-up record format

Every update records: date, task/subcase ID, source fixture or implementation area, exact verification command, result, active execution time, and the next dependency. Test runtime and CI queue time are recorded separately from implementation time. A status changes to `DONE` only when the named evidence is reproducible; otherwise it remains `IN PROGRESS`, `OPEN`, or `EXTERNAL`.

## Portfolio follow-up

| Subproject | Parent rows | Status | Next task | Exit evidence |
|---|---|---:|---|---|
| SP-2 packaged parser matrix | P2-07, P2-08 | EXTERNAL | Run the six-host JNI/resource jobs | Six host logs, manifests, checksums, and size report |
| SP-4.1 aggregate comptime reflection | P4-14 | DONE | Maintain the frozen bounded reflection contract | Shared ABI layout model, structured annotation materialization, bounded collections, mapped failures, and host C compile/run |
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
| SP41-03 | Materialize structured annotation values with deterministic member access | DONE | `annotation.name`, annotation-token source origins, and mapped unknown-member diagnostics |
| SP41-04 | Define the iterable protocol for reflected collections and arbitrary expressions | DONE | Only type `.fields` and field `.annotations` are iterable; scalar/arbitrary forms fail mapped and all expansion limits apply |
| SP41-05 | Apply total-expansion and nested-iteration limits to all new collections | DONE | Existing nested reflected-loop limit/source-map fixtures |
| SP41-06 | Compile and run generated aggregates with padding-sensitive assertions | DONE | Aggregate size/alignment and field layout C11 compile/run fixtures |
| SP41-07 | Reject unsupported aggregate layouts and reflected iterable shapes with original spans | DONE | `prototypeRejectsUnsupportedAggregateLayoutShapesWithMappedDiagnostics`, packed/unknown-target, unsupported-iterable, and expansion-limit fixtures |

### Existing bounded evidence

Primitive/pointer/fixed-array reflection, field/annotation iteration, bitfields, widths, mapped field origins, structured annotation names, and the standard-layout aggregate subset are covered. The shared ABI model handles scalar, pointer, nested-struct, and fixed-array fields on x86_64/arm64 Linux, Windows, and macOS targets. Packed/aligned layouts, unions, bitfields, flexible arrays, and incomplete fields remain unsupported. Arbitrary iterable expressions are outside the language-version contract and fail at their iterable span; they are not unfinished implicit behavior.

### Definition of done

The specification defines the layout contract; every supported aggregate value has materialization and C compile/run evidence; unsupported layouts and annotation forms fail at their original `.cp` spans; no arbitrary iterable can bypass the expansion budget.

## SP-4.2 — scalar ABI and encodings

### Tasks

| ID | Task | Status | Evidence |
|---|---|---:|---|
| SP42-01 | Define the comptime integer model and its mapping to C integer types | IN PROGRESS | Declaration conversion is implemented for fixed-width signed/unsigned types, `_Bool` normalization, and target-dependent `long`; mixed expressions remain open |
| SP42-01-A | Apply target-aware width conversion to module-scope comptime integer declarations | DONE | `prototypeConvertsComptimeIntegerDeclarationsUsingTargetWidths`, `prototypeReportsSignedComptimeIntegerWidthOverflowAtTheDeclaration`, `SPEC.comptime.md`, and mapped `CPLUS_COMPTIME_SCALAR_WIDTH` diagnostics |
| SP42-02 | Implement C promotions, conversions, comparison result types, and casts | IN PROGRESS | Comparison conversion is implemented for the bounded integer subset; arithmetic result typing, casts, and parameter conversions remain |
| SP42-02-A | Apply usual integer conversions to comptime comparisons | DONE | `prototypeUsesCUsualIntegerConversionsForComptimeComparisons` covers unsigned/signed comparison, small-integer promotion, result materialization, and C11 compile/run |
| SP42-02-B | Apply common integer types to bounded comptime arithmetic | DONE | `prototypeUsesCommonIntegerTypeForComptimeArithmetic` covers unsigned modulo results and promoted small-integer addition; `prototypeReportsComptimeArithmeticOverflowForTheCommonSignedType` covers mapped signed overflow |
| SP42-02-C | Select target-aware C integer literal types by radix and suffix | DONE | `prototypeSelectsCIntegerLiteralTypesForRadixAndSuffix` covers decimal widening, hexadecimal unsigned selection, `u` suffix modulo, and mapped materialization |
| SP42-02-D | Apply promotions and target width to unary operators and shifts | DONE | `prototypeSelectsCIntegerLiteralTypesForRadixAndSuffix` covers unsigned negation/complement and 32-bit unsigned shifts; existing signed-shift rejection fixtures remain green |
| SP42-02-E | Apply target-aware integer casts and reject non-integer casts | DONE | `prototypeAppliesTargetAwareIntegerCasts` covers unsigned modulo, signed conversion, and boolean normalization; `prototypeRejectsUnsupportedComptimeScalarCastsWithMappedDiagnostics` covers pointer-cast rejection |
| SP42-02-F | Convert scalar comptime function parameters and results using declared integer types | DONE | `prototypeConvertsScalarComptimeFunctionParametersAndReturns` covers narrowing parameter conversion, unsigned conversion, and boolean result conversion; out-of-range signed conversion has mapped diagnostics |
| SP42-03-A | Select long-family literal suffixes using each target ABI's `long` width | DONE | `prototypeSelectsLongIntegerLiteralSuffixesPerTargetAbi` covers `L`, `UL`/`LU`, `LL`, and `ULL`/`LLU` on Linux/macOS x86_64/arm64 models and Windows x86_64 |
| SP42-03 | Verify width/suffix behavior across target ABIs | OPEN | Linux, macOS, and Windows target fixtures |
| SP42-04-A | Reject prefixed wide/UTF string literals with mapped encoding-boundary diagnostics | DONE | `prototypeRejectsUnsupportedPrefixedComptimeStringLiteralsAtTheirSourceSpans` covers `L`, `u8`, `u`, and `U` prefixes |
| SP42-04 | Define ordinary, UTF-8, wide, and UTF-16/32 literal boundaries | DONE | Ordinary text emits exact UTF-8 bytes; wide/UTF-prefixed forms are mapped-unsupported |
| SP42-05 | Preserve source maps for width, encoding, and conversion errors | DONE | Width, cast/conversion, overflow, escape, and prefixed-literal tests assert exact original spans |

### Existing bounded evidence

Checked signed-64 literals/operators, short-circuiting, division boundaries, shifts, and string interpolation are implemented. Ordinary comptime strings have a target-independent UTF-8 byte materialization; wide and UTF-16/32 values are explicitly unsupported. Wider unsigned integer representation and external ABI evidence remain open.

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
| SP52-02-A | Define and test the six shipped host/architecture target spellings | DONE | `CPlusTargetTest`; compiler/package target set and `SPEC.compiler.md` matrix |
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
| SP64-05-H | Preserve direct pointer-return layers on compact C-plus method declarators | DONE | `retainsDirectPointerReturnLayersOnCPlusMethodSymbols`, semantic `[POINTER, FUNCTION]` metadata, emitted pointer return, host C11 compile/run, and optional `x86_64-w64-mingw32-gcc` object compilation |
| SP64-05-I | Preserve pointer-to-array return layers on C-plus methods | DONE | `cplus-methods.txt`, `retainsPointerToArrayReturnLayersOnCPlusMethodSymbols`, semantic `[ARRAY, POINTER, FUNCTION]` metadata, emitted C, host C11 compile/run, and optional MinGW object compilation |
| SP64-05-J | Preserve pointer-to-array parameter layers on C-plus methods | DONE | `cplus-methods.txt`, `retainsPointerToArrayParameterLayersOnCPlusMethodSymbols`, semantic `[POINTER, ARRAY]` parameter metadata, emitted C, host C11 compile/run, and optional MinGW object compilation |
| SP64-05-K | Preserve qualified pointer-to-array parameter spelling and layers | DONE | `retainsPointerToArrayParameterLayersOnCPlusMethodSymbols` verifies `const`/`restrict` declaration spelling, `[POINTER, ARRAY]` layers, emitted C, host C11 compile/run, and optional MinGW object compilation |
| SP64-05-L | Preserve array-of-function-pointer parameter layers | DONE | `cplus-methods.txt`, `retainsArrayOfFunctionPointerParameterLayersOnCPlusMethodSymbols`, `[ARRAY, POINTER, FUNCTION]` metadata, callback signature metadata, emitted C, host C11 compile/run, and optional MinGW object compilation |
| SP64-05-M | Preserve multidimensional pointer-to-array return layers | DONE | `cplus-methods.txt`, `retainsMultidimensionalPointerToArrayReturnLayersOnCPlusMethodSymbols`, `[ARRAY, ARRAY, POINTER, FUNCTION]` metadata, emitted C, host C11 compile/run, and optional MinGW object compilation |
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
| 2026-09-27 | Retained direct pointer-return layers on the compact C-plus method grammar branch | `retainsDirectPointerReturnLayersOnCPlusMethodSymbols` verifies semantic `[POINTER, FUNCTION]` binding metadata, emitted pointer-return syntax, host C11 compile/run, and optional `x86_64-w64-mingw32-gcc` object compilation; the focused JVM gate passes | SP-6.4-05-H is DONE; P6-12-E remains open for other complex declarators and target-specific validation |
| 2026-09-27 | Revalidated the complete repository after adding the optional target compiler check | `./gradlew test --no-daemon --max-workers=1` passes in 2m09s; compiler, CLI, parser JVM, grammar, and highlighting gates are green with 100/100 grammar parses | SP-6.4-05-H remains complete; the parent still requires broader target/ABI and uncommon declarator evidence |
| 2026-09-27 | Added the first target-aware comptime integer model slice | `prototypeConvertsComptimeIntegerDeclarationsUsingTargetWidths` covers unsigned modulo conversion, `_Bool` normalization, fixed-width signed values, and Linux-vs-Windows `long`; `prototypeReportsSignedComptimeIntegerWidthOverflowAtTheDeclaration` checks the mapped width diagnostic | SP-4.2-01-A is DONE and SP-4.2/SP42-01 is IN PROGRESS; C usual arithmetic conversions, literal typing, casts, and function-parameter conversion remain queued |
| 2026-09-27 | Revalidated the complete repository after the typed integer increment | `./gradlew test --no-daemon --max-workers=1` passes in 2m13s; compiler, CLI, parser JVM, grammar, and highlighting gates are green with 100/100 grammar parses | The typed declaration-conversion slice is regression-safe; SP-4.2 remains active for promotions, conversions, encodings, and broader target evidence |
| 2026-09-27 | Added C usual-integer-conversion behavior for comptime comparisons | `prototypeUsesCUsualIntegerConversionsForComptimeComparisons` verifies `unsigned int` versus `-1`, `unsigned char` promotion, comparison result materialization, and host C11 execution | SP-4.2-02-A is DONE and SP42-02 is IN PROGRESS; arithmetic result typing, casts, and parameter conversions remain queued |
| 2026-09-27 | Revalidated the complete repository after the comparison-conversion increment | `./gradlew test --no-daemon --max-workers=1` passes in 2m15s; compiler, CLI, parser JVM, grammar, and highlighting gates are green with 100/100 grammar parses | SP-4.2-02-A remains complete; no Phase 4 parent closes because arithmetic, casts, encodings, and differential gates remain open |
| 2026-09-27 | Added common-type arithmetic for the supported comptime integer operators | `prototypeUsesCommonIntegerTypeForComptimeArithmetic` verifies unsigned modulo addition and small-integer promotion; `prototypeReportsComptimeArithmeticOverflowForTheCommonSignedType` verifies mapped signed overflow | SP-4.2-02-B is DONE; casts, literal typing, function-parameter conversion, and broader ABI behavior remain queued |
| 2026-09-27 | Revalidated the complete repository after the common-type arithmetic increment | `./gradlew test --no-daemon --max-workers=1` passes in 2m15s; compiler, CLI, parser JVM, grammar, and highlighting gates are green with 100/100 grammar parses | SP-4.2-02-B remains complete; the scalar work package is still open for shifts, casts, parameter conversion, encodings, and cross-target evidence |
| 2026-09-27 | Revalidated the complete repository after correcting mixed signed/unsigned common-type selection for the Windows LLP64 model and documenting the typed-integer boundary | `./gradlew test --no-daemon --max-workers=1` passes in 2m18s; compiler, CLI, parser JVM, grammar, and highlighting gates are green with 100/100 grammar parses | The bounded typed-integer increment remains complete; SP-4.2 is still open for casts, scalar-function parameter conversion, complete suffix families, encodings, and external-host evidence |
| 2026-09-27 | Added target-aware C literal candidate selection and typed unary/shift evaluation | `prototypeSelectsCIntegerLiteralTypesForRadixAndSuffix` covers decimal widening, hexadecimal and suffix selection, unsigned negation/complement, and unsigned shifts; focused `:parser-tree-sitter:jvmTest` passes | SP42-02-C and SP42-02-D are DONE; casts, scalar-function parameter conversion, complete suffix families, encodings, and external-host evidence remain open |
| 2026-09-27 | Added bounded target-aware integer casts with fail-closed pointer/non-integer behavior | `prototypeAppliesTargetAwareIntegerCasts` compiles and runs unsigned, signed, and boolean casts; `prototypeRejectsUnsupportedComptimeScalarCastsWithMappedDiagnostics` verifies a source-mapped pointer-cast diagnostic; focused `:parser-tree-sitter:jvmTest` passes | SP42-02-E is DONE; scalar-function parameter conversion, complete suffix families, encodings, and external-host evidence remain open |
| 2026-09-27 | Added declared-type conversion for scalar comptime function arguments and results | `prototypeConvertsScalarComptimeFunctionParametersAndReturns` compiles and runs narrowing, unsigned, and boolean conversions; `prototypeRejectsOutOfRangeScalarComptimeFunctionConversionWithMappedDiagnostics` verifies signed range failure; focused `:parser-tree-sitter:jvmTest` passes | SP42-02-F is DONE; complete suffix families, encodings, and external-host evidence remain open |
| 2026-09-27 | Revalidated the complete repository after the scalar cast and function-conversion increments | `./gradlew test --no-daemon --max-workers=1` passes in 2m17s; compiler, CLI, parser JVM, grammar, and highlighting gates are green with 100/100 grammar parses | The bounded scalar increment is regression-safe; SP-4.2 remains open for complete suffix families, encodings, cross-host evidence, and non-integer conversion semantics |
| 2026-09-27 | Added the pointer-to-array C-plus method declarator slice | `cplus-methods.txt` raises the grammar corpus to 101 cases; `retainsPointerToArrayReturnLayersOnCPlusMethodSymbols` verifies `[ARRAY, POINTER, FUNCTION]` binding order, emitted receiver lowering, host C11 compile/run, and optional MinGW object compilation | SP-6.4-05-I is DONE; P6-12-E/SP64-05 remains open for other uncommon declarators and broader target/compiler evidence |
| 2026-09-27 | Revalidated the complete repository after the pointer-to-array declarator increment | `./gradlew test --no-daemon --max-workers=1` passes in 2m16s; compiler, CLI, parser JVM, grammar, and highlighting gates are green with 101/101 grammar parses | SP-6.4-05-I remains complete; the parent P6-12-E/SP64-05 gate stays open for other uncommon declarators and target/compiler evidence |
| 2026-09-27 | Added the pointer-to-array parameter declarator slice | `cplus-methods.txt` raises the grammar corpus to 102 cases; `retainsPointerToArrayParameterLayersOnCPlusMethodSymbols` verifies `[POINTER, ARRAY]` parameter binding metadata, implicit receiver lowering, host C11 compile/run, and optional MinGW object compilation | SP-6.4-05-J is DONE; P6-12-E/SP64-05 remains open for other uncommon declarators and broader target/compiler evidence |
| 2026-09-27 | Revalidated the complete repository after pointer-to-array parameter coverage | `./gradlew test --no-daemon --max-workers=1` passes in 2m13s; compiler, CLI, parser JVM, grammar, and highlighting gates are green with 102/102 grammar parses | SP-6.4-05-J remains complete; the parent P6-12-E/SP64-05 gate stays open for other uncommon declarators and target/compiler evidence |
| 2026-09-27 | Extended pointer-to-array parameter coverage to qualified C declarators | `retainsPointerToArrayParameterLayersOnCPlusMethodSymbols` now checks `borrowed const int (* restrict values)[2]`, exact declaration spelling, binding layers, host C11 compile/run, and optional MinGW object compilation | SP-6.4-05-K is DONE; P6-12-E/SP64-05 remains open for other uncommon declarators and broader target/compiler evidence |
| 2026-09-27 | Closed target-aware long-family literal suffix selection for the supported ABI models | `prototypeSelectsLongIntegerLiteralSuffixesPerTargetAbi` passes for Linux/macOS x86_64 and arm64 and Windows x86_64; it caught and fixed wider-unsigned common-type narrowing; focused JVM verification passes | SP-4.2-03-A is DONE; the parent SP-4.2-03 gate remains open for external compiler/ABI evidence and unsigned values beyond signed-64 materialization |
| 2026-09-27 | Revalidated the complete repository after the target-aware suffix/common-type correction | `./gradlew test --no-daemon --max-workers=1` passes in 2m17s; compiler, CLI, parser JVM, grammar, and highlighting gates are green with 102/102 grammar parses | SP-4.2-03-A remains complete; SP-4.2 remains open for external compiler/ABI evidence, plain `char`, and unsigned values beyond signed-64 materialization |
| 2026-09-27 | Added the shipped target normalization contract | `CPlusTargetTest` covers Linux, Windows, and macOS triples, x86_64/arm64 aliases, missing `--target` values, and invalid incomplete options; `SPEC.compiler.md` records the six packaged host/architecture pairs | SP-5.2-02-A is DONE; SP-5.2 remains open for compiler/link/header behavior and CI evidence |
| 2026-09-27 | Added explicit prefixed-string encoding boundaries | `prototypeRejectsUnsupportedPrefixedComptimeStringLiteralsAtTheirSourceSpans` rejects `L`, `u8`, `u`, and `U` comptime strings with `CPLUS_COMPTIME_SCALAR_STRING` at each literal origin; focused JVM verification passes | SP-4.2-04-A is DONE; SP-4.2-04 remains open for actual UTF-8/wide literal materialization and rune semantics |
| 2026-09-27 | Revalidated the complete repository after target, encoding-boundary, and qualified-declarator updates | `./gradlew test --no-daemon --max-workers=1` passes in 2m13s; compiler, CLI, parser JVM, grammar, and highlighting gates are green with 102/102 grammar parses | The new bounded cases are regression-safe; Phase 4/5 parent gates remain open for actual encoding materialization, external compiler evidence, broader declarators, differential parity, and retirement |
| 2026-09-27 | Added array-of-function-pointer parameter coverage | `retainsArrayOfFunctionPointerParameterLayersOnCPlusMethodSymbols` passes; the grammar corpus now parses 103/103 cases and retains `[ARRAY, POINTER, FUNCTION]` plus nested callback metadata; emitted C compiles/runs on the host | SP64-05-L is DONE; P6-12-E/SP64-05 remains open for remaining uncommon declarators and target/compiler evidence |
| 2026-09-27 | Revalidated the complete Tree-sitter JVM suite after the new declarator slice | `./gradlew :parser-tree-sitter:jvmTest --no-daemon --max-workers=1` passes in 2m11s; parser generation/verification, JVM tests, grammar, and highlighting checks are green | The bounded declarator increment is regression-safe; no parent migration gate closes |
| 2026-09-27 | Added multidimensional pointer-to-array return coverage | `retainsMultidimensionalPointerToArrayReturnLayersOnCPlusMethodSymbols` passes; the grammar corpus now parses 104/104 cases and retains `[ARRAY, ARRAY, POINTER, FUNCTION]`; emitted C compiles/runs on the host | SP64-05-M is DONE; P6-12-E/SP64-05 remains open for remaining uncommon declarators and target/compiler evidence |
| 2026-09-27 | Revalidated the complete repository after the declarator batch | `./gradlew test --no-daemon --max-workers=1` passes in 2m13s; compiler, CLI, parser JVM, grammar, and highlighting gates are green with 104/104 grammar parses | The declarator batch is regression-safe; P6-12-E/SP64-05 remains open and the next local queue item is the target-aware scalar boundary |
| 2026-09-27 | Established the phase-1 grammar proof from live sources | `SPEC.grammar.md` records the C-plus extension grammar; `provesLiveCPlusSyntaxNodeInventoryForGrammarProof` parses all 62 `stdlib/` and `examples/` sources without recovery and asserts the exact 36 live `cplus_*` node kinds; the focused grammar corpus remains 104/104 | Phase 1 is now a versioned syntax contract. Phase 2 will classify each mismatch as documentation, grammar, AST, lowering, or intentional unsupported behavior and require generated-C evidence before changing semantics |
| 2026-09-27 | Closed the supported plain-`char` scalar-model slice | `./gradlew :compiler:test --tests cplus.CPlusTargetTest` and the two focused Tree-sitter scalar tests pass; Linux arm64 is modeled unsigned, supported Linux x86_64/macOS/Windows pairs signed, and unknown targets fail with a mapped diagnostic | SP-4.2 gains a bounded target-model case; the parent remains open for external ABI confirmation, wider unsigned materialization, and encoding semantics |
| 2026-09-28 | Materialized structured annotation reflection | `prototypeIteratesReflectedFieldAnnotationsAsMappedNestedComptimeLoop` uses `annotation.name`, compiles/runs generated C, and preserves token origins; `prototypeRejectsUnknownStructuredAnnotationMembersAtTheirSourceSpan` verifies the mapped member diagnostic | SP41-03, P4-14-D, and CF-02 are DONE |
| 2026-09-28 | Froze the bounded iterable protocol | `SPEC.comptime.md` limits iteration to type `.fields` and field `.annotations`; `prototypeRejectsScalarAnnotationProjectionAsAnIterable`, the arbitrary nested-iterable fixture, and expansion-limit fixtures pass | SP41-04, P4-14-E, CF-03, FE-SUB-03, SP-4.1, and P4-14 are DONE; arbitrary expressions are explicitly unsupported |
| 2026-09-28 | Closed comptime string encoding and mapped scalar diagnostics | `prototypeMaterializesComptimeStringsAsTargetStableUtf8Bytes` emits `hé🌍` as exact UTF-8 octal bytes and compiles/runs; ordinary escape/concatenation and mapped prefixed-literal tests pass; existing width/conversion failures assert exact spans | SP42-04, SP42-05, CF-05, and CF-06 are DONE; SP-4.2 remains active only for wider unsigned integer representation and external ABI evidence |

### Next update protocol

For each next task: record the source fixture, implementation commit/change, exact Gradle/CLI command, result, elapsed engineering time, and whether the parent acceptance condition changed. Then update both this log and the parent row in `COMPILER-FRONTEND-IMPLEMENTATION.md`.
