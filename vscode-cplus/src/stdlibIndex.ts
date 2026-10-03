import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join, normalize, resolve } from "node:path";

const cache = new Map<string, string[]>();

function files(root: string): string[] {
    if (!existsSync(root)) return [];
    const result: string[] = [];
    for (const entry of readdirSync(root)) {
        const path = join(root, entry);
        if (statSync(path).isDirectory()) result.push(...files(path));
        else if (path.endsWith(".cp") || path.endsWith(".c+")) result.push(path);
    }
    return result;
}

export function standardLibraryRoot(workspaceRoot?: string): string | undefined {
    const candidates: string[] = [];
    if (workspaceRoot) {
        const manifest = join(workspaceRoot, "cplus.toml");
        if (existsSync(manifest)) {
            const configured = /^\s*stdlib\s*=\s*"([^"]*)"\s*$/m.exec(readFileSync(manifest, "utf8"))?.[1];
            if (configured && configured !== "auto") candidates.push(resolve(workspaceRoot, configured));
        }
        let cursor = resolve(workspaceRoot);
        while (true) {
            candidates.push(join(cursor, "stdlib"));
            const parent = resolve(cursor, "..");
            if (parent === cursor) break;
            cursor = parent;
        }
    }
    if (process.env.CPLUS_STDLIB) candidates.push(process.env.CPLUS_STDLIB);
    if (process.env.CPLUS_HOME) candidates.push(join(process.env.CPLUS_HOME, "stdlib"));
    const home = process.env.HOME ?? process.env.USERPROFILE;
    if (home) {
        candidates.push(join(home, ".local", "share", "cplus", "stdlib"));
        candidates.push(join(home, ".local", "opt", "cplus", "stdlib"));
    }
    candidates.push("/usr/local/share/cplus/stdlib", "/usr/share/cplus/stdlib");
    return candidates.map(normalize).find((path) => existsSync(path) && statSync(path).isDirectory());
}

export function standardLibrarySymbols(workspaceRoot?: string): string[] {
    const root = standardLibraryRoot(workspaceRoot);
    const roots = [...(root ? [root] : []), ...projectModuleRoots(workspaceRoot)];
    if (roots.length === 0) return [];
    const cacheKey = roots.join(";");
    const cached = cache.get(cacheKey);
    if (cached) return cached;
    const symbols = new Set<string>();
    for (const source of roots.flatMap(files)) {
        const text = readFileSync(source, "utf8");
        for (const match of text.matchAll(/^\s*#\s*define\s+([A-Za-z_]\w*)/gm)) symbols.add(match[1]);
        for (const match of text.matchAll(/^\s*typedef\s+(?:struct|enum|union)?[^;{}]*\b([A-Za-z_]\w*_t)\s*;/gm)) symbols.add(match[1]);
        for (const match of text.matchAll(/^\s*(?:pub\s+|static\s+|extern\s+|const\s+|unsigned\s+|signed\s+|long\s+|short\s+|struct\s+|enum\s+|union\s+|[A-Za-z_]\w*_t\s+|[A-Za-z_]\w+\s+)+([A-Za-z_]\w*)\s*\(/gm)) {
            if (!["if", "for", "while", "switch"].includes(match[1])) symbols.add(match[1]);
        }
    }
    const result = [...symbols].sort();
    cache.set(cacheKey, result);
    return result;
}

export function projectModuleRoots(workspaceRoot?: string): string[] {
    if (!workspaceRoot) return [];
    const visited = new Set<string>();
    const visit = (root: string): string[] => {
        const identity = normalize(root);
        if (visited.has(identity) || !existsSync(identity)) return [];
        visited.add(identity);
        const manifest = join(identity, "cplus.toml");
        if (!existsSync(manifest)) return [];
        const text = readFileSync(manifest, "utf8");
        const moduleText = /^\s*module-paths\s*=\s*\[([^]]*)]/m.exec(text)?.[1];
        const paths = moduleText ? [...moduleText.matchAll(/"([^"]+)"/g)].map((match) => match[1]) : ["src", "modules"];
        const result = paths.map((path) => resolve(identity, path)).filter((path) => existsSync(path) && statSync(path).isDirectory());
        const dependencyText = /\[dependencies]([\s\S]*?)(?:\n\[|$)/.exec(text)?.[1] ?? "";
        for (const match of dependencyText.matchAll(/^\s*[A-Za-z_]\w*\s*=\s*\{[^}]*path\s*=\s*"([^"]+)"/gm)) {
            result.push(...visit(resolve(identity, match[1])));
        }
        return result;
    };
    return [...new Set(visit(workspaceRoot))];
}

export function standardLibraryImports(workspaceRoot?: string): string[] {
    const root = standardLibraryRoot(workspaceRoot);
    if (!root) return [];
    return files(root).map((source) => {
        const relative = source.slice(root.length + 1).replace(/\\/g, "/").replace(/\.(cp|c\+)$/, "");
        return `stdlib:/${relative}`;
    }).sort();
}
