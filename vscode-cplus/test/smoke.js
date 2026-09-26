const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { indexText, memberContext, symbolsFromAst } = require("../out/index.js");
const builtins = require("../out/builtins.js");
const { resolveVersion } = require("../scripts/package.js");
const { findTestFixtures, findTestFixturesFromAst } = require("../out/tests.js");
const { decodeImportGraph } = require("../out/importGraph.js");

for (const value of ["pub", "priv", "mut", "borrowed", "owned", "stat", "scratch", "hot", "warm", "cold"]) {
  assert.ok(builtins.cplusAnnotations.includes(value), `missing annotation completion: ${value}`);
}
for (const value of ["@import", "@if", "@else", "@for", "@type", "@var", "@fn", "@code", "@test", "@assert", "@assertEquals", "@throws", "@try", "@catch"]) {
  assert.ok(builtins.comptimeAtForms.includes(value), `missing comptime completion: ${value}`);
}
for (const value of ["comptime", "defer", "type", "variable", "function", "code", "test", "var", "fn", "flags"]) {
  assert.ok(builtins.cplusKeywords.includes(value), `missing C-plus keyword completion: ${value}`);
}
for (const value of ["flags", "os"]) {
  assert.ok(builtins.comptimeForms.includes(value) || builtins.comptimeValues.includes(value), `missing comptime builtin: ${value}`);
}
for (const value of ["name", "size", "align", "fields", "type"]) {
  assert.ok(builtins.comptimeProperties.includes(value), `missing comptime reflection property: ${value}`);
}
for (const value of ["CPLUS_TEST_ASSERT", "CPLUS_TEST_ASSERT_EQUALS", "CPLUS_TEST_FAIL"]) {
  assert.ok(builtins.builtinTestMacros.includes(value), `missing test macro completion: ${value}`);
}

const grammar = JSON.parse(readFileSync("syntaxes/cplus.tmLanguage.json", "utf8"));
const grammarText = JSON.stringify(grammar);
for (const value of ["assertEquals", "CPLUS_TEST_ASSERT_EQUALS", "comptime\\\\s+flags", "variable.language.comptime.cplus", "comptime.property.cplus"]) {
  assert.ok(grammarText.includes(value), `missing syntax scope/pattern: ${value}`);
}

const packageManifest = JSON.parse(readFileSync("package.json", "utf8"));
const cplusIconTheme = packageManifest.contributes.iconThemes.find((theme) => theme.id === "cplus-file-icons");
assert.ok(cplusIconTheme, "C-plus file icon theme must be contributed");
const iconTheme = JSON.parse(readFileSync("icons/cplus-icon-theme.json", "utf8"));
assert.equal(iconTheme.fileExtensions.cp, "_cplus");
assert.equal(iconTheme.fileExtensions["c+"], "_cplus");

assert.equal(resolveVersion({ CPLUS_RELEASE_VERSION: "2.3.4" }), "2.3.4");
assert.equal(resolveVersion({ CPLUS_RELEASE_VERSION: "  " }), resolveVersion({}));

const source = [
  "typedef struct counter_t {",
  "  int value;",
  "  pub int add(borrowed mut *self, int amount) { return 0; }",
  "  static pub counter_t* create(int initial);",
  "} counter_t;",
  "#define mapper__int__to__string mapper_int_string",
  "counter_t counter;",
  "counter_t* counter_ptr = &counter;",
  "int @answer = 21;"
].join("\n");
const index = indexText(source);

assert.equal(index.fieldsByType.get("counter_t")[0].name, "value");
assert.equal(index.methodsByType.get("counter_t")[0].name, "add");
assert.equal(index.variableTypes.get("counter"), "counter_t");
assert.equal(index.variableTypes.get("counter_ptr"), "counter_t");
assert.ok(index.pointerVariables.has("counter_ptr"));
assert.deepEqual(memberContext(index, "counter.add"), {
  receiver: "counter",
  type: "counter_t",
  operator: ".",
  isPointer: false,
  isStatic: false
});
assert.equal(memberContext(index, "counter_ptr->add")?.type, "counter_t");
assert.equal(memberContext(index, "counter_ptr.add"), undefined);
assert.equal(memberContext(index, "counter_t.create")?.isStatic, true);
assert.ok(index.byName.has("@answer"));
assert.equal(index.byName.get("mapper_int_string")[0].kind, "function");
assert.match(index.byName.get("mapper_int_string")[0].detail, /C preprocessor alias for mapper__int__to__string/);

