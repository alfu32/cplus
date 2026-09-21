import * as vscode from "vscode";
import { execFile } from "node:child_process";
import { unlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { CPlusIndex, CPlusSymbol, indexText } from "./index";

const annotations = ["pub", "priv", "mut", "borrowed", "owned", "stat"];
const comptimeKeywords = ["import", "if", "else", "for", "type", "var", "fn"];
const cKeywords = ["typedef", "struct", "enum", "union", "const", "volatile", "restrict", "return", "if", "else", "for", "while", "switch", "case", "default", "break", "continue", "static"];

function symbolKind(kind: CPlusSymbol["kind"]): vscode.SymbolKind {
    switch (kind) {
        case "type": return vscode.SymbolKind.Struct;
        case "field": return vscode.SymbolKind.Field;
        case "method": return vscode.SymbolKind.Method;
        case "function": return vscode.SymbolKind.Function;
        case "variable": return vscode.SymbolKind.Variable;
        case "comptime": return vscode.SymbolKind.Constant;
    }
}

function completionKind(kind: CPlusSymbol["kind"]): vscode.CompletionItemKind {
    switch (kind) {
        case "type": return vscode.CompletionItemKind.Class;
        case "field": return vscode.CompletionItemKind.Field;
        case "method": return vscode.CompletionItemKind.Method;
        case "function": return vscode.CompletionItemKind.Function;
        case "variable": return vscode.CompletionItemKind.Variable;
        case "comptime": return vscode.CompletionItemKind.Value;
    }
}

function wordAt(document: vscode.TextDocument, position: vscode.Position): string {
    const range = document.getWordRangeAtPosition(position, /[A-Za-z_]\w*/);
    return range ? document.getText(range) : "";
}

function rangeFor(document: vscode.TextDocument, start: number, end: number): vscode.Range {
    return new vscode.Range(document.positionAt(start), document.positionAt(end));
}

function ownerFor(index: CPlusIndex, document: vscode.TextDocument, position: vscode.Position): string | undefined {
    const line = document.lineAt(position.line).text.slice(0, position.character);
    const receiver = /([A-Za-z_]\w*)\s*\.\s*[A-Za-z_]*$/.exec(line)?.[1];
    if (!receiver) return undefined;
    return index.variableTypes.get(receiver) ?? (receiver.endsWith("_t") ? receiver : undefined);
}

class CPlusCompletionProvider implements vscode.CompletionItemProvider {
    provideCompletionItems(document: vscode.TextDocument, position: vscode.Position): vscode.CompletionList {
        const index = indexText(document.getText());
        const line = document.lineAt(position.line).text.slice(0, position.character);
        const items: vscode.CompletionItem[] = [];
        const addItem = (label: string, kind: vscode.CompletionItemKind, detail: string, insertText = label): void => {
            const item = new vscode.CompletionItem(label, kind);
            item.detail = detail;
            item.insertText = insertText;
            items.push(item);
        };

        const owner = ownerFor(index, document, position);
        if (owner) {
            for (const field of index.fieldsByType.get(owner) ?? []) {
                addItem(field.name, vscode.CompletionItemKind.Field, field.detail);
            }
            for (const method of index.methodsByType.get(owner) ?? []) {
                addItem(method.name, vscode.CompletionItemKind.Method, method.detail + (method.isStatic ? " (static)" : ""));
            }
            return new vscode.CompletionList(items, false);
        }

        const atContext = /@[A-Za-z_]*$/.test(line);
        if (atContext) {
            for (const keyword of comptimeKeywords) {
                addItem("@" + keyword, vscode.CompletionItemKind.Keyword, "C-plus comptime form", keyword);
            }
            for (const symbol of index.symbols.filter((candidate) => candidate.kind === "comptime")) {
                addItem(symbol.name, vscode.CompletionItemKind.Constant, symbol.detail, symbol.name.slice(1));
            }
        } else {
            for (const annotation of annotations) {
                addItem(annotation, vscode.CompletionItemKind.Keyword, "C-plus annotation");
            }
            for (const keyword of cKeywords) {
                addItem(keyword, vscode.CompletionItemKind.Keyword, "C keyword");
            }
            for (const symbol of index.symbols) {
                if (symbol.kind === "field" || symbol.kind === "comptime") continue;
                addItem(symbol.name, completionKind(symbol.kind), symbol.detail);
            }
        }
        return new vscode.CompletionList(items, false);
    }
}

class CPlusHoverProvider implements vscode.HoverProvider {
    provideHover(document: vscode.TextDocument, position: vscode.Position): vscode.Hover | undefined {
        const word = wordAt(document, position);
        if (!word) return undefined;
        if (annotations.includes(word)) {
            return new vscode.Hover(word + ": optional C-plus source annotation; retained as an empty C macro");
        }
        const index = indexText(document.getText());
        const candidate = index.byName.get(word)?.[0] ?? index.byName.get("@" + word)?.[0];
        if (!candidate) return undefined;
        let explanation = candidate.detail;
        if (candidate.kind === "method" && candidate.owner) {
            const generated = candidate.owner.replace(/_t$/, "") + "__" + candidate.name;
            explanation += "\n\nGenerated C symbol: " + generated;
        } else if (candidate.kind === "comptime") {
            explanation += "\n\nResolved during phase 1; no runtime symbol is emitted.";
        }
        return new vscode.Hover(new vscode.MarkdownString(explanation));
    }
}

class CPlusDefinitionProvider implements vscode.DefinitionProvider {
    provideDefinition(document: vscode.TextDocument, position: vscode.Position): vscode.Definition | undefined {
        const word = wordAt(document, position);
        if (!word) return undefined;
        const index = indexText(document.getText());
        const symbol = index.byName.get(word)?.[0] ?? index.byName.get("@" + word)?.[0];
        return symbol ? new vscode.Location(document.uri, rangeFor(document, symbol.start, symbol.end)) : undefined;
    }
}

class CPlusReferenceProvider implements vscode.ReferenceProvider {
    provideReferences(document: vscode.TextDocument, position: vscode.Position): vscode.Location[] {
        const word = wordAt(document, position);
        if (!word) return [];
        const locations: vscode.Location[] = [];
        const pattern = new RegExp("\\b" + word.replace(/[-/\\^$*+?.()|[\]{}]/g, "\\$&") + "\\b", "g");
        for (const match of document.getText().matchAll(pattern)) {
            const start = match.index ?? 0;
            locations.push(new vscode.Location(document.uri, rangeFor(document, start, start + word.length)));
        }
        return locations;
    }
}

class CPlusDocumentSymbolProvider implements vscode.DocumentSymbolProvider {
    provideDocumentSymbols(document: vscode.TextDocument): vscode.DocumentSymbol[] {
        const index = indexText(document.getText());
        return index.symbols
            .filter((symbol) => symbol.kind !== "field" && symbol.kind !== "method" && symbol.kind !== "comptime")
            .map((symbol) => {
                const children = (symbol.children ?? []).map((child) =>
                    new vscode.DocumentSymbol(child.name, child.detail, symbolKind(child.kind), rangeFor(document, child.start, child.end), rangeFor(document, child.start, child.end)));
                const documentSymbol = new vscode.DocumentSymbol(symbol.name, symbol.detail, symbolKind(symbol.kind), rangeFor(document, symbol.start, symbol.end), rangeFor(document, symbol.start, symbol.end));
                documentSymbol.children = children;
                return documentSymbol;
            });
    }
}

function localDiagnostics(document: vscode.TextDocument): vscode.Diagnostic[] {
    const diagnostics: vscode.Diagnostic[] = [];
    const text = document.getText();
    const stack: Array<{ character: string; offset: number }> = [];
    const pairs: Record<string, string> = { "(": ")", "[": "]", "{": "}" };
    let quote = "";
    let lineComment = false;
    let blockComment = false;
    for (let i = 0; i < text.length; i++) {
        const character = text[i];
        const next = text[i + 1] ?? "";
        if (lineComment) {
            if (character === "\n") lineComment = false;
            continue;
        }
        if (blockComment) {
            if (character === "*" && next === "/") {
                blockComment = false;
                i++;
            }
            continue;
        }
        if (quote) {
            if (character === "\\") i++;
            else if (character === quote) quote = "";
            continue;
        }
        if (character === "/" && next === "/") {
            lineComment = true;
            i++;
        } else if (character === "/" && next === "*") {
            blockComment = true;
            i++;
        } else if (character === "\"" || character === "'") {
            quote = character;
        } else if (pairs[character]) {
            stack.push({ character, offset: i });
        } else if (Object.values(pairs).includes(character)) {
            const expected = pairs[stack[stack.length - 1]?.character ?? ""];
            if (!stack.length || expected !== character) {
                diagnostics.push(new vscode.Diagnostic(rangeFor(document, i, i + 1), "Unmatched closing delimiter", vscode.DiagnosticSeverity.Error));
            } else {
                stack.pop();
            }
        }
    }
    for (const unmatched of stack) {
        diagnostics.push(new vscode.Diagnostic(rangeFor(document, unmatched.offset, unmatched.offset + 1), "Unclosed delimiter", vscode.DiagnosticSeverity.Error));
    }
    return diagnostics;
}

async function compilerDiagnostics(document: vscode.TextDocument, collection: vscode.DiagnosticCollection): Promise<void> {
    const configuration = vscode.workspace.getConfiguration("cplus");
    if (!configuration.get<boolean>("compilerDiagnostics", false) || document.uri.scheme !== "file") return;
    const local = localDiagnostics(document);
    collection.set(document.uri, local);
    const command = configuration.get<string>("compilerCommand", "cplus");
    const extraArgs = configuration.get<string[]>("compilerArguments", []);
    const output = join(tmpdir(), "cplus-vscode-" + Date.now());
    await new Promise<void>((resolve) => {
        execFile(command, ["compile", document.uri.fsPath, "-o", output, ...extraArgs], {
            cwd: vscode.workspace.getWorkspaceFolder(document.uri)?.uri.fsPath,
            maxBuffer: 1024 * 1024
        }, (error, stdout, stderr) => {
            const compiler = [] as vscode.Diagnostic[];
            for (const line of (stderr + "\n" + stdout).split(/\r?\n/)) {
                const match = /^(.*?):(\d+):(\d+):\s*(?:error|warning):\s*(.*)$/.exec(line);
                if (!match || match[1] !== document.uri.fsPath) continue;
                const range = new vscode.Range(Number(match[2]) - 1, Number(match[3]) - 1, Number(match[2]) - 1, Number(match[3]));
                compiler.push(new vscode.Diagnostic(range, match[4], error ? vscode.DiagnosticSeverity.Error : vscode.DiagnosticSeverity.Warning));
            }
            collection.set(document.uri, [...local, ...compiler]);
            void unlink(output).catch(() => undefined);
            resolve();
        });
    });
}

function registerCommands(context: vscode.ExtensionContext, diagnostics: vscode.DiagnosticCollection): void {
    const runCli = (subcommand: string): void => {
        const editor = vscode.window.activeTextEditor;
        if (!editor || editor.document.languageId !== "cplus") {
            void vscode.window.showWarningMessage("Open a C-plus source file first.");
            return;
        }
        const command = vscode.workspace.getConfiguration("cplus").get<string>("compilerCommand", "cplus");
        const terminal = vscode.window.createTerminal("C-plus " + subcommand);
        terminal.show();
        terminal.sendText(command + " " + subcommand + " " + JSON.stringify(editor.document.uri.fsPath));
    };
    context.subscriptions.push(
        vscode.commands.registerCommand("cplus.transcode", () => runCli("transcode")),
        vscode.commands.registerCommand("cplus.compile", () => runCli("compile")),
        vscode.commands.registerCommand("cplus.run", () => runCli("run")),
        vscode.commands.registerCommand("cplus.check", async () => {
            const editor = vscode.window.activeTextEditor;
            if (editor) {
                diagnostics.set(editor.document.uri, localDiagnostics(editor.document));
                await compilerDiagnostics(editor.document, diagnostics);
            }
        })
    );
}

export function activate(context: vscode.ExtensionContext): void {
    const diagnostics = vscode.languages.createDiagnosticCollection("cplus");
    context.subscriptions.push(
        diagnostics,
        vscode.languages.registerCompletionItemProvider("cplus", new CPlusCompletionProvider(), ".", "@"),
        vscode.languages.registerHoverProvider("cplus", new CPlusHoverProvider()),
        vscode.languages.registerDefinitionProvider("cplus", new CPlusDefinitionProvider()),
        vscode.languages.registerReferenceProvider("cplus", new CPlusReferenceProvider()),
        vscode.languages.registerDocumentSymbolProvider("cplus", new CPlusDocumentSymbolProvider()),
        vscode.workspace.onDidOpenTextDocument((document) => diagnostics.set(document.uri, localDiagnostics(document))),
        vscode.workspace.onDidChangeTextDocument((event) => diagnostics.set(event.document.uri, localDiagnostics(event.document))),
        vscode.workspace.onDidSaveTextDocument((document) => compilerDiagnostics(document, diagnostics))
    );
    registerCommands(context, diagnostics);
    for (const document of vscode.workspace.textDocuments) {
        if (document.languageId === "cplus") diagnostics.set(document.uri, localDiagnostics(document));
    }
}

export function deactivate(): void {
    // All resources are owned by the extension context.
}
