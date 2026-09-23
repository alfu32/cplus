#include <stdio.h>
#include <string.h>
#include "fixtures/plain_c_struct.c"

comptime import "../containers/dynamic_list.cp";
comptime import "../containers/dynamic_map.cp";
comptime import "../comptime/list_mapper.cp";

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

#define map_records_to_rank list_map__named_value_t__to__int
#define map_ints_in_place list_map__int__to__int
comptime list_mapper(named_value_t, int, named_value_list_t, int_list_t);
comptime list_mapper(int, int, int_list_t, int_list_t);

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

comptime int expected_answer = 42;

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
    CPLUS_TEST_ASSERT(values.init() == 0);
    CPLUS_TEST_ASSERT(values_ptr->reserve(8) == 0);
    CPLUS_TEST_ASSERT(values.capacity() >= 8);
    CPLUS_TEST_ASSERT(values.push(&first) == 0);
    CPLUS_TEST_ASSERT(values.push(&second) == 0);
    CPLUS_TEST_ASSERT(values.size() == 2);
    CPLUS_TEST_ASSERT(!values.empty());
    visited_int_count = 0;
    CPLUS_TEST_ASSERT(values.each(count_int_visits) == 0);
    CPLUS_TEST_ASSERT(visited_int_count == 2);
    CPLUS_TEST_ASSERT(*values.get(1) == 23);
    CPLUS_TEST_ASSERT(values.set(0, &second) == 0);
    CPLUS_TEST_ASSERT(*values.get(0) == 23);
    CPLUS_TEST_ASSERT(values.data() != NULL);
    CPLUS_TEST_ASSERT(values.pop(NULL) == 0);
    CPLUS_TEST_ASSERT(values.size() == 1);
    CPLUS_TEST_ASSERT(values.reserve(0) != 0);
    CPLUS_TEST_ASSERT(map_ints_in_place(&values, &values, identity_int) != 0);
    values.clear();
    CPLUS_TEST_ASSERT(values.empty());
    values.destroy();
    CPLUS_TEST_ASSERT(values.capacity() == 0);
}

@test "dynamic list stores pointer elements" {
    string_list_t values;
    cstring_t text = "borrowed string";
    CPLUS_TEST_ASSERT(values.init() == 0);
    CPLUS_TEST_ASSERT(values.push(&text) == 0);
    CPLUS_TEST_ASSERT(strcmp(*values.get(0), "borrowed string") == 0);
    values.destroy();
}

@test "generic map insert update remove" {
    score_map_t scores;
    cstring_t key = "compiler";
    int score = 7;
    CPLUS_TEST_ASSERT(scores.init(string_keys_equal) == 0);
    CPLUS_TEST_ASSERT(scores.reserve(8) == 0);
    CPLUS_TEST_ASSERT(scores.capacity() >= 8);
    CPLUS_TEST_ASSERT(scores.put(&key, &score) == 0);
    CPLUS_TEST_ASSERT(scores.contains(&key));
    score = 12;
    CPLUS_TEST_ASSERT(scores.put(&key, &score) == 0);
    CPLUS_TEST_ASSERT(scores.size() == 1);
    CPLUS_TEST_ASSERT(*scores.get(&key) == 12);
    CPLUS_TEST_ASSERT(scores.remove(&key) == 0);
    CPLUS_TEST_ASSERT(scores.empty());
    CPLUS_TEST_ASSERT(!scores.contains(&key));
    CPLUS_TEST_ASSERT(scores.put(&key, &score) == 0);
    scores.clear();
    CPLUS_TEST_ASSERT(scores.empty());
    scores.destroy();
    CPLUS_TEST_ASSERT(scores.capacity() == 0);
}

@test "generic mapper materializes a result list" {
    named_value_list_t records;
    int_list_t ranks;
    named_value_t first = {"parser", "C-plus", 3};
    named_value_t second = {"compiler", "C", 5};
    CPLUS_TEST_ASSERT(records.init() == 0);
    CPLUS_TEST_ASSERT(ranks.init() == 0);
    CPLUS_TEST_ASSERT(records.push(&first) == 0);
    CPLUS_TEST_ASSERT(records.push(&second) == 0);
    CPLUS_TEST_ASSERT(map_records_to_rank(&records, &ranks, rank_with_index) == 0);
    CPLUS_TEST_ASSERT(ranks.size() == 2);
    CPLUS_TEST_ASSERT(*ranks.get(0) == 3);
    CPLUS_TEST_ASSERT(*ranks.get(1) == 6);
    ranks.destroy();
    records.destroy();
}

@test "unchanged C source can be included" {
    plain_record_t value = (plain_record_t){1, 2, 3};
    char hash[32];
    plain_record__hash(&value, hash);
    CPLUS_TEST_ASSERT(strcmp(hash, "1:2:3") == 0);
}
