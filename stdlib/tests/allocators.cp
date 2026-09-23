#include <stdint.h>
#include <string.h>

comptime import "../memory/xmem.cp";

@test "allocator context initializes idempotently" {
    @assertEquals(0, xmem_init())
    @assertEquals(0, xmem_init())
}

@test "scratch allocation alignment growth and reset" {
    scratch unsigned char* first = alloc_scratch(32);
    scratch unsigned char* aligned = alloc_scratch_aligned(257, 128);
    scratch unsigned char* large = alloc_scratch(1200 * 1024);
    @assert(first != NULL)
    @assert(aligned != NULL)
    @assert(large != NULL)
    @assert(alloc_scratch(0) == NULL)
    @assert(alloc_scratch(SIZE_MAX) == NULL)
    @assert(((uintptr_t)first % _Alignof(xmem_max_align_t)) == 0)
    @assert(((uintptr_t)aligned % 128) == 0)
    xmem_stats_t before_reset;
    @assertEquals(0, xmem_get_stats(XMEM_CLASS_SCRATCH, &before_reset))
    @assert(before_reset.region_count >= 2)
    @assert(before_reset.used >= 1200 * 1024)

    reset_scratch();
    xmem_stats_t after_reset;
    @assertEquals(0, xmem_get_stats(XMEM_CLASS_SCRATCH, &after_reset))
    @assertEquals((size_t)0, after_reset.used)
    @assertEquals(before_reset.reserved, after_reset.reserved)
    @assert(after_reset.free_count >= before_reset.allocation_count)
    scratch unsigned char* reused_first = alloc_scratch(32);
    reset_scratch();
    scratch unsigned char* reused_again = alloc_scratch(32);
    @assertEquals(reused_first, reused_again)
}

@test "warm allocate calloc realloc free and overflow" {
    warm int* values = calloc_warm(2, sizeof(int));
    @assert(values != NULL)
    @assertEquals(0, values[0])
    @assertEquals(0, values[1])
    @assert(calloc_warm(SIZE_MAX, 2) == NULL)
    @assert(alloc_warm(0) == NULL)
    @assert(alloc_warm(SIZE_MAX) == NULL)
    values[0] = 17;
    values[1] = 23;

    warm int* grown = realloc_warm(values, 8 * sizeof(int));
    @assert(grown != NULL)
    @assertEquals(17, grown[0])
    @assertEquals(23, grown[1])
    grown[2] = 31;
    warm int* shrunk = realloc_warm(grown, 3 * sizeof(int));
    @assert(shrunk != NULL)
    @assertEquals(17, shrunk[0])
    @assertEquals(23, shrunk[1])
    @assertEquals(31, shrunk[2])
    @assert(realloc_warm(shrunk, 0) == NULL)
    warm int* from_null = realloc_warm(NULL, sizeof(int));
    @assert(from_null != NULL)
    free_warm(from_null);

    xmem_stats_t statistics;
    @assertEquals(0, xmem_get_stats(XMEM_CLASS_WARM, &statistics))
    @assert(statistics.allocation_count >= 2)
    @assert(statistics.free_count >= 1)
    @assert(statistics.peak_used >= 8 * sizeof(int))
}

@test "warm regions preserve old pointers and reuse coalesced blocks" {
    warm unsigned char* first = alloc_warm(64);
    @assert(first != NULL)
    first[0] = 0x5a;
    warm unsigned char* large = alloc_warm(17 * 1024 * 1024);
    @assert(large != NULL)
    @assertEquals((unsigned char)0x5a, first[0])
    free_warm(large);
    free_warm(first);

    warm unsigned char* a = alloc_warm(48);
    warm unsigned char* b = alloc_warm(48);
    warm unsigned char* c = alloc_warm(48);
    @assert(a != NULL && b != NULL && c != NULL)
    free_warm(a);
    free_warm(b);
    warm unsigned char* combined = alloc_warm(80);
    @assertEquals(a, combined)
    free_warm(combined);
    free_warm(c);

    warm unsigned char* grow_in_place = alloc_warm(80);
    warm unsigned char* adjacent = alloc_warm(80);
    @assert(grow_in_place != NULL && adjacent != NULL)
    free_warm(adjacent);
    warm unsigned char* same_address = realloc_warm(grow_in_place, 160);
    @assertEquals(grow_in_place, same_address)
    free_warm(same_address);
}

