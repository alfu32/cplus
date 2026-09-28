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

After comptime materialization (and after optional test-harness generation), the compiler runs its internal `defer` lowering pass before method-call and struct-method lowering. `defer statement` and `defer { statements }` are runtime-source constructs, not comptime evaluator expressions: each is registered at its execution site and conditionally emitted at the closing brace of its containing function/method, in reverse occurrence order, with mapped origins preserved. See the [`defer` language section](SPEC.language.md#defer) for syntax, examples, and the early-return limitation.

Each pass carries source origins forward. Diagnostics in generated declarations resolve through every expansion to the source location that contributed the text. Imports and comptime definitions remain available to later passes. Repeated source states, passes that make no progress, more than 128 passes, and unresolved comptime or `@` forms produce source-mapped diagnostics.

## Syntax

The preferred syntax uses the reserved keyword `comptime` to mark compile-time declarations, invocations, blocks, and inline scalar evaluation. `@name` explicitly refers to a comptime symbol inside ordinary C-plus or a quoted code fragment. `type` denotes a type value or a type-producing result and does not need an `@` prefix. Existing sigil-led forms remain supported for compatibility.

The core forms are:

```text
comptime-import       := "comptime" "import" string-literal ";"
comptime-block        := "comptime" "{" comptime-statement* "}"
comptime-value        := "comptime" c-type ["@"] identifier ["=" comptime-expression] ";"
comptime-function     := "comptime" result-kind "@" identifier "(" parameter* ")" block
result-kind           := c-type | "type" | "variable" | "function" | "string" | "code"
type-parameter        := "type" identifier
comptime-invocation   := "comptime" identifier "(" argument* ")" ";" | "comptime typedef" identifier "(" argument* ")" identifier ";"
inline-value          := "comptime" comptime-expression
code-fragment         := "@code" "{" cplus-top-level-item* "}"
identifier-splice     := "@" identifier "(" argument* ")" ; inside identifiers in materialized type/function entities
comptime-struct       := "typedef struct" "@" identifier "{" cplus-members "}" identifier ";"
legacy-import         := "@import" string-literal [";"] | "@import" "(" string-literal ")" [";"]
legacy-block          := "@" "{" comptime-statement* "}"
legacy-call           := "@" identifier "(" argument* ")"
legacy-type-generator := "@type" ["@"] identifier "(" ("@type" identifier)* ")" block
test-block            := "@test" (string-literal | identifier) block
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

Comptime imports and generator declarations are declared at file scope. Comptime invocations may also appear in a top-level `comptime { ... }` expansion block. The experimental Tree-sitter frontend additionally accepts scalar value declarations there; this prototype treats the block as a translation-unit expansion group, not a runtime C block, so its comptime names enter the translation-unit comptime environment and runtime declarations returned by invocations are inserted at the invocation position. This block-scalar extension is not yet supported by the production legacy evaluator. A comptime declaration or invocation inside an ordinary runtime function is not a supported local binding or expansion site and is diagnosed at its original span (`CPLUS_COMPTIME_VALUE_SCOPE`, `CPLUS_COMPTIME_FLAGS_SCOPE`, or `CPLUS_COMPTIME_INVOCATION_SCOPE`, as applicable). A runtime-scope invocation must not bind to a module generator, because materializing it there would insert a declaration into a C function body. A comptime function name is declared with `@`; an invocation introduced by `comptime` uses the plain symbol name. In ordinary C-plus expressions, `@name` remains an explicit comptime reference, while `comptime expression` evaluates a scalar expression. Therefore `int answer = answer;` is runtime C-plus, `comptime int answer = 21;` declares a comptime-only value, and `int runtime_answer = comptime answer;` materializes it in runtime source. The marker applies through the surrounding C expression delimiter (such as `;`, `,`, `)`, or `]`).

The compile-time scope model is finite and lexical:

- Active imported and root declarations form one module environment in dependency
  order. Callable generators overload by `(name, arity)` only; parameter types do
  not form an overload key. A duplicate signature is an error.
- Scalar values occupy the scalar-value namespace and must have unique names.
  Calls and values remain syntactically distinct (`name(...)` versus `name`).
- Generator parameters form one lexical function scope, must have unique names,
  may shadow module scalar values, and disappear after the return expression/body.
- An `@for` binding exists only in its loop body. A nested loop may shadow the same
  name; leaving it restores the outer binding.
- A top-level `comptime { ... }` is an expansion group, not a nested symbol scope.
- Materialized declarations are reparsed and enter the same module environment on
  the following pass. They therefore participate in duplicate, ambiguity, cycle,
  and generated-runtime-name checks exactly like source declarations.
- Runtime function/struct bodies are not comptime declaration scopes in the current
  language version; active comptime constructs there fail before binding.

Duplicate parameters report `CPLUS_COMPTIME_DUPLICATE_PARAMETER` at the repeated
parameter with the first declaration as a related span. Duplicate/ambiguous callable
signatures, unresolved names, wrong arity, scalar dependency cycles, import cycles,
and runtime entity/tag collisions likewise fail deterministically with mapped
diagnostics. Raw C declarations emitted by a general `@code` fragment are ultimately
subject to the selected C compiler's ordinary and tag namespace rules.

For this scope rule, the Tree-sitter prototype reports `CPLUS_COMPTIME_DECLARATION_SCOPE` for
runtime-scope generator declarations, `CPLUS_COMPTIME_BLOCK_SCOPE` for `comptime { ... }`, and
`CPLUS_COMPTIME_CONDITIONAL_SCOPE` for `@if`/`@else` expansions. These diagnostics are emitted
at the original construct span before name binding or materialization. The existing value,
flags, and invocation scope diagnostics apply to the corresponding constructs as well.

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

The recommended name is a quoted string literal; the parser also accepts one unquoted C identifier before the opening brace. Names containing spaces must be quoted. The body is C-plus statement code and runs in a generated `int` test function after comptime types and functions have materialized. `CPLUS_TEST_ASSERT(condition)` reports failure and returns from the current test; `CPLUS_TEST_FAIL(message)` does the same with a message. A test body may also `return 1` to fail or `return 0` to pass. Tests share process globals but have independent local scopes. Ordinary `transcode`, `compile`, and `run` remove test blocks; `cplus test` extracts them and emits a temporary driver.

Within test bodies, `@assert(condition)` is shorthand for `CPLUS_TEST_ASSERT(condition)`. It always prints the source expression, fixture-wide assertion ordinal, PASS/FAIL, and the given boolean versus expected `true`. `@assertEquals(expected, actual)` captures both runtime expressions once, compares their sizes and object bytes with `memcmp`, and always prints the source expressions and given/expected values. Common scalar and string-pointer values are formatted; other types fall back to `bytes[size]=0x...`. Equality is bytewise, not string-content or deep equality. Use `strcmp` for strings and avoid array operands or structs with indeterminate padding. These annotations are expanded after comptime resolution, when test-local runtime values are in scope; they are not general comptime functions. Test headings are separated by a blank line, numbered in source order as `n/total`, and highlighted yellow; test PASS/FAIL and assertion PASS/FAIL use green/red.

The test command runs all test blocks by default. Exact names after the source paths select tests; source paths may be multiple `.cp`/`.c+` files, with shell-expanded globs supported. A C file can be included unchanged with either `#include "fixture.c"` or `@import("fixture.c")`; `@import` emits a C preprocessor include, while `comptime import` remains reserved for `.cp` and `.c+` modules. During test compilation any source `main` is renamed and is not executed; the generated test driver owns the entry point. A failed assertion is isolated to its test function so later tests still run. The harness remains process-based and does not provide fixtures, setup/teardown hooks, or parallel execution.

