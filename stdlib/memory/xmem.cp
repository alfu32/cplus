#ifndef CPLUS_XMEM_CP
#define CPLUS_XMEM_CP

#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <limits.h>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#else
#include <sys/mman.h>
#include <unistd.h>
#endif

#ifndef scratch
#define scratch
#endif
#ifndef hot
#define hot
#endif
#ifndef warm
#define warm
#endif
#ifndef cold
#define cold
#endif

#ifndef XMEM_SCRATCH_INITIAL_SIZE
#define XMEM_SCRATCH_INITIAL_SIZE (1u * 1024u * 1024u)
#endif
#ifndef XMEM_HOT_INITIAL_SIZE
#define XMEM_HOT_INITIAL_SIZE (4u * 1024u * 1024u)
#endif
#ifndef XMEM_WARM_INITIAL_SIZE
#define XMEM_WARM_INITIAL_SIZE (16u * 1024u * 1024u)
#endif
#ifndef XMEM_COLD_INITIAL_SIZE
#define XMEM_COLD_INITIAL_SIZE (16u * 1024u * 1024u)
#endif
#ifndef XMEM_CACHE_LINE_SIZE
#define XMEM_CACHE_LINE_SIZE 64u
#endif
#ifndef XMEM_HOT_CACHE_ALIGN_THRESHOLD
#define XMEM_HOT_CACHE_ALIGN_THRESHOLD 32u
#endif
#ifndef XMEM_COLD_DIRECT_MAP_THRESHOLD
#define XMEM_COLD_DIRECT_MAP_THRESHOLD (1u * 1024u * 1024u)
#endif
#ifndef XMEM_MAX_REGION_SIZE
#define XMEM_MAX_REGION_SIZE (64u * 1024u * 1024u)
#endif

/* TinyCC's bundled C headers do not consistently expose C11 max_align_t. */
typedef union xmem_max_align_t {
    long double floating;
    long long integer;
    void* pointer;
} xmem_max_align_t;

typedef enum xmem_class_t {
    XMEM_CLASS_SCRATCH = 0,
    XMEM_CLASS_HOT = 1,
    XMEM_CLASS_WARM = 2,
    XMEM_CLASS_COLD = 3
} xmem_class_t;

typedef struct xmem_stats_t {
    size_t reserved;
    size_t used;
    size_t peak_used;
    size_t allocation_count;
    size_t free_count;
    size_t region_count;
} xmem_stats_t;

typedef struct xmem_arena_t xmem_arena_t;
typedef struct xmem_region_t xmem_region_t;
typedef struct xmem_block_t xmem_block_t;
typedef struct xmem_t xmem_t;

struct xmem_region_t {
    xmem_region_t* next;
    size_t mapping_size;
    size_t scratch_capacity;
    size_t scratch_used;
    unsigned int direct;
    unsigned char* scratch_start;
    xmem_block_t* first_block;
};

struct xmem_block_t {
    size_t span;
    size_t requested;
    size_t user_offset;
    size_t alignment;
    uint32_t magic;
    xmem_region_t* region;
    xmem_block_t* previous;
    xmem_block_t* next;
    xmem_block_t* free_previous;
    xmem_block_t* free_next;
    unsigned int owner;
    unsigned int is_free;
};

static int xmem__arena_initialize(xmem_arena_t* arena, xmem_class_t kind,
                                  size_t initial_region_size, size_t direct_threshold);
static void xmem__arena_destroy(xmem_arena_t* arena);
static void* xmem__arena_allocate(xmem_arena_t* arena, size_t size, size_t alignment);
static void* xmem__arena_callocate(xmem_arena_t* arena, size_t count, size_t size, size_t alignment);
static void* xmem__arena_reallocate(xmem_arena_t* arena, void* pointer, size_t size);
static void xmem__arena_release(xmem_arena_t* arena, void* pointer);
static void xmem__arena_reset(xmem_arena_t* arena);
static void xmem__arena_statistics(xmem_arena_t* arena, xmem_stats_t* out);

