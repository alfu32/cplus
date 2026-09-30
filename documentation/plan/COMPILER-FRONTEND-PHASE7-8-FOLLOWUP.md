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

## Execution-level breakdown

The phase rows above are release gates, not individual coding tasks. The following
subtasks are the current work units. A subtask is only moved to `DONE` after its
focused test and aggregate gate are recorded; `DONE-LOCAL` means a host, IDE, or
release gate is still missing.

### Principal versus ancillary work

The ledger uses this accounting rule:

- **Principal task:** a capability required to achieve a phase gate, such as AST
  emission, semantic handles, or compiler-aligned LSP resolution. Principal tasks
  determine migration progress.
- **Ancillary/mopup:** corrections and evidence discovered while completing a
  principal task, such as spacing fixes, extra overload cases, regression fixtures,
  portability repairs, and documentation updates. They remain attributed to their
  parent and are not counted as separate migration goals.

Consequently, commit count is not task count. The recent semantic-resolution commits
are one principal P8.8 task with several ancillary passes, not nine independent parser
projects:

| Principal task                       | Ancillary/mopup included                                                                                              | Current result                                       |
|--------------------------------------|-----------------------------------------------------------------------------------------------------------------------|------------------------------------------------------|
| P7.1 AST emission                    | comma spacing, literal/preprocessor preservation, declarator and method-emission fixtures, unsupported-node rejection | DONE-LOCAL; cross-host promotion evidence remains |
| P7.3 declaration handles             | declaration-kind matching, mapped-origin overlap, generated aggregate members, semantic type/ownership metadata, source-revision boundary tests | DONE-LOCAL; consumer-level invalidation remains |
| P8.8 compiler-aligned LSP resolution | receiver forms, dereference/unary arguments, casts, literal suffixes, ambiguity diagnostics, generated aliases, C promotions, pointer conversions, writes, scope invalidation, and advisory metadata | DONE-LOCAL for the bounded matrix; richer analysis remains intentionally delegated |
| P8.9 client recovery                 | VS Code/Vim restart and stale-revision fixtures                                                                       | IN PROGRESS; IntelliJ interactive evidence remains   |

This prevents corrective commits from inflating the apparent amount of principal work
completed while still keeping every regression and its evidence traceable.

### Phase 7 active and deferred work

| ID     | Parent | Subtask                                                                                                       | Status      | Exit evidence                                                                        |
|--------|--------|---------------------------------------------------------------------------------------------------------------|-------------|--------------------------------------------------------------------------------------|
| P7.1.a | P7.1   | Preserve terminal spelling for literals, preprocessor regions, and comments                                   | DONE-LOCAL  | Emitter fixture reparses and token spellings remain equal                            |
| P7.1.b | P7.1   | Replace punctuation-only spacing decisions with grammar-context rules for comma, colon, and postfix operators | DONE-LOCAL  | Expression/control-flow fixture plus C11 compile/run                                 |
| P7.1.c | P7.1   | Cover pointer, array, function-pointer, and nested declarator layout                                          | DONE-LOCAL  | Declarator corpus reparses and executes                                              |
| P7.1.d | P7.1   | Cover attributes and calling-convention modifiers at declaration, method, and callback positions              | DONE-LOCAL  | Attribute/ABI corpus maps output, reparses, and compiles on available host drivers   |
| P7.1.e | P7.1   | Cover comments adjacent to declarators and nested expressions without changing token boundaries               | DONE-LOCAL | Combined comments/packed-attribute/function-pointer fixture reparses and compiles |
| P7.1.f | P7.1   | Measure emitter determinism and bounded performance on the repository corpus                                  | DONE-LOCAL  | Repeated output/origin equality and benchmark evidence                               |

### P7.1 single-pass formatting matrix

This is the bounded P7.1 acceptance set. It is deliberately defined before the
next implementation pass. A row is a coverage contract, not a claim that the
current emitter had a defect in that area.

