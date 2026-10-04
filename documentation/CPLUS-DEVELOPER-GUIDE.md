# C-plus developer guide

This is the self-contained guide for humans and coding agents working on C-plus. C-plus is a C-compatible systems language whose source is lowered to ordinary C before an installed C compiler or TinyCC compiles it. The generated C is an important compatibility boundary: C-plus conveniences must preserve valid C semantics, explicit ownership decisions, and ordinary linker behavior.

## 1. The mental model

A C-plus source file is still close to C. C headers, declarations, expressions, control flow, preprocessor directives, ABI types, function pointers, variadic functions, and linker flags remain available. C-plus adds structure around C rather than replacing C.

The normal pipeline is:

```text
C-plus source -> frontend/parser -> comptime expansion -> C emission -> C compiler/linker -> executable
```

The default frontend is the AST-oriented Tree-sitter frontend with compatibility fallback. `--frontend=tree-sitter` requires strict AST processing; `--frontend=legacy` selects the compatibility frontend; `--frontend=auto` permits the normal fallback policy. `--backend` selects the parser backend for parse-oriented tooling.

The generated C is not a second application language. It is the implementation artifact used to verify that the C-plus source has been lowered correctly. A C-plus program can therefore use an existing C library directly with `#include`, declarations, linker flags, and normal C calling conventions.

## 2. Command line workflow

Global options must precede the command. Compiler and linker flags are also global. `parse` and `lsp` retain their own options after the command.

```text
cpc help
cpc guide                         print this guide
cpc guide path                    print the installed guide path
cpc version
cpc transcode source.cp -o source.c
cpc compile source.cp -o source
cpc run source.cp
cpc test source.cp
cpc parse source.cp -o source.ast.json
cpc lsp
cpc graph source.cp -o imports.json
```

Useful global options are `--stdlib directory`, `--frontend auto|legacy|tree-sitter`, `--backend legacy|tree-sitter`, `--target triple`, `--sysroot path`, ordinary C compiler flags such as `-I`, `-L`, `-D`, and `-l`, and `-v0`, `-v1`, or `-v2`.

`-v0` suppresses C-plus diagnostics but does not suppress the program's own stdout or stderr. `-v1` shows errors and is the default. `-v2` also shows compiler selection, compilation passes, and test output.

The compiler is selected from the bundled runtime, `TCC`, a `tcc` found on `PATH`, and finally `CC`, according to the active distribution and host. The compiler must still provide the target ABI, headers, startup objects, and libraries. C-plus does not turn a foreign sysroot into a complete host runtime.

## 3. C-plus declarations and methods

Struct methods are declared inside the struct body. A method receives an explicit receiver, normally written as `borrowed` or `borrowed mut`.

```c
typedef struct counter_t {
    int value;

    pub void reset(borrowed mut *self) {
        if (self != NULL) self->value = 0;
    }

    pub int add(borrowed mut *self, int amount) {
        if (self == NULL) return 0;
        self->value += amount;
        return self->value;
    }
} counter_t;

counter_t counter = { 10 };
counter.add(&counter, 5);
```

Methods lower to ordinary C functions. The receiver is not implicit at the ABI boundary. `static pub` methods have no receiver and are called through the type name in C-plus source.

```c
typedef struct ids_t {
    static pub int first(void) { return 1; }
} ids_t;

int value = ids_t.first();
```

The access markers `pub` and `priv`, mutation marker `mut`, storage markers `owned`, `borrowed`, and region markers `scratch`, `hot`, `warm`, and `cold` are semantic guidance and lowering annotations. They do not make C pointers safe by themselves. The developer remains responsible for lifetime, aliasing, and external invalidation.

## 4. Ownership and error conventions

`owned` communicates that a value or pointer owns the resource. `borrowed` communicates that the callee must not release it. `borrowed mut` communicates that the callee may mutate the referenced object without acquiring ownership. Copying a struct does not automatically close or free its pointer members; calling a destructor or close operation twice is still an error.

Important operations should validate null, initialized, open, and range state because external code can close or invalidate a resource independently of the local copy.

The usual C-plus error convention is an integer `error_t`, with zero meaning success:

