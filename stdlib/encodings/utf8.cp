#ifndef CPLUS_STDLIB_ENCODINGS_UTF8_CP
#define CPLUS_STDLIB_ENCODINGS_UTF8_CP

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

comptime import "stdlib:/encodings/rune.cp";

enum {
    UTF8_OK = 0,
    UTF8_END = 1,
    UTF8_INCOMPLETE = 2,
    UTF8_INVALID_SEQUENCE = 3,
    UTF8_INVALID_ARGUMENT = 4,
    UTF8_IO_ERROR = 5
};

typedef struct utf8_t {
    /*
     * Decode one Unicode scalar from a length-delimited byte sequence.
     * On success, output and offset are updated. Incomplete or invalid input
     * leaves both unchanged.
     */
    static pub int next(
        borrowed const char* bytes,
        size_t byte_count,
        borrowed mut size_t* offset,
        borrowed mut rune_t* output
    ) {
        if (offset == NULL || output == NULL || (bytes == NULL && byte_count != 0))
            return UTF8_INVALID_ARGUMENT;
        if (*offset > byte_count) return UTF8_INVALID_ARGUMENT;
        if (*offset == byte_count) return UTF8_END;

        const unsigned char* input = (const unsigned char*)bytes;
        size_t start = *offset;
        unsigned char first = input[start];
        uint32_t value;
        size_t width;

        if (first <= 0x7f) {
            value = first;
            width = 1;
        } else if (first >= 0xc2 && first <= 0xdf) {
            value = first & 0x1f;
            width = 2;
        } else if (first >= 0xe0 && first <= 0xef) {
            value = first & 0x0f;
            width = 3;
        } else if (first >= 0xf0 && first <= 0xf4) {
            value = first & 0x07;
            width = 4;
        } else {
            return UTF8_INVALID_SEQUENCE;
        }

        for (size_t index = 1; index < width; index++) {
            if (index >= byte_count - start) return UTF8_INCOMPLETE;

            unsigned char continuation = input[start + index];
            if ((continuation & 0xc0) != 0x80) return UTF8_INVALID_SEQUENCE;
            if (index == 1 && first == 0xe0 && continuation < 0xa0)
                return UTF8_INVALID_SEQUENCE;
            if (index == 1 && first == 0xed && continuation >= 0xa0)
                return UTF8_INVALID_SEQUENCE;
            if (index == 1 && first == 0xf0 && continuation < 0x90)
                return UTF8_INVALID_SEQUENCE;
            if (index == 1 && first == 0xf4 && continuation > 0x8f)
                return UTF8_INVALID_SEQUENCE;

            value = (value << 6) | (continuation & 0x3f);
        }

        *output = (rune_t)value;
        *offset = start + width;
        return UTF8_OK;
    }

    /* Count Unicode scalar values, preserving output_count on malformed input. */
    static pub int count(
        borrowed const char* bytes,
        size_t byte_count,
        borrowed mut size_t* output_count
    ) {
        if (output_count == NULL || (bytes == NULL && byte_count != 0))
            return UTF8_INVALID_ARGUMENT;

        size_t offset = 0;
        size_t result = 0;
        rune_t value;
        while (1) {
            int status = utf8_t.next(bytes, byte_count, &offset, &value);
            if (status == UTF8_END) {
                *output_count = result;
                return UTF8_OK;
            }
            if (status != UTF8_OK) return status;
            result++;
        }
    }

    /* Write one scalar as UTF-8 bytes; no locale conversion is performed. */
    static pub int print(borrowed FILE* output, rune_t value) {
        if (output == NULL) return UTF8_INVALID_ARGUMENT;
        if (!rune_is_scalar(value)) return UTF8_INVALID_SEQUENCE;

        uint32_t codepoint = (uint32_t)value;
        unsigned char bytes[4];
        size_t width;
        if (codepoint <= 0x7f) {
            bytes[0] = (unsigned char)codepoint;
            width = 1;
        } else if (codepoint <= 0x7ff) {
            bytes[0] = (unsigned char)(0xc0 | (codepoint >> 6));
            bytes[1] = (unsigned char)(0x80 | (codepoint & 0x3f));
            width = 2;
        } else if (codepoint <= 0xffff) {
            bytes[0] = (unsigned char)(0xe0 | (codepoint >> 12));
            bytes[1] = (unsigned char)(0x80 | ((codepoint >> 6) & 0x3f));
            bytes[2] = (unsigned char)(0x80 | (codepoint & 0x3f));
            width = 3;
        } else {
            bytes[0] = (unsigned char)(0xf0 | (codepoint >> 18));
            bytes[1] = (unsigned char)(0x80 | ((codepoint >> 12) & 0x3f));
            bytes[2] = (unsigned char)(0x80 | ((codepoint >> 6) & 0x3f));
            bytes[3] = (unsigned char)(0x80 | (codepoint & 0x3f));
            width = 4;
        }

        return fwrite(bytes, 1, width, output) == width ? UTF8_OK : UTF8_IO_ERROR;
    }
} utf8_t;

#endif
