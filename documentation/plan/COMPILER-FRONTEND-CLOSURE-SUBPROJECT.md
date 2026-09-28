# Compiler Frontend Closure Subproject

This is the bounded subproject for the open work that remains after the local Tree-sitter frontend, AST lowering, source mapping, and repository fixtures are working. It is an execution plan, not a promise to support every legal C program. The legacy frontend remains the rollback path until the retirement gates close.

## Objective

Close the remaining promotion risks with finite evidence:

- define the supported C-plus/comptime boundary;
- finish the high-value semantic and declarator slices;
- prove behavior across the declared compiler/target matrix;
- compare the AST path with the legacy path on a finite overlap corpus;
- retire textual lowerers only when their individual gates pass.

## Non-goals

This subproject does not implement a complete C type checker, enumerate the Cartesian product of C syntax, or infer unsupported ABI behavior. Unsupported forms must receive a mapped diagnostic or be delegated to the selected C compiler.

## Work breakdown

Points are relative sizing units, not engineer-days. A task closes only with its listed evidence. Dependencies are deliberate: later tasks cannot hide an unresolved contract in an earlier one.

| ID | Work package | Task | Depends on | Status | Points | Acceptance evidence |
|---|---|---|---|---:|---:|---|
| CF-01 | boundary | Freeze the supported comptime value/AST boundary | none | DONE | 2 | `SPEC.grammar.md`, spec inventory, fail-closed diagnostics, 62/62 live-source parse gate, 37-node live syntax inventory, and 104/104 grammar corpus gate |
| CF-02 | boundary | Define structured annotation values and member access | CF-01 | DONE | 4 | `annotation.name`, explicit `field.annotationsText`, nested materialization, and mapped unknown-member fixtures |
| CF-03 | boundary | Define the bounded iterable protocol | CF-01 | DONE | 4 | Type `.fields` and field `.annotations` collections, arbitrary-expression rejection, and 1,024/65,536 expansion limits |
| CF-04 | scalar | Close target-aware integer conversions beyond the current subset | CF-01 | DONE | 4 | `char`, full supported `unsigned long long` width, casts/conversions, target fixtures; external compiler confirmation remains CF-10 |
| CF-05 | scalar | Close string, UTF-8, wide, and UTF-16/32 boundaries | CF-01 | DONE | 4 | Ordinary strings materialize target-stable UTF-8 bytes; prefixed wide/UTF forms fail mapped |
| CF-06 | scalar | Verify scalar diagnostics and source-map spans | CF-04, CF-05 | DONE | 2 | Exact origin assertions cover width, conversion, overflow, escape, and encoding failures |
| CF-07 | declarators | Finish complex pointer/array/function binding shapes | none | DONE | 4 | Identifier-outward recursive constructor model, method/function depth law, emitted C, host compile/run, optional MinGW checks |
| CF-08 | declarators | Finish calling-convention and attribute target evidence | CF-07 | DONE | 4 | Explicit compiler-delegated ABI table, host GNU fixtures, MinGW x86_64 accepted-spelling fixture, and preservation-only boundary |
| CF-09 | scope | Resolve comptime lexical scope, shadowing, generated names, and collisions | CF-01, CF-07 | DONE | 4 | Finite lexical-scope specification; arity overloads; parameter/loop shadowing; generated-pass visibility; mapped duplicate, ambiguity, cycle, and collision errors |
| CF-10 | targets | Verify the six shipped host/architecture compiler matrix | CF-04, CF-07 | OPEN-EXTERNAL | 6 | Linux/macOS/Windows x86_64/arm64 compile, link, parser/resource logs |
| CF-11 | corpus | Inventory normative examples and expected materializations | CF-02, CF-03, CF-04, CF-05 | DONE | 2 | `documentation/corpus/frontend-v1.tsv` plus `frontend-v1-contract.tsv` link all 29 normative cases to executable evidence and declare `materialize` as their result contract |
| CF-12 | corpus | Add malformed, unsupported, limit, cycle, and map fixtures | CF-06, CF-09 | DONE | 4 | The 25 boundary rows link the existing mapped-diagnostic fixtures; the corpus gate verifies their specification markers, unique IDs, evidence methods, and `mapped-diagnostic` result contract |
| CF-13 | corpus | Freeze the finite legacy-overlap corpus | CF-11, CF-12 | DONE | 4 | The 14 overlap rows and contract freeze the migration-only comparison set; README records that comparisons normalize token output while separately asserting options, maps, diagnostics, and runtime |
| CF-14 | corpus | Run AST-versus-legacy differential comparisons | CF-13 | DONE | 4 | Existing overlap fixtures execute legacy/Tree-sitter comparisons for output, options, mapped diagnostics, imports, tests, flags, methods, defer, and checked calls; the full local parser suite is the gate |
| CF-15 | migration | Inventory remaining scanner/textual entry points | CF-13 | DONE | 2 | `COMPILER-FRONTEND-MIGRATION-INVENTORY.md` maps each scanner/textual owner to its AST replacement and retirement gate |
| CF-16 | migration | Move remaining comptime forms behind AST input | CF-14, CF-15 | DONE | 6 | AST prototype fixtures cover imports, OS conditions, flags, scalar/entity expansion, generated imports, fixed-point passes, limits, cycles, and mapped origins; CLI selection test exercises the complete AST frontend |
| CF-17 | migration | Add per-pass backend selection and rollback checks | CF-14, CF-15 | IN PROGRESS | 4 | Both frontends expose migration-only pass selection and fail-closed rollback; five runtime lowerers plus validation have dependency-aware substitution evidence, while broader corpus and revert evidence remain |
| CF-18 | retirement | Prove expression sequencing and allocation-flow parity | CF-14 | DONE | 6 | Ordered/unsequenced expression fixtures, C compile/run, conservative joins, loop fixed points, pointer-output invalidation, and mapped allocation diagnostics |
| CF-19 | retirement | Retire method/receiver and `defer` textual lowerers | CF-07, CF-14, CF-17, CF-18 | OPEN | 4 | Individual parity gates, rollback test, and no legacy call path |
| CF-20 | retirement | Retire `try`/`catch`, test extraction, and allocation textual lowerers | CF-14, CF-17, CF-18 | OPEN | 6 | Individual parity gates, rollback test, and full repository acceptance |
| CF-21 | promotion | Run stdlib/examples compile and test acceptance | CF-10, CF-19, CF-20 | OPEN | 4 | All supported sources compile; tests pass; known excluded fixtures are listed |
| CF-22 | promotion | Decide promotion, rollback, and legacy removal | CF-21 | OPEN | 2 | Recorded decision, performance/package evidence, removal or retained fallback rationale |

