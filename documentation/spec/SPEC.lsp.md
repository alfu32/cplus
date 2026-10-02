# C-plus language-server contract

Status: implemented tooling interface; local and hosted acceptance are tracked
separately in [the finite sprint ledger](../plan/FRONTEND-TOOLING-SPRINT.md).
This does not retire the legacy transcoder or make the server a complete C
type checker. The downstream C compiler remains authoritative for C legality.

## Starting and configuring the server

```sh
cpc --stdlib ./stdlib --target=x86_64-linux -v0 lsp
cpc lsp --trace /tmp/cplus-lsp.log
```

`--stdlib`, `--target`, frontend/backend selectors, and verbosity are global
options placed before the command. `--trace` belongs after `lsp`. The server
uses Tree-sitter independently of the transcoding frontend selection. Target
OS controls comptime materialization and parsing; it does not require a C
compiler or a target sysroot. `workspace/didChangeConfiguration` accepts
`settings.cplus.targetOs` (`linux`, `windows`, or `macos`) and refreshes open
snapshots. Other compiler configuration belongs to the launching process.

The transport is framed JSON-RPC on stdin/stdout. Protocol stdout must contain
no shell banners, compiler output, or diagnostic logging. stderr, `--trace`,
and the client's output/IDE log are the feedback channels. Frames larger than
16 MiB are rejected at the transport boundary.

## Advertised services

| Method | Contract |
|---|---|
| `textDocument/documentSymbol` | Hierarchical structs/unions/enums/functions, fields/methods, parameters, local and comma-separated declarations, templates and named tests; original declaration and selection ranges |
| `workspace/symbol` | Loaded workspace/open-import symbols, deduplicated and excluding local variables/parameters; not a whole-disk index |
| `textDocument/completion` | C/C-plus keywords plus visible declarations; lexical local filtering and bounded typed receiver selection; LSP completion kinds, not symbol kinds |
| `textDocument/hover` | Fenced C declaration/access/annotations; range refers to the word under the requesting cursor |
| `textDocument/definition` | Original declaration URI and selection range through the current import closure |
| `textDocument/references` | Deduplicated mapped occurrences in loaded documents, honoring `includeDeclaration` |
| `textDocument/documentHighlight` | Read/write occurrences in the requested document only, never dependency offsets applied to its editor |
| `textDocument/signatureHelp` | Declared overloads, explicit parameter labels, active argument, bounded overload selection; implicit instance `self` is not an explicit argument |
| `textDocument/foldingRange` | Multiline original-source blocks, aggregates/enums, comments, and preprocessor regions |
| `textDocument/codeLens` | Original `@test` spans with `cplus.runTest(uri, fixtureName)` client command |
| `textDocument/semanticTokens/full` | Original AST identifier roles and scoped declarations; typedef/type, aggregate, callable, field, variable, parameter, and enumerator classifications with declaration modifiers |

Ranges are zero-based, half-open UTF-16 coordinates, while internal compiler
spans remain one-based line/column coordinates. Generated offsets, original
offsets, and editor URI spelling are separate identities. URI output retains
the editor's lexical namespace (including symlink paths); canonical paths are
used for file identity and compiler import resolution, not to silently rename
an opened document.

An unresolved receiver/call does not invent a definition. Equal best callable
scores return no selected declaration and may produce `CPLUS_AMBIGUOUS_CALL`.
The bounded scorer supports canonical alias/pointer/array shapes and common
expression conversions; it is not an ABI or general data-flow checker.

Semantic tokens use the advertised legend and full-document responses (no
delta cache). Unresolved ordinary identifiers retain lexical highlighting.
Comments and strings are not searched for symbol names. VS Code explicitly
registers the legend; IntelliJ receives the same standard capability through
its native LSP client. Theme colors and support vary by editor version.

## Snapshot and dependency lifetime

`didOpen` and full-text `didChange` install an immutable current snapshot before
evaluating imports. Versions must increase; stale edits are ignored. The same
compiler import resolver/materializer used by tooling receives open imported
snapshots through a source provider. An unsaved generator therefore overrides
its saved file, including an open file that does not yet exist on disk, without
writing an editor buffer to disk.

An imported edit re-materializes the transitive dependent closure. Imports,
generated declarations, mapped spans, and diagnostics are rebuilt together.
Traversal is cycle-safe and dependency discovery is bounded. Completion and
navigation cannot see an unrelated open file simply because it has a matching
symbol name. Successful compiler materialization supplies actual import edges;
when materialization fails, recovered AST/lexical import discovery is a
best-effort fallback, not a promise to evaluate unsupported conditional forms.

