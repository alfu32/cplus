# C-plus Compiler and CLI Specification

Status: living specification. Update this document whenever the CLI, module layout, or compilation behavior changes.

## Commands

The executable is named `cplus` and accepts these subcommands:

```text
cplus help
cplus parse filename.cp [--backend legacy|tree-sitter] [-o ast.json]
cplus parse --stdin [--source filename.cp] [--backend legacy|tree-sitter] [-o ast.json]
cplus graph filename.cp [-o imports.json]
cplus transcode filename.cp [-o some_file_name.c] [--frontend legacy|tree-sitter] [--target=TRIPLE]
cplus compile filename.cp [-o executable] [--frontend legacy|tree-sitter] [passthrough tcc parameters]
cplus run filename.cp [-o executable] [--frontend legacy|tree-sitter] [passthrough tcc parameters]
cplus test [run] [compiler flags] filename.cp [filename2.cp ...] [exact test name ...]
cplus test transcode [-o test.c] filename.cp
cplus test compile [-o executable] [compiler flags] filename.cp
cplus new project_name|.
cplus --stdlib directory <subcommand> ...
cplus -v0|-v1|-v2 <subcommand> ...
```

`parse` emits the recovered normalized syntax tree and diagnostics as JSON using schema `cplus.parse.v1`. All offsets are UTF-16 code units. Its default backend is `tree-sitter`; `--backend legacy` selects the legacy scanner adapter, whose ordinary C/C-plus regions are explicitly opaque. This option is for parser comparison and does not change the production transpilation backend. Its default output is stdout; `-o` writes the JSON to a file. Syntax errors still produce the partial tree and diagnostic list, then return status `1`; command or file errors return `2`.

Use `parse --stdin` to parse unsaved editor text. The source is read from stdin; optional `--source filename.cp` supplies the canonical path used in AST spans and diagnostics (without reading that file). The stdin form cannot be combined with a positional source filename, and `--source` is valid only with `--stdin`.

`graph` resolves the input through the normal C-plus comptime importer and emits `cplus.imports.v1` JSON. `dependencyOrder` lists canonical C-plus source identities in dependency-first order, ending with the requested root; `imports` contains directed `{importer, imported, location}` edges with source-mapped UTF-16 spans. It shares project, module, and standard-library resolution with compilation. The command performs normal transpilation to obtain the resolved graph, so syntax/import/materialization errors fail the command rather than returning a partial graph. Ordinary C `#include` and C `@import` directives remain compiler-managed and are not C-plus dependency edges. The default output is stdout; `-o` writes the JSON file.

`transcode` defaults to `filename.c`. `compile` and `run` default to an executable named `filename`. The `-o` option selects the output path. `transcode` accepts only `--target` among compiler options; it uses the option to choose comptime branches but does not compile. Additional arguments for `compile` and `run` are passed to the selected C compiler; for example, `-DFLAG=1` or `-Iinclude`. Code-processing commands print the C-plus transcoder version. Before each C compilation, the CLI reports whether the compiler is bundled or external and its payload/JAR or executable location.

## Diagnostics do not suppress emission

The transcoder reports mapped warnings and recoverable errors while continuing
the current code-generation pass. A warning may describe an unsupported or
recovered AST fragment; an error records a construct that could not be fully
resolved, but the emitter preserves the original text or the best available
lowering so that later C compilation can provide additional diagnostics. Every
diagnostic carries severity, code, message, and the original `.cp` URI/range
when a source map is available. Ordinary mapped errors do not stop
transcoding. Only implicitly fatal conditions—such as the transcoder crashing,
an unreadable input, an invalid output destination, or a pass that cannot
produce a coherent source stream—stop generation. `compile`, `run`, and `test` still
return the downstream compiler/runtime status; a non-fatal transcoder
diagnostic alone does not prevent generated C from being written.