`comptime type` functions return exactly one `@code` fragment containing one named `struct` definition. A `comptime typedef generator(args) alias;` invocation turns it into `typedef struct tag { ... } alias;`. Other comptime invocations materialize the runtime entity returned by the function. The legacy `@type` and `@fn` generator forms, legacy `@name(args);` invocations, and scalar `@name` references remain accepted. The keyword-led result kinds are `type`, `function`, `variable`, `string`, and `code`; `@var` and `@fn` are not result-kind spellings in the formal grammar.

Inside source materialized by a `comptime type` or `comptime function` generator, a comptime scalar call embedded in an identifier, such as `list_of_@typename(T)` or `mapper__@name(T)__to__@name(R)`, is an identifier splice. The called comptime function must return a string made only from ASCII identifier characters (`A-Z`, `a-z`, `0-9`, `_`); it is inserted without quotes. The combined name after all splices must be one valid C identifier and not a C keyword. This permits type-name fragments such as `int` when combined with a prefix (`list_of_int`). Materialized output is reparsed; C keyword misuse and ordinary/tag namespace collisions are diagnosed by the C compiler during compilation and mapped to the original C-plus file and line. Other `@code` fragments do not eagerly evaluate nested declarations or embedded names; those declarations remain opaque until a later expansion pass. Outside identifier splices, strings keep their normal quoted C representation. Strings are not parsed as source by themselves; source is introduced explicitly by `@code`.

