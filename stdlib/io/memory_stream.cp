#ifndef CPLUS_STDLIB_IO_MEMORY_STREAM_CP
#define CPLUS_STDLIB_IO_MEMORY_STREAM_CP

#include <errno.h>
#include <limits.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

comptime import "stdlib:/memory/xmem.cp";

#ifndef CPLUS_ERROR_T_DEFINED
#define CPLUS_ERROR_T_DEFINED
typedef int error_t;
#endif

enum {
    MEMORY_STREAM_OK = 0,
    MEMORY_STREAM_ERROR_INVALID_ARGUMENT = 1,
    MEMORY_STREAM_ERROR_ALLOCATION = 2,
    MEMORY_STREAM_ERROR_RANGE = 3,
    MEMORY_STREAM_ERROR_CLOSED = 4
};

/* Growable binary storage. The contents are bytes, not a NUL-terminated string. */
typedef struct memory_stream_t {
    warm unsigned char* data;
    size_t length;
    size_t capacity;
    size_t position;
    int is_open;

    pub error_t init(borrowed mut *self) {
        if (self == NULL) return MEMORY_STREAM_ERROR_INVALID_ARGUMENT;
        self->data = NULL;
        self->length = 0;
        self->capacity = 0;
        self->position = 0;
        self->is_open = 1;
        return MEMORY_STREAM_OK;
    }

    pub error_t reserve(borrowed mut *self, size_t requested) {
        if (self == NULL) return MEMORY_STREAM_ERROR_INVALID_ARGUMENT;
        if (!self->is_open) return MEMORY_STREAM_ERROR_CLOSED;
        if (requested > (size_t)LONG_MAX) return MEMORY_STREAM_ERROR_RANGE;
        if (requested <= self->capacity) return MEMORY_STREAM_OK;

        warm unsigned char* resized = (warm unsigned char*)realloc_warm(self->data, requested);
        if (resized == NULL) return MEMORY_STREAM_ERROR_ALLOCATION;
        self->data = resized;
        self->capacity = requested;
        return MEMORY_STREAM_OK;
    }

    pub size_t read_bytes(borrowed mut *self, borrowed mut void* destination, size_t count) {
        if (self == NULL) { errno = EINVAL; return 0; }
        if (!self->is_open) { errno = EBADF; return 0; }
        if (count == 0) return 0;
        if (destination == NULL) { errno = EINVAL; return 0; }
        if (self->position >= self->length) return 0;

        size_t available = self->length - self->position;
        size_t copied = count < available ? count : available;
        memmove(destination, self->data + self->position, copied);
        self->position += copied;
        return copied;
    }

    pub size_t write_bytes(borrowed mut *self, borrowed const void* source, size_t count) {
        if (self == NULL) { errno = EINVAL; return 0; }
        if (!self->is_open) { errno = EBADF; return 0; }
        if (count == 0) return 0;
        if (source == NULL) { errno = EINVAL; return 0; }
        if (self->position > (size_t)LONG_MAX || count > (size_t)LONG_MAX - self->position) {
            errno = ERANGE;
            return 0;
        }

        size_t source_offset = 0;
        int source_is_internal = 0;
        if (self->data != NULL) {
            for (size_t i = 0; i < self->length; i++) {
                if (source == self->data + i) {
                    source_offset = i;
                    source_is_internal = 1;
                    break;
                }
            }
        }
        if (source_is_internal && count > self->length - source_offset) {
            errno = EINVAL;
            return 0;
        }

        size_t end = self->position + count;
        error_t error = memory_stream__reserve(self, end);
        if (error != MEMORY_STREAM_OK) {
            errno = error == MEMORY_STREAM_ERROR_RANGE ? ERANGE : ENOMEM;
            return 0;
        }

        if (source_is_internal) source = self->data + source_offset;

        if (self->position > self->length)
            memset(self->data + self->length, 0, self->position - self->length);
        memmove(self->data + self->position, source, count);
        self->position = end;
        if (end > self->length) self->length = end;
        return count;
    }

    pub int seek(borrowed mut *self, long offset, int origin) {
        if (self == NULL) { errno = EINVAL; return -1; }
        if (!self->is_open) { errno = EBADF; return -1; }

        long base;
        if (origin == SEEK_SET) base = 0;
        else if (origin == SEEK_CUR) {
            if (self->position > (size_t)LONG_MAX) { errno = ERANGE; return -1; }
            base = (long)self->position;
        } else if (origin == SEEK_END) {
            if (self->length > (size_t)LONG_MAX) { errno = ERANGE; return -1; }
            base = (long)self->length;
        } else {
            errno = EINVAL;
            return -1;
        }

        long target;
        if (offset < 0) {
            unsigned long distance = (unsigned long)(-(offset + 1)) + 1UL;
            if (distance > (unsigned long)base) { errno = EINVAL; return -1; }
            target = base - (long)distance;
        } else {
            if (offset > LONG_MAX - base) { errno = ERANGE; return -1; }
            target = base + offset;
        }

        self->position = (size_t)target;
        return 0;
    }

    pub long tell(borrowed *self) {
        if (self == NULL) { errno = EINVAL; return -1L; }
        if (!self->is_open) { errno = EBADF; return -1L; }
        if (self->position > (size_t)LONG_MAX) { errno = ERANGE; return -1L; }
        return (long)self->position;
    }

    pub long size(borrowed *self) {
        if (self == NULL) { errno = EINVAL; return -1L; }
        if (!self->is_open) { errno = EBADF; return -1L; }
        if (self->length > (size_t)LONG_MAX) { errno = ERANGE; return -1L; }
        return (long)self->length;
    }

    pub error_t truncate(borrowed mut *self, size_t new_length) {
        if (self == NULL) return MEMORY_STREAM_ERROR_INVALID_ARGUMENT;
        if (!self->is_open) return MEMORY_STREAM_ERROR_CLOSED;
        if (new_length > (size_t)LONG_MAX) return MEMORY_STREAM_ERROR_RANGE;
        if (new_length > self->length) {
            error_t error = memory_stream__reserve(self, new_length);
            if (error != MEMORY_STREAM_OK) return error;
            memset(self->data + self->length, 0, new_length - self->length);
        }
        self->length = new_length;
        return MEMORY_STREAM_OK;
    }

    pub void clear(borrowed mut *self) {
        if (self == NULL) return;
        if (!self->is_open) { errno = EBADF; return; }
        self->length = 0;
        self->position = 0;
    }

    pub borrowed const unsigned char* buffer(borrowed *self) {
        if (self == NULL || !self->is_open) return NULL;
        return self->data;
    }

    pub int flush(borrowed *self) {
        if (self == NULL) { errno = EINVAL; return EOF; }
        if (!self->is_open) { errno = EBADF; return EOF; }
        return 0;
    }

    pub int close(borrowed mut *self) {
        if (self == NULL) { errno = EINVAL; return EOF; }
        if (!self->is_open) { errno = EBADF; return EOF; }
        free_warm(self->data);
        self->data = NULL;
        self->length = 0;
        self->capacity = 0;
        self->position = 0;
        self->is_open = 0;
        return 0;
    }

    pub error_t init_copy(borrowed mut *self, borrowed const void* source, size_t count) {
        if (self == NULL || (source == NULL && count != 0))
            return MEMORY_STREAM_ERROR_INVALID_ARGUMENT;
        if (count > (size_t)LONG_MAX) return MEMORY_STREAM_ERROR_RANGE;

        error_t error = memory_stream__init(self);
        if (error != MEMORY_STREAM_OK) return error;
        if (count == 0) return MEMORY_STREAM_OK;

        size_t copied = memory_stream__write_bytes(self, source, count);
        if (copied != count) {
            memory_stream__close(self);
            return errno == ERANGE ? MEMORY_STREAM_ERROR_RANGE : MEMORY_STREAM_ERROR_ALLOCATION;
        }
        self->position = 0;
        return MEMORY_STREAM_OK;
    }
} memory_stream_t;

#endif
