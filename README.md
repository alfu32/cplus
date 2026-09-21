# C-plus

C-plus is a small Kotlin command-line processor that lowers C-plus source (`.cp` or `.c+`) to ordinary C. C-plus keeps C's expressions and statements, adding struct-scoped methods, method-call syntax, and ownership/visibility annotations.

## Build and run

The repository intentionally uses the Kotlin compiler directly so it does not require a Gradle installation:

```sh
make build
make test
java -jar build/cplus.jar examples/basic.cp > build/basic.c
```

The CLI also accepts `-o output.c input.cp`.

## Current lowering rules

- `typedef struct name_t { ... } name_t;` method definitions and declarations are moved outside the struct.
- An instance method whose first parameter is `*self` receives an implicit `name_t *self` parameter.
- Methods become `name__method(...)`; for example, `(&value).reset()` becomes `name__reset(&value)`.
- `name_t.method(...)` becomes the corresponding static method call.
- `pub`, `priv`, `mut`, `borrowed`, `owned`, and `stat` are annotation markers and are removed from generated C. A `static` method remains a C `static` function.

The receiver type is inferred from declarations such as `name_t value;` or `name_t *value;`. Unknown receivers are left unchanged so ordinary C remains valid.

Comptime and generic declarations beginning with `@` are reserved by the syntax, but are not expanded yet; the processor reports a focused error instead of emitting invalid C. The intended implementation boundary is a first pass that resolves those declarations and a second pass that runs the normal C-plus lowering.
