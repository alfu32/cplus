/* C-plus source annotations are intentionally retained in generated C. */
#ifndef CPLUS_ANNOTATIONS_DEFINED
#define CPLUS_ANNOTATIONS_DEFINED
#define pub
#define priv
#define mut
#define borrowed
#define owned
#define stat
#endif


#include <stdlib.h>
#include <stdio.h>

typedef int error_t;

typedef struct counter_t {
    int value;

} counter_t;

pub error_t counter__add(borrowed mut counter_t *self, int amount) {
        self->value += amount;
        return 0;
    }

pub error_t counter__init(borrowed counter_t *self, int initial) {
        self->value=initial;
        return 0;
    }

static pub counter_t* counter__alloc_init(int initial) {
        counter_t* s = malloc(sizeof *s);
        return s;
    }

pub void counter__print(borrowed counter_t *self){
        printf("Counter{%d}",self->value);
    }


int main(void) {
    counter_t counter;
    counter__print(&counter);
    counter__add(&counter, 3);
    counter__print(&counter);
    counter__alloc_init(0);
    return 0;
}

