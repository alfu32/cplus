/* 😀 UTF-8 before declaration checks UTF-16-to-byte position conversion. */ int first(void);
typedef struct sample_t {
    int value;
    pub int get(borrowed *self) { return self->value; }
    static pub sample_t* create(void);
} sample_t;

int main(void) { return 0; }
