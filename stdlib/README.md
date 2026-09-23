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
| [`strings/string.cp`](strings/string.cp) | Owning mutable string and string/memory helpers |
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

Methods: `init`, `reserve`, `push`, `each`, `pop`, `get`, `set`, `clear`, `size`, `capacity`, `empty`, `data`, and `destroy`. `push`/`set` copy a supplied item; `get` returns a borrowed pointer or `NULL` when out of range. `each` calls `int callback(mut T* item, size_t index)` in index order and stops on a nonzero callback result. `clear` retains capacity; `destroy` releases storage. Capacity grows geometrically from four elements. A failed growth leaves existing elements intact.

### `dynamic_map(K, V)`

```c
comptime import "stdlib:/containers/dynamic_map.cp";
comptime typedef dynamic_map(char*, int) score_map_t;
```

Methods: `init(keys_equal)`, `reserve`, `put`, `get`, `contains`, `remove`, `clear`, `size`, `capacity`, `empty`, and `destroy`. Supply `int keys_equal(const K*, const K*)`; keys and values are copied by value. `get` returns a pointer to the stored value or `NULL`; `put` replaces an equal key's value. Lookup and removal are O(n) because this initial implementation uses linear search. `clear` keeps allocated capacity; `destroy` also clears the equality callback.

## Generic list mapper

`list_mapper(T, R, InputList, OutputList)` emits a typed `list_map__T__to__R` function. The input must be initialized, and the distinct output list must also be initialized. It applies `R callback(T* item, size_t index)` to each item and appends each result; it does not clear existing output. Input and output cannot alias. On failure, already appended results remain in output.

```c
comptime import "stdlib:/comptime/list_mapper.cp";
comptime list_mapper(int, float, int_list_t, float_list_t);
```

Use a normal C `#define` if a shorter public name is useful, for example `#define map_int_float list_map__int__to__float`; this is a C preprocessor alias, not comptime alias syntax.

## Mutable `string`

`strings/string.cp` defines an owning, NUL-terminated `string` backed by warm memory. `init()` starts it empty without allocating; `destroy()` frees its buffer. Successful operations preserve `length <= capacity` and `data[length] == '\0'` when data is allocated. Capacity counts characters but excludes the terminator. Failed allocation/range operations preserve the previous value, and mutating source arguments may alias the string's own buffer.

Errors: `STRING_OK`, `STRING_ERROR_INVALID_ARGUMENT`, `STRING_ERROR_ALLOCATION`, and `STRING_ERROR_RANGE`.

- Lifecycle/value API: `init`, `reserve`, `assign`, `assign_n`, `append`, `append_n`, `append_char`, `clear`, `c_str`, `size`, `capacity_of`, `empty`, `destroy`.
- String facade: `strlen`, `strcpy`, `strncpy`, `strcat`, `strncat`, `strcmp`, `strncmp`, `strcoll`, `strchr`, `strrchr`, `strstr`, `strspn`, `strcspn`, `strpbrk`, `strxfrm`.
- Static C helpers: `string.memchr`, `memcmp`, `memcpy`, `memmove`, `memset`, `strerror`, and `strtok`. `strtok` works on a caller-owned raw buffer, not the `string`'s internal storage.
- Indentation: `indent(n)` adds spaces to each logical line; `dedent(n)` removes up to `n` leading spaces per line; `string.trim_indent(&value)` removes the common indentation of nonblank lines. Negative counts are rejected; tabs are not indentation.

See the [string API specification](../documentation/spec/stdlib/STRING.SPEC.md) for detailed behavior and complexity. Exercise the implementation with `cpc test stdlib/tests/string.cp`.

## Examples and tests

[`examples/containers.cp`](examples/containers.cp) demonstrates a struct-valued list, a map, and a generated list mapper. [`examples/allocators.cp`](examples/allocators.cp) shows allocator lifetimes; [`examples/allocator_debug.cp`](examples/allocator_debug.cp) intentionally triggers debug diagnostics. Run every standard-library suite with:

```sh
cpc test stdlib/tests/*.cp
```

For a source checkout before installing `cpc`, use `./gradlew run --args='test stdlib/tests/containers.cp'` or the equivalent `run`/`test` command for another fixture.
