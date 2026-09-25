# Error Handling Specification

Status: implemented subset; update with every compiler behavior change.

## Contract

`@throws()` marks an `error_t`-returning function. `@throws(name)` marks a function whose final parameter is a writable `error_t *` named `name`; the function may otherwise return any C value. In both forms, zero means success. The annotation is compiler metadata and is removed before C emission; the signatures and calling convention remain conventional C.

```c
@throws()
pub error_t read_value(borrowed mut int *out) { *out = 42; return 0; }

@throws(error)
pub int make_value(int seed, borrowed mut error_t *error) {
    *error = seed < 0 ? ERROR_INVALID : 0;
    return seed;
}
```

`@throws(name)` requires `name` to occur exactly once as the final pointer parameter and have base type `error_t`. The error-return form must return `error_t`. A prototype may carry the annotation without annotating its definition; when multiple declarations carry annotations, their convention and parameter count must agree.

## Try and catch

```c
@try {
    read_value(&value);
    value = make_value(value);
    use_value(value);
}
@catch (ERROR_INVALID, error_t error) { report(error); }
@catch (error_t error) { recover(error); }
after_try();
```

The final error-output argument is omitted at checked error-out call sites. Catch patterns are one or more C error-code identifiers separated by `|`, followed by a binding declaration (`error_t name`). A sole `error_t name` pattern catches all remaining codes. Catch blocks are tested in source order; a catch-all, when present, is last. The outermost try requires a catch-all; a nested try may omit it and forward unmatched codes to the enclosing handler. The binding is initialized to the actual nonzero status.

The compiler conceptually emits a local status and a shared dispatch:

```c
{
    error_t cplus_status_0 = 0;
    {
        cplus_status_0 = read_value(&value);
        if (cplus_status_0 != 0) goto cplus_catch_0;
        value = make_value(value, &cplus_status_0);
        if (cplus_status_0 != 0) goto cplus_catch_0;
        use_value(value);
    }
    goto cplus_end_0;
cplus_catch_0:
    if (cplus_status_0 == ERROR_INVALID) {
        error_t error = cplus_status_0;
        report(error);
    } else {
        error_t error = cplus_status_0;
        recover(error);
    }
cplus_end_0:
    ;
}
```

Generated identifiers are uniquified and generated control flow is source-mapped to the relevant call or `@try`. Arguments and destinations are evaluated once. Calls with an explicit final error-out argument are left untouched for caller-managed handling. Calls outside a try are also untouched. Error-returning calls are auto-checked only as standalone expression statements; assigning their result explicitly means manual handling.

## Supported subset and diagnostics

Inside a try body, automatic checking supports a whole annotated call expression statement for either convention, plus an error-out call as the entire right-hand side of an assignment or declaration initializer. Calls are matched by their canonical emitted C identifier after method lowering; this is declaration-name lookup, not full C type/scope resolution. Ordinary nested C blocks are supported.

An annotated call in a condition, return expression, nested function argument, comma expression, short-circuit expression, compound right-hand side, or other larger expression is rejected with a source-located diagnostic. The compiler diagnoses missing/misordered catches, malformed catch patterns, bad annotations, and unresolved declared error-call forms. This is a structured statement/call lowering subset, not a general C expression AST.

Nested try bodies are handled inside-out. An unmatched inner status is assigned to the enclosing try and jumps to its dispatch. Statements in a nested catch body are checked by the enclosing try, not by that inner try. Statements in a top-level catch body are outside its try's checking scope; use explicit handling or another try there.

`@try` only transfers control. It does not perform cleanup, unwind resources, or change C `return`, `break`, or `continue` behavior. Use `defer` only with its separately documented end-of-function semantics; it is not automatically run when a try exits early.

## Examples and tests

The executable example is in [`../examples/error_handling.cp`](../examples/error_handling.cp). Compiler/runtime regressions live in [`../../cli/src/test/kotlin/cplus/TranspilerTest.kt`](../../cli/src/test/kotlin/cplus/TranspilerTest.kt). Run `./gradlew test` to validate generated C and diagnostics.

## Tree-sitter migration status

The opt-in `TreeSitterCPlusPrototypeTranspiler` now has AST-backed extraction and signature validation for both `@throws` conventions and AST-based lowering for standalone checked calls, error-out assignment/initializer values, ordered catches, and nested unmatched-error propagation. It resolves annotated methods against the semantic index before receiver lowering. Its tests compile and execute generated C with the host compiler. This is not yet the CLI's production frontend; the legacy compiler remains authoritative while output parity, broader expression cases, source-map comparisons, and platform gates are completed.
