const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const Module = require("node:module");

const mockVscode = {
  languages: {
    createDiagnosticCollection: () => ({ set() {}, delete() {}, dispose() {} })
  },
  workspace: {
    workspaceFolders: [],
    textDocuments: [],
    onDidOpenTextDocument: () => ({ dispose() {} }),
    onDidChangeTextDocument: () => ({ dispose() {} }),
    onDidCloseTextDocument: () => ({ dispose() {} })
  },
  Uri: { parse: (value) => ({ toString: () => value }) },
  Position: class Position {},
  Range: class Range {},
  Diagnostic: class Diagnostic {},
  DiagnosticSeverity: { Error: 0, Warning: 1 },
  MarkdownString: class MarkdownString {},
  CompletionItem: class CompletionItem {},
  CompletionList: class CompletionList {},
  CompletionItemKind: { Keyword: 17, Function: 3 },
  DocumentSymbol: class DocumentSymbol {},
  SymbolKind: { Namespace: 1 }
};

const originalLoad = Module._load;
Module._load = function(request, parent, isMain) {
  if (request === "vscode") return mockVscode;
  return originalLoad.call(this, request, parent, isMain);
};

const { CPlusLspClient } = require("../out/lspClient.js");
Module._load = originalLoad;

const statePath = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "cplus-vscode-restart-")), "starts");
const server = path.join(__dirname, "restart-server.sh");
const output = { append() {}, appendLine() {} };
const client = new CPlusLspClient(["/bin/sh", server], [statePath], output);

async function waitFor(predicate, timeout = 5000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error("timed out waiting for VS Code client restart fixture");
}

(async () => {
  await client.start();
  await waitFor(() => Number(fs.readFileSync(statePath, "utf8")) >= 2);
  await new Promise((resolve) => setTimeout(resolve, 500));
  assert.equal(Number(fs.readFileSync(statePath, "utf8")), 2, "automatic restart must be bounded to one attempt");

  await client.restart();
  await waitFor(() => Number(fs.readFileSync(statePath, "utf8")) >= 3);
  assert.equal(Number(fs.readFileSync(statePath, "utf8")), 3, "explicit restart must reset the recovery window");
  client.dispose();
})().catch((error) => {
  client.dispose();
  console.error(error);
  process.exitCode = 1;
});