| Case | Required invariant | Status | Evidence |
|---|---|---|---|
| Terminal boundaries | Identifiers, keywords, literals, and comments remain distinct tokens | DONE-LOCAL | Normalized-token and combined trivia fixtures |
| Preprocessor regions | Preprocessor spelling and line structure remain verbatim | DONE-LOCAL | Preprocessor emitter fixture |
| Unary/binary/postfix operators | Prefix, binary, and postfix operators remain parseable and readable | DONE-LOCAL | Operator fixture with reparsing and C execution |
| Punctuation contexts | Commas, semicolons, brackets, member access, colons, and ternaries retain their grammar meaning | DONE-LOCAL | Expression/control-flow fixtures |
| Declarators | Pointer, array, function-pointer, pointer-to-array, and nested declarators retain shape | DONE-LOCAL | Declarator corpus and callback execution |
| Attributes and ABI modifiers | Declaration, method, callback, and aggregate attributes remain attached to the declaration | DONE-LOCAL | Attribute/ABI fixtures and packed-attribute fixture |
| Comments/trivia | Block and line comments adjacent to declarators and expressions survive without changing token boundaries | DONE-LOCAL | `astCEmitterPreservesCommentsAttributesAndNestedDeclaratorBoundaries` |
| Nested expressions | Casts, compound literals, designated initializers, `_Generic`, `sizeof`, `_Alignof`, and `offsetof` reparse | DONE-LOCAL | Expression-context fixture |
| Structured control flow | Labels, switch/case, loops, and `else` attachment remain valid | DONE-LOCAL | Control-flow fixture |
| Fail-closed boundary | Recovered, opaque, unlowered, and unknown C-plus nodes do not reach host C | DONE-LOCAL | Rejection diagnostics fixtures |
| Mapped determinism | Repeated emission has identical bytes and origin segments | DONE-LOCAL | Determinism fixture |
| Repository acceptance | The bounded repository corpus emits, reparses, and passes the aggregate gate | DONE-LOCAL | 104/104 grammar parses and aggregate Gradle test |

The comments row was initially a coverage hypothesis derived from the generic
trivia branch, not a reproduced failure. It is now closed as evidence coverage.
P7.1 is complete for this bounded matrix; cross-host compiler evidence and default
frontend promotion remain P7.6/P7.7 work rather than new P7.1 cases.

### P7.7 promotion evidence and compatibility boundary

The comment-survival check belongs to P7.1.e; it was planned emitter coverage,
not an untracked defect. It verifies that comments survive the AST round trip
without changing token boundaries. Promotion consumes that emitter contract as
an input and does not create a separate comment feature.

The first default-backend attempt exposed nine CLI regressions. They are recorded
under this one principal promotion task: six constructs were not yet lowered by
the AST path, two were diagnostic/pass-name compatibility assertions, and one AST
output reached the host compiler but did not compile. The compatibility boundary is:

1. `AUTO` (the default) attempts Tree-sitter first.
2. A lowering failure retries the legacy frontend.
3. A host compiler failure retries legacy once and preserves the final diagnostics.
4. Explicit `--frontend=tree-sitter` remains strict and fails closed.
5. Explicit `--frontend=legacy`, `CPLUS_FRONTEND=legacy`, and
   `-Dcplus.frontend=legacy` remain rollback paths.

The default is therefore usable while the differential corpus and release soak
measure whether the fallback can eventually be removed; legacy retirement is not
being claimed by this local gate.

