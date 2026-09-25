# Compiler Frontend Migration Follow-up

This is the implementation checklist for [`COMPILER-FRONTEND-ARCHITECTURE.md`](COMPILER-FRONTEND-ARCHITECTURE.md). Statuses describe repository work, not promises. A phase advances only when its acceptance checks pass; the legacy compiler remains the default until Phase 7 promotion.

## Component register

| ID | Component / deliverable | Phase | Current status | Acceptance / expiry |
|---|---|---:|---|---|
| SRC-01 | Source identity, immutable snapshots, revisions, UTF-8/UTF-16 coordinate map | 0 | Complete | Unit tests cover Unicode boundaries, revisions, canonical file loading; the full CLI suite passes unchanged. Becomes foundation for every parser and diagnostic. |
| PAR-01 | Parser backend/result/selection contract | 1 | Contract implemented; shared-subset differential check passes, broad gate open | Generator-neutral nodes/results, registry selection, and explicitly partial legacy scanning are covered by tests. Shadow comparison now checks normalized comptime/test construct names and exact spans; broad source/diagnostic/output parity remains open. Legacy adapter expires only after new backend promotion. |
| TS-01 | Pinned Tree-sitter C grammar/runtime integration | 2 | Host-specific JNI resources and a six-host CI matrix are configured; Linux x86_64/JDK 21 verified locally (149 KiB JVM jar, 1,283,272-byte JNI resource), remote matrix pending | Reproducible generation, Java 21-compatible loading/package proof on each supported host, license/provenance, CI parser build. |
| TS-02 | C-plus grammar extension and corpus | 2 | Explicit grammar slices for methods, defer, comptime, tests, and error handling | Named nodes for supported constructs; all upstream C and C-plus corpus passes. |
| AST-01 | Stable C-plus CST adapter and AST schema | 3 | Core C/C-plus declarations and control-flow categories normalized; full-language normalization open | Adapter coverage, span fidelity, unknown/recovery preservation, grammar upgrade tests. |
| MOD-01 | Canonical source/import graph and C/C-plus boundary | 4 | Shared `SourceManager`, deterministic graph ordering, canonical edges, root-mapped import-cycle diagnostics, and confined relative/stdlib/module/project path resolution are implemented; IDE integration remains open | Relative/module/stdlib resolution, canonical identity, cycle diagnostics, C includes unchanged. |
| CT-01 | AST-backed comptime parsing/materialization | 4 | AST syntax index implemented; materializer remains legacy | Existing limits/security retained; generated declarations reparse to fixed point; multi-pass maps preserved. Text scanners expire after parity gate. |
| SEM-01 | Scope, symbols, method/type resolution, annotation metadata | 5 | Struct/method and simple typedef-chain resolution, explicit throws conventions, nested callback signatures, and function-pointer return composition indexed; full C semantics open | C typedef forms, nested scopes, comptime scopes, diagnostics; ownership remains advisory. |
| IR-01 | Source-spanned runtime IR and ordered lowering pipeline | 6 | Shared AST edit/result contracts implemented; IR pipeline open | Each pass has invariants and isolated golden tests; no comptime nodes reach C lowering. |
| LOWER-01 | AST lowering for methods, defer, try/catch, tests, allocation diagnostics | 6 | Opt-in AST pipeline now lowers validated throws contracts and statement-oriented checked calls/try-catch, in addition to methods and defer; tests and broader parity remain open | Differential output and behavior parity. Test harness extraction, broad defer scopes, and legacy retirement remain open. |
| EMIT-01 | Structured C emitter, origin segments, diagnostic mapping | 7 | Experimental Tree-sitter subset emits mapped, compiler-validated C; general AST emission open | Full C output compiles; mapped diagnostics cover generated/imported code; `#line` remains until toolchain matrix passes. |
| IDE-01 | Shared parser-backed editor indexing/diagnostics/test discovery | 8 | Versioned normalized-AST JSON contract added; editor consumers not connected | Existing editor capabilities preserved, incomplete buffers recover, CLI diagnostics agree with editor locations. |
| VERIFY-01 | Differential, fuzz, compatibility, performance, platform suite | 0–8 | Not started | Runs continuously; gates parser promotion and each legacy retirement. |
| RETIRE-01 | Legacy backend/lowerer removal | 9 | Not started | New backend default for two stable releases, no open corpus mismatches, rollback documented, all component gates passed. |

