# C-plus LSP Implementation Plan

This is the implementation roadmap for editor intelligence. It is a plan first
and a status ledger second. The language server is a CLI component, not a new
Gradle module: `cplus lsp` owns one parser runtime and exposes it to all editor
clients through standard input/output.

## Objective and boundaries

The LSP must provide diagnostics, navigation, completion, hover, symbols, and
test discovery for open C-plus documents while preserving `.cp` source
locations. It must consume the parser-neutral normalized AST and never inspect
Tree-sitter nodes from editor code. The existing scanner frontend remains a
selectable compiler fallback; LSP adoption does not retire it.

The first release targets a single process per editor workspace, in-memory
open-document snapshots, JSON-RPC 2.0 framing, and parser-backed features. It
does not initially provide type checking, build orchestration, refactoring, or
cross-process project indexing.

## Architecture and component lifetime

```text
editor client
  -> JSON-RPC / stdio transport
  -> document store and revision scheduler
  -> parser backend selector
  -> normalized C-plus AST + source spans
  -> symbol/index services
  -> LSP responses and mapped diagnostics
```

| Component        | Initial implementation                     | Replacement/lifetime rule                                                    |
|------------------|--------------------------------------------|------------------------------------------------------------------------------|
| Transport        | CLI stdio JSON-RPC server                  | Permanent protocol boundary                                                  |
| Document store   | URI/revision/text snapshots                | Permanent; storage may become incremental                                    |
| Parser adapter   | Tree-sitter C-plus -> normalized AST       | Replaceable behind the parser backend contract                               |
| Source positions | Shared UTF-8/UTF-16/source-span conversion | Permanent shared infrastructure                                              |
| Symbol index     | Syntax-oriented normalized-AST index       | Temporary; replace with semantic index after type/scope resolution is stable |
| Editor adapters  | Thin VS Code, Vim, and IntelliJ clients    | Independent; must not duplicate parser or lowering logic                     |
| Packaging        | Launch configured `cplus lsp`              | Permanent discovery contract; platform scripts may evolve                    |

## Ordered roadmap

Each phase has a gate. A phase is complete only when its gate and listed
regression evidence pass locally; implementation code alone is not completion
evidence.

### LSP-0 — Freeze the protocol contract

Subphases:

1. Define supported LSP version, JSON-RPC framing, request/notification rules,
   shutdown behavior, error codes, and zero-based position conventions.
2. Define document identity, revision handling, parser selection, diagnostic
   severity, and source-map behavior.
3. Create a protocol fixture format that can replay requests and assert ordered
   responses/notifications.

Gate: the contract and one replayable fixture exist before client work begins.

### LSP-1 — Transport and process lifecycle

Subphases:

1. Implement `Content-Length` frame reading and writing.
2. Implement `initialize`, `initialized`, `shutdown`, and `exit`.
3. Return standard `-32600`, `-32601`, and `-32602` errors without terminating
   the server; malformed JSON returns `-32700` with a null ID, and valid
   non-object messages return `-32600`.
4. Keep stdout protocol-clean; diagnostics and operational logs go to stderr.

Gate: fragmented frames, multiple messages, malformed requests, shutdown, and
EOF are covered by JVM tests.

### LSP-2 — Documents and diagnostics

Subphases:

1. Implement `didOpen`, `didChange`, and `didClose` with monotonically tracked
   revisions.
2. Parse each current snapshot through the selected backend.
3. Publish parser and source-map diagnostics with exact URI/range/code data.
4. Clear diagnostics after close and reject stale changes deterministically.
5. Emit a warning for recovered or unsupported AST fragments that have no
   normalized compiler mapping/resolution, while preserving compiler/parser
   errors as errors with their mapped source ranges. Diagnostics must not stop
   code generation unless an implicitly fatal condition occurs, such as a
   transcoder crash or an unusable source stream/pass.

Gate: valid, malformed, edited, closed, and Unicode-containing documents have
stable diagnostics and positions; unsupported AST fragments are visible as
warnings and compiler/parser failures remain errors.

### LSP-3 — AST bridge and structural index

Subphases:

1. Adapt normalized AST declarations into symbols without parser-specific IDs.
2. Index structs, unions, enums, typedefs, fields, variables, functions,
   methods, static methods, tests, and comptime declarations.
3. Preserve declaration and selection ranges plus containing-symbol hierarchy.
4. Rebuild only the changed document; keep the service correct before making it
   incremental.

Gate: `textDocument/documentSymbol` and a direct index test agree on names,
ranges, hierarchy, and source revisions.

### LSP-4 — Core language features

Subphases:

1. Add prefix/trigger completion for C-plus keywords, macros, symbols, fields,
   and methods.
2. Add hover with declaration kind, spelling, and source location.
3. Add definition lookup using lexical scope and receiver-aware symbol lookup.
4. Add `workspace/symbol` only after the workspace index exists.
5. Add test-fixture discovery metadata for editor test gutters.

Gate: one protocol fixture covers completion, hover, definition, symbols, method
receivers, keywords, and unresolved identifiers.

### LSP-5 — Workspace and semantic services

Subphases:

1. Discover project root and project configuration.
2. Resolve relative and `stdlib:` imports into canonical document identities.
3. Maintain a bounded workspace/module graph and invalidate dependents on change.
4. Replace syntax-only lookup with the compiler's semantic scopes, typedefs,
   receiver inference, comptime-generated declarations, and overload rules.
5. Add cancellation and request scheduling so stale work cannot overwrite newer
   revisions.

Gate: multi-file fixtures prove import navigation, generated symbols, cycles,
shadowing, stale-request suppression, and mapped diagnostics.

### LSP-6 — Editor integrations

Subphases:

1. VS Code: launch/configure `cplus lsp`, use server diagnostics/navigation, and
   retain syntax highlighting as an offline fallback.
2. Vim: add an opt-in LSP launcher while retaining CLI commands for environments
   without an LSP client.
3. IntelliJ: implement an external-process LSP adapter first; evaluate in-process
   embedding only if process integration cannot meet performance requirements.
4. Keep each adapter thin: no copied parser, comptime evaluator, or C emitter.

Gate: each client opens, edits, diagnoses, navigates, completes, and discovers
tests in a fixture project without editor-specific source interpretation.

### LSP-7 — Reliability and performance

Subphases:

1. Add cancellation, bounded queues, crash recovery, and restart behavior. The
   VS Code adapter now exposes an explicit restart command and performs one
   bounded automatic restart after an unexpected server exit.
2. Add incremental parsing only after full-snapshot correctness is established.
   The CLI LSP now reuses one Tree-sitter parse session per document and falls
   back to a fresh session if an identity mismatch or session failure occurs.
