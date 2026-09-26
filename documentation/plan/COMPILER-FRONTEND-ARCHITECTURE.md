# C-plus Compiler Frontend: Target Architecture

Status: architecture plan, not a normative language specification. Existing behavior remains authoritative until a phase is implemented and its acceptance gate passes. Track implementation in [`COMPILER-FRONTEND-IMPLEMENTATION.md`](COMPILER-FRONTEND-IMPLEMENTATION.md).

## Goals and boundaries

The compiler should parse C-plus structurally, retain exact source provenance through every transformation, and continue emitting portable C for the existing TinyCC/system-compiler backends. Parser choice must not leak into language lowering. The old text-based frontend remains usable during migration; no feature is removed merely because a new parser can recognize it.

“Flawless” is not a realistic guarantee. The target is predictable behavior backed by grammar conformance tests, mapped diagnostics, differential migration checks, fuzzing, and measured performance. Tree-sitter is a parser/CST engine, not the semantic AST, type checker, preprocessor, or C backend.

## Target pipeline

```text
project/import graph
  -> immutable source snapshots and coordinate maps
  -> selectable parser backend
  -> lossless C-plus CST with recoverable syntax diagnostics
  -> normalized C-plus AST and symbol/type model
  -> comptime/import expansion to a fixed point
  -> semantic validation and runtime lowering IR
  -> C AST / structured C emission with source map
  -> existing external C compiler (TCC, CC, or configured compiler)
```

Each stage consumes an explicit value and returns an explicit value plus diagnostics. Stages do not discover structure by rewriting arbitrary substrings. Generated syntax is parsed again at the phase boundary where it becomes active. The C compiler remains the authority for C ABI details and final C validity.

## Component specifications and solution sketches

### 1. Source manager and source snapshots

Own canonical source identities, immutable file contents, revisions, import relationships, and encoding/coordinate conversion. A snapshot exposes C-plus UTF-16 offsets used by current Kotlin code, UTF-8 byte offsets used by Tree-sitter, and line/column spans. A parser node span must always map back to a source snapshot; generated text carries origin segments rather than fabricated file positions. Cache a snapshot for unchanged content and increment its revision when content changes. Imports are resolved by a separate policy using project roots, standard-library roots, and relative paths.

Initial implementation adds `SourceId`, `SourceSnapshot`, `SourceCoordinateMap`, and `SourceManager`, reuses the existing `SourceFile` span model, and routes the root input through the manager. It deliberately does not yet replace comptime import loading.

### 2. Frontend backend contract and selection

Expose a narrow compiler API: parse a `SourceSnapshot` under an explicit dialect/target/options value and return a backend-neutral parse result, diagnostics, and backend identity. Backend implementations own lexing where their parser combines lexing and parsing. Selection is explicit (`legacy` or `tree-sitter`) through an internal compiler option, with the current backend as the default until promotion. Do not expose Tree-sitter `Node` or numeric symbol IDs to compiler passes. Keep CLI/parser selection experimental until output behavior is stable.

Migration implementation: `CPlusParserShadowRunner` parses with an authoritative backend and an observational backend in one call. It records coverage, diagnostics, and normalized-node fingerprint differences, plus exact-span parity for the comptime/test subset both implementations recognize, while preserving the authoritative result as-is. Ordinary C/C-plus remains opaque in the legacy adapter, so whole-tree differences are expected and no output or diagnostic policy switches based on this prototype. Broader parity still requires comparison over representative repository sources.

### 3. Tree-sitter C-plus grammar module

Keep the upstream C grammar, license, provenance, pinned revision, generator version, and local changes together in a dedicated module. Extend its grammar to recognize valid C-plus constructs directly: struct-contained methods, annotations, receiver calls, comptime declarations/blocks/invocations, `defer`, checked error calls, and test fixtures. Define named nodes and fields for constructs consumed by later passes. Use external scanners only for lexical state that cannot be expressed cleanly in the grammar. Recovery `ERROR`/missing nodes are diagnostic input and a migration aid, never the normative C-plus syntax boundary.

Check generated parser artifacts into the repository (or make generation reproducible in CI from the pinned toolchain); regular compiler builds must not need a parser generator download. Preserve upstream attribution/license and record the fork point. Build and package the Tree-sitter runtime and language parser for supported hosts, or select a compatible Kotlin/JVM binding after validating Java 21, native loading, and distribution size. This packaging decision is a phase gate, not an assumption.

