# C-plus

C-plus is C with struct-scoped methods and a staged compile-time language. It accepts `.cp` and `.c+` files and transcodes them to ordinary C; the generated program keeps C's data layout, ABI, and runtime model.

## Cross-compilation

The cross-build CLI bundle includes TinyCC payloads for all six supported host combinations. The bundled JVM facade accepts the six target triples below, while end-to-end support depends on the target runtime and sysroot:

| Host bundle | Linux x86-64 | Linux ARM64 | macOS x86-64 | macOS ARM64 | Windows x86-64 | Windows ARM64 |
| --- |:---:|:---:|:---:|:---:|:---:|:---:|
| Linux (x86-64 / ARM64) | ✓ | ✓ | — | — | △ | △ |
| macOS (x86-64 / ARM64) | ✓ | ✓ | — | — | △ | △ |
| Windows (x86-64 / ARM64) | ✓ | ✓ | — | — | △ | △ |

Legend: `✓` executable output verified; `△` minimal executable output works, but the bundled sysroot is incomplete; `—` the current C-plus/TinyCC bridge does not produce a usable target binary. The Windows sysroots currently lack `mm_malloc.h`, required through `<stdlib.h>`, so typical programs using the standard library may fail to compile. The macOS target currently fails to link `libc`; even `-c` has produced an ELF object rather than Mach-O, and C-plus now reports a target-format mismatch instead of treating that as success.

The matrix applies to the `-Ptarget=crossbuild` bundle. A host-only package contains just its matching TinyCC host payload and sysroot (Linux/Windows); it is intended for native builds, not cross-compilation. TinyCC does not bundle a macOS SDK. Select a program output target with TinyCC's `--target` option; C-plus forwards it through the bundled API (ARM64 is named `aarch64`):

```sh
cpc compile src/main.cp --target=linux-aarch64 -o build/app
```

The package's `-Ptarget` selects which TinyCC host payloads and sysroots are embedded; `--target` independently selects the program's output platform. Use `-Ptarget=crossbuild` for embedded cross-compilation; `run` also attempts to execute the output, so use it only when the target can run on the current host. Pass `--sysroot path` or `--sysroot=path` to override an embedded sysroot. An external `tcc` installation is governed by that compiler's own targets.

