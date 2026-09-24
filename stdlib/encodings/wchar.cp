#ifndef CPLUS_STDLIB_ENCODINGS_WCHAR_CP
#define CPLUS_STDLIB_ENCODINGS_WCHAR_CP

#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <wchar.h>

typedef wchar_t wide_char_t;

typedef struct wstr_t {
    borrowed const wide_char_t* data;

    static pub wstr_t from(borrowed const wide_char_t* value) {
        wstr_t view = { value };
        return view;
    }

    pub size_t length(borrowed *self) {
        return self == NULL || self->data == NULL ? 0 : wcslen(self->data);
    }

    pub int compare(borrowed *self, borrowed const wide_char_t* other) {
        if (self == NULL || self->data == NULL) return other == NULL ? 0 : -1;
        if (other == NULL) return 1;
        return wcscmp(self->data, other);
    }

    pub int compare_n(borrowed *self, borrowed const wide_char_t* other, size_t count) {
        if (self == NULL || self->data == NULL) return other == NULL ? 0 : -1;
        if (other == NULL) return 1;
        return wcsncmp(self->data, other, count);
    }

    pub borrowed const wide_char_t* find(borrowed *self, wint_t value) {
        if (self == NULL || self->data == NULL) return NULL;
        return wcschr(self->data, value);
    }

    pub borrowed const wide_char_t* find_last(borrowed *self, wint_t value) {
        if (self == NULL || self->data == NULL) return NULL;
        return wcsrchr(self->data, value);
    }

    pub borrowed const wide_char_t* find_string(borrowed *self, borrowed const wide_char_t* needle) {
        if (self == NULL || self->data == NULL || needle == NULL) return NULL;
        return wcsstr(self->data, needle);
    }

    pub size_t span(borrowed *self, borrowed const wide_char_t* accepted) {
        if (self == NULL || self->data == NULL || accepted == NULL) return 0;
        return wcsspn(self->data, accepted);
    }

    pub size_t span_until(borrowed *self, borrowed const wide_char_t* rejected) {
        if (self == NULL || self->data == NULL || rejected == NULL) return 0;
        return wcscspn(self->data, rejected);
    }
} wstr_t;

typedef struct wide_string_t {
    static pub int compare(borrowed const wide_char_t* left, borrowed const wide_char_t* right) {
        if (left == NULL || right == NULL) return left == right ? 0 : (left == NULL ? -1 : 1);
        return wcscmp(left, right);
    }

    static pub size_t length(borrowed const wide_char_t* value) {
        return value == NULL ? 0 : wcslen(value);
    }

    static pub borrowed wide_char_t* copy(borrowed mut wide_char_t* destination, borrowed const wide_char_t* source) {
        if (destination == NULL || source == NULL) return NULL;
        return wcscpy(destination, source);
    }

    static pub borrowed wide_char_t* copy_n(borrowed mut wide_char_t* destination, borrowed const wide_char_t* source, size_t count) {
        if (destination == NULL || source == NULL) return NULL;
        return wcsncpy(destination, source, count);
    }

    static pub borrowed wide_char_t* append(borrowed mut wide_char_t* destination, borrowed const wide_char_t* source) {
        if (destination == NULL || source == NULL) return NULL;
        return wcscat(destination, source);
    }

    static pub borrowed wide_char_t* append_n(borrowed mut wide_char_t* destination, borrowed const wide_char_t* source, size_t count) {
        if (destination == NULL || source == NULL) return NULL;
        return wcsncat(destination, source, count);
    }

    static pub int collate(borrowed const wide_char_t* left, borrowed const wide_char_t* right) {
        if (left == NULL || right == NULL) return left == right ? 0 : (left == NULL ? -1 : 1);
        return wcscoll(left, right);
    }

    static pub size_t transform(borrowed mut wide_char_t* destination, borrowed const wide_char_t* source, size_t count) {
        if (source == NULL || (destination == NULL && count != 0)) return (size_t)-1;
        return wcsxfrm(destination, source, count);
    }
} wide_string_t;

typedef struct wide_io_t {
    static pub wint_t get(borrowed FILE* stream) {
        return stream == NULL ? WEOF : fgetwc(stream);
    }

    static pub wint_t put(borrowed mut FILE* stream, wide_char_t value) {
        return stream == NULL ? WEOF : fputwc(value, stream);
    }

    static pub borrowed wide_char_t* read_line(borrowed mut wide_char_t* destination, int capacity, borrowed FILE* stream) {
        if (destination == NULL || stream == NULL || capacity <= 0) return NULL;
        return fgetws(destination, capacity, stream);
    }

    static pub int write_string(borrowed const wide_char_t* value, borrowed mut FILE* stream) {
        if (value == NULL || stream == NULL) return EOF;
        return fputws(value, stream);
    }

    static pub wint_t get_stdin() {
        return getwchar();
    }

    static pub wint_t put_stdout(wide_char_t value) {
        return putwchar(value);
    }

    static pub int printf(borrowed const wide_char_t* format, ...) {
        va_list arguments;
        va_start(arguments, format);
        int result = vwprintf(format, arguments);
        va_end(arguments);
        return result;
    }

    static pub int fprintf(borrowed mut FILE* stream, borrowed const wide_char_t* format, ...) {
        if (stream == NULL) return -1;
        va_list arguments;
        va_start(arguments, format);
        int result = vfwprintf(stream, format, arguments);
        va_end(arguments);
        return result;
    }

    static pub int scanf(borrowed const wide_char_t* format, ...) {
        va_list arguments;
        va_start(arguments, format);
        int result = vwscanf(format, arguments);
        va_end(arguments);
        return result;
    }

    static pub int fscanf(borrowed FILE* stream, borrowed const wide_char_t* format, ...) {
        if (stream == NULL) return EOF;
        va_list arguments;
        va_start(arguments, format);
        int result = vfwscanf(stream, format, arguments);
        va_end(arguments);
        return result;
    }

    static pub int snprintf(borrowed mut wide_char_t* destination, size_t capacity, borrowed const wide_char_t* format, ...) {
        if (destination == NULL && capacity != 0) return -1;
        va_list arguments;
        va_start(arguments, format);
        int result = vswprintf(destination, capacity, format, arguments);
        va_end(arguments);
        return result;
    }

    static pub int sscanf(borrowed const wide_char_t* input, borrowed const wide_char_t* format, ...) {
        if (input == NULL) return EOF;
        va_list arguments;
        va_start(arguments, format);
        int result = vswscanf(input, format, arguments);
        va_end(arguments);
        return result;
    }
} wide_io_t;

#endif
