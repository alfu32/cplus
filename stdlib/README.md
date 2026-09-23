# C-plus Standard Library

This directory starts the source-level standard library. It is imported from `.cp` files with `comptime import`; generic generators remain comptime declarations, while each instantiation materializes ordinary C-plus structs and functions.

## Containers

- `containers/dynamic_list.cp` provides `@dynamic_list(T)`: a resizable contiguous list with init, reserve, push, each, pop, get, set, clear, size, capacity, empty, data, and destroy operations.
- `containers/dynamic_map.cp` provides `@dynamic_map(K, V)`: a resizable key/value table with caller-supplied key equality. The initial implementation uses linear lookup, so lookup and removal are O(n).

Both containers copy elements by value. They do not deep-copy, own, or free memory reachable through pointer elements. Call `destroy` once for each initialized container. A failed `push` or `put` leaves existing elements intact.

Instantiate a container from a source file with a comptime import and type invocation:

```c
comptime import "../stdlib/containers/dynamic_list.cp";
comptime typedef dynamic_list(int) int_list_t;
```

The import path is relative to the importing `.cp` file. The typedef gives generated methods a stable receiver type and names such as `int_list__push`.

## Comptime Mapper

`comptime/list_mapper.cp` provides `list_mapper(T, R, InputList, OutputList)`, which materializes a typed function applying an `R (*callback)(T*, size_t)` to each input element and appending the results to a distinct, initialized dynamic list of `R`. It can return an error if output growth fails; already appended items remain in the output.

Generated function names encode their types, for example `list_map__int__to__float`. If a shorter hand-written name is preferred, use an ordinary C preprocessor alias before the comptime invocation:

```c
#define map_int_float list_map__int__to__float
```

## Example and Tests

`examples/containers.cp` demonstrates list, map, and mapper instantiations. Run it with `./gradlew run --args='run examples/containers.cp'`. The annotated test harness is under `tests/`; run it with `./gradlew run --args='test stdlib/tests/containers.cp'`.
