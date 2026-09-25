# C-plus Compiler and CLI Specification

Status: living specification. Update this document whenever the CLI, module layout, or compilation behavior changes.

## Commands

The executable is named `cplus` and accepts these subcommands:

```text
cplus help
cplus transcode filename.cp [-o some_file_name.c] [--target=TRIPLE]
cplus compile filename.cp [-o executable] [passthrough tcc parameters]
cplus run filename.cp [-o executable] [passthrough tcc parameters]
cplus test [run] [compiler flags] filename.cp [filename2.cp ...] [exact test name ...]
cplus test transcode [-o test.c] filename.cp
cplus test compile [-o executable] [compiler flags] filename.cp
cplus new project_name|.
cplus --stdlib directory <subcommand> ...
cplus -v0|-v1|-v2 <subcommand> ...
```

`transcode` defaults to `filename.c`. `compile` and `run` default to an executable named `filename`. The `-o` option selects the output path. `transcode` accepts only `--target` among compiler options; it uses the option to choose comptime branches but does not compile. Additional arguments for `compile` and `run` are passed to the selected C compiler; for example, `-DFLAG=1` or `-Iinclude`. Code-processing commands print the C-plus transcoder version. Before each C compilation, the CLI reports whether the compiler is bundled or external and its payload/JAR or executable location.

Compiler selection is: a usable bundled TinyCC route for the requested target; an explicit executable from `TCC`; system `tcc` found on `PATH`; then the command in `CC` (including simple quoted paths and arguments such as `CC='ccache gcc'`). `CC` is a fallback, not an override for an installed TinyCC; set `TCC` to choose an explicit TinyCC. When no compiler is usable, `compile`, `run`, and `test` print OS-specific setup commands. The external compiler must have the host C runtime development headers and libraries; optional libraries such as Raylib are installed separately.

`run` compiles first and then executes the generated executable, inheriting its standard input, output, and error streams.

Top-level `comptime flags` declarations in the source and its comptime imports are added to the compiler arguments for `compile`, `run`, and `test`. Duplicate logical source-declared options are removed in dependency-first, first-seen order, preserving required option/value pairs such as `-framework Cocoa`. `transcode` includes the consolidated arguments as an informational comment in generated C; when compiling that C separately, provide the arguments to the C compiler explicitly. C has no portable linker-flags directive.

Comptime exposes `os` as the normalized operating system of the selected target. `compile` and `run` derive it from TinyCC's `--target` option (or the local host when omitted); `transcode` can be given `--target` for the same selection. The current supported spellings include `linux`, `windows`, and `macos`. This only selects source branches: the target compiler/sysroot must still contain the requested libraries.

`test` runs all `@test` blocks by default; `test run` is equivalent. Compiler flags may appear before or after source paths, and `-v0`, `-v1`, or `-v2` may be placed before or after the command. Flags from all input files and CLI arguments are logically deduplicated and passed to each test compilation. Generated test C carries one consolidated `cplus compiler flags` comment, matching ordinary transpilation. `test compile` and `test transcode` currently accept one source file because each generated test harness owns a `main` function. Test-name filters remain exact and case-sensitive:

```sh
java -jar c-plus.jar test test/folder/some_file.cp
java -jar c-plus.jar test run -lraylib test/folder/raylib_math.cp
java -jar c-plus.jar test test/folder/some_file.cp "print and init struct"
java -jar c-plus.jar test test/folder/*.cp "print and init struct" "list grows"
java -jar c-plus.jar test transcode -o build/tests.c test/folder/some_file.cp
java -jar c-plus.jar test compile -o build/tests -lraylib test/folder/raylib_math.cp
java -jar c-plus.jar -v2 test test/folder/some_file.cp
```