The Tree-sitter grammar represents these generated names as interpolated-identifier nodes, including when used as a type or declarator name. Its opt-in prototype materializer supports identifier splices that call string-returning comptime functions with generic primitive or named type parameters, including `T.name`.

A comptime `string` is a Unicode text value read from an ordinary, unprefixed source literal. Materialization to an ordinary C string uses UTF-8 bytes on every target; non-ASCII and non-printable bytes are emitted as exact three-digit octal escapes, so the selected C compiler's source and execution character sets cannot reinterpret them. String operands concatenate with comptime `+`. The evaluator handles simple escapes (`\\`, `\"`, `\'`, `\?`, `\a`, `\b`, `\f`, `\n`, `\r`, `\t`, `\v`) and maps unsupported escapes to the literal. Hex, octal, and universal-character escapes are not accepted in this language version. `L`, `u8`, `u`, and `U` prefixed literals are also unsupported and produce `CPLUS_COMPTIME_SCALAR_STRING` at the prefixed literal; UTF-16/32 and wide values must use runtime encoding APIs rather than implicit comptime coercion. This boundary is target-independent.

Splice fragments remain restricted to the ASCII identifier alphabet and materialized output is reparsed. Compile tests verify that C ordinary-name and tag-name collisions are rejected at the original source line; these semantic checks remain the downstream C compiler's responsibility. Integer-returning generic functions can also query `T.size` and `T.align` for primitive C types, primitive typedef aliases, pointer aliases, and supported standard-layout structs on the supported x86_64/arm64 Linux, Windows, and macOS ABI combinations. Unknown properties, unsupported ABI targets, and unsupported type layouts receive mapped diagnostics. The aggregate subset is intentionally limited to ordinary structs with scalar, pointer, nested-struct, and fixed-size array fields; unions, bitfields, flexible arrays, packed/aligned layouts, incomplete fields, and target-specific ABI extensions remain unsupported and fail closed. Production continues through the established evaluator.

For example, a type generator and a code generator can both defer splices until their generated code has been materialized:

```c
comptime string @typename(type T) { return T.name; }

comptime type @make_list(type T) {
    return @code { struct list_of_@typename(T) { T value; }; };
}
comptime typedef make_list(int) list_of_int_t;

comptime code @emit_mapper(type T) {
    return @code { int mapper__@typename(T)(int value) { return value + 1; } };
}
comptime emit_mapper(int);
```

The AST prototype lowers the relevant declarations to C equivalent to:

```c
typedef struct list_of_int__list_of_int_t { int value; } list_of_int_t;
int mapper__int(int value) { return value + 1; }
```

The type parameter in `@typename(T)` is substituted when the enclosing generator expands; the nested scalar call then resolves on a later lowering pass. The generated identifier characters map back to the splice call's source.

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

The declaration and its parameters are comptime-only. `comptime expression` evaluates a supported scalar expression in a runtime expression; `@name` remains an explicit comptime reference spelling. The result must be a literal or another materializable value.

An inline `comptime` marker consumes the surrounding C expression up to its enclosing delimiter. It may begin with a scalar literal, a comptime name/function call, a unary operator, or a parenthesized expression. Both the production scanner and the experimental AST path accept literal-leading and parenthesized-leading forms; evaluator support remains limited to the documented scalar subset below. The shared regression compiles and runs both frontends' output for these forms.

