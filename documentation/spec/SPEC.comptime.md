# C-plus Comptime Specification

Status: core implementation. Scalar values/functions, keyword-led comptime declarations and invocations, imports, comptime blocks, entity materialization, generic struct generation, identifier-safe code interpolation, basic reflection, iterative generated-declaration expansion, mapped diagnostics, and named runtime test blocks are implemented. The limitations below are normative for the current implementation.

This is a living specification. Any change to comptime syntax, evaluation, imports, reflection, or materialization must update this file and its examples.

## Purpose and Phases

Comptime is an earlier evaluation phase of the same language, not a separate macro language. A comptime function is an in-source compiler plugin: it receives compile-time values or source fragments, transforms them, and returns values or valid C-plus declarations for the next phase.

```text
C-plus source
  -> phase 1: parse and resolve active comptime declarations
  -> repeat: register generated declarations, expand calls, and preserve source maps
  -> stop when no active comptime declarations remain
  -> phase 2: transpile the resulting C-plus to mapped C
  -> optional phase 3: compile C with TinyCC
```

No unresolved comptime declaration, `comptime` marker, or `@` reference may reach the C emitter. A comptime value is not a runtime variable, and executing a comptime function never calls generated runtime code.

### One language, two evaluation contexts

The source remains C-plus in both contexts. The difference is when a declaration executes and what it is allowed to produce:

| Context | Input | Output |
| --- | --- | --- |
| Phase 1, comptime | comptime C-plus functions, values, fragments, and imports | scalars, type metadata, and valid C-plus declarations |
| Phase 2, runtime C-plus | the phase-1 materialized source | ordinary C-plus declarations and expressions, then C |

Phase-1 functions may use compiler-provided fragment and reflection APIs. They must not call runtime functions, dereference runtime pointers, execute processes, or access nondeterministic state. This keeps the surface language familiar without pretending that a phase-1 function is an ordinary executable function.

The phase-1 contract is strict: every invocation either produces a supported value, produces a parseable C-plus fragment, or reports a source-mapped diagnostic. Before phase 2 starts, all phase-1 invocations and decorators must have been resolved, generated names must be checked, and the remaining source must be valid C-plus.

Comptime modules can therefore be written in C-plus and imported as compiler plugins. An imported module may expose phase-1 functions and may materialize ordinary runtime declarations; it is not linked as a runtime library merely because it contains a comptime function.

### Repeated expansion passes

The compiler parses active declarations at module scope, registers their comptime definitions, expands their invocations, and reparses the resulting mapped C-plus. A returned fragment stays opaque while it is part of a generator body. Once a call emits that fragment into the module, its declarations become active on the next pass. Expansion repeats until no active comptime syntax remains; C-plus lowering starts afterward.

Each pass carries source origins forward. Diagnostics in generated declarations resolve through every expansion to the source location that contributed the text. Imports and comptime definitions remain available to later passes. Repeated source states, passes that make no progress, more than 128 passes, and unresolved comptime or `@` forms produce source-mapped diagnostics.

## Syntax

The preferred syntax uses the reserved keyword `comptime` to mark compile-time declarations, invocations, blocks, and inline scalar evaluation. `@name` explicitly refers to a comptime symbol inside ordinary C-plus or a quoted code fragment. `type` denotes a type value or a type-producing result and does not need an `@` prefix. Existing sigil-led forms remain supported for compatibility.

The core forms are:

```text
comptime-import       := "comptime" "import" string-literal ";"
comptime-block        := "comptime" "{" comptime-statement* "}"
comptime-value        := "comptime" c-type ["@"] identifier ["=" comptime-expression] ";"
comptime-function     := "comptime" result-kind "@" identifier "(" parameter* ")" block
result-kind           := c-type | "type" | "variable" | "function" | "@var" | "@fn" | "@code"
type-parameter        := "type" identifier
comptime-invocation   := "comptime" identifier "(" argument* ")" ";" | "comptime typedef" identifier "(" argument* ")" identifier ";"
inline-value          := "comptime" comptime-expression
code-fragment         := "@code" "{" cplus-top-level-item* "}"
identifier-splice     := "@" identifier "(" argument* ")" ; inside identifiers in materialized type/function entities
comptime-struct       := "typedef struct" "@" identifier "{" cplus-members "}" identifier ";"
legacy-import         := "@import" string-literal ";"
legacy-block          := "@" "{" comptime-statement* "}"
legacy-call           := "@" identifier "(" argument* ")"
legacy-type-generator := "@type" ["@"] identifier "(" ("@type" identifier)* ")" block
test-block            := "@test" (string-literal | unquoted-name) block
```

