# C-plus Standard Library

This guide consolidates the APIs and behavior implemented in `stdlib/`. Library code is ordinary C-plus, imported during comptime expansion. Prefer stable `stdlib:/` imports over paths relative to an example or project:

```c
comptime import "stdlib:/memory/xmem.cp";
comptime import "stdlib:/containers/dynamic_list.cp";
comptime typedef dynamic_list(int) int_list_t;
```

The CLI resolves the `stdlib:/` root from the bundled or installed library, `--stdlib`, `CPLUS_STDLIB`, or the project configuration. See the [project/module specification](../documentation/spec/SPEC.project.md) for search order and limitations.

## Modules

| Source | Provides |
| --- | --- |
| [`memory/xmem.cp`](memory/xmem.cp) | OS-backed scratch, hot, warm, and cold arenas |
| [`io/file.cp`](io/file.cp) | Namespaced facade over the C `<stdio.h>` API |
| [`strings/string.cp`](strings/string.cp) | Owning mutable string and string/memory helpers |
| [`encodings/rune.cp`](encodings/rune.cp) | Fixed-width `rune_t` and Unicode scalar helpers |
| [`encodings/utf8.cp`](encodings/utf8.cp) | Locale-independent UTF-8 decode, count, and write operations |
| [`encodings/uchar.cp`](encodings/uchar.cp) | `char16_t` and restartable C runtime conversion bindings |
| [`encodings/wchar.cp`](encodings/wchar.cp) | Borrowed wide-string, wide-string-operation, and wide-I/O facades |
| [`encodings/wctype.cp`](encodings/wctype.cp) | Locale-aware wide-character classification and case mapping |
| [`graphics/raylib.cp`](graphics/raylib.cp) | Raylib 6.0 native headers, grouped by domain |
| [`containers/dynamic_list.cp`](containers/dynamic_list.cp) | Generic contiguous resizable list |
| [`containers/dynamic_map.cp`](containers/dynamic_map.cp) | Generic linear key/value table |
| [`comptime/list_mapper.cp`](comptime/list_mapper.cp) | Typed callback-based list conversion generator |

Runnable samples are in `examples/`; annotated suites are in `tests/`. The CLI test runner prints test and assertion results, including given/expected values.

## Memory and allocation intent

`xmem.cp` defines `scratch`, `hot`, `warm`, and `cold` as empty macros. The annotations describe intent, not an allocator or ABI change: use the corresponding API to allocate memory. The compiler can issue advisory allocation-intent warnings for direct calls, simple aliases, annotated returns, and known arguments; it does not enforce lifetimes or perform general pointer analysis.

| Class | Intended use | Lifetime / policy |
| --- | --- | --- |
| `scratch` | Temporary per-operation data | Bump allocated; `reset_scratch()` invalidates all outstanding pointers and retains regions for reuse. No individual free. |
| `hot` | Small, frequently accessed working-set data | Reusable blocks; allocations of at least 32 bytes use 64-byte alignment by default. “Hot” does not reserve CPU cache. |
| `warm` | General mutable application storage | Reusable blocks; used by the containers and `string`. |
| `cold` | Large or rarely accessed storage | Large allocations (1 MiB by default) receive individual OS mappings. |

The initial regions default to 1 MiB scratch, 4 MiB hot, 16 MiB warm, and 16 MiB cold; regions grow as needed up to the configured 64 MiB region size. These values and thresholds can be overridden with the `XMEM_*` macros before import.

Public API:

```c
int xmem_init(void);                 // idempotent; allocations also initialize lazily
void xmem_destroy(void);             // releases all mappings; may be followed by re-init
void reset_scratch(void);

void* alloc_scratch(size_t size);
void* alloc_scratch_aligned(size_t size, size_t alignment);
void* alloc_hot(size_t size);
void* alloc_hot_aligned(size_t size, size_t alignment);
void* alloc_warm(size_t size);
void* alloc_cold(size_t size);

void* calloc_hot(size_t count, size_t size);
void* calloc_warm(size_t count, size_t size);
void* calloc_cold(size_t count, size_t size);
void* realloc_hot(void* pointer, size_t size);
void* realloc_warm(void* pointer, size_t size);
void* realloc_cold(void* pointer, size_t size);
void free_hot(void* pointer);
void free_warm(void* pointer);
void free_cold(void* pointer);
int xmem_get_stats(xmem_class_t kind, xmem_stats_t* out);
```

