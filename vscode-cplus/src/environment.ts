declare const require: (name: string) => any;
const inherited: { env: Record<string, string | undefined>; platform: string } = require("node:process");

/** Expand assignments in order without executing shell code or modifying the host. */
export function commandEnvironment(
    overrides: Record<string, string> = {},
    base: Record<string, string | undefined> = inherited.env,
    caseInsensitive: boolean = inherited.platform === "win32"
): Record<string, string | undefined> {
    const environment = { ...base };
    const key = (name: string): string => caseInsensitive
        ? Object.keys(environment).find((candidate) => candidate.toLowerCase() === name.toLowerCase()) ?? name
        : name;
    for (const [name, raw] of Object.entries(overrides)) {
        if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) throw new Error(`Invalid environment name: ${name}`);
        const quoted = raw.length >= 2 && (raw[0] === '"' && raw.endsWith('"') || raw[0] === "'" && raw.endsWith("'"));
        const value = quoted ? raw.slice(1, -1) : raw;
        environment[key(name)] = value.replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)|%([A-Za-z_][A-Za-z0-9_]*)%/g,
            (_, braced, plain, windows) => environment[key(braced ?? plain ?? windows)] ?? "");
    }
    return environment;
}
