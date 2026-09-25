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
