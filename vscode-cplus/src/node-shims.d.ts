declare module "node:child_process" {
    export function execFile(
        command: string,
        args: string[],
        options: { cwd?: string; maxBuffer?: number },
        callback: (error: Error | null, stdout: string, stderr: string) => void
    ): void;
}

declare module "node:fs/promises" {
    export function unlink(path: string): Promise<void>;
}

declare module "node:os" {
    export function tmpdir(): string;
}

declare module "node:path" {
    export function join(...parts: string[]): string;
}
