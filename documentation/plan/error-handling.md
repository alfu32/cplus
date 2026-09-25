# Error Handling Implementation Plan

## Goal

Lower C-plus `@throws`, `@try`, and `@catch` syntax to ordinary C while preserving the original function ABI, exactly-once evaluation, lexical scopes, and source-map origins.

## Initial language contract

- `@throws()` marks an error-returning function. Its return type is `error_t`; zero is success.
- `@throws(error)` marks an error-out function. `error` must be the final writable `error_t *` parameter; zero is success.
- A try body checks annotated calls that form a complete expression statement, assignment RHS, or declaration initializer. The first nonzero status leaves the body and enters ordered catch dispatch.
- For error-out calls, the compiler supplies the omitted final parameter. An explicitly supplied final error argument opts into manual checking.
- Catch alternatives use `|`; the outermost try requires a catch-all, while nested tries may propagate unmatched codes outward. Catch bindings are scoped to their own catch body.
- Generated status names and labels are unique per source transformation. Each original argument and destination expression is evaluated once.
- Calls nested in larger expressions, implicit conversion of nonzero return values, cleanup/unwinding, and C++-style exception behavior are out of scope. Unsupported annotated-call contexts inside `@try` are diagnosed.

## Pipeline and work sequence

1. Document the syntax, ABI invariants, supported statement forms, limitations, and generated-C examples.
2. Scan declarations for `@throws`, validate annotation/signature consistency, strip only the annotation text, and retain source-mapped metadata. Translate struct method names to their eventual lowered C identifiers.
3. Parse try/catch blocks structurally with delimiter matching rather than regular-expression substitution. Validate catch grammar and exact try/catch pairing.
4. Resolve annotated call names against collected declarations. Rewrite only supported complete statement shapes, injecting error-out arguments where applicable and emitting one shared catch dispatch per try.
5. Preserve mapped origins on copied source and anchor generated control flow at the `@try` location.
6. Add transpilation, diagnostic, source-map, compile/run, and test-harness regression coverage.

## Verification

- Unit-level generated-C checks for both conventions, ordered alternatives, catch-all, nested tries/ordinary blocks, method calls, explicit error arguments, and hygienic identifiers.
- Negative checks for malformed signatures/catches and unsupported expression contexts, asserting `.cp` source locations.
- Native compile-and-run tests to prove early transfer, skipped later statements, continued execution after a catch, and exactly-once evaluation.
- Run the compiler test suite and the standard-library suite after implementation.

## Follow-up boundary

Supporting annotated calls in conditions, return expressions, arguments, or short-circuit expressions requires a full expression/statement AST with symbol resolution and must be a separate extension, not a textual rewrite.
