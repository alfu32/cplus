# Frontend/tooling implementation sprint

This is the finite implementation batch requested on 2026-10-02. It supersedes
open-ended "semantic parity" implementation work; historical evidence remains
in the phase and LSP ledgers. Implementation, local verification, and hosted/UI
acceptance are separate states. No new syntax, legacy retirement, compiler
bundles, or full C ABI checker is part of this sprint.

| ID | Principal task | Status | Acceptance boundary |
|----|----------------|--------|---------------------|
| S1 | Compiler materialization accepts in-memory imported sources | DONE | Optional read-only source provider; unsaved/new imports override disk without writing files |
| S2 | AST tooling owns common recovery/mapping diagnostics | DONE | Shared mapping warnings/traversals, advisory warning retention, CLI preserved-stream recovery distinct from strict API success |
| S3 | Rebuild generated dependent documents on edits/close/save/watch | DONE | Transitive cycle-safe invalidation, close-to-disk restoration, filesystem refresh with dirty-buffer precedence |
| S4 | Keep original, generated, and lexical URI coordinates distinct | DONE | Mapped usage hover, document-local highlights, deduplicated references, canonical file identity with lexical URI presentation |
| S5 | Complete structural symbol index | DONE | Parameters/enumerators/comma declarators, cached hierarchy, template/test declarations, local/workspace filtering |
| S6 | Complete advertised semantic responses | DONE | Scoped/typed receiver completion, canonical shapes, signatures, original-source folds/test lenses, semantic typedef/symbol tokens |
| S7 | Fail visibly and recover per request/document | DONE | Mapped materialization warnings, worker error responses, bounded framing/recovery; scope-boundary semantic map copies and single snapshot index |
| S8 | Expose server features in VS Code | DONE | References/highlights/workspace symbols, signatures/folds/lenses/semantic tokens, cancellation and correct protocol enum conversion |
| S9 | Harden VS Code process synchronization | DONE | Environment/CC, global flag placement, request deadlines, restart/reopen/diagnostic reset, watched files and save notifications |
| S10 | Refresh IntelliJ configuration/lifecycle | DONE | Validate/persist environment, restart on settings change, language ID/work directory, lifecycle feedback and one automatic recovery |
| S11 | Pin cohesive regression fixtures and documentation | DONE | Compiler/parser/CLI/editor invariant fixtures, LSP user/developer specification and updated adapter READMEs |
| S12 | Build/compile checks and implementation handoff | DONE | Focused Kotlin/parser/CLI/editor checks pass; local commit contains only sprint-owned changes; cross-host/full-corpus/UI acceptance explicitly separate below |

Mop-up belongs to its principal row, not a new task. Deep corpus, GUI, and host
verification may follow this implementation sprint at the user's request.

`DONE` here means the specified implementation is present with focused local
evidence. It does not promote historical full-semantic-parity or interactive
IDE acceptance rows to complete.

## Implemented subcomponents

- Compiler: overlay-aware import resolver/materializer, imports/flags returned
  together with mapped text, parser-neutral AST identifier/declarator helpers,
  recoverable diagnostic channel, and scope-boundary semantic traversal.
- Parser/CLI: preserve warning severity through reparses and lowering; explicit
  AST CLI emission can retain diagnosed source, while the prototype API stays
  strict. Auto mode tries compatibility lowering before preserved-source recovery.
- Server: original and materialized trees, immutable per-request semantic/import
  views, dependent regeneration, mapped diagnostics/navigation, complete advertised
  standard feature responses, versioned diagnostics and bounded error handling.
- Editors: VS Code consumes the standard services and synchronizes process/files;
  IntelliJ refreshes settings and reports/retries unexpected termination; Vim
  synchronizes unsaved edits, aggregates diagnostics, navigates UTF-16/UTF-8 spans,
  and rejects stale responses/malformed frames.

## Local evidence (2026-10-02)

| Principal rows | Evidence | Result |
|---|---|---|
| S1–S2 | `:compiler:test --tests cplus.CPlusAstToolingTest` | 3 passing tests |
| S2 | `:parser-tree-sitter:jvmTest --tests cplus.parser.TreeSitterFrontendDiagnosticTest` | 3 passing tests: advisory emission, diagnosed stream recovery, fatal exception propagation |
| S2/S9 | CLI diagnostic recovery plus compiler-flag/test-subcommand and multi-file fixture compatibility tests | 3 passing tests; preserved diagnosed source is written to the requested C output, errors retain original source location |
| S3–S7 | `:cli:test --tests cplus.CPlusLspServerTest --tests cplus.CPlusLspToolingSprintTest` | 72 existing + 7 new tests passing, including unsaved generated imports, new-file overlays, original coordinates, semantic tokens and symlink namespace |
| S7 | Existing `CPlusLspBenchmarkTest` fixtures | Local benchmark fixtures passing; no hosted-performance claim |
| S8–S9 | `cd vscode-cplus && npm test` | TypeScript build and smoke/restart/diagnostic/tooling adapters passing |
| S10 | IntelliJ standalone Gradle compile and settings/recovery tests | Local compile and state tests passing; not a forced-exit GUI replay |
| S11 | Headless `vim-cplus/test/discovery.vim` and `tooling.vim` | Passing environment/discovery, Unicode navigation and diagnostic aggregation checks |

The initial broad CLI run was not a clean acceptance result: it reported missing
anonymous/generated JVM classes plus two compatibility regressions. The latter
were fixed under S2; focused reruns pass. Do not quote the earlier broad run as
full-suite evidence or assume its class-loading failures have a proven cause.

## Finite verification handoff (not new implementation principals)

| ID | Parent | Status | Remaining acceptance check |
|---|---|---|---|
| V1 | S2/S12 | OPEN | Run a clean full Kotlin/frontend/corpus suite with no concurrent writers to build outputs; retain failures rather than widening the grammar goal |
| V2 | S3–S7/S12 | OPEN | Replay this fixed fixture set on Windows x86_64 and macOS arm64, including native loading and original URI/UTF-16 diagnostics |
| V3 | S8–S10 | OPEN | Real VS Code/IntelliJ open-edit-navigate, typedef colors, test lenses, forced child exit and settings-driven restart |
| V4 | S11 | OPEN | Native-process Vim LSP replay after edit/save/close and unexpected-exit recovery |

No legacy retirement, new compiler bundling/target support, complete C ABI
checking, rename/refactoring, or extra protocol feature project is implied by
this handoff. Full semantic parity is not a finite acceptance condition here.
