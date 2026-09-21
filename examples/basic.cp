typedef int error_t;

typedef struct counter_t {
    int value;

    pub error_t add(borrowed mut *self, int amount) {
        self->value += amount;
        return 0;
    }

    static pub counter_t* alloc_init(int initial) {
        return 0;
    }
    pub void print(borrowed *self){
        printf("Counter{%d}",self.value);
    }
} counter_t;

int main(void) {
    counter_t counter;
    (&counter).add(3);
    counter_t.alloc_init(0);
    return 0;
}

