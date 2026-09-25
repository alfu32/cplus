# VS Code C-plus Module

This extension provides C-plus file detection for `.cp` and `.c+`, TextMate syntax highlighting, annotation and receiver-aware member completion, symbols, hover information, local delimiter diagnostics, definition/reference navigation, and optional compiler-backed diagnostics. Completion resolves `value.` and `pointer->` against declared struct values/pointers, and `type_t.` offers static methods.

The highlighter gives comptime keywords, built-in comptime forms (`@if`, `@for`, `@assert`, and related forms), error-handling forms (`@throws`, `@try`, `@catch`), test helper macros, reflection properties, compiler flags, the `os` comptime value, and `self` dedicated scopes. Completion includes C keywords, all C-plus annotations, comptime and error-handling forms, and built-in test helpers. Identifier splices such as `mapper__@name(T)__to__@name(R)` are recognized. These editor services do not evaluate comptime; use the CLI for the exact materialized C output.

The compiler-lowered `defer` statement and the `@throws`/`@try`/`@catch` error-handling forms are highlighted and offered as C-plus completions.

The Testing view discovers named `@test` fixtures and places run controls in the editor gutter. Fixture runs invoke the full command in `cplus.testProgramCommand` and append the source path and fixture name. **Run C-plus main** adds a `▶ Run main` code lens and invokes `cplus.runnerCommand` with the source path. The compile command is configured separately as `cplus.compilerCommand`; command fields accept an executable and fixed arguments, such as `/path/to/cpc test`.

The extension contributes a **C-plus File Icons** theme for `.cp` and `.c+` files. Select it through **Preferences: File Icon Theme**; VS Code requires an explicit icon-theme choice before file icons are applied. The theme's SVG is included in the extension so it also works in an Extension Development Host.

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