`test` runs with the legacy frontend by default for compatibility. Its `run`, `compile`, and `transcode` forms also accept `--frontend=legacy|tree-sitter` (or the separated `--frontend tree-sitter` form). The Tree-sitter test path owns runtime materialization and lowering, then uses the established fixture harness bridge while fixture-body AST lowering remains a separate migration gate. This boundary is explicit and does not silently claim full test-front-end parity.

For migration tests, fixture extraction is an independent frontend concern. The
internal `CPlusLegacyPassSelection.extractTests` and
`TreeSitterPassSelection.extractTests` switches default to enabled and, when
disabled, fail at the original `@test` declaration instead of emitting unresolved
C-plus into C. They are rollback controls, not public language syntax or a promise
that the two harness implementations are already semantically identical.

The migration differential harness compares fixture order, decoded names, and
assertion counts across the legacy and AST frontends. It also compares normalized
generated `cplus_test_N` function-body tokens for the bounded supported fixture-body corpus, excluding
formatting, comments, and `#line` directives, plus the ordered original source
lines of discovered assertions. This is a body-parity contract for accepted forms,
not yet permission to remove the textual test extractor for unsupported forms.
The AST fixtures are additionally passed through the explicit test-harness bridge;
fixture names and assertion counts must survive that materialization. This validates
the current compatibility boundary.

Test-mode differential validation compares compiler options as a logical deduplicated
set because the harness bridge does not own dependency traversal. The ordinary
compilation differential separately compares exact dependency-first option ordering.
For test extraction, source-map coverage is scoped to extracted assertion source lines;
imported runtime modules may have different generated layout while retaining their own
mapped origins.

For ordinary compilation, dependency emission and compiler-option ordering are separate
contracts. Generated runtime C uses a canonical dependency-first order with stable ordering
for independent modules. Collected `comptime flags` retain the historical dependency-first
depth-first discovery order, so existing link and define ordering remains reproducible even
when canonical source emission sorts independent modules differently.

The bounded accepted fixture-body contract is the repository's ordinary C statements,
structured control flow, C-plus method/receiver calls, `defer`, and supported checked
calls/assertions. The standard-library fixture corpus is the executable positive
inventory for that contract. Checked calls embedded in unsupported expression contexts
are rejected at their original call span with no fixture or C output; this is a
fail-closed boundary, not an implicit promise to rewrite arbitrary C expressions.
The AST extractor is therefore classified `MIGRATED-FALLBACK`: it has parity and
rollback evidence for the bounded contract, while the legacy scanner remains available
until the separately tracked textual-retirement decision.

For runtime lowering, the migration differential can also report one exercised pass:
the full legacy/AST result must match on its bounded comparison fields, the pass must
be observed changing both mapped outputs, and disabling it must fail closed. This is
an acceptance primitive for building a corpus; it is not by itself a lowerer-retirement
criterion.

`comparePassSubstitution` provides a stronger migration check by enabling only the
selected transformation and its documented prerequisites. The receiver-call case
explicitly retains struct-method lowering because semantic receiver indexing depends
on method declarations. The report compares normalized C, compiler options, source-line
map coverage, and whether the selected pass changed both outputs, followed by host
compilation and execution.

Analysis-only frontend passes use a diagnostic parity contract rather than a text
change contract. The allocation-validation comparison requires matching normalized
diagnostic signatures and fail-closed rollback when validation is disabled.
`compareSemanticPassSubstitution` runs the validation pass with runtime lowerers
disabled and compares normalized allocation diagnostics, generated C/options, and
source-line map coverage. Because validation is analysis-only, unchanged source is
expected and is not treated as a failed pass exercise.
The parity comparison is exact for the bounded supported-flow corpus. The legacy
scanner is intentionally not treated as semantic authority: it may report a
possible assignment inside a zero-or-more-iteration `while` loop even when the
AST fixed-point analysis correctly retains the incoming domain on the
zero-iteration path. That remains a visible migration difference until an
independent ownership-diagnostic contract replaces both implementations; it must
not be hidden by weakening AST flow analysis or dropping the legacy diagnostic.
The current bounded report records `try`/`catch` as exercised and token-identical on
the representative fixture. The AST lowerer owns compound-body/catch-body braces and
emits a separator before the following statement so its generated C matches the
legacy shape while retaining independent mapped lowering.
The checked-error overlap fixtures cover consecutive and nested handlers, both
error-return and error-out conventions, and instance methods. Unsupported embedded
checked-call expressions remain mapped diagnostics rather than being rewritten by
guesswork.

