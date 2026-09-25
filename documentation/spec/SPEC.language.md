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

## `defer`

`defer` is a compiler-lowered statement valid inside a function or method body. It accepts either one C/C-plus statement or a braced group. The compiler removes each occurrence and appends its payload immediately before the enclosing function's closing brace, reversing the order in which the occurrences appeared. Statements within one deferred group keep their written order; the group remains braced so its local declarations stay scoped.

```c
void close_example(void) {
    defer release_resource();
    defer {
        flush_output();
        close_output();
    }
    work();
}
```

The generated C-plus body ends conceptually as:

```c
    work();
    {
        flush_output();
        close_output();
    }
    release_resource();
```

This is a source transformation, not runtime stack unwinding: in particular, an explicit `return` before the inserted tail skips the deferred statements. Put defers in functions that reach their closing brace, or arrange control flow accordingly. A defer without a complete statement/block, or outside a function/method body, is diagnosed. Moved text retains its original source-map locations.

## Error propagation with `@throws`, `@try`, and `@catch`

Error propagation is an opt-in lowering convention over ordinary C return values and output parameters. It does not change the C ABI or enforce ownership. Define `error_t` in the usual way and reserve zero for success.

Annotate an error-returning function with empty `@throws()` metadata:

```c
@throws()
pub error_t read_count(borrowed FILE *file, borrowed mut int *out_count) {
    if (fscanf(file, "%d", out_count) != 1) return ERROR_IO;
    return ERROR_NONE;
}
```

Annotate a function that returns a value and reports failure through a final writable `error_t *` parameter by naming that parameter:

```c
@throws(error)
pub counter_t *create__counter(int initial, borrowed mut error_t *error) {
    counter_t *self = malloc(sizeof *self);
    if (self == NULL) {
        *error = ERROR_ALLOCATION;
        return NULL;
    }
    *error = ERROR_NONE;
    self->value = initial;
    return self;
}
```

The error-out parameter must be last and is supplied automatically when omitted at a checked call site. Both conventions treat `ERROR_NONE`/zero as success. An explicitly provided error-out argument requests manual handling for that call.

Use `@try` with ordered catches; the outermost try must end in a catch-all. Nested tries may omit it to propagate unmatched errors outward:

```c
@try {
    read_count(file, &count);
    counter = create__counter(count);
    printf("count=%d\n", count);
}
@catch (ERROR_ALLOCATION, error_t error) {
    report_allocation_error(error);
}
@catch (ERROR_IO | ERROR_INVALID_ARGUMENT, error_t error) {
    report_input_error(error);
}
@catch (error_t error) {
    report_unexpected_error(error);
}
continue_after_try();
```

The first failed checked call skips the remaining try-body statements and dispatches to the first matching catch. The binding contains the actual returned/reported code; execution resumes after the whole construct when the catch finishes. `|` separates alternatives (it is not a C bitwise expression).

The current lowering supports annotated calls as complete expression statements, and error-out calls as complete assignment or declaration-initializer right-hand sides. Error-returning calls used as explicit assignments are intentionally manual. Each call argument and assignment destination is evaluated once. `@throws` declarations are matched by their canonical C identifier, including methods after receiver-call lowering; this initial pass is not full C type/scope resolution. A call outside `@try` remains an ordinary C call.

The first implementation rejects annotated calls embedded in conditions, return expressions, other call arguments, comma/short-circuit expressions, or other larger expressions. Nested tries handle their own body first; an unmatched error, or an error from a nested catch body, is forwarded to the enclosing try. Calls from a top-level catch body are not caught by that same try. `@try` is not stack unwinding: it does not release resources or alter `return`, `break`, or `continue`. A catch block must handle or explicitly manage its own errors. The compiler diagnoses malformed signatures, catch lists, unmatched constructs, and unsupported checked-call contexts at the originating `.cp` location. See [`SPEC.errors.md`](SPEC.errors.md) for generated-C behavior and compiler limitations.

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

Quoted names are recommended; unquoted names such as `@test print and init struct { ... }` are also accepted. Each body is emitted as a test function. `@assert(condition)` and `CPLUS_TEST_ASSERT(condition)` always print the expression, invocation-wide assertion number/total, obtained boolean, expected `true`, and green/red PASS/FAIL status. `@assertEquals(expected, actual)` always prints both expressions and obtained/expected values; common scalar and string-pointer values are formatted, while unsupported types use a byte-hex fallback. Equality remains bytewise, not deep equality (for strings, compare contents with `strcmp`), and arrays are not supported operands. `CPLUS_TEST_FAIL(message)` also fails the current test. The runner separates test headings from preceding output with a blank line and displays each selected test's number out of the invocation-wide total in yellow. Test blocks are removed from ordinary `transcode`, `compile`, and `run` output.

The runner accepts one or more `.cp`/`.c+` sources, followed by optional exact test names. The shell expands patterns such as `test/folder/*.cp`. At completion it reports selected fixture and assertion-site counts per file, then totals files, fixtures, and assertions for the invocation. Filters affect all these counts; files with no matching fixtures are omitted. Ordinary C files can remain unchanged and be included with `#include "fixture.c"` or `@import("fixture.c")`; the latter emits a normal C preprocessor include and does not evaluate the C file at comptime. In test mode, a source-defined `main` is renamed so the generated test driver can own the executable entry point; the application `main` is not run.

## Standard Library

The initial source library lives in [`../../stdlib/`](../../stdlib/README.md). `@dynamic_list(T)` is a resizable contiguous value container with callback iteration and in-place numeric/alphanumeric heapsort; `@dynamic_map(K, V)` is a resizable key/value table with custom or bytewise equality, callback heapsort, and O(n) lookup/removal. `list_mapper` generates typed conversions. `list_aggregate.cp` generates fold, group, zip, union, intersection, and directional difference functions; the collection-specific signatures and bytewise semantics are specified in [`stdlib/CONTAINERS.SPEC.md`](stdlib/CONTAINERS.SPEC.md). Containers copy values and do not deep-own pointer members; call `destroy` to release container storage, including nested group lists.