Preferred declarations and invocations look like this:

```c
comptime string @typename(type T) {
    return T.name;
}

comptime type @list(type T) {
    return @code {
        struct list_of_@typename(T) {
            T buffer[100];
            pub int add(borrowed mut *self, T* item) { /* ... */ return 0; }
        };
    };
}

comptime typedef list(string_t) string_list_t;
comptime make_helpers(int_t, string_t);
int count = comptime item_count;
```

`comptime` declarations and invocations are active in module scope or an explicit comptime block. A comptime function name is declared with `@`; an invocation introduced by `comptime` uses the plain symbol name. In ordinary C-plus expressions, `@name` remains an explicit comptime reference, while `comptime expression` evaluates a scalar expression. Therefore `int answer = answer;` is runtime C-plus, `comptime int answer = 21;` declares a comptime-only value, and `int runtime_answer = comptime answer;` materializes it in runtime source. The marker applies through the surrounding C expression delimiter (such as `;`, `,`, `)`, or `]`).

### Runtime test declarations

`@test` is a test-runner annotation, not a comptime function and not runtime code:

```c
@test "construct and hash" {
    some_struct value = (some_struct){1, 2, 3};
    char hash[32];
    some_struct__hash(&value, hash);
    CPLUS_TEST_ASSERT(strcmp(hash, "1:2:3") == 0);
}
```

The recommended name is a quoted string literal; the parser also accepts unquoted text before the opening brace. The body is C-plus statement code and runs in a generated `int` test function after comptime types and functions have materialized. `CPLUS_TEST_ASSERT(condition)` reports failure and returns from the current test; `CPLUS_TEST_FAIL(message)` does the same with a message. A test body may also `return 1` to fail or `return 0` to pass. Tests share process globals but have independent local scopes. Ordinary `transcode`, `compile`, and `run` remove test blocks; `cplus test` extracts them and emits a temporary driver.

The test command runs all test blocks by default. Exact names after the source paths select tests; source paths may be multiple `.cp`/`.c+` files, with shell-expanded globs supported. A `#include "fixture.c"` remains a normal C preprocessor include and lets tests exercise unchanged C declarations and functions. C files are not accepted by `comptime import`. During test compilation any source `main` is renamed and is not executed; the generated test driver owns the entry point. A failed assertion is isolated to its test function so later tests still run. The current harness is process-based and does not provide fixtures, setup/teardown hooks, parallel execution, or structured assertion values.

`comptime type` functions return exactly one `@code` fragment containing one named `struct` definition. A `comptime typedef generator(args) alias;` invocation turns it into `typedef struct tag { ... } alias;`. Other comptime invocations materialize the runtime entity returned by the function. Previous `@type`, `@fn`, `@var`, and scalar sigil-led forms remain accepted.

Inside source materialized by a `comptime type` or `comptime function` generator, a comptime scalar call embedded in an identifier, such as `list_of_@typename(T)` or `mapper__@name(T)__to__@name(R)`, is an identifier splice. The called comptime function must return a string containing one valid C identifier token; it is inserted without quotes. Other `@code` fragments do not eagerly evaluate nested declarations or embedded names; those declarations remain opaque until a later expansion pass. Outside identifier splices, strings keep their normal quoted C representation. Strings are not parsed as source by themselves; source is introduced explicitly by `@code`.

Parameters of a comptime function are compile-time values by context. `type T` declares a type-valued parameter. Legacy parameters such as `int @value` and `@type T` remain accepted.

Legacy type-producing calls may use the C-like `typedef` form:

```c
typedef @wrapper(int) wrapper_int_t;
```

In the preferred grammar, `typedef` follows `comptime`: `comptime typedef wrapper(int) wrapper_int_t;`. The parser does not special-case generator names; the declared `type` result kind determines that the call produces a type. Local type instantiations are not implemented yet.

### Comptime values and functions

```c
comptime int @answer = 21;

comptime int @twice(int value) {
    return value * 2;
}

int runtime_answer = comptime twice(answer);
```

The declaration and its parameters are comptime-only. `comptime expression` evaluates a scalar reference or scalar function call in a runtime expression; `@name` remains an explicit comptime reference spelling. The result must be a literal or another materializable value.

In the current implementation, an inline `comptime` expression must begin with a comptime identifier or scalar function call; evaluator operators may follow it. Literal-leading and parenthesized-leading forms are not yet accepted.

### Comptime blocks

A top-level `comptime { ... }` block executes during precompilation. An expression statement that evaluates to a runtime entity is materialized in place, in source order. The legacy `@ { ... }` block is also accepted:

```c
comptime {
    make_declarations(4);
}
```