Source-map parity is bounded by source-line coverage: every original file/line that
appears in the legacy `SourceMap` must also appear in the AST `SourceMap`. Generated
line numbers, columns, and additional AST-generated mapping entries are not required
to be identical. This protects diagnostic reachability without pretending that two
different emitters have the same layout.

The compilation frontend defaults to `legacy`, preserving the established production
transcoder. `--frontend=tree-sitter` selects the AST prototype for ordinary
`transcode`, `compile`, `run`, and explicitly selected `test` commands; it performs comptime fixed-point
materialization, semantic/runtime lowering, and mapped C emission through the
Tree-sitter pipeline. A Tree-sitter parser, lowering, or unsupported-construct
diagnostic fails the command before invoking the C compiler. The selector is a
migration and rollback switch, not permission to remove the legacy path: retirement
requires the finite overlap corpus and per-lowerer gates in
[`COMPILER-FRONTEND-MIGRATION-INVENTORY.md`](../plan/COMPILER-FRONTEND-MIGRATION-INVENTORY.md).

Compiler selection is: a usable bundled TinyCC route for the requested target; an explicit executable from `TCC`; system `tcc` found on `PATH`; then the command in `CC` (including simple quoted paths and arguments such as `CC='ccache gcc'`). `CC` is a fallback, not an override for an installed TinyCC; set `TCC` to choose an explicit TinyCC. When no compiler is usable, `compile`, `run`, and `test` print OS-specific setup commands. The external compiler must have the host C runtime development headers and libraries; optional libraries such as Raylib are installed separately.

`run` compiles first and then executes the generated executable, inheriting its standard input, output, and error streams.

Top-level `comptime flags` declarations in the source and its comptime imports are added to the compiler arguments for `compile`, `run`, and `test`. Duplicate logical source-declared options are removed in dependency-first, first-seen order, preserving required option/value pairs such as `-framework Cocoa`. `transcode` includes the consolidated arguments as an informational comment in generated C; when compiling that C separately, provide the arguments to the C compiler explicitly. C has no portable linker-flags directive.

Comptime exposes `os` as the normalized operating system of the selected target. `compile` and `run` derive it from TinyCC's `--target` option (or the local host when omitted); `transcode` can be given `--target` for the same selection. The current supported spellings include `linux`, `windows`, and `macos`. This only selects source branches: the target compiler/sysroot must still contain the requested libraries.

The shipped cross-runtime distribution names the supported host/architecture pairs explicitly.
The compiler accepts either the resource ID shown below or an equivalent target triple and
normalizes it to that ID for bundled-resource lookup and output-format validation:

| Host/target family | Resource ID | Accepted examples | Status |
|---|---|---|---|
| Linux | `linux-x86_64` | `x86_64-linux-gnu` | bundled TinyCC driver/runtime; ABI reflection model available |
| Linux | `linux-aarch64` | `linux-arm64` | bundled TinyCC driver/runtime; ABI reflection model available |
| macOS | `macos-x86_64` | `x86_64-apple-darwin` | bundled TinyCC driver/runtime; ABI reflection model available |
| macOS | `macos-aarch64` | `macos-arm64` | bundled TinyCC driver/runtime; ABI reflection model available |
| Windows | `windows-x86_64` | `x86_64-w64-mingw32` | bundled TinyCC driver/runtime; ABI reflection model available |
| Windows | `windows-aarch64` | `windows-aarch64` | bundled TinyCC driver/runtime; ABI reflection model available |

