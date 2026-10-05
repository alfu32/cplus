# Scoped imports specification

Status: planned implementation, with the syntax/parser/compiler slice being implemented first.

## Goal

Give an imported C-plus module a local source-level namespace without changing the C ABI or requiring a runtime module object:

```c
@import "stdlib:c/stdio.cp" as local_stdio;

int main(void) {
    local_stdio.printf("asdf %d\\n", 42);
    return 0;
}
```

The import is compile-time. It loads the module, contributes its declarations and compiler flags exactly as an ordinary module import does, and makes the alias available only in the importing source file. The alias is not a runtime variable and does not allocate or link a module object.

## Syntax

```text
scoped-import ::= "@import" import-path "as" identifier [";"]
import-path  ::= string-literal
```

Paths use the existing double-quoted string-literal syntax. The canonical spelling is a quoted path followed by `as alias` and a semicolon.

The existing forms remain valid:

```c
comptime import "stdlib:/strings/string.cp";
@import("fixture.c");
```

`@import("file.c")` remains a C source import. A scoped alias is valid only for a C-plus module (`.cp` or `.c+`); C files do not acquire a C-plus namespace.

## Semantics

1. The path is resolved with the existing relative, `stdlib:/`, `module:/`, and project-root policy. The spelling `stdlib:c/...` is normalized as an accepted compatibility alias for `stdlib:/...`.
2. The imported module is parsed, expanded, cycle-checked, and emitted once in dependency order.
3. The alias belongs to the importing file only. It is not exported transitively and is not visible in another file that imports the same module.
4. `alias.member` is a qualified reference to a declaration provided by that module. The first implementation lowers the qualification to the existing C symbol because C-plus modules currently emit their public declarations with their source names.
5. An alias cannot be declared twice in one file, cannot shadow a comptime binding in the same module namespace, and must be a valid C identifier.
6. A member qualification is not a runtime field access. The alias must be the left side of a dotted identifier and the member must be an identifier. Pointer/member operators, dynamic member names, and arbitrary expressions are not part of the first syntax slice.
7. Existing unqualified imports remain source-compatible during migration. A future strict-module mode may require qualification, but it is not part of this change.
8. Errors are mapped to the import or qualified-use span. Missing modules, invalid aliases, duplicate aliases, cycles, and unsupported C-file aliasing are compiler diagnostics.

## Components affected

- Tree-sitter grammar and generated parser artifacts: recognize quoted scoped imports and expose the alias field.
- Legacy comptime parser: recognize the same syntax for the compatibility frontend.
- Comptime module model: store import aliases per importing module, validate them, and apply qualified-reference lowering before C emission.
- Import resolver: accept the documented `stdlib:c/` compatibility spelling without weakening root confinement.
- AST/index/semantic tooling: retain import alias/path fields so LSP completion, definition, hover, and diagnostics can use the same namespace.
- LSP: index aliases in the open-document import closure and offer member completion/definition where the imported module is available.
- CLI/compiler documentation: specify syntax, scope, ABI behavior, and migration rules.
- Tests: grammar, legacy parser, transcode, compile/run, import graph, diagnostics, and LSP fixtures.

## Explicit non-goals for the first release

- Renaming or prefixing emitted C symbols.
- Runtime module objects, dynamic loading, or package initialization.
- Visibility enforcement beyond the alias namespace.
- C-header aliasing or automatic wrapping of arbitrary C declarations.
- Wildcard imports, nested namespace chains, `alias->member`, or alias expressions.

## Progressive implementation plan

Each step is intended to be a similar-sized, independently testable change.

| Step | Status | Scope | Completion evidence |
| --- | --- | --- | --- |
| SI-1 | DONE | Freeze syntax, semantics, affected components, diagnostics, and migration constraints | This specification is committed and examples are explicit |
| SI-2 | DONE | Extend Tree-sitter and legacy grammar surfaces for aliases | Parser tests produce a named alias node and reject malformed forms |
| SI-3 | DONE | Extend compiler import data and resolver validation | Duplicate/invalid/C-import alias diagnostics pass |
| SI-4 | DONE | Lower qualified references and preserve dependency-first emission | A scoped module transcodes through both frontend paths |
| SI-5 | OPEN | Update AST/import graph JSON and compiler metadata | Alias/path data is stable and import graph tests pass |
| SI-6 | DONE | Add LSP namespace indexing, completion, definition, and hover support | Open-document scoped import fixture passes over stdio |
| SI-7 | OPEN | Add standard-library/example migration samples and documentation | Guide/spec/examples describe the final behavior |
| SI-8 | OPEN | Full regression and cross-host validation | CLI, parser, stdlib, examples, and CI host gates pass |

## Progress log

| Date | Steps | Result |
| --- | --- | --- |
| 2026-10-05 | SI-1 | Syntax and first-slice semantics frozen; paths are double-quoted only. |
| 2026-10-05 | SI-2 | Tree-sitter grammar regenerated; grammar corpus and highlighting checks pass. |
| 2026-10-05 | SI-3 | Legacy compiler parses aliases, rejects duplicates, preserves C-import separation, and normalizes `stdlib:c/`. |
| 2026-10-05 | SI-4 | Legacy and explicit Tree-sitter pipelines lower `alias.member`; focused CLI regression passes. |
| 2026-10-05 | SI-6 | LSP indexes alias symbols, resolves `alias.` completion, and maps member hover/definition to the imported document. |

## Compatibility and retirement

The unqualified `comptime import` form remains indefinitely. The legacy frontend remains authoritative for existing source until SI-8 has passed on all supported hosts. No old import syntax is removed by scoped imports.
