# String Library Implementation Plan

Status: completed.

## Goal

Add an owning C-plus `string` standard-library type that presents an OOP facade over `<string.h>`, remains directly interoperable with NUL-terminated C strings, and adds indentation helpers required by source/code-processing workloads.

## Work plan

1. Inspect the language/compiler specifications, stdlib style, annotations, test harness, and CLI behavior.
2. Define the `string` representation and invariants: owned buffer, logical length, capacity, terminating NUL.
3. Define a small `error_t` contract without colliding with a future shared error module.
4. Implement lifecycle, capacity, assignment, append, observation, and destruction methods.
5. Map string-oriented `<string.h>` functions to receiver methods.
6. Map raw memory/string utility functions to static methods where receiver semantics would be artificial.
7. Implement `indent`, `dedent`, and static `trim_indent` with explicit line/space semantics.
8. Harden overlap/alias behavior so a `string` can safely consume its own buffer or substring during assignment/append/transform operations.
9. Add unit tests for normal operations, boundaries, null validation, empty strings, self-aliasing, blank lines, indentation, and every exposed `<string.h>` facade family.
10. Add an executable example and update standard-library documentation.
11. Run the new tests, then the entire existing stdlib test suite, then compile/run the example with the shipped `c-plus.jar`.

## Compiler-specific implementation note

The current lowering pass does not rewrite receiver calls made through the implicit method receiver inside another method (for example `self->reserve(...)`). Internal implementation calls therefore use their generated C names such as `string__reserve(self, ...)`. Public callers still use normal C-plus receiver syntax (`value.reserve(...)`).
