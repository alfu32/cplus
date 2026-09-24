# C-plus I/O Facades

Status: C `<stdio.h>` wrappers, with both a raw compatibility facade and a value-based `file_t` stream wrapper.

## Import and `file_t`

```c
comptime import "stdlib:/io/file.cp";

file_t stream = file_t.fopen("notes.txt", "w+");
if (stream.stream != NULL) {
    defer stream.fclose();
    stream.fprintf("%s %d\n", "answer", 42);
    stream.rewind();

    char label[32];
    int value = 0;
    if (stream.fscanf("%31s %d", label, &value) == 2)
        var_io_t.printf("%s=%d\n", label, value);
}
```

`file_t` is currently a small value wrapper around an owned `FILE*`, not a path object. `file_t.fopen(path, mode)` and `file_t.tmpfile()` return the wrapper by value; on failure its `stream` field is `NULL`. Instance operations infer the receiver address (`stream.fwrite(...)`). `stream.fclose()` closes the C stream and clears `stream.stream`; it does not free the `file_t` value. Use `defer` to close on scope exit.

Its instance API groups file-stream operations: `freopen`, `fclose`, `fflush`, buffering, formatted `fprintf`/`fscanf` and `v*` forms, character/line/block I/O, positioning, and stream status. `remove` and `rename` are static path operations on `file_t` for now, although they are conceptually filesystem operations rather than stream methods.

## `var_io_t` and `stdio_t`

`var_io_t` groups process-global standard I/O (`printf`, `scanf`, `getchar`, `putchar`, `puts`, `perror`) and buffer/string conversion (`sprintf`, `snprintf`, `sscanf` and their available `v*` variants). `var_io_t.standard()` returns the current `stdin`, `stdout`, and `stderr` handles as fields. The global wrappers still call libc's global streams; the returned handles are not yet connected to instance methods.

`stdio_t` retains the original raw C-style static facade for compatibility: functions accept and return `FILE*` directly, e.g. `stdio_t.fopen(...)`, `stdio_t.fwrite(..., stream)`, and `stdio_t.fclose(stream)`. New code should prefer `file_t` for an owned stream value and `var_io_t` for process-global I/O. Both facades forward variadic functions through matching `v*` functions.

## Safety and limitations

The ownership/access annotations are documentation only; C-plus does not enforce their lifetime promises. Close each successfully opened `file_t` exactly once. In C, `freopen` closes the previous stream even if reopening fails; this wrapper records the resulting `NULL` stream. A custom `file_t.buffer` is borrowed: assign caller-owned storage before calling `setbuf()` or `setvbuf()`, and keep it alive as required by C. `fread`/`fwrite` return complete items, not bytes. Format strings must match argument types and destination capacities.

`EOF`, `stdin`, `stdout`, `stderr`, `SEEK_SET`, and related macros come from `<stdio.h>`. The unsafe/obsolete `gets` and `tmpnam` functions are omitted. No `string_stream_t` exists yet; `sprintf`/`snprintf` currently target caller-provided buffers.

## Tests

`stdlib/tests/io.cp` covers string formatting/parsing, open failure, close-state clearing, temporary-file reads/writes, EOF, and deferred cleanup. Run `cpc test stdlib/tests/io.cp`.
