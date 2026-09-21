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

The generated C preamble defines these names as empty macros. They are documentation and tooling hints, not statically enforced contracts.

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

Calls use receiver syntax:

```c
counter_t counter;
(&counter).add(3);          // counter__add(&counter, 3)
counter_t.alloc_init(0);    // counter__alloc_init(0)
```

## Comptime and Generics

`@` introduces compile-time values, functions, imports, blocks, generic type values, and reflection. The complete design, materialization rules, intermediate C-plus examples, and limitations are specified in [`SPEC.comptime.md`](SPEC.comptime.md). The compiler implements the documented core forms and rejects unsupported extensions before C lowering.

## Source Mapping

Generated C contains `#line` directives referencing the original C-plus file so compiler diagnostics point back to `.cp` source locations.