```c
typedef int error_t;

pub error_t open_config(borrowed const char* path, borrowed mut FILE** out_file) {
    if (path == NULL || out_file == NULL) return 1;
    *out_file = fopen(path, "rb");
    return *out_file == NULL ? 2 : 0;
}
```

The language also supports the proposed error annotations and `@try`/`@catch` lowering when the active frontend implements them. Keep explicit error handling valid C-compatible code when writing library interfaces so callers can opt out of the convenience syntax.

## 5. Comptime

`comptime` is executed during transcoding. It emits or selects source text and compiler metadata; it is not a runtime thread and does not perform runtime I/O.

### Comptime blocks and platform selection

```c
comptime {
    @if (os == "linux") {
        comptime flags -lraylib -lGL -lm -lpthread;
    } @else if (os == "windows") {
        comptime flags -lraylib -lopengl32 -lgdi32 -lwinmm;
    } @else if (os == "macos") {
        comptime flags -lraylib -framework Foundation -framework AppKit;
    }
}
```

`os` is the normalized selected target, not a runtime probe. Use `@if`, `@else if`, and `@else` to select source branches. A selected branch is materialized; discarded branches do not contribute declarations or flags.

### Comptime flags

`comptime flags` adds compiler/linker arguments to the current translation unit. The transcoder collects flags from all imported and source files, removes duplicate values while preserving first-use order, and places the consolidated list into the compile metadata. It is valid for a file to declare its own library requirements.

```c
comptime flags -Iinclude -Lbuild -lwidgets;
```

Use platform branches for platform-specific flags. Do not hide required libraries in an IDE-only configuration.

### Imports

```c
comptime import "stdlib:/containers/dynamic_list.cp";
comptime import "module:/shared/types.cp";
```

`stdlib:/` resolves against the selected standard-library root. `module:/` resolves through the project source/module and dependency roots. Imports are compile-time textual/module inputs and are processed before generated C is compiled.

### Comptime declarations and generators

Comptime can declare values and generate declarations. The standard library uses generic generators such as:

```c
comptime typedef dynamic_list(int) int_list_t;
comptime typedef dynamic_map(string, int) score_map_t;
```

The `@type` form defines a type generator:

```c
@type @box(@type T) {
    return struct { T value; };
}
```

Legacy generator spellings remain for compatibility. New code should keep generated declarations deterministic and should not depend on textual matching of unrelated identifiers.

### Comptime reflection and loops

Comptime reflection can inspect generated declarations and fields. Use it to generate repetitive C declarations, not to introduce hidden runtime behavior. Generated names must be stable and unique within the translation unit.

### Built-in directives

`@test("name") { ... }` declares a test fixture. `@assert(expression)` and `@assertEquals(expected, actual)` record assertions for the C-plus test harness. `@import` can include unchanged C fixtures where the test or example needs a C implementation.

`hot`, `warm`, `cold`, and `scratch` are intentionally lightweight directives. They currently lower to compatible macro annotations or storage-policy markers; they are not a scheduler, garbage collector, or cache guarantee.

## 6. Tests and fixtures

Tests are source-level fixtures, not comments or external scripts:

```c
@test("counter adds") {
    counter_t counter = { 2 };
    @assertEquals(7, counter.add(&counter, 5));
}
```

Run one or more files with `cpc test file.cp`. Test output belongs to the program/test stream and is independent of CLI verbosity. The runner reports fixtures, assertions, files, per-file results, and aggregate totals. A compile failure is distinct from a failed assertion.

Raylib examples may test model logic without opening a window. Keep rendering and event dequeueing at the application boundary so fixtures can exercise state transitions deterministically.

## 7. Standard-library modules

All standard modules are imported with `comptime import "stdlib:/..."`. They are C-plus facades over libc, the host socket API, or bundled third-party headers. They are intentionally small, explicit, and value/pointer based.

### Memory: `memory/xmem.cp`

Provides region-labelled allocation helpers and platform memory primitives. The regions are `scratch`, `hot`, `warm`, and `cold`. Use the matching allocator/free operation and preserve the ownership contract. The region labels do not change the C pointer ABI.

### Strings: `strings/string.cp`

