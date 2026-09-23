const assert = require("node:assert/strict");
const { indexText } = require("../out/index.js");

const source = [
  "typedef struct counter_t {",
  "  int value;",
  "  pub int add(borrowed mut *self, int amount) { return 0; }",
  "} counter_t;",
  "#define mapper__int__to__string mapper_int_string",
  "counter_t counter;",
  "int @answer = 21;"
].join("\n");
const index = indexText(source);

assert.equal(index.fieldsByType.get("counter_t")[0].name, "value");
assert.equal(index.methodsByType.get("counter_t")[0].name, "add");
assert.equal(index.variableTypes.get("counter"), "counter_t");
assert.ok(index.byName.has("@answer"));
assert.equal(index.byName.get("mapper_int_string")[0].kind, "function");
assert.match(index.byName.get("mapper_int_string")[0].detail, /C preprocessor alias for mapper__int__to__string/);
