#include <stddef.h>
#include <stdlib.h>
#include <string.h>

comptime import "stdlib:/memory/xmem.cp";

// Generic contiguous key/value storage. Equality is supplied by the caller;
// entries and pointer-valued members are copied, never deep-owned.
@type @dynamic_map(@type K, @type V) {
    return struct {
        struct {
            K key;
            V value;
        } warm *entries;
        size_t length;
        size_t capacity;
        int (*keys_equal)(const K* left, const K* right);

        int initialized;

        pub int init(borrowed mut *self, int (*keys_equal)(const K*, const K*)) {
            if (self == NULL) return 1;
            self->entries = NULL;
            self->length = 0;
            self->capacity = 0;
            self->keys_equal = keys_equal;
            self->initialized = 1;
            return 0;
        }

        pub int reserve(borrowed mut *self, size_t requested) {
            if (self == NULL || !self->initialized) return 1;
            if (requested < self->length) return 1;
            if (requested <= self->capacity) return 0;
            if (requested > ((size_t)-1) / sizeof(*self->entries)) return 1;
            void* resized = realloc_warm(self->entries, requested * sizeof(*self->entries));
            if (resized == NULL) return 1;
            self->entries = resized;
            self->capacity = requested;
            return 0;
        }

        pub int put(borrowed mut *self, borrowed const K* key, borrowed const V* value) {
            if (self == NULL || !self->initialized || key == NULL || value == NULL) return 1;
            for (size_t i = 0; i < self->length; i++) {
                if (self->keys_equal != NULL
                    ? self->keys_equal(&self->entries[i].key, key)
                    : memcmp(&self->entries[i].key, key, sizeof(K)) == 0) {
                    self->entries[i].value = *value;
                    return 0;
                }
            }
            if (self->length == self->capacity) {
                if (self->capacity > ((size_t)-1) / 2) return 1;
                size_t next_capacity = self->capacity == 0 ? 4 : self->capacity * 2;
                if (next_capacity > ((size_t)-1) / sizeof(*self->entries)) return 1;
                void* resized = realloc_warm(self->entries, next_capacity * sizeof(*self->entries));
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
            if (self == NULL || !self->initialized || key == NULL) return NULL;
            for (size_t i = 0; i < self->length; i++) {
                if (self->keys_equal != NULL
                    ? self->keys_equal(&self->entries[i].key, key)
                    : memcmp(&self->entries[i].key, key, sizeof(K)) == 0) return &self->entries[i].value;
            }
            return NULL;
        }

        pub int contains(borrowed *self, borrowed const K* key) {
            if (self == NULL || !self->initialized || key == NULL) return 0;
            for (size_t i = 0; i < self->length; i++) {
                if (self->keys_equal != NULL
                    ? self->keys_equal(&self->entries[i].key, key)
                    : memcmp(&self->entries[i].key, key, sizeof(K)) == 0) return 1;
            }
            return 0;
        }

        pub int remove(borrowed mut *self, borrowed const K* key) {
            if (self == NULL || !self->initialized || key == NULL) return 1;
            for (size_t i = 0; i < self->length; i++) {
                if (self->keys_equal != NULL
                    ? self->keys_equal(&self->entries[i].key, key)
                    : memcmp(&self->entries[i].key, key, sizeof(K)) == 0) {
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

        pub int orderByNumeric(
            borrowed mut *self,
            int (*key)(borrowed const K* key, borrowed const V* value)
        ) {
            if (self == NULL || !self->initialized || key == NULL) return 1;
            for (size_t start = self->length / 2; start > 0;) {
                start--;
                size_t root = start;
                for (;;) {
                    if (root >= self->length / 2) break;
                    size_t child = root * 2 + 1;
                    if (child + 1 < self->length &&
                        key(&self->entries[child].key, &self->entries[child].value) <
                        key(&self->entries[child + 1].key, &self->entries[child + 1].value)) child++;
                    if (key(&self->entries[root].key, &self->entries[root].value) >=
                        key(&self->entries[child].key, &self->entries[child].value)) break;
                    K key_value = self->entries[root].key;
                    V mapped_value = self->entries[root].value;
                    self->entries[root].key = self->entries[child].key;
                    self->entries[root].value = self->entries[child].value;
                    self->entries[child].key = key_value;
                    self->entries[child].value = mapped_value;
                    root = child;
                }
            }
            for (size_t end = self->length; end > 1;) {
                end--;
                K key_value = self->entries[0].key;
                V mapped_value = self->entries[0].value;
                self->entries[0].key = self->entries[end].key;
                self->entries[0].value = self->entries[end].value;
                self->entries[end].key = key_value;
                self->entries[end].value = mapped_value;
                size_t root = 0;
                for (;;) {
                    if (root >= end / 2) break;
                    size_t child = root * 2 + 1;
                    if (child + 1 < end &&
                        key(&self->entries[child].key, &self->entries[child].value) <
                        key(&self->entries[child + 1].key, &self->entries[child + 1].value)) child++;
                    if (key(&self->entries[root].key, &self->entries[root].value) >=
                        key(&self->entries[child].key, &self->entries[child].value)) break;
                    K current_key = self->entries[root].key;
                    V current_value = self->entries[root].value;
                    self->entries[root].key = self->entries[child].key;
                    self->entries[root].value = self->entries[child].value;
                    self->entries[child].key = current_key;
                    self->entries[child].value = current_value;
                    root = child;
                }
            }
            return 0;
        }

        pub int orderByAlphaumeric(
            borrowed mut *self,
            borrowed const char* (*key)(borrowed const K* key, borrowed const V* value)
        ) {
            if (self == NULL || !self->initialized || key == NULL) return 1;
            for (size_t start = self->length / 2; start > 0;) {
                start--;
                size_t root = start;
                for (;;) {
                    if (root >= self->length / 2) break;
                    size_t child = root * 2 + 1;
                    borrowed const char* left = key(&self->entries[child].key, &self->entries[child].value);
                    borrowed const char* right = child + 1 < self->length ? key(&self->entries[child + 1].key, &self->entries[child + 1].value) : NULL;
                    if (child + 1 < self->length && (left == NULL || (right != NULL && strcmp(left, right) < 0))) child++;
                    left = key(&self->entries[root].key, &self->entries[root].value);
                    right = key(&self->entries[child].key, &self->entries[child].value);
                    if (left != NULL && (right == NULL || strcmp(left, right) >= 0)) break;
                    if (left == NULL && right == NULL) break;
                    K key_value = self->entries[root].key;
                    V mapped_value = self->entries[root].value;
                    self->entries[root].key = self->entries[child].key;
                    self->entries[root].value = self->entries[child].value;
                    self->entries[child].key = key_value;
                    self->entries[child].value = mapped_value;
                    root = child;
                }
            }
            for (size_t end = self->length; end > 1;) {
                end--;
                K key_value = self->entries[0].key;
                V mapped_value = self->entries[0].value;
                self->entries[0].key = self->entries[end].key;
                self->entries[0].value = self->entries[end].value;
                self->entries[end].key = key_value;
                self->entries[end].value = mapped_value;
                size_t root = 0;
                for (;;) {
                    if (root >= end / 2) break;
                    size_t child = root * 2 + 1;
                    borrowed const char* left = key(&self->entries[child].key, &self->entries[child].value);
                    borrowed const char* right = child + 1 < end ? key(&self->entries[child + 1].key, &self->entries[child + 1].value) : NULL;
                    if (child + 1 < end && (left == NULL || (right != NULL && strcmp(left, right) < 0))) child++;
                    left = key(&self->entries[root].key, &self->entries[root].value);
                    right = key(&self->entries[child].key, &self->entries[child].value);
                    if (left != NULL && (right == NULL || strcmp(left, right) >= 0)) break;
                    if (left == NULL && right == NULL) break;
                    K current_key = self->entries[root].key;
                    V current_value = self->entries[root].value;
                    self->entries[root].key = self->entries[child].key;
                    self->entries[root].value = self->entries[child].value;
                    self->entries[child].key = current_key;
                    self->entries[child].value = current_value;
                    root = child;
                }
            }
            return 0;
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
            if (self == NULL) return;
            free_warm(self->entries);
            self->entries = NULL;
            self->length = 0;
            self->capacity = 0;
            self->keys_equal = NULL;
            self->initialized = 0;
        }
    };
}