Zero-size allocation returns `NULL`; calloc checks multiplication overflow and zeros successful storage. `realloc_*(NULL, n)` allocates, and `realloc_*(p, 0)` frees and returns `NULL`. A failed nonzero realloc leaves the original allocation valid. `xmem_stats_t` reports `reserved`, `used`, `peak_used`, allocation/free counts, and region count. Define `XMEM_DEBUG` to report invalid, duplicate, or mismatched frees, boundary-guard corruption, and live bytes on destruction. The global allocator is currently single-threaded. POSIX uses `mmap`/`munmap`; Windows uses `VirtualAlloc`/`VirtualFree`.

The allocator implementation and diagnostic model are detailed in the [allocator specification](../documentation/spec/stdlib/ALLOCATORS.SPEC.md). Run `cpc run stdlib/examples/allocators.cp` and `cpc test stdlib/tests/allocators.cp` on a supported distribution.

## Generic containers

Both generators produce C-plus struct types; instantiate them with a typedef so their methods have stable receiver names. They copy elements by value, do not deep-copy or free pointer members, and use the warm arena for backing storage. Call `destroy()` once after successful `init()`.

### `dynamic_list(T)`

```c
comptime import "stdlib:/containers/dynamic_list.cp";
comptime typedef dynamic_list(int) int_list_t;
```

Methods: `init`, `reserve`, `push`, `each`, `orderByNumeric`, `orderByAlphaumeric`, `pop`, `get`, `set`, `clear`, `size`, `capacity`, `empty`, `data`, and `destroy`. `push`/`set` copy a supplied item; `get` returns a borrowed pointer or `NULL` when out of range. `each` calls `int callback(mut T* item, size_t index)` in index order and stops on a nonzero callback result. The two order methods use in-place heapsort and take an item-to-`int` key callback or an item-to-`const char*` key callback respectively. `clear` retains capacity; `destroy` releases storage. Capacity grows geometrically from four elements. A failed growth leaves existing elements intact.

### `dynamic_map(K, V)`

```c
comptime import "stdlib:/containers/dynamic_map.cp";
comptime typedef dynamic_map(char*, int) score_map_t;
```

Methods: `init(keys_equal)`, `reserve`, `put`, `get`, `contains`, `remove`, `orderByNumeric`, `orderByAlphaumeric`, `clear`, `size`, `capacity`, `empty`, and `destroy`. Supply `int keys_equal(const K*, const K*)` for semantic key equality; passing `NULL` selects bytewise equality. Keys and values are copied by value. `get` returns a pointer to the stored value or `NULL`; `put` replaces an equal key's value. Lookup and removal are O(n); callback ordering is in-place heapsort. `clear` keeps allocated capacity; `destroy` also clears the equality callback.

### Callback aggregation and set operations

Import the list, map, and aggregate templates, then use `comptime/list_aggregate.cp` to generate operations with concrete C signatures. The result types that vary independently from the input element type are supplied explicitly:

```c
comptime import "stdlib:/containers/dynamic_list.cp";
comptime import "stdlib:/containers/dynamic_map.cp";
comptime import "stdlib:/comptime/list_aggregate.cp";

comptime typedef dynamic_list(int) int_list_t;
comptime typedef dynamic_map(int, int_list_t) int_groups_t;
comptime typedef dynamic_map(int, int) int_map_t;
comptime list_fold(int, int, int_list_t);
comptime list_group_by(int, int, int_list_t, int_list_t, int_groups_t);
comptime list_zip(int, int, int_list_t, int_list_t, int_map_t);
comptime list_union(int, int_list_t);
comptime list_intersect(int, int_list_t);
comptime list_subtract(int, int_list_t);
comptime list_subtract_right(int, int_list_t);
```

This emits `list_fold__int__with__int`, `list_group_by__int__by__int`, `list_zip__int__with__int`, and the four `list_*__int` set functions. `fold` calls `A callback(A accumulator, const T* item, size_t index)` in list order. `group_by` returns `dynamic_map(R, dynamic_list(T))`; destroy each nested list's items before destroying the outer map. `zip` pairs by index through the shorter list and later duplicate keys replace earlier values. The set operations return initialized list values on success (`initialized == 0` signals allocation/argument failure), deduplicate results, and compare each item's object bytes with `memcmp`; this is not deep equality and includes struct padding. `subtract` means left minus right; `subtract_right` means right minus left.

