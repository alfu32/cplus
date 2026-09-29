declare const require: (moduleName: string) => any;
declare const process: { platform: string };
const fileSystem = require("node:fs") as { existsSync(path: string): boolean };
const pathModule = require("node:path") as { join(...parts: string[]): string };

export interface LspDiscoveryOptions {
    configured?: string;
    workspaceRoot?: string;
    platform?: string;
    exists?: (path: string) => boolean;
}

/**
 * Resolve the executable without making editor configuration carry packaging
 * knowledge. An explicitly configured command always wins; otherwise a
 * project-local launcher is preferred over the installed `cpc` command.
 */
export function resolveLanguageServerCommand(options: LspDiscoveryOptions = {}): string[] {
    const configured = options.configured?.trim() ?? "";
    if (configured.length > 0) return splitCommand(configured);

    const root = options.workspaceRoot?.trim();
    const exists = options.exists ?? fileSystem.existsSync;
    if (root) {
        const candidates = (options.platform ?? process.platform) === "win32"
            ? ["cpc.cmd", "cpc.exe", "cpc"]
            : ["cpc.sh", "cpc"];
        for (const candidate of candidates) {
            const path = pathModule.join(root, ".cplus", candidate);
            if (exists(path)) return [path];
        }
    }
    return ["cpc"];
}

export function languageServerDiscoveryFailure(command: string[]): string {
    return `C-plus language server '${command.join(" ")}' could not be started. ` +
        "Set cplus.languageServerCommand, add .cplus/cpc(.sh|.cmd) to the project, " +
        "or install the cpc launcher and add it to PATH.";
}

export function splitCommand(command: string): string[] {
    const result: string[] = [];
    const current: string[] = [];
    let quote = "";
    let escaped = false;
    for (const character of command) {
        if (escaped) {
            current.push(character);
            escaped = false;
        } else if (character === "\\" && quote !== "'") {
            escaped = true;
        } else if (quote && character === quote) {
            quote = "";
        } else if (!quote && (character === "'" || character === '"')) {
            quote = character;
        } else if (!quote && /\s/.test(character)) {
            if (current.length > 0) {
                result.push(current.join(""));
                current.length = 0;
            }
        } else {
            current.push(character);
        }
    }
    if (escaped) current.push("\\");
    if (current.length > 0) result.push(current.join(""));
    return result;
}
