# Compiler Frontend Migration Follow-up

This is the implementation checklist for [`COMPILER-FRONTEND-ARCHITECTURE.md`](COMPILER-FRONTEND-ARCHITECTURE.md). Statuses describe repository work, not promises. A phase advances only when its acceptance checks pass; the legacy compiler remains the default until Phase 7 promotion.

## Component register

| ID | Component / deliverable | Phase | Current status | Acceptance / expiry |
|---|---|---:|---|---|
| SRC-01 | Source identity, immutable snapshots, revisions, UTF-8/UTF-16 coordinate map | 0 | Complete | Unit tests cover Unicode boundaries, revisions, canonical file loading; the full CLI suite passes unchanged. Becomes foundation for every parser and diagnostic. |
| PAR-01 | Parser backend/result/selection contract | 1 | Contract implemented; differential gate open | Generator-neutral nodes/results, registry selection, and explicitly partial legacy scanning are covered by tests. Differential comparison awaits TS-01/TS-02. Legacy adapter expires only after new backend promotion. |
| TS-01 | Pinned Tree-sitter C grammar/runtime integration | 2 | Linux x86_64/JDK 21 works; multi-host gate open | Reproducible generation, Java 21-compatible loading/package proof on each supported host, license/provenance, CI parser build. |
| TS-02 | C-plus grammar extension and corpus | 2 | Explicit grammar slices for methods, defer, comptime, tests, and error handling | Named nodes for supported constructs; all upstream C and C-plus corpus passes. |
| AST-01 | Stable C-plus CST adapter and AST schema | 3 | Core C/C-plus declarations and control-flow categories normalized; full-language normalization open | Adapter coverage, span fidelity, unknown/recovery preservation, grammar upgrade tests. |
| MOD-01 | Canonical source/import graph and C/C-plus boundary | 4 | Dependency-first order and canonical edges now exposed on `TranscodedSource`; IDE integration and path resolver coverage remain open | Relative/module/stdlib resolution, canonical identity, cycle diagnostics, C includes unchanged. |
| CT-01 | AST-backed comptime parsing/materialization | 4 | AST syntax index implemented; materializer remains legacy | Existing limits/security retained; generated declarations reparse to fixed point; multi-pass maps preserved. Text scanners expire after parity gate. |
| SEM-01 | Scope, symbols, method/type resolution, annotation metadata | 5 | Struct/method and simple typedef-chain resolution plus parameterized function symbols and explicit throws conventions indexed; full C semantics open | C typedef forms, nested scopes, comptime scopes, diagnostics; ownership remains advisory. |
| IR-01 | Source-spanned runtime IR and ordered lowering pipeline | 6 | Shared AST edit/result contracts implemented; IR pipeline open | Each pass has invariants and isolated golden tests; no comptime nodes reach C lowering. |
| LOWER-01 | AST lowering for methods, defer, try/catch, tests, allocation diagnostics | 6 | Opt-in AST pipeline now lowers validated throws contracts and statement-oriented checked calls/try-catch, in addition to methods and defer; tests and broader parity remain open | Differential output and behavior parity. Test harness extraction, broad defer scopes, and legacy retirement remain open. |
| EMIT-01 | Structured C emitter, origin segments, diagnostic mapping | 7 | Experimental Tree-sitter subset emits mapped, compiler-validated C; general AST emission open | Full C output compiles; mapped diagnostics cover generated/imported code; `#line` remains until toolchain matrix passes. |
| IDE-01 | Shared parser-backed editor indexing/diagnostics/test discovery | 8 | Versioned normalized-AST JSON contract added; editor consumers not connected | Existing editor capabilities preserved, incomplete buffers recover, CLI diagnostics agree with editor locations. |
| VERIFY-01 | Differential, fuzz, compatibility, performance, platform suite | 0–8 | Not started | Runs continuously; gates parser promotion and each legacy retirement. |
| RETIRE-01 | Legacy backend/lowerer removal | 9 | Not started | New backend default for two stable releases, no open corpus mismatches, rollback documented, all component gates passed. |

## Phase sequence

### Phase 0 — source foundation (current)

- [x] SRC-01: establish source IDs, snapshots, revisioning, and exact UTF-8/UTF-16 boundary conversion.
- [ ] Integrate imported comptime sources with the manager while retaining `SourceFile` compatibility.
- [ ] Add deterministic import graph ownership and cycle diagnostics (MOD-01 groundwork).
- [ ] Keep emitted C byte-for-byte stable for existing fixtures where possible; compare source maps where `#line` formatting legitimately differs.

### Phase 1 — switchable frontend contract (shadow comparison prototype implemented)

