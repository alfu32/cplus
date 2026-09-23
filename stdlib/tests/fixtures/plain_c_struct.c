#include <stdio.h>
#include <string.h>

typedef struct plain_record_t {
    int index;
    int value;
    int bias;
} plain_record_t;

void plain_record__hash(const plain_record_t* self, char* output) {
    snprintf(output, 32, "%d:%d:%d", self->index, self->value, self->bias);
}

/* The test command renames this main while building its generated test driver. */
int main(void) {
    return 0;
}
