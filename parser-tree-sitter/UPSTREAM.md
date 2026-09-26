# Upstream provenance

- Project: [`tree-sitter/tree-sitter-c`](https://github.com/tree-sitter/tree-sitter-c)
- Version/tag: `v0.24.2`
- Pinned commit: `b780e47fc780ddc8da13afa35a3f4ed5c157823d`
- License: MIT; see [`upstream/LICENSE`](upstream/LICENSE).
- Generated with Tree-sitter CLI `0.25.10`, language ABI 15.

## Local changes

`upstream/grammar.js` adds named nodes for access-annotated global functions and struct methods, static methods, `defer`, comptime declarations/blocks/imports/flags/invocations, `@test`, `@throws`, and `@try`/`@catch`. C-plus method/function declarations require `pub` or `priv`; static methods may use `static pub` or `static priv`. Parameter lists additionally accept `borrowed`, `owned`, and `mut` prefixes, including an untyped pointer receiver such as `borrowed mut *self`. Catch alternatives use `|` and catch-all clauses bind an `error_t` parameter.

The added corpus files are `upstream/test/corpus/cplus-methods.txt`, `cplus-defer.txt`, `cplus-comptime.txt`, and `cplus-errors.txt`. C-plus declaration modifiers include the optional `mut` annotation, and allocator-domain annotations are valid in casts. Parameter rules support const/volatile/restrict qualifiers before either typed parameters or inferred pointer receivers such as `borrowed const *self`. Comptime function bodies have an AST node for generated declarations returned directly from the comptime closure and accept the `code` result kind. The legacy `@fn` generator and returned-function form, `@T` type references, nested `comptime code` declarations, and `typedef @generator(T) Alias;` specialization are explicitly represented. No upstream C corpus case was changed. Keep future patches narrow and list each here so rebasing against upstream remains reviewable.

The `@try` and `@catch` keywords are each a single grammar token. Splitting either into `@` plus an identifier let comptime-call/interpolated-identifier rules recover across adjacent handlers; the `cplus-errors` corpus now protects consecutive tries and multiple catches.

The Tree-sitter Kotlin/JVM grammar generator expects a flat `tree-sitter-c.h` include, while this upstream revision stores it at `tree_sitter/tree-sitter-c.h`. `upstream/bindings/c/tree-sitter-c.h` is a local include shim only; it includes the untouched upstream header. The grammar name remains `c` so the upstream generated `tree_sitter_c` ABI symbol and existing corpus remain compatible.
