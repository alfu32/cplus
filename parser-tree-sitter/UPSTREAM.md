# Upstream provenance

- Project: [`tree-sitter/tree-sitter-c`](https://github.com/tree-sitter/tree-sitter-c)
- Version/tag: `v0.24.2`
- Pinned commit: `b780e47fc780ddc8da13afa35a3f4ed5c157823d`
- License: MIT; see [`upstream/LICENSE`](upstream/LICENSE).
- Generated with Tree-sitter CLI `0.25.10`, language ABI 15.

## Local changes

`upstream/grammar.js` adds named nodes for access-annotated global functions and struct methods, static methods, `defer`, comptime declarations/blocks/imports/flags/invocations, `@test`, `@throws`, and `@try`/`@catch`. C-plus method/function declarations require `pub` or `priv`; static methods may use `static pub` or `static priv`. Parameter lists additionally accept `borrowed`, `owned`, and `mut` prefixes, including an untyped pointer receiver such as `borrowed mut *self`. Catch alternatives use `|` and catch-all clauses bind an `error_t` parameter.

The added corpus files include `upstream/test/corpus/cplus-methods.txt`, `cplus-defer.txt`, `cplus-comptime.txt`, `cplus-errors.txt`, and `cplus-legacy-function-invocation.txt`. C-plus declaration modifiers include the optional `mut` annotation, and allocator-domain annotations are valid in casts. Parameter rules support const/volatile/restrict qualifiers before either typed parameters or inferred pointer receivers such as `borrowed const *self`. Comptime function bodies have an AST node for generated declarations returned directly from the comptime closure and accept the `code` result kind. The legacy `@fn` generator, its returned-function form and invocation, `@T` type references, nested `comptime code` declarations, and `typedef @generator(T) Alias;` specialization are explicitly represented. No upstream C corpus case was changed. Keep future patches narrow and list each here so rebasing against upstream remains reviewable.

The `@try` and `@catch` keywords are each a single grammar token. Splitting either into `@` plus an identifier let comptime-call/interpolated-identifier rules recover across adjacent handlers; the `cplus-errors` corpus now protects consecutive tries and multiple catches.

Empty `comptime flags` argument lists and missing `comptime import` paths are represented as normal C-plus nodes instead of parser recovery nodes. The compiler backends then issue matching semantic diagnostics (`CPLUS_COMPTIME_FLAGS_EMPTY` and `CPLUS_COMPTIME_IMPORT_PATH_MISSING`) at the terminating semicolon, allowing exact cross-backend code, message, and span comparison.

Missing closing braces for a `comptime` block, `@test` body, or legacy `@type` generator use Tree-sitter's zero-width missing-token span at EOF. The JVM adapter adds the enclosing C-plus construct context, while the legacy scanner reports the same insertion point and shared diagnostic codes/messages.

An invalid bare `type` parameter in a modern comptime function is normalized only when its `ERROR` token lies inside that declaration's parameter header. This shares the legacy parameter diagnostic while avoiding reinterpretation of errors inside the function body.

The Tree-sitter Kotlin/JVM grammar generator expects a flat `tree-sitter-c.h` include, while this upstream revision stores it at `tree_sitter/tree-sitter-c.h`. `upstream/bindings/c/tree-sitter-c.h` is a local include shim only; it includes the untouched upstream header. The grammar name remains `c` so the upstream generated `tree_sitter_c` ABI symbol and existing corpus remain compatible.
