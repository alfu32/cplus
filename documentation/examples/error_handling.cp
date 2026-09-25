#include <stdio.h>
#include <stdlib.h>

typedef int error_t;
enum { ERROR_NONE = 0, ERROR_INVALID = 1, ERROR_ALLOCATION = 2 };

typedef struct counter_t {
    int value;
} counter_t;

@throws()
pub error_t parse_count(const char *text, borrowed mut int *out_count) {
    if (text == NULL || out_count == NULL) return ERROR_INVALID;
    char *end = NULL;
    long parsed = strtol(text, &end, 10);
    if (end == text || *end != '\0') return ERROR_INVALID;
    *out_count = (int)parsed;
    return ERROR_NONE;
}

@throws(error)
pub counter_t *create_counter(int value, borrowed mut error_t *error) {
    counter_t *self = malloc(sizeof *self);
    if (self == NULL) {
        *error = ERROR_ALLOCATION;
        return NULL;
    }
    self->value = value;
    *error = ERROR_NONE;
    return self;
}

int main(int argc, char **argv) {
    int count = 0;
    counter_t *counter = NULL;
    @try {
        parse_count(argc > 1 ? argv[1] : "5", &count);
        counter = create_counter(count);
        printf("counter=%d\n", counter->value);
    }
    @catch (ERROR_ALLOCATION, error_t error) {
        fprintf(stderr, "allocation error: %d\n", error);
    }
    @catch (ERROR_INVALID, error_t error) {
        fprintf(stderr, "invalid input: %d\n", error);
    }
    @catch (error_t error) {
        fprintf(stderr, "unexpected error: %d\n", error);
    }
    free(counter);
    return 0;
}
