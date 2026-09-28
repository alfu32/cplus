# C-plus editor capability matrix

This matrix records the Phase-8 local contract. It distinguishes a capability that is
implemented and tested from a capability that is intentionally delegated to the editor
or compiler. The adapters consume `cplus.parse.v1` for normalized AST/diagnostics and
`cplus.imports.v1` for resolved import navigation; they do not depend on Tree-sitter
node types directly.

| Consumer | Syntax and special tokens | Parser diagnostics and mapping | Symbols/navigation | Completion | Tests/gutter | Imports | Local evidence |
|---|---|---|---|---|---|---|---|
| VS Code | TextMate scopes for annotations, `self`, comptime forms, flags, reflection, tests, and errors | Optional parser-on-save diagnostics with UTF-16 spans; stale results are ignored | AST-backed outline with local-index fallback | C-plus annotations, comptime forms, reflection, members, and methods | Testing view and gutter controls use AST fixture spans, with scanner fallback | Import graph command uses `cplus.imports.v1` | `cd vscode-cplus && npm test` |
| Vim | Syntax groups for C, annotations, `self`, comptime, tests, and reflection | `:CPlusParse`/quickfix; UTF-16 columns convert to Vim byte columns | `:CPlusSymbols` location-list outline | `omnifunc` for annotations, comptime forms, fields, and methods | Compiler commands are available; fixture execution remains CLI-driven | `:CPlusImportGraph` navigates resolved edges | `vim -Nu NONE -i NONE -n -es -S vim-cplus/test/outline.vim` |
| IntelliJ | Lexer token types for annotations, `self`, comptime, reflection, C keywords, and test macros | External annotator consumes current-buffer `cplus.parse.v1` diagnostics | Structure view and Go to Declaration use exact cached AST spans | Member/comptime completion uses the exact-source cache, with text fallback | Test line markers use AST fixture spans, with stale/unavailable fallback | Parser graph integration is available through configured CLI commands | `./gradlew -p intellij-cplus test --no-daemon --max-workers=1` |

## Deliberate boundaries

- Editor presentation is not required to be visually identical across hosts.
- Parser-backed results are accepted only for the exact current document snapshot.
- Unsaved-buffer parsing is supported where the adapter sends buffer text; disk-backed
  import graphs still require saved sources.
- The legacy text index/scanner remains a compatibility fallback until the compiler’s
  two-release promotion and retirement gate closes.
- Full interactive host validation and six-host native parser loading remain CI/release
  gates, not claims established by this local matrix.
