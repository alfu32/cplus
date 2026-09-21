# C-plus Comptime Specification

Status: design target for the next compiler pass. The current compiler recognizes `@` as reserved syntax and reports an error; it does not yet implement the semantics below.

This is a living specification. Any change to comptime syntax, evaluation, imports, reflection, or materialization must update this file and its examples.

## Purpose and Phases

Comptime is a deterministic precompilation phase. It produces ordinary C-plus text and entities before the existing C-plus-to-C lowering passes run:

```text
C-plus source
  -> load @import graph
  -> collect and evaluate comptime declarations
  -> materialize returned runtime C-plus entities
  -> resolve remaining @ expressions
  -> intermediate C-plus
  -> method lowering and mapped C emission
  -> C
```

No unresolved `@` token may reach the C emitter. A comptime value is not a runtime variable, and executing a comptime function never calls generated runtime code.

## Syntax

`@` is the comptime sigil. It binds to the following identifier or starts a comptime construct; whitespace is allowed only where shown for a block.

The core forms are:

```text
comptime-import       := "@import" string-literal ";"
comptime-block        := "@" "{" comptime-statement* "}"
comptime-value        := c-type "@" identifier ("=" comptime-expression)? ";"
comptime-function     := (c-type | "variable" | "function") "@" identifier "(" parameter* ")" block
type-generator        := "@type" identifier "(" ("@type" identifier)* ")" block
comptime-struct       := "typedef struct" "@" identifier "{" cplus-members "}" identifier ";"
comptime-reference    := "@" identifier
comptime-call         := "@" identifier "(" argument* ")"
```

The ordinary comptime-function form uses a C type for scalar results, `variable` for a variable declaration entity, or `function` for a function declaration entity. The separate `@type name(...)` form produces a generated type. A comptime parameter is marked with `@`; `@type T` declares a type-value parameter.

### Comptime values and functions

```c
int @answer = 21;

int @twice(int @value) {
    return @value * 2;
}

int runtime_answer = @twice(@answer);
```

The declaration name and marked parameters are comptime-only. A normal C-plus expression can reference a comptime scalar with `@name` or call a comptime function with `@name(...)`. The result must be a literal or another materializable value.

### Comptime blocks

A top-level `@ { ... }` block executes during precompilation. An expression statement that evaluates to a runtime entity is materialized in place, in source order:

```c
@ {
    @make_declarations(4);
}
```

Scalar expression statements are discarded unless returned by a comptime function and used by another expression. Blocks cannot contain runtime control flow that executes after compilation.

Comptime control flow is prefixed with `@` and operates only on comptime values. The first required forms are `@if`, `@else`, and `@for name in value`; a `@for` over `T.fields` visits fields in declaration order. A comptime loop must have a statically bounded iterable or hit the implementation expansion limit.

### Imports

```c
@import "math.cp";
```

The path is relative to the importing file, must resolve to a `.cp` or `.c+` file, and is canonicalized before loading. An imported file is evaluated once per compilation graph. Its comptime declarations become available to the importer; its materialized runtime declarations are emitted once in dependency order. Imports are not C `#include`s and do not reach the C preprocessor.

### Types, generics, and reflection

`@type` is a type value. It can be used as a generic parameter and inside comptime reflection:

```c
@type wrapper(@type T) {
    return struct {
        pub T* wrapped_value;
    };
}

@wrapper(int) wrapper_int_t;
```

The supported reflection surface is compile-time-only:

```c
// Grammar fragment: T is declared in a type-producing comptime parameter list.
@type T;
T.name       // canonical source name, a comptime string
T.size       // sizeof(T), when the target ABI is known
T.align      // alignment of T, when the target ABI is known
T.fields     // ordered field metadata for a known struct
```

Reflection cannot inspect a runtime value or call a generated function. Field metadata includes names, types, and annotations; declaration order is preserved.

### Comptime struct declarations

A struct declaration whose tag starts with `@` is a comptime entity. It is not emitted until referenced from a comptime expression or block:

```c
typedef struct @point_t {
    int x;
    int y;
} point_t;

@ {
    @point_t;
}
```

The materialized declaration removes the sigil:

```c
typedef struct point_t {
    int x;
    int y;
} point_t;
```

### Entity-returning comptime functions

Comptime functions may return runtime C-plus entities. The result kind is declared with `@type`, `variable`, or `function`; the returned body is a C-plus declaration fragment:

