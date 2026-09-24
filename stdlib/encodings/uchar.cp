#ifndef CPLUS_STDLIB_ENCODINGS_UCHAR_CP
#define CPLUS_STDLIB_ENCODINGS_UCHAR_CP

#include <errno.h>
#include <stddef.h>
#include <string.h>
#include <uchar.h>
#include <wchar.h>

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
