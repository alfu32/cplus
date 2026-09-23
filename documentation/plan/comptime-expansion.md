# Comptime Expansion Plan

## Goal

Resolve comptime declarations emitted by other comptime functions before C-plus lowering. Keep the existing `@type`, `@fn`, scalar, import, and comptime-block behavior, and add an explicit way for a generator to return a multi-declaration C-plus fragment.

## Processing Model

Each module is processed through repeated structured top-level parses:

1. Parse the current mapped source and register comptime declarations and imports.
2. Evaluate active comptime calls and blocks, replacing them with mapped C-plus output.
3. Parse the output again so declarations emitted by the previous step become active.
4. Stop when no active comptime syntax remains, then hand the result to the existing C-plus lowering passes.

Generator bodies remain templates during parsing. A returned `@code { ... }` fragment is inserted as source and becomes eligible for parsing on the next pass. The comptime environment and imported modules persist between passes. Source origins pass through every mapped-text splice.

## Safety and Scope

Bound expansion to 128 passes, 10,000 comptime calls, 8 MiB of generated source, and the existing 256-module import limit. Diagnose repeated source states, no-progress expansion, unresolved comptime syntax, duplicate definitions, and malformed fragments at their mapped source locations. This plan covers active module-level declarations and declarations inside explicit comptime blocks; comptime declarations inside ordinary runtime function bodies and a public AST transformation API remain separate work.

## Acceptance Checks

- A generated `@code` fragment can declare another comptime generator, invoke it in a comptime block, and materialize its generated type on a later pass.
- Generated imports register compile-time values and contribute mapped runtime declarations to the module output.
- Existing generic struct and function generation, scalar evaluation, imports, and source mappings continue to work.
- A comptime error introduced by generated source maps to its originating C-plus source.
- Non-converging expansion and unresolved `@` syntax fail with source-mapped diagnostics before C lowering.