Total: **22 tasks / 86 points**. `CF-01` through `CF-16` and `CF-18` are complete; **26 points remain**, of which 6 are external-host evidence. Points are intentionally not converted to hours until three completed batches provide an observed rate.

## Execution phases

| Phase | Tasks | Gate | Can run in parallel |
|---|---|---|---|
| C0 — baseline | CF-01 | Contract and local parser baseline frozen | external CI only |
| C1 — language boundary | CF-02..CF-06 | Every supported value has positive evidence; every unsupported form fails mapped | CF-07 |
| C2 — semantic shape | CF-07..CF-09 | Binding layers, scopes, attributes, and receiver diagnostics are deterministic | C1 |
| C3 — target evidence | CF-10 | Six-host package/compiler matrix recorded | C1/C2 local work |
| C4 — finite corpus | CF-11..CF-14 | Normative and legacy-overlap comparisons are frozen and explainable | none after C1/C2 |
| C5 — migration | CF-15..CF-18 | AST path is selectable, comparable, and conservative | CF-10 may remain external |
| C6 — retirement | CF-19..CF-20 | Each textual lowerer has its own parity and rollback decision | none |
| C7 — promotion | CF-21..CF-22 | Repository acceptance and promotion decision are recorded | none |

The C4 finite corpus gate is complete locally. The next local batch is **C5**:
inventory remaining scanner/textual entry points and make the AST backend selectable
per pass, while external six-host evidence proceeds independently under `CF-10`.

## Definition of done

The subproject is complete when:

1. every task is `DONE`, `EXTERNAL`, or explicitly `UNSUPPORTED` with a stable mapped diagnostic;
2. the finite corpus has no unexplained AST/legacy mismatch;
3. each textual lowerer has an independent rollback-safe decision;
4. the stdlib and examples acceptance run is green, excluding only documented intentionally failing fixtures;
5. the promotion decision and any retained legacy fallback are documented.

## Follow-up log

Each entry records the task ID, exact evidence, result, and next dependency. Implementation time and CI wait time are recorded separately. A passing full suite does not close a task unless its acceptance evidence is specific to that task.