All collection storage uses the warm allocator. Sorting is in place and allocates no scratch memory; generated result operations reserve geometrically through shared warm-memory helpers. The receiver methods are therefore a good fit for frequently reused lists/maps, while the generated value-returning functions are convenient for one-off transformations.

## Generic list mapper

`list_mapper(T, R, InputList, OutputList)` emits a typed `list_map__T__to__R` function. The input must be initialized, and the distinct output list must also be initialized. It applies `R callback(T* item, size_t index)` to each item and appends each result; it does not clear existing output. Input and output cannot alias. On failure, already appended results remain in output.

```c
comptime import "stdlib:/comptime/list_mapper.cp";
comptime list_mapper(int, float, int_list_t, float_list_t);
```

Use a normal C `#define` if a shorter public name is useful, for example `#define map_int_float list_map__int__to__float`; this is a C preprocessor alias, not comptime alias syntax.

## Mutable `string`

`strings/string.cp` defines an owning, NUL-terminated `string` backed by warm memory, plus a borrowed `str_t` view for C string operations. `init()` starts `string` empty without allocating; `destroy()` frees its buffer. Successful operations preserve `length <= capacity` and `data[length] == '\0'` when data is allocated. Capacity counts characters but excludes the terminator. Failed allocation/range operations preserve the previous value, and mutating source arguments may alias the string's own buffer. A `str_t` created by `text.as_str()` borrows the current buffer and must not outlive it or a reallocation.

Errors: `STRING_OK`, `STRING_ERROR_INVALID_ARGUMENT`, `STRING_ERROR_ALLOCATION`, and `STRING_ERROR_RANGE`.

- Lifecycle/value API: `init`, `reserve`, `assign`, `assign_n`, `append`, `append_n`, `append_char`, `clear`, `c_str`, `size`, `capacity_of`, `empty`, `destroy`.
- Borrowed C-string view: `str_t view = text.as_str()`; receiver methods include `view.strlen()`, `view.strcmp(other)`, and `view.strstr(needle)`. `str_t.from(c_string)` creates a view over an existing C string.
- Raw libc helpers: `str_t.strcpy`, `strncpy`, `strcat`, `strncat`, and `strxfrm` take caller-owned destination buffers and retain libc capacity requirements. Memory functions, `strerror`, and `strtok` are also `str_t` static helpers; `strtok` operates on a caller-owned mutable buffer.
- Indentation: `indent(n)` adds spaces to each logical line; `dedent(n)` removes up to `n` leading spaces per line; `value.trim_indent()` removes the common indentation of nonblank lines. Negative counts are rejected; tabs are not indentation.

See the [string API specification](../documentation/spec/stdlib/STRING.SPEC.md) for detailed behavior and complexity. Exercise the implementation with `cpc test stdlib/tests/string.cp`.

### C character and encoding facades

The `encodings/` modules provide two distinct layers. `rune.cp` defines `rune_t` and Unicode-scalar helpers. `utf8.cp` provides locale-independent UTF-8 `utf8_t.next`, `count`, and `print`; it strictly rejects malformed, overlong, surrogate, and out-of-range sequences. `uchar.cp` wraps the locale-sensitive C UTF-16/UTF-32 conversion functions, while `wchar.cp` and `wctype.cp` expose wide strings/I/O and wide classification. Import only what you need. Keep an `encoding_state_t` per independent libc conversion and call `reset()` before starting one.

```c
comptime import "stdlib:/encodings/utf8.cp";

size_t offset = 0;
rune_t rune;
int status = utf8_t.next(utf8_bytes, byte_count, &offset, &rune);
size_t rune_count;
if (utf8_t.count(utf8_bytes, byte_count, &rune_count) == UTF8_OK)
    utf8_t.print(stdout, rune);
```

`next` advances the byte offset only on `UTF8_OK`; it returns `UTF8_END`, `UTF8_INCOMPLETE`, `UTF8_INVALID_SEQUENCE`, or `UTF8_INVALID_ARGUMENT` as appropriate. `count` writes its output only when the whole input is valid. `print` writes canonical UTF-8 bytes to a `FILE*`; terminal display still depends on the user's terminal encoding. See the [encoding specification](../documentation/spec/stdlib/ENCODINGS.SPEC.md) and run `cpc test stdlib/tests/utf8.cp stdlib/tests/encoding.cp`.

