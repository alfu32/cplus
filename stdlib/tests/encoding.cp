#include <errno.h>
#include <stddef.h>

comptime import "stdlib:/encodings/uchar.cp";
comptime import "stdlib:/encodings/wchar.cp";
comptime import "stdlib:/encodings/wctype.cp";

@test "rune helpers recognize Unicode scalar values" {
    @assert(rune_is_scalar((rune_t)0));
    @assert(rune_is_scalar((rune_t)0x10ffff));
    @assert(!rune_is_scalar((rune_t)0xd800));
    @assert(!rune_is_scalar((rune_t)0x110000));
    @assert(rune_is_ascii((rune_t)'A'));
    @assert(!rune_is_ascii((rune_t)0x80));
}

@test "encoding facade round trips ASCII through libc" {
    encoding_state_t decode_state;
    encoding_state_t encode_state;
    decode_state.reset();
    encode_state.reset();
    @assert(decode_state.is_initial());

    rune_t decoded = 0;
    size_t consumed = encoding_t.decode32(&decoded, "A", 1, &decode_state);
    @assertEquals((size_t)1, consumed);
    @assertEquals((rune_t)'A', decoded);
    @assert(decode_state.is_initial());

    char encoded[16];
    size_t produced = encoding_t.encode32(encoded, decoded, &encode_state);
    @assertEquals((size_t)1, produced);
    @assertEquals((char)'A', encoded[0]);
    @assert(encode_state.is_initial());
}

@test "encoding facade converts UTF-16 code units through libc" {
    encoding_state_t decode_state;
    encoding_state_t encode_state;
    decode_state.reset();
    encode_state.reset();

    code_unit16_t decoded = 0;
    @assertEquals((size_t)1, encoding_t.decode16(&decoded, "B", 1, &decode_state));
    @assertEquals((code_unit16_t)'B', decoded);

    char encoded[16];
    @assertEquals((size_t)1, encoding_t.encode16(encoded, decoded, &encode_state));
    @assertEquals((char)'B', encoded[0]);
}

@test "encoding facade reports invalid state argument" {
    errno = 0;
    @assertEquals((size_t)-1, encoding_t.decode32(NULL, "A", 1, NULL));
    @assertEquals(EINVAL, errno);

    errno = 0;
    @assertEquals((size_t)-1, encoding_t.encode32(NULL, (rune_t)'A', NULL));
    @assertEquals(EINVAL, errno);
}

@test "wide string facade provides borrowed view and mutation bindings" {
    wide_char_t value[] = L"c-plus";
    wide_char_t output[16];
    wstr_t view = wstr_t.from(value);

    @assertEquals((size_t)6, view.length());
    @assert(view.compare(L"c-plus") == 0);
    @assert(view.compare_n(L"c-plus-extra", 6) == 0);
    @assert(view.find(L'-') == &value[1]);
    @assert(view.find_string(L"plus") == &value[2]);

    @assert(wide_string_t.copy(output, value) == output);
    @assert(wide_string_t.append(output, L"!") == output);
    @assert(wide_string_t.length(output) == 7);
    @assert(wide_string_t.compare(output, L"c-plus!") == 0);
}

@test "wide I/O formatting and wide classification facades" {
    wide_char_t rendered[32];
    int rank = 0;
    @assertEquals(7, wide_io_t.snprintf(rendered, 32, L"%ls:%d", L"rank", 17));
    @assert(wide_string_t.compare(rendered, L"rank:17") == 0);
    @assertEquals(1, wide_io_t.sscanf(rendered, L"rank:%d", &rank));
    @assertEquals(17, rank);

    @assert(wide_ctype_t.is_alpha(L'A'));
    @assert(wide_ctype_t.is_digit(L'7'));
    @assert(wide_ctype_t.is_space(L' '));
    @assertEquals((wint_t)L'a', wide_ctype_t.to_lower(L'A'));
    wctype_t alpha_category = wide_ctype_t.category("alpha");
    @assert(wide_ctype_t.is_category(L'Z', alpha_category));
    @assertEquals((wint_t)L'Z', wide_ctype_t.apply_mapping(L'z', wide_ctype_t.mapping("toupper")));
}