Scalar expression statements are discarded unless returned by a comptime function and used by another expression. Blocks cannot contain runtime control flow that executes after compilation.

Comptime control flow currently uses the legacy `@` forms and operates only on comptime values. The supported forms are `@if`, `@else`, and `@for name in value`; a `@for` over `T.fields` visits fields in declaration order. A comptime loop must have a statically bounded iterable or hit the implementation expansion limit.

### Imports

```c
comptime import "math.cp";
```

The path is relative to the importing file, must resolve to a `.cp` or `.c+` file, and is canonicalized before loading. An imported file is evaluated once per compilation graph. Its comptime declarations become available to the importer; its materialized runtime declarations are emitted once in dependency order. Imports are not C `#include`s and do not reach the C preprocessor. The legacy `@import` spelling remains accepted.

### Types, generics, and reflection

`type` is the preferred type-value keyword; legacy `@type` remains accepted. It can be used as a generic parameter and inside comptime reflection. This legacy generic declaration remains accepted:

```c
@type @wrapper(@type T) {
    return struct {
        pub T* wrapped_value;
    };
}

typedef @wrapper(int) wrapper_int_t;
```

The supported reflection surface is compile-time-only:

```c
// Grammar fragment: T is declared in a type-producing comptime parameter list.
type T;
T.name       // canonical source name, a comptime string
T.size       // sizeof(T), when the target ABI is known
T.align      // alignment of T, when the target ABI is known
T.fields     // ordered field metadata for a known struct
```

Reflection cannot inspect a runtime value or call a generated function. Field metadata includes names, types, and annotations; declaration order is preserved.

### Comptime struct declarations

A legacy struct declaration whose tag starts with `@` is a comptime entity. It is not emitted until referenced from a comptime expression or block:

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

Comptime functions may return runtime C-plus entities. The result kind is itself the entity kind: `type` returns a type, `variable` returns a variable declaration, and `function` returns a C function definition. In particular, `function` is not repeated as a marker before the generated declaration. A `type T` parameter is substituted as a C type name within the returned declaration. The sigiled result kinds `@type`, `@var`, and `@fn` remain accepted for compatibility; the older `return function ...` and `return @fn ...` spellings also remain accepted.

```c
comptime variable @make_limit(int @value) {
    return int generated_limit = @value;
}

comptime function @comptime_proto_decl_name(type T) {
    return T some_gen_name(T param) {
        return param + 2;
    }
}

comptime make_limit(10);
comptime comptime_proto_decl_name(int);
```

The corresponding intermediate C-plus is:

```c
int generated_limit = 10;

int some_gen_name(int param) {
    return param + 2;
}
```

The generated function is ordinary C-plus after expansion and can be called from runtime code, for example `some_gen_name(40)`. The result kind selects what the generator returns; the generated C declaration supplies its own name and signature.

Identifier splices allow a generated function name to encode its type arguments:

```c
comptime string @name(type T) {
    return T.name;
}

comptime function @generic_mapper(type T, type R) {
    return R mapper__@name(T)__to__@name(R)(R (*mapper_callback)(T, int), T item, int index) {
        return mapper_callback(item, index);
    }
}

comptime generic_mapper(int, float);
```

This materializes `float mapper__int__to__float(float (*mapper_callback)(int, int), int item, int index)`. Each splice is validated as a single C identifier before insertion.

The exact specialized name is determined during expansion, so source navigation and external C tools may benefit from an explicit public spelling. A contributor may use an ordinary C preprocessor alias before the comptime invocation:

```c
#define mapper__int__to__float mapper_int_to_float
comptime generic_mapper(int, float);
```

Runtime code can call the visible public spelling, `mapper_int_to_float(...)`. This is a coding convention using standard `#define`, not a C-plus alias feature: the C preprocessor rewrites the generated declaration's identifier, and the compiler does not interpret or validate the alias. Keep the directive before the materialized declaration, and remember that object-like macros have translation-unit-wide replacement effects. C-plus does not add special syntax such as `as` or a comptime-aware `#define` form.

The C output keeps the normal C-plus lowering preamble and contains the same runtime declarations:

```c
#define pub
#define priv
#define mut
#define borrowed
#define owned
#define stat

int generated_limit = 10;
int some_gen_name(int param) { return param + 2; }
```

Entity results are mapped C-plus fragments. The compiler does not expose a user-facing typed AST, and ordinary strings are never evaluated as source. `@code` is the explicit source-fragment form.

### Generated declarations across passes

Use `@code` when a comptime function needs to return several declarations or introduce another comptime generator. Declarations inside the returned block are inert while the block is part of the function template. After the function emits the block, later passes parse and resolve those declarations. Identifier splices are evaluated as the fragment is materialized; other comptime declarations remain for a later pass.