3. Benchmark cold start, parse, index, completion, and change latency on the
   standard-library corpus.
4. Add fuzz/property tests for framing, positions, recovery nodes, and edits.

Gate: no protocol corruption under malformed input; latency and memory budgets
are recorded and regressions are reproducible.

#### Request scheduling and overload

The CLI server uses two request workers and a bounded queue of 64 pending
document-scoped requests. The stdio reader remains available for document
changes and `$/cancelRequest`; accepted work is drained during orderly
shutdown. If the queue is full, the server returns a JSON-RPC error with code
`-32001` and message `language server request queue is full`. Clients should
discard or retry that request after applying their usual debounce policy; this
error is not a source diagnostic. Cancellation is checked before dispatch and
after the work completes, so an obsolete request cannot publish a result.

### LSP-8 — Packaging and release

Subphases:

1. Define discovery order: configured executable, project launcher, installed
   `cpc`, then documented failure instructions.
2. Test `cplus lsp` from source, bare distribution, and bundled-runtime
   distribution.
3. Document editor configuration and version compatibility.
4. Publish artifact smoke tests before enabling release automation.

Gate: release artifacts start the server on every supported host and keep
stdout protocol-clean.

## Detailed status ledger for LSP-5, LSP-7, and LSP-8

The parent phases remain `IN PROGRESS` until every required subtask is `DONE`.
`DONE` means the bounded local acceptance evidence exists; it does not imply
that a broader semantic or hosted-platform gate has been silently waived.

## Remaining-work dashboard

This dashboard is the actionable breakdown of the three parent phases. A parent
status is only a summary; work is considered converged when every row below is
`DONE` or is explicitly deferred with an accepted scope change.

