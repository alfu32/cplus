#include <stdlib.h>
#include <stdio.h>

typedef int error_t;

typedef struct counter_t {
    int value;

    pub error_t add(borrowed mut *self, int amount) {
        self->value += amount;
        return 0;
    }

    pub error_t init(borrowed *self,int initial) {
        self->value=initial;
        return 0;
    }
    static pub counter_t* alloc_init(int initial) {
        counter_t* s = malloc(sizeof *s);
        return s;
    }
    pub void print(borrowed *self){
        printf("Counter{%d}\n",self->value);
    }
} counter_t;

int main(void) {
    counter_t counter;
    (&counter).print();
    (&counter).add(3);
    (&counter).print();
    counter_t.alloc_init(0);
    return 0;
}

