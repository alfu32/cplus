# C-plus `string` and `str_t` Standard Library Specification

Status: implemented for the source-level standard library.

## 1. Purpose

`stdlib/strings/string.cp` provides an owning, mutable C-plus `string` and a non-owning `str_t` view. `string` owns its resizable NUL-terminated buffer; `str_t` presents the read-only, receiver-oriented `<string.h>` operations for any borrowed NUL-terminated C string. The split avoids pretending raw C destination-buffer functions can safely resize an owning `string`.

The object is intentionally not just a `char*`: operations such as indentation, concatenation, and assignment can resize the buffer, so the object tracks ownership, logical length, and capacity.

```c
typedef struct string {
    char* data;
    size_t length;
    size_t capacity;
    ... methods ...
} string;
```

Invariants after successful initialization and after every successful mutating method:

- `length <= capacity`.
- if `data != NULL`, `data[length] == '\0'`.
- `data == NULL` represents the empty string and `c_str()` returns `""` for it.
- the `string` owns `data`; `destroy()` releases it.
- failed allocation/range operations preserve the previous valid value.
- mutating copy/append/transform operations support sources that alias the same string buffer, including internal substrings.

## 2. Errors

Until the common standard-library error module is introduced, this module defines `error_t` once behind `CPLUS_ERROR_T_DEFINED` and uses:

```c
STRING_OK                     0
STRING_ERROR_INVALID_ARGUMENT 1
STRING_ERROR_ALLOCATION       2
STRING_ERROR_RANGE            3
```

## 3. Lifecycle and object helpers

```c
pub error_t init(string* self)
pub error_t reserve(string* self, size_t requested_capacity)
pub error_t assign(string* self, const char* source)
pub error_t assign_n(string* self, const char* source, size_t count)
pub error_t append(string* self, const char* suffix)
pub error_t append_n(string* self, const char* suffix, size_t count)
pub error_t append_char(string* self, char value)
pub void clear(string* self)
pub const char* c_str(string* self)
pub size_t size(string* self)
pub size_t capacity_of(string* self)
pub int empty(string* self)
pub void destroy(string* self)
```

`reserve(n)` reserves space for `n` non-NUL characters. The implementation allocates `n + 1` bytes so the terminator is never counted in `capacity`.

## 4. Borrowed `str_t` view and `<string.h>`

Create a view from an existing C string or an owning `string`:

```c
str_t view = str_t.from(c_string);
str_t owned_view = text.as_str();
view.strlen();
view.strcmp("expected");
view.strstr("needle");
```

`str_t.data` is borrowed: the backing bytes must remain alive and NUL-terminated while the view is used. A view from `string.as_str()` is invalidated when the owner is destroyed or an operation reallocates its buffer. Read-only receiver methods (`strlen`, `strcmp`, `strncmp`, `strcoll`, `strchr`, `strrchr`, `strstr`, `strspn`, `strcspn`, and `strpbrk`) delegate to the corresponding C library function.

Operations with a separate raw destination remain static C-style helpers, since neither libc nor these methods know the destination capacity:

```c
str_t.strcpy(destination, source);
str_t.strncpy(destination, source, count);
str_t.strcat(destination, source);
str_t.strncat(destination, source, count);
str_t.strxfrm(destination, source, count);
```

The caller must provide a writable destination large enough for the operation (including the terminating NUL where applicable). These intentionally preserve libc's raw-buffer semantics; use `string.assign`, `assign_n`, `append`, or `append_n` for capacity-safe owned-string mutation. Raw memory functions and the remaining libc helpers are also static utilities:

```c
str_t.memchr(...); str_t.memcmp(...); str_t.memcpy(...);
str_t.memmove(...); str_t.memset(...);
str_t.strerror(error_number);
str_t.strtok(buffer_or_null, delimiters);
```

`strtok` operates on a caller-owned raw buffer, not on a `string`, because inserting internal NUL bytes into the owned object would invalidate its `length` invariant.

## 5. Indentation extensions

### `indent`

```c
pub error_t indent(string* self, int number_of_spaces)
```

Adds exactly `number_of_spaces` ASCII spaces at the beginning of every logical line. The first line is included. A trailing newline does not create an extra indented empty line. Empty strings and an indentation of zero are unchanged. Negative values are invalid.

### `dedent`

```c
pub error_t dedent(string* self, int number_of_spaces)
```

Removes up to `number_of_spaces` ASCII spaces from the beginning of every logical line. A line with fewer leading spaces loses only the spaces it has. Tabs are not treated as spaces. Empty strings and zero are unchanged. Negative values are invalid.

### `trim_indent`

```c
pub error_t trim_indent(string* self)
```

Finds the minimum count of leading ASCII spaces across all nonblank lines, then removes that count from every line. Blank lines do not influence the minimum. Tabs are not expanded and therefore are not counted as indentation.

This is an instance method (`text.trim_indent()`). It mutates the receiver and does not remove leading/trailing blank lines; it only normalizes common space indentation.

## 6. Complexity

- comparisons/search/span: inherited from the C library, normally O(n) or O(n+m).
- assignment/append: O(n), plus `realloc` when growth is needed.
- `indent`: O(n + added spaces), performed in place after one reserve.
- `dedent`: O(n), compacted in place without allocation.
- `trim_indent`: two O(n) passes and no allocation.

## 7. Usage

```c
comptime import "../stdlib/strings/string.cp";

string text;
text.init();
text.assign("alpha\nbeta");
text.indent(4);
puts(text.c_str());
str_t view = text.as_str();
printf("length=%zu\n", view.strlen());
text.destroy();
```
