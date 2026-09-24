#ifndef CPLUS_STDLIB_IO_FILE_CP
#define CPLUS_STDLIB_IO_FILE_CP

#include <errno.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdio.h>

/* Compatibility facade retaining the direct, static C stdio signatures. */
typedef struct stdio_t {
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
} stdio_t;

/* Value wrapper for an owned C stream. fclose() closes and clears the stream. */
typedef struct file_t {
    owned FILE* stream;
    borrowed mut char* buffer;

    static pub file_t fopen(borrowed const char* path, borrowed const char* mode) {
        file_t file = { fopen(path, mode), NULL };
        return file;
    }

    pub int freopen(borrowed mut *self, borrowed const char* path, borrowed const char* mode) {
        if (self == NULL || self->stream == NULL || path == NULL || mode == NULL) return EINVAL;

        errno = 0;
        self->stream = freopen(path, mode, self->stream);
        self->buffer = NULL;
        if (self->stream == NULL) return errno == 0 ? EOF : errno;
        return 0;
    }

    static pub file_t tmpfile(void) {
        file_t file = { tmpfile(), NULL };
        return file;
    }

    pub int fclose(borrowed mut *self) {
        if (self == NULL) return EOF;

        int result = self->stream == NULL ? EOF : fclose(self->stream);
        self->stream = NULL;
        self->buffer = NULL;
        return result;
    }

    pub int fflush(borrowed mut *self) {
        if (self == NULL || self->stream == NULL) return EOF;
        return fflush(self->stream);
    }

    pub void setbuf(borrowed mut *self) {
        if (self == NULL || self->stream == NULL) return;
        setbuf(self->stream, self->buffer);
    }

    pub int setvbuf(borrowed mut *self, int mode, size_t size) {
        if (self == NULL || self->stream == NULL) return EOF;
        return setvbuf(self->stream, self->buffer, mode, size);
    }

    pub int fprintf(borrowed mut *self, borrowed const char* format, ...) {
        if (self == NULL || self->stream == NULL) return EOF;
        va_list arguments;
        va_start(arguments, format);
        int result = vfprintf(self->stream, format, arguments);
        va_end(arguments);
        return result;
    }

    pub int fscanf(borrowed mut *self, borrowed const char* format, ...) {
        if (self == NULL || self->stream == NULL) return EOF;
        va_list arguments;
        va_start(arguments, format);
        int result = vfscanf(self->stream, format, arguments);
        va_end(arguments);
        return result;
    }

    pub int vfprintf(borrowed mut *self, borrowed const char* format, va_list arguments) {
        if (self == NULL || self->stream == NULL) return EOF;
        return vfprintf(self->stream, format, arguments);
    }

    pub int vfscanf(borrowed mut *self, borrowed const char* format, va_list arguments) {
        if (self == NULL || self->stream == NULL) return EOF;
        return vfscanf(self->stream, format, arguments);
    }

    pub int fgetc(borrowed mut *self) {
        if (self == NULL || self->stream == NULL) return EOF;
        return fgetc(self->stream);
    }

    pub int getc(borrowed mut *self) {
        if (self == NULL || self->stream == NULL) return EOF;
        return getc(self->stream);
    }

    pub int fputc(borrowed mut *self, int character) {
        if (self == NULL || self->stream == NULL) return EOF;
        return fputc(character, self->stream);
    }

    pub int putc(borrowed mut *self, int character) {
        if (self == NULL || self->stream == NULL) return EOF;
        return putc(character, self->stream);
    }

    pub borrowed char* fgets(borrowed mut *self, borrowed mut char* buffer, int size) {
        if (self == NULL || self->stream == NULL) return NULL;
        return fgets(buffer, size, self->stream);
    }

    pub int fputs(borrowed mut *self, borrowed const char* text) {
        if (self == NULL || self->stream == NULL) return EOF;
        return fputs(text, self->stream);
    }

    pub int ungetc(borrowed mut *self, int character) {
        if (self == NULL || self->stream == NULL) return EOF;
        return ungetc(character, self->stream);
    }

    pub size_t fread(borrowed mut *self, borrowed mut void* destination, size_t size, size_t count) {
        if (self == NULL || self->stream == NULL) return 0;
        return fread(destination, size, count, self->stream);
    }

    pub size_t fwrite(borrowed mut *self, borrowed const void* source, size_t size, size_t count) {
        if (self == NULL || self->stream == NULL) return 0;
        return fwrite(source, size, count, self->stream);
    }

    pub int fgetpos(borrowed *self, borrowed mut fpos_t* position) {
        if (self == NULL || self->stream == NULL) return -1;
        return fgetpos(self->stream, position);
    }

    pub int fseek(borrowed mut *self, long offset, int origin) {
        if (self == NULL || self->stream == NULL) return -1;
        return fseek(self->stream, offset, origin);
    }

    pub int fsetpos(borrowed mut *self, borrowed const fpos_t* position) {
        if (self == NULL || self->stream == NULL) return -1;
        return fsetpos(self->stream, position);
    }

    pub long ftell(borrowed *self) {
        if (self == NULL || self->stream == NULL) return -1L;
        return ftell(self->stream);
    }

    pub void rewind(borrowed mut *self) {
        if (self != NULL && self->stream != NULL) rewind(self->stream);
    }

    pub void clearerr(borrowed mut *self) {
        if (self != NULL && self->stream != NULL) clearerr(self->stream);
    }

    pub int feof(borrowed *self) {
        if (self == NULL || self->stream == NULL) return 0;
        return feof(self->stream);
    }

    pub int ferror(borrowed *self) {
        if (self == NULL || self->stream == NULL) return 0;
        return ferror(self->stream);
    }
} file_t;

typedef struct path_t{

    static pub int remove(borrowed const char* path) {
        return remove(path);
    }

    static pub int rename(borrowed const char* old_path, borrowed const char* new_path) {
        return rename(old_path, new_path);
    }
} path_t;


typedef struct var_io_t {
    borrowed FILE* input;
    borrowed FILE* output;
    borrowed FILE* error;

    static pub var_io_t standard(void) {
        var_io_t streams = { stdin, stdout, stderr };
        return streams;
    }

    static pub int sprintf(borrowed mut char* destination, borrowed const char* format, ...) {
        va_list arguments;
        va_start(arguments, format);
        int result = vsprintf(destination, format, arguments);
        va_end(arguments);
        return result;
    }

    static pub int printf(borrowed const char* format, ...) {
        va_list arguments;
        va_start(arguments, format);
        int result = vprintf(format, arguments);
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

    static pub int vsprintf(borrowed mut char* destination, borrowed const char* format, va_list arguments) {
        return vsprintf(destination, format, arguments);
    }

    static pub int vsnprintf(borrowed mut char* destination, size_t size, borrowed const char* format, va_list arguments) {
        return vsnprintf(destination, size, format, arguments);
    }

    static pub int vscanf(borrowed const char* format, va_list arguments) {
        return vscanf(format, arguments);
    }

    static pub int vsscanf(borrowed const char* input, borrowed const char* format, va_list arguments) {
        return vsscanf(input, format, arguments);
    }

    static pub int getchar(void) {
        return getchar();
    }

    static pub int putchar(int character) {
        return putchar(character);
    }

    static pub int puts(borrowed const char* text) {
        return puts(text);
    }

    static pub void perror(borrowed const char* prefix) {
        perror(prefix);
    }

} var_io_t;

#endif
