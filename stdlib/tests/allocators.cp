#include <stdint.h>
#include <string.h>

comptime import "../memory/xmem.cp";

@test "allocator context initializes idempotently" {
    CPLUS_TEST_ASSERT(xmem_init() == 0);
    CPLUS_TEST_ASSERT(xmem_init() == 0);
}

@test "scratch allocation alignment growth and reset" {
    scratch unsigned char* first = alloc_scratch(32);
    scratch unsigned char* aligned = alloc_scratch_aligned(257, 128);
    scratch unsigned char* large = alloc_scratch(1200 * 1024);
    CPLUS_TEST_ASSERT(first != NULL);
    CPLUS_TEST_ASSERT(aligned != NULL);
    CPLUS_TEST_ASSERT(large != NULL);
    CPLUS_TEST_ASSERT(alloc_scratch(0) == NULL);
    CPLUS_TEST_ASSERT(alloc_scratch(SIZE_MAX) == NULL);
    CPLUS_TEST_ASSERT(((uintptr_t)first % _Alignof(xmem_max_align_t)) == 0);
    CPLUS_TEST_ASSERT(((uintptr_t)aligned % 128) == 0);
    xmem_stats_t before_reset;
    CPLUS_TEST_ASSERT(xmem_get_stats(XMEM_CLASS_SCRATCH, &before_reset) == 0);
    CPLUS_TEST_ASSERT(before_reset.region_count >= 2);
    CPLUS_TEST_ASSERT(before_reset.used >= 1200 * 1024);

    reset_scratch();
    xmem_stats_t after_reset;
    CPLUS_TEST_ASSERT(xmem_get_stats(XMEM_CLASS_SCRATCH, &after_reset) == 0);
    CPLUS_TEST_ASSERT(after_reset.used == 0);
    CPLUS_TEST_ASSERT(after_reset.reserved == before_reset.reserved);
    CPLUS_TEST_ASSERT(after_reset.free_count >= before_reset.allocation_count);
    scratch unsigned char* reused_first = alloc_scratch(32);
    reset_scratch();
    scratch unsigned char* reused_again = alloc_scratch(32);
    CPLUS_TEST_ASSERT(reused_again == reused_first);
}

@test "warm allocate calloc realloc free and overflow" {
    warm int* values = calloc_warm(2, sizeof(int));
    CPLUS_TEST_ASSERT(values != NULL);
    CPLUS_TEST_ASSERT(values[0] == 0 && values[1] == 0);
    CPLUS_TEST_ASSERT(calloc_warm(SIZE_MAX, 2) == NULL);
    CPLUS_TEST_ASSERT(alloc_warm(0) == NULL);
    CPLUS_TEST_ASSERT(alloc_warm(SIZE_MAX) == NULL);
    values[0] = 17;
    values[1] = 23;

    warm int* grown = realloc_warm(values, 8 * sizeof(int));
    CPLUS_TEST_ASSERT(grown != NULL);
    CPLUS_TEST_ASSERT(grown[0] == 17 && grown[1] == 23);
    grown[2] = 31;
    warm int* shrunk = realloc_warm(grown, 3 * sizeof(int));
    CPLUS_TEST_ASSERT(shrunk != NULL);
    CPLUS_TEST_ASSERT(shrunk[0] == 17 && shrunk[1] == 23 && shrunk[2] == 31);
    CPLUS_TEST_ASSERT(realloc_warm(shrunk, 0) == NULL);
    warm int* from_null = realloc_warm(NULL, sizeof(int));
    CPLUS_TEST_ASSERT(from_null != NULL);
    free_warm(from_null);

    xmem_stats_t statistics;
    CPLUS_TEST_ASSERT(xmem_get_stats(XMEM_CLASS_WARM, &statistics) == 0);
    CPLUS_TEST_ASSERT(statistics.allocation_count >= 2);
    CPLUS_TEST_ASSERT(statistics.free_count >= 1);
    CPLUS_TEST_ASSERT(statistics.peak_used >= 8 * sizeof(int));
}

@test "warm regions preserve old pointers and reuse coalesced blocks" {
    warm unsigned char* first = alloc_warm(64);
    CPLUS_TEST_ASSERT(first != NULL);
    first[0] = 0x5a;
    warm unsigned char* large = alloc_warm(17 * 1024 * 1024);
    CPLUS_TEST_ASSERT(large != NULL);
    CPLUS_TEST_ASSERT(first[0] == 0x5a);
    free_warm(large);
    free_warm(first);

    warm unsigned char* a = alloc_warm(48);
    warm unsigned char* b = alloc_warm(48);
    warm unsigned char* c = alloc_warm(48);
    CPLUS_TEST_ASSERT(a != NULL && b != NULL && c != NULL);
    free_warm(a);
    free_warm(b);
    warm unsigned char* combined = alloc_warm(80);
    CPLUS_TEST_ASSERT(combined == a);
    free_warm(combined);
    free_warm(c);

    warm unsigned char* grow_in_place = alloc_warm(80);
    warm unsigned char* adjacent = alloc_warm(80);
    CPLUS_TEST_ASSERT(grow_in_place != NULL && adjacent != NULL);
    free_warm(adjacent);
    warm unsigned char* same_address = realloc_warm(grow_in_place, 160);
    CPLUS_TEST_ASSERT(same_address == grow_in_place);
    free_warm(same_address);
}

