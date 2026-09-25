# C-plus Tree-sitter Grammar

This module vendors the pinned Tree-sitter C grammar and adds explicit C-plus syntax for access-annotated functions and methods, static methods, ownership/mutation-annotated parameters, comptime declarations and invocations, imports, test fixtures, `defer`, `@throws`, and `@try`/`@catch`. The neutral AST preserves these nodes and the compiler can index comptime forms and annotation metadata. The legacy compiler remains the production parser/lowerer until grammar coverage, native packaging, AST parity, and compiler behavior gates are complete.

## Layout

- `upstream/grammar.js` is the upstream C grammar plus the small C-plus extension.
- `upstream/src/` contains checked-in generated parser sources.
- `upstream/test/corpus/cplus-*.txt` exercises the extensions; other corpus files protect ordinary C parsing.
- `UPSTREAM.md` records the upstream pin, license, and local patch boundaries.

## Regenerate and test

Use Tree-sitter CLI 0.25.x (ABI 15):

```sh
cd parser-tree-sitter/upstream
tree-sitter generate --abi 15
tree-sitter test
```

Generated parser files are committed so downstream builds do not need the CLI. The Kotlin/JVM adapter uses KTreeSitter 0.25.1. Gradle builds the grammar JNI library for the current host; Linux x86_64/JDK 21 is currently tested. The full upstream-plus-extension corpus currently has 89 passing cases. Other JVM host binaries must be added and tested before enabling this backend in distributed compiler builds; see the compiler frontend implementation follow-up.

## Experimental transpilation slice

`TreeSitterCPlusPrototypeTranspiler` composes parser-backed lowering for named `@test` fixture extraction, `@throws` declaration extraction, statement-oriented `@try`/`@catch`, direct function-body `defer`, explicitly resolved value/pointer/static method calls, and struct method extraction. Test names, compound bodies, and declaration spans are retained as source-mapped fixture data; fixture declarations are removed before ordinary C lowering. `CPlusTranspiler.transpileExtractedTests` can turn these extracted fixtures into the existing runnable harness without invoking legacy fixture discovery; assertion recognition still uses the established scanner. The prototype reparses between structural transformations, keeps a mapped output, adds the empty annotation macros, and fails closed on comptime and C-plus import syntax. The CLI and production `CPlusTranspiler` do not use this prototype.

For example, `counter.increment(2)` on a `counter_t` value becomes `counter__increment(&counter, 2)`, while `counter_ptr->increment(2)` becomes `counter__increment(counter_ptr, 2)`. Struct methods are emitted as ordinary functions with a typed `self` pointer. Header normalization currently reuses the legacy method routine inside the exact AST method span, so this is a migration slice, not a replacement frontend.

The integration tests run the prototype on `.cp` fixtures, compile them with host `cc` when available, and execute them to verify value, pointer, static receiver, annotated-function, checked-call, and nested catch-propagation behavior. Both `@throws` conventions are extracted and signature-checked. Checked calls currently support standalone statements or error-out assignment/initializer values, with one checked call per statement; larger expressions receive diagnostics. Diagnostics produced after intermediate reparses are mapped back through the pass output map to the original `.cp` source. The prototype intentionally does not claim parity for all C-plus syntax.

For editor and tooling adapters, `CPlusParseJson.encode(result)` emits normalized nodes and recovery diagnostics using the versioned `cplus.parse.v1` schema. All offsets and columns currently follow the compiler's UTF-16 source-span convention; consumers should check `schema` and `offsetEncoding` before interpreting fields. This is a serialization API only: IDE plugins do not yet launch or embed this parser.
