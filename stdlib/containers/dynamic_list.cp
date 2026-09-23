#include <stddef.h>
#include <stdlib.h>

comptime import "../memory/xmem.cp";

// Value-semantic resizable storage. Pointer members are never deep-freed.
@type @dynamic_list(@type T) {
    return struct {
        warm T* items;
        size_t length;
        size_t capacity;

        pub int init(borrowed mut *self) {
            self->items = NULL;
            self->length = 0;
            self->capacity = 0;
            return 0;
        }

        pub int reserve(borrowed mut *self, size_t requested) {
            if (requested < self->length) return 1;
            if (requested <= self->capacity) return 0;
            if (requested > ((size_t)-1) / sizeof(T)) return 1;
            T* resized = realloc_warm(self->items, requested * sizeof(T));
            if (resized == NULL) return 1;
            self->items = resized;
            self->capacity = requested;
            return 0;
        }

        pub int push(borrowed mut *self, borrowed const T* value) {
            if (value == NULL) return 1;
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
            free_warm(self->items);
            self->items = NULL;
            self->length = 0;
            self->capacity = 0;
        }
    };
}