| P7.3.a | P7.3   | Expose generated aliases, tags, functions, variables, and methods                                             | DONE-LOCAL  | Generic and method handle fixtures                                                   |
| P7.3.b | P7.3   | Expose synthesized aggregate member handles with mapped spans                                                 | DONE-LOCAL  | Generic struct member fixture                                                        |
| P7.3.c | P7.3   | Attach normalized member type, declarator, access, and ownership metadata                                     | DONE-LOCAL  | Generic member handle exposes semantic symbol, `int` type, and advisory `BORROWED` ownership |
| P7.3.d | P7.3   | Publish stable handle identity across comptime revisions and invalidation                                     | DONE-LOCAL  | Generic member ID remains stable after a source revision; removal remains covered by LSP invalidation tests |
| P7.7.a | P7.7   | Run Tree-sitter versus legacy output comparison on the bounded corpus                                         | DONE-LOCAL  | Frozen 16-case overlap corpus, per-pass reports, mapped diagnostics/source coverage, C11 compile/run checks; direct parser class green |
| P7.7.b | P7.7   | Define default-backend feature flag and rollback behavior                                                     | DONE-LOCAL  | AST-first `AUTO` mode, explicit `--frontend=tree-sitter`, explicit `--frontend=legacy`, and `CPLUS_FRONTEND`/`cplus.frontend` rollback tests |
| P7.7.c | P7.7   | Perform release-candidate soak before changing the default                                                    | BLOCKED     | Depends on P7.1–P7.6 and release host evidence                                       |
| P7.7.d | P7.7   | Preserve compatibility when the AST path cannot yet lower a construct                          | DONE-LOCAL  | AUTO mode falls back after a mapped lowering failure or compiler rejection; explicit Tree-sitter mode remains fail-closed |
| P7.7.e | P7.7   | Run the promoted default through CLI, stdlib, examples, and aggregate tests                       | DONE-LOCAL  | `defaultAstFirstFrontendTranscodesTheRepositoryCPlusCorpus` covers the current 69 `.cp`/`.c+` files; focused CLI test and aggregate gate pass; 104/104 grammar parses |

### Phase 8 active and deferred work

| ID      | Parent | Subtask                                                                                | Status     | Exit evidence                                        |
|---------|--------|----------------------------------------------------------------------------------------|------------|------------------------------------------------------|
| P8.5.a  | P8.5   | Start IntelliJ client, initialize, and synchronize a document                          | OPEN       | Automated IDE fixture reaches initialized state      |
| P8.5.b  | P8.5   | Surface diagnostics, completion, navigation, and test gutter actions                   | OPEN       | IntelliJ integration assertions and screenshots/logs |
| P8.5.c  | P8.5   | Recover from LSP exit and restart without stale diagnostics                            | OPEN       | Forced-exit/restart fixture                          |
| P8.8.a  | P8.8   | Match receiver/value/pointer method lookup and overload ranking                        | DONE-LOCAL | LSP semantic fixture suite                           |
| P8.8.b  | P8.8   | Match casts, unary/dereference forms, literal suffixes, and generated aliases          | DONE-LOCAL | Focused overload and comptime tests                  |
| P8.8.c  | P8.8   | Implement C integer promotions and legal pointer/member conversions                    | DONE-LOCAL | Integer and pointer conversion matrices pass; host C remains authoritative for ABI legality |
| P8.8.d  | P8.8   | Resolve writes, callback effects, ownership/access annotations, and scope invalidation | DONE-LOCAL | Highlight, callback, metadata, and generated-scope invalidation fixtures |
| P8.8.e  | P8.8   | Reject unsupported or ambiguous resolution conservatively with mapped diagnostics      | DONE-LOCAL | Ambiguity and unsupported-form diagnostics           |
| P8.9.a  | P8.9   | Verify VS Code child restart and stale-revision handling                               | DONE-LOCAL | Existing restart fixtures                            |
| P8.9.b  | P8.9   | Verify Vim restart, quickfix, and import recovery                                      | DONE-LOCAL | Existing headless fixtures                           |
| P8.9.c  | P8.9   | Verify IntelliJ unexpected exit, restart, and cache invalidation                       | OPEN       | IntelliJ interactive fixture                         |
| P8.10.a | P8.10  | Publish mismatch reporting and a legacy-backend rollback switch                        | OPEN       | Release configuration and operator documentation     |
| P8.10.b | P8.10  | Maintain two stable releases with both backends selectable                             | BLOCKED    | Starts only after P7.7 promotion                     |

`BLOCKED` here means dependency-gated, not an implementation failure. It prevents
release/retirement work from being counted as active engineering before its inputs
exist.

### P8.8 semantic-parity matrix

P8.8 is one principal task. The rows below are its bounded semantic contracts; fixes
discovered while closing a row remain ancillary work under that row.

