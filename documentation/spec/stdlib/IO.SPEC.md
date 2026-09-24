# C-plus `file_t` I/O Facade Specification

Status: thin facade over the C standard I/O library.

## Purpose and import

`stdlib/io/file.cp` exposes `<stdio.h>` through namespaced static methods on `file_t`. It preserves C's signatures, return values, stream state, and error conventions rather than introducing a new buffering or ownership model.

```c
comptime import "stdlib:/io/file.cp";

FILE* stream = file_t.fopen("notes.txt", "w+");
if (stream != NULL) {
    file_t.fprintf(stream, "%s %d\n", "answer", 42);
    file_t.rewind(stream);

    char label[32];
    int value = 0;
    if (file_t.fscanf(stream, "%31s %d", label, &value) == 2) {
        file_t.printf("%s=%d\n", label, value);
    }
    file_t.fclose(stream);
}
```

The `file_t` struct also has a borrowed `FILE* stream` field for code that wants to keep a raw stream in a C-plus value. The facade methods are static and accept/return `FILE*` exactly as the corresponding C functions do; they do not automatically read or write that field.

## API

The facade includes wrappers for:

- Opening and closing: `fopen`, `freopen`, `tmpfile`, `fclose`, `fflush`.
- Buffering: `setbuf`, `setvbuf`.
- Formatted input/output: `printf`, `fprintf`, `sprintf`, `snprintf`, `scanf`, `fscanf`, `sscanf`, plus `vprintf`, `vfprintf`, `vsprintf`, `vsnprintf`, `vscanf`, `vfscanf`, and `vsscanf`.
- Character and line I/O: `fgetc`, `getc`, `getchar`, `fgets`, `fputc`, `putc`, `putchar`, `fputs`, `puts`, `ungetc`.
- Block I/O: `fread`, `fwrite`.
- Position and status: `fgetpos`, `fseek`, `fsetpos`, `ftell`, `rewind`, `clearerr`, `feof`, `ferror`, `perror`.
- Filesystem operations: `remove`, `rename`.

Variadic functions are implemented with `va_list` and forward to their standard `v*` counterparts. This preserves the original format and conversion behavior without trying to reinterpret variadic arguments.

## Lifetime, safety, and limitations

Streams returned by `fopen`, `freopen`, or `tmpfile` must be closed by the caller with `file_t.fclose`. The `borrowed` annotations document that the facade does not allocate the passed streams or buffers; they do not enforce lifetimes. Buffers passed to `setbuf`/`setvbuf` must remain alive for the lifetime required by C. Use only valid pointers, respect buffer capacities, and ensure format strings match their arguments. `fread`/`fwrite` return complete items, not bytes, as in C. `EOF`, `stdin`, `stdout`, `stderr`, `SEEK_SET`, and related macros remain available from `<stdio.h>`.

The unsafe/obsolete `gets` and `tmpnam` functions are intentionally omitted. This module adds no path abstraction, automatic cleanup, encoding conversion, or safe formatting layer.

## Tests

`stdlib/tests/io.cp` checks formatted string conversion, formatted temporary-file I/O, block reads/writes, EOF handling, and defer-based stream cleanup. Run it with:

```sh
cpc test stdlib/tests/io.cp
```
