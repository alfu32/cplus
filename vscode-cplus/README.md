# VS Code C-plus Module

This extension provides C-plus file detection for `.cp` and `.c+`, TextMate syntax highlighting, annotation and method completion, symbols, hover information, local delimiter diagnostics, definition/reference navigation, and optional compiler-backed diagnostics.

The highlighter gives comptime keywords and `self` dedicated scopes, and recognizes `@test` blocks and identifier splices such as `mapper__@name(T)__to__@name(R)`. Simple object-like `#define generated_name public_name` aliases are indexed as callable symbols for completion and navigation. These editor services do not evaluate comptime; use the CLI for the exact materialized C output. The C preprocessor alias is a normal C convention, not a C-plus alias directive.

## Development

```sh
npm install
npm run compile
npm run package
```

`npm run package` creates a versioned deployable VSIX, such as `dist/cplus-language-support-0.3.1.vsix`, using `CPLUS_RELEASE_VERSION`, generated CLI version metadata, the latest Git tag, or the manifest version in that order. The root `editorArtifacts` task passes the same resolved version to all editor packages. Install the VSIX with:

```sh
code --install-extension dist/cplus-language-support-0.3.1.vsix
```

Open this directory in VS Code and press `F5` to launch an Extension Development Host. Set `cplus.compilerCommand` to the C-plus CLI and enable `cplus.compilerDiagnostics` to run compiler-backed checks on save. The local indexer remains available without a language server and understands structs, methods, fields, comptime names, and generated method names.
