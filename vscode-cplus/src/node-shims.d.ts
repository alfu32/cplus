declare const process: { env: Record<string, string | undefined> };

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
        options: { cwd?: string; maxBuffer?: number; env?: Record<string, string | undefined> },
        callback: (error: Error | null, stdout: string, stderr: string) => void
    ): ExecFileChildProcess;
}

declare module "node:fs/promises" {
    export function unlink(path: string): Promise<void>;
}

declare module "node:fs" {
    export function existsSync(path: string): boolean;
    export function readFileSync(path: string, encoding: "utf8"): string;
    export function readdirSync(path: string): string[];
    export function statSync(path: string): { isDirectory(): boolean };
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
    export function normalize(path: string): string;
    export function resolve(...parts: string[]): string;
}
