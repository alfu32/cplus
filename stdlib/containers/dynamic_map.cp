#include <stddef.h>
#include <stdlib.h>
#include <string.h>

// Generic contiguous key/value storage. Equality is supplied by the caller;
// entries and pointer-valued members are copied, never deep-owned.
@type @dynamic_map(@type K, @type V) {
    return struct {
        struct {
            K key;
            V value;
        } *entries;
        size_t length;
        size_t capacity;
        int (*keys_equal)(const K* left, const K* right);

        pub int init(borrowed mut *self, int (*keys_equal)(const K*, const K*)) {
            if (keys_equal == NULL) return 1;
            self->entries = NULL;
            self->length = 0;
            self->capacity = 0;
            self->keys_equal = keys_equal;
            return 0;
        }

        pub int reserve(borrowed mut *self, size_t requested) {
            if (requested < self->length) return 1;
            if (requested <= self->capacity) return 0;
            if (requested > ((size_t)-1) / sizeof(*self->entries)) return 1;
            void* resized = realloc(self->entries, requested * sizeof(*self->entries));
            if (resized == NULL) return 1;
            self->entries = resized;
            self->capacity = requested;
            return 0;
        }

        pub int put(borrowed mut *self, borrowed const K* key, borrowed const V* value) {
            if (key == NULL || value == NULL || self->keys_equal == NULL) return 1;
            for (size_t i = 0; i < self->length; i++) {
                if (self->keys_equal(&self->entries[i].key, key)) {
                    self->entries[i].value = *value;
                    return 0;
                }
            }
            if (self->length == self->capacity) {
                if (self->capacity > ((size_t)-1) / 2) return 1;
                size_t next_capacity = self->capacity == 0 ? 4 : self->capacity * 2;
                if (next_capacity > ((size_t)-1) / sizeof(*self->entries)) return 1;
                void* resized = realloc(self->entries, next_capacity * sizeof(*self->entries));
                if (resized == NULL) return 1;
                self->entries = resized;
                self->capacity = next_capacity;
            }
            self->entries[self->length].key = *key;
            self->entries[self->length].value = *value;
            self->length++;
            return 0;
        }

        pub borrowed V* get(borrowed *self, borrowed const K* key) {
            if (key == NULL || self->keys_equal == NULL) return NULL;
            for (size_t i = 0; i < self->length; i++) {
                if (self->keys_equal(&self->entries[i].key, key)) return &self->entries[i].value;
            }
            return NULL;
        }

        pub int contains(borrowed *self, borrowed const K* key) {
            if (key == NULL || self->keys_equal == NULL) return 0;
            for (size_t i = 0; i < self->length; i++) {
                if (self->keys_equal(&self->entries[i].key, key)) return 1;
            }
            return 0;
        }

        pub int remove(borrowed mut *self, borrowed const K* key) {
            if (key == NULL || self->keys_equal == NULL) return 1;
            for (size_t i = 0; i < self->length; i++) {
                if (self->keys_equal(&self->entries[i].key, key)) {
                    if (i + 1 < self->length) {
                        memmove(&self->entries[i], &self->entries[i + 1],
                                (self->length - i - 1) * sizeof(*self->entries));
                    }
                    self->length--;
                    return 0;
                }
            }
            return 1;
        }

        pub void clear(borrowed mut *self) {
            self->length = 0;
        }

        pub size_t size(borrowed *self) {
            return self->length;
        }

        pub size_t capacity(borrowed *self) {
            return self->capacity;
        }

        pub int empty(borrowed *self) {
            return self->length == 0;
        }

        pub void destroy(borrowed mut *self) {
            free(self->entries);
            self->entries = NULL;
            self->length = 0;
            self->capacity = 0;
            self->keys_equal = NULL;
        }
    };
}
