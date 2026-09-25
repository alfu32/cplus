# C-plus Tree-sitter Grammar

This module vendors the pinned Tree-sitter C grammar and adds explicit C-plus syntax for access-annotated functions and methods, static methods, ownership/mutation and allocator-domain annotations, legacy `@type` generators, comptime declarations and invocations, generated identifier splices, platform-conditioned `@if` chains, imports, test fixtures, `defer`, `@throws`, and `@try`/`@catch`. The neutral AST preserves these nodes and the compiler can index comptime forms and annotation metadata. The legacy compiler remains the production parser/lowerer until grammar coverage, native packaging, AST parity, and compiler behavior gates are complete.

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

Generated parser files are committed so downstream builds do not need the CLI. The Kotlin/JVM adapter uses KTreeSitter 0.25.1. Gradle builds and packages the grammar JNI library under the current host's OS/architecture resource path. The Linux x86_64 JVM jar is about 149 KiB and contains a 1,283,272-byte native library; parsing through JNI is tested on Temurin 21. CI is configured to exercise Linux/macOS/Windows on x86_64 and arm64, but those remote host checks remain required before distributing this parser as a runtime. The full upstream-plus-extension corpus currently has 90 passing cases. See the compiler frontend implementation follow-up for the migration gates.

## Experimental transpilation slice

`TreeSitterCPlusPrototypeTranspiler` composes parser-backed lowering for named `@test` fixture extraction, `@throws` declaration extraction, statement-oriented `@try`/`@catch`, direct function-body `defer`, explicitly resolved value/pointer/static method calls, and struct method extraction. Test names, compound bodies, declaration spans, and AST-indexed `@assert` / `@assertEquals` invocations are retained as source-mapped fixture data; fixture declarations are removed before ordinary C lowering. `CPlusTranspiler.transpileExtractedTests` converts these fixtures to the existing runnable harness without invoking legacy fixture or assertion scanning. The legacy comptime fixture route still uses its scanner. The prototype reparses between structural transformations, keeps a mapped output, adds the empty annotation macros, and fails closed on comptime and C-plus import syntax. The CLI and production `CPlusTranspiler` do not use this prototype.

Successful prototype results also expose `transcodedSource`, the standard compiler-facing `TranscodedSource` generated with `MappedEmitter` and `#line` directives. Its regression compiles and runs the mapped C and verifies that the emitted method implementation maps to its originating `.cp` method line. This is mapped emission over the prototype's source-preserving AST edits, not yet a general AST-node-to-C pretty-printer; production transpilation remains on the legacy emitter.

For example, `counter.increment(2)` on a `counter_t` value becomes `counter__increment(&counter, 2)`, while `counter_ptr->increment(2)` becomes `counter__increment(counter_ptr, 2)`. Struct methods are emitted as ordinary functions with a typed `self` pointer. Supported method headers (access/static modifiers, return declarator prefixes, and remaining parameter spans) are assembled from grammar nodes; unrecognized declarators fail closed. This is a migration slice, not a replacement frontend.

The integration tests run the prototype on `.cp` fixtures, compile them with host `cc` when available, and execute them to verify value, pointer, static receiver, annotated-function, checked-call, and nested catch-propagation behavior. Both `@throws` conventions are extracted and signature-checked. Checked calls currently support standalone statements or error-out assignment/initializer values, with one checked call per statement; larger expressions receive diagnostics. AST `defer` collection includes nested control-flow scopes and appends each deferred statement group to its owning function's end in reverse declaration order. Diagnostics produced after intermediate reparses are mapped back through the pass output map to the original `.cp` source. The prototype intentionally does not claim parity for all C-plus syntax.

The JVM parser suite also parses representative standard-library modules (collections, strings, thread pool, HTTP, xmem, and container tests) without recovery nodes. Coverage includes type-valued comptime invocation arguments, identifier interpolation in generated type/function names, file-scope access annotations, allocator-domain annotations, and `@assert` / `@assertEquals` statements with optional semicolons. This is a representative syntax gate, not a claim of complete C or C-plus grammar parity.

For editor and tooling adapters, `CPlusParseJson.encode(result)` emits normalized nodes and recovery diagnostics using the versioned `cplus.parse.v1` schema. All offsets and columns currently follow the compiler's UTF-16 source-span convention; consumers should check `schema` and `offsetEncoding` before interpreting fields. This is a serialization API only: IDE plugins do not yet launch or embed this parser.