@test "warm realloc moves while preserving contents" {
    warm unsigned char* value = alloc_warm(64);
    warm unsigned char* blocker = alloc_warm(64);
    @assert(value != NULL && blocker != NULL)
    value[0] = 0xa5;
    value[63] = 0x5a;
    warm unsigned char* moved = realloc_warm(value, 17 * 1024 * 1024);
    @assert(moved != NULL)
    @assertEquals((unsigned char)0xa5, moved[0])
    @assertEquals((unsigned char)0x5a, moved[63])
    free_warm(blocker);
    free_warm(moved);
}

@test "hot allocation alignment and reallocation" {
    hot unsigned char* value = alloc_hot_aligned(48, XMEM_CACHE_LINE_SIZE);
    @assert(value != NULL)
    @assert(((uintptr_t)value % XMEM_CACHE_LINE_SIZE) == 0)
    value[0] = 0x6b;
    hot unsigned char* grown = realloc_hot(value, 256);
    @assert(grown != NULL)
    @assert(((uintptr_t)grown % XMEM_CACHE_LINE_SIZE) == 0)
    @assertEquals((unsigned char)0x6b, grown[0])
    free_hot(grown);
    @assert(alloc_hot_aligned(8, 3) == NULL)
    @assert(alloc_hot(0) == NULL)
    @assert(alloc_hot(SIZE_MAX) == NULL)

    hot unsigned char* zeroed = calloc_hot(4, 8);
    @assert(zeroed != NULL)
    @assertEquals((unsigned char)0, zeroed[0])
    @assertEquals((unsigned char)0, zeroed[31])
    free_hot(zeroed);
}

@test "cold small blocks and direct mappings" {
    cold unsigned char* small = alloc_cold(128);
    @assert(small != NULL)
    small[0] = 0x7c;
    xmem_stats_t before_direct;
    @assertEquals(0, xmem_get_stats(XMEM_CLASS_COLD, &before_direct))

    cold unsigned char* direct = alloc_cold(XMEM_COLD_DIRECT_MAP_THRESHOLD + 1);
    @assert(direct != NULL)
    direct[0] = 0x4d;
    xmem_stats_t during_direct;
    @assertEquals(0, xmem_get_stats(XMEM_CLASS_COLD, &during_direct))
    @assertEquals(before_direct.region_count + 1, during_direct.region_count)
    @assertEquals((unsigned char)0x7c, small[0])
    @assertEquals((unsigned char)0x4d, direct[0])
    free_cold(direct);
    xmem_stats_t after_direct;
    @assertEquals(0, xmem_get_stats(XMEM_CLASS_COLD, &after_direct))
    @assertEquals(before_direct.region_count, after_direct.region_count)
    free_cold(small);
    @assert(alloc_cold(0) == NULL)
    @assert(alloc_cold(SIZE_MAX) == NULL)
}

@test "destroy releases allocator regions and permits reinitialization" {
    warm void* value = alloc_warm(512);
    @assert(value != NULL)
    xmem_stats_t before_destroy;
    @assertEquals(0, xmem_get_stats(XMEM_CLASS_WARM, &before_destroy))
    @assert(before_destroy.reserved > 0)
    xmem_destroy();
    @assert(xmem_get_stats(XMEM_CLASS_WARM, &before_destroy) != 0)
    @assertEquals(0, xmem_init())
    warm void* after = alloc_warm(512);
    @assert(after != NULL)
    xmem_destroy();
}
