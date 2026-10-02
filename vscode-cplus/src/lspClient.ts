import * as vscode from "vscode";
import { clearTimeout, setTimeout } from "node:timers";
import { languageServerDiscoveryFailure, resolveLanguageServerCommand, splitCommand } from "./lspDiscovery";
import { lspDiagnosticKind } from "./lspDiagnostics";
import { commandEnvironment } from "./environment";

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

/** Must match the server's advertised semantic-token legend order. */
export const semanticTokenTypes = ["type", "struct", "enum", "typeParameter", "function", "method", "property", "variable", "parameter", "enumMember", "macro"];
export const semanticTokenModifiers = ["declaration"];

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
    cleanup: () => void;
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
        private command: string | string[],
        private arguments_: string[],
        private readonly output: vscode.OutputChannel,
        private environment: Record<string, string> = {},
        private requestTimeoutMs = 15000
    ) {}

    async start(): Promise<void> {
        if (this.started) return;
        if (this.disposed) throw new Error("language server client is disposed");
        const command = Array.isArray(this.command)
            ? this.command
            : resolveLanguageServerCommand({ configured: this.command });
        if (!command.length) throw new Error("cplus.languageServerCommand is empty");
        const child = childProcess.spawn(command[0], [...command.slice(1), ...this.arguments_, "lsp"], {
            cwd: vscode.workspace.workspaceFolders?.[0]?.uri.fsPath,
            env: commandEnvironment(this.environment),
            stdio: "pipe"
        });
        this.child = child;
        child.stdout.on("data", (chunk: Buffer) => this.consume(chunk));
        child.stderr.on("data", (chunk: Buffer) => this.output.append(chunk.toString()));
        child.stdin.on("error", (error: unknown) => {
            // A server may exit immediately after replying to initialize. The
            // follow-up initialized notification can then race with pipe
            // teardown; handle EPIPE as normal crash recovery rather than
            // allowing an unhandled stream error to terminate the host.
            if (this.child === child && !this.stopping) this.fail(error);
        });
        child.on("error", (error: unknown) => {
            if (this.child === child) this.fail(new Error(languageServerDiscoveryFailure(command)));
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
            const watcher = vscode.workspace.createFileSystemWatcher?.("**/*.{cp,c+}");
            if (watcher) {
                const changed = (uri: vscode.Uri, type: number): void => this.notify("workspace/didChangeWatchedFiles", {
                    changes: [{ uri: uri.toString(), type }]
                });
                this.disposables.push(watcher, watcher.onDidCreate((uri) => changed(uri, 1)),
                    watcher.onDidChange((uri) => changed(uri, 2)), watcher.onDidDelete((uri) => changed(uri, 3)));
            }
            if (vscode.workspace.onDidSaveTextDocument) this.disposables.push(
                vscode.workspace.onDidSaveTextDocument((document) => {
                    if (document.languageId === "cplus") this.notify("textDocument/didSave", { textDocument: { uri: document.uri.toString() } });
                })
            );
        }
    }

    /** Restart the external server while retaining open-document synchronization. */
    async restart(): Promise<void> {
        if (this.disposed) return;
        // A user-requested restart starts a fresh bounded crash-recovery window.
        this.restartAttempts = 0;
        if (this.restartTimer !== undefined) clearTimeout(this.restartTimer);
        this.restartTimer = undefined;
        this.stopping = true;
        this.fail(new Error("language server restarting"));
        this.stopProcess();
        this.stopping = false;
        await this.start();
    }

    async reconfigure(command: string[], arguments_: string[], environment: Record<string, string>, timeout: number): Promise<void> {
        // Validate the environment before stopping a working process.
        commandEnvironment(environment);
        this.command = command;
        this.arguments_ = arguments_;
        this.environment = environment;
        this.requestTimeoutMs = timeout;
        await this.restart();
    }

    async request<T>(method: string, params: unknown, token?: vscode.CancellationToken): Promise<T> {
        if (!this.child || !this.started && method !== "initialize") throw new Error("language server is not running");
        const id = this.nextId++;
        if (token?.isCancellationRequested) throw new Error("language server request cancelled");
        if (this.pending.size >= 128) throw new Error("too many pending language server requests");
        const promise = new Promise<unknown>((resolve, reject) => {
            let cancellation: vscode.Disposable | undefined;
            const timer = setTimeout(() => {
                const entry = this.pending.get(id);
                if (!entry) return;
                this.pending.delete(id);
                entry.cleanup();
                this.notify("$/cancelRequest", { id });
                reject(new Error(`Language server timed out: ${method}`));
            }, method === "initialize" ? Math.max(60000, this.requestTimeoutMs) : this.requestTimeoutMs);
            this.pending.set(id, { resolve, reject, cleanup: () => { clearTimeout(timer); cancellation?.dispose(); } });
            cancellation = token?.onCancellationRequested(() => {
                const entry = this.pending.get(id);
                if (!entry) return;
                this.pending.delete(id);
                entry.cleanup();
                this.notify("$/cancelRequest", { id });
                reject(new Error("language server request cancelled"));
            });
        });
        try { this.write({ jsonrpc: "2.0", id, method, params }); } catch (error) {
            const entry = this.pending.get(id);
            if (entry) { this.pending.delete(id); entry.cleanup(); entry.reject(error); }
        }
        return await promise as T;
    }

    notify(method: string, params: unknown): void {
        if (!this.child) return;
        this.write({ jsonrpc: "2.0", method, params });
    }

    async semanticTokens(document: vscode.TextDocument, token?: vscode.CancellationToken): Promise<vscode.SemanticTokens> {
        const result = await this.request<{ data: number[] }>("textDocument/semanticTokens/full", {
            textDocument: { uri: document.uri.toString() }
        }, token);
        return new vscode.SemanticTokens(new Uint32Array(result?.data ?? []));
    }

    async completion(document: vscode.TextDocument, position: vscode.Position, token?: vscode.CancellationToken): Promise<vscode.CompletionList> {
        const result = await this.request<{ items?: Array<{ label: string; kind?: number; detail?: string }> }>("textDocument/completion", {
            textDocument: { uri: document.uri.toString() },
            position: toLspPosition(position)
        }, token);
        const items = (result?.items ?? []).map((item) => {
            const completion = new vscode.CompletionItem(item.label, item.kind ? item.kind - 1 : vscode.CompletionItemKind.Text);
            completion.detail = item.detail;
            return completion;
        });
        return new vscode.CompletionList(items, false);
    }

    async hover(document: vscode.TextDocument, position: vscode.Position, token?: vscode.CancellationToken): Promise<vscode.Hover | undefined> {
        const result = await this.request<{ contents?: { value?: string }; range?: LspRange } | null>("textDocument/hover", {
            textDocument: { uri: document.uri.toString() },
            position: toLspPosition(position)
        }, token);
        if (!result?.contents) return undefined;
        const range = result.range ? fromLspRange(result.range) : undefined;
        return new vscode.Hover(new vscode.MarkdownString(result.contents.value ?? ""), range);
    }

    async definition(document: vscode.TextDocument, position: vscode.Position, token?: vscode.CancellationToken): Promise<vscode.Location[] | undefined> {
        const result = await this.request<LspLocation[]>("textDocument/definition", {
            textDocument: { uri: document.uri.toString() },
            position: toLspPosition(position)
        }, token);
        return result?.map((location) => new vscode.Location(vscode.Uri.parse(location.uri), fromLspRange(location.range)));
    }

    async documentSymbols(document: vscode.TextDocument, token?: vscode.CancellationToken): Promise<vscode.DocumentSymbol[]> {
        const result = await this.request<Array<JsonObject>>("textDocument/documentSymbol", {
            textDocument: { uri: document.uri.toString() }
        }, token);
        return (result ?? []).map((symbol) => toDocumentSymbol(symbol));
    }

    async references(document: vscode.TextDocument, position: vscode.Position, includeDeclaration: boolean, token?: vscode.CancellationToken): Promise<vscode.Location[]> {
        const result = await this.request<LspLocation[]>("textDocument/references", {
            textDocument: { uri: document.uri.toString() }, position: toLspPosition(position), context: { includeDeclaration }
        }, token);
        return (result ?? []).map((location) => new vscode.Location(vscode.Uri.parse(location.uri), fromLspRange(location.range)));
    }

    async highlights(document: vscode.TextDocument, position: vscode.Position, token?: vscode.CancellationToken): Promise<vscode.DocumentHighlight[]> {
        const result = await this.request<Array<{ range: LspRange; kind: number }>>("textDocument/documentHighlight", {
            textDocument: { uri: document.uri.toString() }, position: toLspPosition(position)
        }, token);
        return (result ?? []).map((item) => new vscode.DocumentHighlight(fromLspRange(item.range), item.kind - 1));
    }

    async workspaceSymbols(query: string, token?: vscode.CancellationToken): Promise<vscode.SymbolInformation[]> {
        const result = await this.request<Array<{ name: string; kind: number; containerName?: string; location: LspLocation }>>("workspace/symbol", { query }, token);
        return (result ?? []).map((item) => new vscode.SymbolInformation(item.name, item.kind - 1, item.containerName ?? "",
            new vscode.Location(vscode.Uri.parse(item.location.uri), fromLspRange(item.location.range))));
    }

    async signatureHelp(document: vscode.TextDocument, position: vscode.Position, token?: vscode.CancellationToken): Promise<vscode.SignatureHelp | undefined> {
        const result = await this.request<{ signatures: Array<{ label: string; parameters: Array<{ label: string }> }>; activeSignature: number; activeParameter: number } | null>(
            "textDocument/signatureHelp", { textDocument: { uri: document.uri.toString() }, position: toLspPosition(position) }, token);
        if (!result) return undefined;
        const help = new vscode.SignatureHelp();
        help.signatures = result.signatures.map((item) => {
            const signature = new vscode.SignatureInformation(item.label);
            signature.parameters = item.parameters.map((parameter) => new vscode.ParameterInformation(parameter.label));
            return signature;
        });
        help.activeSignature = result.activeSignature;
        help.activeParameter = result.activeParameter;
        return help;
    }

    async foldingRanges(document: vscode.TextDocument, token?: vscode.CancellationToken): Promise<vscode.FoldingRange[]> {
        const result = await this.request<Array<{ startLine: number; endLine: number; kind: string }>>("textDocument/foldingRange", {
            textDocument: { uri: document.uri.toString() }
        }, token);
        return (result ?? []).map((item) => new vscode.FoldingRange(item.startLine, item.endLine,
            item.kind === "comment" ? vscode.FoldingRangeKind.Comment : vscode.FoldingRangeKind.Region));
    }

    async codeLenses(document: vscode.TextDocument, token?: vscode.CancellationToken): Promise<vscode.CodeLens[]> {
        const result = await this.request<Array<{ range: LspRange; command: vscode.Command }>>("textDocument/codeLens", {
            textDocument: { uri: document.uri.toString() }
        }, token);
        return (result ?? []).map((item) => new vscode.CodeLens(fromLspRange(item.range), item.command));
    }

    reportFailure(error: unknown): void {
        this.output.appendLine(error instanceof Error ? error.message : JSON.stringify(error));
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
            if (separator < 0) {
                if (this.buffer.length > 8192) this.protocolFailure("language server header exceeds 8 KiB");
                return;
            }
            const header = this.buffer.subarray(0, separator).toString("ascii");
            const length = /^Content-Length:\s*(\d+)$/im.exec(header)?.[1];
            if (!length) {
                this.buffer = this.buffer.subarray(separator + 4);
                continue;
            }
            const bodyStart = separator + 4;
            const bodyLength = Number(length);
            if (!Number.isSafeInteger(bodyLength) || bodyLength > 16 * 1024 * 1024) {
                this.protocolFailure("language server frame exceeds 16 MiB");
                return;
            }
            const bodyEnd = bodyStart + bodyLength;
            if (this.buffer.length < bodyEnd) return;
            const body = this.buffer.subarray(bodyStart, bodyEnd).toString("utf8");
            this.buffer = this.buffer.subarray(bodyEnd);
            this.receive(body);
        }
    }

    private protocolFailure(message: string): void {
        this.output.appendLine(message);
        this.fail(new Error(message));
        this.stopProcess();
    }

    private receive(body: string): void {
        let message: JsonObject;
        try { message = JSON.parse(body) as JsonObject; } catch { return; }
        if (message.method === "textDocument/publishDiagnostics") {
            const params = message.params as JsonObject | undefined;
            const uri = typeof params?.uri === "string" ? vscode.Uri.parse(params.uri) : undefined;
            if (!uri) return;
            const document = vscode.workspace.textDocuments.find((candidate) => candidate.uri.toString() === uri.toString());
            if (document && typeof params?.version === "number" && params.version < document.version) return;
            const rawDiagnostics = Array.isArray(params?.diagnostics) ? params.diagnostics : [];
            const diagnostics = rawDiagnostics
                .map((item) => toDiagnostic(item as LspDiagnostic))
                .filter((item): item is vscode.Diagnostic => Boolean(item));
            this.diagnostics.set(uri, diagnostics);
            return;
        }
        if (message.method === "window/logMessage" || message.method === "window/showMessage") {
            const params = message.params as JsonObject | undefined;
            this.output.appendLine(String(params?.message ?? ""));
            return;
        }
        const id = typeof message.id === "number" ? message.id : undefined;
        if (id === undefined) return;
        const pending = this.pending.get(id);
        if (!pending) return;
        this.pending.delete(id);
        pending.cleanup();
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
        pending.forEach((request) => { request.cleanup(); request.reject(error); });
    }

    private stopProcess(): void {
        const child = this.child;
        this.child = undefined;
        this.started = false;
        this.buffer = Buffer.alloc(0);
        this.diagnostics.clear?.();
        if (child) child.kill();
    }

    private onExit(child: any, code: number | null, signal: string | null): void {
        if (this.child !== child) return;
        this.child = undefined;
        this.started = false;
        this.buffer = Buffer.alloc(0);
        this.diagnostics.clear?.();
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
        lspDiagnosticKind(value.severity) === "warning"
            ? vscode.DiagnosticSeverity.Warning
            : vscode.DiagnosticSeverity.Error);
    diagnostic.source = value.source ?? "C-plus language server";
    diagnostic.code = value.code;
    return diagnostic;
}

function toDocumentSymbol(value: JsonObject): vscode.DocumentSymbol {
    const location = value.location as JsonObject | undefined;
    const range = fromLspRange((value.range ?? location?.range) as LspRange);
    const selection = value.selectionRange ? fromLspRange(value.selectionRange as LspRange) : range;
    const symbol = new vscode.DocumentSymbol(
        String(value.name ?? ""),
        String(value.detail ?? ""),
        typeof value.kind === "number" ? value.kind - 1 : vscode.SymbolKind.Namespace,
        range,
        selection
    );
    const children = Array.isArray(value.children) ? value.children.map((child) => toDocumentSymbol(child as JsonObject)) : [];
    symbol.children = children;
    return symbol;
}