| Parent | Subtask                                 | Status      | Concrete deliverable                                                                                                                                                                                                                                    | Blocks          |
|--------|-----------------------------------------|-------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------|
| LSP-5  | 5.6a semantic type model                | DONE        | Compiler semantics expose canonical primitive, typedef, pointer, array, callable, parameter, and local-value shapes with declaration spans and preserved declaration qualifiers                                                                         | 5.6b–e          |
| LSP-5  | 5.6a.1 qualified pointer shape          | DONE        | Normalize `const`/`volatile`/`restrict` around pointer declarations and preserve pointer depth for receiver matching                                                                                                                                    | 5.6a            |
| LSP-5  | 5.6a.2 array decay shape                | DONE        | Model array arguments as one pointer layer for bounded overload and receiver selection                                                                                                                                                                  | 5.6a            |
| LSP-5  | 5.6a.3 lexical-vs-call selection        | DONE        | Preserve overload ranking at call sites while selecting the narrowest lexical declaration for ordinary references                                                                                                                                       | 5.6a            |
| LSP-5  | 5.6b member typing                      | IN PROGRESS | Infer `.`/`->` receiver types, fields, methods, `self`, chained fields, and bounded method-return receivers, including overload-aware return selection                                                                                                  | 5.6c–e          |
| LSP-5  | 5.6b.1 receiver operator compatibility  | DONE        | Reject value/`->` and pointer/`.` member mismatches while retaining valid value, pointer, `self`, and static receivers                                                                                                                                  | 5.6b–e          |
| LSP-5  | 5.6b.2 method-return receiver chains    | DONE        | Resolve simple function/method return shapes so chains such as `factory.get()->value` expose the returned type's members                                                                                                                                | 5.6b–e          |
| LSP-5  | 5.6b.3 parenthesized receivers          | DONE        | Resolve explicit address/dereference receivers such as `(&value).field` and `(*pointer).field` while retaining ordinary operator checks                                                                                                                 | 5.6c–e          |
| LSP-5  | 5.6c callable ranking                   | IN PROGRESS | Semantic call resolution ranks same-name functions and methods by explicit arity, exact canonical shape, numeric conversion, and string/character literal shape; implicit `self` is excluded only for instance methods and broader conversions remain   | 5.6e            |
| LSP-5  | 5.6c.1 bounded argument ranking         | DONE        | Rank same-arity overloads for integer, floating, character/string, identifier, and pointer-shaped arguments with conservative fallback                                                                                                                  | 5.6c            |
| LSP-5  | 5.6c.2 function-pointer compatibility   | DONE        | Match direct function values to compatible function-pointer overloads using return type and callback arity                                                                                                                                              | 5.6c            |
| LSP-5  | 5.6c.3 instance-method argument ranking | DONE        | Exclude the implicit `self` parameter when ranking instance-method arguments while preserving every explicit static-method parameter                                                                                                                    | 5.6c            |
| LSP-5  | 5.6c.4 scalar conversion ranking        | DONE        | Prefer the nearest supported scalar conversion when no exact numeric overload exists; retain compiler authority for legality                                                                                                                            | 5.6c            |
| LSP-5  | 5.6c.5 ambiguity safety                 | DONE        | Refuse definition/receiver selection when the best bounded callable score is shared by distinct declarations                                                                                                                                            | 5.6c            |
| LSP-5  | 5.6c.6 ambiguity diagnostics            | DONE        | Publish a mapped severity-1 `CPLUS_AMBIGUOUS_CALL` error only when distinct free-call candidates share the best bounded score; unresolved and uniquely ranked calls remain delegated                                                                    | 5.6c            |
| LSP-5  | 5.6c.7 receiver ambiguity diagnostics   | DONE        | Apply the same proven equal-score diagnostic to resolvable `.`/`->` method calls, using the current unsaved snapshot and receiver owner; unresolved receiver shapes remain fail-closed                                                                  | 5.6c            |
| LSP-5  | 5.6c.8 return-receiver ambiguity safety | DONE        | Refuse chained member typing when equally ranked overloads return different receiver shapes instead of selecting by declaration order                                                                                                                   | 5.6c            |
| LSP-5  | 5.6c.9 dereferenced-argument typing     | DONE        | Infer a known scalar shape through unary `*` and parenthesized expressions before bounded overload ranking                                                                                                                                              | 5.6c            |
| LSP-5  | 5.6c.10 unary numeric argument typing   | DONE        | Preserve known numeric shapes through unary `+`, `-`, `~`, and `!` before bounded overload ranking                                                                                                                                                      | 5.6c            |
| LSP-5  | 5.6c.11 explicit cast argument typing   | DONE        | Read simple C cast target shapes before bounded overload ranking, preserving pointer and scalar overload selection                                                                                                                                      | 5.6c            |
| LSP-5  | 5.6d write/reference effects            | DONE        | Distinguish reads, writes, declarations, callback/function-pointer references, and indirect pointee writes; function names used as callback values and pointer operands in `*pointer = value` are read references                                                                 | 5.6e            |
| LSP-5  | 5.6d.1 document highlight effects       | DONE        | Expose AST-resolved `textDocument/documentHighlight` results with LSP read/write kinds for declarations, assignments, increments, and uses                                                                                                              | 5.6d–e          |
| LSP-5  | 5.6d.2 indirect pointer effects         | DONE        | Keep the pointer operand of `*pointer = value` as a read while retaining writes for declarations and direct pointer assignments; alias-aware pointee data-flow remains intentionally bounded                                                                                         | 5.6e            |
| LSP-5  | 5.6e comptime parity                    | IN PROGRESS | Expose generated declarations, scopes, aliases, and source origins consistently in all semantic features                                                                                                                                                | LSP-5 gate      |
| LSP-5  | 5.6e.1 materialized receiver aliases    | DONE        | Generated comptime aliases participate in receiver completion while preserving the source document identity and mapped origin                                                                                                                           | 5.6e            |
| LSP-5  | 5.6e.2 generated-scope invalidation     | DONE        | Removing a comptime generator through `textDocument/didChange` removes its generated aliases and members from subsequent completion results                                                                                                             | 5.6e            |
| LSP-2  | 2.5 unsupported-AST diagnostics         | DONE        | Recovered/unsupported AST fragments are mapped warnings, compiler/parser failures remain errors, recoverable diagnostics do not stop emission, and all three adapters preserve both severities; implicitly fatal transcoder crashes may stop generation | 2.1–2.4         |
| LSP-2  | 2.5a AST warning classification         | DONE        | `CPLUS_UNSUPPORTED_AST` warnings are emitted for top-level unmapped named/opaque AST fragments, with source spans and duplicate suppression                                                                                                             | 2.5b–c          |
| LSP-2  | 2.5b severity and emission contract     | DONE        | Warning severity remains advisory and parser recovery diagnostics remain errors; LSP session and document queries continue after warning publication                                                                                                    | 2.5c            |
| LSP-2  | 2.5c editor severity fixtures           | DONE        | VS Code's framed client fixture, Vim quickfix fixture, and IntelliJ decoder fixture preserve warning severity 2 and parser/compiler error severity 1                                                                                                    | LSP-2 gate      |
| LSP-2  | 2.5c.1 adapter severity mapping         | DONE        | VS Code's pure LSP severity mapper, Vim quickfix conversion, and IntelliJ warning decoding preserve warning-vs-error classification                                                                                                                     | 2.5c            |
| LSP-7  | 7.6a sustained throughput               | DONE        | 60-second gate completed 2,202 rounds and 46,242 document requests on Linux/JDK 21                                                                                                                                                                      | 7.7 host matrix |
| LSP-7  | 7.6b fairness/starvation                | DONE        | Every sustained round completed its post-cancellation tail request without starvation                                                                                                                                                                   | 7.7 host matrix |
| LSP-7  | 7.6c memory budget                      | DONE        | 60-second run stayed within the 64 MiB post-GC growth ceiling, reporting a −567,968 byte delta                                                                                                                                                          | 7.7 host matrix |
| LSP-7  | 7.6d threshold policy                   | DONE        | Freeze machine-independent pass/fail thresholds and benchmark report format                                                                                                                                                                             | 7.7             |
| LSP-7  | 7.7a Linux host matrix                  | DONE        | Run protocol, edit, crash, and benchmark fixtures on supported Linux hosts                                                                                                                                                                              | 7.7b            |
| LSP-7  | 7.7b macOS host matrix                  | DONE        | Run the same fixtures on supported macOS hosts                                                                                                                                                                                                          | 7.7c            |
| LSP-7  | 7.7c Windows host matrix                | DONE        | Run the same fixtures on supported Windows hosts                                                                                                                                                                                                        | LSP-7 gate      |
| LSP-7  | 7.9a Vim restart                        | DONE        | Headless unexpected-exit/restart fixture passes                                                                                                                                                                                                         | 7.9 gate        |
| LSP-7  | 7.9b VS Code restart                    | DONE        | Real child-process fixture proves bounded auto-restart and explicit restart reset, including closed-pipe recovery                                                                                                                                       | 7.9 gate        |
| LSP-7  | 7.9c IntelliJ restart                   | OPEN        | Exercise unexpected server exit, restart action, document recovery, and diagnostics recovery                                                                                                                                                            | 7.9 gate        |
| LSP-8  | 8.1a discovery precedence               | DONE        | Configured command, project `.cplus`, project `c-plus-bin`, project root, `CPLUS_HOME`, user-local launchers, then PATH `cpc` order is implemented consistently in all clients                                                                          | 8.1b            |
| LSP-8  | 8.1b packaged discovery                 | DONE        | Shared fixture passes from extracted bare and six-host cross-runtime distributions through the project-local launcher                                                                                                                                   | 8.1 gate        |
| LSP-8  | 8.5a IntelliJ startup/sync              | DONE-LOCAL  | Isolated IntelliJ IDEA 2026.2.2 loads the packaged plugin, opens `doom.cp`, discovers the project-local launcher, and reaches LSP initialization                                                                                                        | 8.5b–c          |
| LSP-8  | 8.5b IntelliJ features                  | OPEN        | Verify diagnostics, navigation, completion, and test discovery through the real client                                                                                                                                                                  | 8.5c            |
| LSP-8  | 8.5c IntelliJ restart                   | OPEN        | Verify restart and state recovery through the real client                                                                                                                                                                                               | LSP-8.5 gate    |
| LSP-8  | 8.6a bare release hosts                 | DONE        | `release-host-matrix` validates the bare ZIP on Linux x86_64/arm64, macOS arm64, and Windows x86_64; CI run `36647197070` is green                                                                                                                      | 8.6c            |
| LSP-8  | 8.6b bundled release hosts              | DONE        | `release-host-matrix` validates the cross-runtime ZIP on Linux x86_64/arm64, macOS arm64, and Windows x86_64; CI run `36647197070` is green                                                                                                             | 8.6c            |
| LSP-8  | 8.6c protocol-clean matrix              | DONE        | The matrix records clean initialize/shutdown, exit status, launcher metadata, and per-host reports; CI run `36647197070` passes all hosts                                                                                                               | 8.7             |
| LSP-8  | 8.7a artifact manifest                  | DONE        | Generate a deterministic tagged-release manifest containing SHA-256, byte size, and filename for every downloaded application/editor asset                                                                                                              | 8.7b            |
| LSP-8  | 8.7b publication rehearsal              | DONE        | Publish a tagged release candidate and verify downloadable assets and metadata                                                                                                                                                                          | LSP-8 gate      |

