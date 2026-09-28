#ifndef CPLUS_STDLIB_ENCODINGS_UCHAR_CP
#define CPLUS_STDLIB_ENCODINGS_UCHAR_CP

#include <errno.h>
#include <stddef.h>
#include <string.h>
#include <wchar.h>

/* Some Apple SDKs do not ship <uchar.h> even though their libc exposes the
 * wide-character conversion primitives. Keep the public C-plus facade stable
 * by providing the small C11 conversion surface it uses. */
#if defined(__has_include)
#  if __has_include(<uchar.h>)
#    include <uchar.h>
#  else
#    include <stdint.h>
typedef uint16_t char16_t;
typedef uint32_t char32_t;

static size_t cplus_mbrtoc32(char32_t* output, const char* input, size_t byte_count, mbstate_t* state) {
    if (input == NULL) return 0;
    wchar_t decoded = 0;
    size_t result = mbrtowc(&decoded, input, byte_count, state);
    if (output != NULL && result != (size_t)-1 && result != (size_t)-2)
        *output = (char32_t)decoded;
    return result;
}

static size_t cplus_c32rtomb(char* output, char32_t value, mbstate_t* state) {
    if (output == NULL) return 1;
    return wcrtomb(output, (wchar_t)value, state);
}

static size_t cplus_mbrtoc16(char16_t* output, const char* input, size_t byte_count, mbstate_t* state) {
    if (input == NULL) return 0;
    wchar_t decoded = 0;
    size_t result = mbrtowc(&decoded, input, byte_count, state);
    if (output != NULL && result != (size_t)-1 && result != (size_t)-2) {
        if ((uint32_t)decoded > 0xffffu) {
            errno = EILSEQ;
            return (size_t)-1;
        }
        *output = (char16_t)decoded;
    }
    return result;
}

static size_t cplus_c16rtomb(char* output, char16_t value, mbstate_t* state) {
    if (output == NULL) return 1;
    return wcrtomb(output, (wchar_t)value, state);
}
#    define mbrtoc16 cplus_mbrtoc16
#    define c16rtomb cplus_c16rtomb
#    define mbrtoc32 cplus_mbrtoc32
#    define c32rtomb cplus_c32rtomb
#  endif
#else
#  include <uchar.h>
#endif

comptime import "stdlib:/encodings/rune.cp";

typedef char16_t code_unit16_t;

typedef struct encoding_state_t {
    mbstate_t value;

    pub void reset(borrowed mut *self) {
        if (self != NULL) memset(&self->value, 0, sizeof(self->value));
    }

    pub int is_initial(borrowed *self) {
        return self != NULL && mbsinit(&self->value);
    }
} encoding_state_t;

typedef struct encoding_t {
    static pub size_t decode16(borrowed mut code_unit16_t* output, borrowed const char* input, size_t byte_count, borrowed mut encoding_state_t* state) {
        if (state == NULL) { errno = EINVAL; return (size_t)-1; }
        return mbrtoc16(output, input, byte_count, &state->value);
    }

    static pub size_t decode32(borrowed mut rune_t* output, borrowed const char* input, size_t byte_count, borrowed mut encoding_state_t* state) {
        if (state == NULL) { errno = EINVAL; return (size_t)-1; }
        char32_t decoded;
        size_t result = mbrtoc32(output == NULL ? NULL : &decoded, input, byte_count, &state->value);
        if (output != NULL && result != (size_t)-1 && result != (size_t)-2)
            *output = (rune_t)decoded;
        return result;
    }

    static pub size_t encode16(borrowed mut char* output, code_unit16_t value, borrowed mut encoding_state_t* state) {
        if (state == NULL) { errno = EINVAL; return (size_t)-1; }
        return c16rtomb(output, value, &state->value);
    }

    static pub size_t encode32(borrowed mut char* output, rune_t value, borrowed mut encoding_state_t* state) {
        if (state == NULL) { errno = EINVAL; return (size_t)-1; }
        return c32rtomb(output, (char32_t)value, &state->value);
    }
} encoding_t;

#endif
