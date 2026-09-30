declare const require: (moduleName: string) => any;
declare const process: { platform: string; env?: { [key: string]: string | undefined } };
const fileSystem = require("node:fs") as { existsSync(path: string): boolean };
const pathModule = require("node:path") as { join(...parts: string[]): string };

export interface LspDiscoveryOptions {
    configured?: string;
    workspaceRoot?: string;
    platform?: string;
    exists?: (path: string) => boolean;
    cplusHome?: string;
    userHome?: string;
}

/**
 * Resolve the executable without making editor configuration carry packaging
 * knowledge. An explicitly configured command always wins; otherwise a
 * project-local launchers are preferred over installed commands. Keep this
 * order aligned with the IntelliJ and Vim clients so IDEs started outside a
 * login shell can still find a repository or user-local launcher.
 */
export function resolveLanguageServerCommand(options: LspDiscoveryOptions = {}): string[] {
    const configured = options.configured?.trim() ?? "";
    if (configured.length > 0) return splitCommand(configured);

    const root = options.workspaceRoot?.trim();
    const exists = options.exists ?? fileSystem.existsSync;
    const candidates = (options.platform ?? process.platform) === "win32"
            ? ["cpc.cmd", "cpc.exe", "cpc"]
            : ["cpc.sh", "cpc"];
    if (root) {
        for (const directory of [".cplus", "c-plus-bin", ""]) {
            for (const candidate of candidates) {
                const path = pathModule.join(root, directory, candidate);
                if (exists(path)) return [path];
            }
        }
    }
    const environment = process.env ?? {};
    const cplusHome = options.cplusHome ?? environment.CPLUS_HOME;
    if (cplusHome) {
        for (const candidate of candidates) {
            const path = pathModule.join(cplusHome, candidate);
            if (exists(path)) return [path];
        }
    }
    const userHome = options.userHome ?? environment.HOME ?? environment.USERPROFILE;
    if (userHome) {
        for (const directory of [
            pathModule.join(userHome, ".local", "bin"),
            pathModule.join(userHome, ".local", "bin", "c-plus"),
            pathModule.join(userHome, ".local", "share", "c-plus")
        ]) {
            for (const candidate of candidates) {
                const path = pathModule.join(directory, candidate);
                if (exists(path)) return [path];
            }
        }
    }
    return ["cpc"];
}

export function languageServerDiscoveryFailure(command: string[]): string {
    return `C-plus language server '${command.join(" ")}' could not be started. ` +
        "Set cplus.languageServerCommand, add a project .cplus/cpc(.sh|.cmd) or " +
        "c-plus-bin/cpc launcher, or install the cpc launcher and add it to PATH.";
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
