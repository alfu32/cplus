import * as vscode from "vscode";
import { clearTimeout, setTimeout } from "node:timers";
import { splitCommand } from "./lspCommand";

// The extension intentionally keeps its runtime dependency-free. VS Code's
// extension host supplies Node, while this project does not require the full
// @types/node package just to compile the adapter.
declare const require: (moduleName: string) => any;
declare const process: { pid: number };
declare const Buffer: {
    alloc(size: number): any;
    concat(chunks: any[]): any;
    from(value: string, encoding?: string): any;
};
type Buffer = any;
const childProcess: any = require("node:child_process");

type JsonObject = { [key: string]: unknown };

interface LspPosition {
    line: number;
    character: number;
}

interface LspRange {
    start: LspPosition;
    end: LspPosition;
}

interface LspLocation {
    uri: string;
    range: LspRange;
}

interface LspDiagnostic {
    range: LspRange;
    severity?: number;
    code?: string | number;
    source?: string;
    message: string;
}

interface PendingRequest {
    resolve: (value: unknown) => void;
    reject: (reason: unknown) => void;
}

/** Small dependency-free JSON-RPC client for the CLI-owned C-plus language server. */
export class CPlusLspClient implements vscode.Disposable {
    private child?: any;
    private buffer = Buffer.alloc(0);
    private nextId = 1;
    private readonly pending = new Map<number, PendingRequest>();
    private readonly disposables: vscode.Disposable[] = [];
    private readonly diagnostics = vscode.languages.createDiagnosticCollection("cplus-lsp");
    private started = false;
    private disposed = false;
    private stopping = false;
    private restartAttempts = 0;
    private restartTimer?: any;

    constructor(
        private readonly command: string,
        private readonly arguments_: string[],
        private readonly output: vscode.OutputChannel
    ) {}

    async start(): Promise<void> {
        if (this.started) return;
        if (this.disposed) throw new Error("language server client is disposed");
        const command = splitCommand(this.command);
        if (!command.length) throw new Error("cplus.languageServerCommand is empty");
        const child = childProcess.spawn(command[0], [...command.slice(1), ...this.arguments_, "lsp"], {
            cwd: vscode.workspace.workspaceFolders?.[0]?.uri.fsPath,
            stdio: "pipe"
        });
        this.child = child;
        child.stdout.on("data", (chunk: Buffer) => this.consume(chunk));
        child.stderr.on("data", (chunk: Buffer) => this.output.append(chunk.toString()));
        child.on("error", (error: unknown) => {
            if (this.child === child) this.fail(error);
        });
        child.on("exit", (code: number | null, signal: string | null) => this.onExit(child, code, signal));
        this.started = true;
        await this.request("initialize", {
            processId: process.pid,
            rootUri: vscode.workspace.workspaceFolders?.[0]?.uri.toString() ?? null,
            capabilities: {},
            workspaceFolders: vscode.workspace.workspaceFolders?.map((folder) => ({
                uri: folder.uri.toString(),
                name: folder.name
            })) ?? null
        });
        this.notify("initialized", {});
        for (const document of vscode.workspace.textDocuments) this.open(document);
        if (this.disposables.length === 0) {
            this.disposables.push(
                vscode.workspace.onDidOpenTextDocument((document) => this.open(document)),
                vscode.workspace.onDidChangeTextDocument((event) => this.change(event.document)),
                vscode.workspace.onDidCloseTextDocument((document) => this.close(document))
            );
        }
        this.restartAttempts = 0;
    }

    /** Restart the external server while retaining open-document synchronization. */
    async restart(): Promise<void> {
        if (this.disposed) return;
        this.stopping = true;
        this.stopProcess();
        this.stopping = false;
        await this.start();
    }

    async request<T>(method: string, params: unknown): Promise<T> {
        if (!this.child || !this.started && method !== "initialize") throw new Error("language server is not running");
        const id = this.nextId++;
        const promise = new Promise<unknown>((resolve, reject) => this.pending.set(id, { resolve, reject }));
        this.write({ jsonrpc: "2.0", id, method, params });
        return await promise as T;
    }

