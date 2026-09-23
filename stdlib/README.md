# C-plus Standard Library

This directory starts the source-level standard library. It is imported from `.cp` files with `comptime import`; generic generators remain comptime declarations, while each instantiation materializes ordinary C-plus structs and functions.

## Memory and Allocation Intent

`memory/xmem.cp` provides OS-mapped `scratch`, `hot`, `warm`, and `cold` arenas. It defines those words as empty C macros, so declarations such as `warm char* text` remain ordinary C pointers and keep the same ABI. Allocation APIs—not the annotation—select the arena.

```c
comptime import "stdlib/memory/xmem.cp";

int main(void) {
    if (xmem_init() != 0) return 1;

    scratch char* message = alloc_scratch(128);
    warm char* text = alloc_warm(64);
    hot void* state = alloc_hot_aligned(128, XMEM_CACHE_LINE_SIZE);
    cold void* snapshot = alloc_cold(2 * 1024 * 1024); // direct mapping at/above threshold
    if (message == NULL || text == NULL || state == NULL || snapshot == NULL) {
        xmem_destroy();
        return 2;
    }

    warm char* larger_text = realloc_warm(text, 256);
    if (larger_text == NULL) {
        free_warm(text);
        free_hot(state);
        free_cold(snapshot);
        xmem_destroy();
        return 3;
    }
    text = larger_text; // a failed realloc leaves the original pointer valid
    free_warm(text);
    free_hot(state);
    free_cold(snapshot);
    reset_scratch(); // invalidates every scratch pointer; keeps regions for reuse
    xmem_destroy();  // releases all arena mappings
    return 0;
}
```

The first allocation lazily initializes the global context; call `xmem_init()` explicitly for clear lifecycle boundaries. `alloc_*` with size zero returns `NULL`; `calloc_*` checks multiplication overflow and zeroes successful allocations. `realloc_*(NULL, n)` allocates, while `realloc_*(p, 0)` frees and returns `NULL`. Hot allocations of at least 32 bytes use the configured cache-line alignment; smaller allocations use fundamental alignment. Cold allocations at least `XMEM_COLD_DIRECT_MAP_THRESHOLD` receive an individual mapping. Use the matching `free_hot`, `free_warm`, or `free_cold`; scratch is released only with `reset_scratch()`.

`xmem_get_stats(XMEM_CLASS_WARM, &stats)` reports reserved bytes, live requested bytes, peak live bytes, allocation/free counts, and mapped-region count. Define `XMEM_DEBUG` to diagnose invalid/double/mismatched frees and live bytes at arena destruction. This first implementation is single-threaded; do not share an arena concurrently. POSIX systems use `mmap`/`munmap`; Windows uses `VirtualAlloc`/`VirtualFree`. Runtime tests currently exercise the Linux x86-64 bundle.

### Allocation-intent analysis

The compiler keeps each declaration's `NONE`, `SCRATCH`, `HOT`, `WARM`, or `COLD` intent, ownership tag, source span, and any known pointer provenance in `TranscodedSource.allocationAnalysis`. `transcode`, `compile`, `run`, and `test` print advisory warnings at the original `.cp` location and continue compiling. The initial analysis catches direct allocator mismatches, simple pointer aliases/assignments, annotated function return mismatches, and known-intent function arguments (including mismatched `free_*` calls).

```c
int main(void) {
    hot char* value = alloc_cold(1024); // warning: declared hot, receives cold storage
    char* alias = value;                // provenance follows this simple assignment
    warm char* retained = alias;        // warning: cold storage assigned to warm intent
    return 0;
}
```

Function contracts carry the intent across API boundaries:

```c
pub owned warm char* make_name(void) {
    return alloc_warm(64);
}

pub int create_name(owned warm char** out) {
    *out = alloc_warm(64);
    return 0;
}
```

Warnings do not change the C ABI or turn intent tags into enforcement. The current frontend has no full C AST: analysis intentionally covers straightforward declarations, direct calls, and simple assignments only. It does not perform control-flow joins, escape/lifetime checking, macro expansion analysis, or general pointer/alias reasoning. VS Code and Vim can display compiler-backed diagnostics when configured to run the CLI; IntelliJ currently provides token highlighting and completion, not live allocation analysis.

Run the executable example and allocator tests with the Linux x86-64 distribution jar:

```sh
java -jar dist/c-plus.linux-x86_64.jar run stdlib/examples/allocators.cp
java -jar dist/c-plus.linux-x86_64.jar test stdlib/tests/allocators.cp
java -jar dist/c-plus.linux-x86_64.jar test stdlib/tests/string.cp
```

The tests cover alignment, scratch region growth/reset, warm calloc/realloc/free and coalescing, pointer stability across regions, hot alignment, cold direct maps, statistics, and destroy/reinitialization. To verify debug mismatch, boundary-guard, and double-free reporting, run the intentional misuse fixture with `java -jar dist/c-plus.linux-x86_64.jar run stdlib/examples/allocator_debug.cp -DXMEM_DEBUG`; it should report all three cases and still exit successfully.

## Containers

- `containers/dynamic_list.cp` provides `@dynamic_list(T)`: a resizable contiguous list with init, reserve, push, each, pop, get, set, clear, size, capacity, empty, data, and destroy operations.
- `containers/dynamic_map.cp` provides `@dynamic_map(K, V)`: a resizable key/value table with caller-supplied key equality. The initial implementation uses linear lookup, so lookup and removal are O(n).

Both containers use the warm allocator for their backing arrays and copy elements by value. They do not deep-copy, own, or free memory reachable through pointer elements. Call `destroy` once for each initialized container. A failed `push` or `put` leaves existing elements intact.

Instantiate a container from a source file with a comptime import and type invocation:

```c
comptime import "../stdlib/containers/dynamic_list.cp";
comptime typedef dynamic_list(int) int_list_t;
```

The import path is relative to the importing `.cp` file. The typedef gives generated methods a stable receiver type and names such as `int_list__push`.

## Strings

`strings/string.cp` uses the warm arena for its resizable character buffer and for temporary copies needed by alias-safe transformations. Its existing `init`, mutation, and `destroy` calls remain the public lifecycle; the allocator lazily initializes on the first string allocation. Destroy every initialized string before `xmem_destroy()` when keeping explicit application shutdown accounting.

## Comptime Mapper

`comptime/list_mapper.cp` provides `list_mapper(T, R, InputList, OutputList)`, which materializes a typed function applying an `R (*callback)(T*, size_t)` to each input element and appending the results to a distinct, initialized dynamic list of `R`. It can return an error if output growth fails; already appended items remain in the output.

Generated function names encode their types, for example `list_map__int__to__float`. If a shorter hand-written name is preferred, use an ordinary C preprocessor alias before the comptime invocation:

```c
#define map_int_float list_map__int__to__float
```

## Example and Tests

`examples/containers.cp` demonstrates list, map, and mapper instantiations. Run it with `./gradlew run --args='run examples/containers.cp'`. The annotated test harness is under `tests/`; run it with `./gradlew run --args='test stdlib/tests/containers.cp'`.