typedef struct xmem_arena_t {
    xmem_class_t kind;
    xmem_region_t* regions;
    xmem_region_t* scratch_current;
    xmem_block_t* free_blocks;
    size_t initial_region_size;
    size_t next_region_size;
    size_t direct_threshold;
    size_t reserved;
    size_t used;
    size_t peak_used;
    size_t allocation_count;
    size_t free_count;
    size_t scratch_outstanding;
    size_t region_count;
    int initialized;

    pub int init(borrowed mut *self, xmem_class_t kind,
                 size_t initial_region_size, size_t direct_threshold) {
        return xmem__arena_initialize(self, kind, initial_region_size, direct_threshold);
    }

    pub owned void* alloc(borrowed mut *self, size_t size, size_t alignment) {
        return xmem__arena_allocate(self, size, alignment);
    }

    pub owned void* calloc(borrowed mut *self, size_t count, size_t size, size_t alignment) {
        return xmem__arena_callocate(self, count, size, alignment);
    }

    pub owned void* realloc(borrowed mut *self, borrowed void* pointer, size_t size) {
        return xmem__arena_reallocate(self, pointer, size);
    }

    pub void free(borrowed mut *self, borrowed void* pointer) {
        xmem__arena_release(self, pointer);
    }

    pub void reset(borrowed mut *self) {
        xmem__arena_reset(self);
    }

    pub void stats(borrowed *self, owned xmem_stats_t* out) {
        xmem__arena_statistics(self, out);
    }

    pub void destroy(borrowed mut *self) {
        xmem__arena_destroy(self);
    }
} xmem_arena_t;

typedef struct xmem_t {
    xmem_arena_t scratch_arena;
    xmem_arena_t hot_arena;
    xmem_arena_t warm_arena;
    xmem_arena_t cold_arena;
    int initialized;

    pub int init(borrowed mut *self) {
        if (self->initialized) return 0;
        memset(self, 0, sizeof(*self));
        if (xmem_arena__init(&self->scratch_arena, XMEM_CLASS_SCRATCH,
                             XMEM_SCRATCH_INITIAL_SIZE, 0) != 0) return 1;
        if (xmem_arena__init(&self->hot_arena, XMEM_CLASS_HOT,
                             XMEM_HOT_INITIAL_SIZE, 0) != 0) return 1;
        if (xmem_arena__init(&self->warm_arena, XMEM_CLASS_WARM,
                             XMEM_WARM_INITIAL_SIZE, 0) != 0) return 1;
        if (xmem_arena__init(&self->cold_arena, XMEM_CLASS_COLD,
                             XMEM_COLD_INITIAL_SIZE, XMEM_COLD_DIRECT_MAP_THRESHOLD) != 0) return 1;
        self->initialized = 1;
        return 0;
    }

    pub void destroy(borrowed mut *self) {
        if (!self->initialized) return;
        xmem_arena__destroy(&self->scratch_arena);
        xmem_arena__destroy(&self->hot_arena);
        xmem_arena__destroy(&self->warm_arena);
        xmem_arena__destroy(&self->cold_arena);
        memset(self, 0, sizeof(*self));
    }
} xmem_t;

priv static xmem_t xmem;

static int xmem__checked_add(size_t left, size_t right, size_t* out) {
    if (right > SIZE_MAX - left) return 0;
    *out = left + right;
    return 1;
}

static int xmem__is_power_of_two(size_t value) {
    return value != 0 && (value & (value - 1)) == 0;
}

static size_t xmem__normalize_alignment(size_t alignment) {
    size_t minimum = _Alignof(xmem_max_align_t);
    if (!xmem__is_power_of_two(alignment)) return 0;
    return alignment < minimum ? minimum : alignment;
}

static unsigned char* xmem__align_pointer(unsigned char* pointer, size_t alignment) {
    uintptr_t value = (uintptr_t)pointer;
    uintptr_t mask = (uintptr_t)alignment - 1;
    if (value > UINTPTR_MAX - mask) return NULL;
    return (unsigned char*)((value + mask) & ~mask);
}

static size_t xmem__page_size(void) {
#if defined(_WIN32)
    SYSTEM_INFO information;
    GetSystemInfo(&information);
    return information.dwPageSize == 0 ? 4096u : (size_t)information.dwPageSize;
#else
    long value = sysconf(_SC_PAGESIZE);
    return value <= 0 ? 4096u : (size_t)value;
#endif
}