| ID | Contract | Status | Exit evidence |
|---|---|---|---|
| P8.8.1 | Receiver/value/pointer lookup and method ownership | DONE-LOCAL | Receiver-chain and method fixtures |
| P8.8.2 | Overload arity, exact types, literals, casts, unary/dereference, suffixes, and ambiguity | DONE-LOCAL | Focused LSP resolution and diagnostic fixtures |
| P8.8.3 | C integer promotions and bounded scalar conversion ranking | DONE-LOCAL | `resolvesCIntegerPromotionsBeforeWiderOverloads`; focused LSP class and aggregate gate pass; host C remains authoritative |
| P8.8.4 | Array/function decay and legal pointer/member conversions | DONE-LOCAL | Array-to-pointer, function-pointer decay, `void*` compatibility, incompatible-pointer rejection, and receiver operator fixtures; aggregate gate passes |
| P8.8.5 | Read/write classification and callback mutation effects | DONE-LOCAL | Document-highlight and callback fixtures; richer data-flow remains |
| P8.8.6 | Generated comptime aliases, members, imports, and revision invalidation | DONE-LOCAL | Materialized-symbol and invalidation fixtures |
| P8.8.7 | Ownership/access annotations remain advisory and visible to tooling | DONE-LOCAL | `exposesAdvisoryAccessAndOwnershipMetadataThroughSymbolsAndHover`; document symbols and hover expose access plus sorted annotation metadata |
| P8.8.8 | Unsupported/ambiguous conversions fail conservatively with mapped diagnostics | DONE-LOCAL | Ambiguity and unsupported-form diagnostics |

## Phase 7 — AST emission and promotion

| ID   | Task                                                          | Depends on | Status            | Evidence / next action                                                                                                                                                                                                                |
|------|---------------------------------------------------------------|------------|-------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| P7.1 | Emit normalized AST terminals with deterministic C formatting | P2, P6     | DONE-LOCAL        | The bounded formatting matrix passes: token boundaries, preprocessing, operators, punctuation, declarators, attributes, comments, nested expressions, control flow, fail-closed rejection, mapping determinism, and repository acceptance. Cross-host evidence and promotion remain P7.6/P7.7 work. |
| P7.2 | Preserve mapped origins through AST emission and diagnostics  | P4, P6     | DONE-LOCAL        | `#line`, mapped diagnostics, token origins, and pre-hygiene source revisions pass local and supported-host parser CI gates.                                                                                                           |
| P7.3 | Publish semantic declaration handles                          | P4, P5     | DONE-LOCAL        | Generated aliases, tags, variables, functions, struct/union members, method declarations, semantic member type/declarator/access/ownership metadata, stable IDs, source origins, and `TranscodedSource` parity are covered locally. |
| P7.4 | Validate the hygiene boundary                                 | P7.1       | DONE              | Pre-hygiene C-plus is reparsed; post-hygiene `CPLUS_*` macros are validated as host C. The boundary is documented and tested.                                                                                                         |
| P7.5 | Prove deterministic output and performance                    | P7.1       | DONE-LOCAL        | Repeated output/origin equality, incremental Tree-sitter reuse, representative benchmarks, and tagged host benchmark jobs pass. A stable production budget remains a promotion input.                                                 |
| P7.6 | Run repository acceptance through the AST path                | P7.1–P7.5  | DONE-LOCAL        | 104/104 grammar fixtures, repository C-plus syntax corpus, stdlib fixtures, and finite non-Raylib examples pass locally. Raylib runtime/linking remains host-dependent.                                                               |
| P7.7 | Promote Tree-sitter as default frontend                       | P7.1–P7.6  | IN PROGRESS       | AST-first AUTO mode and compatibility fallback are locally green; differential comparison, cross-host evidence, and the release-candidate soak remain before declaring promotion complete. |
| P7.8 | Retire textual lowerers independently                         | P7.7       | RETAINED-FALLBACK | Method/receiver, defer, try/catch, test extraction, and allocation lowerers have bounded parity/rollback evidence. Deletion is deferred until promotion and the two-release soak finish.                                              |

## Phase 8 — LSP and editor adoption