```c
comptime string @typename(type T) {
    return T.name;
}

comptime code @emit_seed() {
    return @code {
        comptime code @emit_box(type T) {
            return @code {
                comptime type @box(type U) {
                    return @code {
                        struct box_of_@typename(U) { U value; };
                    };
                }
                comptime typedef box(T) generated_box_t;
            };
        }
        comptime {
            emit_box(int);
        }
    };
}

comptime {
    emit_seed();
}
```

The first expansion emits `@emit_box` and its comptime block. A later pass registers `@emit_box`, runs it, and emits `@box` and its typedef invocation. Another pass evaluates the delayed identifier splice and materializes the struct:

```c
typedef struct box_of_int {
    int value;
} generated_box_t;
```

An emitted fragment contains module-level C-plus declarations. Declarations inside ordinary runtime function bodies are not comptime expansion sites in this version.

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
@type @wrapper(@type T) {
    return struct {
        T* wrapped_value;
    };
}

typedef @wrapper(int) wrapper_int_t;
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

## Proposed AST fragments, decorators, and runtime reflection

The next comptime layer should be AST-aware, but should keep a strict distinction between compile-time metadata and runtime data. Rust is a useful comparison: declarative and procedural macros operate on compile-time token streams or syntax structures, not arbitrary runtime strings. C-plus can offer the same power with typed, source-mapped fragments.

### Typed fragments and decorators

The plugin API should expose opaque compile-time fragment values, not runtime structs that happen to describe syntax. A fragment can be inspected and rebuilt during phase 1, then validated before it enters phase 2. Useful operations include:

- visit declarations, parameters, fields, annotations, and expressions;
- construct or clone a declaration while preserving its source origin;
- rename an identifier through an explicit hygiene-aware operation;
- attach or remove C-plus annotations such as `pub`, `borrowed`, or `mut`;
- return a module containing the original declaration and generated companions.

Source text should still be available for controlled tooling. A proposed API is:

```text
parse_fragment(text, grammar_kind) -> Fragment
print_fragment(fragment) -> string
```

`parse_fragment` must parse only the requested grammar kind, reject unresolved phase-1 forms unless explicitly allowed, and associate failures with the plugin call site. `print_fragment` is useful for diagnostics and snapshots, but text substitution should not be the normal transformation path. Typed fragments are easier to validate, map, and keep hygienic.

The proposed fragment kinds are:

```text
TypeFragment       a type declaration or type expression
DeclFragment       a variable, function, typedef, or struct declaration
StmtFragment       a statement sequence
ExprFragment       an expression
ModuleFragment     a sequence of top-level declarations
```

Each fragment should retain its source span, syntactic kind, identifiers, annotations, and child nodes. A decorator must not be able to return an expression where a declaration is required.

The existing `@ { ... }` form is a good explicit comptime block. A future declaration annotation could mark an entire declaration for compile-time transformation:

```c
// Proposed; not accepted by the current compiler.
@typedef struct type_info_t {
    const char* name;
    const field_info_t* fields;
} type_info_t;
```

`@` at the beginning of a statement should remain an explicit comptime invocation or reference. This keeps a runtime declaration containing a comptime value separate from a declaration that produces another declaration. `@type` should remain the intrinsic type value; use a normal name such as `type_info_t` for emitted reflection data.

A future decorator could look like this:

```c
// Proposed; exact quote/unquote syntax is not fixed.
@add_debug_wrapper
pub int add(int left, int right) {
    return left + right;
}
```

Conceptually, the decorator receives a `DeclFragment` for `add`, inspects its parameters and body, and returns a `ModuleFragment` containing the original function plus a wrapper. The compiler must define whether decorators replace declarations, append declarations, or may do both.

Typed constructors and visitors should be the primary API. String-to-fragment and fragment-to-string helpers are useful for tooling, snapshots, and diagnostics, but should be constrained:

- `parse_decl(string)` must require a grammar kind and reject unresolved `@` forms.
- `print(fragment)` should not be the primary transformation mechanism.
- Quasiquoted fragments should preserve source locations and distinguish literal text from interpolated identifiers and expressions.

### In-source compiler plugins

Plugin definitions are ordinary C-plus-shaped source with a phase-1 result contract:

```c
// Proposed; fragment types and quote syntax are not implemented yet.
@fn @derive_debug(decl_fragment_t @input) {
    return @module {
        @input;
        // construct a debug helper from @input.fields and @input.name
    };
}
```

The plugin can be imported before the rest of a source module is materialized:

