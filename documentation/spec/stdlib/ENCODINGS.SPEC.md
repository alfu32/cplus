# C-plus Encoding and Wide-Character Facades

Status: source-level facades implemented; integration with the owning `string` remains future work.

## Purpose and header boundaries

The modules in `stdlib/encodings/` bind to the C runtime rather than duplicating its locale data or code-page tables. Keep the APIs grouped by their C headers:

| Module | C header | C-plus facade |
| --- | --- | --- |
| `uchar.cp` | `<uchar.h>` | `code_unit16_t`, `rune_t`, `encoding_state_t`, `encoding_t` |
| `wchar.cp` | `<wchar.h>` | `wide_char_t`, borrowed `wstr_t`, `wide_string_t`, `wide_io_t` |
| `wctype.cp` | `<wctype.h>` | `wide_ctype_t` |

```c
comptime import "stdlib:/encodings/uchar.cp";
comptime import "stdlib:/encodings/wchar.cp";
comptime import "stdlib:/encodings/wctype.cp";
```

Import only what a translation unit uses. `<uchar.h>` models UTF-16/UTF-32 code units; `rune_t` aliases `char32_t`, while `rune_is_scalar` additionally rejects surrogate code points and values above `U+10FFFF`.

## Restartable conversions

`encoding_state_t` owns an `mbstate_t`. Use one state per independent conversion stream; `reset()` returns it to the initial state. The bindings are thin and retain the C functions' return conventions:

```c
encoding_state_t state;
state.reset();
rune_t rune;
size_t result = encoding_t.decode32(&rune, bytes, byte_count, &state);
```

`encoding_t.decode16`/`encode16` call `mbrtoc16`/`c16rtomb`; `decode32`/`encode32` call `mbrtoc32`/`c32rtomb`. Decode can return zero, the number of consumed bytes, `(size_t)-1` for an encoding error, `(size_t)-2` for incomplete input, or `(size_t)-3` when another code unit is pending from the prior input. Encode returns the byte count or `(size_t)-1`. The facade sets `errno = EINVAL` and returns `(size_t)-1` if its state pointer is null; other argument and conversion behavior is delegated to libc.

These are not a guarantee that each target uses UTF-8. ISO C's multibyte functions use the implementation's current `LC_CTYPE` conversion rules; runtimes can differ in supported locales and extensions. A C-library encoding API may consult tables or platform locale services, but C does not promise every code page or provide general-purpose Unicode normalization/property data through these conversion functions.

## Wide strings, I/O, and classification

`wstr_t` is a borrowed view over a NUL-terminated `wchar_t` sequence. Its length, comparisons, search, and span methods delegate to the matching `wcs*` functions. `wide_string_t` provides libc copy, append, collation, and transform operations; destination capacity remains the caller's responsibility, just as with the narrow raw-buffer functions.

`wide_io_t` wraps wide character/line input and output, standard wide-character I/O, and formatted wide I/O. It delegates to `<wchar.h>` (`fgetwc`, `fputwc`, `fgetws`, `fputws`, and the `vfwprintf`/`vfwscanf`/`vswprintf`/`vswscanf` family). The caller owns and sizes destination buffers.

`wide_ctype_t` wraps the `isw*`, `tow*`, `wctype`, and `wctrans` operations. Classification and case mapping are locale-aware; they are not a complete Unicode property, normalization, or grapheme library.

`wchar_t` width and representation are implementation-defined. Treat it as the platform's wide-character interface, not as a portable alias for `rune_t`. Use `rune_t` for a UTF-32 code unit/scalar value and retain explicit encoding at byte boundaries.

## Current limits and tests

The mutable `string` in `stdlib/strings/string.cp` remains byte-oriented and NUL-terminated. It does not yet enforce UTF-8, count/iterate runes, or perform encoding-aware slicing and mutation. Those belong to a later text layer built on shared byte storage.

The bundled Windows TinyCC sysroot currently defines `char16_t` and `char32_t` but omits declarations for the C11 conversion functions in `<uchar.h>`; this facade is not yet verified for that target. Do not assume a C11 conversion symbol exists merely because the type header does.

Run the Linux-hosted facade tests with:

```sh
cpc test stdlib/tests/encoding.cp
```