static void* xmem__map(size_t requested, size_t* mapped_size) {
    size_t page = xmem__page_size();
    size_t rounded;
    if (!xmem__checked_add(requested, page - 1, &rounded)) return NULL;
    rounded = (rounded / page) * page;
#if defined(_WIN32)
    void* memory = VirtualAlloc(NULL, rounded, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
    if (memory == NULL) return NULL;
#else
    int flags = MAP_PRIVATE;
#if defined(MAP_ANONYMOUS)
    flags |= MAP_ANONYMOUS;
#elif defined(MAP_ANON)
    flags |= MAP_ANON;
#else
    return NULL;
#endif
    void* memory = mmap(NULL, rounded, PROT_READ | PROT_WRITE, flags, -1, 0);
    if (memory == MAP_FAILED) return NULL;
#endif
    *mapped_size = rounded;
    return memory;
}

static void xmem__unmap(void* memory, size_t mapped_size) {
    if (memory == NULL) return;
#if defined(_WIN32)
    (void)mapped_size;
    VirtualFree(memory, 0, MEM_RELEASE);
#else
    munmap(memory, mapped_size);
#endif
}

static void xmem__free_list_insert(xmem_arena_t* arena, xmem_block_t* block) {
    block->free_previous = NULL;
    block->free_next = arena->free_blocks;
    if (arena->free_blocks != NULL) arena->free_blocks->free_previous = block;
    arena->free_blocks = block;
    block->is_free = 1;
    block->magic = 0x584d4652u;
}

static void xmem__free_list_remove(xmem_arena_t* arena, xmem_block_t* block) {
    if (block->free_previous != NULL) block->free_previous->free_next = block->free_next;
    else if (arena->free_blocks == block) arena->free_blocks = block->free_next;
    if (block->free_next != NULL) block->free_next->free_previous = block->free_previous;
    block->free_previous = NULL;
    block->free_next = NULL;
    block->is_free = 0;
    block->magic = 0;
}

static void xmem__unlink_region(xmem_arena_t* arena, xmem_region_t* region) {
    xmem_region_t** cursor = &arena->regions;
    while (*cursor != NULL && *cursor != region) cursor = &(*cursor)->next;
    if (*cursor == region) *cursor = region->next;
    if (arena->scratch_current == region) arena->scratch_current = region->next;
    arena->reserved -= region->mapping_size;
    arena->region_count--;
    xmem__unmap(region, region->mapping_size);
}

static xmem_region_t* xmem__region_create(xmem_arena_t* arena, size_t payload_size,
                                          size_t alignment, int direct) {
    size_t required = sizeof(xmem_region_t);
    if (!xmem__checked_add(required, _Alignof(xmem_block_t) - 1, &required) ||
        !xmem__checked_add(required, sizeof(xmem_block_t), &required) ||
        !xmem__checked_add(required, sizeof(xmem_block_t*), &required) ||
        !xmem__checked_add(required, alignment - 1, &required) ||
        !xmem__checked_add(required, payload_size, &required)) return NULL;

    size_t request = required;
    if (!direct && request < arena->next_region_size) request = arena->next_region_size;
    size_t actual_size = 0;
    xmem_region_t* region = xmem__map(request, &actual_size);
    if (region == NULL) return NULL;
    if (actual_size > SIZE_MAX - arena->reserved) {
        xmem__unmap(region, actual_size);
        return NULL;
    }

    memset(region, 0, sizeof(*region));
    region->mapping_size = actual_size;
    region->direct = direct != 0;
    region->next = arena->regions;
    arena->regions = region;
    arena->reserved += actual_size;
    arena->region_count++;

    unsigned char* start = xmem__align_pointer(
        (unsigned char*)region + sizeof(*region), _Alignof(xmem_block_t));
    unsigned char* end = (unsigned char*)region + actual_size;
    if (start == NULL || (size_t)(end - start) < sizeof(xmem_block_t) + sizeof(xmem_block_t*) + alignment) {
        xmem__unlink_region(arena, region);
        return NULL;
    }

    xmem_block_t* block = (xmem_block_t*)start;
    memset(block, 0, sizeof(*block));
    block->span = (size_t)(end - start);
    block->region = region;
    block->owner = (unsigned int)arena->kind;
    region->first_block = block;
    if (!direct) xmem__free_list_insert(arena, block);

    if (!direct) {
        size_t growth_base = actual_size;
        if (growth_base < arena->initial_region_size) growth_base = arena->initial_region_size;
        arena->next_region_size = growth_base >= XMEM_MAX_REGION_SIZE / 2
            ? XMEM_MAX_REGION_SIZE
            : growth_base * 2;
    }
    return region;
}

static void* xmem__block_pointer(xmem_block_t* block) {
    return (unsigned char*)block + block->user_offset;
}

#ifdef XMEM_DEBUG
#define XMEM_DEBUG_GUARD_SIZE 8u
#define XMEM_DEBUG_GUARD_BYTE 0xa7
#define XMEM_DEBUG_USED_MAGIC 0x584d5553u

static void xmem__write_guard(xmem_block_t* block) {
    memset((unsigned char*)xmem__block_pointer(block) + block->requested,
           XMEM_DEBUG_GUARD_BYTE, XMEM_DEBUG_GUARD_SIZE);
}

static int xmem__guard_is_valid(xmem_block_t* block) {
    if (block->user_offset > block->span ||
        block->requested > block->span - block->user_offset ||
        XMEM_DEBUG_GUARD_SIZE > block->span - block->user_offset - block->requested) return 0;
    unsigned char* guard = (unsigned char*)xmem__block_pointer(block) + block->requested;
    for (size_t i = 0; i < XMEM_DEBUG_GUARD_SIZE; i++) {
        if (guard[i] != XMEM_DEBUG_GUARD_BYTE) return 0;
    }
    return 1;
}
#endif

static int xmem__block_requirements(xmem_block_t* block, size_t size, size_t alignment,
                                    size_t* user_offset, size_t* required_span) {
    unsigned char* candidate = (unsigned char*)block + sizeof(*block) + sizeof(xmem_block_t*);
    unsigned char* user = xmem__align_pointer(candidate, alignment);
    if (user == NULL) return 0;
    size_t offset = (size_t)(user - (unsigned char*)block);
    size_t total;
    size_t guarded_size = size;
#ifdef XMEM_DEBUG
    if (!xmem__checked_add(guarded_size, XMEM_DEBUG_GUARD_SIZE, &guarded_size)) return 0;
#endif
    if (!xmem__checked_add(offset, guarded_size, &total) ||
        !xmem__checked_add(total, _Alignof(xmem_block_t) - 1, &total)) return 0;
    total = (total / _Alignof(xmem_block_t)) * _Alignof(xmem_block_t);
    *user_offset = offset;
    *required_span = total;
    return 1;
}

static void* xmem__activate_block(xmem_arena_t* arena, xmem_block_t* block,
                                  size_t size, size_t alignment, int from_free_list) {
    if (size > SIZE_MAX - arena->used) return NULL;
    size_t offset;
    size_t required;
    if (!xmem__block_requirements(block, size, alignment, &offset, &required) ||
        required > block->span) return NULL;

    if (from_free_list) xmem__free_list_remove(arena, block);
    size_t previous_span = block->span;
    size_t remainder = previous_span - required;
    size_t minimum_free_span = sizeof(xmem_block_t) + sizeof(xmem_block_t*) + _Alignof(xmem_max_align_t) + 1;
    if (from_free_list && remainder >= minimum_free_span) {
        xmem_block_t* next = block->next;
        xmem_block_t* split = (xmem_block_t*)((unsigned char*)block + required);
        memset(split, 0, sizeof(*split));
        split->span = remainder;
        split->region = block->region;
        split->owner = (unsigned int)arena->kind;
        split->previous = block;
        split->next = next;
        if (next != NULL) next->previous = split;
        block->next = split;
        block->span = required;
        xmem__free_list_insert(arena, split);
    }

    block->is_free = 0;
    block->requested = size;
    block->user_offset = offset;
    block->alignment = alignment;
    block->owner = (unsigned int)arena->kind;
#ifdef XMEM_DEBUG
    block->magic = XMEM_DEBUG_USED_MAGIC;
#endif
    void* result = xmem__block_pointer(block);
    *((xmem_block_t**)((unsigned char*)result - sizeof(xmem_block_t*))) = block;
#ifdef XMEM_DEBUG
    xmem__write_guard(block);
#endif

    arena->used += size;
    if (arena->used > arena->peak_used) arena->peak_used = arena->used;
    arena->allocation_count++;
    return result;
}

static xmem_block_t* xmem__find_block(xmem_arena_t* arena, void* pointer) {
    for (xmem_region_t* region = arena->regions; region != NULL; region = region->next) {
        for (xmem_block_t* block = region->first_block; block != NULL; block = block->next) {
#ifdef XMEM_DEBUG
            uintptr_t region_start = (uintptr_t)region;
            uintptr_t block_address = (uintptr_t)block;
            if (block_address < region_start ||
                block_address - region_start > region->mapping_size - sizeof(*block)) {
                fprintf(stderr, "xmem: corrupted block link in region %p\n", (void*)region);
                return NULL;
            }
            if (block->magic != 0x584d4652u && block->magic != XMEM_DEBUG_USED_MAGIC) {
                fprintf(stderr, "xmem: corrupted block header at %p\n", (void*)block);
                return NULL;
            }
            size_t block_offset = (size_t)(block_address - region_start);
            if (block->span > region->mapping_size - block_offset ||
                (block->is_free && block->magic != 0x584d4652u) ||
                (!block->is_free && block->magic != XMEM_DEBUG_USED_MAGIC) ||
                (!block->is_free &&
                 (block->user_offset > block->span ||
                  block->requested > block->span - block->user_offset ||
                  XMEM_DEBUG_GUARD_SIZE > block->span - block->user_offset - block->requested))) {
                fprintf(stderr, "xmem: corrupted block header at %p\n", (void*)block);
                return NULL;
            }
#endif
            if (!block->is_free && xmem__block_pointer(block) == pointer) return block;
        }
    }
    return NULL;
}

static xmem_block_t* xmem__pointer_block(xmem_arena_t* arena, void* pointer) {
#ifdef XMEM_DEBUG
    return xmem__find_block(arena, pointer);
#else
    (void)arena;
    return *((xmem_block_t**)((unsigned char*)pointer - sizeof(xmem_block_t*)));
#endif
}

static int xmem__arena_initialize(xmem_arena_t* arena, xmem_class_t kind,
                                  size_t initial_region_size, size_t direct_threshold) {
    if (arena == NULL || kind < XMEM_CLASS_SCRATCH || kind > XMEM_CLASS_COLD) return 1;
    memset(arena, 0, sizeof(*arena));
    arena->kind = kind;
    arena->initial_region_size = initial_region_size == 0 ? 4096u : initial_region_size;
    arena->next_region_size = arena->initial_region_size;
    arena->direct_threshold = direct_threshold;
    arena->initialized = 1;
    return 0;
}

static xmem_region_t* xmem__scratch_region_create(xmem_arena_t* arena, size_t size,
                                                  size_t alignment) {
    size_t request = sizeof(xmem_region_t);
    if (!xmem__checked_add(request, _Alignof(xmem_max_align_t) - 1, &request) ||
        !xmem__checked_add(request, size, &request)) return NULL;
    if (request < arena->next_region_size) request = arena->next_region_size;
    size_t mapped_size = 0;
    xmem_region_t* region = xmem__map(request, &mapped_size);
    if (region == NULL || mapped_size > SIZE_MAX - arena->reserved) {
        if (region != NULL) xmem__unmap(region, mapped_size);
        return NULL;
    }
    memset(region, 0, sizeof(*region));
    region->mapping_size = mapped_size;
    region->next = arena->regions;
    arena->regions = region;
    region->scratch_start = xmem__align_pointer(
        (unsigned char*)region + sizeof(*region), alignment);
    if (region->scratch_start == NULL) {
        arena->regions = region->next;
        xmem__unmap(region, mapped_size);
        return NULL;
    }
    region->scratch_capacity = mapped_size - (size_t)(region->scratch_start - (unsigned char*)region);
    arena->scratch_current = region;
    arena->reserved += mapped_size;
    arena->region_count++;

    size_t growth_base = mapped_size;
    if (growth_base < arena->initial_region_size) growth_base = arena->initial_region_size;
    arena->next_region_size = growth_base >= XMEM_MAX_REGION_SIZE / 2
        ? XMEM_MAX_REGION_SIZE
        : growth_base * 2;
    return region;
}

static void* xmem__scratch_allocate(xmem_arena_t* arena, size_t size, size_t alignment) {
    if (size == 0 || size > SIZE_MAX - arena->used) return NULL;
    xmem_region_t* region = arena->scratch_current != NULL ? arena->scratch_current : arena->regions;
    for (; region != NULL; region = region->next) {
        unsigned char* candidate = xmem__align_pointer(
            region->scratch_start + region->scratch_used, alignment);
        if (candidate == NULL) continue;
        size_t offset = (size_t)(candidate - region->scratch_start);
        if (offset <= region->scratch_capacity && size <= region->scratch_capacity - offset) {
            region->scratch_used = offset + size;
            arena->scratch_current = region;
            arena->used += size;
            arena->scratch_outstanding++;
            arena->allocation_count++;
            if (arena->used > arena->peak_used) arena->peak_used = arena->used;
            return candidate;
        }
    }

    size_t minimum_region_payload;
    if (!xmem__checked_add(size, alignment - 1, &minimum_region_payload)) return NULL;
    region = xmem__scratch_region_create(arena, minimum_region_payload, alignment);
    if (region == NULL) return NULL;
    unsigned char* result = xmem__align_pointer(region->scratch_start, alignment);
    if (result == NULL) return NULL;
    size_t offset = (size_t)(result - region->scratch_start);
    if (offset > region->scratch_capacity || size > region->scratch_capacity - offset) return NULL;
    region->scratch_used = offset + size;
    arena->used += size;
    arena->scratch_outstanding++;
    arena->allocation_count++;
    if (arena->used > arena->peak_used) arena->peak_used = arena->used;
    return result;
}

static void* xmem__arena_allocate(xmem_arena_t* arena, size_t size, size_t alignment) {
    if (arena == NULL || !arena->initialized || size == 0) return NULL;
    alignment = xmem__normalize_alignment(alignment);
    if (alignment == 0) return NULL;
    if (arena->kind == XMEM_CLASS_SCRATCH) return xmem__scratch_allocate(arena, size, alignment);
    if (size > SIZE_MAX - arena->used) return NULL;

    int direct = arena->kind == XMEM_CLASS_COLD && arena->direct_threshold != 0 &&
                 size >= arena->direct_threshold;
    if (!direct) {
        for (xmem_block_t* block = arena->free_blocks; block != NULL; block = block->free_next) {
            size_t offset;
            size_t required;
            if (xmem__block_requirements(block, size, alignment, &offset, &required) &&
                required <= block->span) {
                return xmem__activate_block(arena, block, size, alignment, 1);
            }
        }
    }

    xmem_region_t* region = xmem__region_create(arena, size, alignment, direct);
    if (region == NULL) return NULL;
    void* result = xmem__activate_block(arena, region->first_block, size, alignment, !direct);
    if (result == NULL) xmem__unlink_region(arena, region);
    return result;
}

static void* xmem__arena_callocate(xmem_arena_t* arena, size_t count, size_t size,
                                   size_t alignment) {
    if (count != 0 && size > SIZE_MAX / count) return NULL;
    size_t total = count * size;
    void* result = xmem__arena_allocate(arena, total, alignment);
    if (result != NULL) memset(result, 0, total);
    return result;
}

static void xmem__coalesce_and_insert(xmem_arena_t* arena, xmem_block_t* block) {
    xmem_block_t* previous = block->previous;
    if (previous != NULL && previous->is_free) {
        xmem__free_list_remove(arena, previous);
        previous->span += block->span;
        previous->next = block->next;
        if (block->next != NULL) block->next->previous = previous;
        block = previous;
    }
    xmem_block_t* next = block->next;
    if (next != NULL && next->is_free) {
        xmem__free_list_remove(arena, next);
        block->span += next->span;
        block->next = next->next;
        if (next->next != NULL) next->next->previous = block;
    }
    block->requested = 0;
    block->user_offset = 0;
    block->alignment = 0;
    xmem__free_list_insert(arena, block);
}

static void xmem__arena_release(xmem_arena_t* arena, void* pointer) {
    if (arena == NULL || pointer == NULL || !arena->initialized || arena->kind == XMEM_CLASS_SCRATCH) return;
    xmem_block_t* block = xmem__pointer_block(arena, pointer);
    if (block == NULL || block->owner != (unsigned int)arena->kind || block->is_free) {
#ifdef XMEM_DEBUG
        fprintf(stderr, "xmem: invalid, duplicate, or mismatched free (%p)\n", pointer);
#endif
        return;
    }
#ifdef XMEM_DEBUG
    if (!xmem__guard_is_valid(block)) {
        fprintf(stderr, "xmem: allocation boundary guard corrupted (%p)\n", pointer);
    }
    memset(pointer, 0xdd, block->requested);
#endif
    arena->used -= block->requested;
    arena->free_count++;
    if (block->region->direct) {
        xmem__unlink_region(arena, block->region);
        return;
    }
    xmem__coalesce_and_insert(arena, block);
}

static void* xmem__arena_reallocate(xmem_arena_t* arena, void* pointer, size_t size) {
    if (arena == NULL || !arena->initialized || arena->kind == XMEM_CLASS_SCRATCH) return NULL;
    if (pointer == NULL) return xmem__arena_allocate(arena, size, _Alignof(xmem_max_align_t));
    if (size == 0) {
        xmem__arena_release(arena, pointer);
        return NULL;
    }

    xmem_block_t* block = xmem__pointer_block(arena, pointer);
    if (block == NULL || block->owner != (unsigned int)arena->kind || block->is_free) {
#ifdef XMEM_DEBUG
        fprintf(stderr, "xmem: invalid or mismatched realloc (%p)\n", pointer);
#endif
        return NULL;
    }
#ifdef XMEM_DEBUG
    if (!xmem__guard_is_valid(block)) {
        fprintf(stderr, "xmem: allocation boundary guard corrupted (%p)\n", pointer);
    }
#endif
    size_t old_size = block->requested;
    size_t guard_size = 0;
#ifdef XMEM_DEBUG
    guard_size = XMEM_DEBUG_GUARD_SIZE;
#endif
    if (block->user_offset <= block->span &&
        guard_size <= block->span - block->user_offset &&
        size <= block->span - block->user_offset - guard_size) {
        if (size > old_size && size - old_size > SIZE_MAX - arena->used) return NULL;
        if (size < old_size) {
            size_t offset;
            size_t required;
            size_t minimum_free_span = sizeof(xmem_block_t) + sizeof(xmem_block_t*) +
                                       _Alignof(xmem_max_align_t) + 1;
            if (xmem__block_requirements(block, size, block->alignment, &offset, &required) &&
                block->span - required >= minimum_free_span) {
                xmem_block_t* tail = (xmem_block_t*)((unsigned char*)block + required);
                memset(tail, 0, sizeof(*tail));
                tail->span = block->span - required;
                tail->region = block->region;
                tail->owner = (unsigned int)arena->kind;
                tail->previous = block;
                tail->next = block->next;
                if (tail->next != NULL) tail->next->previous = tail;
                block->span = required;
                block->next = tail;
                xmem__coalesce_and_insert(arena, tail);
            }
        }
        arena->used = arena->used - old_size + size;
        if (arena->used > arena->peak_used) arena->peak_used = arena->used;
        block->requested = size;
#ifdef XMEM_DEBUG
        xmem__write_guard(block);
#endif
        return pointer;
    }

    size_t required_offset;
    size_t required_span;
    if (block->next != NULL && block->next->is_free &&
        xmem__block_requirements(block, size, block->alignment,
                                 &required_offset, &required_span) &&
        size - old_size <= SIZE_MAX - arena->used &&
        block->span <= SIZE_MAX - block->next->span &&
        block->span + block->next->span >= required_span) {
        xmem_block_t* next = block->next;
        xmem__free_list_remove(arena, next);
        block->span += next->span;
        block->next = next->next;
        if (next->next != NULL) next->next->previous = block;
        arena->used += size - old_size;
        if (arena->used > arena->peak_used) arena->peak_used = arena->used;
        block->requested = size;
#ifdef XMEM_DEBUG
        xmem__write_guard(block);
#endif
        return pointer;
    }

    void* resized = xmem__arena_allocate(arena, size, block->alignment);
    if (resized == NULL) return NULL;
    memcpy(resized, pointer, old_size < size ? old_size : size);
    xmem__arena_release(arena, pointer);
    return resized;
}

static void xmem__arena_reset(xmem_arena_t* arena) {
    if (arena == NULL || !arena->initialized || arena->kind != XMEM_CLASS_SCRATCH) return;
    for (xmem_region_t* region = arena->regions; region != NULL; region = region->next) {
        region->scratch_used = 0;
    }
    arena->scratch_current = arena->regions;
    arena->free_count += arena->scratch_outstanding;
    arena->scratch_outstanding = 0;
    arena->used = 0;
}

static void xmem__arena_statistics(xmem_arena_t* arena, xmem_stats_t* out) {
    if (out == NULL) return;
    memset(out, 0, sizeof(*out));
    if (arena == NULL || !arena->initialized) return;
    out->reserved = arena->reserved;
    out->used = arena->used;
    out->peak_used = arena->peak_used;
    out->allocation_count = arena->allocation_count;
    out->free_count = arena->free_count;
    out->region_count = arena->region_count;
}

static void xmem__arena_destroy(xmem_arena_t* arena) {
    if (arena == NULL || !arena->initialized) return;
#ifdef XMEM_DEBUG
    if (arena->used != 0) {
        fprintf(stderr, "xmem: destroying class %u with %zu live bytes\n",
                (unsigned int)arena->kind, arena->used);
    }
#endif
    xmem_region_t* region = arena->regions;
    while (region != NULL) {
        xmem_region_t* next = region->next;
        xmem__unmap(region, region->mapping_size);
        region = next;
    }
    memset(arena, 0, sizeof(*arena));
}

pub int xmem_init(void) {
    return xmem.init();
}

pub void xmem_destroy(void) {
    xmem.destroy();
}

pub owned scratch void* alloc_scratch(size_t size) {
    if (!xmem.initialized && xmem_init() != 0) return NULL;
    return xmem_arena__alloc(&xmem.scratch_arena, size, _Alignof(xmem_max_align_t));
}

pub owned scratch void* alloc_scratch_aligned(size_t size, size_t alignment) {
    if (!xmem.initialized && xmem_init() != 0) return NULL;
    return xmem_arena__alloc(&xmem.scratch_arena, size, alignment);
}

pub void reset_scratch(void) {
    if (xmem.initialized) xmem_arena__reset(&xmem.scratch_arena);
}

pub owned hot void* alloc_hot(size_t size) {
    if (!xmem.initialized && xmem_init() != 0) return NULL;
    size_t alignment = size >= XMEM_HOT_CACHE_ALIGN_THRESHOLD
        ? XMEM_CACHE_LINE_SIZE : _Alignof(xmem_max_align_t);
    return xmem_arena__alloc(&xmem.hot_arena, size, alignment);
}

pub owned hot void* alloc_hot_aligned(size_t size, size_t alignment) {
    if (!xmem.initialized && xmem_init() != 0) return NULL;
    return xmem_arena__alloc(&xmem.hot_arena, size, alignment);
}

pub owned warm void* alloc_warm(size_t size) {
    if (!xmem.initialized && xmem_init() != 0) return NULL;
    return xmem_arena__alloc(&xmem.warm_arena, size, _Alignof(xmem_max_align_t));
}

pub owned cold void* alloc_cold(size_t size) {
    if (!xmem.initialized && xmem_init() != 0) return NULL;
    return xmem_arena__alloc(&xmem.cold_arena, size, _Alignof(xmem_max_align_t));
}

pub owned hot void* calloc_hot(size_t count, size_t size) {
    if (!xmem.initialized && xmem_init() != 0) return NULL;
    size_t alignment = size >= XMEM_HOT_CACHE_ALIGN_THRESHOLD
        ? XMEM_CACHE_LINE_SIZE : _Alignof(xmem_max_align_t);
    return xmem_arena__calloc(&xmem.hot_arena, count, size, alignment);
}

pub owned warm void* calloc_warm(size_t count, size_t size) {
    if (!xmem.initialized && xmem_init() != 0) return NULL;
    return xmem_arena__calloc(&xmem.warm_arena, count, size, _Alignof(xmem_max_align_t));
}

pub owned cold void* calloc_cold(size_t count, size_t size) {
    if (!xmem.initialized && xmem_init() != 0) return NULL;
    return xmem_arena__calloc(&xmem.cold_arena, count, size, _Alignof(xmem_max_align_t));
}

pub owned hot void* realloc_hot(borrowed hot void* pointer, size_t size) {
    if (!xmem.initialized && xmem_init() != 0) return NULL;
    if (pointer == NULL) return alloc_hot(size);
    return xmem_arena__realloc(&xmem.hot_arena, pointer, size);
}

pub owned warm void* realloc_warm(borrowed warm void* pointer, size_t size) {
    if (!xmem.initialized && xmem_init() != 0) return NULL;
    return xmem_arena__realloc(&xmem.warm_arena, pointer, size);
}

pub owned cold void* realloc_cold(borrowed cold void* pointer, size_t size) {
    if (!xmem.initialized && xmem_init() != 0) return NULL;
    return xmem_arena__realloc(&xmem.cold_arena, pointer, size);
}

pub void free_hot(borrowed hot void* pointer) {
    if (xmem.initialized) xmem_arena__free(&xmem.hot_arena, pointer);
}

pub void free_warm(borrowed warm void* pointer) {
    if (xmem.initialized) xmem_arena__free(&xmem.warm_arena, pointer);
}

pub void free_cold(borrowed cold void* pointer) {
    if (xmem.initialized) xmem_arena__free(&xmem.cold_arena, pointer);
}

pub int xmem_get_stats(xmem_class_t kind, owned xmem_stats_t* out) {
    if (out == NULL || !xmem.initialized) return 1;
    switch (kind) {
        case XMEM_CLASS_SCRATCH: xmem_arena__stats(&xmem.scratch_arena, out); return 0;
        case XMEM_CLASS_HOT: xmem_arena__stats(&xmem.hot_arena, out); return 0;
        case XMEM_CLASS_WARM: xmem_arena__stats(&xmem.warm_arena, out); return 0;
        case XMEM_CLASS_COLD: xmem_arena__stats(&xmem.cold_arena, out); return 0;
        default: return 1;
    }
}

#endif
