typedef struct value_t {
    int value;

    pub int increment(borrowed mut *self) {
        self->value++;
        return 0;
    }
} value_t;

int main(void) {
    value_t value = {0};
    (&value).increment();
    return value.value == FLAG ? 0 : 1;
}