## Phase sequence

### Phase 0 — source foundation (current)

- [x] SRC-01: establish source IDs, snapshots, revisioning, and exact UTF-8/UTF-16 boundary conversion.
- [x] Integrate imported comptime sources with `SourceManager` while retaining `SourceFile` compatibility; tests verify the imported snapshot is registered.
- [x] Own resolved comptime edges in `SourceImportGraph`, provide deterministic dependency order, and map cycle diagnostics to the root import request; tests cover DAG order, cycle closure, and root-span reporting.
- [x] Lock a representative legacy transpilation to a byte-for-byte C golden and assert its generated statement maps to the original `.cp` line; imported-source tests continue to cover `#line` origin changes.

### Phase 1 — switchable frontend contract (shadow comparison prototype implemented)

- [x] Define parser input/options/result and backend ID without generator-specific node types.
- [x] Add registry selection with `legacy` as default; no CLI behavior or generated C changes.
- [x] Adapt the existing comptime scanner's recognized items to neutral syntax nodes; explicitly label all other text as opaque.
- [x] Add a non-invasive shadow runner that returns the authoritative parse unchanged and reports backend coverage, diagnostics, normalized-node fingerprints, and shared comptime/test construct-span parity. Differential fixtures cover comptime values, flags, imports, C imports, function/type-result declarations, type invocations, and tests.
- [x] Run shared-construct/span parity against representative collections, strings, concurrency, HTTP, memory, and test sources; descend through preprocessor wrappers while treating comptime blocks/declaration bodies as one expansion unit. This does not assert whole-tree/output parity.

### Phase 2 — grammar and runtime prototype (grammar slice complete; runtime open)

- [x] Pin upstream `tree-sitter-c` revision and license; vendor it under the dedicated parser module with a clear local patch log.
- [x] Select KTreeSitter 0.25.1 and verify generated grammar loading under Java 21 on Linux x86_64.
- [x] Measure the local Linux x86_64 parser artifact and verify its JNI resource is present in the packaged JVM jar: 149 KiB jar, containing a 1,283,272-byte `libktreesitter-c.so`; JVM integration tests load and parse through the native bridge.
- [ ] Confirm JNI loading and packaged sizes on every supported Linux, macOS, and Windows host architecture; CI is configured, but those remote host results are not available from this local run.
- [x] Install the JNI library at the runtime loader's OS/architecture resource path and configure CI smoke tests for Linux/macOS/Windows x86_64 and arm64; actual remote runner results remain pending.
- [x] Extend C grammar for one vertical slice: access-annotated methods in structs and annotated/inferred receiver parameters. Receiver calls already parse through standard C field/call nodes.
- [x] Add the next explicit statement slice: `defer statement` and `defer { ... }`, with a valid-source corpus case.
- [x] Add comptime declaration/invocation/import/flags/block, `@test`, `@throws`, and `@try`/`@catch` syntax nodes with dedicated corpus coverage.
- [x] Add an explicit AST node for platform-conditioned `@if` / `@else if` / `@else` blocks, including comptime flags in selected branches.
- [x] Add generated-parser reproducibility check and grammar tests; valid C-plus syntax uses explicit rules rather than recovery nodes.
- [x] Parse both marked and unmarked comptime value names, and keep inline comptime expressions in expression contexts without letting them steal top-level declaration parses; corpus and shadow tests cover both.
- [x] Parse the legacy `@type @name(@type T) { ... }` generator form as a named comptime type-generator node and index its symbol/body span; the legacy parser remains authoritative for evaluation.
- [x] Recognize allocator-domain/ownership annotations in declarations and local variables, access annotations on file-scope declarations, plus the Win32 `WINAPI` calling-convention macro, in the grammar. JVM fixtures check representative stdlib modules without recovery.
- [x] Parse `@assert(...)` and `@assertEquals(...)` as explicit statement nodes; AST test extraction accepts these and the legacy at-call representation.
- [x] Parse identifier splices such as `list_of_@typename(T)` and `mapper__@typename(T)__to__@typename(R)` as explicit interpolation nodes, including when used as generated type names; corpus and stable-AST/JVM tests cover them. Remaining dialect edge cases stay separate from this completed slice.

