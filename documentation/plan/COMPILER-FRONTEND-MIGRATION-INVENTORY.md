# Compiler Frontend Migration Inventory

This inventory is the C5 migration boundary. It names every production entry point
that still depends on the textual compiler and the AST replacement that must become
authoritative before that entry point is retired. The legacy implementation remains
the default and is a supported rollback path.

## Selection boundary

`CPlusParserService` already selects `legacy` or `tree-sitter` for normalized parsing.
The CLI now has the same explicit compilation switch for ordinary programs:

```sh
cpc transcode source.cp --frontend=tree-sitter -o build/source.c
cpc compile source.cp --frontend=tree-sitter -o build/source
cpc run source.cp --frontend=legacy
```

`legacy` is the default. Tree-sitter failures are mapped to the original C-plus
span and stop before C compilation. The selector is intentionally at the whole
compilation boundary first. Inside the AST prototype, `TreeSitterPassSelection`
also records the runtime pass IDs that actually ran and allows focused rollback
tests to disable one pass; it does not yet pretend that a legacy implementation is
available as a per-pass substitute.

Test extraction is a separate mode boundary: `TreeSitterPassSelection.extractTests`
and `CPlusLegacyPassSelection.extractTests` independently control whether `@test`
fixtures are consumed. Disabling extraction fails closed at the original fixture
span; it never emits an unresolved test declaration as if it were C. This keeps the
runtime pass inventory honest while making legacy and AST test-mode rollback
behavior explicit.

## Entry-point inventory

| Entry point                      | Current owner                                    | AST replacement                                           | Status / retirement gate                                                                    |
|----------------------------------|--------------------------------------------------|-----------------------------------------------------------|---------------------------------------------------------------------------------------------|
| `ComptimeCompiler.compile`       | `compiler/Comptime.kt` textual scanner/evaluator | `TreeSitterCPlusPrototypeTranspiler` comptime passes      | AST path is selectable; retire after fixed-point and import differential (`CF-16`, `CF-17`) |
| `CPlusTranspiler.transpile`      | legacy comptime plus textual runtime lowerers    | prototype AST pipeline plus mapped C emitter              | legacy default; compare through `CF-14`, then migrate per pass                              |
| `CPlusTranspiler.transpileTests` | textual test discovery/harness extraction        | `CPlusTestExtractionPass` plus AST-lowered fixture bridge | runtime AST extraction exists; test CLI migration is `SP44-03`                              |
| `StructTypeCollector`            | textual struct-name discovery                    | `CPlusSemanticAnalyzer` / AST symbol index                | replacement exists for AST path; remove only after receiver differential                    |
| `MethodCallLowerer`              | masked-text receiver rewrite                     | `CPlusMethodCallLoweringPass`                             | AST replacement covered by overlap fixtures; retirement is `CF-19`                          |
| `DeferLowerer`                   | brace/statement scanner                          | `CPlusDeferLoweringPass`                                  | AST replacement covered; retirement is `CF-19`                                              |
| `StructLowerer`                  | textual method extraction                        | `CPlusStructMethodLoweringPass`                           | AST replacement covered; retirement is `CF-19`                                              |
| `TryCatchLowerer`                | textual checked-call rewrite                     | `CPlusTryCatchLoweringPass`                               | AST replacement covered; retirement is `CF-20`                                              |
| `ErrorAnnotationCollector`       | textual `@throws` scan                           | `CPlusThrowsLoweringPass`                                 | AST replacement covered; retirement is `CF-20`                                              |
| `TestProgram` / assertion scan   | textual test/assertion bridge                    | `CPlusTestExtractionPass` plus harness emitter            | bridge remains during test migration; retirement is `CF-20`                                 |
| `AllocationIntentAnalyzer`       | mapped-text flow scan                            | `TreeSitterAllocationIntentAnalyzer`                      | AST analyzer is available; sequencing/flow parity remains `CF-18`                           |
| `MappedEmitter`                  | shared mapped C emission                         | `CPlusAstCEmitter` followed by `MappedEmitter`            | retained as the common source-map/output facade                                             |

## Rules for migration

1. A selector or adapter is not a retirement decision. The legacy path stays
   available until its named overlap and rollback tests pass.
2. A textual helper may remain as a compatibility bridge only when its input is
   already AST-lowered and its ownership is documented (currently test harness
   generation).
3. New AST-only syntax gets normative generated-C and diagnostic tests; it does not
   get added to the legacy overlap corpus merely to make the two frontends look alike.
