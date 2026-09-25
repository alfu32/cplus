| Done | Domain                        | Main headers      | What it provides                                          |
|------| ----------------------------- | ----------------- | --------------------------------------------------------- |
|  [x] | Memory allocation             | `<stdlib.h>`      | `malloc`, `calloc`, `realloc`, `free`, aligned allocation |
|  [x] | Strings                       | `<string.h>`      | `strlen`, `memcpy`, `strcmp`, `strcpy`, `strstr`, etc.    |
|  [x] | Character handling            | `<ctype.h>`       | `isalpha`, `isdigit`, `tolower`, etc.                     |
|  [x] | I/O / files                   | `<stdio.h>`       | `FILE`, `fopen`, `printf`, `scanf`, `fread`, `fwrite`     |
|  [ ] | Numeric conversions           | `<stdlib.h>`      | `strtol`, `strtod`, `atoi`, etc.                          |
|  [ ] | Mathematics                   | `<math.h>`        | `sin`, `cos`, `sqrt`, `pow`, `log`, etc.                  |
|  [ ] | Complex numbers               | `<complex.h>`     | complex arithmetic and functions                          |
|  [ ] | Floating-point environment    | `<fenv.h>`        | rounding modes, FP exceptions                             |
|  [ ] | Floating-point properties     | `<float.h>`       | limits/characteristics of FP types                        |
|  [ ] | Integer limits                | `<limits.h>`      | `INT_MAX`, `CHAR_BIT`, etc.                               |
|  [ ] | Fixed-width integers          | `<stdint.h>`      | `int32_t`, `uint64_t`, `intptr_t`, etc.                   |
|  [ ] | Integer formatting            | `<inttypes.h>`    | `PRIu64`, `strtoimax`, etc.                               |
|  [ ] | General algorithms            | `<stdlib.h>`      | `qsort`, `bsearch`                                        |
|  [ ] | Random numbers                | `<stdlib.h>`      | `rand`, `srand`                                           |
|  [ ] | Process/environment           | `<stdlib.h>`      | `exit`, `abort`, `atexit`, `getenv`, `system`             |
|  [ ] | Assertions                    | `<assert.h>`      | `assert`                                                  |
|  [ ] | Errors                        | `<errno.h>`       | `errno`, standard error codes                             |
|  [ ] | Signals                       | `<signal.h>`      | `signal`, `raise`, signal constants                       |
|  [ ] | Time/date                     | `<time.h>`        | `time`, `clock`, `struct tm`, `strftime`                  |
|  [ ] | Localization                  | `<locale.h>`      | locales, decimal formatting, collation                    |
|  [ ] | Variable arguments            | `<stdarg.h>`      | `va_list`, `va_start`, `va_arg`                           |
|  [ ] | Type/offset utilities         | `<stddef.h>`      | `size_t`, `ptrdiff_t`, `offsetof`, `NULL`                 |
|  [ ] | Boolean type                  | `<stdbool.h>`     | `bool`, `true`, `false` in pre-C23 C                      |
|  [ ] | Generic type selection        | `<tgmath.h>`      | type-generic math macros                                  |
|  [ ] | Atomics                       | `<stdatomic.h>`   | atomic types and operations                               |
|  [x] | Threads                       | `<threads.h>`     | threads, mutexes, condition variables                     |
|  [x] | Unicode-ish character types   | `<uchar.h>`       | `char16_t`, `char32_t`, conversions                       |
|  [x] | Wide characters               | `<wchar.h>`       | wide strings, wide I/O                                    |
|  [x] | Wide character classification | `<wctype.h>`      | wide equivalents of `<ctype.h>`                           |
|  [ ] | Alignment                     | `<stdalign.h>`    | alignment macros, mainly older standards                  |
|  [ ] | No-return annotation          | `<stdnoreturn.h>` | `_Noreturn` convenience, older standards                  |
|  [ ] | Bit operations                | `<stdbit.h>`      | C23 bit counting/rotation/etc.                            |
|  [ ] | Checked integer arithmetic    | `<stdckdint.h>`   | C23 checked add/subtract/multiply                         |