### Phase 3 — normalized AST (common C/C-plus declaration slice implemented)

- [x] Define stable AST categories, syntax-kind preservation (including interpolated identifiers), and source-span conventions independent of Tree-sitter; verify serialization through `cplus.parse.v1`.
- [x] Adapt translation units, structs/unions/enums, typedefs, fields, variables, function definitions/prototypes, methods, parameters, blocks, calls, field access, control flow, preprocessor directives, comptime/test/error-handling nodes, and error nodes. Full C/comptime normalization remains open.
- [x] Add deterministic AST dump support and golden-style assertions for reviewable parser changes.
- [x] Test supplementary-Unicode spans and malformed-buffer diagnostics against SRC-01 coordinate conventions.
- [x] Normalize explicit `@assert` / `@assertEquals` grammar nodes to a stable test-assertion AST category and verify syntax kind/span preservation.
- [x] Normalize comptime declaration/generator, invocation, inline-expression, and code-fragment nodes to generator-neutral AST categories; adapter tests pin categories while retaining original syntax kinds.

### Phase 4 — imports and comptime

- [x] Load imported C-plus files through the shared `SourceManager` and record canonical directed import edges with requesting spans; retain `#include` for C compiler handling.
- [x] Expose deterministic dependency-first ordering from the source graph; reject cycles rather than returning a partial order.
- [x] Expose the dependency-first graph order and canonical import edges (including requester spans) to compiler API consumers through `TranscodedSource`.
- [x] Verify relative, `stdlib:/`, `module:/`, and `project:/` imports, extensionless `.cp` resolution, dependency-first graph membership, and configured-root traversal rejection; existing-path symlink aliases are covered by a host-capability-aware `SourceId` canonicalization test.
- [ ] Integrate dependency ordering and import edges with IDE consumers.
- [x] Index comptime declarations, invocations, imports, generator bodies, platform conditionals, and tests from AST nodes; nested declarations in generator bodies are marked dormant until materialization. Evaluator semantics and resource limits remain unchanged.
- [x] Reparse each emitted declaration layer and preserve source origins through every iteration; the nested-generator CLI integration test materializes declarations across passes and asserts the final generated method maps to its original `.cp` source.
- [ ] Retire old comptime scanners only after all current spec examples/tests pass through both paths.

### Phase 5 — resolution and semantics

- [x] Build a first semantic index for struct/union names, fields, functions, instance/static methods, method parameters, access, and ownership/mutation annotations. Local block variables support simple explicit receiver typing.
- [x] Resolve unambiguous `value.method()` / `pointer->method()` and `Type.static_method()` calls by declared receiver/type symbols.
- [x] Preserve `@throws()` versus `@throws(error)` as distinct semantic metadata for global functions and methods, including their parameter symbols.
- [x] Resolve named typedef chains, const-qualified aliases, pointer typedefs, and typed parameters for receiver calls; local blocks shadow outer variables without leaking symbols.
- [x] Associate anonymous `struct`/`union` bodies with their typedef names so their fields and methods participate in receiver-call resolution.
- [x] Extract catch alternatives and catch-all error binding names/types as structured semantic data.
- [x] Index function-pointer typedefs as callable signatures (base result type, parameter names/base types and original parameter spellings, variadic marker, declarator span); retain the complete typedef declaration verbatim so pointer/qualifier placement remains available until fully normalized, and exclude parameter-list identifiers when resolving the typedef name.
- [x] Preserve recursively nested callable signatures when a function-pointer parameter itself accepts callback parameters, and record function-pointer return composition; an integration fixture parses, indexes, compiles, and executes both forms.
- [ ] Complete deeper nested/qualified declarator compatibility, less common qualified C typedef forms, comptime scopes, and diagnostics for invalid receiver use.
- [x] Add an end-to-end C compatibility test for a function-pointer typedef, conditional preprocessing, MSVC/GNU API attributes, and host C compile/run.
- [ ] Expand C compatibility verification across supported target compilers/dialects and additional declaration ambiguities.
- [ ] Keep unimplemented C semantic checks delegated to the selected C compiler; do not claim whole-language type checking prematurely.

### Phase 6 — runtime IR and lowering

