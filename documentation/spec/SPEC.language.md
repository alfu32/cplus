# C-plus Language Specification

Status: living specification. Update this document whenever the language or generated C changes.

## Overview

C-plus is C with a small object-oriented surface syntax. Files use `.cp` or `.c+` and otherwise contain ordinary C declarations, expressions, statements, and preprocessor directives. The compiler lowers C-plus to C; it does not impose a runtime or ownership checker.

## Annotations

The following annotations are optional, empty C macros retained in generated code:

| Annotation | Meaning |
| --- | --- |
| `pub`, `priv` | Intended visibility of a declaration |
| `mut` | The pointed-to value may be mutated |
| `borrowed` | The pointer is borrowed and is not initialized by the callee |
| `owned` | The callee may initialize the pointer but does not free it |
| `stat` | Marks a static method in C-plus source |
| `scratch` | Short-lived memory invalidated by a scratch reset |
| `hot` | Frequently accessed working-set memory |
| `warm` | General-purpose dynamic memory |
| `cold` | Infrequently accessed or large memory |

The generated C preamble defines these names as empty macros. They are documentation and tooling hints, not statically enforced contracts. The four allocation-intent annotations do not allocate by themselves; use the matching allocator API described in [`stdlib/README.md`](../../stdlib/README.md#memory-and-allocation-intent).

The compiler retains allocation intent and ownership tags as source-mapped metadata and emits advisory warnings for supported, directly visible mismatches. The initial analysis follows simple pointer assignments and checks annotated allocator returns/parameters. It is not a complete C AST or ownership checker; see the standard-library guide for supported cases and limitations.

## Struct Methods

Struct aliases conventionally end in `_t`:

```c
typedef struct counter_t {
    int value;

    pub int add(borrowed mut *self, int amount) {
        self->value += amount;
        return 0;
    }

    static pub counter_t* alloc_init(int initial) {
        return 0;
    }
} counter_t;
```

An instance method uses `self` as its first parameter. It is emitted as `counter__add(counter_t *self, int amount)`. A static method is emitted with a C `static` qualifier and the same `counter__method` name.

Instance calls use receiver syntax. For a struct value, the compiler supplies its address; for a struct pointer, use C's `->` spelling and the pointer is passed directly. The older explicit-address form remains accepted. Static methods use the struct type as the receiver:

```c
counter_t counter;
counter.add(3);             // counter__add(&counter, 3)
counter_t* counter_ptr = &counter;
counter_ptr->add(3);        // counter__add(counter_ptr, 3)
(&counter).add(3);          // also accepted: counter__add(&counter, 3)
counter_t.alloc_init(0);    // counter__alloc_init(0), no receiver argument
```

## Comptime and Generics

`comptime` marks compile-time declarations, invocations, blocks, and inline scalar evaluation; comptime string calls can splice validated identifiers into generated type and function declarations. Ordinary C `#define` may be used as a source-visible aliasing convention for generated function names; the compiler adds no alias syntax. The implemented forms and limitations are specified in [`SPEC.comptime.md`](SPEC.comptime.md). Runtime reflection and typed AST decorators are documented there as proposed extensions and are not accepted by the current compiler.

## Source Mapping

Generated C contains `#line` directives referencing the original C-plus file so compiler diagnostics point back to `.cp` source locations.

## Tests

Test blocks use a named C-plus annotation and are compiled only by the `test` command:

```c
#include <string.h>

@test "print and initialize a struct" {
    some_struct value = (some_struct){1, 2, 3};
    char hash[32];
    some_struct__hash(&value, hash);
    CPLUS_TEST_ASSERT(strcmp(hash, "1:2:3") == 0);
}
```

Quoted names are recommended; unquoted names such as `@test print and init struct { ... }` are also accepted. Each body is emitted as a test function. `@assert(condition)` and `CPLUS_TEST_ASSERT(condition)` always print the expression, fixture-wide assertion number/total, obtained boolean, expected `true`, and green/red PASS/FAIL status. `@assertEquals(expected, actual)` always prints both expressions and obtained/expected values; common scalar and string-pointer values are formatted, while unsupported types use a byte-hex fallback. Equality remains bytewise, not deep equality (for strings, compare contents with `strcmp`), and arrays are not supported operands. `CPLUS_TEST_FAIL(message)` also fails the current test. The runner separates test headings from preceding output with a blank line and displays each test's source-order number out of the total in yellow. Test blocks are removed from ordinary `transcode`, `compile`, and `run` output.

The runner accepts one or more `.cp`/`.c+` sources, followed by optional exact test names. The shell expands patterns such as `test/folder/*.cp`. Ordinary C files can remain unchanged and be included with `#include "fixture.c"` or `@import("fixture.c")`; the latter emits a normal C preprocessor include and does not evaluate the C file at comptime. In test mode, a source-defined `main` is renamed so the generated test driver can own the executable entry point; the application `main` is not run.

## Standard Library

The initial source library lives in [`../../stdlib/`](../../stdlib/README.md). `@dynamic_list(T)` is a resizable contiguous value container with callback iteration. `@dynamic_map(K, V)` is a resizable key/value table with caller-supplied equality and O(n) lookup/removal. `list_mapper(T, R, InputList, OutputList)` generates a typed callback-based list conversion function. Containers copy values and do not deep-own pointer members; call `destroy` to release container storage.