The opt-in Tree-sitter scalar prototype uses arbitrary-precision evaluation internally, then materializes only values representable by the selected C integer type. The supported upper bound is the target's `unsigned long long` maximum (`18446744073709551615` for the supported 64-bit `long long` model), not the JVM/Kotlin signed-64 range. Unsuffixed decimal literals choose the first fitting type from `int`, `long`, and `long long`; unsuffixed non-decimal literals choose from `int`, `unsigned int`, `long`, `unsigned long`, `long long`, and `unsigned long long`. `u`, `l`, `ul`/`lu`, and `ll`/`ull`/`llu` suffixes select the corresponding C candidate sequence. The target model supplies the width of `long`; values beyond every supported unsigned candidate fail closed. A leading sign is evaluated as the unary operator applied to the literal, including modulo behavior for unsigned operands.

Unary integer operators apply the modeled integer promotions before evaluating `+`, `-`, and `~`. Addition, subtraction, multiplication, division, remainder, and bitwise binary operators use the common C integer type; signed results are range-checked and unsigned results wrap modulo their width. Shift operands are promoted independently, the left operand determines the result type, and the count must be in `0..width-1`. Unsigned shifts wrap naturally at the target width; negative signed shift operands and signed right shifts whose result would depend on implementation-defined behavior are rejected. Division and remainder reject zero divisors and the signed `LONG_MIN / -1` overflow case. Logical `&&` and `||` short-circuit, and the conditional operator (`condition ? consequence : alternative`) evaluates only the selected branch. Conditional/logical operands must evaluate to supported integer or boolean scalar values; unsupported operands produce a source-mapped diagnostic instead of being coerced to false. Unsupported operations fail with a source-mapped diagnostic. This remains a bounded subset rather than full C constant-expression conformance: non-integer/pointer casts and unsupported target ABIs remain open; scalar-function argument/result conversion is defined below.

### Comptime blocks

A top-level `comptime { ... }` block executes during precompilation. An expression statement that evaluates to a runtime entity is materialized in place, in source order. The legacy `@ { ... }` block is also accepted:

```c
comptime {
    make_declarations(4);
}
```

Scalar expression statements are evaluated and discarded; to use a scalar result, return it from a comptime function and consume it in another comptime expression. Invalid or unsupported scalar expressions produce a mapped diagnostic rather than being emitted as runtime C. Blocks cannot contain runtime control flow that executes after compilation.

Comptime control flow uses the `@` forms and operates only on comptime values. `@if` supports `@else if` chains and an optional final `@else`; `@for name in value` visits `T.fields` in declaration order. Conditional branches can return comptime entities, and can materialize `comptime flags` declarations for the selected target OS. A comptime loop must have a statically bounded iterable or hit the implementation expansion limit.

### Imports

```c
comptime import "math.cp";
```

Comptime imports are declared at module scope. An import inside a top-level C preprocessor guard (`#if`, `#ifdef`, or an alternative branch) remains at module scope; the same syntax inside a function or runtime compound statement is invalid. Ordinary paths are relative to the importing file. Stable `stdlib:/path` prefixes search the standard-library root selected by the CLI/project. `module:/path` and its `project:/path` alias search project module roots. `.cp` or `.c+` may be omitted and is resolved in that order. Paths are canonicalized, constrained to their configured root for prefixed imports, and loaded once per compilation graph. Imported comptime declarations become available to the importer; materialized runtime declarations are emitted once in dependency order. Comptime imports are not C `#include`s and do not reach the C preprocessor. The legacy `@import` spelling remains accepted.

For unchanged C implementation files, `@import("fixture.c")` instead resolves the path and emits an absolute `#include` directive. The C file is processed by the C preprocessor/compiler rather than the comptime evaluator. `#include "fixture.c"` remains equally valid and is the direct C spelling.

### Compiler flags

Use a top-level `comptime flags` directive to attach compiler/linker arguments to a C-plus translation unit:

```c
comptime import "stdlib:/graphics/raylib.cp";
comptime flags -lGL -lm -lpthread -ldl -lrt -lXrandr -lXinerama -lXcursor -lXi -lraylib;
```

