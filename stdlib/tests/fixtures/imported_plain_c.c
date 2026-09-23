typedef struct imported_plain_value {
    int value;
} imported_plain_value;

int imported_plain_value_read(const imported_plain_value* value) {
    return value->value;
}

int main(void) {
    return 91;
}