```c
@import "derive_debug.cp";

@derive_debug
pub typedef struct user_t {
    int id;
} user_t;
```

Resolution should proceed as follows:

1. Load and register imported phase-1 declarations.
2. Parse the source module without sending unresolved C-plus extensions to the C parser.
3. Evaluate comptime values, explicit invocations, and decorators.
4. Validate each returned fragment and insert its runtime declarations.
5. Repeat expansion until no phase-1 invocation remains, subject to cycle and expansion limits.
6. Hand the resulting ordinary C-plus module to the existing transpiler.

This gives C-plus a macro system that is written in C-plus syntax without asking programmers to learn a second macro language. It still has a phase boundary: plugin code is evaluated by the compiler, while generated declarations execute only after phase 2 transpilation.

### Runtime reflection

Compile-time reflection produces source during precompilation. Runtime reflection produces explicit C data in the executable:

| Feature | Available when | Result |
| --- | --- | --- |
| Compile-time reflection | During precompilation | `T.name`, `T.size`, `T.fields`, and generated source |
| Runtime reflection | In the executable | C metadata tables and, optionally, function pointers |

Runtime reflection should be opt-in because it increases binary size, exposes names, and commits the program to an ABI/layout representation. A proposed declaration is:

```c
// Proposed; not implemented.
@reflect(user_t) user_type_info;
```

Its first materialization could be ordinary target-compiled C:

```c
typedef struct field_info_t {
    const char* name;
    size_t offset;
    size_t size;
} field_info_t;

typedef struct type_info_t {
    const char* name;
    size_t size;
    size_t align;
    const field_info_t* fields;
    size_t field_count;
} type_info_t;

static const field_info_t user_t_fields[] = {
    {"id", offsetof(user_t, id), sizeof(((user_t*)0)->id)},
    {"name", offsetof(user_t, name), sizeof(((user_t*)0)->name)},
};

static const type_info_t user_type_info = {
    "user_t", sizeof(user_t), _Alignof(user_t),
    user_t_fields, 2
};
```

The target C compiler evaluates `offsetof`, `sizeof`, and `_Alignof`, keeping the table consistent with the selected ABI. The current evaluator does not yet provide this target-aware layout pipeline. Methods should use a separate explicit table: instance entries include the receiver, static entries do not, and private methods are omitted unless registered.

Useful applications include serializers, generic equality and hashing, debuggers and inspectors, structured logging, plugin registries, FFI schemas, configuration loaders, protocol codecs, and startup schema validation. Runtime reflection must not imply that arbitrary C code can discover or execute every symbol.

## Reliability Rules and Limitations

- **Determinism:** comptime code cannot read the clock, random values, process state, environment variables, or the network. File access is limited to source files reached through `@import`.
- **Sandboxing:** comptime functions cannot call runtime C functions, dereference runtime pointers, execute subprocesses, or mutate compiler-global state.
- **Dependency safety:** imports are canonicalized, evaluated once, and rejected on cycles. Duplicate generated declarations with incompatible definitions are errors.
- **Hygiene:** generated identifiers are collision-checked and derived from the generator and type arguments. A future explicit hygienic name escape must not silently capture user declarations.
- **Bounded evaluation:** the current implementation limits the import graph to 256 modules, comptime calls to 10,000, expansion to 128 passes, and materialized output to 8 MiB. Repeated source states and passes that make no progress are diagnosed; exceeding a limit is a source-mapped comptime error.
- **Target dependence:** `T.size` and `T.align` currently support the built-in scalar C types. Target-specific struct layout reflection remains reserved until the evaluator receives an explicit target ABI.
- **No runtime reflection yet:** `@type`, field metadata, and comptime values disappear before C compilation. Runtime inspection currently requires ordinary C-plus data and code.
- **Explicit source fragments:** strings remain values. Only a typed `@code { ... }` fragment is interpreted as C-plus source, and the fragment is reparsed on a later pass.
- **No general AST API yet:** typed fragments, decorators, quote/unquote, and AST visitors are proposed, not implemented.
- **Source mapping:** generated entities retain both their generator span and instantiation span. Diagnostics in generated code point to the instantiation first and can explain the generator origin.
- **Current implementation limits:** scalar comptime functions currently require a single `return` expression; variable/function entity functions return one declaration; `@code` returns one explicitly delimited module fragment; `comptime type` returns one named struct; identifier splices must produce one valid C identifier token; `@for` currently iterates reflected fields and emits one entity expression per iteration; comptime declarations and imports must occur at file scope, while comptime invocations may also occur inside an explicit comptime block. Unsupported forms produce source-mapped diagnostics rather than being passed to C.