Arguments are split into tokens (quoted arguments may contain spaces); the directive ends at a semicolon or line ending. C comments and backslash-newline continuation are supported. Flags from imported C-plus modules are collected before the importing file's flags, then duplicate logical options are removed while retaining first-seen order; paired arguments such as `-framework Cocoa` remain intact. `compile`, `run`, and `test` pass the collected tokens to TinyCC, whether embedded or externally installed. `transcode` writes one informational `/* cplus compiler flags: ... */` line into generated C; a separate C compiler does not interpret that comment, so pass those flags to it yourself. C has no portable source directive for requesting linker arguments; `#pragma comment(lib, ...)` is a compiler-specific alternative, not C-plus behavior.

The comptime string `os` identifies the selected compile target (`linux`, `windows`, `macos`, other recognized OS names, or `unknown`). It defaults to the machine running C-plus; `compile`/`run` derive it from the passed `--target` option, and `transcode` accepts `--target` to select it without compiling. A target triple such as `windows-x86_64` or `aarch64-apple-darwin` is normalized to its OS. Use conditional branches to select platform-specific flags:

```c
comptime {
    @if (os == "linux") {
        comptime flags -lraylib -lGL -lm -lpthread -ldl -lrt -lX11;
    } @else if (os == "windows") {
        comptime flags -lraylib -lopengl32 -lgdi32 -lwinmm -lshcore;
    } @else if (os == "macos") {
        comptime flags -lraylib -framework Cocoa -framework OpenGL -framework IOKit;
    }
}
```

Only the selected branch is materialized; its flags are collected on the next comptime pass. `os` is compile-time metadata, not runtime detection. The selected compiler/sysroot must still provide the named libraries and frameworks.

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

Reflection cannot inspect a runtime value or call a generated function. Field metadata exposes `.annotations` as an ordered comptime collection of structured annotation values. Each annotation currently has one member, `.name`, containing one of `borrowed`, `owned`, `mut`, `scratch`, `hot`, `warm`, or `cold`. Fields additionally expose `.annotationsText`, a space-separated string projection in declaration order; it is the empty string when no annotations are present. During migration, using `.annotations` in a scalar string position remains a compatibility alias for `.annotationsText`, but new code should use the explicit projection.

The opt-in Tree-sitter prototype currently implements this bounded field-loop form at module scope:

```c
typedef struct user_t {
    int id;
    borrowed char* name;
} user_t;

comptime code @emit_field(type T, string @name, string @annotations) {
    return @code {
        _Static_assert(sizeof(T) > 0, @name);
        _Static_assert(sizeof(@annotations) > 0, "annotation metadata materialized");
    };
}

comptime {
    @for field in user_t.fields {
        comptime emit_field(field.type, field.name, field.annotationsText);
    }
}
```

For this prototype, `.name` materializes as a quoted C string, `.type` as a C type token, and `.annotationsText` as the space-separated string described above. Standard-layout fields additionally expose `.offset`, `.size`, and `.align` as integer tokens computed for the selected target ABI. These values include ordinary C padding and are consistent with the aggregate `T.size` and `T.align` values. A field loop may contain nested loops over independent reflected type fields and over the annotations of a bound field:

```c
comptime {
    @for field in user_t.fields {
        @for annotation in field.annotations {
            comptime emit_annotation(field.name, annotation.name);
        }
    }
}
```

For example, independent loop variables compose without sharing or overwriting bindings:

```c
comptime {
    @for left in left_t.fields {
        @for right in right_t.fields {
            comptime emit_pair(left.name, right.name);
        }
    }
}
```

Each nested loop resolves its iterable from the current lexical bindings. An annotation loop runs once for each recognized annotation in source order; an empty annotation list produces no iterations. `annotation.name` materializes as a quoted string mapped to the annotation token. Unknown annotation members fail with `CPLUS_COMPTIME_ANNOTATION_PROPERTY` at the member span. Generated field and annotation values retain their original source origins.

The bounded iterable protocol deliberately recognizes only type `.fields` and a bound field's `.annotations`. Scalar projections such as `.annotationsText`, arbitrary expressions, and dynamic runtime collections are not iterable and fail at the iterable span. Each loop is limited to 1,024 items and one root expansion is limited to 65,536 total items, so nested products cannot bypass the budget. This is the complete iterable contract for this language version, not a placeholder for implicit iteration over arbitrary C values.

