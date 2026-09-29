export function splitCommand(command: string): string[] {
    return [...command.matchAll(/"([^\"]*)"|'([^']*)'|([^\s]+)/g)]
        .map((match) => match[1] ?? match[2] ?? match[3] ?? "");
}
