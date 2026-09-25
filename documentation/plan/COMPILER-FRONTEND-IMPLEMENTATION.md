# Compiler Frontend Migration Follow-up

This is the implementation checklist for [`COMPILER-FRONTEND-ARCHITECTURE.md`](COMPILER-FRONTEND-ARCHITECTURE.md). Statuses describe repository work, not promises. A phase advances only when its acceptance checks pass; the legacy compiler remains the default until Phase 7 promotion.

## Component register

| ID | Component / deliverable | Phase | Current status | Acceptance / expiry |
|---|---|---:|---|---|
| SRC-01 | Source identity, immutable snapshots, revisions, UTF-8/UTF-16 coordinate map | 0 | Complete | Unit tests cover Unicode boundaries, revisions, canonical file loading; the full CLI suite passes unchanged. Becomes foundation for every parser and diagnostic. |
| PAR-01 | Parser backend/result/selection contract | 1 | Not started | Contract tests and legacy backend produces identical output/diagnostics. Legacy adapter expires only after new backend promotion. |
| TS-01 | Pinned Tree-sitter C grammar/runtime integration | 2 | Not started | Reproducible generation, Java 21-compatible loading/package proof, license/provenance, CI parser build. |
| TS-02 | C-plus grammar extension and corpus | 2 | Not started | Named nodes for documented syntax; valid and malformed corpus; no dependence on recovery nodes for valid extension boundaries. |
| AST-01 | Stable C-plus CST adapter and AST schema | 3 | Not started | Adapter coverage, span fidelity, unknown/recovery preservation, grammar upgrade tests. |
| MOD-01 | Canonical source/import graph and C/C-plus boundary | 4 | Not started | Relative/module/stdlib resolution, deterministic graph, cycle diagnostics, C includes unchanged. |
| CT-01 | AST-backed comptime parsing/materialization | 4 | Not started | Existing limits/security retained; generated declarations reparse to fixed point; multi-pass maps preserved. Text scanners expire after parity gate. |
| SEM-01 | Scope, symbols, method/type resolution, annotation metadata | 5 | Not started | C typedefs, methods, static calls, comptime scopes, throws resolution; ownership remains advisory. |
| IR-01 | Source-spanned runtime IR and ordered lowering pipeline | 6 | Not started | Each pass has invariants and isolated golden tests; no comptime nodes reach C lowering. |
| LOWER-01 | AST lowering for methods, defer, try/catch, tests, allocation diagnostics | 6 | Not started | Differential output and behavior parity. Corresponding text lowerers expire individually only after their focused gates pass. |
| EMIT-01 | Structured C emitter, origin segments, diagnostic mapping | 7 | Not started | C output compiles; mapped diagnostics cover generated/imported code; `#line` remains until toolchain matrix passes. |
| IDE-01 | Shared parser-backed editor indexing/diagnostics/test discovery | 8 | Not started | Existing editor capabilities preserved, incomplete buffers recover, CLI diagnostics agree with editor locations. |
| VERIFY-01 | Differential, fuzz, compatibility, performance, platform suite | 0–8 | Not started | Runs continuously; gates parser promotion and each legacy retirement. |
| RETIRE-01 | Legacy backend/lowerer removal | 9 | Not started | New backend default for two stable releases, no open corpus mismatches, rollback documented, all component gates passed. |

## Phase sequence

### Phase 0 — source foundation (current)

- [x] SRC-01: establish source IDs, snapshots, revisioning, and exact UTF-8/UTF-16 boundary conversion.
- [ ] Integrate imported comptime sources with the manager while retaining `SourceFile` compatibility.
- [ ] Add deterministic import graph ownership and cycle diagnostics (MOD-01 groundwork).
- [ ] Keep emitted C byte-for-byte stable for existing fixtures where possible; compare source maps where `#line` formatting legitimately differs.

### Phase 1 — switchable frontend contract

- [ ] Define parser input/options/result and backend ID without generator-specific node types.
- [ ] Add internal `legacy`/`tree-sitter` backend selection; default remains `legacy`.
- [ ] Adapt existing scans as far as possible without pretending textual passes constitute a full AST. Mark unsupported legacy parse data explicitly.
- [ ] Add shadow/differential execution that reports mismatches without changing output.

