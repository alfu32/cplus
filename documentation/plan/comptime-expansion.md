# Comptime Syntax and Expansion Plan

## Goal

Introduce a clearer keyword-led comptime syntax while retaining existing sigil-led forms for compatibility. Support compile-time-only declarations, explicit invocation sites, inline scalar evaluation, named type generation from `@code`, and validated identifier interpolation. Preserve repeated mapped expansion before C-plus lowering.

## Processing Model

Each module is processed through repeated structured top-level parses:

1. Parse the current mapped source and register comptime declarations and imports.
2. Evaluate active comptime calls and blocks, replacing them with mapped C-plus output.
3. Parse the output again so declarations emitted by the previous step become active.
4. Stop when no active comptime syntax remains, then hand the result to the existing C-plus lowering passes.

The preferred syntax has `comptime` mark declarations, invocations, imports, and blocks. A declaration uses `comptime <result-kind> @name(...)`; `type T` marks a type-valued parameter and `comptime type` marks a type generator. `comptime` at a runtime expression evaluates a scalar expression, while `@name` remains an explicit comptime reference. Existing `@`-prefixed grammar continues to work.

Generator bodies remain templates during parsing. A returned `@code { ... }` fragment is inserted as source and becomes eligible for parsing on the next pass. In a named struct type result, embedded `@function(...)` calls may splice only identifier-safe strings into the generated identifier. Other comptime declarations remain inert until the fragment is emitted, then are resolved on a later pass. The comptime environment and imports persist between passes; source origins pass through every mapped-text splice.

## Safety and Scope

Bound expansion to 128 passes, 10,000 comptime calls, 8 MiB of generated source, and the existing 256-module import limit. Diagnose repeated source states, no-progress expansion, unresolved comptime syntax, duplicate definitions, malformed type fragments, and invalid identifier splices at mapped source locations. This plan covers active module-level declarations and comptime invocation statements inside explicit comptime blocks; declarations inside ordinary runtime function bodies and a public AST transformation API remain separate work.

## Acceptance Checks

- A generated `@code` fragment can declare another comptime generator, invoke it in a comptime block, and materialize its generated type on a later pass.
- Keyword-led declarations, imports, blocks, invocations, and inline scalar expressions parse and materialize without changing legacy syntax behavior.
- A `comptime type` generator can return one named struct in `@code`, splice a reflected type name into its tag, and typedef it under the requested alias.
- Identifier splices insert valid string tokens without quotes and reject invalid C identifiers; ordinary scalar strings remain quoted in runtime expressions.
- Generated imports register compile-time values and contribute mapped runtime declarations to the module output.
- Existing generic struct and function generation, scalar evaluation, imports, and source mappings continue to work.
- A comptime error introduced by generated source maps to its originating C-plus source.
- Non-converging expansion and unresolved `@` syntax fail with source-mapped diagnostics before C lowering.
