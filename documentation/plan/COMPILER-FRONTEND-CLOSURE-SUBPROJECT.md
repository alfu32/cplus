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
| CF-07 | declarators | Finish complex pointer/array/function binding shapes | none | IN PROGRESS | 4 | Semantic layer matrix, emitted C, host compile/run, optional MinGW checks |
| CF-08 | declarators | Finish calling-convention and attribute target evidence | CF-07 | OPEN | 4 | Supported/unsupported ABI table and target compiler fixtures |
| CF-09 | scope | Resolve comptime lexical scope, shadowing, generated names, and collisions | CF-01, CF-07 | OPEN | 4 | Nested scope/materialization fixtures and mapped ambiguity/cycle errors |
| CF-10 | targets | Verify the six shipped host/architecture compiler matrix | CF-04, CF-07 | OPEN-EXTERNAL | 6 | Linux/macOS/Windows x86_64/arm64 compile, link, parser/resource logs |
| CF-11 | corpus | Inventory normative examples and expected materializations | CF-02, CF-03, CF-04, CF-05 | OPEN | 2 | Versioned manifest linking each spec example to a fixture |
| CF-12 | corpus | Add malformed, unsupported, limit, cycle, and map fixtures | CF-06, CF-09 | OPEN | 4 | Stable diagnostics and exact source spans |
| CF-13 | corpus | Freeze the finite legacy-overlap corpus | CF-11, CF-12 | OPEN | 4 | Manifest and normalization rules for output/options/runtime |
| CF-14 | corpus | Run AST-versus-legacy differential comparisons | CF-13 | OPEN | 4 | No unexplained accepted-input, output, option, map, or diagnostic mismatch |
| CF-15 | migration | Inventory remaining scanner/textual entry points | CF-13 | OPEN | 2 | Feature-to-entry-point inventory with fallback owner |
| CF-16 | migration | Move remaining comptime forms behind AST input | CF-14, CF-15 | OPEN | 6 | Imports, conditions, flags, scalar/entity expansion and fixed-point traces |
| CF-17 | migration | Add per-pass backend selection and rollback checks | CF-14, CF-15 | OPEN | 4 | Each migrated pass can be selected, compared, and reverted independently |
| CF-18 | retirement | Prove expression sequencing and allocation-flow parity | CF-14 | OPEN | 6 | Ordered/unsequenced expression fixtures, C compile/run, conservative joins |
| CF-19 | retirement | Retire method/receiver and `defer` textual lowerers | CF-07, CF-14, CF-17, CF-18 | OPEN | 4 | Individual parity gates, rollback test, and no legacy call path |
| CF-20 | retirement | Retire `try`/`catch`, test extraction, and allocation textual lowerers | CF-14, CF-17, CF-18 | OPEN | 6 | Individual parity gates, rollback test, and full repository acceptance |
| CF-21 | promotion | Run stdlib/examples compile and test acceptance | CF-10, CF-19, CF-20 | OPEN | 4 | All supported sources compile; tests pass; known excluded fixtures are listed |
| CF-22 | promotion | Decide promotion, rollback, and legacy removal | CF-21 | OPEN | 2 | Recorded decision, performance/package evidence, removal or retained fallback rationale |

Total: **22 tasks / 86 points**. `CF-01` through `CF-06` are complete; **66 points remain**, of which 6 are external-host evidence. Points are intentionally not converted to hours until three completed batches provide an observed rate.

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

The next local batch is **C1/C2**: finish `CF-07`–`CF-09` declarator/scope boundaries. `CF-10` is submitted to CI when its local fixtures and manifests are ready; it does not block local implementation.

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
| 2026-09-27 | CF-07 | Pointer-to-array return and qualified pointer-to-array parameter fixtures preserve `[ARRAY, POINTER, FUNCTION]` and `[POINTER, ARRAY]`; host C11 compile/run and optional MinGW object checks pass | Partial declarator slice closed under existing `SP-6.4-05-I/K`; CF-07 remains open for remaining shapes and target evidence |
| 2026-09-27 | CF-07 | `retainsArrayOfFunctionPointerParameterLayersOnCPlusMethodSymbols` preserves `[ARRAY, POINTER, FUNCTION]`, callback signature metadata, emitted C, and host C11 execution; the matching corpus fixture passes | Array-of-function-pointer parameter slice is complete; CF-07 remains open for remaining shapes and target/compiler evidence |
| 2026-09-27 | CF-07 | `retainsMultidimensionalPointerToArrayReturnLayersOnCPlusMethodSymbols` preserves `[ARRAY, ARRAY, POINTER, FUNCTION]`, emits `matrix_return__grid`, and compiles/runs on the host; the matching grammar fixture passes | Multidimensional pointer-to-array return slice is complete; CF-07 remains open for remaining shapes and target/compiler evidence |
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

## Update protocol

After each implementation batch:

1. update the task status and acceptance evidence here;
2. append one follow-up row with the exact command and result;
3. update the matching parent row in `COMPILER-FRONTEND-SUBPROJECT-FOLLOWUP.md` and `COMPILER-FRONTEND-IMPLEMENTATION.md`;
4. rerun the smallest focused test, then `./gradlew test --no-daemon --max-workers=1` before changing a parent status;
5. do not create a new task for spelling variants; add them to the nearest invariant unless they require a new semantic rule or evidence class.