@test "warm realloc moves while preserving contents" {
    warm unsigned char* value = alloc_warm(64);
    warm unsigned char* blocker = alloc_warm(64);
    CPLUS_TEST_ASSERT(value != NULL && blocker != NULL);
    value[0] = 0xa5;
    value[63] = 0x5a;
    warm unsigned char* moved = realloc_warm(value, 17 * 1024 * 1024);
    CPLUS_TEST_ASSERT(moved != NULL);
    CPLUS_TEST_ASSERT(moved[0] == 0xa5 && moved[63] == 0x5a);
    free_warm(blocker);
    free_warm(moved);
}

@test "hot allocation alignment and reallocation" {
    hot unsigned char* value = alloc_hot_aligned(48, XMEM_CACHE_LINE_SIZE);
    CPLUS_TEST_ASSERT(value != NULL);
    CPLUS_TEST_ASSERT(((uintptr_t)value % XMEM_CACHE_LINE_SIZE) == 0);
    value[0] = 0x6b;
    hot unsigned char* grown = realloc_hot(value, 256);
    CPLUS_TEST_ASSERT(grown != NULL);
    CPLUS_TEST_ASSERT(((uintptr_t)grown % XMEM_CACHE_LINE_SIZE) == 0);
    CPLUS_TEST_ASSERT(grown[0] == 0x6b);
    free_hot(grown);
    CPLUS_TEST_ASSERT(alloc_hot_aligned(8, 3) == NULL);
    CPLUS_TEST_ASSERT(alloc_hot(0) == NULL);
    CPLUS_TEST_ASSERT(alloc_hot(SIZE_MAX) == NULL);

    hot unsigned char* zeroed = calloc_hot(4, 8);
    CPLUS_TEST_ASSERT(zeroed != NULL);
    CPLUS_TEST_ASSERT(zeroed[0] == 0 && zeroed[31] == 0);
    free_hot(zeroed);
}

@test "cold small blocks and direct mappings" {
    cold unsigned char* small = alloc_cold(128);
    CPLUS_TEST_ASSERT(small != NULL);
    small[0] = 0x7c;
    xmem_stats_t before_direct;
    CPLUS_TEST_ASSERT(xmem_get_stats(XMEM_CLASS_COLD, &before_direct) == 0);

    cold unsigned char* direct = alloc_cold(XMEM_COLD_DIRECT_MAP_THRESHOLD + 1);
    CPLUS_TEST_ASSERT(direct != NULL);
    direct[0] = 0x4d;
    xmem_stats_t during_direct;
    CPLUS_TEST_ASSERT(xmem_get_stats(XMEM_CLASS_COLD, &during_direct) == 0);
    CPLUS_TEST_ASSERT(during_direct.region_count == before_direct.region_count + 1);
    CPLUS_TEST_ASSERT(small[0] == 0x7c && direct[0] == 0x4d);
    free_cold(direct);
    xmem_stats_t after_direct;
    CPLUS_TEST_ASSERT(xmem_get_stats(XMEM_CLASS_COLD, &after_direct) == 0);
    CPLUS_TEST_ASSERT(after_direct.region_count == before_direct.region_count);
    free_cold(small);
    CPLUS_TEST_ASSERT(alloc_cold(0) == NULL);
    CPLUS_TEST_ASSERT(alloc_cold(SIZE_MAX) == NULL);
}

@test "destroy releases allocator regions and permits reinitialization" {
    warm void* value = alloc_warm(512);
    CPLUS_TEST_ASSERT(value != NULL);
    xmem_stats_t before_destroy;
    CPLUS_TEST_ASSERT(xmem_get_stats(XMEM_CLASS_WARM, &before_destroy) == 0);
    CPLUS_TEST_ASSERT(before_destroy.reserved > 0);
    xmem_destroy();
    CPLUS_TEST_ASSERT(xmem_get_stats(XMEM_CLASS_WARM, &before_destroy) != 0);
    CPLUS_TEST_ASSERT(xmem_init() == 0);
    warm void* after = alloc_warm(512);
    CPLUS_TEST_ASSERT(after != NULL);
    xmem_destroy();
}
