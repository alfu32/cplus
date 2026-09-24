#include <stdio.h>
#include <string.h>

comptime import "stdlib:/encodings/utf8.cp";

@test "UTF-8 next decodes ASCII and multibyte scalar values" {
    const unsigned char bytes[] = {
        'A', 0xc3, 0xa9, 0xe2, 0x82, 0xac, 0xf0, 0x9f, 0x8c, 0x8d
    };
    const char* text = (const char*)bytes;
    size_t offset = 0;
    rune_t value = 0;

    @assertEquals(UTF8_OK, utf8_t.next(text, sizeof(bytes), &offset, &value));
    @assertEquals((rune_t)'A', value);
    @assertEquals((size_t)1, offset);
    @assertEquals(UTF8_OK, utf8_t.next(text, sizeof(bytes), &offset, &value));
    @assertEquals((rune_t)0x00e9, value);
    @assertEquals(UTF8_OK, utf8_t.next(text, sizeof(bytes), &offset, &value));
    @assertEquals((rune_t)0x20ac, value);
    @assertEquals(UTF8_OK, utf8_t.next(text, sizeof(bytes), &offset, &value));
    @assertEquals((rune_t)0x1f30d, value);
    @assertEquals(UTF8_END, utf8_t.next(text, sizeof(bytes), &offset, &value));
    @assertEquals((size_t)sizeof(bytes), offset);
}

@test "UTF-8 count handles embedded NUL and rejects malformed sequences" {
    const unsigned char bytes[] = { 'A', 0, 0xc3, 0xa9, 0xf0, 0x9f, 0x8c, 0x8d };
    size_t count = 99;
    @assertEquals(UTF8_OK, utf8_t.count((const char*)bytes, sizeof(bytes), &count));
    @assertEquals((size_t)4, count);

    const unsigned char overlong[] = { 0xe0, 0x80, 0x80 };
    count = 99;
    @assertEquals(UTF8_INVALID_SEQUENCE, utf8_t.count((const char*)overlong, sizeof(overlong), &count));
    @assertEquals((size_t)99, count);

    const unsigned char partial[] = { 0xf0, 0x9f };
    @assertEquals(UTF8_INCOMPLETE, utf8_t.count((const char*)partial, sizeof(partial), &count));
    @assertEquals((size_t)99, count);

    const unsigned char above_unicode_max[] = { 0xf4, 0x90, 0x80, 0x80 };
    @assertEquals(UTF8_INVALID_SEQUENCE, utf8_t.count((const char*)above_unicode_max, sizeof(above_unicode_max), &count));
    @assertEquals((size_t)99, count);
}

@test "UTF-8 next preserves cursor and result on invalid or incomplete input" {
    const unsigned char surrogate[] = { 0xed, 0xa0, 0x80 };
    size_t offset = 0;
    rune_t value = (rune_t)'Q';
    @assertEquals(UTF8_INVALID_SEQUENCE, utf8_t.next((const char*)surrogate, sizeof(surrogate), &offset, &value));
    @assertEquals((size_t)0, offset);
    @assertEquals((rune_t)'Q', value);

    const unsigned char partial[] = { 0xf0, 0x9f, 0x8c };
    @assertEquals(UTF8_INCOMPLETE, utf8_t.next((const char*)partial, sizeof(partial), &offset, &value));
    @assertEquals((size_t)0, offset);
    @assertEquals((rune_t)'Q', value);
    @assertEquals(UTF8_INVALID_ARGUMENT, utf8_t.next(NULL, 1, &offset, &value));
    @assertEquals(UTF8_INVALID_ARGUMENT, utf8_t.next("A", 1, NULL, &value));
    offset = 2;
    @assertEquals(UTF8_INVALID_ARGUMENT, utf8_t.next("A", 1, &offset, &value));
    size_t empty_count = 99;
    @assertEquals(UTF8_OK, utf8_t.count(NULL, 0, &empty_count));
    @assertEquals((size_t)0, empty_count);
}

@test "UTF-8 print emits canonical bytes and rejects non-scalars" {
    FILE* output = tmpfile();
    @assert(output != NULL);
    @assertEquals(UTF8_OK, utf8_t.print(output, (rune_t)'A'));
    @assertEquals(UTF8_OK, utf8_t.print(output, (rune_t)0x20ac));
    @assertEquals(UTF8_OK, utf8_t.print(output, (rune_t)0x1f30d));
    @assertEquals(UTF8_INVALID_SEQUENCE, utf8_t.print(output, (rune_t)0xd800));
    @assertEquals(0, fseek(output, 0, SEEK_SET));

    const unsigned char expected[] = {
        'A', 0xe2, 0x82, 0xac, 0xf0, 0x9f, 0x8c, 0x8d
    };
    unsigned char actual[sizeof(expected)];
    @assertEquals(sizeof(expected), fread(actual, 1, sizeof(actual), output));
    @assert(memcmp(actual, expected, sizeof(expected)) == 0);
    fclose(output);
}