- [x] Define parser input/options/result and backend ID without generator-specific node types.
- [x] Add registry selection with `legacy` as default; no CLI behavior or generated C changes.
- [x] Adapt the existing comptime scanner's recognized items to neutral syntax nodes; explicitly label all other text as opaque.
- [x] Add a non-invasive shadow runner that returns the authoritative parse unchanged and reports backend coverage, diagnostic, and normalized-node fingerprint differences.

### Phase 2 — grammar and runtime prototype (grammar slice complete; runtime open)

- [x] Pin upstream `tree-sitter-c` revision and license; vendor it under the dedicated parser module with a clear local patch log.
- [x] Select KTreeSitter 0.25.1 and verify generated grammar loading under Java 21 on Linux x86_64.
- [ ] Measure packaged parser/runtime size and build/test JNI loading for all supported Linux, macOS, and Windows host architectures.
- [x] Extend C grammar for one vertical slice: access-annotated methods in structs and annotated/inferred receiver parameters. Receiver calls already parse through standard C field/call nodes.
- [x] Add the next explicit statement slice: `defer statement` and `defer { ... }`, with a valid-source corpus case.
- [x] Add comptime declaration/invocation/import/flags/block, `@test`, `@throws`, and `@try`/`@catch` syntax nodes with dedicated corpus coverage.
- [x] Add generated-parser reproducibility check and grammar tests; valid C-plus syntax uses explicit rules rather than recovery nodes.
- [ ] Expand grammar for conditional comptime forms, all documented interpolation syntax, and dialect edge cases; avoid a broad untestable rewrite.

### Phase 3 — normalized AST (common C/C-plus declaration slice implemented)

- [x] Define stable AST categories, syntax-kind preservation, and source-span conventions independent of Tree-sitter.
- [x] Adapt translation units, structs/unions/enums, typedefs, fields, variables, function definitions/prototypes, methods, parameters, blocks, calls, field access, control flow, preprocessor directives, comptime/test/error-handling nodes, and error nodes. Full C/comptime normalization remains open.
- [x] Add deterministic AST dump support and golden-style assertions for reviewable parser changes.
- [x] Test supplementary-Unicode spans and malformed-buffer diagnostics against SRC-01 coordinate conventions.

### Phase 4 — imports and comptime

- [x] Load imported C-plus files through the shared `SourceManager` and record canonical directed import edges with requesting spans; retain `#include` for C compiler handling.
- [x] Expose deterministic dependency-first ordering from the source graph; reject cycles rather than returning a partial order.
- [x] Expose the dependency-first graph order and canonical import edges (including requester spans) to compiler API consumers through `TranscodedSource`.
- [ ] Integrate ordering with IDE consumers; verify symlink/canonical identity and all path namespaces.
- [x] Index comptime declarations, invocations, imports, generator bodies, and tests from AST nodes; nested declarations in generator bodies are marked dormant until materialization. Evaluator semantics and resource limits remain unchanged.
- [ ] Reparse each emitted declaration layer and preserve source origins through every iteration.
- [ ] Retire old comptime scanners only after all current spec examples/tests pass through both paths.

### Phase 5 — resolution and semantics

- [x] Build a first semantic index for struct/union names, fields, functions, instance/static methods, method parameters, access, and ownership/mutation annotations. Local block variables support simple explicit receiver typing.
- [x] Resolve unambiguous `value.method()` / `pointer->method()` and `Type.static_method()` calls by declared receiver/type symbols.
- [x] Preserve `@throws()` versus `@throws(error)` as distinct semantic metadata for global functions and methods, including their parameter symbols.
- [x] Resolve simple named typedef chains and typed parameters for receiver calls; local blocks shadow outer variables without leaking symbols.
- [x] Extract catch alternatives and catch-all error binding names/types as structured semantic data.
- [ ] Complete function-pointer/anonymous/qualified C typedef forms, comptime scopes, and diagnostics for invalid receiver use.
- [ ] Add C compatibility tests for declaration ambiguities, preprocessor directives, and target dialects.
- [ ] Keep unimplemented C semantic checks delegated to the selected C compiler; do not claim whole-language type checking prematurely.

### Phase 6 — runtime IR and lowering

- [x] Introduce source-spanned lowering results, mapped edits, and explicit diagnostics; build the rest of the ordered IR pipeline incrementally.
- [x] Port function-scoped `defer` to an AST pass with reverse ordering and origin preservation.
- [x] Lower resolved value, pointer, and static receiver calls to C function calls using source-spanned AST edits.
- [x] Extract struct-contained methods into ordinary C functions, add the receiver's concrete type, and preserve mapped origins.
- [x] Extract global and method `@throws` conventions from AST declarations, validate required signatures, and remove annotation spans with mapped AST edits.
- [x] Lower standalone checked calls and error-out assignment/initializer values inside AST `@try` bodies; support nested propagation, ordered catches, explicit manual error pointers, and mapped diagnostics.
- [ ] Port test harness extraction and allocation diagnostics; retain legacy text lowerers until output/runtime parity gates pass.
- [ ] Complete broad receiver inference and method declaration lowering without relying on the legacy method-header normalizer.
- [ ] For each pass, compare runtime behavior, C compiler output, source maps, and failure locations with legacy behavior.
- [ ] Retire each textual lowerer independently only after its own corpus gate; keep the others active meanwhile.