### Current bottleneck and next execution order

The next local work is not “finish LSP-5/7/8” as one task. It is:

1. Add real IntelliJ unexpected-exit coverage for 7.9c.
2. Extend the IntelliJ interactive fixture with protocol-visible diagnostics/features (8.5b) and restart recovery (8.5c).
3. Run host/release validation (7.7 and 8.6) only after the local gates are green.
4. Finish the remaining semantic services in LSP-5.6 and close the parent gates.

The semantic work in 5.6 is independent of packaging work after its existing
5.5 scheduling contract; it should not be used as a reason to leave the
reliability and release subtasks represented by a single indefinite status.

### LSP-2 — diagnostics completeness

| ID      | Status | Subtask                                    | Dependencies | Evidence / remaining action                                                                                                                                |
|---------|--------|--------------------------------------------|--------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| LSP-2.5 | DONE   | Report unsupported/recovered AST fragments | LSP-2.1–2.4  | Mapped warning generation, parser-error preservation, and VS Code/Vim/IntelliJ severity fixtures pass; implicitly fatal generation failures remain errors. |

### LSP-5 — workspace and semantic services

| ID       | Status | Subtask                                                                | Dependencies | Evidence / remaining action                                                                                                                                                                                                                        |
|----------|--------|------------------------------------------------------------------------|--------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| LSP-5.1  | DONE   | Discover project root, `cplus.toml`, and module paths                  | LSP-4        | Manifest fixture resolves configured module roots and canonical `module:/` identities.                                                                                                                                                             |
| LSP-5.2  | DONE   | Index bounded imported documents                                       | LSP-5.1      | Imported symbols appear in workspace symbols, hover, definition, and completion fixtures.                                                                                                                                                          |
| LSP-5.3  | DONE   | Invalidate import dependents and prune unreachable snapshots           | LSP-5.2      | Open/change/close import-graph tests verify dependent refresh and pruning without restart.                                                                                                                                                         |
| LSP-5.4a | DONE   | Resolve lexical scopes and shadowing                                   | LSP-5.3      | Nearest-local declaration fixtures pass, including nested blocks and generated symbols.                                                                                                                                                            |
| LSP-5.4b | DONE   | Resolve typedefs, `self`, value/pointer receivers, fields, and methods | LSP-5.4a     | Receiver completion/reference fixtures cover `.`, `->`, instance methods, static methods, and fields.                                                                                                                                              |
| LSP-5.4c | DONE   | Index materialized comptime declarations with original ranges          | LSP-5.4b     | Materialized declaration fixture maps generated symbols back to the `.cp` invocation.                                                                                                                                                              |
| LSP-5.4d | DONE   | Resolve bounded reference identity and call arity                      | LSP-5.4c     | Function, callback-value, variable, field, instance/static method, and arity fixtures pass.                                                                                                                                                        |
| LSP-5.5a | DONE   | Add revision guards and cancellation                                   | LSP-5.4d     | Queued and in-flight cancellation tests suppress obsolete responses.                                                                                                                                                                               |
| LSP-5.5b | DONE   | Add bounded asynchronous scheduling and orderly drain                  | LSP-5.5a     | Two workers, queue capacity 64, exact-future cleanup, overload `-32001`, and shutdown drain are tested.                                                                                                                                            |
| LSP-5.6  | OPEN   | Replace bounded syntax lookup with complete compiler semantic services | LSP-5.5b     | Canonical scoped types, recursive chained field typing, overload-aware method/function return inference, and conservative callable ranking are implemented; broader pointer/member conversions, writes, and complete comptime scope parity remain. |

### LSP-7 — reliability and performance

| ID      | Status      | Subtask                                                  | Dependencies     | Evidence / remaining action                                                                                                                                                                                                                                                                  |
|---------|-------------|----------------------------------------------------------|------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| LSP-7.1 | DONE        | Protect JSON-RPC framing and malformed-message recovery  | LSP-5.5b         | Fragmentation and malformed/non-object recovery matrices pass; stdout remains protocol-clean.                                                                                                                                                                                                |
| LSP-7.2 | DONE        | Protect cancellation, revision, and request-ID lifetimes | LSP-7.1          | Pre-dispatch, in-flight, repeated-ID, and exact-future cleanup regressions pass.                                                                                                                                                                                                             |
| LSP-7.3 | DONE        | Bound queue growth and report overload                   | LSP-7.2          | 256-request overload benchmark reports accepted/rejected accounting and orderly shutdown.                                                                                                                                                                                                    |
| LSP-7.4 | DONE        | Reuse incremental parse sessions safely                  | LSP-7.2          | Unicode/malformed edit fixtures compare incremental results with fresh parses and recover on session failure.                                                                                                                                                                                |
| LSP-7.5 | DONE        | Record cold, burst, and overload latency baselines       | LSP-7.3, LSP-7.4 | `:cli:benchmarkLsp` records current Linux x86_64/JDK 21 measurements.                                                                                                                                                                                                                        |
| LSP-7.6 | DONE        | Establish sustained-load throughput and memory budgets   | LSP-7.5          | Linux/JDK 21 `benchmarkLspGate` passed for 60 seconds with 2,202 rounds, 36.700 rounds/sec, 46,242 completed requests, successful tail requests, and −567,968 bytes post-GC heap delta. Cross-host validation remains LSP-7.7.                                                               |
| LSP-7.7 | DONE        | Validate reliability/performance on supported hosts      | LSP-7.6          | CI run `36647197070` passed `CPlusLspServerTest`, the enforced benchmark, protocol, and reliability gates on Linux x86_64/arm64, macOS arm64, and Windows x86_64.                                                                                                                            |
| LSP-7.8 | DONE        | Expand property/fuzz coverage for positions and edits    | LSP-7.4          | `survivesDeterministicUnicodeAndMalformedEditMatrix` runs 32 deterministic edit seeds with valid snapshots, malformed recovery snapshots, supplementary Unicode, repeated full-document revisions, final symbol recovery, and diagnostics assertions.                                        |
| LSP-7.9 | IN PROGRESS | Prove crash/restart recovery through each client         | LSP-7.1          | Vim and VS Code child-process restart fixtures pass; VS Code also handles an initialize/pipe-teardown `EPIPE` and permits only one automatic restart per failure window. IntelliJ 2026.2.2 now has real plugin-load/LSP-start/clean-stop evidence; unexpected-exit/restart coverage remains. |

#### LSP-7.9 acceptance subtasks

