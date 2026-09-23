# C-plus Memory Allocator Specification

## 1. Objective

Implement a C-plus memory subsystem providing four allocation intents:

```c
scratch
hot
warm
cold
```

They are optional empty C-plus annotations, like:

```c
pub
priv
borrowed
owned
mut
stat
```

and therefore must remain valid documentation/tooling hints rather than new pointer representations or ABI types.

Generated C shall ultimately define them as empty macros:

```c
#define scratch
#define hot
#define warm
#define cold
```

Typical C-plus:

```c
scratch char* temporary;
hot processor_t* processor;
warm char* text;
cold unsigned char* blob;
```

The annotation describes the intended storage/access semantics.

The allocator used to obtain the object should agree with the annotation.

---

# 2. Allocation classes

## `scratch`

Very short-lived temporary memory.

Expected uses:

```text
temporary strings
formatting buffers
parser/compiler temporary data
intermediate calculations
per-operation data
```

Properties:

```text
fast bump allocation
no individual free
bulk reset
normal C alignment
stable addresses until reset
```

Example:

```c
scratch char* text = alloc_scratch(1024);

/* use text */

reset_scratch();
```

All outstanding scratch pointers become invalid after:

```c
reset_scratch();
```

---

## `hot`

Small frequently accessed runtime data.

Expected uses:

```text
active state
frequently traversed nodes
small queues
dispatch tables
counters
working-set objects
```

`hot` does NOT mean physically allocating into L1/L2/L3 cache.

The allocator should instead favor cache locality by using:

```text
compact placement
low fragmentation
appropriate alignment
stable addresses
small metadata overhead
```

Default hot allocations should be cache-line aligned where doing so is beneficial.

Default configurable assumption:

```c
#define XMEM_CACHE_LINE_SIZE 64
```

Example:

```c
hot processor_t* processor =
    alloc_hot(sizeof(processor_t));
```

---

## `warm`

General-purpose dynamic application memory.

This is the normal replacement for ordinary:

```c
malloc
calloc
realloc
free
```

Expected uses:

```text
strings
collections
application objects
workspace state
medium/long-lived mutable data
```

Example:

```c
warm char* text = alloc_warm(1024);

text = realloc_warm(text, 2048);

free_warm(text);
```

---

## `cold`

Large or rarely accessed memory.

Expected uses:

```text
large blobs
archives
snapshots
large indexes
rarely traversed structures
cached generated data
```

The primary purpose is to separate cold data from the normal/hot working sets.

Example:

```c
cold unsigned char* blob =
    alloc_cold(blob_size);
```

Large allocations may use an individual OS mapping rather than an arena block.

Example default threshold:

```c
#define XMEM_COLD_DIRECT_MAP_THRESHOLD (1024 * 1024)
```

---

# 3. Non-goals

Do not implement:

```text
L1 allocator
L2 allocator
L3 allocator
frozen allocator
disk allocator
garbage collector
reference counting
moving allocator
implicit object serialization
```

CPU cache residency is controlled by the hardware.

Persistence is separate from allocation and should use facilities such as:

```c
dump(...)
restore(...)
```

rather than pretending disk is another RAM allocator.

---

# 4. C-plus allocator implementation

The implementation should use C-plus structs and receiver methods internally.

Define an allocator arena:

```c
typedef struct xmem_arena_t {
    unsigned char* base;
    size_t capacity;
    size_t used;

    void* regions;
    void* free_list;

    size_t allocation_count;
    size_t free_count;
    size_t peak_used;

    pub error_t init(
        borrowed mut *self,
        size_t initial_capacity
    ) {
        ...
    }

    pub owned void* alloc(
        borrowed mut *self,
        size_t size
    ) {
        ...
    }

    pub owned void* calloc(
        borrowed mut *self,
        size_t count,
        size_t size
    ) {
        ...
    }

    pub owned void* realloc(
        borrowed mut *self,
        borrowed void* ptr,
        size_t new_size
    ) {
        ...
    }

    pub void free(
        borrowed mut *self,
        borrowed void* ptr
    ) {
        ...
    }

    pub void reset(
        borrowed mut *self
    ) {
        ...
    }

    pub void destroy(
        borrowed mut *self
    ) {
        ...
    }
} xmem_arena_t;
```

Not every allocation class has to expose every operation.

In particular, scratch allocation must not require individual `free`.

---

# 5. Global allocator context

Provide one runtime allocation context containing the four independent arenas:

