#include <stddef.h>
#include <stdlib.h>
#include <string.h>

comptime import "stdlib:/memory/xmem.cp";

// Value-semantic resizable storage. Pointer members are never deep-freed.
@type @dynamic_list(@type T) {
    return struct {
        warm T* items;
        size_t length;
        size_t capacity;
        int initialized;

        pub int init(borrowed mut *self) {
            self->items = NULL;
            self->length = 0;
            self->capacity = 0;
            self->initialized = 1;
            return 0;
        }

        pub int reserve(borrowed mut *self, size_t requested) {
            if (self == NULL || !self->initialized || requested < self->length) return 1;
            if (requested <= self->capacity) return 0;
            if (requested > ((size_t)-1) / sizeof(T)) return 1;
            T* resized = realloc_warm(self->items, requested * sizeof(T));
            if (resized == NULL) return 1;
            self->items = resized;
            self->capacity = requested;
            return 0;
        }

        pub int push(borrowed mut *self, borrowed const T* value) {
            if (self == NULL || !self->initialized || value == NULL) return 1;
            if (self->length == self->capacity) {
                if (self->capacity > ((size_t)-1) / 2) return 1;
                size_t next_capacity = self->capacity == 0 ? 4 : self->capacity * 2;
                if (next_capacity > ((size_t)-1) / sizeof(T)) return 1;
                T* resized = realloc_warm(self->items, next_capacity * sizeof(T));
                if (resized == NULL) return 1;
                self->items = resized;
                self->capacity = next_capacity;
            }
            self->items[self->length++] = *value;
            return 0;
        }

        pub int each(borrowed mut *self, int (*callback)(borrowed mut T* item, size_t index)) {
            if (callback == NULL) return 1;
            for (size_t i = 0; i < self->length; i++) {
                int result = callback(&self->items[i], i);
                if (result != 0) return result;
            }
            return 0;
        }

        // In-place heapsort; callbacks are evaluated as comparisons are made.
        pub int orderByNumeric(borrowed mut *self, int (*key)(borrowed const T* item)) {
            if (self == NULL || !self->initialized || key == NULL) return 1;
            for (size_t start = self->length / 2; start > 0;) {
                start--;
                size_t root = start;
                for (;;) {
                    if (root >= self->length / 2) break;
                    size_t child = root * 2 + 1;
                    if (child + 1 < self->length && key(&self->items[child]) < key(&self->items[child + 1])) child++;
                    if (key(&self->items[root]) >= key(&self->items[child])) break;
                    T temporary = self->items[root];
                    self->items[root] = self->items[child];
                    self->items[child] = temporary;
                    root = child;
                }
            }
            for (size_t end = self->length; end > 1;) {
                end--;
                T temporary = self->items[0];
                self->items[0] = self->items[end];
                self->items[end] = temporary;
                size_t root = 0;
                for (;;) {
                    if (root >= end / 2) break;
                    size_t child = root * 2 + 1;
                    if (child + 1 < end && key(&self->items[child]) < key(&self->items[child + 1])) child++;
                    if (key(&self->items[root]) >= key(&self->items[child])) break;
                    T value = self->items[root];
                    self->items[root] = self->items[child];
                    self->items[child] = value;
                    root = child;
                }
            }
            return 0;
        }

        pub int orderByAlphaumeric(borrowed mut *self, borrowed const char* (*key)(borrowed const T* item)) {
            if (self == NULL || !self->initialized || key == NULL) return 1;
            for (size_t start = self->length / 2; start > 0;) {
                start--;
                size_t root = start;
                for (;;) {
                    if (root >= self->length / 2) break;
                    size_t child = root * 2 + 1;
                    borrowed const char* left = key(&self->items[child]);
                    borrowed const char* right = child + 1 < self->length ? key(&self->items[child + 1]) : NULL;
                    if (child + 1 < self->length && (left == NULL || (right != NULL && strcmp(left, right) < 0))) child++;
                    left = key(&self->items[root]);
                    right = key(&self->items[child]);
                    if (left != NULL && (right == NULL || strcmp(left, right) >= 0)) break;
                    if (left == NULL && right == NULL) break;
                    T temporary = self->items[root];
                    self->items[root] = self->items[child];
                    self->items[child] = temporary;
                    root = child;
                }
            }
            for (size_t end = self->length; end > 1;) {
                end--;
                T temporary = self->items[0];
                self->items[0] = self->items[end];
                self->items[end] = temporary;
                size_t root = 0;
                for (;;) {
                    if (root >= end / 2) break;
                    size_t child = root * 2 + 1;
                    borrowed const char* left = key(&self->items[child]);
                    borrowed const char* right = child + 1 < end ? key(&self->items[child + 1]) : NULL;
                    if (child + 1 < end && (left == NULL || (right != NULL && strcmp(left, right) < 0))) child++;
                    left = key(&self->items[root]);
                    right = key(&self->items[child]);
                    if (left != NULL && (right == NULL || strcmp(left, right) >= 0)) break;
                    if (left == NULL && right == NULL) break;
                    T value = self->items[root];
                    self->items[root] = self->items[child];
                    self->items[child] = value;
                    root = child;
                }
            }
            return 0;
        }

        pub int pop(borrowed mut *self, borrowed T* out) {
            if (self->length == 0) return 1;
            self->length--;
            if (out != NULL) *out = self->items[self->length];
            return 0;
        }

        pub borrowed T* get(borrowed *self, size_t index) {
            return index < self->length ? &self->items[index] : NULL;
        }

        pub int set(borrowed mut *self, size_t index, borrowed const T* value) {
            if (index >= self->length || value == NULL) return 1;
            self->items[index] = *value;
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

        pub borrowed T* data(borrowed *self) {
            return self->items;
        }

        pub void destroy(borrowed mut *self) {
            if (self == NULL) return;
            free_warm(self->items);
            self->items = NULL;
            self->length = 0;
            self->capacity = 0;
            self->initialized = 0;
        }
    };
}