| ID        | Status     | Subtask                                        | Acceptance evidence / next action                                                                                         |
|-----------|------------|------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| LSP-7.9.a | DONE-LOCAL | Recover VS Code child-process exit             | Real child-process fixture covers `EPIPE`, one automatic restart, and explicit restart.                                   |
| LSP-7.9.b | DONE-LOCAL | Recover Vim child-process exit                 | Headless Vim restart fixture passes.                                                                                      |
| LSP-7.9.c | DONE-LOCAL | Start and stop IntelliJ LSP cleanly            | IntelliJ 2026.2.2 loads C-plus, starts `cpc lsp`, initializes, and shuts down cleanly in the isolated smoke run.          |
| LSP-7.9.d | OPEN       | Recover an unexpectedly killed IntelliJ server | Add a deterministic child-server kill and assert the client state/restart behavior; manual restart alone is insufficient. |

### LSP-8 — packaging and release

| ID      | Status      | Subtask                                                     | Dependencies     | Evidence / remaining action                                                                                                                                                                                                                                                                                                              |
|---------|-------------|-------------------------------------------------------------|------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| LSP-8.1 | DONE        | Implement and verify discovery order                        | LSP-6            | VS Code, Vim, and IntelliJ use the configured command, project `.cplus/cpc(.sh                                                                                                                                                                                                                                                           |.cmd)`, project `c-plus-bin/cpc`, project root, `CPLUS_HOME`, user-local installation paths, then installed `cpc`; source and packaged fixtures pass clean initialize/shutdown exchange. VS Code discovery tests and Vim headless discovery/outline tests also pass. Release-host matrix validation remains LSP-8.6. |
| LSP-8.2 | DONE        | Launch from source, bare, and bundled-runtime distributions | LSP-7            | Local source, bare `target=none`, and cross-runtime launchers return clean initialize/shutdown frames.                                                                                                                                                                                                                                   |
| LSP-8.3 | DONE        | Document editor configuration and version compatibility     | LSP-6            | VS Code, Vim, and IntelliJ configuration/version guidance is documented and packaged.                                                                                                                                                                                                                                                    |
| LSP-8.4 | DONE        | Build editor artifacts and run consumer smoke tests         | LSP-6            | `./gradlew --offline --no-daemon editorArtifacts --max-workers=1` passes at version 0.5.59: VS Code VSIX packaging, IntelliJ 2026.2.2 plugin tests/package, and Vim archive packaging.                                                                                                                                               |
| LSP-8.5 | IN PROGRESS | Run an interactive IntelliJ protocol fixture                | LSP-8.2, LSP-8.4 | A headless IntelliJ IDEA 2026.2.2 run loaded `C-plus (0.5.58)`, opened `doom.cp`, discovered the project-local `c-plus-bin/cpc.sh` despite missing Toolbox PATH inheritance, started `cpc lsp`, and initialized successfully. Add assertions for document diagnostics, navigation/completion, test gutter, unexpected exit, and restart. |
| LSP-8.6 | DONE        | Validate release discovery on supported hosts               | LSP-8.1, LSP-8.2 | CI run `36647197070` launched both bare and cross-runtime artifacts with protocol-clean stdout on Linux x86_64/arm64, macOS arm64, and Windows x86_64.                                                                                                                                                                                   |
| LSP-8.7 | DONE        | Verify release publication and artifact completeness        | LSP-8.6          | Tagged run `36622759483` published `0.5.57` with the generated manifest; run `36647197070` completed bare/cross host validation after the launcher correction.                                                                                                                                                                           |

#### LSP-8.5 acceptance subtasks

| ID        | Status      | Subtask                                  | Acceptance evidence / next action                                                                                                                                      |
|-----------|-------------|------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| LSP-8.5.a | DONE-LOCAL  | Load the packaged plugin in IntelliJ     | `scripts/intellij-lsp-smoke.sh` loads the 0.5.59 plugin in IntelliJ IDEA 2026.2.2 under an isolated profile.                                                           |
| LSP-8.5.b | IN PROGRESS | Activate LSP for a real project document | The smoke harness opens `doom.cp`, discovers the project-local launcher, and observes `cpc lsp` initialization; the provider now exposes a C-plus-specific Language Services widget/settings target; add protocol-visible diagnostics/document-sync checks. |
| LSP-8.5.c | IN PROGRESS | Verify editor feature presentation       | Exercise diagnostics, completion, navigation, structure, and test gutter against a real IDE document.                                                                  |
| LSP-8.5.d | OPEN        | Verify unexpected exit and restart       | Kill the child server, assert recovery, and exercise the restart action with log/protocol evidence.                                                                    |

#### IntelliJ smoke harness note

The CLI LSP server has an opt-in `CPLUS_LSP_TRACE` file trace for host-integration
diagnostics. It records method names only, keeps protocol stdout clean, and is used
by `scripts/intellij-lsp-smoke.sh` to require `initialize`, `textDocument/didOpen`,
and `textDocument/publishDiagnostics`. The harness selects its isolated profile via
the launcher's supported `IDEA_PROPERTIES` environment variable and supplies a local
bundled-plugin inventory so the user's plugin migration settings are not imported.
The focused server test and IntelliJ plugin test/package gate pass. A fresh IntelliJ
profile still requires host-level Unix-domain socket support; until that replay is
run outside the restricted sandbox, LSP-8.5.b/c and LSP-7.9.d remain open.

The IntelliJ command settings migration is whitespace-tolerant for legacy built-ins,
including `cplus test`, `cplus parse`, and `cplus lsp`; current built-in defaults use
the `cpc` launcher. Gutter run/test actions now create separate disposable closeable
content tabs, while the permanent Output tab remains available for the welcome text.

## Dependency graph and execution order

```text
LSP-0 -> LSP-1 -> LSP-2 -> LSP-3 -> LSP-4 -> LSP-5 -> LSP-6 -> LSP-8
                                      \\-> LSP-7 (after the first stable feature slice)
```

LSP-0 through LSP-4 are the local foundation. LSP-5 is the current
compiler-facing work package; its first two subphases are the in-memory
open-document workspace index and relative import indexing, followed by project
configuration and semantic lookup. LSP-6
may begin with the stable local protocol fixture, but client packaging waits
for LSP-5's launch/configuration contract. LSP-7 and LSP-8 do not justify a CI
loop until their local acceptance tests pass.

## Current execution plan

The local implementation sequence through client construction and launch
recovery is complete. The remaining work is evidence-oriented: close the
interactive IntelliJ fixture, record the reliability/performance measurements,
then validate release-host discovery. Do not treat a locally packaged artifact
as proof of supported-host release behavior.

