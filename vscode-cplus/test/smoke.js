const assert = require("node:assert/strict");
const { indexText, memberContext } = require("../out/index.js");
const { resolveVersion } = require("../scripts/package.js");

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
