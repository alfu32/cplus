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
cplus new project_name|.
cplus --stdlib directory <subcommand> ...
```

`transcode` defaults to `filename.c`. `compile` and `run` default to an executable named `filename`. The `-o` option selects the output path. Additional arguments for `compile` and `run` are passed to the embedded TinyCC compiler; for example, `-DFLAG=1` or `-Iinclude`.

`run` compiles first and then executes the generated executable, inheriting its standard input, output, and error streams.

`test` compiles and runs all `@test` blocks in the input files. Source paths must come first; any following arguments are exact, case-sensitive test-name filters. The shell may expand file globs before invoking C-plus:

```sh
java -jar c-plus.jar test test/folder/some_file.cp
java -jar c-plus.jar test test/folder/some_file.cp "print and init struct"
java -jar c-plus.jar test test/folder/*.cp "print and init struct" "list grows"
```

Each source file gets a temporary test executable, and its temporary output is deleted after execution. The driver prints numbered start/end banners for selected tests, with the test name highlighted yellow and a blank line before each start banner. Test PASS is green and FAIL is red. Every assertion prints its original expression(s), fixture-wide assertion number/total, given value, and expected value even when it passes. `@assert(condition)` reports the condition's boolean result; `@assertEquals(expected, actual)` prints scalar values where recognized and otherwise emits byte hex. Assertions are numbered in source order across all tests in each file. A failed assertion marks that test failed and continues to later tests; a nonzero test run returns `1`, an unknown requested name or CLI error returns `2`. Test bodies support `@assert(condition)`, bytewise `@assertEquals(expected, actual)`, the corresponding `CPLUS_TEST_ASSERT` compatibility macro, and `CPLUS_TEST_FAIL(message)`. Existing C files can be used with `#include "fixture.c"` or `@import("fixture.c")`; C imports become normal preprocessor includes. Their `main` definition is renamed in test builds so the generated driver can supply `main`.

`new` creates `cplus.toml`, `src/main.cp`, and `modules/`/`tests/` directories. It accepts a new directory or `.` for the current directory; it never overwrites an existing manifest, main source, or README. `--stdlib directory` overrides the standard-library root. Otherwise the CLI checks `--stdlib`, the project manifest, `CPLUS_STDLIB`, `CPLUS_HOME`, jar/bundle location, repository ancestors, and conventional user/system install paths, in that order.

C-plus module imports support stable search prefixes: `comptime import "stdlib:/memory/xmem.cp"` searches the standard library; `comptime import "module:/shared/types.cp"` searches project module paths. The `.cp`/`.c+` suffix may be omitted. Ordinary relative imports remain relative to the importing file. C `@import` continues to emit a C preprocessor include and may resolve `stdlib:/...` paths as well.

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
- `documentation/spec/` contains the living language, comptime, compiler, and project specifications.
- `vscode-cplus/`, `intellij-cplus/`, and `vim-cplus/` contain editor integrations.

The project targets Java 21:

```sh
./gradlew build
./gradlew test
./gradlew -Prelease=0.2.0 fatJar
./gradlew -Prelease=0.2.0 -Pos=linux -Parch=x86_64 bundleDist
./gradlew -Prelease=0.2.0 -Pos=all -Parch=all bundleDist
java -jar cli/build/libs/c-plus-0.2.0.jar help
```

`-Prelease=VERSION` sets the shared CLI/editor release version. The root `editorArtifacts` task passes it to VS Code, IntelliJ, and Vim packaging; standalone VS Code/IntelliJ packaging falls back to generated CLI version metadata or the latest Git tag.

The fat jar embeds TinyCC together with the sysroot for each selected platform. `-Parch=x86_64|arm64|all` and `-Pos=win|mac|linux|all` select which platform bundles are included; both default to `all`. For example, `./gradlew -Parch=arm64 -Pos=linux fatJar` includes only Linux ARM64. `arm64` selects TinyCC's `aarch64` bundle; `win` and `mac` select `windows` and `macos`. A jar built with restricted targets can compile and run programs only on a matching included platform.

`bundleDist` creates an expanded install folder and a versioned zip under `dist/`. Build one target with `-Pos=linux|mac|win -Parch=x86_64|arm64`, or all six native targets and all platform scripts with `-Pos=all -Parch=all`. Platform bundles include `cpc.sh`, `cpc.zsh`, or `cpc.cmd` and the matching install/uninstall scripts. Linux/macOS installers probe `/usr/local` and fall back to `$HOME/.local`; Windows probes `%ProgramData%`, falls back to `%USERPROFILE%\.bin`, updates the current-user `PATH`, registers `.cmdrc` through CMD's AutoRun registry value, and creates a `C+ Developer Console` shortcut.

The archive path is `dist/cplus-VERSION-OS-ARCH.zip`, with an expanded staging directory under `build/distributions/`. CI runs tests and a Linux bundle smoke test, packages each OS/architecture pair, validates selected native resources, checks macOS/Windows installer syntax on their respective runners, and uploads the complete all/all archive.
