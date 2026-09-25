package cplus

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files

class TranspilerTest {
    @Test
    fun deferMovesStatementsAndBlocksToFunctionEndInReverseOrder() {
        val directory = Files.createTempDirectory("cplus-defer-order")
        try {
            val source = CPlusTranspiler().transpile(
                """
                    int deferred_values[4];
                    int deferred_count;
                    void record_deferred_value(int value) {
                        deferred_values[deferred_count++] = value;
                    }
                    void run_deferred(void) {
                        defer record_deferred_value(1);
                        defer { record_deferred_value(2); record_deferred_value(3); }
                        if (1) defer record_deferred_value(4);
                    }
                    typedef struct deferred_value_t {
                        int value;
                        pub void increment(borrowed mut *self) {
                            defer self->value += 1;
                        }
                    } deferred_value_t;
                    int main(void) {
                        run_deferred();
                        deferred_value_t item = {0};
                        item.increment();
                        return item.value != 1 || deferred_count != 4 || deferred_values[0] != 4 ||
                            deferred_values[1] != 2 || deferred_values[2] != 3 || deferred_values[3] != 1;
                    }
                """.trimIndent(),
                "cleanup-order.cp"
            )
            assertFalse(Regex("\\bdefer\\b").containsMatchIn(source.code), source.code)
            val deferredLine = source.code.lines().indexOfFirst { "record_deferred_value(2)" in it } + 1
            assertTrue(deferredLine > 0, source.code)
            assertEquals(8, source.sourceMap.sourceForGeneratedLine(deferredLine)?.startLine)

            val executable = directory.resolve("defer-order")
            val compile = TccCompiler().compileExecutable(source, executable, emptyList())
            assertEquals(0, compile.exitCode, compile.diagnostics.joinToString("\n"))
            assertEquals(0, ProcessBuilder(executable.toString()).start().waitFor())
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun deferIsRejectedOutsideAFunctionBody() {
        val error = assertThrows(CPlusSyntaxException::class.java) {
            CPlusTranspiler().transpile("defer cleanup();", "outside-defer.cp")
        }
        assertTrue(error.message!!.contains("inside a function body"))
        assertEquals(1, error.sourceSpan?.startLine)
    }

    @Test
    fun raylibDomainImportsExposeNativeHeadersThroughTheUmbrella() {
        val example = findRepositoryFile("stdlib/examples/raylib_hello.cp")
        val raylibDirectory = findRepositoryFile("stdlib/graphics/raylib/core.cp").parent
        val domains = mapOf(
            "core.cp" to listOf("raylib.h"),
            "input.cp" to listOf("raylib.h"),
            "gestures.cp" to listOf("raylib.h"),
            "camera.cp" to listOf("raylib.h"),
            "draw.cp" to listOf("raylib.h"),
            "shapes.cp" to listOf("raylib.h"),
            "textures.cp" to listOf("raylib.h"),
            "text.cp" to listOf("raylib.h"),
            "models.cp" to listOf("raylib.h"),
            "audio.cp" to listOf("raylib.h"),
            "resources.cp" to listOf("raylib.h"),
            "math.cp" to listOf("raylib.h", "raymath.h"),
            "low_level.cp" to listOf("raylib.h", "rlgl.h")
        )
        domains.forEach { (module, headers) ->
            val moduleSource = Files.readString(raylibDirectory.resolve(module))
            headers.forEach { header ->
                assertTrue("#include <$header>" in moduleSource, "$module should include <$header>")
            }
        }

        val generated = CPlusTranspiler().transpile(
            Files.readString(example),
            example.toString(),
            importPaths = CPlusImportPaths(
                standardLibraryRoots = listOf(findRepositoryFile("stdlib/README.md").parent.toAbsolutePath())
            )
        ).code

        listOf("raylib.h", "raymath.h", "rlgl.h").forEach { header ->
            assertTrue("#include <$header>" in generated, "umbrella import omitted <$header>")
        }
        listOf("InitWindow", "WindowShouldClose", "DrawText").forEach { symbol ->
            assertTrue(symbol in generated, "example should use raw Raylib symbol $symbol")
        }
    }

    @Test
    fun raymathFixtureKeepsSystemHeaderAndFunctionBindings() {
        val source = findRepositoryFile("stdlib/tests/raylib_math.cp")
        val fixture = Files.readString(source)
        val generated = CPlusTranspiler().transpile(
            fixture,
            source.toString(),
            importPaths = CPlusImportPaths(
                standardLibraryRoots = listOf(findRepositoryFile("stdlib/README.md").parent.toAbsolutePath())
            )
        ).code
        assertTrue("#include <raymath.h>" in generated)
        assertTrue("Vector3Add" in fixture)
        assertTrue("MatrixMultiply" in fixture)
        assertFalse("Vector3Add" in generated, "@test bodies must not leak into ordinary C output")
    }

    @Test
    fun raylibExampleKeepsExternalHeadersAndLinkFlags() {
        val example = findRepositoryFile("stdlib/examples/raylib_hello.cp")
        val stdlibRoot = findRepositoryFile("stdlib/README.md").parent.toAbsolutePath()
        val generated = CPlusTranspiler().transpile(
            Files.readString(example),
            example.toString(),
            importPaths = CPlusImportPaths(standardLibraryRoots = listOf(stdlibRoot)),
            targetOs = "linux"
        )
        assertTrue("#include <raylib.h>" in generated.code)
        assertTrue("InitWindow" in generated.code)
        assertTrue(generated.compilerOptions.isEmpty(), "Raylib modules do not add linker flags implicitly")
    }

    @Test
    fun stripsTestBlocksFromOrdinaryCOutput() {
        val generated = CPlusTranspiler().transpile(
            """
                int ordinary_value = 4;
                @test "test-only declaration" {
                    CPLUS_TEST_ASSERT(ordinary_value == 4);
                }
            """.trimIndent()
        ).code

        assertTrue("int ordinary_value = 4;" in generated, generated)
        assertFalse("@test" in generated, generated)
        assertFalse("CPLUS_TEST_ASSERT" in generated, generated)
    }

    @Test
    fun cliTestRunsStandardLibraryAndIncludedCFixture() {
        val sourcePath = findRepositoryFile("stdlib/tests/containers.cp")
        val errors = StringBuilder()
        val status = CPlusCli(output = StringBuilder(), errors = errors).run(listOf("test", sourcePath.toString()))

        assertEquals(0, status, errors.toString())
    }

    @Test
    fun cliTestResolvesTheAllocatorLibraryThroughTheStdlibNamespace() {
        val sourcePath = findRepositoryFile("stdlib/tests/allocators.cp")
        val errors = StringBuilder()
        val status = CPlusCli(output = StringBuilder(), errors = errors).run(listOf("test", sourcePath.toString()))

        assertEquals(0, status, errors.toString())
    }

    @Test
    fun cliTestRunsTheStdioFacadeSuite() {
        val sourcePath = findRepositoryFile("stdlib/tests/io.cp")
        val errors = StringBuilder()
        val status = CPlusCli(output = StringBuilder(), errors = errors).run(listOf("test", sourcePath.toString()))

        assertEquals(0, status, errors.toString())
    }

    @Test
    fun cliTestRunsTheCooperativeThreadPoolSuite() {
        val sourcePath = findRepositoryFile("stdlib/tests/thread_pool.cp")
        val output = StringBuilder()
        val errors = StringBuilder()
        val status = CPlusCli(output = output, errors = errors).run(listOf("test", sourcePath.toString()))

        assertEquals(0, status, "${errors}\n${output}")
        assertTrue("thread_pool.cp | 8 |" in output, output.toString())
        assertTrue("0 failed files" in output, output.toString())
    }

    @Test
    fun cliTestRunsTheHttpClientAndServerSuite() {
        val sourcePath = findRepositoryFile("stdlib/tests/http.cp")
        val output = StringBuilder()
        val errors = StringBuilder()
        val status = CPlusCli(output = output, errors = errors).run(listOf("test", sourcePath.toString()))

        assertEquals(0, status, "${errors}\n${output}")
        assertTrue("http.cp | 4 |" in output, output.toString())
        assertTrue("0 failed files" in output, output.toString())
    }

    @Test
    fun cliTestImportsUnchangedCAndRunsRuntimeAssertions() {
        val sourcePath = findRepositoryFile("stdlib/tests/c_import.cp")
        val errors = StringBuilder()
        val status = CPlusCli(output = StringBuilder(), errors = errors).run(listOf("test", sourcePath.toString()))

        assertEquals(0, status, errors.toString())
    }

    @Test
    fun cliTestAggregatesSelectedFixturesAndAssertionsAcrossFiles() {
        val directory = Files.createTempDirectory("cplus-test-aggregate")
        try {
            val first = directory.resolve("first.cp")
            val second = directory.resolve("second.cp")
            Files.writeString(
                first,
                """
                    @test "not selected" {
                        @assert(0);
                    }
                    @test "alpha" {
                        @assert(1 == 1);
                        @assertEquals(2, 2);
                    }
                """.trimIndent()
            )
            Files.writeString(
                second,
                """
                    @test "beta" {
                        @assertEquals(3, 3);
                    }
                """.trimIndent()
            )
            val output = StringBuilder()
            val errors = StringBuilder()
            val status = CPlusCli(output = output, errors = errors).run(
                listOf("test", first.toString(), second.toString(), "alpha", "beta")
            )

            assertEquals(0, status, errors.toString())
            assertTrue("first.cp | 1 | 2 |" in output, output.toString())
            assertTrue("second.cp | 1 | 1 |" in output, output.toString())
            assertTrue("TOTAL: 2 files, 2 fixtures, 3 asserts, 0 failed files" in output, output.toString())
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun cliResolvesProjectAndStandardLibraryImportsFromManifest() {
        val directory = Files.createTempDirectory("cplus-project-imports")
        try {
            val project = directory.resolve("sample")
            val sourceDirectory = project.resolve("src")
            val moduleDirectory = project.resolve("modules")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(moduleDirectory)
            val stdlibPath = findRepositoryFile("stdlib/README.md").parent.toAbsolutePath().normalize()
            Files.writeString(
                project.resolve("cplus.toml"),
                """name = "sample"
                    |version = "0.1.0"
                    |source = "src"
                    |stdlib = "${stdlibPath.toString().replace("\\", "\\\\")}"
                    |module-paths = ["src", "modules"]
                    |dependencies = []
                """.trimMargin()
            )
            Files.writeString(moduleDirectory.resolve("answer.cp"), "int project_answer(void) { return 42; }\n")
            val source = sourceDirectory.resolve("main.cp")
            Files.writeString(
                source,
                """comptime import "stdlib:/memory/xmem";
                    |comptime import "module:/answer";
                    |int main(void) {
                    |    if (xmem_init() != 0) return 1;
                    |    int result = project_answer() == 42 ? 0 : 1;
                    |    xmem_destroy();
                    |    return result;
                    |}
                """.trimMargin()
            )

            val errors = StringBuilder()
            val status = CPlusCli(output = StringBuilder(), errors = errors).run(listOf("run", source.toString()))

            assertEquals(0, status, errors.toString())
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun comptimeFlagsAreCollectedDeduplicatedAndPassedToTcc() {
        val directory = Files.createTempDirectory("cplus-comptime-flags")
        try {
            val imported = directory.resolve("graphics.cp")
            val source = directory.resolve("main.cp")
            Files.writeString(
                imported,
                "comptime flags -DIMPORT_FLAG=1 -lm\n"
            )
            Files.writeString(
                source,
                """comptime import "graphics.cp";
                    |comptime flags -DLOCAL_FLAG=2 -lm
                    |int main(void) {
                    |    return IMPORT_FLAG != 1 || LOCAL_FLAG != 2;
                    |}
                    |@test "declared flags compile test fixtures" {
                    |    @assert(IMPORT_FLAG == 1 && LOCAL_FLAG == 2);
                    |}
                """.trimMargin()
            )

            val transcoded = CPlusTranspiler().transpile(
                Files.readString(source),
                source.toString()
            )
            assertEquals(
                listOf("-DIMPORT_FLAG=1", "-lm", "-DLOCAL_FLAG=2"),
                transcoded.compilerOptions
            )
            assertEquals(
                1,
                transcoded.code.lines().count { it.startsWith("/* cplus compiler flags:") },
                transcoded.code
            )
            assertTrue(
                "/* cplus compiler flags: \"-DIMPORT_FLAG=1\" \"-lm\" \"-DLOCAL_FLAG=2\" */" in transcoded.code,
                transcoded.code
            )
            assertFalse("comptime flags" in transcoded.code, transcoded.code)

            val errors = StringBuilder()
            val status = CPlusCli(output = StringBuilder(), errors = errors)
                .run(listOf("run", source.toString()))
            assertEquals(0, status, errors.toString())

            val testStatus = CPlusCli(output = StringBuilder(), errors = errors)
                .run(listOf("test", source.toString()))
            assertEquals(0, testStatus, errors.toString())
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun comptimeOsBranchesSelectPlatformFlagsAndTranscodeTarget() {
        val directory = Files.createTempDirectory("cplus-platform-flags")
        try {
            val sourceText = """comptime {
                |    @if(os == "linux") {
                |        comptime flags -lraylib -lGL;
                |    } @else if (os == "windows") {
                |        comptime flags -lraylib -lopengl32 -lgdi32 -lwinmm;
                |    } @else if (os == "macos") {
                |        comptime flags -lraylib -framework Cocoa;
                |    } @else {
                |        comptime flags -lportable;
                |    }
                |}
                |int main(void) { return 0; }
            """.trimMargin()
            val expected = mapOf(
                "linux" to listOf("-lraylib", "-lGL"),
                "windows" to listOf("-lraylib", "-lopengl32", "-lgdi32", "-lwinmm"),
                "macos" to listOf("-lraylib", "-framework", "Cocoa"),
                "freebsd" to listOf("-lportable")
            )
            expected.forEach { (targetOs, flags) ->
                val transcoded = CPlusTranspiler().transpile(
                    sourceText,
                    "platform-flags.cp",
                    targetOs = targetOs
                )
                assertEquals(flags, transcoded.compilerOptions, targetOs)
            }

            val raylibExample = findRepositoryFile("stdlib/examples/raylib/tetris.cp")
            val stdlibRoot = findRepositoryFile("stdlib/README.md").parent.toAbsolutePath().normalize()
            val raylibFlags = mapOf(
                "linux" to listOf(
                    "-lraylib", "-lGL", "-lm", "-lpthread", "-ldl", "-lrt", "-lX11", "-lXrandr", "-lXinerama",
                    "-lXcursor", "-lXi"
                ),
                "windows" to listOf("-lraylib", "-lopengl32", "-lgdi32", "-lwinmm", "-lshcore"),
                "macos" to listOf(
                    "-lraylib", "-framework", "Foundation", "-framework", "AppKit", "-framework", "IOKit",
                    "-framework", "OpenGL", "-framework", "CoreVideo", "-framework", "QuartzCore"
                )
            )
            raylibFlags.forEach { (targetOs, flags) ->
                val transcoded = CPlusTranspiler().transpile(
                    Files.readString(raylibExample),
                    raylibExample.toString(),
                    importPaths = CPlusImportPaths(standardLibraryRoots = listOf(stdlibRoot)),
                    targetOs = targetOs
                )
                assertEquals(flags, transcoded.compilerOptions, "Raylib flags for $targetOs")
            }

            val source = directory.resolve("platform.cp")
            val output = directory.resolve("platform.c")
            Files.writeString(source, sourceText)
            val errors = StringBuilder()
            val status = CPlusCli(output = StringBuilder(), errors = errors).run(
                listOf("transcode", source.toString(), "-o", output.toString(), "--target=windows-x86_64")
            )
            assertEquals(0, status, errors.toString())
            assertTrue(
                "/* cplus compiler flags: \"-lraylib\" \"-lopengl32\" \"-lgdi32\" \"-lwinmm\" */" in
                    Files.readString(output)
            )
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun cliNewScaffoldsAProjectWithoutOverwritingExistingFiles() {
        val directory = Files.createTempDirectory("cplus-new-project")
        try {
            val project = directory.resolve("hello")
            val output = StringBuilder()
            val status = CPlusCli(output = output, errors = StringBuilder()).run(listOf("new", project.toString()))

            assertEquals(0, status)
            assertTrue(Files.isRegularFile(project.resolve("cplus.toml")))
            assertTrue(Files.isRegularFile(project.resolve("src/main.cp")))
            assertTrue(Files.isDirectory(project.resolve("modules")))
            assertTrue(Files.isDirectory(project.resolve("tests")))
            assertTrue(output.contains("Created C-plus project"), output.toString())

            val errors = StringBuilder()
            val runStatus = CPlusCli(output = StringBuilder(), errors = errors).run(
                listOf("run", project.resolve("src/main.cp").toString())
            )
            assertEquals(0, runStatus, errors.toString())

            assertThrows(IllegalArgumentException::class.java) {
                CPlusCli(output = StringBuilder(), errors = StringBuilder()).run(listOf("new", project.toString()))
            }
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun runtimeTestAssertionSpellingsLowerIntoHarnessCalls() {
        val generated = CPlusTranspiler().transpileTests(
            """
                @test "runtime values" {
                    int answer = 42;
                    @assert(answer == 42)
                    @assertEquals(answer, 40 + 2)
                }
            """.trimIndent(),
            "assertions.cp"
        ).source.code

        assertTrue("CPLUS_TEST_ASSERT_AT(1, 2, answer == 42);" in generated, generated)
        assertTrue("CPLUS_TEST_ASSERT_EQUALS_AT(2, 2, answer, 40 + 2);" in generated, generated)
        assertFalse("@assert(" in generated, generated)
        assertFalse("@assertEquals(" in generated, generated)
        assertTrue("cplus_test_assert_equals_bytes" in generated, generated)
        assertTrue("given: " in generated, generated)
        assertTrue("expected: " in generated, generated)
        assertTrue("cplus_test_print_value(stdout" in generated, generated)
        assertTrue("CPLUS_TEST_ASSERT_EQUALS_AT(number, total, expected, given)" in generated, generated)
        assertTrue("passed ? \"\\033[1;32m\" : \"\\033[1;31m\"" in generated, generated)
        assertTrue("CPLUS_TEST_FIXTURE_OFFSET" in generated, generated)
        assertTrue("CPLUS_TEST_ASSERTION_OFFSET" in generated, generated)
        assertTrue("BEGIN TEST %d/%d: %s" in generated, generated)
        assertTrue("\\033[1;33m========== BEGIN TEST" in generated, generated)
        assertTrue("\\033[1;32m" in generated, generated)
        assertTrue("\\033[1;31m" in generated, generated)
        assertTrue("printf(\"\\n\\033[1;33m" in generated, generated)
    }

    @Test
    fun runtimeAssertionsAlwaysPrintValuesAndUseGreenRedStatuses() {
        val directory = Files.createTempDirectory("cplus-assert-output")
        try {
            val source = CPlusTranspiler().transpileTests(
                """
                    @test "passing assertion" {
                        int value = 42;
                        int* value_pointer = &value;
                        @assertEquals(42, value)
                        @assert(value == 42)
                        @assertEquals(value_pointer, value_pointer)
                    }
                    @test "failing assertion" {
                        int value = 7;
                        @assertEquals(42, value)
                    }
                """.trimIndent(),
                "assert-output.cp"
            )
            val executable = directory.resolve("assert-output")
            val compile = TccCompiler().compileExecutable(source.source, executable, emptyList())
            assertEquals(0, compile.exitCode, compile.diagnostics.joinToString("\n"))

            val outputPath = directory.resolve("output.txt")
            val exitCode = ProcessBuilder(executable.toString())
                .redirectErrorStream(true)
                .redirectOutput(outputPath.toFile())
                .start()
                .waitFor()
            val output = Files.readString(outputPath)

            assertEquals(1, exitCode, output)
            assertTrue("\u001B[1;32m@assertEquals [1/4] 42, value [PASS]" in output, output)
            assertTrue("given: 42" in output, output)
            assertTrue("expected: 42" in output, output)
            assertTrue("\u001B[1;32m@assert [2/4] value == 42 [PASS]" in output, output)
            assertTrue("given: true" in output, output)
            assertTrue("expected: true" in output, output)
            assertTrue("\u001B[1;32m@assertEquals [3/4] value_pointer, value_pointer [PASS]" in output, output)
            assertTrue(Regex("given: bytes\\[\\d+]=0x[0-9a-f]+", RegexOption.IGNORE_CASE).containsMatchIn(output), output)
            assertTrue("\u001B[1;31m@assertEquals [4/4] 42, value [FAIL]" in output, output)
            assertTrue("given: 7" in output, output)
            assertTrue("expected: 42" in output, output)
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun cliTestFiltersExactNamesAcrossMultipleFiles() {
        val directory = Files.createTempDirectory("cplus-multiple-tests")
        try {
            val cSource = directory.resolve("plain.c")
            val first = directory.resolve("first.cp")
            val second = directory.resolve("second.cp")
            Files.writeString(
                cSource,
                """
                    typedef struct plain_value { int value; } plain_value;
                    int main(void) { return 0; }
                """.trimIndent()
            )
            Files.writeString(
                first,
                """
                    #include "plain.c"
                    @test "plain C works" {
                        plain_value value = (plain_value){42};
                        CPLUS_TEST_ASSERT(value.value == 42);
                    }
                    @test "not selected" { CPLUS_TEST_FAIL("must not run"); }
                """.trimIndent()
            )
            Files.writeString(
                second,
                """
                    @test second file selected { CPLUS_TEST_ASSERT(3 * 7 == 21); }
                """.trimIndent()
            )

            val errors = StringBuilder()
            val status = CPlusCli(output = StringBuilder(), errors = errors).run(
                listOf("test", first.toString(), second.toString(), "plain C works", "second file selected")
            )

            assertEquals(0, status, errors.toString())
            val unknownStatus = CPlusCli(output = StringBuilder(), errors = StringBuilder()).run(
                listOf("test", first.toString(), "missing test")
            )
            assertEquals(2, unknownStatus)
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun cliTestMapsCCompilerErrorsInsideTestBodiesBackToCp() {
        val directory = Files.createTempDirectory("cplus-test-diagnostic")
        try {
            val source = directory.resolve("diagnostic.cp")
            Files.writeString(
                source,
                """
                    @test "mapped failure" {
                        missing_test_symbol();
                    }
                """.trimIndent()
            )
            val errors = StringBuilder()
            val status = CPlusCli(output = StringBuilder(), errors = errors).run(listOf("test", source.toString()))

            assertEquals(1, status, errors.toString())
            assertTrue(errors.contains("${source.toAbsolutePath()}:2"), errors.toString())
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun findRepositoryFile(relativePath: String): java.nio.file.Path =
        sequenceOf(java.nio.file.Path.of(relativePath), java.nio.file.Path.of("..", relativePath))
            .map { it.toAbsolutePath().normalize() }
            .firstOrNull(Files::isRegularFile)
            ?: error("cannot locate repository file $relativePath from ${System.getProperty("user.dir")}")

    @Test
    fun supportsKeywordLedComptimeDeclarationsAndInlineScalarEvaluation() {
        val result = CPlusTranspiler().transpile(
            """
                int prior_function(void) { return 0; }
                int answer = 5;
                comptime int compile_answer = 21;
                comptime int next_answer = compile_answer + 1;
                comptime int @twice(int value) {
                    return value * 2;
                }
                int ordinary_answer = answer;
                int explicit_answer = comptime compile_answer;
                int compile_time_initialized_answer = comptime next_answer;
                int computed_answer = comptime twice(compile_answer) + 1;
            """.trimIndent()
        ).code

        assertTrue("int ordinary_answer = answer;" in result, result)
        assertTrue("int explicit_answer = 21;" in result, result)
        assertTrue("int compile_time_initialized_answer = 22;" in result, result)
        assertTrue("int computed_answer = 43;" in result, result)
        assertTrue("comptime" !in result, result)
    }

    @Test
    fun generatesNamedTypesWithValidatedIdentifierInterpolation() {
        val source = """
            comptime string @typename(type T) {
                return T.name;
            }
            comptime type @list(type T) {
                return @code {
                    struct list_of_@typename(T) {
                        T buffer[100];
                        pub T first(borrowed *self) {
                            return self->buffer[0];
                        }
                    };
                };
            }
            comptime typedef list(int) int_list_t;
            int main(void) {
                int_list_t values = {.buffer = {17}};
                return (&values).first() == 17 ? 0 : 1;
            }
        """.trimIndent()

        val result = CPlusTranspiler().transpile(source, "keyword-type.cp").code
        assertTrue("typedef struct int_list_t" in result, result)
        assertTrue("} int_list_t;" in result, result)
        assertTrue("int int_list__first(borrowed int_list_t *self)" in result, result)
        assertTrue("int_list__first(&values)" in result, result)
        assertTrue("@typename" !in result, result)

        val directory = Files.createTempDirectory("cplus-keyword-type")
        try {
            val sourcePath = directory.resolve("list.cp")
            val executable = directory.resolve("list")
            Files.writeString(sourcePath, source)
            val errors = StringBuilder()
            val status = CPlusCli(output = StringBuilder(), errors = errors).run(
                listOf("run", sourcePath.toString(), "-o", executable.toString())
            )
            assertTrue(status == 0, errors.toString())
            assertTrue(Files.isExecutable(executable), "TinyCC did not produce the named-type executable")
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun compilesForAnExplicitEmbeddedCrossTarget() {
        val payload = TccCompiler::class.java.classLoader
        assumeTrue(
            payload.getResource("native/linux-aarch64/files.list") != null &&
                payload.getResource("native/linux-aarch64/tinycc/sysroot/usr/include/stdio.h") != null,
            "cross executable test requires a Linux ARM64 TinyCC payload with its sysroot"
        )
        val directory = Files.createTempDirectory("cplus-cross-target")
        try {
            val sourcePath = directory.resolve("minimal.cp")
            val executable = directory.resolve("minimal-linux-arm64")
            Files.writeString(sourcePath, "int main(void) { return 0; }")
            val errors = StringBuilder()
            val status = CPlusCli(output = StringBuilder(), errors = errors).run(
                listOf(
                    "compile",
                    sourcePath.toString(),
                    "--target=linux-aarch64",
                    "-o",
                    executable.toString()
                )
            )

            assertEquals(0, status, errors.toString())
            val binary = Files.readAllBytes(executable)
            assertTrue(binary.size > 20, "TinyCC produced an empty cross-target executable")
            assertEquals(0x7f, binary[0].toInt() and 0xff)
            assertEquals('E'.code, binary[1].toInt())
            assertEquals('L'.code, binary[2].toInt())
            assertEquals('F'.code, binary[3].toInt())
            assertEquals(183, binary[18].toInt() and 0xff, "Expected an AArch64 ELF target")
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun rejectsCrossTargetOutputWithTheWrongObjectFormat() {
        assumeTrue(
            TccCompiler::class.java.classLoader.getResource("native/macos-x86_64/files.list") != null,
            "target-format test requires an embedded macOS TinyCC driver"
        )
        val directory = Files.createTempDirectory("cplus-invalid-cross-format")
        try {
            val sourcePath = directory.resolve("minimal.cp")
            val outputPath = directory.resolve("minimal-macos.o")
            Files.writeString(sourcePath, "int main(void) { return 0; }")
            val errors = StringBuilder()
            val status = CPlusCli(output = StringBuilder(), errors = errors).run(
                listOf(
                    "compile",
                    sourcePath.toString(),
                    "--target=macos-x86_64",
                    "-c",
                    "-o",
                    outputPath.toString()
                )
            )

            assertEquals(1, status, errors.toString())
            assertTrue(errors.toString().contains("output format does not match"), errors.toString())
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun rejectsInvalidIdentifierInterpolationAtTheGeneratorCall() {
        val source = """
            comptime string @bad_name(type T) {
                return "not-a-name";
            }
            comptime type @box(type T) {
                return @code {
                    struct box_@bad_name(T) { T value; };
                };
            }
            comptime typedef box(int) box_t;
        """.trimIndent()

        val error = assertThrows(CPlusSyntaxException::class.java) {
            CPlusTranspiler().transpile(source, "bad-splice.cp")
        }
        assertTrue(error.message.orEmpty().contains("not a C identifier"), error.message)
        assertTrue(error.sourceSpan?.file == "bad-splice.cp", error.sourceSpan.toString())
        assertTrue(error.sourceSpan?.startLine == 9, error.sourceSpan.toString())
    }

    @Test
    fun expandsKeywordLedCodeFragmentsOnLaterPasses() {
        val result = CPlusTranspiler().transpile(
            """
                comptime string @typename(type T) {
                    return T.name;
                }
                comptime code @emit_value() {
                    return @code {
                        comptime int late_value = 73;
                        comptime code @emit_box(type T) {
                            return @code {
                                comptime type @late_box(type U) {
                                    return @code {
                                        struct late_box_@typename(U) { U item; };
                                    };
                                }
                                comptime typedef late_box(T) late_box_t;
                            };
                        }
                        comptime {
                            emit_box(int);
                        }
                    };
                }
                comptime emit_value();
                int generated_value = comptime late_value;
                late_box_t generated_box = {.item = 9};
                comptime variable @emit_block_value(int @value) {
                    return int generated_from_block = @value;
                }
                comptime {
                    emit_block_value(11);
                }
            """.trimIndent()
        ).code

        assertTrue("int generated_value = 73;" in result, result)
        assertTrue("typedef struct late_box_int" in result, result)
        assertTrue("late_box_t generated_box" in result, result)
        assertTrue("int generated_from_block = 11;" in result, result)
        assertTrue("late_value" !in result, result)
        assertTrue("comptime" !in result, result)
    }

    @Test
    fun mapsKeywordLedComptimeErrorsFromGeneratedFragments() {
        val source = """
            comptime code @emit_error() {
                return @code {
                    int generated = comptime missing_value;
                };
            }
            comptime emit_error();
        """.trimIndent()

        val error = assertThrows(CPlusSyntaxException::class.java) {
            CPlusTranspiler().transpile(source, "keyword-mapped-error.cp")
        }

        assertTrue(error.message.orEmpty().contains("unknown comptime name missing_value"), error.message)
        assertTrue(error.sourceSpan?.file == "keyword-mapped-error.cp", error.sourceSpan.toString())
        assertTrue(error.sourceSpan?.startLine == 3, error.sourceSpan.toString())
    }

    @Test
    fun importsComptimeValuesWithKeywordLedSyntax() {
        val directory = Files.createTempDirectory("cplus-keyword-import")
        try {
            val imported = directory.resolve("constants.cp")
            Files.writeString(imported, "comptime int imported_value = 73;\nint imported_runtime = 1;\n")
            val source = """
                comptime import "constants.cp";
                int result = comptime imported_value;
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
        assertTrue("#define scratch" in result, result)
        assertTrue("#define hot" in result, result)
        assertTrue("#define warm" in result, result)
        assertTrue("#define cold" in result, result)
        assertTrue("pub int counter__add(borrowed mut counter_t *self, int amount)" in result, result)
        assertTrue("static pub counter_t* counter__alloc_init(int initial)" in result, result)
        assertTrue("counter__add(&counter, 3)" in result, result)
        assertTrue("counter__alloc_init(0)" in result, result)
    }

    @Test
    fun retainsAllocationIntentAnnotationsAsEmptyMacros() {
        val generated = CPlusTranspiler().transpile(
            """
                scratch char* temporary;
                hot node_t* active;
                warm char* text;
                cold unsigned char* snapshot;
            """.trimIndent()
        ).code

        assertTrue("#define scratch" in generated, generated)
        assertTrue("#define hot" in generated, generated)
        assertTrue("#define warm" in generated, generated)
        assertTrue("#define cold" in generated, generated)
        assertTrue("scratch char* temporary;" in generated, generated)
        assertTrue("hot node_t* active;" in generated, generated)
        assertTrue("warm char* text;" in generated, generated)
        assertTrue("cold unsigned char* snapshot;" in generated, generated)
    }

    @Test
    fun compilesAllocationIntentAnnotationsWithoutTheAllocatorModule() {
        val directory = Files.createTempDirectory("cplus-allocation-intents")
        try {
            val source = directory.resolve("intents.cp")
            val executable = directory.resolve("intents")
            Files.writeString(
                source,
                """
                    #include <stddef.h>
                    scratch char* temporary = NULL;
                    hot int* active = NULL;
                    warm char* text = NULL;
                    cold unsigned char* snapshot = NULL;
                    int main(void) {
                        return temporary != NULL || active != NULL || text != NULL || snapshot != NULL;
                    }
                """.trimIndent()
            )
            val errors = StringBuilder()
            val status = CPlusCli(output = StringBuilder(), errors = errors).run(
                listOf("run", source.toString(), "-o", executable.toString())
            )
            assertEquals(0, status, errors.toString())
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun diagnosesDirectAllocationIntentMismatchesAndRetainsIntentMetadata() {
        val result = CPlusTranspiler().transpile(
            """
                int main(void) {
                    hot char* value = alloc_cold(64);
                    return 0;
                }
            """.trimIndent(),
            "intent.cp"
        )

        assertEquals(1, result.allocationAnalysis.diagnostics.size)
        val diagnostic = result.allocationAnalysis.diagnostics.single()
        assertTrue(diagnostic.message.contains("'value' is declared hot but receives memory from alloc_cold()"))
        assertEquals("intent.cp", diagnostic.sourceSpan.file)
        assertEquals(2, diagnostic.sourceSpan.startLine)
        val variable = result.allocationAnalysis.symbols.single { it.name == "value" }
        assertEquals(AllocationSymbolKind.VARIABLE, variable.kind)
        assertEquals(AllocationIntent.HOT, variable.intent)
        assertEquals(AllocationIntent.COLD, variable.knownProvenance)
    }

    @Test
    fun propagatesAllocationProvenanceThroughSimpleAssignments() {
        val result = CPlusTranspiler().transpile(
            """
                int main(void) {
                    scratch char* temporary = alloc_scratch(32);
                    char* alias = temporary;
                    warm char* retained = alias;
                    return 0;
                }
            """.trimIndent()
        )

        assertEquals(1, result.allocationAnalysis.diagnostics.size)
        assertTrue(result.allocationAnalysis.diagnostics.single().message.contains("'retained' is declared warm"), result.allocationAnalysis.diagnostics.toString())
        assertEquals(AllocationIntent.SCRATCH, result.allocationAnalysis.symbols.single { it.name == "alias" }.knownProvenance)
    }

    @Test
    fun checksAnnotatedFunctionReturnsAndAllocationIntentParameters() {
        val result = CPlusTranspiler().transpile(
            """
                pub owned warm char* make_name(void) {
                    return alloc_cold(24);
                }
                pub void consume(borrowed warm char* value) {}
                pub int fill(owned warm char** out) {
                    *out = alloc_cold(8);
                    return 0;
                }
                int main(void) {
                    scratch char* temporary = alloc_scratch(24);
                    consume(temporary);
                    warm char* name = make_name();
                    return 0;
                }
            """.trimIndent(),
            "contracts.cp"
        )

        assertEquals(3, result.allocationAnalysis.diagnostics.size)
        assertTrue(result.allocationAnalysis.diagnostics.any { it.message.contains("function 'make_name' is annotated warm but returns alloc_cold()") })
        assertTrue(result.allocationAnalysis.diagnostics.any { it.message.contains("argument for 'consume.value' is scratch") })
        assertTrue(result.allocationAnalysis.diagnostics.any { it.message.contains("'out' is declared warm but receives memory from alloc_cold()") })
        val returned = result.allocationAnalysis.symbols.single { it.kind == AllocationSymbolKind.FUNCTION_RETURN && it.name == "make_name" }
        assertEquals(AllocationIntent.WARM, returned.intent)
        assertEquals(AllocationOwnership.OWNED, returned.ownership)
        val parameter = result.allocationAnalysis.symbols.single { it.kind == AllocationSymbolKind.PARAMETER && it.name == "value" }
        assertEquals(AllocationIntent.WARM, parameter.intent)
        assertEquals(AllocationOwnership.BORROWED, parameter.ownership)
        val output = result.allocationAnalysis.symbols.single { it.kind == AllocationSymbolKind.PARAMETER && it.name == "out" }
        assertEquals(AllocationIntent.WARM, output.intent)
        assertEquals(AllocationOwnership.OWNED, output.ownership)
    }

    @Test
    fun cliPrintsMappedAllocationIntentWarningsWithoutFailingTranscode() {
        val directory = Files.createTempDirectory("cplus-allocation-warning")
        try {
            val source = directory.resolve("warning.cp")
            val imported = directory.resolve("allocator.cp")
            val generated = directory.resolve("warning.c")
            Files.writeString(imported,
                """
                    void imported_function(void) {
                        hot char* value = alloc_cold(16);
                    }
                """.trimIndent()
            )
            Files.writeString(
                source,
                """
                    comptime import "allocator.cp";
                    int main(void) { return 0; }
                """.trimIndent()
            )
            val errors = StringBuilder()
            val status = CPlusCli(output = StringBuilder(), errors = errors).run(
                listOf("transcode", source.toString(), "-o", generated.toString())
            )

            assertEquals(0, status)
            assertTrue(errors.contains("allocator.cp:2:15: warning: allocation intent mismatch: 'value' is declared hot"), errors.toString())
            assertTrue(Files.isRegularFile(generated))
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun infersMethodReceiverAddressFromStructValueOrPointerSyntax() {
        val source = """
            typedef struct counter_t {
                int value;
                pub int set(borrowed mut *self, int value) {
                    self->value = value;
                    return 0;
                }
                pub int increment(borrowed mut *self) {
                    self->value++;
                    return 0;
                }
                pub int get(borrowed *self) {
                    return self->value;
                }
            } counter_t;

            int main(void) {
                counter_t ob;
                counter_t* obp = &ob;
                ob.set(10);
                obp->increment();
                (&ob).set(11);
                return obp->get() == 11 ? 0 : 1;
            }
        """.trimIndent()

        val generated = CPlusTranspiler().transpile(source).code
        assertTrue("counter__set(&ob, 10)" in generated, generated)
        assertTrue("counter__increment(obp)" in generated, generated)
        assertTrue("counter__set(&ob, 11)" in generated, generated)
        assertTrue("counter__get(obp)" in generated, generated)

        val directory = Files.createTempDirectory("cplus-method-receiver")
        try {
            val sourcePath = directory.resolve("receivers.cp")
            Files.writeString(sourcePath, source)
            val errors = StringBuilder()
            val status = CPlusCli(output = StringBuilder(), errors = errors)
                .run(listOf("run", sourcePath.toString()))
            assertEquals(0, status, errors.toString())
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
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
    fun generatesAndRunsGenericFunctionFromFunctionResultKind() {
        val directory = Files.createTempDirectory("cplus-comptime-function-result")
        try {
            val source = directory.resolve("generated.cp")
            val executable = directory.resolve("generated")
            Files.writeString(
                source,
                """
                    comptime function @comptime_proto_decl_name(type T) {
                        return T some_gen_name(T param) {
                            return param + 2;
                        }
                    }
                    comptime comptime_proto_decl_name(int);
                    int main(void) {
                        return some_gen_name(40) == 42 ? 0 : 1;
                    }
                """.trimIndent()
            )

            val generatedC = try {
                CPlusTranspiler().transpile(Files.readString(source), source.toString()).code
            } catch (error: CPlusSyntaxException) {
                throw AssertionError("${error.message} at ${error.sourceSpan}", error)
            }
            assertTrue("int some_gen_name(int param)" in generatedC, generatedC)
            assertTrue("return param + 2;" in generatedC, generatedC)
            assertTrue(CPlusCli().run(listOf("run", source.toString(), "-o", executable.toString())) == 0)
            assertTrue(Files.isExecutable(executable), "TCC did not produce a generated-function executable")
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun interpolatesTypeNamesInGeneratedGenericFunctionNames() {
        val directory = Files.createTempDirectory("cplus-comptime-mapper-name")
        try {
            val source = directory.resolve("mapper.cp")
            val executable = directory.resolve("mapper")
            Files.writeString(
                source,
                """
                    comptime string @name(type T) {
                        return T.name;
                    }
                    #define mapper__int__to__float mapper_int_to_float
                    comptime function @generic_mapper(type T, type R) {
                        return R mapper__@name(T)__to__@name(R)(R (*mapper_callback)(T, int), T item, int index) {
                            return mapper_callback(item, index);
                        }
                    }
                    comptime generic_mapper(int, float);
                    float add_index(int item, int index) {
                        return (float)(item + index);
                    }
                    int main(void) {
                        return mapper_int_to_float(add_index, 40, 2) == 42.0f ? 0 : 1;
                    }
                """.trimIndent()
            )

            val generatedC = CPlusTranspiler().transpile(Files.readString(source), source.toString()).code
            assertTrue("float mapper__int__to__float(float (*mapper_callback)(int, int), int item, int index)" in generatedC, generatedC)
            assertTrue("#define mapper__int__to__float mapper_int_to_float" in generatedC, generatedC)
            assertTrue("@name" !in generatedC, generatedC)
            val errors = StringBuilder()
            val status = CPlusCli(output = StringBuilder(), errors = errors).run(
                listOf("run", source.toString(), "-o", executable.toString())
            )
            assertTrue(status == 0, errors.toString())
            assertTrue(Files.isExecutable(executable), "TinyCC did not produce the specialized mapper executable")
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
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
            assertTrue(":5:" in errors.toString(), errors.toString())
            assertTrue("pass: lower-method-calls" in errors.toString(), errors.toString())
            assertTrue("pass: tcc-compile" in errors.toString(), errors.toString())
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun usesSystemTccWhenEmbeddedHostPayloadHasNoSysroot() {
        val loader = TccCompiler::class.java.classLoader
        val hostOs = when {
            System.getProperty("os.name").contains("win", ignoreCase = true) -> "windows"
            System.getProperty("os.name").contains("mac", ignoreCase = true) -> "macos"
            else -> "linux"
        }
        val hostArch = when (System.getProperty("os.arch").lowercase()) {
            "amd64", "x86_64", "x64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> "unknown"
        }
        val hostTarget = "$hostOs-$hostArch"
        val sysroot = "native/$hostTarget/tinycc/sysroot/"
        val hostPayload = loader.getResource("native/$hostTarget/files.list")
        val hostSysroot = listOf("${sysroot}usr/include/stdio.h", "${sysroot}include/stdio.h")
            .any { loader.getResource(it) != null }
        assumeTrue(hostPayload != null && !hostSysroot, "test requires a no-sysroots host TinyCC payload")

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
                    listOf("run", source.toString(), "-o", executable.toString(), "-DFLAG=1", "--target=$hostTarget")
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
