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
   the server; ignore or report malformed notifications safely.
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

Gate: valid, malformed, edited, closed, and Unicode-containing documents have
stable diagnostics and positions.

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

The active work is deliberately limited to the local LSP slice. Complete these
items in order; do not start release packaging or CI integration until the
listed local gate passes.

| Order | Work item                                                | Depends on | Acceptance gate                                                                                                          |
|-------|----------------------------------------------------------|------------|--------------------------------------------------------------------------------------------------------------------------|
| 1     | LSP-5.1: define project-root and module-path discovery   | LSP-4      | A fixture resolves relative and manifest-declared imports to canonical URIs                                              |
| 2     | LSP-5.2: index bounded imported documents                | LSP-5.1    | `workspace/symbol`, hover, and definition resolve symbols from an imported file                                          |
| 3     | LSP-5.3: invalidate changed documents and dependents     | LSP-5.2    | A changed import graph removes unreachable snapshots and republishes dependent diagnostics without restarting the server |
| 4     | LSP-5.4a: add lexical scope resolution                   | LSP-5.3    | A shadowed local reference resolves to the nearest declaration in its containing block                                   |
| 5     | LSP-5.4b: add typedef, receiver, and method resolution   | LSP-5.4a   | Shadowed aliases and `object.method`, `object->method`, and `self` calls resolve consistently                            |
| 6     | LSP-5.4c: index materialized comptime declarations       | LSP-5.4b   | Generated workspace symbols and definitions retain original `.cp` invocation ranges                                      |
| 7     | LSP-5.5a: add cancellation and revision guards           | LSP-5.4c   | `$/cancelRequest` suppresses canceled document responses and revision checks prevent stale publication                   |
| 8     | LSP-5.5b: add cooperative asynchronous scheduling        | LSP-5.5a   | Expensive parse/index work yields or cancels without blocking the stdio reader                                           |
| 9     | LSP-6.1–6.4: complete client parity and launch recovery | LSP-5.5b   | VS Code, Vim, and IntelliJ pass the same protocol fixture for diagnostics, navigation, completion, and tests             |
| 10    | LSP-7: reliability and performance evidence              | LSP-6      | Framing fuzz cases, restart behavior, and corpus latency measurements are recorded                                       |
| 11    | LSP-8: distribution discovery and release packaging      | LSP-7      | Bare and bundled distributions launch `cplus lsp` with clean stdout                                                      |

The current code has completed items 1–9 locally for adapter construction and
packaging. Item 9 still needs an interactive IntelliJ protocol fixture and
client restart coverage before it can close completely. Items 10–11 remain
planned work; the adapters are not a substitute for reliability or release
evidence.

## Current status ledger

| Phase | State       | Evidence or next action                                                                                                                                                                                                                                       |
|-------|-------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| LSP-0 | DONE        | Protocol behavior is documented and replayed by the CLI JVM fixture                                                                                                                                                                                           |
| LSP-1 | DONE        | Stdio server, framing, lifecycle, and CLI entry point are implemented locally                                                                                                                                                                                 |
| LSP-2 | DONE        | Open/change/close snapshots publish parser diagnostics locally                                                                                                                                                                                                |
| LSP-3 | DONE        | Normalized-AST structural symbols are indexed locally                                                                                                                                                                                                         |
| LSP-4 | DONE-LOCAL  | Symbols, completion, hover, and definition pass the local protocol fixture                                                                                                                                                                                    |
| LSP-5 | IN PROGRESS | Open-document `workspace/symbol`, bounded imports, dependency invalidation, lexical/receiver lookup, evaluator-backed comptime symbols, cancellation/revision guards, and bounded cooperative scheduling are implemented; broader semantic resolution remains |
| LSP-6 | DONE-LOCAL | VS Code, Vim, and IntelliJ have opt-in adapters that launch the shared `cplus lsp` process; IntelliJ 2026.2.2 packaging/tests pass. Interactive parity and release packaging remain separate gates |
| LSP-7 | IN PROGRESS | Cancellation, fair bounded scheduling, deterministic fragmented-frame coverage, malformed-frame recovery, UTF-16 position coverage, bounded VS Code/Vim restart recovery, IntelliJ platform-managed restart action, and CLI incremental parse-session reuse are implemented; add broader recovery fuzzing and performance evidence |
| LSP-8 | IN PROGRESS | `:cli:fatJar`, VS Code VSIX, IntelliJ plugin, and Vim archives build locally; the fat JAR and extracted `cplus-0.5.47-bare.zip` launcher start `cplus lsp` with clean JSON-RPC stdout and the adapter artifacts are present. Bundled-runtime discovery, supported-host release validation, and interactive IntelliJ coverage remain |

Local verification command:

```text
./gradlew test --no-daemon --max-workers=1 --offline
```

The current local changes are intentionally not pushed until the next local
LSP work package is complete. Native DLL-per-editor packaging, a separate LSP
Gradle module, and target-specific compiler/ABI support remain out of scope for
this roadmap.