`-v1` (default) shows errors without compiler-pass chatter; `-v0` suppresses C-plus diagnostics and progress, while `-v2` also shows pass and compiler details. Program output from `cplus run` and test-runner output (fixture banners, assertions, and aggregate reports) are ordinary output and remain visible at every verbosity level. Each selected source file gets a temporary test executable, and its temporary output is deleted after execution. Test fixture banners are numbered continuously across the selected fixtures from all files; assertion numbers likewise continue across files and use one aggregate denominator. Test names are yellow, PASS is green, FAIL is red. Every assertion prints its original expression(s), invocation-wide assertion number/total, given value, and expected value even when it passes. `@assert(condition)` reports the condition's boolean result; `@assertEquals(expected, actual)` prints scalar values where recognized and otherwise emits byte hex. After all files run, the CLI prints an aggregate table with each file's selected fixture/assert totals and result, followed by total files, fixtures, assertions, and failed files. Assert totals count assertion sites in the selected fixtures, including sites after a failing assertion that short-circuits that fixture. A failed assertion marks that fixture failed and later fixtures still run; a nonzero test run returns `1`, an unknown requested name or CLI error returns `2`. Test bodies support `@assert(condition)`, bytewise `@assertEquals(expected, actual)`, the corresponding `CPLUS_TEST_ASSERT` compatibility macro, and `CPLUS_TEST_FAIL(message)`. Existing C files can be used with `#include "fixture.c"` or `@import("fixture.c")`; C imports become normal preprocessor includes. Their `main` definition is renamed in test builds so the generated driver can supply `main`.

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
./gradlew -Prelease=0.2.0 -Ptarget=none bundleJar
./gradlew -Prelease=0.2.0 -Ptarget=cross bundleJar
java -jar cli/build/libs/c-plus-0.2.0.jar help
```

`-Prelease=VERSION` sets the shared CLI/editor release version. The root `editorArtifacts` task passes it to VS Code, IntelliJ, and Vim packaging; standalone VS Code/IntelliJ packaging falls back to generated CLI version metadata or the latest Git tag.

By default, `fatJar` and `bundleJar` omit TinyCC native binaries and all sysroots. `transcode` still works normally, while `compile`, `run`, and `test` select an external compiler in the order above. The `CC` fallback is useful for GCC or Clang when system TinyCC is unavailable.

With the embedded Linux TinyCC bundle, C-plus links executable output statically by default so it does not depend on the bundle's musl dynamic loader being installed on the host. Pass `-dynamic` or `-shared` to request a non-static link; external `tcc` invocations keep that compiler's own defaults.

`-Ptarget=none` (the default) embeds no TinyCC native binaries; the bare CLI uses an available external compiler. `-Ptarget=cross` embeds the six TinyCC host drivers from `tinycc-cross-cli-no-sysroots.jar`, but no sysroots; target headers and libraries must be supplied by the host or through compiler options. `all` and `crossbuild` are aliases for `cross`. The old per-platform targets and `-Pos`/`-Parch` properties are unsupported.

### Cross-target drivers and system dependencies

For the all-host `cross` bundle, pass target options through `compile` or `run`, for example `cpc compile src/main.cp --target=linux-aarch64 -o build/main`. For a native target, when the embedded host payload has no sysroot, C-plus delegates to system `tcc` so the system toolchain supplies its configured startup objects, headers, and libraries. For a foreign target, the JVM TinyCC facade selects the matching embedded cross-driver; no target sysroot is embedded or injected, so cross-compilation needs compatible headers, libraries, and (where applicable) a sysroot supplied externally. `run` additionally requires the produced binary to be runnable on the current host.

The CI workflow refreshes both TinyCC inputs from the latest TinyCC release before building. On tagged runs it publishes two application ZIPs plus the VS Code, IntelliJ, and Vim plugin bundles: `-cross-no-sysroots.zip` includes all six TinyCC host runtimes but no sysroots, and `-bare.zip` includes no TinyCC runtime and uses an external compiler. Both ZIPs contain the CLI JAR, standard library, documentation, examples, launchers, and platform install/uninstall scripts.

Build the application ZIPs locally with `./gradlew -Prelease=VERSION -Ptarget=cross bundleDist` and `./gradlew -Prelease=VERSION -Ptarget=none bundleDist`. `bundleDist` stages the existing launchers and install/uninstall scripts along with the documentation, standard library, and examples. `bundleJar` remains available for standalone JARs; `bundleJars` is a legacy helper for extracting JARs from existing ZIPs.
