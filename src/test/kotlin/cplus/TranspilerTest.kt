package cplus

fun main() {
    lowersStructMethodsAndCalls()
    stripsAnnotationsOutsideStructs()
    rejectsUnimplementedComptime()
    println("cplus tests: ok")
}

private fun lowersStructMethodsAndCalls() {
    val source = """
        typedef struct counter_t {
            int value;
            pub int add(borrowed mut *self, int amount) {
                self->value += amount;
                return 0;
            }
            static pub counter_t* alloc_init(int initial) {
                return 0;
            }
        } counter_t;

        int main(void) {
            counter_t counter;
            (&counter).add(3);
            counter_t.alloc_init(0);
            return 0;
        }
    """.trimIndent()

    val result = CPlusTranspiler().transpile(source)
    check("int counter__add(counter_t *self, int amount)" in result) { result }
    check("static counter_t* counter__alloc_init(int initial)" in result) { result }
    check("counter__add(&counter, 3)" in result) { result }
    check("counter__alloc_init(0)" in result) { result }
    check("borrowed" !in result && "pub" !in result && "mut" !in result) { result }
}

private fun stripsAnnotationsOutsideStructs() {
    val result = CPlusTranspiler().transpile(
        "priv int read(owned char* output);\nborrowed int* value;\n"
    )
    check("int read( char* output);" in result) { result }
    check(" int* value;" in result) { result }
}

private fun rejectsUnimplementedComptime() {
    val error = runCatching { CPlusTranspiler().transpile("int @value;") }.exceptionOrNull()
    check(error is CPlusSyntaxException) { "expected comptime diagnostic, got $error" }
    check("not implemented" in error.message.orEmpty()) { error.message.orEmpty() }
}