| Order | Work item                                               | Depends on | Acceptance gate                                                                                                                                                               |
|-------|---------------------------------------------------------|------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1     | LSP-5.1: define project-root and module-path discovery  | LSP-4      | A fixture resolves relative and manifest-declared imports to canonical URIs                                                                                                   |
| 2     | LSP-5.2: index bounded imported documents               | LSP-5.1    | `workspace/symbol`, hover, and definition resolve symbols from an imported file                                                                                               |
| 3     | LSP-5.3: invalidate changed documents and dependents    | LSP-5.2    | A changed import graph removes unreachable snapshots and republishes dependent diagnostics without restarting the server                                                      |
| 4     | LSP-5.4a: add lexical scope resolution                  | LSP-5.3    | A shadowed local reference resolves to the nearest declaration in its containing block                                                                                        |
| 5     | LSP-5.4b: add typedef, receiver, and method resolution  | LSP-5.4a   | Shadowed aliases and `object.method`, `object->method`, and `self` calls resolve consistently                                                                                 |
| 6     | LSP-5.4c: index materialized comptime declarations      | LSP-5.4b   | Generated workspace symbols and definitions retain original `.cp` invocation ranges                                                                                           |
| 7     | LSP-5.4d: resolve bounded reference identity            | LSP-5.4c   | Function call arity, callback-value contexts, nearest variable scopes, and value/type receiver owners select function, method, and field references without same-name leakage |
| 8     | LSP-5.5a: add cancellation and revision guards          | LSP-5.4d   | `$/cancelRequest` suppresses canceled document responses and revision checks prevent stale publication                                                                        |
| 9     | LSP-5.5b: add cooperative asynchronous scheduling       | LSP-5.5a   | Expensive parse/index work yields or cancels without blocking the stdio reader                                                                                                |
| 9     | LSP-6.1–6.4: complete client parity and launch recovery | LSP-5.5b   | VS Code, Vim, and IntelliJ pass the same protocol fixture for diagnostics, navigation, completion, and tests                                                                  |
| 10    | LSP-7: reliability and performance evidence             | LSP-6      | Framing fuzz cases, restart behavior, and corpus latency measurements are recorded                                                                                            |
| 11    | LSP-8: distribution discovery and release packaging     | LSP-7      | Bare and bundled distributions launch `cplus lsp` with clean stdout                                                                                                           |

The current code has completed items 1–9 locally for adapter construction,
VS Code/Vim restart behavior, and packaging. Item 9 still needs an interactive
IntelliJ protocol fixture and IntelliJ restart coverage before it can close
completely. Items 10–11 remain active evidence work: broader
framing/position/recovery fuzzing, corpus latency measurements, and
supported-host release validation.
The adapters are not a substitute for reliability or release evidence.

## Current status ledger

| Phase | State       | Evidence or next action                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
|-------|-------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| LSP-0 | DONE        | Protocol behavior is documented and replayed by the CLI JVM fixture                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| LSP-1 | DONE        | Stdio server, framing, lifecycle, and CLI entry point are implemented locally                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| LSP-2 | DONE        | Open/change/close snapshots publish parser diagnostics locally                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| LSP-3 | DONE        | Normalized-AST structural symbols are indexed locally                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| LSP-4 | DONE-LOCAL  | Symbols, completion, hover, and definition pass the local protocol fixture                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| LSP-5 | IN PROGRESS | Open-document `workspace/symbol`, bounded imports, import-closure visibility for completion/definition/references, receiver-aware member completion, bounded call-arity overload selection, dependency invalidation, lexical/receiver lookup, evaluator-backed comptime symbols, cancellation/revision guards, and bounded cooperative scheduling are implemented; broader semantic resolution remains                                                                                                                                                                                                                                                                                                    |
| LSP-6 | DONE-LOCAL  | VS Code, Vim, and IntelliJ have opt-in adapters that launch the shared `cplus lsp` process; IntelliJ command construction covers configured executables and quoted `java -jar` paths, and IntelliJ 2026.2.2 packaging/tests pass. Interactive parity and release packaging remain separate gates                                                                                                                                                                                                                                                                                                                                                                                                          |
| LSP-7 | IN PROGRESS | Cancellation is checked before dispatch and after in-flight document work; completed futures are cleaned by exact future identity after insertion to handle fast tasks and reused request IDs. Pre-dispatch cancellation is one-shot, and a bounded seen-ID set prevents post-completion cancellation from poisoning reused IDs. Scheduling uses two workers with a 64-entry bounded queue and JSON-RPC `-32001` overload responses. Deterministic edit/property coverage now runs 32 Unicode/malformed-recovery seeds. The 60-second Linux/JDK 21 gate reports 2,202 rounds at 36.700 rounds/sec with a −567,968 byte post-GC heap delta; supported-host validation and IntelliJ restart coverage remain |
| LSP-8 | IN PROGRESS | `editorArtifacts` rebuilds the versioned VS Code `0.5.49` VSIX, IntelliJ 2026.2.2 plugin, and Vim archives; VS Code npm smoke and headless Vim LSP/outline fixtures pass. Extracted bare and six-host cross-runtime `cplus-0.5.49` launchers start `cplus lsp` with clean JSON-RPC stdout. The supported-host release matrix is now wired; tagged evidence and interactive IntelliJ coverage remain                                                                                                                                                                                                                                                                                                       |

## Latest evidence