### Phase 2 — grammar and runtime prototype

- [ ] Pin upstream `tree-sitter-c` revision and license; vendor it under the dedicated parser module with a clear local patch log.
- [ ] Select a Java 21-compatible Kotlin/JVM binding or build a narrow supported native bridge. Measure artifact size and test Linux, macOS, and Windows loading before choosing.
- [ ] Extend C grammar for one vertical slice: annotated C declarations plus methods in structs and receiver calls.
- [ ] Add generated-parser reproducibility check and grammar tests; use explicit C-plus rules for valid syntax and recovery nodes only for incomplete/error source.
- [ ] Expand grammar slice-by-slice to comptime, tests, defer, and error handling; avoid a broad untestable grammar rewrite.

### Phase 3 — normalized AST

- [ ] Define syntax types and source-span conventions independent of Tree-sitter.
- [ ] Adapt declarations, types, expressions, statements, method members/calls, comptime forms, and recovery nodes.
- [ ] Add AST dump/golden test support for reviewable parser changes.
- [ ] Test Unicode node spans against SRC-01 and malformed-buffer stability.

### Phase 4 — imports and comptime

- [ ] Move import discovery to source snapshots and a canonical graph; retain `#include` for C compiler handling.
- [ ] Port comptime declaration recognition and invocation sites to AST nodes; keep evaluator semantics and resource limits.
- [ ] Reparse each emitted declaration layer and preserve source origins through every iteration.
- [ ] Retire old comptime scanners only after all current spec examples/tests pass through both paths.

### Phase 5 — resolution and semantics

- [ ] Build scopes, typedef/type names, functions, fields, instance/static methods, and comptime symbol tables.
- [ ] Resolve calls and annotations by symbols rather than matching source text.
- [ ] Add C compatibility tests for declaration ambiguities, preprocessor directives, and target dialects.
- [ ] Keep unimplemented C semantic checks delegated to the selected C compiler; do not claim whole-language type checking prematurely.

### Phase 6 — runtime IR and lowering

- [ ] Introduce ordered AST passes with declared preconditions/postconditions and diagnostics.
- [ ] Port receiver adjustment/method naming, then `defer`, then `try/catch`, then test harness extraction, then allocation metadata diagnostics.
- [ ] For each pass, compare runtime behavior, C compiler output, source maps, and failure locations with legacy behavior.
- [ ] Retire each textual lowerer independently only after its own corpus gate; keep the others active meanwhile.

### Phase 7 — emitter and promotion

- [ ] Emit C from structured nodes with source-origin segments and stable `TranscodedSource` facade.
- [ ] Compare mapped diagnostics from TCC, GCC, and Clang on supported host CI; retain `#line` until verified.
- [ ] Benchmark cold parse/transcode and incremental edit/reparse on stdlib, examples, and generated large fixtures.
- [ ] Promote Tree-sitter backend to default only after grammar, semantic, output, diagnostics, performance, and packaging gates pass.

### Phase 8/9 — IDE adoption and retirement

- [ ] Reuse normalized parser results in VS Code, IntelliJ, and Vim consumers; keep UI/platform adapters isolated.
- [ ] Verify feature parity for syntax errors, outline, methods, completions, test gutters, and source-mapped diagnostics.
- [ ] Keep legacy backend opt-in for two stable releases after promotion; then remove it only after mismatch backlog is zero and rollback guidance is published.
- [ ] Remove superseded text scanners/lowerers and update specs, README, examples, changelog, and contributor instructions.

## Verification ledger

- First transition: source snapshots are parser-independent; no parser backend selected yet.
- Required commands for each compiler change: `./gradlew :compiler:test`, `./gradlew test`, and `./gradlew build` when public packaging or module wiring changes.
- Each parser phase adds small targeted tests before broad fixtures. Record tested platform/JDK/binding versions in this file when native integration begins.
- Do not mark a row complete based only on compilation: include behavior parity, span checks, and regression-suite results.
