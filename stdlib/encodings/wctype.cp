#ifndef CPLUS_STDLIB_ENCODINGS_WCTYPE_CP
#define CPLUS_STDLIB_ENCODINGS_WCTYPE_CP

#include <wctype.h>

typedef struct wide_ctype_t {
    static pub int is_alnum(wint_t value) { return iswalnum(value); }
    static pub int is_alpha(wint_t value) { return iswalpha(value); }
    static pub int is_blank(wint_t value) { return iswblank(value); }
    static pub int is_control(wint_t value) { return iswcntrl(value); }
    static pub int is_digit(wint_t value) { return iswdigit(value); }
    static pub int is_graph(wint_t value) { return iswgraph(value); }
    static pub int is_lower(wint_t value) { return iswlower(value); }
    static pub int is_print(wint_t value) { return iswprint(value); }
    static pub int is_punct(wint_t value) { return iswpunct(value); }
    static pub int is_space(wint_t value) { return iswspace(value); }
    static pub int is_upper(wint_t value) { return iswupper(value); }
    static pub int is_xdigit(wint_t value) { return iswxdigit(value); }

    static pub wint_t to_lower(wint_t value) { return towlower(value); }
    static pub wint_t to_upper(wint_t value) { return towupper(value); }

    static pub wctype_t category(borrowed const char* name) {
        return name == NULL ? (wctype_t)0 : wctype(name);
    }

    static pub int is_category(wint_t value, wctype_t category) {
        return category == (wctype_t)0 ? 0 : iswctype(value, category);
    }

    static pub wctrans_t mapping(borrowed const char* name) {
        return name == NULL ? (wctrans_t)0 : wctrans(name);
    }

    static pub wint_t apply_mapping(wint_t value, wctrans_t mapping) {
        return mapping == (wctrans_t)0 ? value : towctrans(value, mapping);
    }
} wide_ctype_t;

#endif