- [x] Introduce source-spanned lowering results, mapped edits, and explicit diagnostics; build the rest of the ordered IR pipeline incrementally.
- [x] Port function-scoped `defer` to an AST pass with reverse ordering and origin preservation.
- [x] Lower resolved value, pointer, and static receiver calls to C function calls using source-spanned AST edits.
- [x] Extract struct-contained methods into ordinary C functions, add the receiver's concrete type, and preserve mapped origins.
- [x] Extract global and method `@throws` conventions from AST declarations, validate required signatures, and remove annotation spans with mapped AST edits.
- [x] Lower standalone checked calls and error-out assignment/initializer values inside AST `@try` bodies; support nested propagation, ordered catches, explicit manual error pointers, and mapped diagnostics.
- [x] Extract AST `@test` fixtures from ordinary runtime C, preserving decoded names, mapped bodies, and declaration spans.
- [x] Generate and run the test harness from AST-extracted fixtures through a compiler bridge; runtime compilation verifies assertion lowering and fixture execution.
- [x] Replace scanner-based assertion discovery/lowering for AST-extracted fixtures with source-spanned AST records for `@assert` and `@assertEquals`; compile and run both through the existing harness, verify assertion-like string contents are ignored, and report bad arity at the original call span. The legacy comptime fixture path retains its scanner until broader parity is proven.
- [x] Expand AST `defer` collection to nested control-flow blocks while retaining function-closure-wide LIFO placement; compiled C executes a nested-defer fixture and checks reverse group order and preserved in-group order.
- [x] Emit supported method headers directly from Tree-sitter declarator/parameter nodes: access/static modifiers, return declarator prefix (including pointer returns), generated C name, inferred typed `self`, remaining parameters, and original method body/prototype. Parser integration tests compile and execute instance, static, annotated, pointer-returning, and prototype methods.
- [x] Add an opt-in Tree-sitter AST allocation-intent slice for direct `alloc_*`/`calloc_*`/`realloc_*` initializers, simple lexical initializer aliases, standalone direct identifier assignments, annotated global-function parameter checks at call sites, and annotated global-function return checks; merge matching prototype/definition contracts and expose parameter/return/variable symbols plus identifier-mapped mismatches without changing the production analyzer.
- [x] Recognize `owned` double-pointer output parameters in global functions, diagnose mismatched allocator domains written through `*out`, and exclude those output parameters from input-argument checks.
- [ ] Port method contracts, expression-aware call evaluation, and branch-sensitive data flow; keep the legacy path until parity gates pass.
- [ ] Complete broad receiver inference and cover complex/qualified method declarators; unsupported AST shapes must continue to fail closed.
- [ ] For each pass, compare runtime behavior, C compiler output, source maps, and failure locations with legacy behavior.
- [ ] Retire each textual lowerer independently only after its own corpus gate; keep the others active meanwhile.

### Phase 7 — emitter and promotion

- [x] Add an opt-in prototype pipeline that composes test-fixture extraction, throws extraction, statement-oriented try/catch, defer, receiver-call, and struct-method passes, reparsing between structural stages; fail closed on comptime and C-plus imports.
- [x] Keep newly structured platform comptime conditionals inside that fail-closed boundary; regression checks the unsupported-node kind and original source span so raw `@if` cannot leak into generated C.
- [x] Compile and execute the emitted method/defer subset with host `cc`; production `CPlusTranspiler` remains untouched.
- [x] Expose prototype output through the existing `TranscodedSource`/source-map facade in addition to its mapped-text result; compile/run mapped emission and assert that a lowered method line maps to its original `.cp` declaration.
- [ ] Generate C from normalized AST nodes for the supported C/C-plus subset instead of relying on mapped source-span copying/editing; preserve origin segments during node emission.
- [ ] Compare mapped diagnostics from TCC, GCC, and Clang on supported host CI; retain `#line` until verified.
- [ ] Benchmark cold parse/transcode and incremental edit/reparse on stdlib, examples, and generated large fixtures.
- [ ] Promote Tree-sitter backend to default only after grammar, semantic, output, diagnostics, performance, and packaging gates pass.

### Phase 8/9 — IDE adoption and retirement