### 4. CST-to-AST adapter

Translate grammar-specific concrete nodes into stable C-plus AST types with source spans and references to retained syntax/trivia where source-preserving output is useful. Normalize aliases and equivalent syntax once. The initial compiler-facing categories distinguish structs, unions, enums, typedefs, fields, variable declarations, function declarations/definitions, methods, types, control flow, preprocessor directives, and C-plus constructs. Unknown/recovered nodes remain representable and diagnostic-bearing; they must not be silently dropped. A dedicated AST schema and exhaustive adapter tests prevent generated grammar changes from cascading into every pass.

### 5. Module graph, preprocessing, and comptime

Represent source imports as a graph with canonical identities, cycle diagnostics, and deterministic dependency order. Keep ordinary C `#include` semantics delegated to the C compiler. Parse C-plus `comptime import`, resolve comptime definitions, evaluate permitted values, and materialize generated C-plus declarations until no active comptime forms remain. Generated declarations are parsed on the next expansion iteration, with origin segments chained to the generator call/body. The evaluator stays sandboxed and bounded as specified in `SPEC.comptime.md`; it consumes AST nodes rather than rescanning source text.

Current transition: comptime imports still use the established evaluator, but the compilation result now exposes canonical import edges and dependency-first `sourceOrder` metadata via `TranscodedSource`. Runtime emission remains dependency-first as before; this metadata adds a stable API for future IDE graph consumers without changing C output.

### 6. Name resolution and semantic model

Build scopes and symbol tables for C declarations, typedef names, structs, fields, methods, static methods, comptime names, and annotations. Resolve `receiver.method(...)` against declared receiver types and pointer/value forms; inside an instance method, infer an untyped `self` receiver from its enclosing struct. Explicit parenthesized `&value` and `*pointer` receiver expressions are normalized for type lookup; chained field receivers are resolved through declared field types, and already-pointer receivers are passed without adding another address operator. Preserve an explicit distinction between `@throws()` (error-return) and `@throws(name)` (error-out), with the annotated declaration's parameter symbols available for validation and call rewriting. Resolve `@throws` declarations to call sites, validate catch codes/parameters, and retain ownership/access annotations as metadata without enforcing them unless the language specification later changes. C preprocessor expansion is not reimplemented here; unresolved macro-dependent C constructs remain delegated to the C compiler or receive explicitly documented limits.

Initial semantic support also records function-pointer typedefs as callable signatures, preserving the base result type, parameter names/base types and original parameter declaration spellings, variadic marker, and declarator span. The typedef symbol retains its exact original declaration text so pointer/qualifier placement is not lost while deeper declarator normalization is incomplete. Callback signatures nested in function-pointer parameters and returned-function-pointer composition are indexed recursively. The opt-in allocation analyzer validates known provenance for annotated global/method parameters and function/method returns, plus owned double-pointer outputs, and carries explicit annotated call-return domains into surrounding arguments. It also infers value provenance through assignment results and the final operand of comma expressions, but does not model their nested side effects or short-circuit execution. It does not yet validate function-pointer assignments or all qualified/deep declarator compatibility.

### 7. Lowering IR and ordered transformations

Use typed, source-spanned runtime IR (or a normalized AST with explicit lowered nodes) for C-plus-only behavior. Lower in named, testable passes: implicit receiver insertion/method names; `defer`; `try`/`catch`; test harness extraction; and optional allocation diagnostics. Each pass declares prerequisites and invariants, returns diagnostics, and preserves source origins. The transition prototype extracts/validates `@throws` metadata and AST-lowers standalone checked calls plus error-out assignment/initializer values through nested ordered catches. It also has an opt-in AST allocation-intent analyzer for annotated-variable mismatches, direct allocator calls, simple initializer aliases, standalone direct identifier assignments, annotated global-function parameter and return checks (combining prototype/definition contracts), owned double-pointer output writes, resolved instance/static method parameter checks, and annotated method return checks using known allocator/parameter provenance. Explicit call-return domains propagate to surrounding argument checks. An `if/else` join retains provenance only when both paths agree (a missing `else` is the unchanged incoming path), and a conditional expression has known provenance only when both value arms agree; divergent branches, loops, and short-circuit assignments remain unknown. Larger checked-call expressions fail with mapped diagnostics. Comptime syntax is absent before runtime lowering. Avoid serial string replacements for semantics or control flow.

### 8. C representation, emitter, maps, and diagnostics