```c
variable @make_limit(int @value) {
    return int generated_limit = @value;
}

function @make_checker(int @limit) {
    return function int generated_checker(int value) {
        return value < @limit;
    };
}

@ {
    @make_limit(10);
    @make_checker(10);
}
```

The corresponding intermediate C-plus is:

```c
int generated_limit = 10;

int generated_checker(int value) {
    return value < 10;
}
```

The C output keeps the normal C-plus lowering preamble and contains the same runtime declarations:

```c
#define pub
#define priv
#define mut
#define borrowed
#define owned
#define stat

int generated_limit = 10;
int generated_checker(int value) { return value < 10; }
```

Entity results are syntax trees, not strings. A function cannot manufacture executable source by concatenating arbitrary text; it must return a parsed C-plus fragment. This keeps delimiters, types, and source locations verifiable.

## Materialization Examples

### Scalar value

Input:

```c
int @answer = 21;
int @twice(int @value) { return @value * 2; }
int runtime_answer = @twice(@answer);
```

After precompilation, the intermediate C-plus is:

```c
int runtime_answer = 42;
```

`answer` and `twice` do not create runtime symbols.

After the normal C-plus lowering step, the relevant C is:

```c
int runtime_answer = 42;
```

The complete generated file also contains the annotation macro preamble and source-mapping directives.

### Imported value

`constants.cp`:

```c
int @default_limit = 8;
```

`main.cp`:

```c
@import "constants.cp";
int limit = @default_limit;
```

After precompilation, the intermediate C-plus is:

```c
int limit = 8;
```

The imported comptime declaration is evaluated but is not copied into the output.

After C lowering, the runtime declaration remains:

```c
int limit = 8;
```

### Generic type

Input:

```c
@type wrapper(@type T) {
    return struct {
        T* wrapped_value;
    };
}

@wrapper(int) wrapper_int_t;
```

The generator is instantiated once. Its intermediate C-plus materialization is:

```c
typedef struct __int__wrapper_t {
    int* wrapped_value;
} wrapper_int_t;
```

After C lowering, the relevant C is:

```c
typedef struct __int__wrapper_t {
    int* wrapped_value;
} wrapper_int_t;
```

The complete generated file also contains the normal annotation preamble. Generated names use `__<canonical-type>__<generator>` and must be collision-checked; the requested alias remains the public C-plus name.

### Reflected fields

Input:

```c
typedef struct user_t {
    int id;
    borrowed char* name;
} user_t;

@ {
    @for field in user_t.fields {
        @make_field_logger(field.name, field.type);
    }
}
```

After precompilation, this materializes one C-plus declaration per field. The exact loop spelling is part of the comptime-block grammar and must be accepted only by the comptime parser; it is never passed to the C parser. For the example, the result conceptually resembles:

```c
void log_user_id(int value);
void log_user_name(char* value);
```

Those declarations are already ordinary C, so the corresponding C output has the same declarations, plus the annotation preamble and `#line` directives.

## Reliability Rules and Limitations

- **Determinism:** comptime code cannot read the clock, random values, process state, environment variables, or the network. File access is limited to source files reached through `@import`.
- **Sandboxing:** comptime functions cannot call runtime C functions, dereference runtime pointers, execute subprocesses, or mutate compiler-global state.
- **Dependency safety:** imports are canonicalized, evaluated once, and rejected on cycles. Duplicate generated declarations with incompatible definitions are errors.
- **Hygiene:** generated identifiers are collision-checked and derived from the generator and type arguments. A future explicit hygienic name escape must not silently capture user declarations.
- **Bounded evaluation:** recursion, expansion depth, generated bytes, and imported files have implementation limits. Exceeding a limit is a source-mapped comptime diagnostic.
- **Target dependence:** `T.size` and `T.align` require a selected C target ABI. Reflection that depends on ABI data is evaluated with the same target configuration used by TinyCC.
- **No runtime reflection:** `@type`, field metadata, and comptime values disappear before C compilation. Runtime inspection requires ordinary C-plus data and code.
- **No implicit string evaluation:** strings are values, not source code. Only typed entity results can materialize declarations.
- **Source mapping:** generated entities retain both their generator span and instantiation span. Diagnostics in generated code point to the instantiation first and can explain the generator origin.
- **Current status:** all `@` forms are currently rejected by the compiler. Implementation must land with parser, evaluator, source-map, import-cycle, expansion-limit, and golden-output tests for every example in this document.
