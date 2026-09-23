// Generic callback-driven conversion from a dynamic_list<T> to dynamic_list<R>.
// Output must be initialized; on allocation failure, prior output items remain.
#include <stddef.h>
#include <stdlib.h>

comptime import "stdlib:/memory/xmem.cp";

comptime string @name(type T) {
    return T.name;
}

comptime function @list_mapper(type T, type R, type InputList, type OutputList) {
    return int list_map__@name(T)__to__@name(R)(
        borrowed InputList* input,
        borrowed mut OutputList* output,
        R (*mapper_callback)(borrowed T* item, size_t index)
    ) {
        if (input == NULL || output == NULL || mapper_callback == NULL ||
            (const void*)input == (const void*)output) return 1;
        for (size_t i = 0; i < input->length; i++) {
            if (output->length == output->capacity) {
                if (output->capacity > ((size_t)-1) / 2) return 1;
                size_t next_capacity = output->capacity == 0 ? 4 : output->capacity * 2;
                if (next_capacity > ((size_t)-1) / sizeof(R)) return 1;
                R* resized = realloc_warm(output->items, next_capacity * sizeof(R));
                if (resized == NULL) return 1;
                output->items = resized;
                output->capacity = next_capacity;
            }
            output->items[output->length++] = mapper_callback(&input->items[i], i);
        }
        return 0;
    };
}