The C conversion facade, wide I/O, and classification still follow the target runtime and locale. `wchar_t` has platform-dependent width and is not interchangeable with `rune_t`. The owning `string` remains byte-oriented; UTF-8 iteration/counting exists as a separate codec and is not yet integrated into string slicing or mutation.

## Graphics, input, and audio with raylib

Raylib 6.0 is exposed as its native C API: these modules include Raylib headers and do not rename functions, wrap resources, or add automatic linking. Import the umbrella module or only the domain you use; you can also `#include <raylib.h>` directly:

```c
comptime import "stdlib:/graphics/raylib.cp";

int main(void) {
    InitWindow(960, 540, "C-plus with Raylib");
    defer CloseWindow();
    while (!WindowShouldClose()) {
        BeginDrawing();
        ClearBackground(RAYWHITE);
        DrawText("Hello from C-plus", 32, 32, 24, DARKBLUE);
        EndDrawing();
    }
}
```

Available domains are `core`, `input`, `gestures`, `camera`, `draw`, `shapes`, `textures`, `text`, `models`, `audio`, `resources`, `math` (`raymath.h`), and `low_level` (`rlgl.h`). Every module includes `<raylib.h>`; math and low-level rendering also include their companion headers. Use the native structs, enums, constants, and functions directly; resource lifetime follows Raylib's `Unload*` contracts. See the [Raylib standard-library specification](../documentation/spec/stdlib/RAYLIB.SPEC.md) for the module inventory and resource notes.

The standalone `rcamera.h` and `rgestures.h` are not staged consistently across targets, so their functions are exposed through `<raylib.h>`. Raylib headers and libraries are target-specific; C-plus selects the matching bundled header and archive when using an embedded payload without a custom `--sysroot`. Explicitly link Raylib and platform libraries. For Linux, for example:

```sh
cpc run stdlib/examples/raylib_hello.cp -dynamic -lraylib -lGL -lm -lpthread -ldl -lrt -lX11 -lXrandr -lXinerama -lXcursor -lXi
```

The headless test suite exercises `raymath.h` and links the example on Linux x86-64; it does not open a window or initialize audio. See [`examples/raylib_hello.cp`](examples/raylib_hello.cp) and run `cpc test stdlib/tests/raylib_math.cp`.

Four independent, playable game examples demonstrate queued keyboard input, clock-driven updates, Raylib rendering, and headless logic checks: [Arkanoid](examples/raylib/arkanoid.cp), [Space Invaders](examples/raylib/space_invaders.cp), [Tetris](examples/raylib/tetris.cp), and [2048](examples/raylib/game_2048.cp). Each file owns its entities and event model; no shared game framework is imposed. Controls and build commands are in the [Raylib games guide](examples/raylib/README.md), and the design sketches remain in [`games.md`](examples/raylib/games.md).

## Streams and I/O

`io/file.cp` provides receiver-based `FILE*` operations through `stream_t`, factories through `file_t`, and path operations through `path_t`. `io/memory_stream.cp` provides growable binary storage through `memory_stream_t`; both expose matching `read_bytes`, `write_bytes`, `seek`, `tell`, `flush`, and `close` operations for comptime-generated byte algorithms. `var_io_t.standard()` creates borrowed stdin/stdout/stderr wrappers, `format_t` handles buffer-based text formatting/parsing, and `stdio_t` remains the raw compatibility facade. Ownership is conventional rather than compiler-enforced; avoid copying owning stream values because closing multiple aliases can close/free the same resource more than once. See the [I/O specification](../documentation/spec/stdlib/IO.SPEC.md) and run `cpc test stdlib/tests/io.cp stdlib/tests/memory_stream.cp`.

## Examples and tests

[`examples/containers.cp`](examples/containers.cp) demonstrates a struct-valued list, a map, a generated list mapper, and `defer`-based normal-exit cleanup. [`examples/allocators.cp`](examples/allocators.cp) shows allocator lifetimes; [`examples/allocator_debug.cp`](examples/allocator_debug.cp) intentionally triggers debug diagnostics. The container suite also checks deferred statement/block ordering. Run every standard-library suite with:

```sh
cpc test stdlib/tests/*.cp
```

For a source checkout before installing `cpc`, use `./gradlew run --args='test stdlib/tests/containers.cp'` or the equivalent `run`/`test` command for another fixture.
