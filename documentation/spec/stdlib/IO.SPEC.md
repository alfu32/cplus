# C-plus I/O, Streams, and Formatting

Status: receiver-oriented file streams, a byte-oriented memory stream, configurable standard I/O, and the retained raw compatibility facade.

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

Binary memory I/O uses the same byte-level operations without treating zero bytes as terminators:

```c
comptime import "stdlib:/io/memory_stream.cp";

memory_stream_t bytes;
bytes.init();
defer bytes.close();

const unsigned char packet[] = { 0x43, 0x00, 0x2b };
bytes.write_bytes(packet, sizeof(packet));
bytes.seek(0, SEEK_SET);
unsigned char decoded[sizeof(packet)];
bytes.read_bytes(decoded, sizeof(decoded));
```

`stream_t` wraps a C `FILE*`, its close-responsibility flag, and optional caller-provided buffer. `file_t.fopen(path, mode)` and `file_t.tmpfile()` are path-facing factories returning `stream_t`; successful results own the handle. `path_t.remove(path)` and `path_t.rename(old, new)` hold path-only operations. Stream instance methods cover formatted stream I/O, characters/lines, block I/O, positioning, buffering, and status.

`memory_stream_t` is the in-memory byte backend. It owns a growable `unsigned char` buffer, cursor, length, and capacity; it can hold arbitrary binary data, including embedded zero bytes. Call `init()` or `init_copy(data, length)` once on fresh storage; the latter copies the source range. `close()` releases its storage. `read_bytes`, `write_bytes`, `seek`, `tell`, `flush`, and `size` form its byte-oriented API. `clear()` resets length and cursor while retaining capacity; `truncate(length)` changes the logical length and zero-fills newly exposed bytes. `buffer()` returns a borrowed buffer whose contents may change on mutation and whose address may change on growth; it becomes invalid on close.

Byte reads may return fewer bytes than requested at end-of-stream. Check `stream.ferror()` to distinguish a file I/O error from EOF; a valid memory-stream read returns zero at EOF and sets `errno` for invalid or closed wrappers. File writes may also be partial, while a memory write either commits the full request or returns zero on error. `memory_stream_t.seek` accepts the standard `SEEK_SET`/`SEEK_CUR`/`SEEK_END` origins and may position past the current end; later writes fill the gap with zero bytes.

Both concrete backends expose the same byte operations: file `stream_t` provides `read_bytes`, `write_bytes`, `seek`, `tell`, `flush`, and `close`; `memory_stream_t` provides matching signatures. This is a documented method-signature contract, not compiler-enforced interface conformance; comptime-generated algorithms can specialize against a concrete backend. There is deliberately no runtime-polymorphic `byte_stream_t`/vtable yet: code selects a concrete backend statically, avoiding an extra indirect-call layer and keeping formatted `FILE*` operations distinct. Add runtime dispatch only when a consumer needs to hold or switch between backend kinds through one handle.

## Ownership policy

`STREAM_OWNED` and `STREAM_BORROWED` are explicit values stored in `stream_t.is_owned`. `stream_t.from_owned(FILE*)` marks a handle owned; `from_borrowed(FILE*)` does not. `stream_t.standard_input()`, `standard_output()`, and `standard_error()` produce borrowed wrappers. `var_io_t.standard()` returns those wrappers in its `input`, `output`, and `error` fields.

`stream.fclose()` closes and clears an owned handle, then resets its ownership flag. On a borrowed handle it returns `EOF`, sets `errno` to `EINVAL`, and leaves the handle usable. `stream.freopen()` is restricted to owned handles because libc closes/replaces the original stream; it returns `0` on success, otherwise an errno value (or `EOF` if libc left errno unset), and clears the wrapper on failure. Invalid arguments return `EINVAL`. Use `defer stream.fclose()` for owned streams.

Ownership is a C-plus convention, not compiler-enforced move semantics. Copying an owner does not close anything by itself, but each copy carries the same resource and ownership flag; closing both copies can double-close or double-free. Do not copy an owning `stream_t` or initialized `memory_stream_t`; borrowed file wrappers may be copied while their underlying handle remains alive. `stream_t.buffer` is borrowed; keep custom storage alive as required by C. `fread`/`fwrite` report complete items, not bytes; use the byte operations when byte counts are wanted.

Operations check wrapper state before touching the resource and return their documented failure sentinel for a null/closed wrapper. This catches closes performed through that wrapper. C cannot portably detect a `FILE*` that another owner closed behind the wrapper; do not externally close an owned or borrowed handle while a wrapper may use it. A memory stream similarly cannot detect a buffer freed outside its ownership boundary.

## Standard I/O and formatting

`var_io_t` is now an instance facade over its configured streams. `io.printf`/`io.vprintf` write to `io.output`; `io.scanf`/`io.vscanf` read from `io.input`; `getchar`, `putchar`, and `puts` use those same fields. `io.perror(prefix)` writes the prefix and current `strerror(errno)` message to `io.error`. Fields can be replaced with borrowed `stream_t` wrappers to redirect I/O.

`format_t` owns stream-independent buffer conversion: `sprintf`, `snprintf`, `sscanf`, and their `v*` forms. These use caller-provided buffers or C strings. `string` remains NUL-terminated text storage; it is not a stream and is not suitable for arbitrary binary content. `stdio_t` remains the original raw C-style compatibility/reference facade, with static methods that take `FILE*` explicitly. It retains libc behavior and is useful for migration and direct comparisons.

## Limitations and tests

The unsafe/obsolete `gets` and `tmpnam` APIs are omitted. The byte contract does not define text encoding, typed serialization, or safer format-string checking; those should be layered over byte reads/writes or kept in `format_t`. Ensure format types and buffer capacities are correct. `<stdio.h>` provides `EOF`, standard stream macros, seek constants, and related declarations. A memory stream is growable, but allocation is bounded by `LONG_MAX` so its cursor can be represented by the shared `tell()` result type.

`stdlib/tests/io.cp` exercises ownership states, refusal to close/reopen borrowed streams, standard-handle routing for formatted and character I/O, path errors, formatting/parsing, temporary-file I/O, byte operations, failure behavior, and deferred close. `stdlib/tests/memory_stream.cp` covers binary data, cursor/EOF behavior, growth, truncation, and closed-state handling. Run `cpc test stdlib/tests/io.cp stdlib/tests/memory_stream.cp`.
