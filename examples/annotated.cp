typedef struct value_t {
    int value;

    pub int increment(borrowed mut *self) {
        self->value++;
        return 0;
    }
} value_t;
// @generic(T,typedef struct wrapper_t{
//     T value;
//     pub error_t set(*self,T value){
//         self->value=value;
//         return 0;
//     }
//     pub T get(*self){
//         return self->value;
//     }
//     pub error_t add(*self,T x){
//         return self->value+=x;
//         return0
//     }
//     @generic(M, pub M map(*self){
//         return (M)self->value;
//     })
// } wrapper_t);

int main(void) {
    value_t value = {0};
    value.increment();

    /// wrapper_t(int) w_int;
    /// w_int.set(5);
    /// printf("%d\n",w_int.value);
    /// long l=w_int.(long)cast();

    return value.value == FLAG ? 0 : 1;
}
