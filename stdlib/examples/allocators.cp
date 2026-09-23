#include <stdio.h>
#include <string.h>

comptime import "stdlib:/memory/xmem.cp";

int main(void) {
    if (xmem_init() != 0) return 1;

    scratch char* message = alloc_scratch(128);
    warm char* greeting = alloc_warm(32);
    hot unsigned long* frame = alloc_hot(sizeof(unsigned long));
    cold unsigned char* snapshot = alloc_cold(64);
    if (message == NULL || greeting == NULL || frame == NULL || snapshot == NULL) {
        xmem_destroy();
        return 2;
    }

    snprintf(message, 128, "scratch memory is reset in bulk");
    strcpy(greeting, "warm memory is explicitly freed");
    *frame = 7;
    snapshot[0] = 0x2a;
    printf("%s; %s; frame=%lu; snapshot=%u\n",
           message, greeting, *frame, (unsigned int)snapshot[0]);

    free_hot(frame);
    free_warm(greeting);
    free_cold(snapshot);
    reset_scratch();
    xmem_destroy();
    return 0;
}