The JARs also contain additional TinyCC command-line backends such as `i386`, `arm`, and `riscv64`. Their unsuffixed upstream defaults are Linux ABI targets; `c67` is a TMS320C67 DSP backend that emits COFF, not a general-purpose host executable. These extra backend binaries are not among the six target triples currently exposed through C-plus `--target`. See the [TinyCC target notes](https://github.com/Tiny-C-Compiler/tinycc-mirror-repository/blob/mob/tcc-doc.texi) and [upstream target defaults](https://github.com/mirror/tinycc/blob/mob/Makefile).

## Language and examples

Write methods inside a struct and call them on values or pointers. C-plus infers the receiver address and lowers the method to a normal C function:

```c
typedef struct counter_t {
    int value;

    pub int increment(borrowed mut *self) {
        self->value++;
        return 0;
    }
} counter_t;

int main(void) {
    counter_t counter = {0};
    counter.increment();       // counter__increment(&counter)

    counter_t* pointer = &counter;
    pointer->increment();      // counter__increment(pointer)
    return 0;
}
```

The annotations `pub`, `priv`, `mut`, `borrowed`, and `owned` document intent and remain empty C macros; they do not change the ABI or enforce ownership. Static methods use `static` inside the struct and are emitted as C `static` functions.

Comptime declarations generate C-plus declarations before C lowering. Imports can use stable standard-library paths, and type/function generators can materialize generic containers and functions over multiple passes:

```c
comptime import "stdlib:/containers/dynamic_list.cp";
comptime typedef dynamic_list(int) int_list_t;
```

Named `@test` blocks run through the CLI test command. `@assert(condition)` and `@assertEquals(expected, actual)` print their inputs and colored results. C sources can be included unchanged with `#include` or `@import("file.c")`.

See the [language specification](documentation/spec/SPEC.language.md), [comptime specification](documentation/spec/SPEC.comptime.md), and [project/module guide](documentation/spec/SPEC.project.md) for syntax, examples, and current limitations.

## Standard library

The [standard-library guide](stdlib/README.md) is the consolidated catalog of the modules currently in `stdlib/`, their APIs, lifecycle rules, examples, and tests:

- OS-backed `scratch`, `hot`, `warm`, and `cold` allocation arenas, with allocation-intent diagnostics.
- An owning, mutable `string` type with C string/memory helpers and indentation operations.
- Generic resizable `dynamic_list(T)` and `dynamic_map(K, V)` containers.
- A comptime `list_mapper(T, R, InputList, OutputList)` generator for callback-driven list conversion.

Detailed references: [allocator behavior and design](documentation/spec/stdlib/ALLOCATORS.SPEC.md) and [string API](documentation/spec/stdlib/STRING.SPEC.md). Run the library tests with `cpc test stdlib/tests/*.cp`; see the guide for examples and allocator-specific test notes.

## IDE support

Deployable editor bundles are attached to [GitHub releases](https://github.com/c-plus/c-plus/releases):

- [VS Code](vscode-cplus/README.md): highlighting, completion, navigation, and optional compiler diagnostics. Install a downloaded VSIX with `code --install-extension cplus-language-support-<version>.vsix`.
- [IntelliJ IDEA](intellij-cplus/README.md): highlighting, completion, and declaration navigation; targets IntelliJ IDEA 2026.2.2. CI caches the target platform between runs. Install its ZIP with **Settings → Plugins → Install Plugin from Disk**.
- [Vim](vim-cplus/README.md): syntax highlighting, omnifunc completion, quickfix diagnostics, and compiler commands.

## Releases and building from source

Most users can download a CLI bundle or editor plugin from [Releases](https://github.com/c-plus/c-plus/releases). The manual GitHub Actions workflow builds the selected branch or tag; branch runs publish workflow artifacts, while a successful tag run creates or updates a GitHub Release with the CLI and editor bundles. It does not run automatically on push.

For local development, install a Java 21 JDK and use the Gradle wrapper:

```sh
./gradlew build test
./gradlew run --args='help'
./gradlew -Prelease=0.3.4 fatJar
./gradlew -Prelease=0.3.4 -Ptarget=linux-x86_64 bundleDist
./gradlew -Prelease=0.3.4 -Ptarget=crossbuild bundleDist
./gradlew bundleJars
./gradlew -Prelease=0.3.4 editorArtifacts
```

The root Gradle project aggregates the Kotlin compiler in `compiler/` and CLI/tests in `cli/`. `bundleDist` writes both `dist/cplus-VERSION-TARGET.zip` and a directly executable `dist/cplus-VERSION-TARGET.jar`; `bundleJars` extracts matching JARs from ZIPs already present for that version. With `-Ptarget` absent, the CLI JAR and distribution omit TinyCC binaries and sysroots; `compile`, `run`, and `test` use `tcc` from `PATH` (or `TCC`). Use one host payload name (`linux-x86_64`, `linux-aarch64`, `macos-x86_64`, `macos-aarch64`, `windows-x86_64`, or `windows-aarch64`) for a native-only bundle. Use `-Ptarget=all` or `-Ptarget=crossbuild` to include all six host payloads and the available Linux/Windows libc sysroots. The older `-Pos` and `-Parch` properties are no longer supported.

TinyCC JARs are not stored in Git. CI resolves the latest published [TinyCC release](https://github.com/alfu32/tinycc/releases/latest), downloads the base CLI plus only the host payloads each job needs, and shares them as short-lived workflow artifacts. For local tests or embedded-target bundles, download the same assets with `bash .github/scripts/download-tinycc.sh latest tinycc-cli-cross.jar tinycc-cli-linux-x86_64.jar` (requires `curl` and `jq`; replace/add the target JAR as needed); these files are ignored by Git.
