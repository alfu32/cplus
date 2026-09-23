# C-plus Compiler and CLI Specification

Status: living specification. Update this document whenever the CLI, module layout, or compilation behavior changes.

## Commands

The executable is named `cplus` and accepts these subcommands:

```text
cplus help
cplus transcode filename.cp [-o some_file_name.c]
cplus compile filename.cp [-o executable] [passthrough tcc parameters]
cplus run filename.cp [-o executable] [passthrough tcc parameters]
```

`transcode` defaults to `filename.c`. `compile` and `run` default to an executable named `filename`. The `-o` option selects the output path. Additional arguments for `compile` and `run` are passed to the embedded TinyCC compiler; for example, `-DFLAG=1` or `-Iinclude`.

`run` compiles first and then executes the generated executable, inheriting its standard input, output, and error streams.

## Diagnostics and Passes

Compilation logs each pass to stderr. The current passes are:

```text
read-source
comptime-resolve
comptime-pass-1-parse
comptime-pass-1-expand
comptime-pass-2-parse
comptime-pass-2-expand
...
comptime-materialize
collect-struct-types
lower-method-calls
lower-struct-methods
emit-mapped-c
tcc-compile
run-executable
```

`comptime-pass-N-parse` parses the active comptime declarations for that expansion pass. `comptime-pass-N-expand` evaluates them and emits mapped C-plus; generated comptime declarations are discovered on a later pass. `comptime-materialize` returns the fully resolved mapped source after the parser finds no remaining comptime forms. Phase 2 begins only then; unsupported or unresolved forms fail before method lowering and are never passed to the C parser. Typed AST decorators and general in-source plugin execution remain proposed.

Generated C includes `#line` directives. TinyCC diagnostics are normalized and mapped to the original C-plus filename and line where possible, including imported files and generated entities. Comptime parser/evaluator errors carry the originating source span and the CLI prints `file:line:column`. Transcoding and compiler failures return a non-zero exit code; CLI argument or unexpected processing errors return `2`.

## Build Layout

- `compiler/` contains the reusable Kotlin transcoder, mapped emitter, diagnostics, and TinyCC adapter.
- `cli/` contains the command-line application and its tests.
- `documentation/spec/` contains the living language, comptime, and compiler specifications.
- `vscode-cplus/`, `intellij-cplus/`, and `vim-cplus/` contain editor integrations.

The project targets Java 21:

```sh
./gradlew build
./gradlew test
./gradlew -Prelease=0.2.0 fatJar
java -jar cli/build/libs/c-plus-0.2.0.jar help
```