`str_t` is a borrowed NUL-terminated C-string view. Its interface includes construction from `const char*`, `c_str`, length, comparison, prefix/suffix/search helpers, and formatted/string operations backed by libc. It does not own the pointed-to bytes.

### Memory streams: `io/memory_stream.cp`

`memory_stream_t` is an owned, growable binary byte stream. It stores byte data, length, capacity, cursor position, and open state. Its interface covers `init`, reserve/growth, read, write, seek, tell, close, and access to the written byte range. Data is bytes, not text; use an encoding layer for strings/runes.

### File and stdio: `io/file.cp`

`stdio_t` is the compatibility facade for direct C stdio calls such as `fopen`, `freopen`, `tmpfile`, `fclose`, `fflush`, `printf`, `scanf`, `fread`, and `fwrite`. `file_t` contains file-oriented operations and `var_io_t` contains variable/stdin/stdout/stderr formatting helpers. Standard handles are borrowed streams; closing a borrowed standard handle is the caller's responsibility and usually inappropriate.

When the stream abstraction is used, distinguish owned streams opened by the program from borrowed streams wrapping stdin, stdout, stderr, or externally managed handles. Check `is_open`/validity before important operations.

### Encodings: `encodings/rune.cp`, `utf8.cp`, `uchar.cp`, `wchar.cp`, `wctype.cp`

`rune_t` stores one Unicode scalar value. `rune_is_scalar` rejects surrogate code points and values above U+10FFFF; `rune_is_ascii` recognizes ASCII.

`utf8_t.next` decodes one rune from a length-delimited byte buffer and advances an offset. The UTF-8 interface returns explicit statuses for end, incomplete input, invalid sequences, invalid arguments, and I/O errors. This is the stable cross-platform text entry point.

`wstr_t` wraps platform wide strings and exposes length, comparison, search, and wide I/O helpers. `wide_ctype_t` exposes wide character classification and case conversion. `uchar.cp` provides the C11 `char16_t`/`char32_t` conversion facade, including compatibility implementations where the platform header is incomplete. These APIs depend on the host locale/runtime; UTF-8 remains the portable baseline.

### Containers: `containers/dynamic_list.cp`, `dynamic_map.cp`

`dynamic_list(T)` generates a resizable contiguous value container with `init`, `reserve`, `push`, indexed access, removal, clear, and release operations. It copies values and does not recursively own pointer members.

`dynamic_map(K, V)` generates contiguous key/value storage. The caller supplies key equality. `put`, lookup, reserve, remove, clear, and release operate on copied key/value values. Resource ownership of pointer-valued keys and values remains with the caller.

### Concurrency: `concurrency/thread_pool.cp`

The virtual thread pool owns a bounded set of system workers and visits registered tasks for a time slice. A task is a resumable state machine, not a blocking function. Each step returns `THREAD_STEP_YIELD`, `THREAD_STEP_DONE`, or `THREAD_STEP_FAILED` and stores its progress in task state before yielding. The pool removes completed/failed tasks and revisits yielded tasks fairly.

### Networking: `net/socket.cp`

`net_socket_t` wraps the platform socket API with initialization, address resolution, nonblocking mode, bind/listen, accept, connect, read, write, wait, close, error, and monotonic time operations. Windows uses Winsock and links `ws2_32`; Unix uses POSIX sockets. Callers must handle partial reads/writes, timeout, disconnect, and platform error results.

### HTTP: `http/protocol.cp`, `http/client.cp`, `http/server.cp`

The protocol module incrementally recognizes the minimal HTTP/1.1 request/header framing needed by the client and server. The client accepts a raw request string, connects nonblockingly, writes in chunks, waits with a configured deadline, and accumulates the response while reporting DNS, connection, timeout, I/O, framing, and size errors.

The server consists of a nonblocking head, request/response records, and a router. Each connection record contains a unique ID, request bytes, request-finished state, response bytes, response-ready state, response-sent state, and connection handle. The head accepts and services small chunks per activation, yields on every loop, forwards completed requests to the router, and writes ready responses incrementally. Routes and handlers must memoize state and yield rather than block the pool.

### UI: `ui/vdom.cp`, `layout.cp`, `renderer.cp`, `driver.cp`, `react.cp`, `printf_renderer.cp`

The UI stack is layered:

