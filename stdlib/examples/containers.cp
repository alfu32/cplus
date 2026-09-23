#include <stdio.h>
#include <string.h>

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
comptime typedef dynamic_list(int) int_list_t;
comptime typedef dynamic_map(cstring_t, int) score_map_t;

#define map_records_to_rank list_map__named_value_t__to__int
comptime list_mapper(named_value_t, int, named_value_list_t, int_list_t);

int string_keys_equal(const cstring_t* left, const cstring_t* right) {
    return *left != NULL && *right != NULL && strcmp(*left, *right) == 0;
}

int rank_with_index(borrowed named_value_t* item, size_t index) {
    return (int)item->rank + (int)index;
}

int main(void) {
    named_value_list_t records;
    int_list_t ranks;
    score_map_t scores;
    named_value_t record = {"language", "C-plus", 4};
    int score = 42;
    cstring_t key = "answer";

    if ((&records).init() || (&records).push(&record)) return 1;
    if ((&ranks).init() || map_records_to_rank(&records, &ranks, rank_with_index)) return 1;
    if ((&scores).init(string_keys_equal) || (&scores).put(&key, &score)) return 1;

    printf("%s=%s rank=%d mapped=%d score=%d\n",
           record.name, record.value, record.rank,
           *(&ranks).get(0), *(&scores).get(&key));

    (&scores).destroy();
    (&ranks).destroy();
    (&records).destroy();
    return 0;
}
