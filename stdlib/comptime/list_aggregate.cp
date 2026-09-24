// Generic list algorithms whose result types vary independently from T.
#include <stddef.h>
#include <string.h>

comptime import "stdlib:/memory/xmem.cp";

#ifndef CPLUS_COLLECTION_STORAGE_HELPERS
#define CPLUS_COLLECTION_STORAGE_HELPERS
static int cplus_collection_reserve(void** buffer, size_t* capacity, size_t required, size_t item_size) {
    if (buffer == NULL || capacity == NULL || item_size == 0 || required > ((size_t)-1) / item_size) return 1;
    if (required <= *capacity) return 0;
    size_t next = *capacity == 0 ? 4 : *capacity;
    while (next < required) {
        if (next > ((size_t)-1) / 2) { next = required; break; }
        next *= 2;
    }
    if (next > ((size_t)-1) / item_size) return 1;
    void* resized = realloc_warm(*buffer, next * item_size);
    if (resized == NULL) return 1;
    *buffer = resized;
    *capacity = next;
    return 0;
}

static int cplus_collection_append(void** buffer, size_t* length, size_t* capacity,
                                   size_t item_size, const void* item) {
    if (length == NULL || item == NULL || *length == (size_t)-1 ||
        cplus_collection_reserve(buffer, capacity, *length + 1, item_size) != 0) return 1;
    memcpy((unsigned char*)*buffer + *length * item_size, item, item_size);
    (*length)++;
    return 0;
}
#endif

comptime function @list_fold(type T, type A, type InputList) {
    return A list_fold__@name(T)__with__@name(A)(
        borrowed const InputList* input,
        A initial,
        A (*callback)(A accumulator, borrowed const T* item, size_t index)
    ) {
        if (input == NULL || !input->initialized || callback == NULL) return initial;
        A result = initial;
        for (size_t i = 0; i < input->length; i++) {
            result = callback(result, &input->items[i], i);
        }
        return result;
    };
}

comptime function @list_group_by(type T, type R, type InputList, type GroupList, type OutputMap) {
    return OutputMap list_group_by__@name(T)__by__@name(R)(
        borrowed const InputList* input,
        R (*key_callback)(borrowed const T* item)
    ) {
        OutputMap result = {0};
        result.initialized = 1;
        if (input == NULL || !input->initialized || key_callback == NULL) return (OutputMap){0};
        for (size_t i = 0; i < input->length; i++) {
            R key = key_callback(&input->items[i]);
            GroupList* group = NULL;
            for (size_t j = 0; j < result.length; j++) {
                if (memcmp(&result.entries[j].key, &key, sizeof(R)) == 0) {
                    group = &result.entries[j].value;
                    break;
                }
            }
            if (group == NULL) {
                GroupList first_group = {0};
                first_group.initialized = 1;
                if (cplus_collection_append((void**)&first_group.items, &first_group.length,
                                            &first_group.capacity, sizeof(T), &input->items[i]) != 0 ||
                    result.length == (size_t)-1 || cplus_collection_reserve((void**)&result.entries, &result.capacity,
                                             result.length + 1, sizeof(*result.entries)) != 0) {
                    free_warm(first_group.items);
                    for (size_t j = 0; j < result.length; j++) free_warm(result.entries[j].value.items);
                    free_warm(result.entries);
                    return (OutputMap){0};
                }
                result.entries[result.length].key = key;
                result.entries[result.length].value = first_group;
                result.length++;
            } else if (cplus_collection_append((void**)&group->items, &group->length,
                                               &group->capacity, sizeof(T), &input->items[i]) != 0) {
                for (size_t j = 0; j < result.length; j++) free_warm(result.entries[j].value.items);
                free_warm(result.entries);
                return (OutputMap){0};
            }
        }
        return result;
    };
}

comptime function @list_zip(type T, type K, type LeftList, type RightList, type OutputMap) {
    return OutputMap list_zip__@name(T)__with__@name(K)(
        borrowed const LeftList* left,
        borrowed const RightList* right
    ) {
        OutputMap result = {0};
        result.initialized = 1;
        if (left == NULL || right == NULL || !left->initialized || !right->initialized) return (OutputMap){0};
        size_t count = left->length < right->length ? left->length : right->length;
        for (size_t i = 0; i < count; i++) {
            size_t found = result.length;
            for (size_t j = 0; j < result.length; j++) {
                if (memcmp(&result.entries[j].key, &right->items[i], sizeof(K)) == 0) { found = j; break; }
            }
            if (found < result.length) {
                result.entries[found].value = left->items[i];
            } else {
                if (result.length == (size_t)-1 || cplus_collection_reserve((void**)&result.entries, &result.capacity,
                                             result.length + 1, sizeof(*result.entries)) != 0) {
                    free_warm(result.entries);
                    return (OutputMap){0};
                }
                result.entries[result.length].key = right->items[i];
                result.entries[result.length].value = left->items[i];
                result.length++;
            }
        }
        return result;
    };
}

