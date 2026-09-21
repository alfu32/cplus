export type CPlusSymbolKind = "type" | "field" | "method" | "function" | "variable" | "comptime";

export interface CPlusSymbol {
    name: string;
    kind: CPlusSymbolKind;
    detail: string;
    start: number;
    end: number;
    owner?: string;
    isStatic?: boolean;
    children?: CPlusSymbol[];
}

export interface CPlusIndex {
    symbols: CPlusSymbol[];
    byName: Map<string, CPlusSymbol[]>;
    fieldsByType: Map<string, CPlusSymbol[]>;
    methodsByType: Map<string, CPlusSymbol[]>;
    variableTypes: Map<string, string>;
}

function cleanName(name: string): string {
    return name.startsWith("@") ? name.slice(1) : name;
}

function add(index: CPlusIndex, symbol: CPlusSymbol): void {
    index.symbols.push(symbol);
    const entries = index.byName.get(symbol.name) ?? [];
    entries.push(symbol);
    index.byName.set(symbol.name, entries);
}

function matching(text: string, open: number, opener: string, closer: string): number {
    let depth = 0;
    let quote = "";
    let lineComment = false;
    let blockComment = false;
    for (let i = open; i < text.length; i++) {
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
            continue;
        }
        if (character === "/" && next === "*") {
            blockComment = true;
            i++;
            continue;
        }
        if (character === "\"" || character === "'") {
            quote = character;
            continue;
        }
        if (character === opener) depth++;
        if (character === closer) {
            depth--;
            if (depth === 0) return i;
        }
    }
    return -1;
}

function topLevelStatements(text: string, start: number, end: number): Array<[number, number]> {
    const result: Array<[number, number]> = [];
    let segmentStart = start;
    let braces = 0;
    let quote = "";
    let lineComment = false;
    let blockComment = false;
    for (let i = start; i < end; i++) {
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
            continue;
        }
        if (character === "/" && next === "*") {
            blockComment = true;
            i++;
            continue;
        }
        if (character === "\"" || character === "'") {
            quote = character;
            continue;
        }
        if (character === "{") braces++;
        if (character === "}") braces = Math.max(0, braces - 1);
        if (character === ";" && braces === 0) {
            result.push([segmentStart, i + 1]);
            segmentStart = i + 1;
        }
    }
    return result;
}

function parseTypeAndName(statement: string): { type: string; name: string } | undefined {
    const match = /^(?:\s*(?:pub|priv|mut|borrowed|owned|stat|static)\s+)*([\w\s*]+?)\s+([A-Za-z_]\w*)\s*(?:\[[^\]]*\])?\s*;?$/.exec(statement);
    if (!match) return undefined;
    return {
        type: match[1].replace(/\s+/g, " ").trim(),
        name: match[2]
    };
}

export function indexText(text: string): CPlusIndex {
    const index: CPlusIndex = {
        symbols: [],
        byName: new Map(),
        fieldsByType: new Map(),
        methodsByType: new Map(),
        variableTypes: new Map()
    };
    const structRanges: Array<[number, number]> = [];
    const structPattern = /typedef\s+struct\s+(@?[A-Za-z_]\w*)?\s*\{/g;
    for (const match of text.matchAll(structPattern)) {
        const start = match.index ?? 0;
        const open = text.indexOf("{", start);
        const close = matching(text, open, "{", "}");
        if (close < 0) continue;
        const alias = /^\s*([A-Za-z_]\w*)\s*;/.exec(text.slice(close + 1))?.[1];
        const tag = match[1] ? cleanName(match[1]) : alias ?? "anonymous_struct";
        const typeName = alias ?? tag;
        const typeSymbol: CPlusSymbol = {
            name: typeName,
            kind: "type",
            detail: "struct " + typeName,
            start,
            end: alias ? close + 1 + (text.slice(close + 1).indexOf(";") + 1) : close + 1,
            children: []
        };
        add(index, typeSymbol);
        structRanges.push([start, close + 1]);

        const fields: CPlusSymbol[] = [];
        for (const [fieldStart, fieldEnd] of topLevelStatements(text, open + 1, close)) {
            const field = parseTypeAndName(text.slice(fieldStart, fieldEnd));
            if (!field || text.slice(fieldStart, fieldEnd).includes("(")) continue;
            const nameOffset = text.indexOf(field.name, fieldStart);
            const fieldSymbol: CPlusSymbol = {
                name: field.name,
                kind: "field",
                detail: field.type + " " + field.name,
                owner: typeName,
                start: nameOffset,
                end: nameOffset + field.name.length
            };
            fields.push(fieldSymbol);
            typeSymbol.children?.push(fieldSymbol);
        }
        index.fieldsByType.set(typeName, fields);

        const body = text.slice(open + 1, close);
        const methodPattern = /(?:(static)\s+)?(?:(pub|priv)\s+)?([A-Za-z_][\w\s*]*?)\s+([A-Za-z_]\w*)\s*\(/g;
        const methods: CPlusSymbol[] = [];
        for (const method of body.matchAll(methodPattern)) {
            const name = method[4];
            if (["if", "for", "while", "switch"].includes(name)) continue;
            const nameOffset = open + 1 + (method.index ?? 0) + method[0].lastIndexOf(name);
            const methodSymbol: CPlusSymbol = {
                name,
                kind: "method",
                detail: (method[3].replace(/\s+/g, " ").trim() || "void") + " " + name + "(...)",
                owner: typeName,
                isStatic: Boolean(method[1]),
                start: nameOffset,
                end: nameOffset + name.length
            };
            methods.push(methodSymbol);
            typeSymbol.children?.push(methodSymbol);
        }
        index.methodsByType.set(typeName, methods);
        methods.forEach((method) => add(index, method));
        fields.forEach((field) => add(index, field));
    }

    const functionPattern = /(?:^|[;}\n])\s*(?:(?:pub|priv|static)\s+)*([A-Za-z_][\w\s*]*?)\s+([A-Za-z_]\w*)\s*\(/g;
    for (const match of text.matchAll(functionPattern)) {
        const start = match.index ?? 0;
        if (structRanges.some(([from, to]) => start >= from && start < to)) continue;
        const name = match[2];
        if (["if", "for", "while", "switch"].includes(name)) continue;
        const nameOffset = start + match[0].lastIndexOf(name);
        add(index, {
            name,
            kind: "function",
            detail: match[1].replace(/\s+/g, " ").trim() + " " + name + "(...)",
            start: nameOffset,
            end: nameOffset + name.length
        });
    }

    const variablePattern = /\b([A-Za-z_]\w*_t)\s+([A-Za-z_]\w*)\s*(?:[;=])/g;
    for (const match of text.matchAll(variablePattern)) {
        index.variableTypes.set(match[2], match[1]);
        add(index, {
            name: match[2],
            kind: "variable",
            detail: match[1] + " " + match[2],
            start: (match.index ?? 0) + match[0].lastIndexOf(match[2]),
            end: (match.index ?? 0) + match[0].lastIndexOf(match[2]) + match[2].length
        });
    }

    const comptimePattern = /@([A-Za-z_]\w*)/g;
    for (const match of text.matchAll(comptimePattern)) {
        const name = match[1];
        if (["import", "if", "else", "for", "type", "var", "fn"].includes(name)) continue;
        add(index, {
            name: "@" + name,
            kind: "comptime",
            detail: "comptime symbol @" + name,
            start: match.index ?? 0,
            end: (match.index ?? 0) + match[0].length
        });
    }
    return index;
}
