const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { indexText, memberContext } = require("../out/index.js");
const builtins = require("../out/builtins.js");
const { resolveVersion } = require("../scripts/package.js");

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
