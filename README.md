# C-plus

C-plus is a small Kotlin command-line processor that lowers C-plus source (`.cp` or `.c+`) to ordinary C. C-plus keeps C's expressions and statements, adding struct-scoped methods, method-call syntax, and ownership/visibility annotations.

The `cli/` Gradle module contains the command-line application and its tests. The `compiler/` module contains the transcoder, mapped emitter, source-map model, diagnostics, and embedded TinyCC adapter. The root project only aggregates the modules and forwards lifecycle tasks.

## Build and run

This is a Java 21-compatible Gradle project. The wrapper is the canonical build entry point:

```sh
./gradlew build
./gradlew test
./gradlew run --args='help'
```

The same tasks can be addressed explicitly as `:cli:run`, `:cli:test`, or `:cli:fatJar`.

To create a self-contained release jar, pass the release version explicitly:

```sh
./gradlew -Prelease=0.2.0 fatJar
java -jar cli/build/libs/c-plus-0.2.0.jar help
```

The bundled TinyCC JNI library is used for compilation, so `compile` and `run` do not require a system `tcc` executable:

```sh
./gradlew run --args='transcode examples/basic.cp -o build/basic.c'
./gradlew run --args='compile examples/basic.cp -o build/basic -DDEBUG=1'
./gradlew run --args='run examples/basic.cp -o build/basic-run -Iinclude'
```

The installed application can also be launched from `cli/build/install/c-plus/bin/c-plus` after `./gradlew :cli:installDist`.

Build all deployable editor artifacts with:

```sh
./gradlew editorArtifacts
```

This writes `vscode-cplus/dist/cplus-language-support.vsix`, `intellij-cplus/build/distributions/*.zip`, and `vim-cplus/dist/vim-cplus-*.tar.gz`/`.zip`.

Compilation prints each pass to stderr. Generated C contains `#line` directives pointing back to the `.cp` file, and TinyCC diagnostics are normalized and reported with the original C-plus path and line.

## Repository modules

- `compiler/`: reusable Kotlin transpiler and embedded TinyCC adapter.
- `cli/`: command-line application and integration tests.
- `documentation/spec/`: living language, comptime, and compiler specifications.
- `vscode-cplus/`: deployable VS Code extension with highlighting, completion, symbols, navigation, diagnostics, and CLI commands.
- `intellij-cplus/`: deployable IntelliJ Platform plugin with a flat PSI parser, highlighting, completion, and declaration navigation.
- `vim-cplus/`: deployable Vim runtime with file detection, highlighting, omnifunc completion, quickfix diagnostics, and compiler commands.

Editor integrations provide local syntax and lightweight semantic support without a language server. VS Code and Vim can also invoke the CLI for compiler-backed diagnostics/builds; the IntelliJ plugin remains self-contained and uses its local PSI/index services.

## Current lowering rules

- `typedef struct name_t { ... } name_t;` method definitions and declarations are moved outside the struct.
- An instance method whose first parameter is `*self` receives an implicit `name_t *self` parameter.
- Methods become `name__method(...)`; for example, `(&value).reset()` becomes `name__reset(&value)`.
- `name_t.method(...)` becomes the corresponding static method call.
- `pub`, `priv`, `mut`, `borrowed`, `owned`, and `stat` are retained as empty C macros. A `static` method remains a C `static` function.

The receiver type is inferred from declarations such as `name_t value;` or `name_t *value;`. Unknown receivers are left unchanged so ordinary C remains valid.

Keyword-led and legacy comptime declarations are resolved before method lowering. Comptime string calls can splice validated type names into generated function identifiers; for a more source-visible public name, use an ordinary C `#define` alias before the comptime invocation. This is a coding convention handled by the C preprocessor, not a special C-plus alias feature. The syntax, materialization rules, and current limitations are documented in [`documentation/spec/SPEC.comptime.md`](documentation/spec/SPEC.comptime.md).