comptime function @list_union(type T, type List) {
    return List list_union__@name(T)(borrowed const List* left, borrowed const List* right) {
        List result = {0};
        result.initialized = 1;
        if (left == NULL || right == NULL || !left->initialized || !right->initialized) return (List){0};
        for (size_t side = 0; side < 2; side++) {
            const List* source = side == 0 ? left : right;
            for (size_t i = 0; i < source->length; i++) {
                int found = 0;
                for (size_t j = 0; j < result.length; j++)
                    if (memcmp(&result.items[j], &source->items[i], sizeof(T)) == 0) { found = 1; break; }
                if (!found && cplus_collection_append((void**)&result.items, &result.length,
                                                       &result.capacity, sizeof(T), &source->items[i]) != 0) {
                    free_warm(result.items);
                    return (List){0};
                }
            }
        }
        return result;
    };
}

comptime function @list_intersect(type T, type List) {
    return List list_intersect__@name(T)(borrowed const List* left, borrowed const List* right) {
        List result = {0};
        result.initialized = 1;
        if (left == NULL || right == NULL || !left->initialized || !right->initialized) return (List){0};
        for (size_t i = 0; i < left->length; i++) {
            int in_right = 0, already_added = 0;
            for (size_t j = 0; j < right->length; j++)
                if (memcmp(&left->items[i], &right->items[j], sizeof(T)) == 0) { in_right = 1; break; }
            if (!in_right) continue;
            for (size_t j = 0; j < result.length; j++)
                if (memcmp(&left->items[i], &result.items[j], sizeof(T)) == 0) { already_added = 1; break; }
            if (!already_added && cplus_collection_append((void**)&result.items, &result.length,
                                                           &result.capacity, sizeof(T), &left->items[i]) != 0) {
                free_warm(result.items);
                return (List){0};
            }
        }
        return result;
    };
}

comptime function @list_subtract(type T, type List) {
    return List list_subtract__@name(T)(borrowed const List* left, borrowed const List* right) {
        List result = {0};
        result.initialized = 1;
        if (left == NULL || right == NULL || !left->initialized || !right->initialized) return (List){0};
        for (size_t i = 0; i < left->length; i++) {
            int in_right = 0, already_added = 0;
            for (size_t j = 0; j < right->length; j++)
                if (memcmp(&left->items[i], &right->items[j], sizeof(T)) == 0) { in_right = 1; break; }
            if (in_right) continue;
            for (size_t j = 0; j < result.length; j++)
                if (memcmp(&left->items[i], &result.items[j], sizeof(T)) == 0) { already_added = 1; break; }
            if (!already_added && cplus_collection_append((void**)&result.items, &result.length,
                                                           &result.capacity, sizeof(T), &left->items[i]) != 0) {
                free_warm(result.items);
                return (List){0};
            }
        }
        return result;
    };
}

comptime function @list_subtract_right(type T, type List) {
    return List list_subtract_right__@name(T)(borrowed const List* left, borrowed const List* right) {
        List result = {0};
        result.initialized = 1;
        if (left == NULL || right == NULL || !left->initialized || !right->initialized) return (List){0};
        for (size_t i = 0; i < right->length; i++) {
            int in_left = 0, already_added = 0;
            for (size_t j = 0; j < left->length; j++)
                if (memcmp(&right->items[i], &left->items[j], sizeof(T)) == 0) { in_left = 1; break; }
            if (in_left) continue;
            for (size_t j = 0; j < result.length; j++)
                if (memcmp(&right->items[i], &result.items[j], sizeof(T)) == 0) { already_added = 1; break; }
            if (!already_added && cplus_collection_append((void**)&result.items, &result.length,
                                                           &result.capacity, sizeof(T), &right->items[i]) != 0) {
                free_warm(result.items);
                return (List){0};
            }
        }
        return result;
    };
}