Simple fields, pointer fields, fixed-size array fields (including multidimensional arrays and arrays of pointers), function-pointer fields, and named or unnamed bitfields are supported for metadata, while bitfields are rejected for layout queries. An unnamed bitfield participates in `.fields` and has an empty `.name`. The prototype also provides `.flag` as `0` or `1`, and `.width` as the original C integer-constant-expression tokens; ordinary fields report width `0`. Width tokens preserve their source origins and can be passed as entity-generator scalar arguments when they form an expression supported by the bounded integer/bool evaluator. `.offset`, `.size`, and `.align` fail with `CPLUS_COMPTIME_REFLECTION_LAYOUT` when the target or field shape is not supported. For named fields, the identifier is removed from the parsed C declarator to form its abstract type. Generic type arguments accept C abstract pointer, array, and function declarators when structurally valid in the AST. The loop may be inside a dormant comptime generator and is evaluated only after that generator materializes. Field definitions must be complete and visible in the active source after imports. These restrictions describe the prototype; the production evaluator remains authoritative until frontend promotion.

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

Comptime functions may return runtime C-plus entities. The result kind is itself the entity kind: `type` returns a type, `variable` returns a variable declaration, and `function` returns a C function definition. In particular, `function` is not repeated as a marker before the generated declaration. A `type T` parameter is substituted as a C type name within the returned declaration. The legacy generator declarations `@type` and `@fn` remain accepted for compatibility; the older `return function ...` and `return @fn ...` entity-return spellings are represented separately in the grammar. `@var` is not a keyword-led result kind.

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

### Typed integer declarations (prototype boundary)

The Tree-sitter prototype evaluates with arbitrary precision, then applies the declared integer type when a module-scope comptime value is resolved. Fixed-width signed types (`signed char`, `short`, `int`, `long`, and `long long`, including their standard spelling variants) reject values outside their target range with a mapped `CPLUS_COMPTIME_SCALAR_WIDTH` diagnostic. Unsigned types use C-style modulo conversion within their target width; for example, `-1` declared as `unsigned char` materializes as `255`, and a full-width `unsigned long long` value remains representable above `LONG_MAX`. `_Bool` and `bool` normalize any integer or boolean initializer to `0` or `1`.

`long` is target-dependent in this prototype: it is 64-bit on Linux and macOS targets and 32-bit on Windows targets. The target is selected by the compiler configuration, not by the host running the JVM. Plain `char` uses the supported target model: signed on Linux x86_64, macOS x86_64/arm64, and Windows x86_64/arm64; unsigned on Linux arm64. An unsupported OS/architecture combination fails closed with `CPLUS_COMPTIME_SCALAR_TYPE` instead of guessing the selected compiler's ABI.

```c
comptime unsigned char @byte_value = -1;
comptime bool @enabled = 7;
comptime long @limit = 4294967296;

int main(void) {
    return comptime byte_value != 255 ||
        comptime enabled != 1 ||
        comptime limit != 4294967296 ? 1 : 0;
}
```

For a Linux x86_64 target, the declarations materialize as scalar literals and the program returns zero:

```c
int main(void) {
    return 0 || 0 || 0 ? 1 : 0;
}
```

For a Windows x86_64 target, the `long` declaration above is rejected because `4294967296` does not fit the target's 32-bit signed `long`. The prototype applies the usual integer conversions for the bounded comparison and arithmetic operators: `bool`, `char`, and `short` promote to `int` when representable, and mixed signed/unsigned operands are compared in their common C integer type. For example, `unsigned int maximum = 4294967295` compared with `-1` is false because `-1` converts to `UINT_MAX`, while an `unsigned char` first promotes to `int`. Literal selection follows the radix/suffix rules above, so `0xffffffff + 1` is an unsigned-`int` wrap to zero while decimal `4294967295 + 1` uses a fitting wider signed type. Unary unsigned negation and complement, and target-width unsigned shifts, follow the same model:

Suffix selection also follows the target's `long` width. On Linux/macOS, `0xffffffffUL + 1` uses a 64-bit `unsigned long` and materializes `4294967296`; on Windows, it uses a 32-bit `unsigned long` and materializes `0`. `0xffffffffLL + 1` and `0xffffffffULL + 1` remain `4294967296` on all three supported target families because both `long long` forms are 64-bit. The prototype verifies the `L`, `UL`/`LU`, `LL`, `ULL`/`LLU` spellings across x86_64/arm64 Unix models and the x86_64 Windows model.

