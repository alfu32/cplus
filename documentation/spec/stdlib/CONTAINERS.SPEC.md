# C-plus Collections Specification

Status: implemented in `stdlib/containers` and `stdlib/comptime/list_aggregate.cp`.

## Storage and ownership

`dynamic_list(T)` stores a contiguous warm-allocated array of values. `dynamic_map(K,V)` stores contiguous key/value entries, also in warm memory. Growth is geometric and uses `realloc_warm`; map removal compacts entries with `memmove`. Values and pointer members are copied by value, not deep-copied. `clear()` retains capacity; `destroy()` releases the collection's own backing array. Each initialized collection sets `initialized`; a zero-initialized result returned after allocation failure has it unset.

## Callback ordering

Lists provide:

```c
int orderByNumeric(int (*key)(const T* item));
int orderByAlphaumeric(const char* (*key)(const T* item));
```

Maps provide equivalent methods whose callbacks receive pointers to an entry's key and value:

```c
int orderByNumeric(int (*key)(const K* key, const V* value));
int orderByAlphaumeric(const char* (*key)(const K* key, const V* value));
```

Both are in-place O(n log n) heapsorts requiring no temporary allocation. The callback may be evaluated multiple times and should be deterministic. Alphanumeric ordering uses `strcmp`; a null key sorts before a non-null key. A callback that derives a key from owning `string` or borrowed `str_t` should return the corresponding NUL-terminated `const char*` (`string.c_str()` or `str_t.c_str()`). The view's backing storage must remain valid for the duration of the sort.

For example, key adapters return borrowed storage; they do not allocate a temporary string per comparison:

```c
const char* record_name(const named_value_t* item) { return item->name; }
const char* owned_string_key(const string* item) { return item->data ? item->data : ""; }
const char* view_key(const str_t* item) { return item->data ? item->data : ""; }

records.orderByAlphaumeric(record_name);
```

## Generated list operations

Operations whose return type introduces independent generic types are emitted as concrete functions by `comptime` generators rather than runtime-generic methods. Import `stdlib:/comptime/list_aggregate.cp`, declare the concrete list/map types, then invoke the needed generators:

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

The generated symbols are `list_fold__int__with__int`, `list_group_by__int__by__int`, `list_zip__int__with__int`, `list_union__int`, `list_intersect__int`, `list_subtract__int`, and `list_subtract_right__int`.

- `fold(input, initial, callback)` returns `A`; callback signature is `A callback(A accumulator, const T* item, size_t index)`.
- `group_by(input, callback)` returns `dynamic_map(R, dynamic_list(T))`, using bytewise key equality. Each value list is separately allocated. Free each nested list's `items` (or call its generated `destroy` C function) before destroying the outer map.
- `zip(left, right)` maps each right-side item at index `i` to the left-side item at `i`. It processes the shorter list; duplicate keys overwrite earlier values.
- `union` includes unique values from left then right; `intersect` includes unique left-side values also present on the right; `subtract` returns unique left-minus-right values; `subtract_right` returns unique right-minus-left values.

Set and default map-key equality compare `sizeof(T)`/`sizeof(K)` bytes with `memcmp`. This deliberately compares pointer values, does not compare pointed-to content, and includes structure padding. Supply a custom `keys_equal` callback to `dynamic_map.init` for semantic equality. Aggregation functions return initialized collections on success; allocation or invalid-input failure returns a zero-initialized value (`initialized == 0`). `fold` has no error channel: a null input or callback returns its initial value.

## Complexity

- List/map ordering: O(n log n), in-place.
- Fold: O(n).
- Grouping and zip: O(n²) worst case because the current map uses linear lookup.
- Set operations: O(n·m) membership checks (plus output deduplication); warm-backed output grows geometrically.
- Map lookup/contains/remove: O(n); removal shifts the trailing entries using `memmove`.