```c
typedef struct xmem_t {
    xmem_arena_t scratch;
    xmem_arena_t hot;
    xmem_arena_t warm;
    xmem_arena_t cold;

    pub error_t init(
        borrowed mut *self
    ) {
        ...
    }

    pub void destroy(
        borrowed mut *self
    ) {
        ...
    }
} xmem_t;
```

The runtime owns one allocator context:

```c
priv xmem_t xmem;
```

Initialization:

```c
error_t error = xmem.init();

if (error != ERROR_NONE) {
    ...
}
```

Shutdown:

```c
xmem.destroy();
```

The allocator subsystem itself is therefore written idiomatically in C-plus.

---

# 6. Public convenience API

Application code should not need to address the internal arenas directly for ordinary allocation.

Expose:

```c
pub owned void* alloc_scratch(size_t size);

pub owned void* alloc_hot(size_t size);
pub owned void* alloc_warm(size_t size);
pub owned void* alloc_cold(size_t size);
```

These delegate internally:

```c
pub owned void* alloc_scratch(size_t size) {
    return xmem.scratch.alloc(size);
}

pub owned void* alloc_hot(size_t size) {
    return xmem.hot.alloc(size);
}

pub owned void* alloc_warm(size_t size) {
    return xmem.warm.alloc(size);
}

pub owned void* alloc_cold(size_t size) {
    return xmem.cold.alloc(size);
}
```

This preserves compact application syntax:

```c
scratch char* tmp = alloc_scratch(256);

hot node_t* node =
    alloc_hot(sizeof(node_t));

warm string_t* string =
    alloc_warm(sizeof(string_t));

cold void* image =
    alloc_cold(image_size);
```

---

# 7. Zeroed allocation

Provide:

```c
pub owned void* calloc_hot(
    size_t count,
    size_t size
);

pub owned void* calloc_warm(
    size_t count,
    size_t size
);

pub owned void* calloc_cold(
    size_t count,
    size_t size
);
```

Scratch does not require a dedicated calloc unless a concrete use case appears.

Every calloc implementation must detect multiplication overflow:

```c
if (
    count != 0 &&
    size > SIZE_MAX / count
) {
    return NULL;
}
```

Returned memory must be zeroed.

---

# 8. Reallocation

Provide:

```c
pub owned void* realloc_hot(
    borrowed void* ptr,
    size_t size
);

pub owned void* realloc_warm(
    borrowed void* ptr,
    size_t size
);

pub owned void* realloc_cold(
    borrowed void* ptr,
    size_t size
);
```

Required semantics:

```c
realloc_warm(NULL, n);
```

is equivalent to:

```c
alloc_warm(n);
```

and:

```c
realloc_warm(ptr, 0);
```

must release the allocation and return:

```c
NULL
```

When possible, resize in place.

Otherwise:

```text
allocate replacement
copy min(old_size, new_size)
release original
return replacement
```

Previously returned pointers must never move merely because an arena itself grows.

---

# 9. Free

Provide:

```c
pub void free_hot(
    borrowed void* ptr
);

pub void free_warm(
    borrowed void* ptr
);

pub void free_cold(
    borrowed void* ptr
);
```

Do not provide:

```c
free_scratch(...)
```

Scratch allocation uses bulk lifetime management.

---

# 10. Scratch reset

Expose:

```c
pub void reset_scratch(void);
```

which delegates to:

```c
xmem.scratch.reset();
```

Resetting scratch:

```text
sets its current allocation position back to the beginning
invalidates every outstanding scratch pointer
retains backing memory for reuse
does not normally return the arena to the OS
```

This operation should be extremely cheap.

---

# 11. Scratch implementation

Scratch should use bump allocation.

Conceptually:

```c
size_t aligned =
    xmem__align_up(self->used, alignment);

if (aligned + size > self->capacity) {
    /* acquire another region */
}

void* result =
    self->base + aligned;

self->used =
    aligned + size;

return result;
```

There should normally be no allocation header for individual scratch objects.

Minimum alignment:

```c
_Alignof(max_align_t)
```

Provide:

```c
priv size_t xmem__align_up(
    size_t value,
    size_t alignment
);
```

---

# 12. Managed-block allocator

`hot`, `warm`, and most `cold` allocations require block metadata.

Conceptually:

```text
+----------------------+
| xmem block metadata  |
+----------------------+
| user memory          |
+----------------------+
```

Internal representation may resemble:

