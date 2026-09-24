# C-plus

C-plus is C with struct-scoped methods and a staged compile-time language. It accepts `.cp` and `.c+` files and transcodes them to ordinary C; the generated program keeps C's data layout, ABI, and runtime model.

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
./gradlew -Prelease=0.3.4 -Pos=linux -Parch=x86_64 bundleDist
./gradlew -Prelease=0.3.4 editorArtifacts
```

The root Gradle project aggregates the Kotlin compiler in `compiler/` and CLI/tests in `cli/`. With no platform properties, the CLI jar and distribution omit TinyCC binaries and sysroots; `compile` and `run` then use `tcc` from `PATH` (or the executable named by `TCC`). Add matching `-Pos` and `-Parch` properties to embed a target, or set both to `all` for a complete all-platform bundle. The accepted values are `linux|mac|win|all|none` and `x86_64|arm64|all|none`; `none` must be selected for both.
