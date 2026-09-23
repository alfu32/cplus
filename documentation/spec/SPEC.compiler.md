# C-plus Compiler and CLI Specification

Status: living specification. Update this document whenever the CLI, module layout, or compilation behavior changes.

## Commands

The executable is named `cplus` and accepts these subcommands:

```text
cplus help
cplus transcode filename.cp [-o some_file_name.c]
cplus compile filename.cp [-o executable] [passthrough tcc parameters]
cplus run filename.cp [-o executable] [passthrough tcc parameters]
cplus test filename.cp [filename2.cp ...] [exact test name ...]
```

`transcode` defaults to `filename.c`. `compile` and `run` default to an executable named `filename`. The `-o` option selects the output path. Additional arguments for `compile` and `run` are passed to the embedded TinyCC compiler; for example, `-DFLAG=1` or `-Iinclude`.

`run` compiles first and then executes the generated executable, inheriting its standard input, output, and error streams.

`test` compiles and runs all `@test` blocks in the input files. Source paths must come first; any following arguments are exact, case-sensitive test-name filters. The shell may expand file globs before invoking C-plus:

```sh
java -jar c-plus.jar test test/folder/some_file.cp
java -jar c-plus.jar test test/folder/some_file.cp "print and init struct"
java -jar c-plus.jar test test/folder/*.cp "print and init struct" "list grows"
```

Each source file gets a temporary test executable, and its temporary output is deleted after execution. The driver prints start/end banners for each selected test and a per-file summary. A failed assertion marks that test failed and continues to later tests; a nonzero test run returns `1`, an unknown requested name or CLI error returns `2`. Test bodies support `@assert(condition)`, bytewise `@assertEquals(expected, actual)`, `CPLUS_TEST_ASSERT(condition)`, and `CPLUS_TEST_FAIL(message)`. Existing C files can be used with `#include "fixture.c"` or `@import("fixture.c")`; C imports become normal preprocessor includes. Their `main` definition is renamed in test builds so the generated driver can supply `main`.

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
collect-tests (test command only)
collect-struct-types
lower-method-calls
lower-struct-methods
emit-mapped-c
tcc-compile
run-executable (run command)
run-tests (test command)
```

`comptime-pass-N-parse` parses the active comptime declarations for that expansion pass. `comptime-pass-N-expand` evaluates them and emits mapped C-plus; generated comptime declarations are discovered on a later pass. Materialization substitutes type parameters and evaluates validated identifier splices in generated type and function declarations. C preprocessor directives such as `#define` are passed through as ordinary source; the compiler does not define a comptime alias directive or interpret alias macros. `comptime-materialize` returns the fully resolved mapped source after the parser finds no remaining comptime forms. Phase 2 begins only then; unsupported or unresolved forms fail before method lowering and are never passed to the C parser. Typed AST decorators and general in-source plugin execution remain proposed.

Generated C includes `#line` directives. TinyCC diagnostics are normalized and mapped to the original C-plus filename and line where possible, including imported files and generated entities. Comptime parser/evaluator errors carry the originating source span and the CLI prints `file:line:column`. Transcoding and compiler failures return a non-zero exit code; CLI argument or unexpected processing errors return `2`.

## Build Layout

- `compiler/` contains the reusable Kotlin transcoder, mapped emitter, diagnostics, and TinyCC adapter.
- `cli/` contains the command-line application and its tests.
- `stdlib/` contains generic C-plus container sources, comptime generators, examples, and tests.
- `documentation/spec/` contains the living language, comptime, and compiler specifications.
- `vscode-cplus/`, `intellij-cplus/`, and `vim-cplus/` contain editor integrations.

The project targets Java 21:

```sh
./gradlew build
./gradlew test
./gradlew -Prelease=0.2.0 fatJar
java -jar cli/build/libs/c-plus-0.2.0.jar help
```