- LSP-7.8 is closed locally by `CPlusLspServerTest.survivesDeterministicUnicodeAndMalformedEditMatrix`. It replays 32 deterministic seeds containing valid full-document edits, malformed intermediate snapshots, supplementary Unicode, repeated revisions, parser recovery diagnostics, and final symbol recovery.
- Verified with `gradle :cli:test --tests cplus.CPlusLspServerTest.survivesDeterministicUnicodeAndMalformedEditMatrix --no-daemon --max-workers=1 --offline -Dorg.gradle.native=false` (`BUILD SUCCESSFUL`). The test respects the current full-sync and asynchronous-read contract; it does not require intermediate requests to observe every transient revision.
- LSP-7.6 benchmark slice: `:cli:benchmarkLsp` includes configurable sustained-load instrumentation, while `:cli:benchmarkLspGate` enforces the documented policy. The 60-second Linux/JDK 21 gate completed 2,202 rounds/46,242 requests at 36.700 rounds/sec, passed tail fairness, and stayed below the 64 MiB post-GC heap-growth ceiling with a −567,968 byte delta.
- LSP-8.1 discovery slice: the editor clients share the documented precedence of explicit command, project-local `.cplus/cpc(.sh|.cmd)` launcher, and installed `cpc`. The shared `documentation/fixtures/lsp-discovery` project is exercised by VS Code and Vim; IntelliJ tests cover configured/project/fallback cases; extracted bare and six-host cross-runtime packages pass protocol smoke. Supported release-host validation remains LSP-8.6.
- LSP-7.7a hosted evidence: CI run `36576516545` completed the parser, host-compiler, benchmark, protocol, and reliability gates successfully on both `linux-x86_64` and `linux-arm64`; macOS arm64 failed three import-closure tests and Windows x86_64 failed five LSP tests. The workflow now runs protocol and benchmark checks independently and uploads host reports even when one fails, so the next run will retain complete failure payloads.
- CI run `36790605715` was investigated without relaxing the benchmark policy: macOS arm64 reported two import-resolution test failures before the benchmark task, while the benchmark itself passed (`22.800` sustained rounds/sec, negative post-run heap delta, and overload accounting green). The LSP import materializer now keeps canonical paths for filesystem reads but uses the lexical import path and URI for document identity and `SourceId`, preventing host-specific `/var`/`/private/var` canonicalization from changing definition/workspace-symbol locations. The complete `CPlusLspServerTest` class and the new generated-comptime imported-declaration regression pass locally; hosted macOS verification remains required.
- CI run `36590485867` confirmed the prior import/cancellation fixes on Linux and narrowed the remaining cross-host issue to two mapped-range lookups: one macOS manifest-import definition and one Windows comptime-materialization workspace symbol. `LspDocument.symbolAt` now requires the mapped selection range to belong to the opened document before using it for hit-testing; imported/generated declarations continue through name-based visibility resolution. The focused local suite passes all 31 tests. A new hosted run is required before LSP-7.7 closes.
- CI run `36593943620` showed that exact source-name comparison was still too strict on macOS arm64 and Windows x86_64: both hosts continued to fail one mapped-range lookup while Linux and all benchmark/editor gates passed. `sameSourceFile` now compares URI and filesystem-path representations through normalized, canonical paths, preserving the imported-origin exclusion while accepting equivalent host-specific spellings. The focused LSP suite and aggregate offline gate pass locally: 31 focused tests, 104/104 grammar parses, and all project tests. The fix is pushed as `b5718de`; another hosted run is required for LSP-7.7 closure.
- CI run `36599719553` confirmed the source-identity hit-test fix on Linux and retained only cross-host URI presentation failures: macOS manifest-import definition lookup and Windows baseline/comptime symbol lookups. `displaySymbol` now remaps canonical materialization origins to matching live document URIs, preserving the lexical URI supplied by the editor for definitions and workspace symbols. The focused 31-test suite and aggregate offline gate pass locally; a hosted rerun is required.
- CI run `36602082135` confirmed the comptime URI materialization fix on macOS and Linux, but Windows then exposed the baseline definition fixture as a separate canonical-versus-lexical URI case. Symbol serialization now receives the owning live document URI as a fallback, while still preferring a matching imported document URI for mapped declarations. The focused 31-test suite passes locally; hosted Windows verification remains required.
- CI run `36607942388` passed all four parser hosts, editor bundles, and benchmarks, but the aggregate CLI test job reported one `servesSymbolsCompletionHoverDefinitionDiagnosticsAndShutdownOverStdio` completion assertion failure at line 54. The same exact `./gradlew --offline --no-daemon test` command passes locally, so this is recorded as a hosted aggregate-run flake requiring replay before the release stage is promoted.
- The pending host fix gives each asynchronous request an instance-specific cancellation token/future, preventing reused JSON-RPC IDs from canceling older work. LSP import discovery retains AST indexing and adds a recovery-node-only textual import fallback plus a canonical relative-path fallback for Windows/macOS URI normalization. Source-level Kotlin compilation passes; host CI remains the acceptance gate.
- Direct runtime smoke using the rebuilt classes and `parser-tree-sitter/build/native-parser/libktreesitter-c.so` passed both repaired invariants: relative comptime-import workspace symbols and reused request-ID cancellation. This is local Linux evidence only; the supported-host gate remains open.
- LSP-5.6b receiver-chain slice: `CPlusLspServer.receiverValue` recursively resolves `.` and `->` field chains and rejects operator/shape mismatches at each step. `CPlusLspServerTest.resolvesChainedValueAndPointerFieldReceivers` covers `outer.inner.` and `outer.ptr->`; the focused test and aggregate offline suite pass with 104/104 grammar parses. Method-return chains, write tracking, implicit conversions, and full comptime semantic parity remain outside this slice.
- LSP-5.6b.2 method-return slice: simple zero/nonscoped-argument function and method calls now contribute their declared return shape to receiver resolution, including access modifiers and pointer returns. `resolvesMethodReturnReceiversForChainedMemberCompletion` covers `factory.get()->value`; the full LSP class and aggregate suite pass. Nested argument expressions and broader overload/conversion semantics remain fail-closed or delegated.
- LSP-5.6d.1 effect slice: the CLI advertises and serves `textDocument/documentHighlight`, reusing resolved AST references and classifying declarations, assignments, compound assignments, increments, and ordinary uses as LSP write/read highlights. `classifiesDocumentHighlightsAsReadsAndWrites` passes, as do the complete LSP class and aggregate suite; richer data-flow and callback mutation analysis remain open.
- LSP-5.6e.1 comptime parity slice: `resolvesReceiverCompletionThroughAComptimeMaterializedAlias` verifies that a generated `int_list_t` alias participates in receiver completion and exposes its generated `buffer` field while retaining the opened `.cp` document. The full LSP class and aggregate suite pass; generated writes, broader alias chains, and all comptime semantic features remain open.
- LSP-5.6e.2 incremental comptime invalidation: `invalidatesGeneratedComptimeMembersAfterDocumentChange` opens a generated `int_box_t` with a `get` method, verifies generated completion, removes the comptime declarations through `didChange`, and verifies that `get` and `int_box_t` are absent from the updated completion result. The focused test passes; broader generated-scope invalidation and all comptime semantic features remain open.
- LSP-5.6c.3 callable slice: instance-method overload ranking now removes only the implicit `self` parameter before comparing explicit call arguments; static methods retain their complete parameter list. `resolvesOverloadedInstanceMethodByExplicitArgumentType` selects the `const char*` instance overload from a string call, `resolvesOverloadedMethodReturnReceiverByExplicitArgumentType` proves the same ranking through a method-return receiver, and `doesNotInferReceiverFromKnownWrongCallArity` verifies fail-closed behavior. The focused test and aggregate offline Gradle gate pass with 104/104 grammar parses; implicit conversions, ambiguity diagnostics, and complete callable semantics remain open.
- LSP-5.6c.4 callable slice: same-name numeric candidates now receive a bounded scalar-conversion distance after exact-shape matching. `ranksClosestNumericConversionWhenNoExactOverloadExists` selects `long` over `double` for an integer argument; the focused and aggregate offline Gradle gates pass with 104/104 grammar parses. Full C conversion legality remains delegated to the selected compiler.
- LSP-5.6c.5 callable slice: when distinct callable declarations share the best bounded score, symbol selection now fails closed instead of guessing by source order. `failsClosedForEquallyRankedCallableCandidates` verifies an ambiguous duplicate signature returns no definition, while `keepsFreeFunctionSelectionSeparateFromSameNamedMethods` verifies receiver-free calls exclude same-named methods. Focused and aggregate offline Gradle gates pass with 104/104 grammar parses.
- LSP-5.6c.6 callable diagnostics: open-document analysis now publishes `CPLUS_AMBIGUOUS_CALL` as a mapped severity-1 error for proven same-score receiver-free overload ambiguity, while leaving unresolved calls and uniquely ranked calls unchanged. `publishesAnErrorForProvenAmbiguousCallableCalls` verifies the framed diagnostic and clean shutdown; the focused LSP class and aggregate gate pass.
- LSP-5.6c.7 receiver diagnostics: the same mapped `CPLUS_AMBIGUOUS_CALL` error now covers resolvable `.`/`->` method calls, and visible-symbol lookup includes the current unsaved snapshot before the stored import closure. `publishesAnErrorForProvenAmbiguousReceiverCalls` verifies the receiver-owner filter and framed error; the complete LSP class passes.
- LSP-5.6c.8 return-receiver safety: chained completion now fails closed when equally ranked overloads have different return receiver shapes. `failsClosedForAmbiguousOverloadedFunctionReturnReceivers` prevents declaration-order selection for `make(1).`; the complete `CPlusLspServerTest` suite passes. This keeps ambiguous semantic inference conservative; user-facing ambiguity diagnostics for chained member completion remain a later slice.
- LSP-5.6c.9 dereferenced arguments: bounded overload ranking now unwraps parenthesized expressions and resolves unary `*` against a known pointer declaration. `resolvesAnOverloadForDereferencedPointerArgument` selects the integer overload for `choose(*pointer)`; focused and aggregate Gradle gates pass.
- LSP-5.6b.3 parenthesized receivers: receiver matching now understands explicit address/dereference forms used by C-plus receiver sugar, including `(&box).field` and `(*pointer).field`. `resolvesParenthesizedAddressAndDereferenceReceivers` verifies completion for both forms; the focused LSP suite passes.
- LSP-5.6c.10 unary numeric arguments: bounded overload ranking now preserves scalar shapes through unary `+`, `-`, `~`, and `!`. `resolvesAnOverloadForUnaryNumericArgument` selects the integer overload for `choose(-1)`; focused and aggregate Gradle gates pass.
- LSP-5.6c.11 explicit casts: bounded overload ranking now recognizes simple cast target shapes, including pointer casts. `resolvesAnOverloadForExplicitCastArgument` selects the `const char*` overload for `choose((const char*)text)`; focused and aggregate Gradle gates pass.
- LSP-5.6c.12 literal suffixes: bounded overload ranking now recognizes common C integer suffixes (`L`, `LL`, `U`) and floating suffixes (`f`, `l`), preserving the inferred scalar family for navigation. `resolvesOverloadsForCIntegerAndFloatingLiteralSuffixes` selects the `long` and `float` overloads for `1L` and `1.0f`; focused and aggregate offline Gradle gates pass with 104/104 grammar parses. Full C literal typing and conversion legality remain delegated to the selected compiler.
- LSP-5.6d.2 indirect-write effects: document highlights now keep a pointer variable as a read in `*pointer = value`; the write belongs to the pointee rather than the pointer storage. `classifiesIndirectPointerAssignmentsAsPointerReads` covers declaration-write, indirect-assignment-read, direct-pointer-assignment-write, and dereference-read occurrences. The focused CLI LSP gate passes; richer alias/data-flow mutation remains outside this bounded service.
- Phase 7 semantic-node evidence: generated declaration matching now checks origin overlap over the full identifier span, so a specialized struct tag composed from template text plus invocation text retains its `STRUCT_DECLARATION` handle. The generic-struct parser fixture verifies typedef and struct-node identity; aggregate frontend gates remain green. Per-member semantic nodes and canonical emitter promotion remain open.
- Phase 7 semantic-member evidence: the frontend now publishes normalized aggregate-member nodes owned by synthesized struct/union handles through both prototype and mapped-source results. The generic-struct fixture verifies the field handle and its pre-hygiene source revision; the aggregate gate remains green. Member type/ownership semantics and canonical emitter promotion remain open.

