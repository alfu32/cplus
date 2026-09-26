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
    pointerVariables: Set<string>;
}

export interface CPlusMemberContext {
    receiver: string;
    type: string;
    operator: "." | "->";
    isPointer: boolean;
    isStatic: boolean;
}

export interface CPlusAstSpan {
    startOffset: number;
    endOffset: number;
}

export interface CPlusAstNode {
    kind: string;
    syntaxKind?: string;
    field?: string | null;
    span: CPlusAstSpan;
    children?: CPlusAstNode[];
}

function descendants(node: CPlusAstNode): CPlusAstNode[] {
    return (node.children ?? []).flatMap((child) => [child, ...descendants(child)]);
}

function nodeName(source: string, node?: CPlusAstNode): string | undefined {
    if (!node) return undefined;
    const name = source.slice(node.span.startOffset, node.span.endOffset).trim();
    return /^[A-Za-z_][A-Za-z_0-9]*$/.test(name) ? name : undefined;
}

/** Project the normalized parser tree into the editor's stable outline-symbol model. */
export function symbolsFromAst(source: string, root: CPlusAstNode): CPlusSymbol[] {
    const symbols: CPlusSymbol[] = [];
    const identifier = (node: CPlusAstNode, syntaxKinds?: Set<string>): CPlusAstNode | undefined =>
        descendants(node).find((child) => child.kind === "identifier" && child.field === "declarator" &&
            (!syntaxKinds || syntaxKinds.has(child.syntaxKind ?? "")));

    for (const declaration of root.children ?? []) {
        if (declaration.kind === "type_alias") {
            const structure = declaration.children?.find((child) => child.kind === "struct_declaration");
            if (!structure) continue;
            const aliasNode = declaration.children?.find((child) => child.field === "declarator" && child.kind === "identifier");
            const typeName = nodeName(source, aliasNode) ?? nodeName(source, identifier(structure, new Set(["type_identifier"]))) ?? "anonymous_struct";
            const type: CPlusSymbol = {
                name: typeName,
                kind: "type",
                detail: "struct " + typeName,
                start: declaration.span.startOffset,
                end: declaration.span.endOffset,
                children: []
            };
            const body = structure.children?.find((child) => child.field === "body");
            for (const member of body?.children ?? []) {
                if (member.kind === "field_declaration") {
                    const nameNode = identifier(member);
                    const name = nodeName(source, nameNode);
                    if (!name || !nameNode) continue;
                    const memberText = source.slice(member.span.startOffset, member.span.endOffset).replace(/;\s*$/, "").trim();
                    type.children?.push({
                        name,
                        kind: "field",
                        detail: memberText,
                        owner: typeName,
                        start: nameNode.span.startOffset,
                        end: nameNode.span.endOffset
                    });
                } else if (member.kind === "method_declaration") {
                    const nameNode = identifier(member);
                    const name = nodeName(source, nameNode);
                    if (!name || !nameNode) continue;
                    const header = source.slice(member.span.startOffset, member.span.endOffset).split("{")[0].trim().replace(/;$/, "");
                    type.children?.push({
                        name,
                        kind: "method",
                        detail: header,
                        owner: typeName,
                        isStatic: descendants(member).some((child) => child.syntaxKind === "cplus_static_modifier"),
                        start: nameNode.span.startOffset,
                        end: nameNode.span.endOffset
                    });
                }
            }
            symbols.push(type);
        } else if (declaration.kind === "function_declaration") {
            const nameNode = identifier(declaration);
            const name = nodeName(source, nameNode);
            if (!name || !nameNode) continue;
            const header = source.slice(declaration.span.startOffset, declaration.span.endOffset).split("{")[0].trim().replace(/;$/, "");
            symbols.push({
                name,
                kind: "function",
                detail: header,
                start: nameNode.span.startOffset,
                end: nameNode.span.endOffset
            });
        }
    }
    return symbols;
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
        variableTypes: new Map(),
        pointerVariables: new Set()
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

    const macroAliasPattern = /^[ \t]*#\s*define\s+([A-Za-z_]\w*)[ \t]+([A-Za-z_]\w*)[ \t]*$/gm;
    for (const match of text.matchAll(macroAliasPattern)) {
        const generatedName = match[1];
        const publicName = match[2];
        if (index.byName.has(publicName)) continue;
        const start = (match.index ?? 0) + match[0].lastIndexOf(publicName);
        add(index, {
            name: publicName,
            kind: "function",
            detail: "C preprocessor alias for " + generatedName,
            start,
            end: start + publicName.length
        });
    }

    const variablePattern = /\b([A-Za-z_]\w*_t)\s*(\*+)?\s*([A-Za-z_]\w*)\s*(?=[;=,)\[])/g;
    for (const match of text.matchAll(variablePattern)) {
        const variable = match[3];
        index.variableTypes.set(variable, match[1]);
        if (match[2]) index.pointerVariables.add(variable);
        else index.pointerVariables.delete(variable);
        add(index, {
            name: variable,
            kind: "variable",
            detail: match[1] + (match[2] ?? "") + " " + variable,
            start: (match.index ?? 0) + match[0].lastIndexOf(variable),
            end: (match.index ?? 0) + match[0].lastIndexOf(variable) + variable.length
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

export function memberContext(index: CPlusIndex, linePrefix: string): CPlusMemberContext | undefined {
    const match = /([A-Za-z_]\w*)\s*(\.|->)\s*[A-Za-z_]*$/.exec(linePrefix);
    if (!match) return undefined;

    const receiver = match[1];
    const operator = match[2] as "." | "->";
    const type = index.variableTypes.get(receiver);
    if (type) {
        const isPointer = index.pointerVariables.has(receiver);
        if ((isPointer && operator !== "->") || (!isPointer && operator !== ".")) return undefined;
        return { receiver, type, operator, isPointer, isStatic: false };
    }
    if (receiver.endsWith("_t") && operator === ".") {
        return { receiver, type: receiver, operator, isPointer: false, isStatic: true };
    }
    return undefined;
}
