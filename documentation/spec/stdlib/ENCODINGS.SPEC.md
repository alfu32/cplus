# C-plus Encoding and Wide-Character Facades

Status: UTF-8 scalar decoding/counting/encoding and C runtime facades are implemented; integration with the owning `string` remains future work.

## Purpose and header boundaries

The modules in `stdlib/encodings/` bind to the C runtime rather than duplicating its locale data or code-page tables. Keep the APIs grouped by their C headers:

| Module | C header | C-plus facade |
| --- | --- | --- |
| `rune.cp` | `<stdint.h>` | fixed-width `rune_t`, `rune_is_scalar`, `rune_is_ascii` |
| `utf8.cp` | byte-level UTF-8 | `utf8_t.next`, `utf8_t.count`, `utf8_t.print`, status constants |
| `uchar.cp` | `<uchar.h>` / `<wchar.h>` | `code_unit16_t`, `encoding_state_t`, `encoding_t` |
| `wchar.cp` | `<wchar.h>` | `wide_char_t`, borrowed `wstr_t`, `wide_string_t`, `wide_io_t` |
| `wctype.cp` | `<wctype.h>` | `wide_ctype_t` |

```c
comptime import "stdlib:/encodings/utf8.cp";
comptime import "stdlib:/encodings/uchar.cp";
comptime import "stdlib:/encodings/wchar.cp";
comptime import "stdlib:/encodings/wctype.cp";
```

Import only what a translation unit uses. `rune_t` is `uint32_t`, independent of platform `wchar_t`, C locale state, and `<uchar.h>`; `rune_is_scalar` rejects surrogate code points and values above `U+10FFFF`. `utf8_t` is the stable, locale-independent byte API; it does not call the C multibyte conversion functions. The `encoding_t` facade converts between this stable type and the implementation's `char32_t` at its boundary.

## Stable UTF-8 API

UTF-8 is the portable byte representation exposed for rune-oriented text. The API is deliberately small and length-based, so it can process buffers containing NUL bytes and does not depend on `LC_CTYPE`, `wchar_t`, Windows code pages, or `mbrtoc32` availability.

```c
comptime import "stdlib:/encodings/utf8.cp";

size_t byte_offset = 0;
rune_t rune;
int status = utf8_t.next(bytes, byte_count, &byte_offset, &rune);
size_t rune_count;
if (utf8_t.count(bytes, byte_count, &rune_count) == UTF8_OK) {
    utf8_t.print(stdout, rune);
}
```

`next` returns `UTF8_OK` after decoding one Unicode scalar and advancing `byte_offset`; at the end it returns `UTF8_END`. It returns `UTF8_INCOMPLETE` for a valid prefix cut short at the buffer boundary and `UTF8_INVALID_SEQUENCE` for invalid leads, continuations, overlong forms, surrogates, or values above `U+10FFFF`. On failure, its output and offset are unchanged. Null/invalid arguments return `UTF8_INVALID_ARGUMENT`.

`count` counts Unicode scalar values, not grapheme clusters or terminal columns. It accepts embedded NUL when a length is supplied and only updates its output count if the complete buffer is valid. `print(FILE*, rune_t)` writes the canonical one-to-four-byte UTF-8 sequence using `fwrite`; it rejects non-scalars and reports write errors. It does not configure a terminal's display encoding.

Example: bytes `41 C3 A9 F0 9F 8C 8D` decode to `U+0041`, `U+00E9`, and `U+1F30D`; `count` returns `3`. The mutable `string` remains byte-oriented; these helpers do not yet add UTF-8-aware slicing or mutation to it.

## Restartable conversions

`encoding_state_t` owns an `mbstate_t`. Use one state per independent conversion stream; `reset()` returns it to the initial state. The bindings are thin and retain the C functions' return conventions:

```c
encoding_state_t state;
state.reset();
rune_t rune;
size_t result = encoding_t.decode32(&rune, bytes, byte_count, &state);
```

`encoding_t.decode16`/`encode16` call `mbrtoc16`/`c16rtomb`; `decode32`/`encode32` call `mbrtoc32`/`c32rtomb`. Decode can return zero, the number of consumed bytes, `(size_t)-1` for an encoding error, `(size_t)-2` for incomplete input, or `(size_t)-3` when another code unit is pending from the prior input. Encode returns the byte count or `(size_t)-1`. The facade sets `errno = EINVAL` and returns `(size_t)-1` if its state pointer is null; other argument and conversion behavior is delegated to libc.

The `encoding_t` functions are not a guarantee that each target uses UTF-8. ISO C's multibyte functions use the implementation's current `LC_CTYPE` conversion rules; runtimes can differ in supported locales and extensions. Use `utf8_t` where a stable UTF-8 byte protocol is required. Neither layer provides normalization, grapheme segmentation, or complete Unicode property data.

## Wide strings, I/O, and classification

`wstr_t` is a borrowed view over a NUL-terminated `wchar_t` sequence. Its length, comparisons, search, and span methods delegate to the matching `wcs*` functions. `wide_string_t` provides libc copy, append, collation, and transform operations; destination capacity remains the caller's responsibility, just as with the narrow raw-buffer functions.

`wide_io_t` wraps wide character/line input and output, standard wide-character I/O, and formatted wide I/O. It delegates to `<wchar.h>` (`fgetwc`, `fputwc`, `fgetws`, `fputws`, and the `vfwprintf`/`vfwscanf`/`vswprintf`/`vswscanf` family). The caller owns and sizes destination buffers.

`wide_ctype_t` wraps the `isw*`, `tow*`, `wctype`, and `wctrans` operations. Classification and case mapping are locale-aware; they are not a complete Unicode property, normalization, or grapheme library.

`wchar_t` width and representation are implementation-defined. Treat it as the platform's wide-character interface, not as a portable alias for `rune_t`. Use `rune_t` for a UTF-32 code unit/scalar value and retain explicit encoding at byte boundaries.

## Current limits and tests

The mutable `string` in `stdlib/strings/string.cp` remains byte-oriented and NUL-terminated. It does not yet enforce UTF-8, expose rune iteration/counting as string methods, or perform encoding-aware slicing and mutation. Those belong to a later text layer built on shared byte storage.

The Windows runtime includes the UCRT conversion symbols and the MinGW-w64 sysroot declares them, but TinyCC's own `win32/include/uchar.h` currently shadows that sysroot header and omits the declarations. A TinyCC header patch is in progress. The UTF-8 API above is independent of that issue; defer Windows validation of `encoding_t` until the patched TinyCC JAR is available.

Run the facade tests with:

```sh
cpc test stdlib/tests/encoding.cp
cpc test stdlib/tests/utf8.cp
```
