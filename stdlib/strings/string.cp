#ifndef CPLUS_STDLIB_STRING_CP
#define CPLUS_STDLIB_STRING_CP

#include <stddef.h>
#include <stdlib.h>
#include <string.h>

comptime import "stdlib:/memory/xmem.cp";

#ifndef CPLUS_ERROR_T_DEFINED
#define CPLUS_ERROR_T_DEFINED
typedef int error_t;
#endif

enum {
    STRING_OK = 0,
    STRING_ERROR_INVALID_ARGUMENT = 1,
    STRING_ERROR_ALLOCATION = 2,
    STRING_ERROR_RANGE = 3
};

typedef struct string {
    warm char* data;
    size_t length;
    size_t capacity;

    pub error_t init(borrowed mut *self) {
        if (self == NULL) return STRING_ERROR_INVALID_ARGUMENT;
        self->data = NULL;
        self->length = 0;
        self->capacity = 0;
        return STRING_OK;
    }

    pub error_t reserve(borrowed mut *self, size_t requested_capacity) {
        if (self == NULL) return STRING_ERROR_INVALID_ARGUMENT;
        if (requested_capacity <= self->capacity && self->data != NULL) return STRING_OK;
        if (requested_capacity == (size_t)-1) return STRING_ERROR_RANGE;

        size_t bytes = requested_capacity + 1;
        char* resized = (char*)realloc_warm(self->data, bytes);
        if (resized == NULL) return STRING_ERROR_ALLOCATION;

        self->data = resized;
        self->capacity = requested_capacity;
        if (self->length == 0) self->data[0] = '\0';
        return STRING_OK;
    }

    pub error_t assign(borrowed mut *self, borrowed const char* source) {
        if (self == NULL || source == NULL) return STRING_ERROR_INVALID_ARGUMENT;
        size_t source_length = strlen(source);
        size_t source_offset = 0;
        int internal_source = 0;
        if (self->data != NULL) {
            for (size_t i = 0; i <= self->length; i++) {
                if (source == self->data + i) { source_offset = i; internal_source = 1; break; }
            }
        }
        error_t error = string__reserve(self, source_length);
        if (error != STRING_OK) return error;
        if (internal_source) source = self->data + source_offset;
        memmove(self->data, source, source_length + 1);
        self->length = source_length;
        return STRING_OK;
    }

    pub error_t assign_n(borrowed mut *self, borrowed const char* source, size_t count) {
        if (self == NULL || source == NULL) return STRING_ERROR_INVALID_ARGUMENT;
        size_t source_length = strlen(source);
        size_t copied = source_length < count ? source_length : count;
        size_t source_offset = 0;
        int internal_source = 0;
        if (self->data != NULL) {
            for (size_t i = 0; i <= self->length; i++) {
                if (source == self->data + i) { source_offset = i; internal_source = 1; break; }
            }
        }
        error_t error = string__reserve(self, copied);
        if (error != STRING_OK) return error;
        if (internal_source) source = self->data + source_offset;
        if (copied > 0) memmove(self->data, source, copied);
        self->data[copied] = '\0';
        self->length = copied;
        return STRING_OK;
    }

    pub error_t append(borrowed mut *self, borrowed const char* suffix) {
        if (self == NULL || suffix == NULL) return STRING_ERROR_INVALID_ARGUMENT;
        size_t suffix_length = strlen(suffix);
        size_t source_offset = 0;
        int internal_source = 0;
        if (self->data != NULL) {
            for (size_t i = 0; i <= self->length; i++) {
                if (suffix == self->data + i) { source_offset = i; internal_source = 1; break; }
            }
        }
        if (suffix_length > ((size_t)-1) - self->length) return STRING_ERROR_RANGE;
        size_t old_length = self->length;
        size_t new_length = old_length + suffix_length;
        error_t error = string__reserve(self, new_length);
        if (error != STRING_OK) return error;
        if (internal_source) suffix = self->data + source_offset;
        memmove(self->data + old_length, suffix, suffix_length + 1);
        self->length = new_length;
        return STRING_OK;
    }

    pub error_t append_n(borrowed mut *self, borrowed const char* suffix, size_t count) {
        if (self == NULL || suffix == NULL) return STRING_ERROR_INVALID_ARGUMENT;
        size_t suffix_length = strlen(suffix);
        size_t appended = suffix_length < count ? suffix_length : count;
        size_t source_offset = 0;
        int internal_source = 0;
        if (self->data != NULL) {
            for (size_t i = 0; i <= self->length; i++) {
                if (suffix == self->data + i) { source_offset = i; internal_source = 1; break; }
            }
        }
        if (appended > ((size_t)-1) - self->length) return STRING_ERROR_RANGE;
        size_t old_length = self->length;
        size_t new_length = old_length + appended;
        error_t error = string__reserve(self, new_length);
        if (error != STRING_OK) return error;
        if (internal_source) suffix = self->data + source_offset;
        if (appended > 0) memmove(self->data + old_length, suffix, appended);
        self->data[new_length] = '\0';
        self->length = new_length;
        return STRING_OK;
    }

    pub error_t append_char(borrowed mut *self, char value) {
        if (self == NULL) return STRING_ERROR_INVALID_ARGUMENT;
        if (self->length == (size_t)-1) return STRING_ERROR_RANGE;
        size_t new_length = self->length + 1;
        error_t error = string__reserve(self, new_length);
        if (error != STRING_OK) return error;
        self->data[self->length] = value;
        self->data[new_length] = '\0';
        self->length = new_length;
        return STRING_OK;
    }

    pub void clear(borrowed mut *self) {
        if (self == NULL) return;
        self->length = 0;
        if (self->data != NULL) self->data[0] = '\0';
    }

    pub borrowed const char* c_str(borrowed *self) {
        if (self == NULL || self->data == NULL) return "";
        return self->data;
    }

    pub size_t size(borrowed *self) {
        return self == NULL ? 0 : self->length;
    }

    pub size_t capacity_of(borrowed *self) {
        return self == NULL ? 0 : self->capacity;
    }

    pub int empty(borrowed *self) {
        return self == NULL || self->length == 0;
    }

    pub void destroy(borrowed mut *self) {
        if (self == NULL) return;
        free_warm(self->data);
        self->data = NULL;
        self->length = 0;
        self->capacity = 0;
    }

    /* Object-managed facade over the C string functions. */
    pub size_t strlen(borrowed *self) {
        return strlen(string__c_str(self));
    }

    pub error_t strcpy(borrowed mut *self, borrowed const char* source) {
        if (self == NULL || source == NULL) return STRING_ERROR_INVALID_ARGUMENT;
        size_t source_length = strlen(source);
        error_t error = string__reserve(self, source_length);
        if (error != STRING_OK) return error;
        size_t source_offset = 0;
        int internal_source = 0;
        if (self->data != NULL) {
            for (size_t i = 0; i <= self->length; i++) {
                if (source == self->data + i) { source_offset = i; internal_source = 1; break; }
            }
        }
        if (internal_source) source = self->data + source_offset;
        memmove(self->data, source, source_length + 1);
        self->length = source_length;
        return STRING_OK;
    }

    pub error_t strncpy(borrowed mut *self, borrowed const char* source, size_t count) {
        if (self == NULL || source == NULL) return STRING_ERROR_INVALID_ARGUMENT;
        size_t source_length = strlen(source);
        size_t copied = source_length < count ? source_length : count;
        error_t error = string__reserve(self, copied);
        if (error != STRING_OK) return error;
        size_t source_offset = 0;
        int internal_source = 0;
        if (self->data != NULL) {
            for (size_t i = 0; i <= self->length; i++) {
                if (source == self->data + i) { source_offset = i; internal_source = 1; break; }
            }
        }
        if (internal_source) source = self->data + source_offset;
        if (copied > 0) memmove(self->data, source, copied);
        self->data[copied] = '\0';
        self->length = copied;
        return STRING_OK;
    }

    pub error_t strcat(borrowed mut *self, borrowed const char* suffix) {
        if (self == NULL || suffix == NULL) return STRING_ERROR_INVALID_ARGUMENT;
        size_t suffix_length = strlen(suffix);
        size_t source_offset = 0;
        int internal_source = 0;
        if (self->data != NULL) {
            for (size_t i = 0; i <= self->length; i++) {
                if (suffix == self->data + i) { source_offset = i; internal_source = 1; break; }
            }
        }
        if (suffix_length > ((size_t)-1) - self->length) return STRING_ERROR_RANGE;
        size_t old_length = self->length;
        size_t new_length = old_length + suffix_length;
        error_t error = string__reserve(self, new_length);
        if (error != STRING_OK) return error;
        if (internal_source) {
            suffix = self->data + source_offset;
            memmove(self->data + old_length, suffix, suffix_length + 1);
        } else {
            strcat(self->data, suffix);
        }
        self->length = new_length;
        return STRING_OK;
    }

    pub error_t strncat(borrowed mut *self, borrowed const char* suffix, size_t count) {
        if (self == NULL || suffix == NULL) return STRING_ERROR_INVALID_ARGUMENT;
        size_t suffix_length = strlen(suffix);
        size_t appended = suffix_length < count ? suffix_length : count;
        size_t source_offset = 0;
        int internal_source = 0;
        if (self->data != NULL) {
            for (size_t i = 0; i <= self->length; i++) {
                if (suffix == self->data + i) { source_offset = i; internal_source = 1; break; }
            }
        }
        if (appended > ((size_t)-1) - self->length) return STRING_ERROR_RANGE;
        size_t old_length = self->length;
        size_t new_length = old_length + appended;
        error_t error = string__reserve(self, new_length);
        if (error != STRING_OK) return error;
        if (internal_source) {
            suffix = self->data + source_offset;
            if (appended > 0) memmove(self->data + old_length, suffix, appended);
            self->data[new_length] = '\0';
        } else {
            strncat(self->data, suffix, appended);
        }
        self->length = new_length;
        return STRING_OK;
    }

    pub int strcmp(borrowed *self, borrowed const char* other) {
        if (other == NULL) return 1;
        return strcmp(string__c_str(self), other);
    }

    pub int strncmp(borrowed *self, borrowed const char* other, size_t count) {
        if (other == NULL) return 1;
        return strncmp(string__c_str(self), other, count);
    }

    pub int strcoll(borrowed *self, borrowed const char* other) {
        if (other == NULL) return 1;
        return strcoll(string__c_str(self), other);
    }

    pub borrowed const char* strchr(borrowed *self, int value) {
        return strchr(string__c_str(self), value);
    }

    pub borrowed const char* strrchr(borrowed *self, int value) {
        return strrchr(string__c_str(self), value);
    }

    pub borrowed const char* strstr(borrowed *self, borrowed const char* needle) {
        if (needle == NULL) return NULL;
        return strstr(string__c_str(self), needle);
    }

    pub size_t strspn(borrowed *self, borrowed const char* accept) {
        if (accept == NULL) return 0;
        return strspn(string__c_str(self), accept);
    }

    pub size_t strcspn(borrowed *self, borrowed const char* reject) {
        if (reject == NULL) return 0;
        return strcspn(string__c_str(self), reject);
    }

    pub borrowed const char* strpbrk(borrowed *self, borrowed const char* accept) {
        if (accept == NULL) return NULL;
        return strpbrk(string__c_str(self), accept);
    }

    pub error_t strxfrm(borrowed mut *self, borrowed const char* source) {
        if (self == NULL || source == NULL) return STRING_ERROR_INVALID_ARGUMENT;
        char* temporary_source = NULL;
        if (self->data != NULL) {
            for (size_t i = 0; i <= self->length; i++) {
                if (source == self->data + i) {
                    size_t source_length = strlen(source);
                    temporary_source = (char*)alloc_warm(source_length + 1);
                    if (temporary_source == NULL) return STRING_ERROR_ALLOCATION;
                    memcpy(temporary_source, source, source_length + 1);
                    source = temporary_source;
                    break;
                }
            }
        }
        size_t required = strxfrm(NULL, source, 0);
        error_t error = string__reserve(self, required);
        if (error != STRING_OK) { free_warm(temporary_source); return error; }
        size_t transformed = strxfrm(self->data, source, required + 1);
        free_warm(temporary_source);
        if (transformed > required) return STRING_ERROR_RANGE;
        self->length = transformed;
        return STRING_OK;
    }

    /* Raw-memory/string.h utilities that do not belong to an instance. */
    static pub borrowed void* memchr(borrowed const void* memory, int value, size_t count) {
        return (void*)memchr(memory, value, count);
    }

    static pub int memcmp(borrowed const void* left, borrowed const void* right, size_t count) {
        if (left == NULL || right == NULL) return left == right ? 0 : (left == NULL ? -1 : 1);
        return memcmp(left, right, count);
    }

    static pub borrowed void* memcpy(borrowed mut void* destination, borrowed const void* source, size_t count) {
        if (destination == NULL || source == NULL) return NULL;
        return memcpy(destination, source, count);
    }

    static pub borrowed void* memmove(borrowed mut void* destination, borrowed const void* source, size_t count) {
        if (destination == NULL || source == NULL) return NULL;
        return memmove(destination, source, count);
    }

    static pub borrowed void* memset(borrowed mut void* destination, int value, size_t count) {
        if (destination == NULL) return NULL;
        return memset(destination, value, count);
    }

    static pub borrowed char* strerror(int error_number) {
        return strerror(error_number);
    }

    static pub borrowed char* strtok(borrowed mut char* source, borrowed const char* delimiters) {
        if (delimiters == NULL) return NULL;
        return strtok(source, delimiters);
    }

    pub error_t indent(borrowed mut *self, int number_of_spaces) {
        if (self == NULL || number_of_spaces < 0) return STRING_ERROR_INVALID_ARGUMENT;
        if (number_of_spaces == 0 || self->length == 0) return STRING_OK;

        size_t spaces = (size_t)number_of_spaces;
        size_t line_count = 1;
        for (size_t i = 0; i < self->length; i++) {
            if (self->data[i] == '\n' && i + 1 < self->length) line_count++;
        }
        if (spaces != 0 && line_count > (((size_t)-1) - self->length) / spaces) return STRING_ERROR_RANGE;
        size_t new_length = self->length + line_count * spaces;
        error_t error = string__reserve(self, new_length);
        if (error != STRING_OK) return error;

        size_t read_index = self->length;
        size_t write_index = new_length;
        self->data[write_index] = '\0';
        while (read_index > 0) {
            char c = self->data[--read_index];
            self->data[--write_index] = c;
            if (read_index == 0 || (read_index > 0 && self->data[read_index - 1] == '\n')) {
                for (size_t j = 0; j < spaces; j++) self->data[--write_index] = ' ';
            }
        }
        self->length = new_length;
        return STRING_OK;
    }

    pub error_t dedent(borrowed mut *self, int number_of_spaces) {
        if (self == NULL || number_of_spaces < 0) return STRING_ERROR_INVALID_ARGUMENT;
        if (number_of_spaces == 0 || self->length == 0) return STRING_OK;

        size_t spaces = (size_t)number_of_spaces;
        size_t read_index = 0;
        size_t write_index = 0;
        int at_line_start = 1;

        while (read_index < self->length) {
            if (at_line_start) {
                size_t removed = 0;
                while (read_index < self->length && removed < spaces && self->data[read_index] == ' ') {
                    read_index++;
                    removed++;
                }
                at_line_start = 0;
                if (read_index >= self->length) break;
            }
            char c = self->data[read_index++];
            self->data[write_index++] = c;
            if (c == '\n') at_line_start = 1;
        }
        self->data[write_index] = '\0';
        self->length = write_index;
        return STRING_OK;
    }

    static pub error_t trim_indent(borrowed mut string* str) {
        if (str == NULL) return STRING_ERROR_INVALID_ARGUMENT;
        if (str->length == 0 || str->data == NULL) return STRING_OK;

        size_t minimum_indent = (size_t)-1;
        size_t index = 0;
        while (index < str->length) {
            size_t indent_count = 0;
            while (index < str->length && str->data[index] == ' ') {
                indent_count++;
                index++;
            }

            if (index < str->length && str->data[index] != '\n') {
                if (indent_count < minimum_indent) minimum_indent = indent_count;
            }

            while (index < str->length && str->data[index] != '\n') index++;
            if (index < str->length && str->data[index] == '\n') index++;
        }

        if (minimum_indent == (size_t)-1 || minimum_indent == 0) return STRING_OK;
        if (minimum_indent > (size_t)2147483647) return STRING_ERROR_RANGE;
        return string__dedent(str, (int)minimum_indent);
    }
} string;

#endif
