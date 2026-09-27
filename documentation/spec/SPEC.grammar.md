# C-plus Grammar and Frontend Proof

Status: phase-1 grammar contract with a phase-2 repository proof. This document
records the syntax accepted by the Tree-sitter C-plus grammar and the syntax that
is present in the repository's live `.cp`/`.c+` sources. Phase 2 validates the
contract against generated-C behavior and the user-facing language specifications;
the young legacy transcoder is evidence, not the grammar authority.

## Authority and scope

The complete ordinary-C grammar is the pinned upstream grammar in
[`parser-tree-sitter/upstream/grammar.js`](../../parser-tree-sitter/upstream/grammar.js).
It supplies C11 declarations, declarators, expressions, statements, preprocessor
directives, GNU/MS extensions, comments, literals, and recovery. C-plus adds the
productions below. The generated `grammar.json`, `src/node-types.json`, and
`src/parser.c` are derived artifacts and must be regenerated from `grammar.js`.

Tree-sitter produces a concrete syntax tree. Semantic validity (for example, a
receiver really being the declared struct type, or a comptime result being a valid
C-plus declaration) belongs to later compiler passes and must produce a mapped
diagnostic rather than being hidden in recovery.

## Lexical additions

The C lexer remains the base lexer. C-plus adds these token forms:

```ebnf
at-identifier       = "@" , identifier ;
access-keyword      = "pub" | "priv" ;
static-keyword      = "static" | "stat" ;
intent-keyword      = "borrowed" | "owned" | "mut" ;
memory-hint         = "scratch" | "hot" | "warm" | "cold" ;
comptime-keyword    = "comptime" ;
```

The annotation and comptime words are recognized only in the productions below;
ordinary C identifiers remain valid wherever the C grammar permits them.

## Translation-unit extensions

The upstream `translation_unit` is extended with these top-level items:

```ebnf
top-level-item       = c-item
                      | method-declaration
                      | comptime-declaration
                      | comptime-type-definition
                      | comptime-block
                      | comptime-conditional
                      | comptime-for
                      | comptime-import
                      | c-import
                      | test-declaration
                      ;

comptime-declaration = comptime-function
                      | legacy-type-generator
                      | legacy-function-generator
                      | legacy-invocation
                      | comptime-import
                      | comptime-flags
                      | comptime-invocation
                      | comptime-value
                      ;
```

The same C-plus declaration forms allowed in a translation unit are available in
comptime bodies where the grammar explicitly admits a block item. A comptime
declaration or invocation inside an ordinary runtime function is a semantic error in
the current frontend even if a recovery tree can be formed. A runtime-scope invocation
must not bind to a module-scope generator; it receives a mapped
`CPLUS_COMPTIME_INVOCATION_SCOPE` diagnostic. Runtime-scope generators, blocks, and
conditionals receive `CPLUS_COMPTIME_DECLARATION_SCOPE`, `CPLUS_COMPTIME_BLOCK_SCOPE`, or
`CPLUS_COMPTIME_CONDITIONAL_SCOPE` respectively; diagnostics are emitted before binding or
materialization.

## Struct methods and annotations

Methods are members of a struct/union field-declaration list, not free functions
that happen to receive a receiver. `self` is an ordinary declarator name and is
required by semantic lowering for instance methods.

```ebnf
method-definition     = [ static-keyword ] access-keyword
                         declaration-specifiers method-declarator
                         ( compound-statement | ";" ) ;
method-declarator     = function-declarator
                      | pointer* function-declarator
                      | array-declarator
                      ;
throws-annotated      = "@throws" , "(" , [ identifier ] , ")" , method-definition ;
function-declaration  = [ throws-annotated ] access-keyword
                         declaration-specifiers method-declarator
                         ( compound-statement | ";" ) ;

parameter             = annotation* type-qualifier* pointer-declarator
                      | annotation+ [ declaration-specifiers ] [ declarator ]
                      | ordinary-C-parameter
                      ;
annotation            = intent-keyword | memory-hint ;
```

`pub`, `priv`, `static`/`stat`, ownership, mutation, and memory hints are retained
as metadata and emitted as empty macros where appropriate. They are not ownership
enforcement. C declarator binding order is preserved: an array of function pointers
is not a pointer to an array, and a pointer to a multidimensional array remains
distinct in the AST.

## Comptime declarations and expressions

The keyword-led forms are:

