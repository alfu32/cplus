const assert = require("node:assert/strict");
const Module = require("node:module");
const path = require("node:path");

const received = new Map();
class Range {
  constructor(startLine, startCharacter, endLine, endCharacter) {
    this.start = { line: startLine, character: startCharacter };
    this.end = { line: endLine, character: endCharacter };
  }
}
class Diagnostic {
  constructor(range, message, severity) {
    this.range = range;
    this.message = message;
    this.severity = severity;
  }
}

const mockVscode = {
  languages: {
    createDiagnosticCollection: () => ({
      set(uri, diagnostics) { received.set(uri.toString(), diagnostics); },
      delete(uri) { received.delete(uri.toString()); },
      dispose() {}
    })
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
  Range,
  Diagnostic,
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

const output = { append() {}, appendLine() {} };
const server = path.join(__dirname, "diagnostics-server.sh");
const client = new CPlusLspClient(["/bin/sh", server], [], output);

(async () => {
  await client.start();
  const deadline = Date.now() + 3000;
  while (!received.has("file:///diagnostics.cp") && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  const diagnostics = received.get("file:///diagnostics.cp");
  assert.ok(diagnostics, "VS Code adapter should publish received diagnostics");
  assert.deepEqual(diagnostics.map(({ code, severity }) => [code, severity]), [
    ["CPLUS_UNSUPPORTED_AST", mockVscode.DiagnosticSeverity.Warning],
    ["CPLUS_PARSE", mockVscode.DiagnosticSeverity.Error]
  ]);
  client.dispose();
})().catch((error) => {
  client.dispose();
  console.error(error);
  process.exitCode = 1;
});
