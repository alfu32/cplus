package cplus

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files

class TranspilerTest {
    @Test
    fun lowersStructMethodsAndKeepsAnnotations() {
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
        assertTrue("#define borrowed" in result, result)
        assertTrue("pub int counter__add(borrowed mut counter_t *self, int amount)" in result, result)
        assertTrue("static pub counter_t* counter__alloc_init(int initial)" in result, result)
        assertTrue("counter__add(&counter, 3)" in result, result)
        assertTrue("counter__alloc_init(0)" in result, result)
    }

    @Test
    fun retainsAnnotationsOutsideStructs() {
        val result = CPlusTranspiler().transpile(
            "priv int read(owned char* output);\nborrowed int* value;\n"
        )
        assertTrue("priv int read(owned char* output);" in result, result)
        assertTrue("borrowed int* value;" in result, result)
    }

    @Test
    fun reportsComptimeForTheNextPass() {
        val error = assertThrows(CPlusSyntaxException::class.java) {
            CPlusTranspiler().transpile("int @value;")
        }
        assertTrue("not implemented" in error.message.orEmpty(), error.message)
    }

    @Test
    fun printsCommandHelp() {
        val help = StringBuilder()
        assertTrue(CPlusCli(output = help).run(listOf("help")) == 0)
        assertTrue("transcode filename.cp" in help.toString(), help.toString())
        assertTrue("compile filename.cp" in help.toString(), help.toString())
        assertTrue("run filename.cp" in help.toString(), help.toString())
    }

    @Test
    fun compilesAndRunsWithBundledTccAndPassthroughFlags() {
        val directory = Files.createTempDirectory("cplus-test")
        try {
            val source = directory.resolve("hello.cp")
            val executable = directory.resolve("hello")
            Files.writeString(
                source,
                """
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
                """.trimIndent()
            )
            assertTrue(
                CPlusCli().run(
                    listOf("run", source.toString(), "-o", executable.toString(), "-DFLAG=1")
                ) == 0
            )
            assertTrue(Files.isExecutable(executable), "TCC did not produce an executable")
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
