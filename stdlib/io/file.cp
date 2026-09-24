#ifndef CPLUS_STDLIB_IO_FILE_CP
#define CPLUS_STDLIB_IO_FILE_CP

#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>

/* Thin, namespaced wrappers for the C stdio API; FILE* keeps C lifetime rules. */
typedef struct file_t {
    borrowed FILE* stream;

    static pub owned FILE* fopen(borrowed const char* path, borrowed const char* mode) {
        return fopen(path, mode);
    }

    static pub owned FILE* freopen(borrowed const char* path, borrowed const char* mode, borrowed FILE* stream) {
        return freopen(path, mode, stream);
    }

    static pub owned FILE* tmpfile(void) {
        return tmpfile();
    }

    static pub int fclose(borrowed FILE* stream) {
        return fclose(stream);
    }

    static pub int fflush(borrowed FILE* stream) {
        return fflush(stream);
    }

    static pub void setbuf(borrowed FILE* stream, borrowed mut char* buffer) {
        setbuf(stream, buffer);
    }

    static pub int setvbuf(borrowed FILE* stream, borrowed mut char* buffer, int mode, size_t size) {
        return setvbuf(stream, buffer, mode, size);
    }

    static pub int printf(borrowed const char* format, ...) {
        va_list arguments;
        va_start(arguments, format);
        int result = vprintf(format, arguments);
        va_end(arguments);
        return result;
    }

    static pub int fprintf(borrowed FILE* stream, borrowed const char* format, ...) {
        va_list arguments;
        va_start(arguments, format);
        int result = vfprintf(stream, format, arguments);
        va_end(arguments);
        return result;
    }

    static pub int sprintf(borrowed mut char* destination, borrowed const char* format, ...) {
        va_list arguments;
        va_start(arguments, format);
        int result = vsprintf(destination, format, arguments);
        va_end(arguments);
        return result;
    }

    static pub int snprintf(borrowed mut char* destination, size_t size, borrowed const char* format, ...) {
        va_list arguments;
        va_start(arguments, format);
        int result = vsnprintf(destination, size, format, arguments);
        va_end(arguments);
        return result;
    }

    static pub int scanf(borrowed const char* format, ...) {
        va_list arguments;
        va_start(arguments, format);
        int result = vscanf(format, arguments);
        va_end(arguments);
        return result;
    }

    static pub int fscanf(borrowed FILE* stream, borrowed const char* format, ...) {
        va_list arguments;
        va_start(arguments, format);
        int result = vfscanf(stream, format, arguments);
        va_end(arguments);
        return result;
    }

    static pub int sscanf(borrowed const char* input, borrowed const char* format, ...) {
        va_list arguments;
        va_start(arguments, format);
        int result = vsscanf(input, format, arguments);
        va_end(arguments);
        return result;
    }

    static pub int vprintf(borrowed const char* format, va_list arguments) {
        return vprintf(format, arguments);
    }

    static pub int vfprintf(borrowed FILE* stream, borrowed const char* format, va_list arguments) {
        return vfprintf(stream, format, arguments);
    }

    static pub int vsprintf(borrowed mut char* destination, borrowed const char* format, va_list arguments) {
        return vsprintf(destination, format, arguments);
    }

    static pub int vsnprintf(borrowed mut char* destination, size_t size, borrowed const char* format, va_list arguments) {
        return vsnprintf(destination, size, format, arguments);
    }

    static pub int vscanf(borrowed const char* format, va_list arguments) {
        return vscanf(format, arguments);
    }

    static pub int vfscanf(borrowed FILE* stream, borrowed const char* format, va_list arguments) {
        return vfscanf(stream, format, arguments);
    }

    static pub int vsscanf(borrowed const char* input, borrowed const char* format, va_list arguments) {
        return vsscanf(input, format, arguments);
    }

    static pub int fgetc(borrowed FILE* stream) {
        return fgetc(stream);
    }

    static pub int getc(borrowed FILE* stream) {
        return getc(stream);
    }

    static pub int getchar(void) {
        return getchar();
    }

    static pub borrowed char* fgets(borrowed mut char* buffer, int size, borrowed FILE* stream) {
        return fgets(buffer, size, stream);
    }

    static pub int fputc(int character, borrowed FILE* stream) {
        return fputc(character, stream);
    }

    static pub int putc(int character, borrowed FILE* stream) {
        return putc(character, stream);
    }

    static pub int putchar(int character) {
        return putchar(character);
    }

    static pub int fputs(borrowed const char* text, borrowed FILE* stream) {
        return fputs(text, stream);
    }

    static pub int puts(borrowed const char* text) {
        return puts(text);
    }

    static pub int ungetc(int character, borrowed FILE* stream) {
        return ungetc(character, stream);
    }

    static pub size_t fread(borrowed mut void* destination, size_t size, size_t count, borrowed FILE* stream) {
        return fread(destination, size, count, stream);
    }

    static pub size_t fwrite(borrowed const void* source, size_t size, size_t count, borrowed FILE* stream) {
        return fwrite(source, size, count, stream);
    }

    static pub int fgetpos(borrowed FILE* stream, borrowed mut fpos_t* position) {
        return fgetpos(stream, position);
    }

    static pub int fseek(borrowed FILE* stream, long offset, int origin) {
        return fseek(stream, offset, origin);
    }

    static pub int fsetpos(borrowed FILE* stream, borrowed const fpos_t* position) {
        return fsetpos(stream, position);
    }

    static pub long ftell(borrowed FILE* stream) {
        return ftell(stream);
    }

    static pub void rewind(borrowed FILE* stream) {
        rewind(stream);
    }

    static pub void clearerr(borrowed FILE* stream) {
        clearerr(stream);
    }

    static pub int feof(borrowed FILE* stream) {
        return feof(stream);
    }

    static pub int ferror(borrowed FILE* stream) {
        return ferror(stream);
    }

    static pub void perror(borrowed const char* prefix) {
        perror(prefix);
    }

    static pub int remove(borrowed const char* path) {
        return remove(path);
    }

    static pub int rename(borrowed const char* old_path, borrowed const char* new_path) {
        return rename(old_path, new_path);
    }
} file_t;

#endif