```c
typedef struct xmem_block_t {
    size_t size;
    size_t capacity;
    unsigned int flags;

    struct xmem_block_t* previous;
    struct xmem_block_t* next;
} xmem_block_t;
```

This type is private runtime implementation detail.

Application code must never depend on its layout.

---

# 13. Free blocks

Free blocks should initially use simple size classes.

For example:

```text
16
32
64
128
256
512
1024
2048
4096
8192
...
```

Do not overengineer the first implementation.

Required priorities:

```text
correctness
predictable behavior
easy auditing
reasonable fragmentation
```

before sophisticated allocation performance.

Adjacent free blocks should be merged where practical.

---

# 14. Hot-specific policy

The hot allocator should use the same fundamental managed-block machinery where practical, but configure it for locality.

Favor:

```text
small blocks
contiguous placement
low metadata overhead
low fragmentation
cache-line-aware alignment
```

Expose aligned allocation:

```c
pub owned void* alloc_hot_aligned(
    size_t size,
    size_t alignment
);
```

Default hot allocations may use:

```c
XMEM_CACHE_LINE_SIZE
```

when appropriate.

Do not waste an entire 64-byte line for every tiny object merely to satisfy the word `hot`.

The implementation should distinguish useful cache alignment from pointless padding.

---

# 15. Cold-specific policy

Small/medium cold allocations may use an ordinary cold arena.

Large cold allocations should be independently mapped.

Conceptually:

```c
if (
    size >=
    XMEM_COLD_DIRECT_MAP_THRESHOLD
) {
    return xmem__map(size);
}
```

Independent mappings make very large cold objects easy to release without fragmenting the arena.

---

# 16. Backing memory

On POSIX systems, backing regions should come directly from virtual memory.

Use:

```c
mmap(
    NULL,
    size,
    PROT_READ | PROT_WRITE,
    MAP_PRIVATE | MAP_ANONYMOUS,
    -1,
    0
);
```

and release complete mappings with:

```c
munmap(...)
```

Do not use:

```text
malloc
calloc
realloc
free
sbrk
brk
```

inside the allocator implementation, except temporarily while bootstrapping if explicitly documented.

The target implementation should ultimately be independent of the libc heap allocator.

---

# 17. Region growth

An arena may consist of multiple regions:

```text
arena
 |
 +-- region
 |
 +-- region
 |
 +-- region
```

When one region fills, allocate another.

Never relocate an existing region after user pointers have escaped.

Region size may grow geometrically:

```text
1 MiB
2 MiB
4 MiB
8 MiB
...
```

subject to reasonable limits.

---

# 18. Default capacities

Make capacities configurable.

Initial defaults:

```c
#define XMEM_SCRATCH_INITIAL_SIZE \
    (1 * 1024 * 1024)

#define XMEM_HOT_INITIAL_SIZE \
    (4 * 1024 * 1024)

#define XMEM_WARM_INITIAL_SIZE \
    (16 * 1024 * 1024)

#define XMEM_COLD_INITIAL_SIZE \
    (16 * 1024 * 1024)

#define XMEM_CACHE_LINE_SIZE 64

#define XMEM_COLD_DIRECT_MAP_THRESHOLD \
    (1 * 1024 * 1024)
```

These are arena sizes.

They are NOT claims about hardware cache sizes.

---

# 19. Statistics

Define:

```c
typedef struct xmem_stats_t {
    size_t reserved;
    size_t used;
    size_t peak_used;

    size_t allocation_count;
    size_t free_count;

    size_t region_count;
} xmem_stats_t;
```

Expose a method on the arena:

```c
pub void stats(
    borrowed *self,
    owned xmem_stats_t* out
) {
    ...
}
```

Usage:

```c
xmem_stats_t statistics;

xmem.warm.stats(&statistics);
```

Optional public shortcuts may later be added if useful.

---

# 20. Debug mode

When:

```c
#define XMEM_DEBUG
```

is enabled, implement diagnostics for:

```text
double free
invalid free
allocator mismatch
corrupted block header
buffer guards where practical
memory poisoning
allocation counters
leaks at shutdown
```

A useful debug enhancement is tagging each managed allocation with its allocation class:

```c
XMEM_HOT
XMEM_WARM
XMEM_COLD
```

Then this can be diagnosed:

```c
warm char* value =
    alloc_warm(100);

free_hot(value);
```

as an allocator mismatch.

Debug information must not affect the normal public ABI.

---

# 21. Storage annotations

