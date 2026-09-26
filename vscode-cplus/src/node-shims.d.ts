declare module "node:child_process" {
    export interface ExecFileChildProcess {
        kill(): boolean;
        stdin: {
            end(data?: string): void;
            on(event: "error", listener: (error: Error) => void): void;
        };
    }
    export function execFile(
        command: string,
        args: string[],
        options: { cwd?: string; maxBuffer?: number },
        callback: (error: Error | null, stdout: string, stderr: string) => void
    ): ExecFileChildProcess;
}

declare module "node:fs/promises" {
    export function unlink(path: string): Promise<void>;
}

declare module "node:os" {
    export function tmpdir(): string;
}

declare module "node:timers" {
    export interface Timeout {}
    export function setTimeout(callback: () => void, delay?: number): Timeout;
    export function clearTimeout(timeout: Timeout): void;
}

declare module "node:path" {
    export function basename(path: string): string;
    export function join(...parts: string[]): string;
}