    notify(method: string, params: unknown): void {
        if (!this.child) return;
        this.write({ jsonrpc: "2.0", method, params });
    }

    async completion(document: vscode.TextDocument, position: vscode.Position): Promise<vscode.CompletionList> {
        const result = await this.request<{ items?: Array<{ label: string; kind?: number; detail?: string }> }>("textDocument/completion", {
            textDocument: { uri: document.uri.toString() },
            position: toLspPosition(position)
        });
        const items = (result?.items ?? []).map((item) => {
            const completion = new vscode.CompletionItem(item.label, item.kind === 14
                ? vscode.CompletionItemKind.Keyword
                : vscode.CompletionItemKind.Function);
            completion.detail = item.detail;
            return completion;
        });
        return new vscode.CompletionList(items, false);
    }

    async hover(document: vscode.TextDocument, position: vscode.Position): Promise<vscode.Hover | undefined> {
        const result = await this.request<{ contents?: { value?: string }; range?: LspRange } | null>("textDocument/hover", {
            textDocument: { uri: document.uri.toString() },
            position: toLspPosition(position)
        });
        if (!result?.contents) return undefined;
        const range = result.range ? fromLspRange(result.range) : undefined;
        return new vscode.Hover(new vscode.MarkdownString(result.contents.value ?? ""), range);
    }

    async definition(document: vscode.TextDocument, position: vscode.Position): Promise<vscode.Location[] | undefined> {
        const result = await this.request<LspLocation[]>("textDocument/definition", {
            textDocument: { uri: document.uri.toString() },
            position: toLspPosition(position)
        });
        return result?.map((location) => new vscode.Location(vscode.Uri.parse(location.uri), fromLspRange(location.range)));
    }

    async documentSymbols(document: vscode.TextDocument): Promise<vscode.DocumentSymbol[]> {
        const result = await this.request<Array<JsonObject>>("textDocument/documentSymbol", {
            textDocument: { uri: document.uri.toString() }
        });
        return (result ?? []).map((symbol) => toDocumentSymbol(symbol));
    }

    dispose(): void {
        this.disposed = true;
        this.stopping = true;
        if (this.restartTimer !== undefined) clearTimeout(this.restartTimer);
        this.restartTimer = undefined;
        if (this.child) {
            try { this.notify("shutdown", null); } catch { /* Process may already be gone. */ }
        }
        this.stopProcess();
        this.disposables.splice(0).forEach((disposable) => disposable.dispose());
        this.diagnostics.dispose();
        this.fail(new Error("language server disposed"));
    }

    private open(document: vscode.TextDocument): void {
        if (document.languageId !== "cplus") return;
        this.notify("textDocument/didOpen", {
            textDocument: {
                uri: document.uri.toString(),
                languageId: "cplus",
                version: document.version,
                text: document.getText()
            }
        });
    }

    private change(document: vscode.TextDocument): void {
        if (document.languageId !== "cplus") return;
        this.notify("textDocument/didChange", {
            textDocument: { uri: document.uri.toString(), version: document.version },
            contentChanges: [{ text: document.getText() }]
        });
    }

    private close(document: vscode.TextDocument): void {
        if (document.languageId !== "cplus") return;
        this.notify("textDocument/didClose", { textDocument: { uri: document.uri.toString() } });
        this.diagnostics.delete(document.uri);
    }

    private consume(chunk: Buffer): void {
        this.buffer = Buffer.concat([this.buffer, chunk]);
        while (true) {
            const separator = this.buffer.indexOf("\r\n\r\n");
            if (separator < 0) return;
            const header = this.buffer.subarray(0, separator).toString("ascii");
            const length = /^Content-Length:\s*(\d+)$/im.exec(header)?.[1];
            if (!length) {
                this.buffer = this.buffer.subarray(separator + 4);
                continue;
            }
            const bodyStart = separator + 4;
            const bodyEnd = bodyStart + Number(length);
            if (this.buffer.length < bodyEnd) return;
            const body = this.buffer.subarray(bodyStart, bodyEnd).toString("utf8");
            this.buffer = this.buffer.subarray(bodyEnd);
            this.receive(body);
        }
    }

