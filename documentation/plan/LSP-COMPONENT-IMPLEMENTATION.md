# C-plus LSP Component Plan

## Scope

The language server is a CLI component, not a separate Gradle module. The CLI already packages the Tree-sitter C-plus parser and its host JNI library, so one implementation owns parsing, diagnostics, and protocol behavior.

## Transport and ownership

`cplus lsp` uses standard input/output and JSON-RPC 2.0 framing with `Content-Length` headers. It owns document text in memory and reparses opened or changed documents through the parser backend. Diagnostics are published with zero-based LSP positions and retain parser codes, messages, and source ranges.

The server is intentionally independent of editor APIs. VS Code, Vim, and future clients can launch the configured CLI and speak the same transport. IntelliJ may initially launch the CLI as an external language-server process; an in-process adapter is a later optimization, not a second parser implementation.

## Implemented first slice

- `initialize`, `shutdown`, and `exit`.
- `textDocument/didOpen`, `didChange`, and `didClose`.
- Parser-backed `textDocument/publishDiagnostics`.
- `textDocument/documentSymbol` protocol response (currently an empty result).
- CLI help and a JVM protocol test covering framing, initialization, diagnostics, and shutdown.

## Migration phases

1. Stabilize the stdio protocol and parser diagnostic contract.
2. Add document symbols, completion, hover, and definition responses from normalized AST data.
3. Add VS Code and Vim clients using the configured `cplus lsp` executable.
4. Add an IntelliJ external-process adapter; evaluate in-process embedding only after protocol behavior is stable.
5. Package editor launch configuration and platform discovery without copying parser implementations into each plugin.

Native DLL-per-editor packaging, a separate LSP Gradle module, and target-specific compiler/ABI support are deferred. The legacy textual compiler frontend remains selectable while the Tree-sitter component matures.

Validation: `./gradlew :cli:test --tests cplus.CPlusLspServerTest --no-daemon --max-workers=1`.