const astSource = "typedef struct counter_t { int value; pub int add(*self) { return 0; } static pub counter_t* create(void); } counter_t;\nint answer(void) { return 42; }";
const astSpan = (text) => {
  const startOffset = astSource.indexOf(text);
  assert.notEqual(startOffset, -1, `AST fixture text not found: ${text}`);
  return { startOffset, endOffset: startOffset + text.length };
};
const astNode = (kind, syntaxKind, field, text, children = []) => ({ kind, syntaxKind, field, span: astSpan(text), children });
const declaratorName = (name, syntaxKind = "identifier") => astNode("identifier", syntaxKind, "declarator", name);
const ast = {
  kind: "translation_unit",
  span: { startOffset: 0, endOffset: astSource.length },
  children: [
    astNode("type_alias", "type_definition", null, "typedef struct counter_t { int value; pub int add(*self) { return 0; } static pub counter_t* create(void); } counter_t;", [
      astNode("struct_declaration", "struct_specifier", "type", "struct counter_t { int value; pub int add(*self) { return 0; } static pub counter_t* create(void); }", [
        astNode("identifier", "type_identifier", "name", "counter_t"),
        astNode("other", "field_declaration_list", "body", "{ int value; pub int add(*self) { return 0; } static pub counter_t* create(void); }", [
          astNode("field_declaration", "field_declaration", null, "int value;", [declaratorName("value", "field_identifier")]),
          astNode("method_declaration", "cplus_method_definition", null, "pub int add(*self) { return 0; }", [
            astNode("other", "cplus_method_declarator", "declarator", "add(*self)", [declaratorName("add")])
          ]),
          astNode("method_declaration", "cplus_method_definition", null, "static pub counter_t* create(void);", [
            astNode("other", "cplus_static_modifier", null, "static"),
            astNode("other", "cplus_method_declarator", "declarator", "create(void)", [declaratorName("create")])
          ])
        ])
      ]),
      astNode("identifier", "type_identifier", "declarator", "counter_t")
    ]),
    astNode("function_declaration", "function_definition", null, "int answer(void) { return 42; }", [
      astNode("other", "function_declarator", "declarator", "answer(void)", [declaratorName("answer")])
    ])
  ]
};
const astSymbols = symbolsFromAst(astSource, ast);
assert.deepEqual(astSymbols.map(({ name, kind }) => [name, kind]), [["counter_t", "type"], ["answer", "function"]]);
assert.deepEqual(astSymbols[0].children.map(({ name, kind, isStatic }) => [name, kind, isStatic]), [
  ["value", "field", undefined], ["add", "method", false], ["create", "method", true]
]);
assert.equal(astSource.slice(astSymbols[0].children[1].start, astSymbols[0].children[1].end), "add");

const fixtureSource = `
@test "nested fixture" {
  const char *brace = "}";
  /* braces { } in comments are ignored */
  if (1) { run(); }
}
@test plain C fixture { run(); }
`;
const fixtures = findTestFixtures(fixtureSource);
assert.deepEqual(fixtures.map(({ name }) => name), ["nested fixture", "plain C fixture"]);
assert.equal(fixtureSource.slice(fixtures[0].start, fixtures[0].end).includes("if (1) { run(); }"), true);
assert.ok(fixtures[0].end < fixtures[1].start);
const astFixtureSource = String.raw`@test "quoted \"fixture\"" { }`;
const fixtureNameStart = astFixtureSource.indexOf('"');
const fixtureNameEnd = astFixtureSource.indexOf('" {', fixtureNameStart) + 1;
const astFixtures = findTestFixturesFromAst(astFixtureSource, {
  kind: "translation_unit",
  syntaxKind: "translation_unit",
  span: { startOffset: 0, endOffset: astFixtureSource.length },
  children: [{
    kind: "test",
    syntaxKind: "cplus_test_declaration",
    span: { startOffset: 0, endOffset: astFixtureSource.length },
    children: [{
      kind: "literal",
      syntaxKind: "string_literal",
      span: { startOffset: fixtureNameStart, endOffset: fixtureNameEnd }
    }]
  }]
});
assert.deepEqual(astFixtures, [{ name: 'quoted "fixture"', start: 0, end: astFixtureSource.length }]);
assert.deepEqual(astFixtures, findTestFixtures(astFixtureSource), "AST and fallback fixture discovery should agree");
const importGraph = decodeImportGraph({
  schema: "cplus.imports.v1",
  dependencyOrder: ["/project/lib.cp", "/project/main.cp"],
  imports: [{ importer: "/project/main.cp", imported: "/project/lib.cp", location: { startLine: 1, startColumn: 1 } }]
});
assert.deepEqual(importGraph.imports.map(({ imported }) => imported), ["/project/lib.cp"]);
assert.throws(() => decodeImportGraph({ schema: "cplus.imports.v0" }), /unsupported/);
