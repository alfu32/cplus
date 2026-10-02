const assert = require("node:assert/strict");
const Module = require("node:module");
const { commandEnvironment } = require("../out/environment.js");

assert.deepEqual(commandEnvironment({ PATH: '"$PATH:/tools"', CC: "clang", ROOT: "${PATH}/root" }, { PATH: "/usr/bin" }),
  { PATH: "/usr/bin:/tools", CC: "clang", ROOT: "/usr/bin:/tools/root" });
assert.equal(commandEnvironment({ PATH: "%PATH%;C:\\tools" }, { PATH: "C:\\Windows" }).PATH, "C:\\Windows;C:\\tools");
assert.deepEqual(commandEnvironment({ PATH: "%PATH%;C:\\tools" }, { Path: "C:\\Windows" }, true),
  { Path: "C:\\Windows;C:\\tools" });
assert.throws(() => commandEnvironment({ "INVALID NAME": "value" }, {}));

const diagnostics = new Map();
class Range { constructor(a, b, c, d) { this.start = { line: a, character: b }; this.end = { line: c, character: d }; } }
class Location { constructor(uri, range) { this.uri = uri; this.range = range; } }
class Symbol { constructor(name, detail, kind, range, selectionRange) { Object.assign(this, { name, detail, kind, range, selectionRange }); } }
const vscode = {
  languages: { createDiagnosticCollection: () => ({ set(uri, value) { diagnostics.set(uri.toString(), value); }, clear() { diagnostics.clear(); }, delete() {}, dispose() {} }) },
  workspace: { textDocuments: [], workspaceFolders: [] },
  Uri: { parse: (uri) => ({ toString: () => uri }) },
  Range, Location,
  DocumentHighlight: class { constructor(range, kind) { Object.assign(this, { range, kind }); } },
  SymbolInformation: Symbol, DocumentSymbol: Symbol,
  CompletionItem: class { constructor(label, kind) { Object.assign(this, { label, kind }); } },
  CompletionList: class { constructor(items) { this.items = items; } },
  CompletionItemKind: { Text: 0 }, SymbolKind: { Namespace: 2 },
  SignatureHelp: class {}, SignatureInformation: class { constructor(label) { this.label = label; } },
  ParameterInformation: class { constructor(label) { this.label = label; } },
  FoldingRange: class { constructor(start, end, kind) { Object.assign(this, { start, end, kind }); } },
  FoldingRangeKind: { Region: 3, Comment: 1 },
  CodeLens: class { constructor(range, command) { Object.assign(this, { range, command }); } },
  SemanticTokens: class { constructor(data) { this.data = data; } },
  Diagnostic: class {}, DiagnosticSeverity: { Error: 0, Warning: 1 }
};
const originalLoad = Module._load;
Module._load = function(name, ...rest) { return name === "vscode" ? vscode : originalLoad.call(this, name, ...rest); };
const { CPlusLspClient } = require("../out/lspClient.js");
Module._load = originalLoad;

const output = { append() {}, appendLine() {} };
const range = { start: { line: 2, character: 3 }, end: { line: 2, character: 6 } };
const document = { uri: vscode.Uri.parse("file:///tooling.cp") };
const client = new CPlusLspClient(["unused"], [], output, {}, 25);
client.started = true;
client.child = { stdin: { write() {} }, kill() {} };
function respond(result) { client.receive(JSON.stringify({ jsonrpc: "2.0", id: client.nextId - 1, result })); }

(async () => {
  let promise = client.completion(document, { line: 2, character: 3 });
  respond({ items: [{ label: "field", kind: 5 }, { label: "struct_t", kind: 22 }] });
  assert.deepEqual((await promise).items.map((item) => item.kind), [4, 21]);

  promise = client.documentSymbols(document);
  respond([{ name: "value", kind: 13, range, selectionRange: range, children: [] }]);
  assert.equal((await promise)[0].kind, 12);

  promise = client.highlights(document, { line: 2, character: 3 });
  respond([{ range, kind: 3 }]);
  assert.equal((await promise)[0].kind, 2);

  promise = client.signatureHelp(document, { line: 2, character: 3 });
  respond({ signatures: [{ label: "int add(int a)", parameters: [{ label: "int a" }] }], activeSignature: 0, activeParameter: 0 });
  assert.equal((await promise).signatures[0].parameters[0].label, "int a");

  promise = client.semanticTokens(document);
  respond({ data: [0, 12, 7, 0, 1] });
  assert.deepEqual(Array.from((await promise).data), [0, 12, 7, 0, 1]);

  const timeout = client.request("textDocument/hover", {});
  await assert.rejects(timeout, /timed out/);
  assert.equal(client.pending.size, 0);

  let cancel;
  const token = { isCancellationRequested: false, onCancellationRequested(callback) { cancel = callback; return { dispose() {} }; } };
  const cancelled = client.request("textDocument/references", {}, token);
  cancel();
  await assert.rejects(cancelled, /cancelled/);
  assert.equal(client.pending.size, 0);
  client.dispose();
})().catch((error) => { client.dispose(); console.error(error); process.exitCode = 1; });
