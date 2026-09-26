import * as vscode from "vscode";
import { execFile } from "node:child_process";
import { unlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import { clearTimeout, setTimeout } from "node:timers";
import { CPlusAstNode, CPlusSymbol, indexText, memberContext, symbolsFromAst } from "./index";
import { CPlusTestFixture, findTestFixtures, findTestFixturesFromAst } from "./tests";
import { decodeImportGraph } from "./importGraph";
import {
    builtinTestMacros,
    cKeywords,
    cplusAnnotations,
    cplusKeywords,
    cTypes,
    comptimeAtForms,
    comptimeForms,
    comptimeProperties,
    comptimeResultKinds,
    comptimeValues
} from "./builtins";

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

const parserTrees = new Map<string, { version: number; source: string; root: CPlusAstNode }>();
const parserTreeListeners = new Set<(document: vscode.TextDocument) => void>();

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

        const context = memberContext(index, line);
        if (context) {
            if (!context.isStatic) {
                for (const field of index.fieldsByType.get(context.type) ?? []) {
                    addItem(field.name, vscode.CompletionItemKind.Field, field.detail);
                }
            }
            for (const method of index.methodsByType.get(context.type) ?? []) {
                if (method.isStatic !== context.isStatic) continue;
                addItem(method.name, vscode.CompletionItemKind.Method, method.detail + (method.isStatic ? " (static)" : ""));
            }
            return new vscode.CompletionList(items, false);
        }

        const reflectionReceiver = /\b([A-Za-z_]\w*)\.\w*$/.exec(line)?.[1];
        if (reflectionReceiver && new RegExp("(?:^|\\W)@?type\\s+" + reflectionReceiver + "\\b").test(document.getText())) {
            for (const property of comptimeProperties) {
                addItem(property, vscode.CompletionItemKind.Property, "C-plus comptime reflection property");
            }
            return new vscode.CompletionList(items, false);
        }

        if (/\bcomptime\s+[A-Za-z_]*$/.test(line)) {
            for (const kind of [...comptimeResultKinds, ...comptimeForms]) {
                addItem(kind, vscode.CompletionItemKind.Keyword, "C-plus comptime form");
            }
            return new vscode.CompletionList(items, false);
        }

        const atContext = /@[A-Za-z_]*$/.test(line);
        if (atContext) {
            for (const form of comptimeAtForms) {
                addItem(form, vscode.CompletionItemKind.Keyword, "C-plus comptime built-in", form.slice(1));
            }
            for (const symbol of index.symbols.filter((candidate) => candidate.kind === "comptime")) {
                addItem(symbol.name, vscode.CompletionItemKind.Constant, symbol.detail, symbol.name.slice(1));
            }
        } else {
            for (const annotation of cplusAnnotations) {
                addItem(annotation, vscode.CompletionItemKind.Keyword, "C-plus annotation");
            }
            for (const keyword of cplusKeywords) {
                addItem(keyword, vscode.CompletionItemKind.Keyword, "C-plus keyword");
            }
            for (const keyword of cKeywords) {
                addItem(keyword, vscode.CompletionItemKind.Keyword, "C keyword");
            }
            for (const type of cTypes) {
                addItem(type, vscode.CompletionItemKind.Class, "C/C-plus built-in type");
            }
            for (const macro of builtinTestMacros) {
                addItem(macro, vscode.CompletionItemKind.Function, "C-plus test helper macro");
            }
            if (/(?:@if|@else\s+if)\s*\([^)]*$/.test(line)) {
                for (const value of comptimeValues) {
                    addItem(value, vscode.CompletionItemKind.Constant, "C-plus comptime target value");
                }
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
        if (word === "scratch") {
            return new vscode.Hover("scratch: short-lived memory invalidated by reset_scratch()");
        }
        if (word === "hot") {
            return new vscode.Hover("hot: frequently accessed working-set memory; use alloc_hot() or alloc_hot_aligned()");
        }
        if (word === "warm") {
            return new vscode.Hover("warm: general-purpose dynamic memory; use alloc_warm(), realloc_warm(), and free_warm()");
        }
        if (word === "cold") {
            return new vscode.Hover("cold: infrequently accessed or large memory; use alloc_cold() and free_cold()");
        }
        if (cplusAnnotations.includes(word)) {
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
        const source = document.getText();
        const cached = parserTrees.get(document.uri.toString());
        const symbols = cached?.version === document.version && cached.source === source
            ? symbolsFromAst(source, cached.root)
            : indexText(source).symbols;
        return symbols
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

async function compilerDiagnostics(
    document: vscode.TextDocument,
    collection: vscode.DiagnosticCollection,
    includeCompiler = true
): Promise<void> {
    const configuration = vscode.workspace.getConfiguration("cplus");
    if (document.uri.scheme !== "file") return;
    const version = document.version;
    const local = localDiagnostics(document);
    const parsed: vscode.Diagnostic[] = [];
    if (configuration.get<boolean>("parserDiagnostics", false)) {
        const command = splitCommand(configuration.get<string>("parserCommand", "cplus parse"));
        await new Promise<void>((resolve) => {
            const child = execFile(command[0], [...command.slice(1), "--stdin", "--source", document.uri.fsPath], {
                cwd: vscode.workspace.getWorkspaceFolder(document.uri)?.uri.fsPath,
                maxBuffer: 16 * 1024 * 1024
            }, (_error, stdout) => {
                try {
                    const payload = JSON.parse(stdout) as {
                        ast?: CPlusAstNode;
                        diagnostics?: Array<{
                            code?: string;
                            message: string;
                            severity: string;
                            span: { startOffset: number; endOffset: number };
                        }>;
                    };
                    if (document.version === version) {
                        if (payload.ast) {
                            parserTrees.set(document.uri.toString(), { version, source: document.getText(), root: payload.ast });
                            parserTreeListeners.forEach((listener) => listener(document));
                        }
                        for (const item of payload.diagnostics ?? []) {
                            const diagnostic = new vscode.Diagnostic(
                                new vscode.Range(document.positionAt(item.span.startOffset), document.positionAt(item.span.endOffset)),
                                item.message,
                                item.severity === "warning" ? vscode.DiagnosticSeverity.Warning : vscode.DiagnosticSeverity.Error
                            );
                            diagnostic.source = "C-plus parser";
                            diagnostic.code = item.code;
                            parsed.push(diagnostic);
                        }
                    }
                } catch {
                    // The configured parser command may be absent or may not implement cplus.parse.v1.
                }
                resolve();
            });
            child.stdin.on("error", () => undefined);
            child.stdin.end(document.getText());
        });
    }

    const compiler: vscode.Diagnostic[] = [];
    if (includeCompiler && configuration.get<boolean>("compilerDiagnostics", false)) {
        const command = splitCommand(configuration.get<string>("compilerCommand", "cplus compile"));
        const extraArgs = configuration.get<string[]>("compilerArguments", []);
        const output = join(tmpdir(), "cplus-vscode-" + Date.now());
        await new Promise<void>((resolve) => {
            execFile(command[0], [...command.slice(1), document.uri.fsPath, "-o", output, ...extraArgs], {
                cwd: vscode.workspace.getWorkspaceFolder(document.uri)?.uri.fsPath,
                maxBuffer: 1024 * 1024
            }, (error, stdout, stderr) => {
                for (const line of (stderr + "\n" + stdout).split(/\r?\n/)) {
                    const match = /^(.*?):(\d+):(\d+):\s*(?:error|warning):\s*(.*)$/.exec(line);
                    if (!match || match[1] !== document.uri.fsPath) continue;
                    const range = new vscode.Range(Number(match[2]) - 1, Number(match[3]) - 1, Number(match[2]) - 1, Number(match[3]));
                    compiler.push(new vscode.Diagnostic(range, match[4], error ? vscode.DiagnosticSeverity.Error : vscode.DiagnosticSeverity.Warning));
                }
                void unlink(output).catch(() => undefined);
                resolve();
            });
        });
    }
    if (document.version === version) collection.set(document.uri, [...local, ...parsed, ...compiler]);
}

function registerCommands(context: vscode.ExtensionContext, diagnostics: vscode.DiagnosticCollection): void {
    const runCli = (setting: "compilerCommand" | "runnerCommand", replaceLast?: string): void => {
        const editor = vscode.window.activeTextEditor;
        if (!editor || editor.document.languageId !== "cplus") {
            void vscode.window.showWarningMessage("Open a C-plus source file first.");
            return;
        }
        const configuration = vscode.workspace.getConfiguration("cplus");
        const command = splitCommand(configuration.get<string>(setting, setting === "runnerCommand" ? "cplus run" : "cplus compile"));
        if (replaceLast && command.length > 1) command[command.length - 1] = replaceLast;
        const terminal = vscode.window.createTerminal("C-plus " + setting);
        terminal.show();
        terminal.sendText([...command, editor.document.uri.fsPath].map(shellQuote).join(" "));
    };
    context.subscriptions.push(
        vscode.commands.registerCommand("cplus.transcode", () => runCli("compilerCommand", "transcode")),
        vscode.commands.registerCommand("cplus.compile", () => runCli("compilerCommand")),
        vscode.commands.registerCommand("cplus.run", () => runCli("runnerCommand")),
        vscode.commands.registerCommand("cplus.check", async () => {
            const editor = vscode.window.activeTextEditor;
            if (editor) {
                diagnostics.set(editor.document.uri, localDiagnostics(editor.document));
                await compilerDiagnostics(editor.document, diagnostics);
            }
        })
    );
}

interface FixtureLocation {
    uri: vscode.Uri;
    fixture: CPlusTestFixture;
    sourceItem: vscode.TestItem;
}

function registerTestSupport(context: vscode.ExtensionContext): void {
    const controller = vscode.tests.createTestController("cplus.tests", "C-plus Tests");
    const fixtures = new Map<string, FixtureLocation>();
    const sourceItems = new Map<string, vscode.TestItem>();
    const keyFor = (uri: vscode.Uri): string => uri.toString();

    const discover = (document: vscode.TextDocument): void => {
        if (document.languageId !== "cplus" || document.uri.scheme !== "file") return;
        const key = keyFor(document.uri);
        const previous = sourceItems.get(key);
        if (previous) controller.items.delete(previous.id);
        for (const [id, value] of fixtures) if (keyFor(value.uri) === key) fixtures.delete(id);

        const sourceItem = controller.createTestItem("file:" + key, document.uri.fsPath, document.uri);
        sourceItem.canResolveChildren = false;
        sourceItems.set(key, sourceItem);
        const source = document.getText();
        const parsed = parserTrees.get(key);
        const discovered = parsed?.version === document.version && parsed.source === source
            ? findTestFixturesFromAst(source, parsed.root)
            : findTestFixtures(source);
        for (const fixture of discovered) {
            const id = key + "#" + fixture.start;
            const item = controller.createTestItem(id, fixture.name, document.uri);
            const range = new vscode.Range(document.positionAt(fixture.start), document.positionAt(fixture.end));
            item.range = range;
            sourceItem.children.add(item);
            fixtures.set(id, { uri: document.uri, fixture, sourceItem });
        }
        controller.items.add(sourceItem);
    };

    const runProfile = controller.createRunProfile("Run", vscode.TestRunProfileKind.Run, async (request, token) => {
        const run = controller.createTestRun(request);
        const selected = request.include?.length
            ? request.include.flatMap((item) => item.children.size
                ? [...item.children].map(([, child]) => child)
                : [item])
            : [...fixtures.keys()].map((id) => controller.items.get(id) ?? findChild(id));
        const excluded = new Set((request.exclude ?? []).flatMap((item) =>
            item.children.size ? [...item.children].map(([, child]) => child.id) : [item.id]));
        const ids = [...new Set(selected.filter((item): item is vscode.TestItem => Boolean(item))
            .map((item) => item.id).filter((id) => !excluded.has(id)))];
        const jobs = ids.map((id) => ({ item: selected.find((item) => item?.id === id)!, location: fixtures.get(id) }))
            .filter((job): job is { item: vscode.TestItem; location: FixtureLocation } => Boolean(job.location));

        for (const job of jobs) {
            if (token.isCancellationRequested) { run.skipped(job.item); continue; }
            run.started(job.item);
            const document = await vscode.workspace.openTextDocument(job.location.uri);
            await document.save();
            const configuration = vscode.workspace.getConfiguration("cplus");
            const command = splitCommand(configuration.get<string>("testProgramCommand", "cplus test"));
            const args = [...command.slice(1), ...(configuration.get<string[]>("compilerArguments", [])), job.location.uri.fsPath, job.location.fixture.name];
            const result = await execute(command[0] ?? "cplus", args, token);
            if (result.output) run.appendOutput(result.output.replace(/\r?\n/g, "\r\n"), undefined, job.item);
            if (result.cancelled) run.skipped(job.item);
            else if (result.code === 0) run.passed(job.item);
            else run.failed(job.item, new vscode.TestMessage(result.output || `cplus test exited with status ${result.code}`));
        }
        run.end();
    }, true);

    function findChild(id: string): vscode.TestItem | undefined {
        for (const [, parent] of controller.items) {
            const found = parent.children.get(id);
            if (found) return found;
        }
        return undefined;
    }

    const refresh = async (): Promise<void> => {
        for (const document of vscode.workspace.textDocuments) discover(document);
        for (const document of await vscode.workspace.findFiles("**/*.{cp,c+}", "**/{build,node_modules,.git}/**")) {
            try { discover(await vscode.workspace.openTextDocument(document)); } catch { /* Ignore unreadable files. */ }
        }
    };

    const parserTreeListener = (document: vscode.TextDocument): void => {
        if (sourceItems.has(keyFor(document.uri))) discover(document);
    };
    parserTreeListeners.add(parserTreeListener);

    context.subscriptions.push(
        controller,
        runProfile,
        new vscode.Disposable(() => parserTreeListeners.delete(parserTreeListener)),
        vscode.workspace.onDidOpenTextDocument(discover),
        vscode.workspace.onDidChangeTextDocument((event) => discover(event.document)),
        vscode.workspace.onDidSaveTextDocument(discover),
        vscode.workspace.onDidChangeWorkspaceFolders(() => void refresh())
    );
    void refresh();
}

function execute(command: string, args: string[], token: vscode.CancellationToken): Promise<{ code: number; output: string; cancelled: boolean }> {
    return new Promise((resolve) => {
        let child: ReturnType<typeof execFile>;
        let cancelled = false;
        const subscription = token.onCancellationRequested(() => { cancelled = true; child?.kill(); });
        child = execFile(command, args, {
            cwd: vscode.workspace.workspaceFolders?.[0]?.uri.fsPath,
            maxBuffer: 8 * 1024 * 1024
        }, (error, stdout, stderr) => {
            subscription.dispose();
            const output = [stdout, stderr, error?.message].filter(Boolean).join("\n");
            resolve({ code: error ? 1 : 0, output, cancelled });
        });
    });
}

function splitCommand(command: string): string[] {
    return [...command.matchAll(/"([^\"]*)"|'([^']*)'|([^\s]+)/g)]
        .map((match) => match[1] ?? match[2] ?? match[3] ?? "");
}

function shellQuote(value: string): string {
    return "'" + value.replace(/'/g, "'\\''") + "'";
}

class CPlusMainCodeLensProvider implements vscode.CodeLensProvider {
    provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
        if (document.languageId !== "cplus") return [];
        const main = /\bmain\s*\([^)]*\)\s*\{/g.exec(document.getText());
        if (!main) return [];
        const position = document.positionAt(main.index);
        return [new vscode.CodeLens(new vscode.Range(position, position), {
            title: "▶ Run main",
            command: "cplus.runMain",
            arguments: [document.uri]
        })];
    }
}

function registerMainRun(context: vscode.ExtensionContext): void {
    context.subscriptions.push(
        vscode.languages.registerCodeLensProvider("cplus", new CPlusMainCodeLensProvider()),
        vscode.commands.registerCommand("cplus.runMain", async (uri?: vscode.Uri) => {
            const target = uri ?? vscode.window.activeTextEditor?.document.uri;
            if (!target || target.scheme !== "file") return;
            const command = splitCommand(vscode.workspace.getConfiguration("cplus").get<string>("runnerCommand", "cplus run"));
            if (!command.length) return;
            const terminal = vscode.window.createTerminal("C-plus run main");
            terminal.show();
            terminal.sendText([...command, target.fsPath].map(shellQuote).join(" "));
        })
    );
}

function registerImportGraph(context: vscode.ExtensionContext): void {
    context.subscriptions.push(vscode.commands.registerCommand("cplus.showImportGraph", async (uri?: vscode.Uri) => {
        const editor = vscode.window.activeTextEditor;
        const document = uri ? await vscode.workspace.openTextDocument(uri) : editor?.document;
        if (!document || document.uri.scheme !== "file") return;
        if (document.isDirty && !(await document.save())) return;
        const command = splitCommand(vscode.workspace.getConfiguration("cplus").get<string>("importGraphCommand", "cplus graph"));
        if (!command.length) {
            void vscode.window.showErrorMessage("Configure cplus.importGraphCommand to use C-plus import graphs.");
            return;
        }
        const cancellation = new vscode.CancellationTokenSource();
        let result: { code: number; output: string; cancelled: boolean };
        try {
            result = await execute(command[0], [...command.slice(1), document.uri.fsPath], cancellation.token);
        } finally {
            cancellation.dispose();
        }
        if (result.code !== 0) {
            void vscode.window.showErrorMessage(result.output || "C-plus could not resolve the import graph.");
            return;
        }
        try {
            const graph = decodeImportGraph(JSON.parse(result.output));
            const targets = [...new Set(graph.imports.map((edge) => edge.imported))];
            if (!targets.length) {
                void vscode.window.showInformationMessage("This C-plus source has no resolved imports.");
                return;
            }
            type ImportPick = vscode.QuickPickItem & { path: string };
            const picks: ImportPick[] = graph.imports.map((edge) => ({
                label: basename(edge.imported),
                description: `${basename(edge.importer)}:${edge.location.startLine}:${edge.location.startColumn}`,
                detail: edge.imported,
                path: edge.imported
            }));
            const selected = await vscode.window.showQuickPick(picks, { placeHolder: "Resolved C-plus imports (dependency edges)" });
            if (selected) {
                const imported = await vscode.workspace.openTextDocument(vscode.Uri.file(selected.path));
                await vscode.window.showTextDocument(imported);
            }
        } catch (error) {
            void vscode.window.showErrorMessage(error instanceof Error ? error.message : "Invalid C-plus import graph response.");
        }
    }));
}

export function activate(context: vscode.ExtensionContext): void {
    const diagnostics = vscode.languages.createDiagnosticCollection("cplus");
    const pendingParserRuns = new Map<string, ReturnType<typeof setTimeout>>();
    const scheduleParserDiagnostics = (document: vscode.TextDocument): void => {
        if (document.languageId !== "cplus" || !vscode.workspace.getConfiguration("cplus").get<boolean>("parserDiagnostics", false)) return;
        const key = document.uri.toString();
        const previous = pendingParserRuns.get(key);
        if (previous !== undefined) clearTimeout(previous);
        pendingParserRuns.set(key, setTimeout(() => {
            pendingParserRuns.delete(key);
            void compilerDiagnostics(document, diagnostics, false);
        }, 250));
    };
    const parseOnSave = (document: vscode.TextDocument): void => {
        const key = document.uri.toString();
        const previous = pendingParserRuns.get(key);
        if (previous !== undefined) clearTimeout(previous);
        pendingParserRuns.delete(key);
        void compilerDiagnostics(document, diagnostics);
    };
    context.subscriptions.push(
        diagnostics,
        vscode.languages.registerCompletionItemProvider("cplus", new CPlusCompletionProvider(), ".", ">", "@"),
        vscode.languages.registerHoverProvider("cplus", new CPlusHoverProvider()),
        vscode.languages.registerDefinitionProvider("cplus", new CPlusDefinitionProvider()),
        vscode.languages.registerReferenceProvider("cplus", new CPlusReferenceProvider()),
        vscode.languages.registerDocumentSymbolProvider("cplus", new CPlusDocumentSymbolProvider()),
        vscode.workspace.onDidOpenTextDocument((document) => diagnostics.set(document.uri, localDiagnostics(document))),
        vscode.workspace.onDidChangeTextDocument((event) => {
            diagnostics.set(event.document.uri, localDiagnostics(event.document));
            scheduleParserDiagnostics(event.document);
        }),
        vscode.workspace.onDidSaveTextDocument(parseOnSave),
        new vscode.Disposable(() => {
            pendingParserRuns.forEach(clearTimeout);
            pendingParserRuns.clear();
        })
    );
    registerCommands(context, diagnostics);
    registerMainRun(context);
    registerImportGraph(context);
    registerTestSupport(context);
    for (const document of vscode.workspace.textDocuments) {
        if (document.languageId === "cplus") diagnostics.set(document.uri, localDiagnostics(document));
    }
}

export function deactivate(): void {
    // All resources are owned by the extension context.
}