`-Ptarget=cross` packages all six TinyCC runtime payloads without libc sysroots; `-Ptarget=none`/`bare` packages no compiler runtime and uses the selected external compiler. This table describes C-plus target normalization and packaging availability, not a guarantee that every platform library or linker flag exists on every host. The compiler must still report an unsupported target or missing library instead of silently selecting another ABI.

### Calling conventions and declaration attributes

C-plus parses and source-preservingly emits C declarator attributes and calling
conventions. It does not emulate a convention, erase an unknown modifier, or silently
substitute another ABI. Final acceptance belongs to the selected C compiler/target.

| Spelling | Declared support boundary | Evidence |
|---|---|---|
| no modifier | portable supported C subset | local `cc`, GCC, and Clang compile/run fixtures |
| `__attribute__((noinline))` | GNU-compatible compilers | complex function-pointer-return method compile/run |
| `__attribute__((sysv_abi))` | GCC/Clang targets that implement that ABI attribute (notably x86/x86_64) | semantic retention plus local host compile/run |
| `__declspec(noinline)` | Windows GNU-compatible target compiler | MinGW x86_64 object fixture |
| `__cdecl`, `__stdcall`, `__fastcall`, `__thiscall` | MinGW x86_64 accepted spellings; their x64 machine-level distinctions are compiler-defined | MinGW x86_64 object fixture |
| `WINAPI` | Windows headers must define it; normally maps to the platform API convention | `<windows.h>` MinGW x86_64 object fixture |
| `__vectorcall`, `__clrcall` | parsed and preserved, but unsupported by the tested MinGW GCC; usable only when the selected compiler accepts them | preservation fixture; downstream compiler rejection is intentional |
| `__restrict`, `__sptr`, `__uptr`, `_unaligned`, `__unaligned` | parsed and retained on their pointer layer; target acceptance is compiler-defined | semantic spelling/layer fixtures |

The same modifier remains attached to nested callback and returned-callable
declarators. Unsupported target combinations are ordinary downstream C compiler
errors and retain C-plus source mapping. Six-host/architecture compiler evidence is
tracked separately from the language/parser contract; a local MinGW result does not
claim Windows arm64 support.

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

C-plus module imports support stable search prefixes: `comptime import "stdlib:/memory/xmem.cp"` searches the standard library; `comptime import "module:/shared/types.cp"` and its `project:/shared/types.cp` alias search project module paths. The `.cp`/`.c+` suffix may be omitted. Ordinary relative imports remain relative to the importing file. Prefixed paths cannot escape their configured search root. C `@import` continues to emit a C preprocessor include and may resolve `stdlib:/...` paths as well.

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
collect-error-annotations
lower-defer-statements
collect-struct-types
lower-method-calls
lower-struct-methods
lower-try-catch
emit-mapped-c
tcc-compile
run-executable (run command)
run-tests (test command)
```

`comptime-pass-N-parse` parses the active comptime declarations for that expansion pass. `comptime-pass-N-expand` evaluates them and emits mapped C-plus; generated comptime declarations are discovered on a later pass. Materialization substitutes type parameters and evaluates validated identifier splices in generated type and function declarations. C preprocessor directives such as `#define` are passed through as ordinary source; the compiler does not define a comptime alias directive or interpret alias macros. `comptime-materialize` returns the fully resolved mapped source after the parser finds no remaining comptime forms. Phase 2 begins only then; unsupported or unresolved forms fail before method lowering and are never passed to the C parser. Typed AST decorators and general in-source plugin execution remain proposed.

`collect-error-annotations` validates and removes `@throws` markers while retaining function-symbol metadata. `lower-try-catch` runs after method calls and methods are lowered to C identifiers, so checked calls resolve against their declarations. Error-handling syntax and the supported-call boundary are specified in [`SPEC.errors.md`](SPEC.errors.md).

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