```ebnf
comptime-function      = "comptime" result-kind "@" identifier
                          "(" comptime-parameter* ")" comptime-body ;
result-kind             = "type" | "function" | "variable" | "string" | "code"
                        | primitive-type | integer-result-type | identifier ;
integer-result-type     = "signed" [ "int" ]
                        | "unsigned" [ "int" ]
                        | "short" [ "int" ]
                        | "signed" "short" [ "int" ]
                        | "unsigned" "short" [ "int" ]
                        | "long" [ "int" ]
                        | "signed" "long" [ "int" ]
                        | "unsigned" "long" [ "int" ]
                        | "long" "long" [ "int" ]
                        | "signed" "long" "long" [ "int" ]
                        | "unsigned" "long" "long" [ "int" ] ;
comptime-parameter      = "type" identifier
                        | declaration-specifiers "@" identifier
                        | ordinary-C-parameter ;
comptime-body           = "{" ( return-declaration | C-block-item )* "}" ;
return-declaration      = "return" function-definition
                        | "return" type-definition
                        | "return" declaration
                        | "return" legacy-returned-function ;

comptime-value          = "comptime" type [ "@" ] identifier [ "=" expression ] ";" ;
comptime-block          = "comptime" compound-statement ;
comptime-import         = "comptime" "import" [ string-literal ] ";" ;
comptime-flags          = "comptime" "flags" flag-token* ";" ;
comptime-invocation     = "comptime" [ "typedef" ] identifier
                          "(" ( expression | type-argument )* ")"
                          [ identifier ] ";" ;
comptime-type-definition= "typedef" at-call-expression identifier ";" ;
type-argument           = type-descriptor ;
inline-comptime        = "comptime" expression ;

at-call-expression      = "@" identifier argument-list ;
type-reference          = "@" identifier ;
code-fragment           = "@code" compound-statement ;
interpolated-identifier = ( identifier | at-call-expression )*
                          at-call-expression
                          ( identifier | at-call-expression )* ;
```

Compatibility forms remain explicit and are parsed separately:

```ebnf
legacy-type-generator   = "@type" [ "@" ] identifier "("
                          ( "type" identifier | "@type" identifier | C-parameter )*
                          ")" compound-statement ;
legacy-function-generator= "@fn" [ "@" ] identifier "("
                          ( "type" identifier | "@type" identifier | C-parameter )*
                          ")" comptime-body ;
legacy-invocation        = "@" identifier argument-list ";" ;
legacy-import            = "@import" [ "(" ] string-literal [ ")" ] [ ";" ] ;
comptime-conditional     = "@if" "(" expression ")" compound-statement
                           ( "@else" [ "if" "(" expression ")" ] compound-statement )* ;
comptime-for             = "@for" identifier "in" expression compound-statement ;
```

At a semantic level, comptime expansion is repeated until no active comptime
construct remains. Generated C-plus is reparsed before C emission. Identifier
interpolation is restricted to valid identifier text; arbitrary source generation
is represented by `@code` and remains source-mapped.

## Tests, defer, and checked errors

```ebnf
test-declaration       = "@test" ( string-literal | identifier ) compound-statement ;
test-assertion         = "@" ( "assert" | "assertEquals" ) argument-list [ ";" ] ;
defer-statement        = "defer" statement ;
try-statement          = "@try" compound-statement catch-clause+ ;
catch-clause           = "@catch" "(" catch-pattern ")" compound-statement ;
catch-pattern          = identifier ( "|" identifier )* "," parameter-declaration
                       | parameter-declaration ;
throws-annotation      = "@throws" "(" [ identifier ] ")" ;
```

`@test` and `@assert` are test-runner syntax. `defer` is lowered at the enclosing
function tail. `@throws`, `@try`, and `@catch` are structured error-propagation
syntax; their lowering validates the annotated declaration and supported call
contexts.

## Phase-1 proof of life

The test `provesLiveCPlusSyntaxNodeInventoryForGrammarProof` parses every C-plus
source below `stdlib/` and `examples/`, rejects diagnostics and recovery nodes,
and freezes the observed inventory. Current evidence is:

- 62 live `.cp`/`.c+` files parse without recovery.
- 37 named `cplus_*` node kinds occur in that live source corpus; the test records
  the exact set so additions/removals are visible.
- The generated Tree-sitter corpus contains 104 focused syntax cases, including
  complex C declarator binding and C-plus method forms.
- Existing AST/prototype tests transcode the repository acceptance sources and
  validate the resulting C with the host C compiler. That generated-C result is
  the phase-2 behavioral proof, not a reason to accept a questionable parse.

The remaining grammar productions (`@code`, `@for`, `@throws`, `@try`/`@catch`,
and some legacy forms) are exercised by focused parser/compiler fixtures outside
the live stdlib/example inventory. Their support is tracked separately from the
62-file proof and must not be inferred merely from a successful recovery parse.

## Phase-2 generated-C proof

The repository proof is deliberately behavioral rather than textual: generated C
is the acceptance artifact, and the legacy transcoder is not treated as a golden
output. `prototypeTranscodesEveryRepositoryCPlusSourceWithoutActiveComptimeSyntax`
transcodes every `.cp`/`.c+` source below `stdlib/` and `examples/`, rejects parser,
lowering, and unsupported-node failures, verifies that active comptime generators
did not leak into the output, and syntax-checks the result as C11 with each locally
available `cc`, `gcc`, or `clang` driver. The focused standard-library/example
fixture gate additionally executes the generated test harnesses.

The current phase-2 audit found no mismatch in that finite repository corpus. This
does not close the broader C compatibility or cross-host gates: a new mismatch must
be classified as documentation, grammar, AST adaptation, lowering, or intentional
unsupported behavior, then receive a minimal fixture and source-mapped evidence.

## Phase-2 mismatch policy

For each mismatch, classify it as a documentation error, grammar error, AST
adapter error, lowering error, or an intentionally unsupported construct. Add a
minimal source fixture, assert the expected syntax tree and mapped origin, then
compile/run the generated C where applicable. Never “fix” a mismatch by copying
legacy textual behavior without checking the generated C semantics. Unsupported
syntax must fail with a mapped diagnostic; silently treating it as ordinary C is
not an acceptable convergence strategy.