`didClose` clears that editor's diagnostics and restores a disk dependency if
it is still imported; otherwise unreachable imported snapshots are pruned.
`didSave` and watched-file notifications refresh dependent documents. Dirty
open buffers retain precedence over filesystem notifications. A new process
must re-open current documents; it cannot inherit snapshots from an old process.

## Diagnostics, failures, and cancellation

Unmapped named/opaque fragments produce advisory warning
`CPLUS_UNSUPPORTED_AST`. Failed comptime materialization publishes warning
`CPLUS_TOOL_MATERIALIZATION` and retains the original recovered tree, allowing
other editor requests to proceed. Tree-sitter syntax/recovery diagnostics and
proven callable ambiguity retain error severity. An imported range is not
published as an unrelated underline in the root document. Diagnostic
notifications include the current document version where available.

Diagnostics do not authorize suppression of normal program/test stdout.
Tooling warnings do not make an otherwise complete AST structurally incomplete.
AST lowering preserves parser warnings; parser errors or incoherent pass output
remain distinct from advisory diagnostics. This is not a promise that every
malformed construct can be safely lowered by the experimental strict frontend.

Feature requests use two workers and a bounded queue. Overload returns JSON-RPC
`-32001`; ordinary request exceptions return `-32603` rather than disappearing
inside an executor. Cancellation/revision guards discard obsolete work. Fatal
transport/native-runtime failures may still terminate the process. No client
may promise infinite retries: automatic crash recovery is bounded to one restart
until an explicit restart or relevant settings change resets the window.

## Editor configuration examples

VS Code uses a binary plus arguments, not a shell program:

```json
{
  "cplus.languageServer": true,
  "cplus.languageServerCommand": "/opt/c-plus/cpc",
  "cplus.languageServerArguments": ["--target=x86_64-linux"],
  "cplus.compilerArguments": ["--frontend=auto"],
  "cplus.environment": {"PATH": "$PATH:/opt/c-plus", "CC": "clang"},
  "cplus.languageServerRequestTimeout": 15000
}
```

VS Code requests have cancellation and deadlines (15 seconds by default,
at least 60 seconds for initialization). A restart rejects pending requests,
clears diagnostics, and re-opens current buffers. Watch notifications include
creation, change, and deletion of `.cp`/`.c+` files.

IntelliJ **Settings → Tools → C-plus** accepts shell sequences in command
fields. Environment entries such as `PATH="$PATH:$HOME/.local/bin/c-plus"`
and `CC=clang` are expanded against the inherited environment. Apply validates
environment syntax before saving; relevant settings restart live LSP clients.
Command-test diagnostics and the IDEA log show command/work directory feedback;
environment values are not dumped into routine lifecycle logging. Unexpected
exit raises a notification and attempts one restart. Explicit restart resets
the recovery allowance. The IDE's LSP subsystem owns protocol presentation.

```vim
let g:cplus_lsp_enable = 1
let g:cplus_language_server_command = '/opt/c-plus/cpc'
let g:cplus_environment = {'CC': 'clang'}
:CPlusLspHover
:CPlusLspDefinition
:CPlusLspReferences
```

Vim synchronizes edits as well as saves; request results are ignored after the
source buffer changes. Diagnostics aggregate by URI in quickfix. References use
a location list so they do not overwrite diagnostics. Vim job environment
values are passed literally; use expanded Vim values when extending `PATH`.

## Developer boundary

`compiler/CPlusAstTooling.kt` owns parser-neutral identifier ancestry,
declarator-name extraction, and mapping warnings. Consumers do not duplicate
these traversals by Tree-sitter spelling. `ComptimeCompiler.sourceProvider`
is an optional read-only overlay; ordinary compilation still loads imports
from disk. `CPlusToolMaterialization` returns mapped text, resolved imports,
and consolidated compiler options together.

The LSP keeps an original editor AST for folding/tests/templates and a
materialized AST for generated runtime symbols. Symbols carry original ranges,
lexical scope, ownership, and available canonical types. Document hierarchy is
cached once per immutable snapshot rather than recomputed for every request.
The request read view and semantic-document context must stay per request;
global name scans would leak aliases from unrelated import closures.

When extending this boundary, add one fixture per invariant to
`CPlusLspToolingSprintTest`, the compiler AST tooling tests, or the relevant
editor tests. Do not add unbounded spelling/permutation gates. Cross-host and
real-editor acceptance remain separate checks in the sprint ledger.