4. Each retired entry point needs a feature toggle, one negative/failure case, one
   source-map case, and a repository acceptance run before removal.

## Next transition

The inventory and first selector are complete. `TreeSitterPassSelection` now makes
the AST runtime pass set explicit. `CPlusLegacyPassSelection` now exposes the
corresponding legacy pass set; disabled passes fail closed, and
`TranscodedSource.frontendPasses` plus the AST trace make pass order observable.
The identifiers are centralized in `CPlusFrontendPassIds`, so selector and trace
contracts cannot drift through duplicated literals.
`TreeSitterCPlusDifferentialRunner` compares normalized C tokens, compiler options,
allocation diagnostics, import order, and pass inventory; it also reports pass-order
equality separately. Allocation diagnostics are compared structurally with normalized
source revisions, while symbol inventories remain frontend-specific until their
legacy scanner semantics are replaced. Both frontend results also expose the subset of
transformation passes that changed mapped source, allowing overlap fixtures to prove
that a pass was exercised instead of merely configured.
Its `compareTests` mode separately compares legacy and AST fixture names and assertion
counts, ordered assertion source lines, and normalized generated `cplus_test_N`
function-body tokens for the bounded supported fixture-body corpus. Formatting,
comments, and `#line` directives are excluded from the token contract. It materializes the AST fixture list
through `emitExtractedTestHarness`; unsupported fixture-body forms remain a separate
mapped-diagnostic and retirement gate.
The reusable differential report also checks bounded source-map coverage: the AST map
must retain every original source file/line represented by the legacy map, while finer
columns and additional generated mappings may differ.
`comparePass` adds a pass-exercised report for representative sources: it requires the
selected transformation to change both full outputs and combines that result with the
disabled-pass fail-closed probe. This is evidence for a pass corpus, not permission to
retire the corresponding legacy lowerer. The current throws, defer, struct-method, and
try/catch representatives all satisfy this strict bounded parity check.
The migration-only `comparePassSubstitution` narrows the enabled set to one lowerer
plus documented prerequisites, then compares normalized output, compiler options,
source-line mapping coverage, and actual pass exercise. Receiver-call lowering keeps
struct-method lowering as its explicit semantic prerequisite.
Analysis-only validation uses `compareSemanticPass` instead: it compares allocation
diagnostic signatures and the same rollback contract without treating “no source edit”
as a failed transformation. Its current mismatch fixture is covered for both frontends.
`compareSemanticPassSubstitution` narrows the enabled set to validation alone, retaining
the diagnostic contract while checking generated C/options and source-line mapping.
The bounded validation corpus now covers divergent conditional joins, equal-domain
`while` fixed points, and required-first-iteration `do` loops, compiling and
running both generated outputs. One known migration difference remains explicit:
the legacy scanner can warn about a possibly skipped `while`-body assignment while
the AST flow solver correctly retains the incoming zero-iteration domain. This is
tracked as a legacy diagnostic limitation, not copied into AST semantics.
The legacy and AST pipelines currently retain different dependency-safe runtime
orders, and `frontendPassOrderMatch` makes that fact explicit rather than hiding it.
The next implementation unit is `CF-17`/`SP62-06`:
execute one equivalent legacy/AST substitute at a time, compare its mapped
output/diagnostics/runtime, and retain a reversible fallback. Scanner retirement
remains prohibited until those per-pass gates close.

The rollback probe now exercises all six known pass IDs in both selectors on a
neutral C source. That proves switch plumbing and fail-closed omission only; it
does not prove that a legacy pass and its AST counterpart have equivalent output
or diagnostics for a source that exercises that pass.
Runtime method/defer/try/test lowerers follow only after the report can prove a
pass-specific rollback and parity result.

Pass-specific rollback gates are now executable for throws extraction, defer, try/catch,
struct methods, receiver calls, and AST test extraction: disabling each AST pass retains
its C-plus node, maps it to the original source span, and emits no C. The legacy selector
remains available for the runtime sources, so migration can revert without silently
accepting invalid C. These are rollback boundaries, not independent substitute-equivalence
claims; test extraction is intentionally a separate test-mode switch.
The AST allocation analyzer also honors the `validate-semantics` selector, and its
rollback fixture verifies that advisory allocation diagnostics are absent when that
pass is disabled, matching the legacy selector boundary. The legacy comptime
compiler now applies the independent `extractTests` selector before materializing
fixtures, with a mapped diagnostic when extraction is disabled.
