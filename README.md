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

Compilation prints each pass to stderr. Generated C contains `#line` directives pointing back to the `.cp` file, and TinyCC diagnostics are normalized and reported with the original C-plus path and line.

## Repository modules

- `compiler/`: reusable Kotlin transpiler and embedded TinyCC adapter.
- `cli/`: command-line application and integration tests.
- `documentation/spec/`: living language, comptime, and compiler specifications.
- `vscode-cplus/`: VS Code extension with highlighting, completion, symbols, and hover support.
- `intellij-cplus/`: IntelliJ Platform integration sources for highlighting and completion.
- `vim-cplus/`: Vim runtime files for file detection, highlighting, and omnifunc completion.

Editor integrations currently provide local syntax and lightweight symbol support. Compiler-backed diagnostics and full semantic IntelliSense will use a shared language-server layer when that interface is introduced.

## Current lowering rules

- `typedef struct name_t { ... } name_t;` method definitions and declarations are moved outside the struct.
- An instance method whose first parameter is `*self` receives an implicit `name_t *self` parameter.
- Methods become `name__method(...)`; for example, `(&value).reset()` becomes `name__reset(&value)`.
- `name_t.method(...)` becomes the corresponding static method call.
- `pub`, `priv`, `mut`, `borrowed`, `owned`, and `stat` are retained as empty C macros. A `static` method remains a C `static` function.

The receiver type is inferred from declarations such as `name_t value;` or `name_t *value;`. Unknown receivers are left unchanged so ordinary C remains valid.

Comptime and generic declarations beginning with `@` are resolved before method lowering for the supported scalar, import, reflection, block, entity, and generic-struct forms. The syntax, materialization rules, and current limitations are documented in [`documentation/spec/SPEC.comptime.md`](documentation/spec/SPEC.comptime.md).
