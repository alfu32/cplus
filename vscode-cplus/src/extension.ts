import * as vscode from "vscode";

const annotations = ["pub", "priv", "mut", "borrowed", "owned", "stat"];

type MethodInfo = { name: string; typeName: string; isStatic: boolean };

function methodsIn(document: vscode.TextDocument): MethodInfo[] {
  const result: MethodInfo[] = [];
  const structs = /typedef\s+struct\s+([A-Za-z_]\w*)\s*\{([\s\S]*?)\}\s*([A-Za-z_]\w*)\s*;/g;
  for (const match of document.getText().matchAll(structs)) {
    const typeName = match[3];
    const body = match[2];
    const methods = /\b(static\s+)?(?:pub|priv)?\s*[A-Za-z_]\w*(?:\s*\*)?\s+([A-Za-z_]\w*)\s*\(/g;
    for (const method of body.matchAll(methods)) {
      result.push({ name: method[2], typeName, isStatic: Boolean(method[1]) });
    }
  }
  return result;
}

class CPlusCompletionProvider implements vscode.CompletionItemProvider {
  provideCompletionItems(document: vscode.TextDocument, position: vscode.Position): vscode.CompletionItem[] {
    const line = document.lineAt(position.line).text.slice(0, position.character);
    const items = annotations.map((word) => {
      const item = new vscode.CompletionItem(word, vscode.CompletionItemKind.Keyword);
      item.detail = "C-plus annotation";
      return item;
    });

    for (const method of methodsIn(document)) {
      const item = new vscode.CompletionItem(method.name, vscode.CompletionItemKind.Method);
      item.detail = `${method.typeName} method${method.isStatic ? " (static)" : ""}`;
      item.insertText = method.name;
      items.push(item);
    }

    if (line.endsWith("@")) {
      const comptime = new vscode.CompletionItem("comptime", vscode.CompletionItemKind.Keyword);
      comptime.detail = "Reserved C-plus comptime syntax";
      items.push(comptime);
    }
    return items;
  }
}

class CPlusHoverProvider implements vscode.HoverProvider {
  provideHover(document: vscode.TextDocument, position: vscode.Position): vscode.Hover | undefined {
    const range = document.getWordRangeAtPosition(position, /[A-Za-z_]\w*/);
    if (!range) return undefined;
    const word = document.getText(range);
    if (annotations.includes(word)) {
      return new vscode.Hover(`${word}: optional C-plus source annotation`);
    }
    const method = methodsIn(document).find((candidate) => candidate.name === word);
    if (!method) return undefined;
    return new vscode.Hover(`${method.typeName}::${word} → ${method.typeName.replace(/_t$/, "")}__${word}()`);
  }
}

class CPlusDocumentSymbolProvider implements vscode.DocumentSymbolProvider {
  provideDocumentSymbols(document: vscode.TextDocument): vscode.DocumentSymbol[] {
    const symbols: vscode.DocumentSymbol[] = [];
    const text = document.getText();
    const structs = /typedef\s+struct\s+([A-Za-z_]\w*)\s*\{/g;
    for (const match of text.matchAll(structs)) {
      const start = document.positionAt(match.index ?? 0);
      const end = document.positionAt(text.indexOf("}", (match.index ?? 0) + match[0].length));
      symbols.push(new vscode.DocumentSymbol(match[1], "struct", vscode.SymbolKind.Struct,
        new vscode.Range(start, end), new vscode.Range(start, start.translate(0, match[0].length))));
    }
    return symbols;
  }
}

export function activate(context: vscode.ExtensionContext): void {
  context.subscriptions.push(
    vscode.languages.registerCompletionItemProvider("cplus", new CPlusCompletionProvider(), ".", "@"),
    vscode.languages.registerHoverProvider("cplus", new CPlusHoverProvider()),
    vscode.languages.registerDocumentSymbolProvider("cplus", new CPlusDocumentSymbolProvider())
  );
}

export function deactivate(): void {
  // No process or external resource needs cleanup.
}
