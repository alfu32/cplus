# C-plus Documentation

The `spec/` directory contains the living specifications:

- [`SPEC.language.md`](spec/SPEC.language.md) describes C-plus syntax and generated C semantics.
- [`SPEC.comptime.md`](spec/SPEC.comptime.md) defines comptime, imports, generics, reflection, and materialization.
- [`SPEC.errors.md`](spec/SPEC.errors.md) specifies `@throws` annotations and `@try`/`@catch` lowering.
- [`SPEC.compiler.md`](spec/SPEC.compiler.md) describes the CLI, diagnostics, passes, and build layout.
- [`../stdlib/README.md`](../stdlib/README.md) is the consolidated user guide and API catalog for every module currently in `stdlib/`.
- [`spec/stdlib/`](spec/stdlib/) contains deeper subsystem specifications for allocators, collections, and strings.

These files are living specifications and must be updated in the same change as any language or compiler behavior change.

Implementation plans and design decisions are recorded under [`plan/`](plan/).
