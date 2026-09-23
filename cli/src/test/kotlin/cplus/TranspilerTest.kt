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

        val result = CPlusTranspiler().transpile(source).code
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
        ).code
        assertTrue("priv int read(owned char* output);" in result, result)
        assertTrue("borrowed int* value;" in result, result)
    }

    @Test
    fun emitsCompilerVisibleLineMappings() {
        val source = """
            typedef struct value_t {
                int value;
                pub int increment(borrowed mut *self) {
                    self->value++;
                    return 0;
                }
            } value_t;
        """.trimIndent()
        val result = CPlusTranspiler().transpile(source, "/tmp/value.cp")
        assertTrue("#line 3 \"/tmp/value.cp\"" in result.code, result.code)
        assertTrue(result.sourceMap.entries.any { it.source.startLine == 3 }, result.sourceMap.entries.toString())
    }

    @Test
    fun resolvesComptimeScalarsBeforeCplusLowering() {
        val result = CPlusTranspiler().transpile(
            """
                int @answer = 21;
                int @twice(int @value) { return @value * 2; }
                int runtime_answer = @twice(@answer);
            """.trimIndent()
        ).code
        assertTrue("int runtime_answer = 42;" in result, result)
        assertTrue("@answer" !in result, result)
        assertTrue("@twice" !in result, result)
    }

    @Test
    fun materializesComptimeEntitiesAndGenericTypes() {
        val result = CPlusTranspiler().transpile(
            """
                variable @make_limit(int @value) {
                    return int generated_limit = @value;
                }
                function @make_checker(int @limit) {
                    return function int generated_checker(int value) {
                        return value < @limit;
                    };
                }
                @type wrapper(@type T) {
                    return struct {
                        T* wrapped_value;
                    };
                }
                @ {
                    @make_limit(10);
                    @make_checker(10);
                }
                typedef @wrapper(int) wrapper_int_t;
            """.trimIndent()
        ).code
        assertTrue("int generated_limit = 10;" in result, result)
        assertTrue("int generated_checker(int value)" in result, result)
        assertTrue("return value < 10;" in result, result)
        assertTrue("typedef struct __int__wrapper_t" in result, result)
        assertTrue("int* wrapped_value;" in result, result)
        assertTrue("@make_limit" !in result, result)
        assertTrue("@type" !in result, result)
    }

    @Test
    fun acceptsSigiledComptimeEntityResultKinds() {
        val result = CPlusTranspiler().transpile(
            """
                @var @make_limit(int @value) {
                    return int generated_limit = @value;
                }
                @fn @make_checker(int @limit) {
                    return @fn int generated_checker(int value) {
                        return value < @limit;
                    };
                }
                @ {
                    @make_limit(10);
                    @make_checker(10);
                }
            """.trimIndent()
        ).code
        assertTrue("int generated_limit = 10;" in result, result)
        assertTrue("int generated_checker(int value)" in result, result)
        assertTrue("return value < 10;" in result, result)
    }

    @Test
    fun acceptsSigiledTypeGeneratorNames() {
        val result = CPlusTranspiler().transpile(
            """
                @type @wrapper(@type T) {
                    return struct {
                        T* wrapped_value;
                    };
                }
                typedef @wrapper(int) wrapper_int_t;
            """.trimIndent()
        ).code
        assertTrue("typedef struct __int__wrapper_t" in result, result)
        assertTrue("int* wrapped_value;" in result, result)
    }

    @Test
    fun requiresTypedefForFileScopeTypeInstantiation() {
        val error = assertThrows(CPlusSyntaxException::class.java) {
            CPlusTranspiler().transpile(
                """
                    @type @wrapper(@type T) {
                        return struct {
                            T* wrapped_value;
                        };
                    }
                    @wrapper(int) wrapper_int_t;
                """.trimIndent()
            )
        }
        assertTrue(error.message.orEmpty().contains("must use typedef"), error.message)
    }

    @Test
    fun resolvesComptimeDeclarationsGeneratedAcrossPasses() {
        val source = """
            @code @emit_seed() {
                return @code {
                    @code @emit_box(@type T) {
                        return @code {
                            @type @box(@type U) {
                                return struct {
                                    U value;
                                    pub U get(borrowed *self) {
                                        return self->value;
                                    }
                                };
                            }
                            typedef @box(@T) generated_box_t;
                        };
                    }
                    @ {
                        @emit_box(int);
                    }
                };
            }
            @ {
                @emit_seed();
            }
            int main(void) {
                generated_box_t box = {42};
                return (&box).get() == 42 ? 0 : 1;
            }
        """.trimIndent()
        val result = CPlusTranspiler().transpile(source, "nested-expansion.cp").code

        assertTrue("typedef struct generated_box_t" in result, result)
        assertTrue("int generated_box__get(borrowed generated_box_t *self)" in result, result)
        assertTrue("generated_box__get(&box)" in result, result)
        assertTrue("@emit_seed" !in result, result)
        assertTrue("@emit_box" !in result, result)
        assertTrue("@type" !in result, result)
        assertTrue("@box" !in result, result)

        val directory = Files.createTempDirectory("cplus-generated-declarations")
        try {
            val sourcePath = directory.resolve("nested.cp")
            val executable = directory.resolve("nested")
            Files.writeString(sourcePath, source)
            val errors = StringBuilder()
            val status = CPlusCli(output = StringBuilder(), errors = errors).run(
                listOf("run", sourcePath.toString(), "-o", executable.toString())
            )
            assertTrue(status == 0, errors.toString())
            assertTrue(Files.isExecutable(executable), "TinyCC did not produce the generated-declaration executable")
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun mapsErrorsFromLaterComptimePassesBackToGeneratedSource() {
        val source = """
            @code @emit_failure() {
                return @code {
                    @missing_generator();
                };
            }
            @ {
                @emit_failure();
            }
        """.trimIndent()

        val error = assertThrows(CPlusSyntaxException::class.java) {
            CPlusTranspiler().transpile(source, "nested-error.cp")
        }

        assertTrue(error.message.orEmpty().contains("unknown comptime function @missing_generator"), error.message)
        assertTrue(error.sourceSpan?.file == "nested-error.cp", error.sourceSpan.toString())
        assertTrue(error.sourceSpan?.startLine == 3, error.sourceSpan.toString())
    }

    @Test
    fun resolvesImportsAndScalarReferencesIntroducedByGeneratedFragments() {
        val directory = Files.createTempDirectory("cplus-generated-import")
        try {
            val imported = directory.resolve("generated_constants.cp")
            Files.writeString(imported, "int @generated_value = 73;\nint imported_runtime = 1;\n")
            val source = """
                @code @emit_import() {
                    return @code {
                        @import "generated_constants.cp";
                    };
                }
                @ {
                    @emit_import();
                }
                int result = @generated_value;
            """.trimIndent()

            val result = CPlusTranspiler().transpile(source, directory.resolve("main.cp").toString())

            assertTrue("int result = 73;" in result.code, result.code)
            assertTrue("imported_runtime = 1;" in result.code, result.code)
            assertTrue("#line 1 \"${imported.toAbsolutePath()}\"" in result.code, result.code)
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun reportsComptimeExpansionThatMakesNoProgress() {
        val error = assertThrows(CPlusSyntaxException::class.java) {
            CPlusTranspiler().transpile(
                """
                    @code @repeat() {
                        return @code {
                            @repeat();
                        };
                    }
                    @ {
                        @repeat();
                    }
                """.trimIndent()
            )
        }

        assertTrue(error.message.orEmpty().contains("comptime expansion made no progress"), error.message)
    }

    @Test
    fun lowersGenericMethodsWithFunctionPointerParameters() {
        val result = CPlusTranspiler().transpile(
            """
                @type list(@type T) {
                    return struct {
                        T* items;
                        size_t length;
                        pub int each(borrowed *self, int (*callback)(borrowed T* item, size_t index)) {
                            for (size_t i = 0; i < self->length; i++) {
                                if (callback(&self->items[i], i) != 0) return 1;
                            }
                            return 0;
                        }
                    };
                }
                typedef @list(int) int_list_t;
                int visit(borrowed int* item, size_t index) { return *item + (int)index; }
                int main(void) {
                    int_list_t values;
                    (&values).each(visit);
                    return 0;
                }
            """.trimIndent()
        ).code
        assertTrue("int (*callback)(borrowed int* item, size_t index)" in result, result)
        assertTrue("int_list__each(borrowed int_list_t *self" in result, result)
        assertTrue("callback(&self->items[i], i)" in result, result)
        assertTrue("int_list__each(&values, visit)" in result, result)
    }

    @Test
    fun materializesComptimeStructReferences() {
        val result = CPlusTranspiler().transpile(
            """
                typedef struct @point_t {
                    int x;
                    int y;
                } point_t;
                @ {
                    @point_t;
                }
            """.trimIndent()
        ).code
        assertTrue("typedef struct point_t" in result, result)
        assertTrue("int x;" in result, result)
        assertTrue("@point_t" !in result, result)
    }

    @Test
    fun evaluatesTypeReflectionAtComptime() {
        val result = CPlusTranspiler().transpile(
            """
                int @size_of(@type T) { return T.size; }
                int int_size = @size_of(int);
            """.trimIndent()
        ).code
        assertTrue("int int_size = 4;" in result, result)
        assertTrue("@size_of" !in result, result)
    }

    @Test
    fun iteratesReflectedFieldsInComptimeBlocks() {
        val result = CPlusTranspiler().transpile(
            """
                variable @make_field(char* @field) {
                    return char* generated_field = @field;
                }
                typedef struct user_t {
                    int id;
                    int age;
                } user_t;
                @ {
                    @for field in user_t.fields {
                        @make_field(field.name);
                    }
                }
            """.trimIndent()
        ).code
        assertTrue("char* generated_field = \"id\";" in result, result)
        assertTrue("char* generated_field = \"age\";" in result, result)
    }

    @Test
    fun importsComptimeValuesAndPreservesImportedSourceMappings() {
        val directory = Files.createTempDirectory("cplus-import")
        try {
            val imported = directory.resolve("constants.cp")
            val source = directory.resolve("main.cp")
            Files.writeString(
                imported,
                """
                    int @default_limit = 8;
                    typedef struct imported_t {
                        int value;
                    } imported_t;
                """.trimIndent()
            )
            Files.writeString(
                source,
                """
                    @import "constants.cp";
                    int limit = @default_limit;
                """.trimIndent()
            )
            val result = CPlusTranspiler().transpile(Files.readString(source), source.toString())
            assertTrue("int limit = 8;" in result.code, result.code)
            assertTrue("typedef struct imported_t" in result.code, result.code)
            assertTrue(
                result.code.contains("#line 1 \"${imported.toAbsolutePath()}\""),
                result.code
            )
            assertTrue(
                result.sourceMap.entries.any {
                    it.source.file == imported.toAbsolutePath().toString() && it.source.startLine == 2
                },
                result.sourceMap.entries.toString()
            )
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun rejectsImportCyclesWithAComptimeDiagnostic() {
        val directory = Files.createTempDirectory("cplus-cycle")
        try {
            val first = directory.resolve("first.cp")
            val second = directory.resolve("second.cp")
            Files.writeString(first, "@import \"second.cp\";\n")
            Files.writeString(second, "@import \"first.cp\";\n")
            val error = assertThrows(CPlusSyntaxException::class.java) {
                CPlusTranspiler().transpile(Files.readString(first), first.toString())
            }
            assertTrue("import cycle" in error.message.orEmpty(), error.message)
            assertTrue(error.sourceSpan?.file == first.toString(), error.message)
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun mapsComptimeErrorsToTheOriginalSource() {
        val error = assertThrows(CPlusSyntaxException::class.java) {
            CPlusTranspiler().transpile(
                """
                    int @value = @missing;
                    int runtime_value = @value;
                """.trimIndent(),
                "/tmp/missing-value.cp"
            )
        }
        assertTrue(error.sourceSpan?.file == "/tmp/missing-value.cp", error.message)
        assertTrue(error.sourceSpan?.startLine == 1, error.message)
        assertTrue(error.sourceSpan?.startColumn == 14, error.message)
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
    fun reportsCompilerErrorsAgainstCpSource() {
        val directory = Files.createTempDirectory("cplus-diagnostics")
        try {
            val source = directory.resolve("broken.cp")
            val executable = directory.resolve("broken")
            Files.writeString(
                source,
                """
                    typedef struct thing_t {
                        int value;
                        pub int broken(borrowed mut *self) {
                            self->value = ;
                            return 0;
                        }
                    } thing_t;

                    int main(void) { return 0; }
                """.trimIndent()
            )
            val errors = StringBuilder()
            val result = CPlusCli(
                output = StringBuilder(),
                errors = errors,
                logger = ConsoleCompilationLogger(errors)
            ).run(listOf("compile", source.toString(), "-o", executable.toString()))
            assertTrue(result != 0, "invalid C-plus unexpectedly compiled")
            assertTrue(source.toString() in errors.toString(), errors.toString())
            assertTrue(":4:" in errors.toString(), errors.toString())
            assertTrue("pass: lower-method-calls" in errors.toString(), errors.toString())
            assertTrue("pass: tcc-compile" in errors.toString(), errors.toString())
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
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

    @Test
    fun compilesAndRunsMaterializedComptimeEntities() {
        val directory = Files.createTempDirectory("cplus-comptime-run")
        try {
            val source = directory.resolve("generated.cp")
            val executable = directory.resolve("generated")
            Files.writeString(
                source,
                """
                    int @answer = 21;
                    function @make_checker(int @limit) {
                        return function int generated_checker(int value) {
                            return value == @limit;
                        };
                    }
                    @ {
                        @make_checker(@answer);
                    }
                    int main(void) {
                        return generated_checker(21) ? 0 : 1;
                    }
                """.trimIndent()
            )
            assertTrue(CPlusCli().run(listOf("run", source.toString(), "-o", executable.toString())) == 0)
            assertTrue(Files.isExecutable(executable), "TCC did not produce a comptime executable")
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