The full-width unsigned-long-long boundary is also materializable. Internal arbitrary-precision evaluation preserves the value until the declared C type is applied, so values above `9223372036854775807` do not become negative or fail merely because the evaluator runs on the JVM:

```c
comptime unsigned long long @maximum = 18446744073709551615ULL;
comptime unsigned long long @wrapped = maximum + 1ULL;
comptime unsigned long long @high_bit = 1ULL << 63;

unsigned long long maximum_value = comptime maximum; // 18446744073709551615ULL
unsigned long long wrapped_value = comptime wrapped; // 0ULL
unsigned long long high_bit_value = comptime high_bit; // 9223372036854775808ULL
```

An integer literal beyond `ULLONG_MAX`, or a signed declaration that cannot represent the result, remains a mapped compile-time error.

```c
comptime unsigned int @bits = 0xffffffff;

int main(void) {
    unsigned int a = comptime (bits << 1); // 4294967294
    unsigned int b = comptime (-1u);       // 4294967295
    return a != 4294967294U || b != 4294967295U;
}
```

Signed results are range-checked and unsigned results wrap modulo the common type's width. Integer casts use the target-aware width model: unsigned casts modulo-convert, boolean casts normalize to `0` or `1`, and signed casts outside the modeled range fail with a mapped diagnostic instead of assuming implementation-defined behavior. Pointer, array, function, floating-point, and other non-integer casts remain unsupported and fail closed.

Scalar comptime function calls convert each non-generic integer argument to its declared parameter type before evaluating the function body. Their integer or boolean result is converted to the declared result type afterward. This makes narrowing and unsigned modulo behavior explicit:

```c
comptime bool @nonzero(unsigned char value) {
    return value;
}

int main(void) {
    int result = comptime nonzero(2); // parameter conversion, then bool result: 1
    return result != 1;
}
```

An out-of-range signed parameter or result is rejected with a source-mapped diagnostic; unsigned parameter/result conversions modulo-convert within the declared target width, including the full supported `unsigned long long` domain. This boundary must not be read as full C integer-promotion conformance.

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

The target C compiler evaluates `offsetof`, `sizeof`, and `_Alignof`, keeping the table consistent with the selected ABI. The current runtime-reflection proposal does not yet materialize this table; the experimental compile-time AST prototype has a separate bounded layout model for standard-layout structs. Methods should use a separate explicit table: instance entries include the receiver, static entries do not, and private methods are omitted unless registered.

Useful applications include serializers, generic equality and hashing, debuggers and inspectors, structured logging, plugin registries, FFI schemas, configuration loaders, protocol codecs, and startup schema validation. Runtime reflection must not imply that arbitrary C code can discover or execute every symbol.

## Reliability Rules and Limitations

