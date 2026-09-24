# C-plus I/O, Streams, and Formatting

Status: receiver-oriented `FILE*` streams, file/path factories, configurable standard I/O, plus the retained raw compatibility facade.

## Import and streams

```c
comptime import "stdlib:/io/file.cp";

stream_t stream = file_t.fopen("notes.txt", "w+");
if (stream.stream != NULL) {
    defer stream.fclose();
    stream.fprintf("%s %d\n", "answer", 42);
    stream.rewind();

    char label[32];
    int value = 0;
    if (stream.fscanf("%31s %d", label, &value) == 2) {
        var_io_t io = var_io_t.standard();
        io.printf("%s=%d\n", label, value);
    }
}
```

`stream_t` wraps a C `FILE*`, its close-responsibility flag, and optional caller-provided buffer. `file_t.fopen(path, mode)` and `file_t.tmpfile()` are path-facing factories returning `stream_t`; successful results own the handle. `path_t.remove(path)` and `path_t.rename(old, new)` hold path-only operations. Stream instance methods cover formatted stream I/O, characters/lines, block I/O, positioning, buffering, and status.

## Ownership policy

`STREAM_OWNED` and `STREAM_BORROWED` are explicit values stored in `stream_t.is_owned`. `stream_t.from_owned(FILE*)` marks a handle owned; `from_borrowed(FILE*)` does not. `stream_t.standard_input()`, `standard_output()`, and `standard_error()` produce borrowed wrappers. `var_io_t.standard()` returns those wrappers in its `input`, `output`, and `error` fields.

`stream.fclose()` closes and clears an owned handle, then resets its ownership flag. On a borrowed handle it returns `EOF`, sets `errno` to `EINVAL`, and leaves the handle usable. `stream.freopen()` is restricted to owned handles because libc closes/replaces the original stream; it returns `0` on success, otherwise an errno value (or `EOF` if libc left errno unset), and clears the wrapper on failure. Invalid arguments return `EINVAL`. Use `defer stream.fclose()` for owned streams.

Ownership is a C-plus convention, not compiler-enforced move semantics. Do not copy an owned `stream_t` value: copies duplicate the pointer and ownership flag, risking double-close. Borrowed wrappers may be copied while the underlying handle remains alive. `buffer` is always borrowed; keep custom storage alive as required by C. `fread`/`fwrite` report complete items, not bytes.

## Standard I/O and formatting

`var_io_t` is now an instance facade over its configured streams. `io.printf`/`io.vprintf` write to `io.output`; `io.scanf`/`io.vscanf` read from `io.input`; `getchar`, `putchar`, and `puts` use those same fields. `io.perror(prefix)` writes the prefix and current `strerror(errno)` message to `io.error`. Fields can be replaced with borrowed `stream_t` wrappers to redirect I/O.

`format_t` owns stream-independent buffer conversion: `sprintf`, `snprintf`, `sscanf`, and their `v*` forms. These use caller-provided buffers or C strings; no `string_stream_t` exists yet. `stdio_t` remains the original raw C-style compatibility/reference facade, with static methods that take `FILE*` explicitly. It retains libc behavior and is useful for migration and direct comparisons.

## Limitations and tests

The unsafe/obsolete `gets` and `tmpnam` APIs are omitted. This module does not add encoding conversion, dynamic string streams, or safer format-string checking; ensure format types and buffer capacities are correct. `<stdio.h>` provides `EOF`, standard stream macros, seek constants, and related declarations.

`stdlib/tests/io.cp` exercises ownership states, refusal to close/reopen borrowed streams, standard-handle routing for formatted and character I/O, path errors, formatting/parsing, temporary-file I/O, failure behavior, and deferred close. Run `cpc test stdlib/tests/io.cp`.