| Date | Task | Evidence | Result / next action |
|---|---|---|---|
| 2026-09-27 | CF-01 | `./gradlew test --no-daemon --max-workers=1`; Tree-sitter grammar `102/102`; compiler, CLI, parser JVM, grammar, and highlighting gates green | Baseline remains green. CF-01 is closed; begin CF-07, then CF-04..CF-06 |
| 2026-09-27 | CF-01 | `./gradlew :parser-tree-sitter:testTreeSitterGrammar --no-daemon --max-workers=1`; grammar corpus `103/103`; `./gradlew :parser-tree-sitter:jvmTest --no-daemon --max-workers=1` passes in 2m11s | Baseline revalidated after the array-of-function-pointer fixture; CF-01 remains closed and CF-07 advances by one bounded subcase |
| 2026-09-27 | CF-07 | Pointer-to-array return and qualified pointer-to-array parameter fixtures preserve `[FUNCTION, POINTER, ARRAY]` and `[POINTER, ARRAY]`; host C11 compile/run and optional MinGW object checks pass | Partial declarator slice closed under existing `SP-6.4-05-I/K`; CF-07 remains open for remaining shapes and target evidence |
| 2026-09-27 | CF-07 | `retainsArrayOfFunctionPointerParameterLayersOnCPlusMethodSymbols` preserves `[ARRAY, POINTER, FUNCTION]`, callback signature metadata, emitted C, and host C11 execution; the matching corpus fixture passes | Array-of-function-pointer parameter slice is complete; CF-07 remains open for remaining shapes and target/compiler evidence |
| 2026-09-27 | CF-07 | `retainsMultidimensionalPointerToArrayReturnLayersOnCPlusMethodSymbols` preserves `[FUNCTION, POINTER, ARRAY, ARRAY]`, emits `matrix_return__grid`, and compiles/runs on the host; the matching grammar fixture passes | Multidimensional pointer-to-array return slice is complete; CF-07 remains open for remaining shapes and target/compiler evidence |
| 2026-09-27 | CF-07 | `./gradlew test --no-daemon --max-workers=1` passes in 2m13s; grammar reports 104/104, highlighting passes, and compiler/CLI/parser tests are green | Declarator batch is regression-safe; CF-07 remains open for remaining shapes and target/compiler evidence; next local batch is CF-04 |
| 2026-09-27 | CF-04 | Target-aware `L`, `UL`/`LU`, `LL`, and `ULL`/`LLU` fixtures pass for Linux/macOS x86_64/arm64 and Windows x86_64 | Long-family suffix slice closed; `char`, larger unsigned values, and external ABI behavior remain |
| 2026-09-27 | CF-05 | `L`, `u8`, `u`, and `U` prefixed comptime strings fail at their literal origins with mapped diagnostics | Fail-closed boundary closed; actual UTF-8/wide/rune materialization remains |
| 2026-09-27 | CF-04 | `CPlusTargetTest.uses the supported target model for plain char signedness`, `prototypeUsesTargetAwarePlainCharSignedness`, and `prototypeRejectsPlainCharWhenTargetSignednessIsUnknown` pass; supported Linux/macOS/Windows target pairs materialize the expected signedness and unknown targets fail at the declaration type | Plain-`char` signedness is now a bounded target-model case; external compiler ABI confirmation remained open in CF-10 |
| 2026-09-27 | baseline | `git diff --check` | Documentation and implementation changes are whitespace-clean; no parent migration gate was advanced by documentation alone |
| 2026-09-27 | CF-01 | `./gradlew :parser-tree-sitter:jvmTest --tests cplus.parser.TreeSitterCPlusParserBackendTest.provesLiveCPlusSyntaxNodeInventoryForGrammarProof --no-daemon --max-workers=1`; 62/62 live sources parse without recovery and the exact 37-node inventory matches after the explicit comptime result-type addition | Phase-1 grammar proof is frozen in `documentation/spec/SPEC.grammar.md`; phase 2 now classifies grammar/spec/generated-C mismatches without treating the legacy transcoder as authoritative |
| 2026-09-28 | CF-02 | Structured field annotations materialize through `annotation.name`; `prototypeIteratesReflectedFieldAnnotationsAsMappedNestedComptimeLoop` compiles/runs generated C and retains the annotation-token origin; `prototypeRejectsUnknownStructuredAnnotationMembersAtTheirSourceSpan` reports `CPLUS_COMPTIME_ANNOTATION_PROPERTY` at the member | Structured annotation values and deterministic member access are closed for the current annotation vocabulary |
| 2026-09-28 | CF-03 | `SPEC.comptime.md` defines only type `.fields` and field `.annotations` as iterable; `prototypeRejectsScalarAnnotationProjectionAsAnIterable`, the existing arbitrary nested-iterable fixture, and the 65,536-item limit fixture enforce mapped rejection and bounds | The bounded iterable protocol is closed; arbitrary C/comptime expressions are explicitly unsupported rather than an unbounded future promise |
| 2026-09-28 | CF-05 | `prototypeMaterializesComptimeStringsAsTargetStableUtf8Bytes` compiles/runs a direct Unicode string emitted as exact UTF-8 octal bytes; ordinary escape/concatenation tests and mapped `L`/`u8`/`u`/`U` rejection pass | The comptime string encoding contract is closed: ordinary strings are UTF-8; wide and UTF-16/32 prefixed literals are unsupported without implicit coercion |
| 2026-09-28 | CF-06 | Existing signed-width, cast, function-conversion, overflow, unsupported-escape, and prefixed-string tests assert exact original spans; the new UTF-8 positive fixture compiles/runs | Scalar diagnostic/source-map coverage is closed for the bounded scalar contract; external compiler/ABI evidence remains assigned to CF-10 |
| 2026-09-28 | CF-04 | `prototypeMaterializesTheFullUnsignedLongLongDomain` compiles/runs maximum, wraparound, high-bit, cast, comparison, and scalar-function values above `Long.MAX_VALUE`; `prototypeRejectsIntegerLiteralsBeyondUnsignedLongLongAtTheirSourceSpan` preserves the literal origin | The scalar conversion boundary now covers the complete supported 64-bit unsigned domain; external compiler/ABI evidence remains assigned to CF-10 |
| 2026-09-28 | CF-01 / CF-07 | `prototypeTranscodesEveryRepositoryCPlusSourceWithoutActiveComptimeSyntax` passes for the complete repository `.cp`/`.c+` corpus; all generated output is C11 syntax-checked by each locally available `cc`, `gcc`, or `clang` driver, with no active comptime syntax retained | Phase-1 grammar and phase-2 generated-C proof agree for the finite repository corpus; broader target/compiler evidence and unsupported-boundary inventory remain open |
| 2026-09-28 | CF-09 | Runtime-scope comptime invocations are now rejected before entity materialization; `comptimeGeneratorBindingsRespectModuleAndRuntimeFunctionScopes` and `prototypeRejectsEntityComptimeInvocationInsideRuntimeFunctionAtItsSourceSpan` verify no accidental module-generator binding and mapped `CPLUS_COMPTIME_INVOCATION_SCOPE` | The runtime invocation scope boundary is closed; lexical shadowing, generated-name scopes, and broader collision rules remain under P5-10-D/CF-09 |
| 2026-09-28 | CF-07 | `CPlusDeclaratorLayer` is normalized to identifier-outward recursive composition; direct method-return stars come from structural grammar children, and return types are derived by removing the first `FUNCTION` without scanning source text. `composesDeclaratorLayersRecursivelyAndEquallyForMethodsAndFunctions` proves equal method/function shapes through eight array depths; repeated pointers, pointer-to-array, multidimensional arrays, and nested callable returns compile/run in focused fixtures. The complete 268-test parser JVM suite passes in 2m17s and `./gradlew test --no-daemon --max-workers=1` passes with 104/104 grammar parses | CF-07 and P5-10-C are DONE. The finite constructor algebra covers unbounded recursive terms compositionally; calling-convention target evidence remains CF-08/CF-10 |
| 2026-09-28 | CF-08 | `SPEC.compiler.md` distinguishes compiler-supported, header-dependent, and preservation-only ABI spellings. `compilesDocumentedMingwX64CallingConventionsWhenTheTargetCompilerIsAvailable` retains semantic qualifiers and compiles `__cdecl`, `__stdcall`, `__fastcall`, `__thiscall`, `WINAPI`, and `__declspec(noinline)` with installed MinGW x86_64; existing GNU and all-spelling preservation fixtures remain green | CF-08, P5-10-B, P6-12-E, and P6-12 are DONE locally. Six-host/architecture execution remains CF-10 rather than being inferred from one cross-compiler |
| 2026-09-28 | CF-09 | Scalar functions now share resolver `(name, arity)` overload semantics; duplicate parameter names fail with related spans. Focused compile/run fixtures prove parameter shadowing, nested reflected-loop shadow/restore, generated declaration visibility on the next pass, and mapped generated-name collision; existing duplicate/ambiguous/unresolved, scalar/import cycle, runtime-scope, and C namespace fixtures remain the negative matrix. The complete 273-test parser JVM suite and repository Gradle gate pass; grammar remains 104/104 | CF-09, P5-10-D/E, P5-10, and SP-5.1 are DONE. The comptime scope model is finite and documented; corpus freezing follows |
| 2026-09-28 | CF-11..CF-14 | Added `documentation/corpus/frontend-v1.tsv` and `frontend-v1-contract.tsv` with 29 normative, 25 boundary, and 14 overlap cases. `frontendCorpusManifestIsCompleteAndReferencesExecutableEvidence` validates the 68 stable IDs, exact specification markers, evidence methods, category prefixes, and result contracts. The 14 existing differential fixtures cover normalized output plus options, runtime, source maps, imports, tests, flags, defer, and checked calls; the focused corpus gate passes | The finite C4 corpus gate is DONE locally. The next dependency is C5 migration inventory; six-host package/compiler evidence remains CF-10 |
| 2026-09-28 | CF-17 | `comparePassSubstitution` enables one runtime lowerer plus documented prerequisites and compares normalized output, compiler options, source-line map coverage, and pass exercise; throws, defer, struct methods, receiver calls, and try/catch representatives compile/run through both frontends | CF-17 advances to IN PROGRESS; analysis-only validation, broader substitution corpus, and textual-lowerer retirement remain |
| 2026-09-28 | CF-17 | `compareSemanticPassSubstitution` enables only `validate-semantics`, compares normalized allocation diagnostics, generated C/options, and source-line map coverage, and compiles/runs both outputs | Analysis-only substitution is now covered; broader allocation-flow corpus and textual-lowerer retirement remain |
| 2026-09-28 | CF-17 | The substitution fixture matrix now also covers error-out throws, early-return defer, static method definitions, pointer receivers, and consecutive try/catch blocks; both frontend outputs compile/run | Runtime substitution evidence is broader but remains a migration gate; unsupported forms, cross-host evidence, and lowerer retirement remain |
| 2026-09-28 | CF-19 | Method/receiver and defer sub-gates now have broad AST/legacy compile/run, dependency-aware substitution, mapped-origin, and fail-closed rollback evidence; the named SP63-01/SP63-02 tasks are closed | CF-19 remains open for the separate removal decision and retained-legacy fallback review |
| 2026-09-28 | CF-20 | Checked-call/try-catch coverage now includes nested/consecutive propagation, error-return/error-out functions and methods, mapped unsupported-expression diagnostics, dependency-aware substitution, and fail-closed rollback; SP63-03 is closed | CF-20 remains open for test extraction/allocation gates and the separate textual-retirement decision |
| 2026-09-28 | CF-20 | AST test extraction now materializes fixtures through the explicit harness bridge and compares fixture names/counts plus assertion source-line reachability; the extraction rollback switch remains covered | SP63-04 advances to IN PROGRESS; fixture-body semantic parity and textual-retirement evidence remain |
| 2026-09-28 | CF-20 | `TreeSitterTestDifferentialReport` now compares normalized `cplus_test_N` function-body tokens; defer/check-error fixture lowering treats `@test` as a function closure, places `cplus_test_finish` before cleanup, and `astTestHarnessRunsDeferAfterFailedAssertion` verifies cleanup after failure. The complete `stdlib/tests` corpus passes the harness differential, and `astTestFixtureRejectsUnsupportedCheckedExpressionAtTheFixtureSourceSpan` verifies fixture-local fail-closed diagnostics. `./gradlew test --no-daemon --max-workers=1` remains green with 104/104 grammar parses | CF-20 accepted-fixture parity and one unsupported-body boundary are proven; broader unsupported-body mapping and textual test/allocation retirement remain open |

## Update protocol

After each implementation batch:

1. update the task status and acceptance evidence here;
2. append one follow-up row with the exact command and result;
3. update the matching parent row in `COMPILER-FRONTEND-SUBPROJECT-FOLLOWUP.md` and `COMPILER-FRONTEND-IMPLEMENTATION.md`;
4. rerun the smallest focused test, then `./gradlew test --no-daemon --max-workers=1` before changing a parent status;
5. do not create a new task for spelling variants; add them to the nearest invariant unless they require a new semantic rule or evidence class.