Add these annotations to the standard C-plus annotation preamble:

```c
#define scratch
#define hot
#define warm
#define cold
```

Their meanings are:

```text
scratch
    extremely short-lived bulk-reset memory

hot
    frequently accessed working-set memory

warm
    ordinary general-purpose dynamic memory

cold
    infrequently accessed or large memory
```

They express programmer intent.

They do not automatically allocate anything.

Thus:

```c
hot foo_t* value;
```

does not allocate storage.

It simply declares the intent associated with `value`.

Actual allocation still happens explicitly:

```c
hot foo_t* value =
    alloc_hot(sizeof(foo_t));
```

---

# 22. Compiler AST support

The C-plus parser/compiler should retain allocation-intent annotations.

Example:

```c
hot node_t* node;
```

should conceptually produce:

```text
VariableDecl
    name: node
    type: node_t*
    allocationIntent: HOT
```

Supported values:

```text
NONE
SCRATCH
HOT
WARM
COLD
```

The annotation must survive long enough for compiler/IDE analysis even if it ultimately disappears or becomes an empty macro in emitted C.

---

# 23. Allocation-intent diagnostics

The compiler or IDE should detect obvious contradictions.

Example:

```c
hot char* value =
    alloc_cold(1024);
```

Diagnostic:

```text
allocation intent mismatch:
'value' is declared hot but receives memory from alloc_cold()
```

Similarly:

```c
warm char* value =
    alloc_scratch(1024);
```

should warn because the underlying lifetime is potentially much shorter than the declared intent suggests.

Initially these are warnings.

They are not compilation errors.

---

# 24. Assignment propagation

Tooling should ideally propagate known allocation provenance through simple assignments.

Example:

```c
scratch char* a =
    alloc_scratch(100);

char* b = a;
```

`b` can be known by static analysis to reference scratch storage even though it has no explicit annotation.

This does not require an ownership checker.

It is advisory static analysis only.

---

# 25. Functions and allocation provenance

Existing C-plus ownership annotations remain independent from allocation intent.

Example:

```c
pub error_t create_name(
    borrowed const user_t* user,
    owned warm char** out
);
```

means:

```text
user
    borrowed input

out
    function initializes/acquires the returned pointer

warm
    returned allocation is intended to come from warm storage
```

A valid implementation might contain:

```c
*out = alloc_warm(required_size);
```

This gives tooling enough information to check allocation intent across APIs.

Similarly:

```c
pub error_t temporary_text(
    borrowed const source_t* source,
    owned scratch char** out
);
```

declares that the produced pointer has scratch lifetime.

---

# 26. Allocation-return annotations

C-plus should permit allocation intent next to ownership annotations where syntactically valid.

Examples:

```c
owned warm char*
owned hot processor_t*
owned cold void*
owned scratch token_t*
```

This remains annotation metadata over ordinary C pointer types.

The emitted C representation remains an ordinary pointer.

---

# 27. Idiomatic C-plus examples

## Scratch workflow

```c
pub error_t format_user(
    borrowed const user_t* user,
    owned scratch char** out
) {
    scratch char* value =
        alloc_scratch(256);

    if (value == NULL)
        return ERROR_OUT_OF_MEMORY;

    snprintf(
        value,
        256,
        "%s:%d",
        user->name,
        user->id
    );

    *out = value;

    return ERROR_NONE;
}
```

Later:

```c
reset_scratch();
```

---

## Warm object

```c
typedef struct document_t {
    warm char* text;
    size_t length;

    pub error_t init(
        borrowed mut *self,
        size_t capacity
    ) {
        self->text =
            alloc_warm(capacity);

        if (self->text == NULL)
            return ERROR_OUT_OF_MEMORY;

        self->length = 0;

        return ERROR_NONE;
    }

    pub void destroy(
        borrowed mut *self
    ) {
        free_warm(self->text);

        self->text = NULL;
        self->length = 0;
    }
} document_t;
```

Usage:

```c
document_t document;

if (document.init(4096) != ERROR_NONE) {
    ...
}

/* ... */

document.destroy();
```

---

## Hot object

```c
typedef struct runtime_state_t {
    unsigned long frame;
    unsigned int flags;

    stat pub owned hot runtime_state_t* create() {
        hot runtime_state_t* state =
            alloc_hot(sizeof(runtime_state_t));

        if (state == NULL)
            return NULL;

        state->frame = 0;
        state->flags = 0;

        return state;
    }

    pub void destroy(
        borrowed mut *self
    ) {
        free_hot(self);
    }
} runtime_state_t;
```

