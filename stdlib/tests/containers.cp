#include <stdio.h>
#include <string.h>
#include "fixtures/plain_c_struct.c"

comptime import "stdlib:/containers/dynamic_list.cp";
comptime import "stdlib:/containers/dynamic_map.cp";
comptime import "stdlib:/comptime/list_mapper.cp";
comptime import "stdlib:/comptime/list_aggregate.cp";

typedef char* cstring_t;

typedef struct named_value_t {
    char* name;
    char* value;
    unsigned short rank;
} named_value_t;

comptime typedef dynamic_list(named_value_t) named_value_list_t;
comptime typedef dynamic_list(cstring_t) string_list_t;
comptime typedef dynamic_list(int) int_list_t;
comptime typedef dynamic_map(cstring_t, int) score_map_t;
comptime typedef dynamic_map(int, int_list_t) int_groups_t;
comptime typedef dynamic_map(int, int) int_int_map_t;
comptime typedef dynamic_map(int, named_value_list_t) record_groups_t;
comptime typedef dynamic_map(int, named_value_t) record_zip_t;

#define map_records_to_rank list_map__named_value_t__to__int
#define map_ints_in_place list_map__int__to__int
comptime list_mapper(named_value_t, int, named_value_list_t, int_list_t);
comptime list_mapper(int, int, int_list_t, int_list_t);
comptime list_fold(int, int, int_list_t);
comptime list_fold(int, long, int_list_t);
comptime list_group_by(int, int, int_list_t, int_list_t, int_groups_t);
comptime list_zip(int, int, int_list_t, int_list_t, int_int_map_t);
comptime list_group_by(named_value_t, int, named_value_list_t, named_value_list_t, record_groups_t);
comptime list_zip(named_value_t, int, named_value_list_t, int_list_t, record_zip_t);
comptime list_union(int, int_list_t);
comptime list_intersect(int, int_list_t);
comptime list_subtract(int, int_list_t);
comptime list_subtract_right(int, int_list_t);

size_t visited_int_count = 0;

int count_int_visits(borrowed mut int* item, size_t index) {
    (void)item;
    (void)index;
    visited_int_count++;
    return 0;
}

int identity_int(borrowed int* item, size_t index) {
    (void)index;
    return *item;
}

int string_keys_equal(const cstring_t* left, const cstring_t* right) {
    if (*left == NULL || *right == NULL) return *left == *right;
    return strcmp(*left, *right) == 0;
}

int rank_with_index(borrowed named_value_t* item, size_t index) {
    return (int)item->rank + (int)index;
}

int numeric_int_key(borrowed const int* item) {
    return *item;
}

borrowed const char* record_name_key(borrowed const named_value_t* item) {
    return item->name;
}

int map_numeric_value(borrowed const cstring_t* key, borrowed const int* value) {
    (void)key;
    return *value;
}

borrowed const char* map_alpha_key(borrowed const cstring_t* key, borrowed const int* value) {
    (void)value;
    return *key;
}

int sum_item(int accumulator, borrowed const int* item, size_t index) {
    (void)index;
    return accumulator + *item;
}

long sum_long(long accumulator, borrowed const int* item, size_t index) {
    (void)index;
    return accumulator + *item;
}

int defer_order[3];
size_t defer_order_length;

void append_defer_order(int value) {
    defer_order[defer_order_length++] = value;
}

void run_defer_fixture(void) {
    defer append_defer_order(1);
    defer {
        append_defer_order(2);
        append_defer_order(3);
    }
}

int parity_key(borrowed const int* item) {
    return *item % 2;
}

int record_rank_parity(borrowed const named_value_t* item) {
    return item->rank % 2;
}

comptime int expected_answer = 42;

@test "defer statements run in reverse order" {
    defer_order_length = 0;
    run_defer_fixture();
    @assertEquals((size_t)3, defer_order_length);
    @assertEquals(2, defer_order[0]);
    @assertEquals(3, defer_order[1]);
    @assertEquals(1, defer_order[2]);
}

@test "runtime assertion expressions" {
    int answer = 40 + 2;
    @assert(answer == 42)
    @assertEquals(comptime expected_answer, answer)
}

