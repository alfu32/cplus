/* C-plus source annotations are intentionally retained in generated C. */
#ifndef CPLUS_ANNOTATIONS_DEFINED
#define CPLUS_ANNOTATIONS_DEFINED
#define pub
#define priv
#define mut
#define borrowed
#define owned
#define stat
#endif


#line 1 "/home/devlin/Development/c-plus/examples/generic_list.cp"
// A generic, owning storage container. Element lifetime is controlled by the
// caller: the list copies values but does not free pointed-to data.
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

typedef char* cstring_t;

typedef struct named_value_t {
    char* name;
    char* value;
    unsigned short rank;
} named_value_t;

// @dynamic_list(T) materializes a normal C-plus struct for each requested T.
// The generic operations copy values byte-for-byte, so they work for scalars,
// pointers, and plain value structs. They do not deep-copy or destroy members.
#line 97 "/home/devlin/Development/c-plus/examples/generic_list.cp"


// Each invocation materializes a distinct C-plus type and method family.
typedef struct named_value_list_t {
#line 20 "/home/devlin/Development/c-plus/examples/generic_list.cp"

        named_value_t* items;
        size_t length;
        size_t capacity;
#line 96 "/home/devlin/Development/c-plus/examples/generic_list.cp"
    
#line 100 "/home/devlin/Development/c-plus/examples/generic_list.cp"

#line 100 "/home/devlin/Development/c-plus/examples/generic_list.cp"
} named_value_list_t;
#line 100 "/home/devlin/Development/c-plus/examples/generic_list.cp"

#line 25 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int named_value_list__init(borrowed mut named_value_list_t *self) {
            self->items = NULL;
            self->length = 0;
            self->capacity = 0;
            return 0;
        }

#line 32 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int named_value_list__reserve(borrowed mut named_value_list_t *self, size_t requested) {
            if (requested <= self->capacity) return 0;
            named_value_t* resized = realloc(self->items, requested * sizeof(named_value_t));
            if (resized == NULL) return 1;
            self->items = resized;
            self->capacity = requested;
            return 0;
        }

#line 41 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int named_value_list__push(borrowed mut named_value_list_t *self, const named_value_t* value) {
            if (self->length == self->capacity) {
                size_t next_capacity = self->capacity == 0 ? 4 : self->capacity * 2;
                named_value_t* resized = realloc(self->items, next_capacity * sizeof(named_value_t));
                if (resized == NULL) return 1;
                self->items = resized;
                self->capacity = next_capacity;
            }
            self->items[self->length++] = *value;
            return 0;
        }

#line 53 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int named_value_list__pop(borrowed mut named_value_list_t *self, borrowed named_value_t* out) {
            if (self->length == 0) return 1;
            self->length--;
            if (out != NULL) *out = self->items[self->length];
            return 0;
        }

#line 60 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub borrowed named_value_t* named_value_list__get(borrowed named_value_list_t *self, size_t index) {
            return index < self->length ? &self->items[index] : NULL;
        }

#line 64 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int named_value_list__set(borrowed mut named_value_list_t *self, size_t index, const named_value_t* value) {
            if (index >= self->length) return 1;
            self->items[index] = *value;
            return 0;
        }

#line 70 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub void named_value_list__clear(borrowed mut named_value_list_t *self) {
            self->length = 0;
        }

#line 74 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub size_t named_value_list__size(borrowed named_value_list_t *self) {
            return self->length;
        }

#line 78 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub size_t named_value_list__capacity(borrowed named_value_list_t *self) {
            return self->capacity;
        }

#line 82 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int named_value_list__empty(borrowed named_value_list_t *self) {
            return self->length == 0;
        }

#line 86 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub borrowed named_value_t* named_value_list__data(borrowed named_value_list_t *self) {
            return self->items;
        }

#line 90 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub void named_value_list__destroy(borrowed mut named_value_list_t *self) {
            free(self->items);
            self->items = NULL;
            self->length = 0;
            self->capacity = 0;
        }
#line 100 "/home/devlin/Development/c-plus/examples/generic_list.cp"

#line 100 "/home/devlin/Development/c-plus/examples/generic_list.cp"

typedef struct string_list_t {
#line 20 "/home/devlin/Development/c-plus/examples/generic_list.cp"

        cstring_t* items;
        size_t length;
        size_t capacity;
#line 96 "/home/devlin/Development/c-plus/examples/generic_list.cp"
    
#line 101 "/home/devlin/Development/c-plus/examples/generic_list.cp"

#line 101 "/home/devlin/Development/c-plus/examples/generic_list.cp"
} string_list_t;
#line 101 "/home/devlin/Development/c-plus/examples/generic_list.cp"

#line 25 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int string_list__init(borrowed mut string_list_t *self) {
            self->items = NULL;
            self->length = 0;
            self->capacity = 0;
            return 0;
        }

#line 32 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int string_list__reserve(borrowed mut string_list_t *self, size_t requested) {
            if (requested <= self->capacity) return 0;
            cstring_t* resized = realloc(self->items, requested * sizeof(cstring_t));
            if (resized == NULL) return 1;
            self->items = resized;
            self->capacity = requested;
            return 0;
        }

#line 41 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int string_list__push(borrowed mut string_list_t *self, const cstring_t* value) {
            if (self->length == self->capacity) {
                size_t next_capacity = self->capacity == 0 ? 4 : self->capacity * 2;
                cstring_t* resized = realloc(self->items, next_capacity * sizeof(cstring_t));
                if (resized == NULL) return 1;
                self->items = resized;
                self->capacity = next_capacity;
            }
            self->items[self->length++] = *value;
            return 0;
        }