### Phase 7 — emitter and promotion

- [x] Add an opt-in prototype pipeline that composes throws extraction, statement-oriented try/catch, defer, receiver-call, and struct-method passes, reparsing between structural stages; fail closed on comptime, tests, and C-plus imports.
- [x] Compile and execute the emitted method/defer subset with host `cc`; production `CPlusTranspiler` remains untouched.
- [ ] Emit C from structured nodes with source-origin segments and stable `TranscodedSource` facade.
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
- Phase 1 verification: `CPlusParserShadowRunner` compares the legacy authoritative result against the selected shadow backend without replacing it; its focused regression test passes. Full differential acceptance remains open until parser coverage is broad enough for representative repository sources.
- Phase 2 grammar verification: upstream `tree-sitter-c` v0.24.2 at `b780e47fc780ddc8da13afa35a3f4ed5c157823d`; Tree-sitter CLI 0.25.10 / ABI 15; all 89 upstream+extension corpus cases pass, and generated parser source matches regeneration. Extension cases cover methods, defer, comptime, tests, `@throws`, and `@try`/`@catch`.
- Phase 2/3 JVM prototype verification: KTreeSitter 0.25.1 parses via JNI under Temurin 21 on Linux x86_64; CMake 3.28.3 / GCC 13.3.0 builds the grammar bridge. Tests cover method/call nodes, supplementary-Unicode source mapping, recovery, C declaration prototypes and AST categories (struct/union/enum/typedef/field/variable/function/preprocessor/control-flow), comptime indexing, throws metadata, and try/catch nodes. Other OS/architectures remain a hard distribution gate; no production parser selection is enabled.
- Phase 8 groundwork verification: `CPlusParseJson` serializes the same normalized AST and diagnostics independently of editor platform; the schema is versioned and explicitly declares UTF-16 offsets. Tests cover escaping, Unicode-containing buffers, category serialization for C declarations/control flow, and Node JSON parsing when Node is available. No plugin consumes this contract yet.
- Phase 4 graph verification: compiler tests cover deterministic DAG edges, dependency-first ordering, and cycle paths; imported `.cp` sources use the transpiler's `SourceManager`. `TranscodedSource.sourceOrder` and `sourceImports` expose the resolved canonical dependency order and requesting edges; the CLI integration test verifies imported source precedes its root. `CPlusComptimeIndexer` extracts syntax nodes/spans for declarations, invocations, imports, generator bodies, and tests, and distinguishes active declarations from those dormant inside generator bodies. The legacy evaluator remains the active materializer; IDE graph use, full path-identity coverage, and fixed-point AST-driven expansion remain open.
- Phase 5 semantic-index verification: tests cover struct/method and top-level function symbols, access and ownership/mutation metadata, explicit instance/static call resolution, simple typedef chains, parameter receiver types, nested local shadowing, distinct `@throws()` / `@throws(error)` conventions with error-out parameter placement, and catch code/binding extraction. Full C typedef forms and comptime-scope semantics remain open.
- Phase 6/7 foundation verification: `TreeSitterCPlusPrototypeTranspiler` composes AST-bounded throws extraction/removal, statement-oriented checked-call/try-catch lowering, defer, value (`&value`), pointer (`pointer`), static-call, and struct-method passes, reparsing between stages while retaining `MappedText` origins. Runtime fixtures cover both throws conventions, prototype+definition agreement, checked method calls, nested unmatched-error propagation, and method value/pointer/static receiver behavior; they compile and execute with system `cc` on Linux x86_64. Invalid signatures and unsupported embedded checked-call expressions produce diagnostics mapped to original `.cp` spans. Remaining checked-call limits: one checked call per statement, standalone calls or error-out assignment/initializer values only. The prototype still rejects comptime, tests, and C-plus imports. Method-header normalization delegates to the legacy routine within AST-bounded method spans. Defer supports only direct function-body statements and diagnoses conditional/nested scopes. Production `CPlusTranspiler` remains unchanged and authoritative.
- Required commands for each compiler change: `./gradlew :compiler:test`, `./gradlew test`, and `./gradlew build` when public packaging or module wiring changes.
- Each parser phase adds small targeted tests before broad fixtures. Record tested platform/JDK/binding versions in this file when native integration begins.
- Do not mark a row complete based only on compilation: include behavior parity, span checks, and regression-suite results.
