#ifndef CPLUS_STDLIB_ENCODINGS_RUNE_CP
#define CPLUS_STDLIB_ENCODINGS_RUNE_CP

#include <stdint.h>

/* A platform-independent storage type for one Unicode code point value. */
typedef uint32_t rune_t;

pub int rune_is_scalar(rune_t value) {
    return value <= 0x10ffff && !(value >= 0xd800 && value <= 0xdfff);
}

pub int rune_is_ascii(rune_t value) {
    return value <= 0x7f;
}

#endif