    private receive(body: string): void {
        let message: JsonObject;
        try { message = JSON.parse(body) as JsonObject; } catch { return; }
        if (message.method === "textDocument/publishDiagnostics") {
            const params = message.params as JsonObject | undefined;
            const uri = typeof params?.uri === "string" ? vscode.Uri.parse(params.uri) : undefined;
            if (!uri) return;
            const rawDiagnostics = Array.isArray(params?.diagnostics) ? params.diagnostics : [];
            const diagnostics = rawDiagnostics
                .map((item) => toDiagnostic(item as LspDiagnostic))
                .filter((item): item is vscode.Diagnostic => Boolean(item));
            this.diagnostics.set(uri, diagnostics);
            return;
        }
        const id = typeof message.id === "number" ? message.id : undefined;
        if (id === undefined) return;
        const pending = this.pending.get(id);
        if (!pending) return;
        this.pending.delete(id);
        if (message.error) pending.reject(message.error);
        else pending.resolve(message.result);
    }

    private write(message: JsonObject): void {
        if (!this.child) return;
        const body = Buffer.from(JSON.stringify(message), "utf8");
        this.child.stdin.write(Buffer.from(`Content-Length: ${body.length}\r\n\r\n`, "ascii"));
        this.child.stdin.write(body);
    }

    private fail(error: unknown): void {
        if (!this.pending.size) return;
        const pending = [...this.pending.values()];
        this.pending.clear();
        pending.forEach((request) => request.reject(error));
    }

    private stopProcess(): void {
        const child = this.child;
        this.child = undefined;
        this.started = false;
        this.buffer = Buffer.alloc(0);
        if (child) child.kill();
    }

    private onExit(child: any, code: number | null, signal: string | null): void {
        if (this.child !== child) return;
        this.child = undefined;
        this.started = false;
        this.buffer = Buffer.alloc(0);
        this.fail(new Error(`language server exited (${code ?? signal ?? "unknown"})`));
        if (this.disposed || this.stopping || this.restartAttempts >= 1) return;
        this.restartAttempts++;
        this.output.appendLine("C-plus language server exited; restarting once.");
        this.restartTimer = setTimeout(() => {
            this.restartTimer = undefined;
            void this.start().catch((error: unknown) => {
                this.output.appendLine(error instanceof Error ? error.message : String(error));
            });
        }, 250);
    }
}

export function lspCommand(command: string): string[] {
    return splitCommand(command);
}

function toLspPosition(position: vscode.Position): LspPosition {
    return { line: position.line, character: position.character };
}

function fromLspRange(range: LspRange): vscode.Range {
    return new vscode.Range(range.start.line, range.start.character, range.end.line, range.end.character);
}

function toDiagnostic(value: LspDiagnostic): vscode.Diagnostic | undefined {
    if (!value?.range || typeof value.message !== "string") return undefined;
    const diagnostic = new vscode.Diagnostic(fromLspRange(value.range), value.message,
        value.severity === 2 ? vscode.DiagnosticSeverity.Warning : vscode.DiagnosticSeverity.Error);
    diagnostic.source = value.source ?? "C-plus language server";
    diagnostic.code = value.code;
    return diagnostic;
}

function toDocumentSymbol(value: JsonObject): vscode.DocumentSymbol {
    const location = value.location as JsonObject;
    const range = fromLspRange(location.range as LspRange);
    const symbol = new vscode.DocumentSymbol(
        String(value.name ?? ""),
        String(value.detail ?? ""),
        Number(value.kind ?? vscode.SymbolKind.Namespace),
        range,
        range
    );
    const children = Array.isArray(value.children) ? value.children.map((child) => toDocumentSymbol(child as JsonObject)) : [];
    symbol.children = children;
    return symbol;
}
