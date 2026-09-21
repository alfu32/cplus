# VS Code C-plus Module

This extension provides C-plus file detection for `.cp` and `.c+`, TextMate syntax highlighting, annotation and method completion, symbols, hover information, local delimiter diagnostics, definition/reference navigation, and optional compiler-backed diagnostics.

## Development

```sh
npm install
npm run compile
npm run package
```

`npm run package` creates `dist/cplus-language-support.vsix`, a deployable VS Code extension. Install it with:

```sh
code --install-extension dist/cplus-language-support.vsix
```

Open this directory in VS Code and press `F5` to launch an Extension Development Host. Set `cplus.compilerCommand` to the C-plus CLI and enable `cplus.compilerDiagnostics` to run compiler-backed checks on save. The local indexer remains available without a language server and understands structs, methods, fields, comptime names, and generated method names.