- [x] Preserve IDE-specific adapters and provide C-plus token/completion coverage for annotations, `self`, comptime forms, `defer`, and checked-error forms without coupling plugins to Tree-sitter internals.
- [x] Define versioned editor-facing normalized-AST JSON (`cplus.parse.v1`) with UTF-16 offsets, stable syntax/category fields, recovery flags, and source-mapped diagnostics.
- [ ] Reuse normalized parser results in VS Code, IntelliJ, and Vim consumers; keep UI/platform adapters isolated.
- [ ] Verify feature parity for syntax errors, outline, methods, completions, test gutters, and source-mapped diagnostics.
- [ ] Keep legacy backend opt-in for two stable releases after promotion; then remove it only after mismatch backlog is zero and rollback guidance is published.
- [ ] Remove superseded text scanners/lowerers and update specs, README, examples, changelog, and contributor instructions.

## Verification ledger

- Source snapshots remain parser-independent; Phase 1 adds backend selection, while the legacy backend remains the default and is not wired into transpilation yet.
- Phase 1 contract classes are in `compiler/.../FrontendParser.kt`; the legacy partial adapter reuses `ComptimeParser` and is not a claim of full C/C-plus parsing.
- Phase 1 verification: `CPlusParserShadowRunner` compares the legacy authoritative result against the selected shadow backend without replacing it. In addition to full-tree fingerprints, it normalizes recognized comptime/test declaration names (including marked `@` names) and compares exact spans for values, flags, comptime imports, C imports, function declarations (including `type` result kind), type invocations, and test declarations. Unit snippets and representative collections, strings, concurrency, HTTP, memory, and test sources pass shared-node parity. Full differential acceptance remains open until broader C-plus syntax, diagnostics, and emitted behavior are compared.
- Phase 2 grammar verification: upstream `tree-sitter-c` v0.24.2 at `b780e47fc780ddc8da13afa35a3f4ed5c157823d`; Tree-sitter CLI 0.25.10 / ABI 15; all 91 upstream+extension corpus cases pass, and generated parser source matches regeneration. Extension cases cover methods, defer, marked/unmarked comptime values, inline comptime expressions, imports, tests, platform-conditioned `@if` chains, `@throws`, `@try`/`@catch`, comptime identifier interpolation, and nested function-pointer declarators.
- Phase 2 repository-syntax verification: JVM integration tests parse dynamic list/map, string, thread-pool, HTTP client/server, xmem, and container test modules without Tree-sitter recovery. Coverage includes legacy `@type`, type-valued comptime invocation arguments, assertion statements, file-scope access annotations, allocator-domain annotations, and `WINAPI`.
- Latest verification: legacy-output golden, interpolation/AST, import-namespace, nested callback-signature, verbatim typedef-spelling, and AST allocation-contract tests pass. The shared legacy/Tree-sitter construct gate passes for eight representative standard-library files and the interpolation fixture. Nested function-pointer parameters and two return-composition layers compile and execute with host `cc`. All 91 Tree-Sitter corpus cases pass, generated parser source matches regeneration, and `./gradlew test build --no-daemon` passes on Linux x86_64 / JDK 21. Other host-matrix jobs remain unexecuted. `git diff --check` is clean for the current changes.
- Phase 2/3 JVM prototype verification: KTreeSitter 0.25.1 parses via JNI under Temurin 21 on Linux x86_64; CMake 3.28.3 / GCC 13.3.0 builds and loads the grammar bridge. The Linux x86_64 JVM jar is 149 KiB and includes a 1,283,272-byte JNI library. Host-specific packaging now targets runtime-loader paths, and CI is configured for all six supported OS/architecture combinations. Remote macOS/Windows/Linux-arm64/Windows-arm64 results are pending; no production parser selection is enabled.
- Phase 8 groundwork verification: `CPlusParseJson` serializes the same normalized AST and diagnostics independently of editor platform; the schema is versioned and explicitly declares UTF-16 offsets. Tests cover escaping, Unicode-containing buffers, category serialization for C declarations/control flow and comptime interpolation, and Node JSON parsing when Node is available. No plugin consumes this contract yet.
- Phase 4 graph verification: compiler tests cover deterministic DAG edges, dependency-first ordering, cycle paths, shared-manager imported snapshots, root-import-site cycle diagnostics, relative and prefixed `stdlib:/`, `module:/`, and `project:/` imports, extensionless source resolution, and rejection of prefixed-root traversal. `TranscodedSource.sourceOrder` and `sourceImports` expose canonical dependency order and requesting edges; CLI integration verifies imported source ordering. `CPlusComptimeIndexer` extracts syntax nodes/spans and symbols for declarations, invocations, imports, generator bodies, and tests, distinguishing active declarations from dormant nested declarations. Multi-pass materialization tests verify generated methods retain a `.cp` source origin. The legacy evaluator remains the active materializer; IDE graph use and AST-driven expansion remain open.
- Phase 5 semantic-index verification: tests cover struct/method and top-level function symbols, access and ownership/mutation metadata, explicit instance/static call resolution, named alias chains, const-qualified aliases, pointer typedefs, anonymous struct typedef ownership and method resolution, typed parameters, nested local shadowing, distinct `@throws()` / `@throws(error)` conventions with error-out parameter placement, and catch code/binding extraction. Function-pointer typedefs retain callable signature metadata (base result, parameter names/base types and source spellings, variadic state, declarator span) plus the complete original typedef declaration, including pointer/qualifier spelling. Callback parameters recurse and two nested function-pointer return-composition layers are indexed; a C interoperability regression compiles/runs fixed, variadic, nested callback, and callback-return typedefs on host `cc`. More complex qualified declarator normalization, target compiler coverage, and comptime-scope semantics remain open.
- Phase 6/7 foundation verification: `TreeSitterCPlusPrototypeTranspiler` composes AST-bounded `@test` extraction, throws extraction/removal, statement-oriented checked-call/try-catch lowering, defer, value (`&value`), pointer (`pointer`), static-call, and struct-method passes, reparsing between stages while retaining `MappedText` origins. Test extraction returns decoded fixture names, mapped compound bodies, source spans, and AST-indexed assertion records for `@assert` / `@assertEquals`; it removes declarations before ordinary runtime lowering. `CPlusTranspiler.transpileExtractedTests` uses those records rather than legacy fixture/assertion scanning, and a host-`cc` integration test compiles/runs both assertion forms. The legacy comptime fixture path still uses its scanner. AST defer now moves nested control-flow defers to the owning function end in reverse discovery order, preserving statement order within a deferred block; the host-`cc` regression executes the generated function and verifies this exact order. The prototype's opt-in `TreeSitterAllocationIntentAnalyzer` reports direct annotated-variable/allocator domain mismatches and retains identifier-mapped symbol/provenance metadata; alias flow, function contracts, and call-site analysis remain with the legacy analyzer. Runtime fixtures cover both throws conventions, prototype+definition agreement, checked method calls, nested unmatched-error propagation, and method value/pointer/static receiver behavior; they compile and execute with system `cc` on Linux x86_64. Invalid signatures and unsupported embedded checked-call expressions produce diagnostics mapped to original `.cp` spans. Remaining checked-call limits: one checked call per statement, standalone calls or error-out assignment/initializer values only. The prototype still rejects comptime and C-plus imports. Supported method headers are emitted from AST declarator and parameter nodes; pointer-returning definitions and prototypes are included in host-`cc` tests. `MethodLowerer` is no longer called by this pass. Complex declarator coverage remains open. Production `CPlusTranspiler` remains unchanged and authoritative.
- Phase 6 allocation-analysis verification: targeted JVM tests exercise direct and aliased AST-domain mismatches, positive matching provenance, direct reassignment, global-function parameter checks at call sites, return contracts merged across prototype/definition pairs, owned double-pointer output writes and their call-site treatment, block-scope shadowing/non-leakage, non-propagation from a conditional assignment, identifier-exact source spans, and propagation into both prototype and `TranscodedSource` results. Method contracts and branch-sensitive parity are deliberately not claimed; the legacy analyzer remains the production path.
- Allocation analyzer status clarification for the Phase 6/7 foundation summary above: the AST prototype now handles simple aliases, function parameter/return contracts, and owned double-pointer output writes. It remains opt-in; method contracts, complex expressions, and branch-sensitive dataflow are still legacy-only.
- Required commands for each compiler change: `./gradlew :compiler:test`, `./gradlew test`, and `./gradlew build` when public packaging or module wiring changes.
- Each parser phase adds small targeted tests before broad fixtures. Record tested platform/JDK/binding versions in this file when native integration begins.
- Do not mark a row complete based only on compilation: include behavior parity, span checks, and regression-suite results.