@test "dynamic list operations" {
    int_list_t values;
    int_list_t* values_ptr = &values;
    int first = 17;
    int second = 23;
    @assert(values.init() == 0);
    @assert(values_ptr->reserve(8) == 0);
    @assert(values.capacity() >= 8);
    @assert(values.push(&first) == 0);
    @assert(values.push(&second) == 0);
    @assert(values.size() == 2);
    @assert(!values.empty());
    visited_int_count = 0;
    @assert(values.each(count_int_visits) == 0);
    @assert(visited_int_count == 2);
    @assert(*values.get(1) == 23);
    @assert(values.set(0, &second) == 0);
    @assert(*values.get(0) == 23);
    @assert(values.data() != NULL);
    @assert(values.pop(NULL) == 0);
    @assert(values.size() == 1);
    @assert(values.reserve(0) != 0);
    @assert(map_ints_in_place(&values, &values, identity_int) != 0);
    values.clear();
    @assert(values.empty());
    values.destroy();
    @assert(values.capacity() == 0);
}

@test "dynamic list stores pointer elements" {
    string_list_t values;
    cstring_t text = "borrowed string";
    @assert(values.init() == 0);
    @assert(values.push(&text) == 0);
    @assert(strcmp(*values.get(0), "borrowed string") == 0);
    values.destroy();
}

@test "generic map insert update remove" {
    score_map_t scores;
    cstring_t key = "compiler";
    int score = 7;
    @assert(scores.init(string_keys_equal) == 0);
    @assert(scores.reserve(8) == 0);
    @assert(scores.capacity() >= 8);
    @assert(scores.put(&key, &score) == 0);
    @assert(scores.contains(&key));
    score = 12;
    @assert(scores.put(&key, &score) == 0);
    @assert(scores.size() == 1);
    @assert(*scores.get(&key) == 12);
    @assert(scores.remove(&key) == 0);
    @assert(scores.empty());
    @assert(!scores.contains(&key));
    @assert(scores.put(&key, &score) == 0);
    scores.clear();
    @assert(scores.empty());
    scores.destroy();
    @assert(scores.capacity() == 0);
}

@test "generic mapper materializes a result list" {
    named_value_list_t records;
    int_list_t ranks;
    named_value_t first = {"parser", "C-plus", 3};
    named_value_t second = {"compiler", "C", 5};
    @assert(records.init() == 0);
    @assert(ranks.init() == 0);
    @assert(records.push(&first) == 0);
    @assert(records.push(&second) == 0);
    @assert(map_records_to_rank(&records, &ranks, rank_with_index) == 0);
    @assert(ranks.size() == 2);
    @assert(*ranks.get(0) == 3);
    @assert(*ranks.get(1) == 6);
    ranks.destroy();
    records.destroy();
}

@test "list callbacks sort numeric and string keys in place" {
    int_list_t numbers;
    int one = 1, five = 5, three = 3, two = 2;
    @assert(numbers.init() == 0);
    @assert(numbers.push(&one) == 0);
    @assert(numbers.push(&five) == 0);
    @assert(numbers.push(&three) == 0);
    @assert(numbers.push(&two) == 0);
    @assert(numbers.orderByNumeric(numeric_int_key) == 0);
    @assert(*numbers.get(0) == 1 && *numbers.get(3) == 5);
    named_value_list_t records;
    named_value_t zebra = {"zebra", "", 0}, apple = {"apple", "", 0}, pear = {"pear", "", 0};
    @assert(records.init() == 0);
    @assert(records.push(&zebra) == 0);
    @assert(records.push(&apple) == 0);
    @assert(records.push(&pear) == 0);
    @assert(records.orderByAlphaumeric(record_name_key) == 0);
    @assert(strcmp(records.get(0)->name, "apple") == 0);
    @assert(strcmp(records.get(2)->name, "zebra") == 0);
    records.destroy();
    numbers.destroy();
}

@test "map callbacks sort entries without moving key-value pairs apart" {
    score_map_t scores;
    cstring_t zebra = "zebra", apple = "apple", pear = "pear";
    int three = 3, one = 1, two = 2;
    @assert(scores.init(string_keys_equal) == 0);
    @assert(scores.put(&zebra, &three) == 0);
    @assert(scores.put(&apple, &one) == 0);
    @assert(scores.put(&pear, &two) == 0);
    @assert(scores.orderByNumeric(map_numeric_value) == 0);
    @assert(scores.entries[0].value == 1);
    @assert(scores.orderByAlphaumeric(map_alpha_key) == 0);
    @assert(strcmp(scores.entries[0].key, "apple") == 0);
    @assert(strcmp(scores.entries[2].key, "zebra") == 0);
    scores.destroy();

    int_int_map_t bytewise_scores;
    int bytewise_key = 27, bytewise_value = 91;
    @assert(bytewise_scores.init(NULL) == 0);
    @assert(bytewise_scores.put(&bytewise_key, &bytewise_value) == 0);
    @assert(*bytewise_scores.get(&bytewise_key) == 91);
    bytewise_scores.destroy();
}