1. `vdom.cp` defines events, virtual nodes, components, contexts, and reconciliation-oriented tree state.
2. `react.cp` defines state cells, dirty tracking, component instances, rendering/update scheduling, and event dispatch.
3. `layout.cp` turns virtual nodes into drawable boxes/images and computes rectangles.
4. `renderer.cp` defines the renderer callback interface: begin frame, draw box, draw image, and end frame.
5. `driver.cp` supplies polling, clock, wait, viewport, and renderer callbacks.
6. `printf_renderer.cp` is a textual diagnostic renderer useful in tests and headless environments.

The renderer and driver are dependency-injected interfaces. A backend owns its native window/input resources; the UI core owns only the state explicitly assigned to it.

### Graphics: `graphics/raylib/*.cp`

`graphics/raylib.cp` and its shallow modules expose Raylib's native C API. The modules are grouped by header/domain: `core`, `low_level`, `input`, `draw`, `shapes`, `textures`, `text`, `models`, `resources`, `camera`, `audio`, `gestures`, and `math`. They provide declarations and constants rather than a second object model. Applications may call Raylib directly and declare platform-specific `comptime flags`.

Raylib applications normally own the render loop, dequeue input events once per iteration, update a model, render, and sleep or use the library's frame pacing. Tests should prefer pure model assertions and headless-safe math/rendering paths.

### Comptime helpers: `comptime/list_aggregate.cp`, `list_mapper.cp`

These modules provide compile-time list aggregation and mapping patterns used by generated declarations. They are source-generation utilities, not runtime collections.

## 8. Project layout and modules

`cpc new directory` and `cpc init directory` create a complete project with `cplus.toml`, `src/main.cp`, `modules`, `tests`, `README.md`, and this guide in the project root. `cpc pkg init` intentionally creates only `cplus.toml`.

The manifest declares `name`, `version`, `source`, `stdlib`, `module-paths`, and dependencies. `src` and `modules` are default module roots. A package dependency must contain one `cplus.toml`; package installation stages and validates all packages, resolves transitive dependencies, detects cycles, flattens them into `modules`, and records sorted entries in `cplus.lock`.

Use:

```text
cpc pkg init [folder]
cpc pkg add name=../local-module
cpc pkg add name=https://host/module.zip
cpc pkg install
```

Import a module by its project/module path:

```c
comptime import "module:/shared/types.cp";
```

## 9. Toolchains and sysroots

`cpc toolchain list remote` reads the published triple catalog. `cpc toolchain install <triple>` downloads the development and/or runtime bundle, validates its manifest and SHA-256 archive checksum, stages extraction, and records the release, digest, target, and kind in `cplus.lock`. `cpc toolchain update [triple|all]` refreshes selected bundles.

Installed bundles are user-local. Linux uses `~/.local/share/cplus/toolchains`, macOS uses `~/Library/Application Support/cplus/toolchains`, and Windows uses `%LOCALAPPDATA%/cplus/toolchains`. `CPLUS_TOOLCHAINS` or `-Dcplus.toolchains=...` overrides the root.

The compiler discovers `<canonical-triple>-dev` for compilation when no explicit `--sysroot` is supplied. An explicit sysroot always wins. Development bundles contain headers and link libraries; runtime bundles are for runtime deployment. A sysroot is not automatically a complete desktop runtime for OpenGL, X11, audio, or window-system dependencies.

## 10. Diagnostics and agent rules

Warnings and recoverable errors should be emitted with source locations while allowing code generation to continue. Unknown AST fragments, missing compiler mappings, and unsupported tooling features are warnings unless they make deterministic generation impossible. A transcoder crash, corrupted input, or other implicitly fatal condition stops generation.

When modifying C-plus code, preserve methods inside their structs, preserve explicit receiver and ownership annotations, use `comptime` for generation and flags, and prefer standard-library interfaces over handwritten C when an existing component fits. When adding a component, add a focused fixture test, document the interface and ownership/error behavior, and verify the generated C with the available compiler.

The generated C is the final proof of life for an existing feature, but it is not an authority for undefined edge cases. New syntax must be specified first, represented in the AST, lowered deterministically, and tested across the standard library, examples, and negative diagnostics.
