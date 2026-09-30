# Compiler Frontend Phases 7–8 Follow-up

This is the execution ledger for the remaining migration work after the Phase 1–6
Tree-sitter grammar, AST adaptation, comptime, scope, source-map, and differential
contracts. It supplements the broad checklist in
[`COMPILER-FRONTEND-IMPLEMENTATION.md`](COMPILER-FRONTEND-IMPLEMENTATION.md).
The legacy frontend remains selectable and is not removed by any task below.

## Status and evidence rules

- `DONE`: implementation and the task-specific regression gate pass locally.
- `DONE-LOCAL`: local evidence is complete, but host/interactive/release evidence remains.
- `IN PROGRESS`: implementation or one of its required evidence classes is incomplete.
- `OPEN`: dependency is ready, but work has not started.
- `RETAINED-FALLBACK`: parity is proven for the bounded slice; legacy code is intentionally retained.

Every task needs a focused test, the aggregate `./gradlew --offline --no-daemon test
--max-workers=1 -Dorg.gradle.native=false --console=plain` gate, and a documentation row.
Native or interactive tasks additionally need their named host/editor evidence.

## Phase 7 — AST emission and promotion

| ID | Task | Depends on | Status | Evidence / next action |
|---|---|---|---|---|
| P7.1 | Emit normalized AST terminals with deterministic C formatting | P2, P6 | IN PROGRESS | Terminal emission, preprocessor preservation, literal integrity, punctuation, recovery rejection, reparsing, and C11 execution are covered. Replace remaining context heuristics with grammar-aware formatting and expand the corpus. |
| P7.2 | Preserve mapped origins through AST emission and diagnostics | P4, P6 | DONE-LOCAL | `#line`, mapped diagnostics, token origins, and pre-hygiene source revisions pass local and supported-host parser CI gates. |
| P7.3 | Publish semantic declaration handles | P4, P5 | DONE-LOCAL | Generated aliases, tags, variables, functions, struct/union members, method declarations, source origins, and `TranscodedSource` parity are covered by parser fixtures. Add member type/ownership metadata next. |
| P7.4 | Validate the hygiene boundary | P7.1 | DONE | Pre-hygiene C-plus is reparsed; post-hygiene `CPLUS_*` macros are validated as host C. The boundary is documented and tested. |
| P7.5 | Prove deterministic output and performance | P7.1 | DONE-LOCAL | Repeated output/origin equality, incremental Tree-sitter reuse, representative benchmarks, and tagged host benchmark jobs pass. A stable production budget remains a promotion input. |
| P7.6 | Run repository acceptance through the AST path | P7.1–P7.5 | DONE-LOCAL | 104/104 grammar fixtures, repository C-plus syntax corpus, stdlib fixtures, and finite non-Raylib examples pass locally. Raylib runtime/linking remains host-dependent. |
| P7.7 | Promote Tree-sitter as default frontend | P7.1–P7.6 | OPEN | Run the promotion checklist, compare release behavior, retain an explicit rollback switch for two releases, then decide default selection. |
| P7.8 | Retire textual lowerers independently | P7.7 | RETAINED-FALLBACK | Method/receiver, defer, try/catch, test extraction, and allocation lowerers have bounded parity/rollback evidence. Deletion is deferred until promotion and the two-release soak finish. |

## Phase 8 — LSP and editor adoption

| ID | Task | Depends on | Status | Evidence / next action |
|---|---|---|---|---|
| P8.1 | Define and serve `cplus.parse.v1` normalized AST JSON | P2 | DONE | UTF-16 spans, categories, recovery flags, diagnostics, saved/stdin CLI modes, and malformed-input behavior are tested. |
| P8.2 | VS Code parser, outline, tests, diagnostics, and restart client | P8.1 | DONE-LOCAL | npm tests, parser-backed outline/Test Explorer, stale revision handling, diagnostics, child restart, and VSIX packaging pass. |
| P8.3 | Vim parser, outline, quickfix, imports, and restart client | P8.1 | DONE-LOCAL | Headless parser/outline/import/restart fixtures and archive packaging pass. |
| P8.4 | IntelliJ parser cache, annotator, structure, navigation, tests | P8.1 | DONE-LOCAL | IntelliJ 2026.2.2 unit/plugin packaging tests and exact-source cache fixtures pass. |
| P8.5 | Interactive IntelliJ LSP startup, features, and restart | P8.1, P8.4 | OPEN | Build a real IDE-client fixture for startup/sync, diagnostics, completion, navigation, test gutter, unexpected exit, and restart. This is the main editor evidence gap. |
| P8.6 | Supported-host packaged LSP protocol smoke | P8.1, packaging | DONE | CI run `36647197070` passed framed LSP startup/shutdown for the supported bare/cross distributions and host matrix. |
| P8.7 | Bounded LSP scheduling, cancellation, and revision safety | P8.1 | DONE-LOCAL | Two workers, bounded queue, overload response, cancellation lifetime, malformed edit matrix, sustained benchmark, and protocol reliability gates pass; repeat host soak if the release matrix changes. |
| P8.8 | Compiler-aligned semantic resolution | P5, P8.1 | IN PROGRESS | Receiver chains, methods, overload ranking, ambiguity diagnostics, casts, unary forms, literal suffixes, callback effects, and generated comptime aliases are covered. Remaining: C promotion legality, pointer/member conversions, richer writes, and complete comptime scope parity. |
| P8.9 | Cross-client crash/restart behavior | P8.2–P8.5 | IN PROGRESS | VS Code and Vim restart fixtures pass, including bounded automatic recovery; IntelliJ unexpected-exit/restart evidence is still open. |
| P8.10 | Two-release legacy soak and migration rollback | P7.7, P8.2–P8.9 | OPEN | After promotion, publish mismatch reporting and rollback instructions, keep legacy selectable for two stable releases, then audit before removal. |

## What the recent work actually covered

The recent implementation sequence was not one unresolved parser task. It closed these
atomic slices, each with focused and aggregate tests:

1. Callable ambiguity now fails closed instead of selecting by declaration order.
2. Receiver-free functions and same-named methods are separated during lookup.
3. Receiver return inference handles chained calls and known arity failures.
4. Receiver matching covers value/pointer forms, `(&x).field`, and `(*p).field`.
5. Argument inference covers dereference, unary operators, explicit casts, and C literal suffixes.
6. Ambiguous calls produce mapped `CPLUS_AMBIGUOUS_CALL` diagnostics.
7. Generated comptime aliases/methods participate in completion, references, highlights, and invalidation.
8. The AST emitter rejects unknown C-plus nodes, preserves deterministic output, and lowers structured/static methods.
9. Synthesized declarations now match by kind and full mapped-origin overlap; generated aggregate member handles are exposed.

The latest aggregate result is green with 104/104 grammar parses. The current worktree
also contains unrelated Raylib example changes; they are intentionally not part of this
frontend ledger or its commits.

## Immediate queue

1. **P7.1:** replace the remaining AST-emitter spacing heuristics with grammar-context
   formatting tests for declarators, attributes, comments, and nested expressions.
2. **P7.3:** add member type/ownership metadata without coupling editor consumers to
   parser-generator nodes.
3. **P8.8:** extend semantic resolution only where the compiler contract has an explicit
   bounded rule; unsupported conversions must remain conservative and mapped.
4. **P8.5/P8.9:** add the real IntelliJ interactive startup/restart evidence.
5. **P7.7/P8.10:** do not promote or delete legacy code until the above evidence and the
   two-release rollback policy are recorded.