@test "generic list fold group zip and set operations" {
    int_list_t left;
    int_list_t right;
    int values[] = {1, 2, 2, 3};
    int right_values[] = {2, 4, 4};
    @assert(left.init() == 0);
    @assert(right.init() == 0);
    for (size_t i = 0; i < 4; i++) @assert(left.push(&values[i]) == 0);
    for (size_t i = 0; i < 3; i++) @assert(right.push(&right_values[i]) == 0);
    @assert(list_fold__int__with__int(&left, 10, sum_item) == 18);
    @assert(list_fold__int__with__long(&left, 10L, sum_long) == 18L);

    int_groups_t groups = list_group_by__int__by__int(&left, parity_key);
    @assert(groups.initialized && groups.size() == 2);
    int even = 0, odd = 1;
    size_t even_count = 0, odd_count = 0;
    for (size_t i = 0; i < groups.length; i++) {
        if (groups.entries[i].key == even) even_count = groups.entries[i].value.length;
        if (groups.entries[i].key == odd) odd_count = groups.entries[i].value.length;
        int_list__destroy(&groups.entries[i].value);
    }
    @assert(even_count == 2 && odd_count == 2);
    groups.destroy();

    named_value_list_t records;
    named_value_t first_record = {"first", "one", 3};
    named_value_t second_record = {"second", "two", 5};
    named_value_t third_record = {"last", "three", 4};
    @assert(records.init() == 0);
    @assert(records.push(&first_record) == 0);
    @assert(records.push(&second_record) == 0);
    @assert(records.push(&third_record) == 0);
    record_groups_t record_groups = list_group_by__named_value_t__by__int(&records, record_rank_parity);
    @assert(record_groups.initialized && record_groups.length == 2);
    size_t grouped_records = 0;
    for (size_t i = 0; i < record_groups.length; i++) {
        grouped_records += record_groups.entries[i].value.length;
        named_value_list__destroy(&record_groups.entries[i].value);
    }
    @assert(grouped_records == 3);
    record_groups.destroy();

    int_list_t zip_keys;
    int zip_key_a = 2, zip_key_b = 4, zip_key_c = 4;
    @assert(zip_keys.init() == 0);
    @assert(zip_keys.push(&zip_key_a) == 0);
    @assert(zip_keys.push(&zip_key_b) == 0);
    @assert(zip_keys.push(&zip_key_c) == 0);
    record_zip_t zipped = list_zip__named_value_t__with__int(&records, &zip_keys);
    @assert(zipped.initialized && zipped.size() == 2);
    int key_two = 2;
    int key_four = 4;
    @assert(strcmp(zipped.get(&key_two)->name, "first") == 0);
    @assert(strcmp(zipped.get(&key_four)->name, "last") == 0);
    zipped.destroy();
    zip_keys.destroy();
    records.destroy();

    int_list_t united = list_union__int(&left, &right);
    int_list_t intersected = list_intersect__int(&left, &right);
    int_list_t subtracted = list_subtract__int(&left, &right);
    int_list_t subtract_right = list_subtract_right__int(&left, &right);
    @assert(united.initialized && united.size() == 4);
    @assert(intersected.initialized && intersected.size() == 1 && *intersected.get(0) == 2);
    @assert(subtracted.initialized && subtracted.size() == 2 && *subtracted.get(0) == 1 && *subtracted.get(1) == 3);
    @assert(subtract_right.initialized && subtract_right.size() == 1 && *subtract_right.get(0) == 4);
    united.destroy(); intersected.destroy(); subtracted.destroy(); subtract_right.destroy();
    left.destroy(); right.destroy();
}

@test "unchanged C source can be included" {
    plain_record_t value = (plain_record_t){1, 2, 3};
    char hash[32];
    plain_record__hash(&value, hash);
    @assert(strcmp(hash, "1:2:3") == 0);
}