Usage:

```c
hot runtime_state_t* state =
    runtime_state_t.create();
```

---

# 28. Standard-library migration

After the allocator is functional, migrate appropriate C-plus standard-library code away from direct libc allocation.

For example, generated dynamic containers currently have natural `init`, growth, and `destroy` lifetimes.

Their backing storage should initially use `warm` allocation:

```c
warm T* items;
```

and:

```c
T* resized =
    realloc_warm(
        self->items,
        next_capacity * sizeof(T)
    );
```

Destruction:

```c
free_warm(self->items);
```

This makes ordinary dynamic collections warm by default.

Do not automatically convert them to hot or scratch allocations.

---

# 29. Comptime allocator generation

Do not manually duplicate allocator boilerplate unnecessarily.

Where the implementations only differ by policy parameters, comptime may generate the ordinary C-plus implementation.

For example, an internal generator may eventually conceptually produce allocator declarations from:

```text
allocation class
alignment
initial region size
individual-free capability
direct-map threshold
```

However, do this only if it actually reduces implementation complexity.

The materialized result must remain ordinary readable C-plus.

Comptime must not become necessary simply to understand allocator behavior.

---

# 30. Thread safety

The first implementation may be single-threaded.

Design the structures so future policies can include:

```text
per-thread scratch arenas
per-thread hot arenas
shared warm arena
shared cold allocator
```

Do not introduce one global allocator mutex unless required.

Thread-local scratch is a natural future optimization.

---

# 31. Initialization and shutdown

Allocator lifecycle:

```c
int main(...) {
    error_t error =
        xmem.init();

    if (error != ERROR_NONE)
        return error;

    /* application */

    xmem.destroy();

    return 0;
}
```

If the runtime already has centralized initialization/shutdown, integrate the allocator there rather than requiring every application to call these manually.

---

# 32. Implementation order

Implement in this order:

1. add `scratch`, `hot`, `warm`, `cold` annotation support;
2. implement OS mapping helpers;
3. implement region representation;
4. implement alignment helpers;
5. implement scratch bump allocation/reset;
6. implement managed warm allocator;
7. implement `calloc_warm`;
8. implement `realloc_warm`;
9. implement `free_warm`;
10. derive hot behavior from managed allocator machinery;
11. implement cold arena/direct mappings;
12. implement statistics;
13. implement debug allocation validation;
14. migrate dynamic C-plus containers to warm allocation;
15. add compiler/IDE allocation-intent diagnostics.

---

# 33. Tests

Add C-plus tests using the existing `@test` infrastructure.

At minimum test:

```text
scratch allocation
scratch alignment
scratch reset and reuse
multiple scratch regions

warm allocate/free
warm realloc grow
warm realloc shrink
warm calloc zeroing
calloc overflow

hot alignment
hot allocate/free/realloc

cold allocate/free
cold direct mmap threshold

NULL allocation behavior
zero-size behavior
large allocation failure

statistics
peak usage
allocation/free counters

double-free detection in XMEM_DEBUG
wrong allocator detection in XMEM_DEBUG
```

Example:

```c
@test "scratch allocation and reset" {
    scratch int* first =
        alloc_scratch(sizeof(int));

    @assert(first != NULL)

    *first = 42;

    reset_scratch();

    scratch int* second =
        alloc_scratch(sizeof(int));

    @assert(second != NULL)
}
```

And:

```c
@test "warm realloc preserves contents" {
    warm int* values =
        alloc_warm(sizeof(int) * 2);

    @assert(values != NULL)

    values[0] = 10;
    values[1] = 20;

    values =
        realloc_warm(
            values,
            sizeof(int) * 4
        );

    @assert(values != NULL)
    @assertEquals(10, values[0])
    @assertEquals(20, values[1])

    free_warm(values);
}
```

Use the existing C-plus test command so allocator tests participate in normal compiler/runtime regression testing.

---

# 34. Fundamental design rule

Keep these concepts separate:

```text
borrowed / owned / mut
    ownership and mutation intent

scratch / hot / warm / cold
    storage/lifetime/access intent

allocator implementation
    actual storage policy

CPU cache
    hardware-managed runtime behavior
```

C-plus annotations describe intent.

The allocator enforces storage policy.

The compiler and IDE can compare the two and warn about contradictions without turning C-plus into a garbage-collected or ownership-enforced language.
