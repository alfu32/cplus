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
@type @dynamic_list(@type T) {
    return struct {
        T* items;
        size_t length;
        size_t capacity;

        pub int init(borrowed mut *self) {
            self->items = NULL;
            self->length = 0;
            self->capacity = 0;
            return 0;
        }

        pub int each(borrowed *self, int (*callback)(borrowed T* item, size_t index)) {
            if (callback == NULL) return 1;
            for (size_t i = 0; i < self->length; i++) {
                int result = callback(&self->items[i], i);
                if (result != 0) return result;
            }
            return 0;
        }
        pub int reserve(borrowed mut *self, size_t requested) {
            if (requested <= self->capacity) return 0;
            if (requested > ((size_t)-1) / sizeof(T)) return 1;
            T* resized = realloc(self->items, requested * sizeof(T));
            if (resized == NULL) return 1;
            self->items = resized;
            self->capacity = requested;
            return 0;
        }

        pub int push(borrowed mut *self, const T* value) {
            if (value == NULL) return 1;
            if (self->length == self->capacity) {
                if (self->capacity > ((size_t)-1) / 2) return 1;
                size_t next_capacity = self->capacity == 0 ? 4 : self->capacity * 2;
                if (next_capacity > ((size_t)-1) / sizeof(T)) return 1;
                T* resized = realloc(self->items, next_capacity * sizeof(T));
                if (resized == NULL) return 1;
                self->items = resized;
                self->capacity = next_capacity;
            }
            self->items[self->length++] = *value;
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

        pub int set(borrowed mut *self, size_t index, const T* value) {
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
            free(self->items);
            self->items = NULL;
            self->length = 0;
            self->capacity = 0;
        }
    };
}

// Each invocation materializes a distinct C-plus type and method family.
typedef @dynamic_list(named_value_t) named_value_list_t;
typedef @dynamic_list(cstring_t) string_list_t;
typedef @dynamic_list(int) int_list_t;

// Map an input list<T> into an initialized output list<R>. The list aliases
// are explicit because comptime materializes named typedefs for each list.
@fn @map(@type T, @type R, @type InputList, @type OutputList) {
    return @fn pub int list_map(
        borrowed @InputList* input,
        borrowed mut @OutputList* output,
        @R (*callback)(borrowed @T* item, size_t index)
    ) {
        if (input == NULL || output == NULL || callback == NULL) return 1;
        for (size_t i = 0; i < input->length; i++) {
            if (output->length == output->capacity) {
                if (output->capacity > ((size_t)-1) / 2) return 1;
                size_t next_capacity = output->capacity == 0 ? 4 : output->capacity * 2;
                if (next_capacity > ((size_t)-1) / sizeof(@R)) return 1;
                @R* resized = realloc(output->items, next_capacity * sizeof(@R));
                if (resized == NULL) return 1;
                output->items = resized;
                output->capacity = next_capacity;
            }
            output->items[output->length++] = callback(&input->items[i], i);
        }
        return 0;
    };
}

@map(named_value_t, int, named_value_list_t, int_list_t);

int visit_named_value(borrowed named_value_t* item, size_t index) {
    printf("[%zu] %s=%s (rank %hu)\n", index, item->name, item->value, item->rank);
    return 0;
}

int visit_string(borrowed cstring_t* item, size_t index) {
    printf("[%zu] %s\n", index, *item);
    return 0;
}

int visit_int(borrowed int* item, size_t index) {
    printf("[%zu] %d\n", index, *item);
    return 0;
}

int named_value_to_rank(borrowed named_value_t* item, size_t index) {
    return (int)item->rank + (int)index;
}

int main(void) {
    named_value_list_t records;
    if ((&records).init() != 0 || (&records).reserve(2) != 0) return 1;

    named_value_t first = {"language", "C-plus", 1};
    named_value_t second = {"phase", "comptime", 2};
    if ((&records).push(&first) != 0 || (&records).push(&second) != 0) return 1;
    if ((&records).set(1, &first) != 0) return 1;
    if ((&records).each(visit_named_value) != 0) return 1;

    int_list_t mapped_ranks;
    if ((&mapped_ranks).init() != 0) return 1;
    if (list_map(&records, &mapped_ranks, named_value_to_rank) != 0) return 1;
    if ((&mapped_ranks).size() != (&records).size()) return 1;
    printf("mapped rank=%d\n", *(&mapped_ranks).get(0));
    (&mapped_ranks).destroy();

    named_value_t* record = (&records).get(0);
    if (record == NULL || (&records).get(99) != NULL) return 1;
    printf("%s=%s (rank %hu, size %zu/%zu)\n",
           record->name,
           record->value,
           record->rank,
           (&records).size(),
           (&records).capacity());
    named_value_t removed_record;
    if ((&records).pop(&removed_record) != 0 || (&records).size() != 1) return 1;
    (&records).clear();
    if (!(&records).empty() || (&records).data() == NULL) return 1;
    (&records).destroy();
    if ((&records).size() != 0 || (&records).capacity() != 0) return 1;

    string_list_t strings;
    if ((&strings).init() != 0) return 1;
    cstring_t greeting = "hello";
    if ((&strings).push(&greeting) != 0 || (&strings).each(visit_string) != 0) return 1;
    cstring_t popped_string = NULL;
    if ((&strings).pop(&popped_string) != 0) return 1;
    printf("%s\n", popped_string);
    (&strings).destroy();

    int_list_t numbers;
    if ((&numbers).init() != 0) return 1;
    int answer = 42;
    if ((&numbers).push(&answer) != 0 || (&numbers).each(visit_int) != 0) return 1;
    printf("number=%d\n", *(&numbers).get(0));
    (&numbers).destroy();
    return 0;
}