- **Determinism:** comptime code cannot read the clock, random values, process state, environment variables, or the network. File access is limited to source files reached through `@import`.
- **Sandboxing:** comptime functions cannot call runtime C functions, dereference runtime pointers, execute subprocesses, or mutate compiler-global state.
- **Dependency safety:** imports are canonicalized, evaluated once, and rejected on cycles. Duplicate generated declarations with incompatible definitions are errors.
- **Hygiene:** generated identifiers are collision-checked and derived from the generator and type arguments. A future explicit hygienic name escape must not silently capture user declarations.
- **Bounded evaluation:** the current implementation limits the import graph to 256 modules, comptime calls to 10,000, expansion to 128 passes, and materialized output to 8 MiB. Repeated source states and passes that make no progress are diagnosed; exceeding a limit is a source-mapped comptime error.
- **Target dependence:** the experimental AST prototype evaluates `T.size` and `T.align` for supported primitive scalar types, primitive/pointer typedef aliases, and ordinary standard-layout structs on x86_64/arm64 Linux, Windows, and macOS ABI combinations. It rejects other OS/architectures instead of guessing. Packed/aligned structs, unions, bitfields, flexible arrays, incomplete fields, and target-specific ABI extensions are rejected instead of guessed. ABI reflection remains confined to the opt-in prototype; the production evaluator remains authoritative.
- **No runtime reflection yet:** `@type`, field metadata, and comptime values disappear before C compilation. Runtime inspection currently requires ordinary C-plus data and code.
- **Explicit source fragments:** strings remain values. Only a typed `@code { ... }` fragment is interpreted as C-plus source, and the fragment is reparsed on a later pass.
- **No general AST API yet:** typed fragments, decorators, quote/unquote, and AST visitors are proposed, not implemented.
- **Source mapping:** generated entities retain both their generator span and instantiation span. Diagnostics in generated code point to the instantiation first and can explain the generator origin.
- **Current implementation limits:** scalar comptime functions currently require a single `return` expression; variable/function entity functions return one declaration; `@code` returns one explicitly delimited module fragment; `comptime type` returns one named struct; identifier splices must produce one valid C identifier token; `@for` currently iterates reflected fields and emits one entity expression per iteration. In the experimental AST frontend, imports and generator declarations must occur at file scope; scalar values and invocations may also occur in a top-level comptime expansion block, which does not introduce a separate compile-time symbol scope. Comptime declarations inside runtime functions are unsupported. Unsupported forms produce source-mapped diagnostics rather than being passed to C.
- **Experimental Tree-sitter prototype:** its opt-in transpiler materializes OS equality/inequality conditionals, supported module-scope `comptime` blocks, flags, integer/bool scalar declarations and expressions, returned runtime function/variable/type entities, and returned `comptime code` fragments. Entity generators accept integer/bool expressions supported by the scalar evaluator, including references to earlier comptime values and pure scalar functions; generic function entities and `comptime type` also support primitive or named type-token arguments, qualified pointers such as `const char*` and `struct payload_t*`, multiword types such as `unsigned long`, and AST-validated abstract pointer/array/function type arguments, with mixed scalar/type parameters. Invocation indexing takes arguments from the invocation's own argument list, not nested calls inside expressions. The implemented `comptime type` subset accepts one `@code` fragment containing one named struct and emits its requested typedef alias; multiple specializations receive deterministic distinct C tags, including tags containing identifier splices. Fixed-size, multidimensional, and pointer-array fields, plus function-pointer fields, are reflected through `.type` and covered by generated-C compile/run tests. Comma-separated declarators in generated variable declarations are all included in ordinary-name collision checks. Entity declaration/body text maps to its template; substituted scalar/type tokens and generated typedef wrappers map to their originating arguments/invocation. Unsupported parameter and argument shapes produce mapped diagnostics. Common ordinary-name collisions and file-scope tag collisions across `struct`, `union`, and `enum` are diagnosed; prototype- and block-scope tags do not falsely collide, and a generated struct definition may complete a compatible same-kind forward declaration. Scalar functions may have supported integer/bool result and named parameter types and exactly one return expression; calls can appear inline or in scalar initializers. String-returning scalar functions may be used in parsed identifier splices; fragments are limited to identifier characters and materialized output is reparsed. Compile-failure tests verify ordinary-name and tag-name collisions are reported by the C compiler at the original C-plus file and line. Returned code fragments are reparsed after materialization, so emitted comptime declarations become active on later passes and scalar resolution follows the entity fixed point. Three-stage expansion tests check template/substitution source origins; nested unresolved and recursive generated-invocation tests check mapped diagnostics and termination; generated C-import tests check include behavior. Generated C-plus module imports are also re-expanded: tests cover imports emitted by root code and by an imported module, relative resolution from the originating file, dependency-first order, generator activation from the newly imported module, and mapped cycle diagnostics. A resolvable entity invocation that cannot be materialized fails with a mapped no-progress diagnostic. The prototype enforces a 128-pass bound, has a defensive repeated-source-state guard, caps materialized source at 8 MiB, and has tests for downstream C compiler diagnostics mapped to the originating fragment. A malformed C-plus module imported by generated `@code` produces a parser diagnostic mapped to the dependency and stops emission. Fragment syntax itself is validated while parsing its containing template; structurally valid but invalid-at-file-scope output is diagnosed by the downstream C compiler with mapped origins. This path remains opt-in and does not replace the production evaluator. Raw arbitrary C declarators, broader reflection, structured annotation reflection, and general comptime string expressions remain incomplete. The repeated-state guard is not directly exercised: current recursive generator expansion consumes the invoked declaration and terminates earlier with a mapped unresolved-generator diagnostic. This is a migration milestone, not a change to the language-wide implementation contract.
