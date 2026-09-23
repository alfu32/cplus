comptime import "stdlib:/memory/xmem.cp";

int main(void) {
    warm void* value = alloc_warm(sizeof(int));
    if (value == NULL) return 1;

    /* Deliberately mismatched: the debug build reports this without freeing value. */
    free_hot(value);
#ifdef XMEM_DEBUG
    ((unsigned char*)value)[sizeof(int)] = 0; /* Deliberate one-byte guard overwrite. */
#endif
    free_warm(value);
#ifdef XMEM_DEBUG
    free_warm(value); /* Also verifies that a second free is diagnosed. */
#endif
    xmem_destroy();
    return 0;
}