| ID    | Task                                                            | Depends on      | Status      | Evidence / next action                                                                                                                                                                                                                                                                 |
|-------|-----------------------------------------------------------------|-----------------|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| P8.1  | Define and serve `cplus.parse.v1` normalized AST JSON           | P2              | DONE        | UTF-16 spans, categories, recovery flags, diagnostics, saved/stdin CLI modes, and malformed-input behavior are tested.                                                                                                                                                                 |
| P8.2  | VS Code parser, outline, tests, diagnostics, and restart client | P8.1            | DONE-LOCAL  | npm tests, parser-backed outline/Test Explorer, stale revision handling, diagnostics, child restart, and VSIX packaging pass.                                                                                                                                                          |
| P8.3  | Vim parser, outline, quickfix, imports, and restart client      | P8.1            | DONE-LOCAL  | Headless parser/outline/import/restart fixtures and archive packaging pass.                                                                                                                                                                                                            |
| P8.4  | IntelliJ parser cache, annotator, structure, navigation, tests  | P8.1            | DONE-LOCAL  | IntelliJ 2026.2.2 unit/plugin packaging tests and exact-source cache fixtures pass.                                                                                                                                                                                                    |
| P8.5  | Interactive IntelliJ LSP startup, features, and restart         | P8.1, P8.4      | OPEN        | Build a real IDE-client fixture for startup/sync, diagnostics, completion, navigation, test gutter, unexpected exit, and restart. This is the main editor evidence gap.                                                                                                                |
| P8.6  | Supported-host packaged LSP protocol smoke                      | P8.1, packaging | DONE        | CI run `36647197070` passed framed LSP startup/shutdown for the supported bare/cross distributions and host matrix.                                                                                                                                                                    |
| P8.7  | Bounded LSP scheduling, cancellation, and revision safety       | P8.1            | DONE-LOCAL  | Two workers, bounded queue, overload response, cancellation lifetime, malformed edit matrix, sustained benchmark, and protocol reliability gates pass; repeat host soak if the release matrix changes.                                                                                 |
| P8.8  | Compiler-aligned semantic resolution                            | P5, P8.1        | DONE-LOCAL | The bounded semantic-parity matrix is green: receiver/method lookup, overloads, diagnostics, scalar/pointer conversions, writes/callback effects, generated comptime scope, and advisory metadata. Richer data-flow and complete host-ABI legality remain outside this tooling contract. |
| P8.9  | Cross-client crash/restart behavior                             | P8.2–P8.5       | IN PROGRESS | VS Code and Vim restart fixtures pass, including bounded automatic recovery; IntelliJ unexpected-exit/restart evidence is still open.                                                                                                                                                  |
| P8.10 | Two-release legacy soak and migration rollback                  | P7.7, P8.2–P8.9 | OPEN        | After promotion, publish mismatch reporting and rollback instructions, keep legacy selectable for two stable releases, then audit before removal.                                                                                                                                      |

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
10. The AST emitter now has combined regression coverage for comments, packed attributes,
    function-pointer declarators, nested member expressions, reparsing, and C execution.
11. Synthesized aggregate members now expose parser-independent semantic handles with owner,
    normalized symbol/type/declarator metadata, advisory ownership, mapped generated text,
    and original source spans; the generic-struct fixture verifies `borrowed` propagation.
12. Synthesized member handles now expose revision-stable IDs derived from source identity,
    owner, semantic kind, name, and callable signature rather than generated offsets.
13. LSP overload ranking now models the bounded C integer-promotion path: `char`, `short`,
    and `_Bool` arguments prefer an exact `int` overload before `long` or `double`, while
    wider scalar conversions remain conservative tooling hints and host C remains authoritative.
14. Pointer matching now distinguishes pointer and array layers: one-dimensional arrays
    decay to compatible pointers, object pointers may match `void*`, incompatible pointer
    bases/depths fail closed, and existing function-pointer/member-receiver tests remain green.
15. LSP symbols now carry advisory access and ownership annotations from the semantic index;
    document-symbol JSON exposes `cplusAccess`/`cplusAnnotations`, while completion and hover
    include the same metadata in display detail without enforcing it.

The latest aggregate result is green with 104/104 grammar parses. The current worktree
also contains unrelated Raylib example changes; they are intentionally not part of this
frontend ledger or its commits.

## Immediate queue

1. **P8.8:** cover richer writes, callbacks, and comptime scope parity only where their
   compiler contracts are explicit; preserve conservative diagnostics otherwise.
2. **P8.5/P8.9:** add the real IntelliJ interactive startup/restart evidence.
3. **P7.7/P8.10:** do not promote or delete legacy code until the above evidence and the
   two-release rollback policy are recorded.