#line 53 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int string_list__pop(borrowed mut string_list_t *self, borrowed cstring_t* out) {
            if (self->length == 0) return 1;
            self->length--;
            if (out != NULL) *out = self->items[self->length];
            return 0;
        }

#line 60 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub borrowed cstring_t* string_list__get(borrowed string_list_t *self, size_t index) {
            return index < self->length ? &self->items[index] : NULL;
        }

#line 64 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int string_list__set(borrowed mut string_list_t *self, size_t index, const cstring_t* value) {
            if (index >= self->length) return 1;
            self->items[index] = *value;
            return 0;
        }

#line 70 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub void string_list__clear(borrowed mut string_list_t *self) {
            self->length = 0;
        }

#line 74 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub size_t string_list__size(borrowed string_list_t *self) {
            return self->length;
        }

#line 78 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub size_t string_list__capacity(borrowed string_list_t *self) {
            return self->capacity;
        }

#line 82 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int string_list__empty(borrowed string_list_t *self) {
            return self->length == 0;
        }

#line 86 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub borrowed cstring_t* string_list__data(borrowed string_list_t *self) {
            return self->items;
        }

#line 90 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub void string_list__destroy(borrowed mut string_list_t *self) {
            free(self->items);
            self->items = NULL;
            self->length = 0;
            self->capacity = 0;
        }
#line 101 "/home/devlin/Development/c-plus/examples/generic_list.cp"

#line 101 "/home/devlin/Development/c-plus/examples/generic_list.cp"

typedef struct int_list_t {
#line 20 "/home/devlin/Development/c-plus/examples/generic_list.cp"

        int* items;
        size_t length;
        size_t capacity;
#line 96 "/home/devlin/Development/c-plus/examples/generic_list.cp"
    
#line 102 "/home/devlin/Development/c-plus/examples/generic_list.cp"

#line 102 "/home/devlin/Development/c-plus/examples/generic_list.cp"
} int_list_t;
#line 102 "/home/devlin/Development/c-plus/examples/generic_list.cp"

#line 25 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int int_list__init(borrowed mut int_list_t *self) {
            self->items = NULL;
            self->length = 0;
            self->capacity = 0;
            return 0;
        }

#line 32 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int int_list__reserve(borrowed mut int_list_t *self, size_t requested) {
            if (requested <= self->capacity) return 0;
            int* resized = realloc(self->items, requested * sizeof(int));
            if (resized == NULL) return 1;
            self->items = resized;
            self->capacity = requested;
            return 0;
        }

#line 41 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int int_list__push(borrowed mut int_list_t *self, const int* value) {
            if (self->length == self->capacity) {
                size_t next_capacity = self->capacity == 0 ? 4 : self->capacity * 2;
                int* resized = realloc(self->items, next_capacity * sizeof(int));
                if (resized == NULL) return 1;
                self->items = resized;
                self->capacity = next_capacity;
            }
            self->items[self->length++] = *value;
            return 0;
        }

#line 53 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int int_list__pop(borrowed mut int_list_t *self, borrowed int* out) {
            if (self->length == 0) return 1;
            self->length--;
            if (out != NULL) *out = self->items[self->length];
            return 0;
        }

#line 60 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub borrowed int* int_list__get(borrowed int_list_t *self, size_t index) {
            return index < self->length ? &self->items[index] : NULL;
        }

#line 64 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int int_list__set(borrowed mut int_list_t *self, size_t index, const int* value) {
            if (index >= self->length) return 1;
            self->items[index] = *value;
            return 0;
        }

#line 70 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub void int_list__clear(borrowed mut int_list_t *self) {
            self->length = 0;
        }

#line 74 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub size_t int_list__size(borrowed int_list_t *self) {
            return self->length;
        }

#line 78 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub size_t int_list__capacity(borrowed int_list_t *self) {
            return self->capacity;
        }

#line 82 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub int int_list__empty(borrowed int_list_t *self) {
            return self->length == 0;
        }

#line 86 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub borrowed int* int_list__data(borrowed int_list_t *self) {
            return self->items;
        }

#line 90 "/home/devlin/Development/c-plus/examples/generic_list.cp"
pub void int_list__destroy(borrowed mut int_list_t *self) {
            free(self->items);
            self->items = NULL;
            self->length = 0;
            self->capacity = 0;
        }
#line 102 "/home/devlin/Development/c-plus/examples/generic_list.cp"

#line 102 "/home/devlin/Development/c-plus/examples/generic_list.cp"


int main(void) {
    named_value_list_t records;
    named_value_list__init(&records);
    named_value_list__reserve(&records, 2);

    named_value_t first = {"language", "C-plus", 1};
    named_value_t second = {"phase", "comptime", 2};
    named_value_list__push(&records, &first);
    named_value_list__push(&records, &second);
    named_value_list__set(&records, 1, &first);

    named_value_t* record = named_value_list__get(&records, 0);
    printf("%s=%s (rank %hu, size %zu/%zu)\n",
           record->name,
           record->value,
           record->rank,
           named_value_list__size(&records),
           named_value_list__capacity(&records));
    named_value_list__pop(&records, NULL);
    named_value_list__clear(&records);
    named_value_list__empty(&records);
    named_value_list__data(&records);
    named_value_list__destroy(&records);

    string_list_t strings;
    string_list__init(&strings);
    cstring_t greeting = "hello";
    string_list__push(&strings, &greeting);
    cstring_t popped_string = NULL;
    string_list__pop(&strings, &popped_string);
    printf("%s\n", popped_string);
    string_list__destroy(&strings);

    int_list_t numbers;
    int_list__init(&numbers);
    int answer = 42;
    int_list__push(&numbers, &answer);
    printf("number=%d\n", *int_list__get(&numbers, 0));
    int_list__destroy(&numbers);
    return 0;
}