Emit C from structured nodes with a mapped writer. The output map records generated byte/line ranges to original snapshots and spans, including generated text with multiple contributing origins. Origin lookups use half-open character offsets; the legal EOF boundary has no character origin and must return `null` rather than indexing past the buffer. Continue emitting `#line` for compiler compatibility, but use the explicit map to normalize compiler diagnostics precisely. Keep diagnostic severity, code, related spans, and notes independent of terminal formatting. Preserve the public `TranscodedSource`/CLI behavior as a compatibility facade while its internals migrate.

### 9. IDE and tooling consumers

Use the parser contract and shared node names for indexing, outline, navigation, completion, test discovery, and diagnostics. `cplus parse file.cp [-o ast.json]` exposes the versioned normalized result as a first integration bridge. Keep editor-specific presentation adapters separate. VS Code can update trees incrementally; IntelliJ can consume snapshots/results through its platform APIs; Vim may use CLI JSON diagnostics or a future language server. Editor features must degrade gracefully on recovered/incomplete syntax.

### 10. Verification and performance

Maintain a corpus covering valid C, every documented C-plus construct, C preprocessor directives, imports, Unicode, malformed/incomplete editor buffers, and generated source. Compare legacy and new backends on accepted source, symbols, emitted C, diagnostics, and source maps. Add grammar corpus tests, transformation golden tests, property/fuzz tests, cross-platform native-loading tests, and cold/warm parse benchmarks. Performance budgets are measured before parser promotion; incremental speed alone does not justify slower or less correct batch compilation.

## Migration and component lifetime

| Component | Introduced / transition | Lifetime and replacement condition |
|---|---|---|
| Existing `SourceFile` and `MappedText` | Retained in phase 0; wrapped by source snapshots | `SourceFile` becomes a compatibility view in phase 7. `MappedText` expires only after every text-only pass is retired and all origin-map tests pass; replaced by source-spanned nodes plus mapped emitter segments. |
| Existing comptime scanners/evaluator | Legacy backend during phases 0–4 | Retire scanner parsing only when the Tree-sitter AST handles all normative comptime forms, fixed-point expansion and import tests pass, and output/diagnostics match the legacy corpus. The evaluator's sandbox and semantics remain; its input representation changes. |
| Existing textual `DeferLowerer` | Legacy lowering in phases 0–5 | Replace during phase 6 with `DeferLoweringPass` over function/block nodes. Delete only after early-return, nesting, ordering, comments, and source-map regression suites pass. |
| Existing method/type lowerers | Legacy lowering in phases 0–5 | Replace during phase 6 with resolved method-call and struct-method AST passes. Delete after receiver inference, static/instance distinctions, and emitted-C compile tests match. |
| Existing error annotation/try-catch lowerers | Legacy lowering in phases 0–5 | Replace during phase 6 with resolved-call/control-flow lowering. Delete after nested handlers, explicit error arguments, exact-once evaluation, and mapped diagnostics pass. |
| Existing allocation analyzer | Used through phase 5 | Reimplement as semantic-model traversal in phase 6; remove old scanner once annotation/provenance diagnostics are equivalent. Ownership stays advisory. |
| Existing `MappedEmitter` | Used as compatibility output throughout migration | Its API survives; its text input implementation is replaced in phase 7 by structured emission. Keep `#line` until external compiler diagnostics are verified against explicit maps on all supported platforms. |
| Legacy frontend selector/backend | Default in phases 0–6; differential mode in phase 7 | Deprecated when the new backend passes all promotion gates. Remove no sooner than two stable releases with the new backend default, a documented rollback path, and no unresolved corpus differences. |
| Tree-sitter runtime/parser | Prototype in phase 2; opt-in through phase 6 | Long-lived parser backend, replaceable behind the frontend contract. Upgrade only with pinned generator/runtime compatibility and full corpus/benchmark validation. |
| CLI/IDE compatibility facades | Kept during all phases | Long-lived public surface; delegate to the selected frontend. Change only through documented compatibility policy. |

## First transition step

Implement the source manager and coordinate map, without switching parsing or altering generated C. It gives both frontends one source identity/revision model and establishes the UTF-8 byte ↔ Kotlin UTF-16 mapping needed by Tree-sitter and editors. Gate completion on ASCII and multibyte round trips, rejection of offsets inside encoded code points, stable unchanged snapshots, revision changes on edits, and the full existing CLI test suite. Then add the backend contract and a legacy adapter as the next separately reviewable step.
