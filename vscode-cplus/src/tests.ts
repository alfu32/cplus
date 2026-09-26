import { CPlusAstNode } from "./index";

export interface CPlusTestFixture {
    name: string;
    start: number;
    end: number;
}

function matchingBrace(source: string, open: number): number {
    let depth = 0;
    let quote = "";
    let lineComment = false;
    let blockComment = false;
    for (let i = open; i < source.length; i++) {
        const current = source[i];
        const next = source[i + 1] ?? "";
        if (lineComment) {
            if (current === "\n") lineComment = false;
            continue;
        }
        if (blockComment) {
            if (current === "*" && next === "/") { blockComment = false; i++; }
            continue;
        }
        if (quote) {
            if (current === "\\") i++;
            else if (current === quote) quote = "";
            continue;
        }
        if (current === "/" && next === "/") { lineComment = true; i++; continue; }
        if (current === "/" && next === "*") { blockComment = true; i++; continue; }
        if (current === "\"" || current === "'") { quote = current; continue; }
        if (current === "{") depth++;
        if (current === "}" && --depth === 0) return i + 1;
    }
    return -1;
}

/** Finds named @test fixtures while ignoring braces in strings and comments. */
export function findTestFixtures(source: string): CPlusTestFixture[] {
    const fixtures: CPlusTestFixture[] = [];
    const declaration = /@test\s+(?:"((?:\\.|[^"\\])*)"|([^{}\n]+?))\s*\{/g;
    for (const match of source.matchAll(declaration)) {
        const start = match.index ?? 0;
        const open = start + match[0].lastIndexOf("{");
        const end = matchingBrace(source, open);
        if (end < 0) continue;
        const name = match[1] !== undefined
            ? match[1].replace(/\\([\\"])/g, "$1")
            : match[2].trim();
        if (name) fixtures.push({ name, start, end });
    }
    return fixtures;
}

/** Projects test fixtures from a current normalized parser tree, preserving UTF-16 spans. */
export function findTestFixturesFromAst(source: string, root: CPlusAstNode): CPlusTestFixture[] {
    const fixtures: CPlusTestFixture[] = [];
    const visit = (node: CPlusAstNode): void => {
        if (node.syntaxKind === "cplus_test_declaration") {
            const nameNode = node.children?.find((child) =>
                child.syntaxKind === "string_literal" || child.syntaxKind === "identifier");
            if (nameNode) {
                const rawName = source.slice(nameNode.span.startOffset, nameNode.span.endOffset);
                const name = nameNode.syntaxKind === "string_literal" ? decodeStringLiteral(rawName) : rawName.trim();
                if (name) fixtures.push({ name, start: node.span.startOffset, end: node.span.endOffset });
            }
        }
        node.children?.forEach(visit);
    };
    visit(root);
    return fixtures;
}

function decodeStringLiteral(literal: string): string {
    const content = literal.startsWith('"') && literal.endsWith('"') ? literal.slice(1, -1) : literal;
    let decoded = "";
    for (let index = 0; index < content.length; index++) {
        const character = content[index];
        if (character !== "\\" || index + 1 === content.length) {
            decoded += character;
            continue;
        }
        const escaped = content[++index];
        decoded += escaped === "n" ? "\n" : escaped === "r" ? "\r" : escaped === "t" ? "\t" : escaped;
    }
    return decoded;
}