Local verification command:


```text
./gradlew test --no-daemon --max-workers=1 --offline
```

The current LSP fixes are pushed only after the focused and aggregate local
gates pass. Native DLL-per-editor packaging, a separate LSP Gradle module, and
target-specific compiler/ABI support remain out of scope for this roadmap.

- CI run `36647197070` passed the complete supported-host parser, compiler,
  benchmark, LSP reliability, editor-bundle, CLI distribution, and packaged-LSP
  matrix. Both bare and cross-runtime ZIP launchers passed framed
  initialize/initialized/shutdown/exit validation on Linux x86_64/arm64, macOS
  arm64, and Windows x86_64. The preceding run `36645700770` exposed a Windows
  launcher quoting defect; `cpc.cmd` was corrected in `2283728` by removing the
  trailing path separator before quoting `-Dcplus.home`. The replacement run
  passed without a timeout or protocol error.
- CI run `36795168927` isolated a cross-host import-indexing regression: macOS
  arm64 and Windows x86_64 failed the same three import-closure tests, while
  Linux x86_64/arm64 passed the complete LSP gates and all hosts passed the
  benchmark. `importRequests` now combines AST-indexed imports with the
  lexical recovery scan instead of skipping recovery whenever the AST returns
  a partial import list. The focused three-test regression and the complete
  `CPlusLspServerTest` class pass locally; hosted replay is required.
- The follow-up run `36798702065` confirmed the parser, host-compiler,
  benchmark, and complete LSP gates on both Linux hosts, but reproduced the
  three import-closure failures on macOS arm64 and Windows x86_64. Relative
  import identities now resolve from the original file URI string instead of
  converting through a host `Path`; the resolved `Path` remains authoritative
  for filesystem reads. The focused import-closure suite passes locally after
  this correction.
