/** Maps the numeric LSP diagnostic severity to the adapter's stable category. */
export function lspDiagnosticKind(severity?: number): "warning" | "error" {
    return severity === 2 ? "warning" : "error";
}
