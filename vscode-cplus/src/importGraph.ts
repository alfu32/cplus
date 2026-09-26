export interface CPlusImportEdge {
    importer: string;
    imported: string;
    location: { startLine: number; startColumn: number };
}

export interface CPlusImportGraph {
    dependencyOrder: string[];
    imports: CPlusImportEdge[];
}

/** Decode the compiler's versioned import-graph output, rejecting incomplete payloads. */
export function decodeImportGraph(value: unknown): CPlusImportGraph {
    if (!value || typeof value !== "object") throw new Error("C-plus import graph response is not an object");
    const payload = value as Record<string, unknown>;
    if (payload.schema !== "cplus.imports.v1") throw new Error("unsupported C-plus import graph schema");
    if (!Array.isArray(payload.dependencyOrder) || !payload.dependencyOrder.every((entry) => typeof entry === "string")) {
        throw new Error("C-plus import graph has invalid dependency order");
    }
    if (!Array.isArray(payload.imports)) throw new Error("C-plus import graph has invalid edges");
    const imports = payload.imports.map((entry): CPlusImportEdge => {
        if (!entry || typeof entry !== "object") throw new Error("C-plus import graph has an invalid edge");
        const edge = entry as Record<string, unknown>;
        const location = edge.location as Record<string, unknown> | undefined;
        if (typeof edge.importer !== "string" || typeof edge.imported !== "string" ||
            !location || typeof location.startLine !== "number" || typeof location.startColumn !== "number") {
            throw new Error("C-plus import graph has an incomplete edge");
        }
        return {
            importer: edge.importer,
            imported: edge.imported,
            location: { startLine: location.startLine, startColumn: location.startColumn }
        };
    });
    return { dependencyOrder: payload.dependencyOrder, imports };
}
