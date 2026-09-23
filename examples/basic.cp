#include <stdlib.h>
#include <stdio.h>

typedef int error_t;

typedef struct counter_t {
    int value;

    pub error_t add(borrowed mut *self, int amount) {
        self->value += amount;
        return 0;
    }

    pub error_t set(borrowed *self,int initial) {
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

int main(int argc,const char** argv) {
    counter_t counter;
    counter_t cc;
    counter_t* cc_ptr = &cc;
    cc_ptr->set(12);
    counter.set(1);
    for(counter.set(5);counter.value<100;counter.add(1)){
       counter.print();
    }
    for(counter.set(0);counter.value<argc;counter.add(1)){
       counter.print();
       printf("[%d]=%s;\n",counter.value,argv[counter.value]);
    }
    // counter_t.alloc_init(0);
    return 0;
}
