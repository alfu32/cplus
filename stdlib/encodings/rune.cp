#ifndef CPLUS_STDLIB_ENCODINGS_RUNE_CP
#define CPLUS_STDLIB_ENCODINGS_RUNE_CP

#include <stdint.h>

/* A platform-independent storage type for one Unicode code point value.
 * Darwin's libc already reserves rune_t as a signed 32-bit typedef. Reusing
 * its compatible width there avoids a typedef collision with <stdlib.h>; the
 * public range and predicates remain the same for Unicode scalar values. */
#if defined(__APPLE__)
typedef int32_t rune_t;
#else
typedef uint32_t rune_t;
#endif

pub int rune_is_scalar(rune_t value) {
    return value <= 0x10ffff && !(value >= 0xd800 && value <= 0xdfff);
}

pub int rune_is_ascii(rune_t value) {
    return value <= 0x7f;
}

#endif
