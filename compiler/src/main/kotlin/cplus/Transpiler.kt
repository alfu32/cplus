package cplus

/** Converts the supported C-plus syntax into C while retaining source origins. */
class CPlusTranspiler {
    private val sourceManager = SourceManager()

    fun transpile(
        source: String,
        sourceName: String? = null,
        logger: CompilationLogger = SilentCompilationLogger,
        importPaths: CPlusImportPaths = CPlusImportPaths(),
        targetOs: String = CPlusTarget.hostOs()
    ): TranscodedSource = transpileInternal(source, sourceName, logger, testMode = false, importPaths, targetOs).source

    /** Transcodes a source file and its named `@test` blocks into a runnable test program. */
    fun transpileTests(
        source: String,
        sourceName: String? = null,
        logger: CompilationLogger = SilentCompilationLogger,
        importPaths: CPlusImportPaths = CPlusImportPaths(),
        targetOs: String = CPlusTarget.hostOs()
    ): TranscodedTestSource = transpileInternal(source, sourceName, logger, testMode = true, importPaths, targetOs)

    /**
     * Emits the established runnable test harness for fixtures extracted by another frontend.
     * This bridge deliberately reuses only harness/assertion generation; fixture discovery is
     * supplied by the caller and does not invoke the legacy comptime test scanner.
     */
    fun transpileExtractedTests(
        runtime: MappedText,
        fixtures: List<CPlusExtractedTestFixture>,
        logger: CompilationLogger = SilentCompilationLogger
    ): TranscodedTestSource {
        val sourceFile = runtime.firstOrigin()?.file
            ?: fixtures.firstNotNullOfOrNull { it.body.firstOrigin()?.file }
            ?: SourceFile(runtime.text)
        val testBlocks = fixtures.map { fixture ->
            ComptimeTestBlock(fixture.name, fixture.body, sourceFile, fixture.span.startOffset)
        }
        val testProgram = logger.pass("emit-extracted-test-harness") { testProgram(runtime, testBlocks) }
        val annotated = logger.pass("collect-error-annotations") { ErrorAnnotationCollector().collect(testProgram.source) }
        val deferred = logger.pass("lower-defer-statements") { DeferLowerer().lower(annotated.source) }
        val typeNames = logger.pass("collect-struct-types") { StructTypeCollector().collect(deferred.text) }
        val calls = logger.pass("lower-method-calls") { MethodCallLowerer(typeNames).lower(deferred) }
        val structs = logger.pass("lower-struct-methods") { StructLowerer().lower(calls) }
        val errors = logger.pass("lower-try-catch") { TryCatchLowerer(annotated.functions).lower(structs) }
        val allocationAnalysis = logger.pass("allocation-intent-analysis") { AllocationIntentAnalyzer().analyze(errors) }
        val emitted = logger.pass("emit-mapped-c") {
            MappedEmitter(sourceFile).emit(errors, CPlusPreamble.text, allocationAnalysis)
        }
        return TranscodedTestSource(emitted, testProgram.fixtures.map { it.name }, testProgram.fixtures)
    }

    private fun transpileInternal(
        source: String,
        sourceName: String?,
        logger: CompilationLogger,
        testMode: Boolean,
        importPaths: CPlusImportPaths,
        targetOs: String
    ): TranscodedTestSource {
        val sourceFile = sourceManager.open(
            SourceId.named(sourceName ?: "<cplus-input>"),
            source
        ).sourceFile
        val comptime = logger.pass("comptime-resolve") {
            ComptimeCompiler(sourceFile, logger, importPaths, targetOs, sourceManager)
                .compile(resolveTestBodies = testMode)
        }
        val input = if (testMode) {
            logger.pass("collect-tests") { testProgram(comptime.runtime, comptime.tests) }
        } else {
            TestProgram(comptime.runtime, emptyList())
        }

        val annotated = logger.pass("collect-error-annotations") {
            ErrorAnnotationCollector().collect(input.source)
        }

        val deferred = logger.pass("lower-defer-statements") {
            DeferLowerer().lower(annotated.source)
        }

        val typeNames = logger.pass("collect-struct-types") {
            StructTypeCollector().collect(deferred.text)
        }
        val calls = logger.pass("lower-method-calls") {
            MethodCallLowerer(typeNames).lower(deferred)
        }
        val structs = logger.pass("lower-struct-methods") {
            StructLowerer().lower(calls)
        }
        val errors = logger.pass("lower-try-catch") {
            TryCatchLowerer(annotated.functions).lower(structs)
        }
        val allocationAnalysis = logger.pass("allocation-intent-analysis") {
            AllocationIntentAnalyzer().analyze(errors)
        }
        val emitted = logger.pass("emit-mapped-c") {
            MappedEmitter(sourceFile).emit(
                errors,
                CPlusPreamble.text,
                allocationAnalysis,
                comptime.compilerOptions
            ).copy(sourceOrder = comptime.sourceOrder, sourceImports = comptime.imports)
        }
        return TranscodedTestSource(emitted, input.fixtures.map { it.name }, input.fixtures)
    }

    private fun testProgram(
        runtime: MappedText,
        tests: List<ComptimeTestBlock>
    ): TestProgram {
        if (tests.isEmpty()) return TestProgram(runtime, emptyList())

        val assertions = tests.map { test -> testAssertions(test.body) }
        val assertionTotal = assertions.sumOf { it.size }
        val fixtures = tests.mapIndexed { index, test ->
            TranscodedTestFixture(test.name, assertions[index].size)
        }

        val output = MappedTextBuilder()
        output.appendGenerated("#define main cplus_test_original_main\n")
        output.append(runtime)
        if (runtime.text.isNotEmpty() && !runtime.text.endsWith('\n')) output.appendGenerated("\n")
        output.appendGenerated(
            """
            #undef main
            #include <limits.h>
            #include <stddef.h>
            #include <stdio.h>
            #include <stdlib.h>
            #include <string.h>
            enum {
                CPLUS_TEST_VALUE_BYTES,
                CPLUS_TEST_VALUE_BOOL,
                CPLUS_TEST_VALUE_CHAR,
                CPLUS_TEST_VALUE_SIGNED_CHAR,
                CPLUS_TEST_VALUE_SHORT,
                CPLUS_TEST_VALUE_INT,
                CPLUS_TEST_VALUE_LONG,
                CPLUS_TEST_VALUE_LONG_LONG,
                CPLUS_TEST_VALUE_UNSIGNED_CHAR,
                CPLUS_TEST_VALUE_UNSIGNED_SHORT,
                CPLUS_TEST_VALUE_UNSIGNED_INT,
                CPLUS_TEST_VALUE_UNSIGNED_LONG,
                CPLUS_TEST_VALUE_UNSIGNED_LONG_LONG,
                CPLUS_TEST_VALUE_FLOAT,
                CPLUS_TEST_VALUE_DOUBLE,
                CPLUS_TEST_VALUE_LONG_DOUBLE,
                CPLUS_TEST_VALUE_STRING
            };
            #define CPLUS_TEST_VALUE_KIND(value) _Generic((value), \
                _Bool: CPLUS_TEST_VALUE_BOOL, \
                char: CPLUS_TEST_VALUE_CHAR, \
                signed char: CPLUS_TEST_VALUE_SIGNED_CHAR, \
                short: CPLUS_TEST_VALUE_SHORT, \
                int: CPLUS_TEST_VALUE_INT, \
                long: CPLUS_TEST_VALUE_LONG, \
                long long: CPLUS_TEST_VALUE_LONG_LONG, \
                unsigned char: CPLUS_TEST_VALUE_UNSIGNED_CHAR, \
                unsigned short: CPLUS_TEST_VALUE_UNSIGNED_SHORT, \
                unsigned int: CPLUS_TEST_VALUE_UNSIGNED_INT, \
                unsigned long: CPLUS_TEST_VALUE_UNSIGNED_LONG, \
                unsigned long long: CPLUS_TEST_VALUE_UNSIGNED_LONG_LONG, \
                float: CPLUS_TEST_VALUE_FLOAT, \
                double: CPLUS_TEST_VALUE_DOUBLE, \
                long double: CPLUS_TEST_VALUE_LONG_DOUBLE, \
                char*: CPLUS_TEST_VALUE_STRING, \
                const char*: CPLUS_TEST_VALUE_STRING, \
                default: CPLUS_TEST_VALUE_BYTES)
            static void cplus_test_print_bytes(FILE* stream, const void* value, size_t size) {
                fprintf(stream, "bytes[%zu]=0x", size);
                for (size_t index = 0; index < size; index++) fprintf(stream, "%02x", ((const unsigned char*)value)[index]);
            }
            static void cplus_test_print_value(FILE* stream, const void* value, size_t size, int kind) {
                switch (kind) {
                    case CPLUS_TEST_VALUE_BOOL: fprintf(stream, "%s", *(const _Bool*)value ? "true" : "false"); return;
                    case CPLUS_TEST_VALUE_CHAR: fprintf(stream, "%d", (int)*(const char*)value); return;
                    case CPLUS_TEST_VALUE_SIGNED_CHAR: fprintf(stream, "%d", (int)*(const signed char*)value); return;
                    case CPLUS_TEST_VALUE_SHORT: fprintf(stream, "%d", (int)*(const short*)value); return;
                    case CPLUS_TEST_VALUE_INT: fprintf(stream, "%d", *(const int*)value); return;
                    case CPLUS_TEST_VALUE_LONG: fprintf(stream, "%ld", *(const long*)value); return;
                    case CPLUS_TEST_VALUE_LONG_LONG: fprintf(stream, "%lld", *(const long long*)value); return;
                    case CPLUS_TEST_VALUE_UNSIGNED_CHAR: fprintf(stream, "%u", (unsigned int)*(const unsigned char*)value); return;
                    case CPLUS_TEST_VALUE_UNSIGNED_SHORT: fprintf(stream, "%u", (unsigned int)*(const unsigned short*)value); return;
                    case CPLUS_TEST_VALUE_UNSIGNED_INT: fprintf(stream, "%u", *(const unsigned int*)value); return;
                    case CPLUS_TEST_VALUE_UNSIGNED_LONG: fprintf(stream, "%lu", *(const unsigned long*)value); return;
                    case CPLUS_TEST_VALUE_UNSIGNED_LONG_LONG: fprintf(stream, "%llu", *(const unsigned long long*)value); return;
                    case CPLUS_TEST_VALUE_FLOAT: fprintf(stream, "%g", (double)*(const float*)value); return;
                    case CPLUS_TEST_VALUE_DOUBLE: fprintf(stream, "%g", *(const double*)value); return;
                    case CPLUS_TEST_VALUE_LONG_DOUBLE: fprintf(stream, "%Lg", *(const long double*)value); return;
                    case CPLUS_TEST_VALUE_STRING: {
                        const char* string_value = *(const char* const*)value;
                        if (string_value == NULL) fputs("NULL", stream);
                        else fprintf(stream, "\"%s\"", string_value);
                        return;
                    }
                    default: cplus_test_print_bytes(stream, value, size); return;
                }
            }
            static int cplus_test_assertion_offset = 0;
            static int cplus_test_assertion_total = 0;
            static int cplus_test_fixture_offset = 0;
            static int cplus_test_fixture_total = 0;
            static int cplus_test_failure = 0;
            static int cplus_test_environment_int(const char* name, int fallback) {
                const char* value = getenv(name);
                if (value == NULL || *value == '\0') return fallback;
                char* end = NULL;
                long parsed = strtol(value, &end, 10);
                if (end == value || *end != '\0' || parsed < 0 || parsed > INT_MAX) return fallback;
                return (int)parsed;
            }
            static void cplus_test_print_assert_status(int number, int total, const char* name, const char* input, int passed) {
                fprintf(stdout, "%s%s [%d/%d] %s [%s]\033[0m\n",
                    passed ? "\033[1;32m" : "\033[1;31m", name, number, total, input, passed ? "PASS" : "FAIL");
            }
            static void cplus_test_report_assert(int number, int total, const char* expression, _Bool given, const char* file, int line) {
                cplus_test_print_assert_status(number + cplus_test_assertion_offset,
                    cplus_test_assertion_total > 0 ? cplus_test_assertion_total : total,
                    "@assert", expression, given);
                fputs("    given: ", stdout);
                cplus_test_print_value(stdout, &given, sizeof given, CPLUS_TEST_VALUE_KIND(given));
                fputs("\n    expected: true\n", stdout);
                if (!given) fprintf(stdout, "    at %s:%d\n", file, line);
                fflush(stdout);
            }
            static int cplus_test_assert_equals_bytes(const void* expected, size_t expected_size, const void* other, size_t other_size) {
                if (expected_size != other_size) return 0;
                if (expected == other) return 1;
                if (expected == NULL || other == NULL) return 0;
                return memcmp(expected, other, expected_size) == 0;
            }
            #define CPLUS_TEST_ASSERT_AT(number, total, condition) do { \
                _Bool cplus_assert_given = !!(condition); \
                cplus_test_report_assert((number), (total), #condition, cplus_assert_given, __FILE__, __LINE__); \
                if (!cplus_assert_given) { cplus_test_failure = 1; goto cplus_test_finish; } \
            } while (0)
            static void cplus_test_report_assert_equals(int number, int total, const char* expected_expression, const char* given_expression,
                const void* expected, size_t expected_size, int expected_kind, const void* given, size_t given_size, int given_kind,
                int passed, const char* file, int line) {
                char input[1024];
                snprintf(input, sizeof input, "%s, %s", expected_expression, given_expression);
                cplus_test_print_assert_status(number + cplus_test_assertion_offset,
                    cplus_test_assertion_total > 0 ? cplus_test_assertion_total : total,
                    "@assertEquals", input, passed);
                fputs("    given: ", stdout);
                cplus_test_print_value(stdout, given, given_size, given_kind);
                fputs("\n    expected: ", stdout);
                cplus_test_print_value(stdout, expected, expected_size, expected_kind);
                fputc('\n', stdout);
                if (!passed) fprintf(stdout, "    at %s:%d\n", file, line);
                fflush(stdout);
            }
            #define CPLUS_TEST_ASSERT_EQUALS_AT(number, total, expected, given) do { \
                __typeof__(expected) cplus_expected_value = (expected); \
                __typeof__(given) cplus_given_value = (given); \
                int cplus_assert_passed = cplus_test_assert_equals_bytes(&cplus_expected_value, sizeof cplus_expected_value, &cplus_given_value, sizeof cplus_given_value); \
                cplus_test_report_assert_equals((number), (total), #expected, #given, \
                    &cplus_expected_value, sizeof cplus_expected_value, CPLUS_TEST_VALUE_KIND(cplus_expected_value), \
                    &cplus_given_value, sizeof cplus_given_value, CPLUS_TEST_VALUE_KIND(cplus_given_value), \
                    cplus_assert_passed, __FILE__, __LINE__); \
                if (!cplus_assert_passed) { cplus_test_failure = 1; goto cplus_test_finish; } \
            } while (0)
            #define CPLUS_TEST_ASSERT(condition) CPLUS_TEST_ASSERT_AT(0, 0, condition)
            #define CPLUS_TEST_ASSERT_EQUALS(expected, given) CPLUS_TEST_ASSERT_EQUALS_AT(0, 0, expected, given)
            #define CPLUS_TEST_FAIL(message) do { fprintf(stderr, "test failure: %s\n", (message)); cplus_test_failure = 1; goto cplus_test_finish; } while (0)
            """.trimIndent() + "\n",
        )

        tests.forEachIndexed { index, test ->
            output.appendGenerated("static void cplus_test_$index(void) {\n    cplus_test_failure = 0;\n")
            output.append(lowerTestAssertions(test.body, assertions[index], 1, assertions[index].size))
            if (test.body.text.isNotEmpty() && !test.body.text.endsWith('\n')) output.appendGenerated("\n")
            output.appendGenerated("cplus_test_finish:\n    ;\n}\n\n")
        }

        output.appendGenerated(
            """
            static int cplus_test_requested(const char* name, int argc, char** argv) {
                if (argc <= 1) return 1;
                for (int i = 1; i < argc; i++) {
                    if (strcmp(argv[i], name) == 0) return 1;
                }
                return 0;
            }

            int main(int argc, char** argv) {
                int selected = 0;
                int failed = 0;
                int assertions_before_selected = 0;
                int file_assertion_offset = cplus_test_environment_int("CPLUS_TEST_ASSERTION_OFFSET", 0);
                cplus_test_assertion_offset = file_assertion_offset;
                cplus_test_assertion_total = cplus_test_environment_int("CPLUS_TEST_ASSERTION_TOTAL", $assertionTotal);
                cplus_test_fixture_offset = cplus_test_environment_int("CPLUS_TEST_FIXTURE_OFFSET", 0);
                cplus_test_fixture_total = cplus_test_environment_int("CPLUS_TEST_FIXTURE_TOTAL", ${tests.size});
            """.trimIndent() + "\n"
        )
        tests.forEachIndexed { index, test ->
            val literal = cString(test.name)
            output.appendGenerated(
                """
                if (cplus_test_requested($literal, argc, argv)) {
                    selected++;
                    int fixture_number = cplus_test_fixture_offset + selected;
                    cplus_test_assertion_offset = file_assertion_offset + assertions_before_selected;
                    printf("\n\033[1;33m========== BEGIN TEST %d/%d: %s ==========\033[0m\n", fixture_number, cplus_test_fixture_total, $literal);
                    fflush(stdout);
                    cplus_test_$index();
                    int result = cplus_test_failure;
                    printf("========== END TEST %d/%d: %s [%s%s\033[0m] ==========\n", fixture_number, cplus_test_fixture_total, $literal, result == 0 ? "\033[1;32m" : "\033[1;31m", result == 0 ? "PASS" : "FAIL");
                    if (result != 0) failed++;
                    assertions_before_selected += ${assertions[index].size};
                }
                """.trimIndent() + "\n"
            )
        }
        output.appendGenerated(
            """
                if (selected == 0) {
                    fprintf(stderr, "no tests matched the requested names\n");
                    return 2;
                }
                printf("%s========== TEST SUMMARY: %d selected, %d failed ==========\033[0m\n", failed == 0 ? "\033[1;32m" : "\033[1;31m", selected, failed);
                return failed == 0 ? 0 : 1;
            }
            """.trimIndent() + "\n"
        )
        return TestProgram(output.build(), fixtures)
    }

    private data class TestProgram(val source: MappedText, val fixtures: List<TranscodedTestFixture>)

    private fun testAssertions(body: MappedText): List<TestAssertionInvocation> {
        val masked = SourceMasker.mask(body.text)
        val assertions = mutableListOf<TestAssertionInvocation>()
        var index = 0

        while (index < body.text.length) {
            val isAnnotation = masked[index] == '@'
            val isIdentifier = masked[index] == '_' || masked[index].isLetter()
            if ((!isAnnotation && !isIdentifier) || (isAnnotation && body.text.getOrNull(index + 1)?.let { it == '_' || it.isLetter() } != true)) {
                index++
                continue
            }

            val tokenStart = if (isAnnotation) index + 1 else index
            var nameEnd = tokenStart + 1
            while (nameEnd < masked.length && masked[nameEnd].isIdentifierPart()) nameEnd++
            val name = body.text.substring(tokenStart, nameEnd)
            val macro = when {
                isAnnotation && name == "assert" -> "CPLUS_TEST_ASSERT_AT"
                isAnnotation && name == "assertEquals" -> "CPLUS_TEST_ASSERT_EQUALS_AT"
                !isAnnotation && name == "CPLUS_TEST_ASSERT" -> "CPLUS_TEST_ASSERT_AT"
                !isAnnotation && name == "CPLUS_TEST_ASSERT_EQUALS" -> "CPLUS_TEST_ASSERT_EQUALS_AT"
                else -> {
                    index = nameEnd
                    continue
                }
            }
            val open = Delimiters.skipWhitespace(masked, nameEnd)
            if (open >= masked.length || masked[open] != '(') {
                index = nameEnd
                continue
            }
            val close = Delimiters.match(masked, open, '(', ')')
            if (close < 0) throw assertionSyntax("unclosed @$name assertion", body, index)
            val arguments = splitAssertionArguments(body.text.substring(open + 1, close))
            val expectedCount = if (macro == "CPLUS_TEST_ASSERT_AT") 1 else 2
            if (arguments.size != expectedCount || arguments.any(String::isBlank)) {
                val spelling = if (isAnnotation) "@$name" else name
                throw assertionSyntax("$spelling expects $expectedCount argument${if (expectedCount == 1) "" else "s"}", body, index)
            }

            assertions += TestAssertionInvocation(index, close, macro, arguments)
            index = close + 1
        }
        return assertions
    }

    private fun lowerTestAssertions(
        body: MappedText,
        assertions: List<TestAssertionInvocation>,
        firstNumber: Int,
        total: Int
    ): MappedText {
        val output = MappedTextBuilder()
        var cursor = 0
        assertions.forEachIndexed { assertionIndex, assertion ->
            output.append(body, cursor, assertion.start)
            val arguments = assertion.arguments
            output.appendGenerated(
                "${assertion.macro}(${firstNumber + assertionIndex}, $total, ${arguments.joinToString(", ")})",
                body.originAt(assertion.start)
            )
            var semicolon = assertion.close + 1
            while (semicolon < body.text.length && body.text[semicolon].isWhitespace()) semicolon++
            if (semicolon < body.text.length && body.text[semicolon] == ';') {
                output.append(body, assertion.close + 1, semicolon + 1)
                cursor = semicolon + 1
            } else {
                output.appendGenerated(";", body.originAt(assertion.start))
                cursor = assertion.close + 1
            }
        }
        output.append(body, cursor, body.text.length)
        return output.build()
    }

    private data class TestAssertionInvocation(
        val start: Int,
        val close: Int,
        val macro: String,
        val arguments: List<String>
    )

    private fun splitAssertionArguments(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val masked = SourceMasker.mask(text)
        val arguments = mutableListOf<String>()
        var start = 0
        var depth = 0
        masked.forEachIndexed { index, character ->
            when (character) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    arguments += text.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        arguments += text.substring(start).trim()
        return arguments
    }

    private fun assertionSyntax(message: String, body: MappedText, offset: Int): CPlusSyntaxException {
        val origin = body.originAt(offset)
        return CPlusSyntaxException(message, origin?.file?.span(origin.offset))
    }

    private fun cString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }
}

data class TranscodedTestSource(
    val source: TranscodedSource,
    val testNames: List<String>,
    val fixtures: List<TranscodedTestFixture>
)

data class TranscodedTestFixture(val name: String, val assertionCount: Int)

class CPlusSyntaxException(
    message: String,
    val sourceSpan: SourceSpan? = null
) : IllegalArgumentException(message)

private object CPlusPreamble {
    val text = """
        /* C-plus source annotations are intentionally retained in generated C. */
        #ifndef CPLUS_ANNOTATIONS_DEFINED
        #define CPLUS_ANNOTATIONS_DEFINED
        #define pub
        #define priv
        #define mut
        #define borrowed
        #define owned
        #define stat
        #define scratch
        #define hot
        #define warm
        #define cold
        #endif

    """.trimIndent() + "\n\n"
}

private class StructTypeCollector {
    private val structStart = Regex("\\btypedef\\s+struct(?:\\s+([A-Za-z_]\\w*))?\\s*\\{")
    private val aliasAfterStruct = Regex("\\s*([A-Za-z_]\\w*)\\s*;")

    fun collect(source: String): Set<String> {
        val masked = SourceMasker.mask(source)
        val types = linkedSetOf<String>()
        var search = 0
        while (true) {
            val match = structStart.find(masked, search) ?: break
            val openBrace = masked.indexOf('{', match.range.first)
            val closeBrace = Delimiters.match(masked, openBrace, '{', '}')
            if (openBrace < 0 || closeBrace < 0) break
            val alias = aliasAfterStruct.find(masked, closeBrace + 1) ?: break
            types += alias.groupValues[1]
            search = alias.range.last + 1
        }
        return types
    }
}

private class StructLowerer {
    private val structStart = Regex("\\btypedef\\s+struct(?:\\s+([A-Za-z_]\\w*))?\\s*\\{")
    private val aliasAfterStruct = Regex("\\s*([A-Za-z_]\\w*)\\s*;")

    fun lower(input: MappedText): MappedText {
        val masked = SourceMasker.mask(input.text)
        val output = MappedTextBuilder()
        var cursor = 0
        var search = 0

        while (true) {
            val match = structStart.find(masked, search) ?: break
            val openBrace = masked.indexOf('{', match.range.first)
            val closeBrace = Delimiters.match(masked, openBrace, '{', '}')
            if (openBrace < 0 || closeBrace < 0) break
            val alias = aliasAfterStruct.find(masked, closeBrace + 1) ?: break

            output.append(input, cursor, match.range.first)
            val body = input.slice(openBrace + 1, closeBrace)
            val members = StructMembers.extract(body, alias.groupValues[1])
            if (members.methods.isEmpty()) {
                output.append(input, match.range.first, alias.range.last + 1)
            } else {
                val origin = input.originAt(match.range.first)
                output.appendGenerated("typedef struct ${alias.groupValues[1]} {", origin)
                output.append(members.fields)
                output.appendGenerated("\n} ${alias.groupValues[1]};\n\n", origin)
                members.methods.forEachIndexed { index, method ->
                    if (index > 0) output.appendGenerated("\n\n")
                    output.append(method)
                }
                output.appendGenerated("\n", origin)
            }

            cursor = alias.range.last + 1
            search = cursor
        }

        output.append(input, cursor, input.text.length)
        return output.build()
    }
}

private data class StructMembers(
    val fields: MappedText,
    val methods: List<MappedText>
) {
    companion object {
        fun extract(body: MappedText, typeName: String): StructMembers {
            val masked = SourceMasker.mask(body.text)
            val fields = MappedTextBuilder()
            val methods = mutableListOf<MappedText>()
            var segmentStart = 0
            var index = 0

            while (index < body.text.length) {
                when {
                    masked[index] == '(' -> {
                        val close = Delimiters.match(masked, index, '(', ')')
                        if (close >= 0) {
                            val after = Delimiters.skipWhitespace(masked, close + 1)
                            if (after < body.text.length && (masked[after] == '{' || masked[after] == ';')) {
                                // Inspect only the text before the candidate's outer
                                // parameter list. This permits function-pointer
                                // parameters inside a method without mistaking their
                                // nested parentheses for a struct-level method.
                                val header = body.text.substring(segmentStart, index).trim()
                                if (looksLikeMethod(header)) {
                                    val end = if (masked[after] == '{') {
                                        val methodClose = Delimiters.match(masked, after, '{', '}')
                                        if (methodClose < 0) {
                                            throw CPlusSyntaxException("unclosed method body in struct $typeName")
                                        }
                                        methodClose + 1
                                    } else {
                                        after + 1
                                    }
                                    methods += MethodLowerer.lower(body.slice(segmentStart, end), typeName)
                                    segmentStart = end
                                    index = end
                                    continue
                                }
                            }
                        }
                    }

                    masked[index] == ';' -> {
                        fields.append(body, segmentStart, index + 1)
                        segmentStart = index + 1
                    }
                }
                index++
            }

            fields.append(body, segmentStart, body.text.length)
            return StructMembers(fields.build(), methods)
        }

        private fun looksLikeMethod(header: String): Boolean {
            if (header.contains("(*") || header.contains("(&")) return false
            val name = Regex("([A-Za-z_]\\w*)\\s*$").find(header)?.groupValues?.get(1) ?: return false
            return name !in setOf("if", "for", "while", "switch")
        }
    }
}

internal object MethodLowerer {
    fun lower(method: MappedText, typeName: String): MappedText {
        val open = method.text.indexOf('(')
        val masked = SourceMasker.mask(method.text)
        val close = Delimiters.match(masked, open, '(', ')')
        if (open < 0 || close < 0) throw CPlusSyntaxException("malformed method in struct $typeName")

        val prefix = method.text.substring(0, open)
        val nameMatch = Regex("([A-Za-z_]\\w*)\\s*$").find(prefix)
            ?: throw CPlusSyntaxException("method is missing a name in struct $typeName")
        val methodName = nameMatch.groupValues[1]
        val annotations = prefix.substring(0, nameMatch.range.first)
        val isStatic = Regex("\\b(?:static|stat)\\b").containsMatchIn(annotations)
        val returnType = annotations.replace(Regex("\\bstatic\\b"), "").trim()
        if (returnType.isEmpty()) throw CPlusSyntaxException("method $methodName is missing a return type")

        val parameterStart = open + 1
        val parameterRanges = splitParameterRanges(method.text.substring(parameterStart, close))
        val firstParameter = parameterRanges.firstOrNull()?.let {
            method.text.substring(parameterStart + it.first, parameterStart + it.last + 1)
        }
        val firstIsSelf = !isStatic && firstParameter?.let { Regex("\\bself\\b").containsMatchIn(it) } == true
        val output = MappedTextBuilder()
        val headerOrigin = method.originAt(nameMatch.range.first) ?: method.firstOrigin()
        output.appendGenerated(if (isStatic) "static " else "", headerOrigin)
        output.appendGenerated("$returnType ", headerOrigin)
        output.appendGenerated("${typeStem(typeName)}__$methodName", method.originAt(nameMatch.range.first))
        output.appendGenerated("(", method.originAt(open))

        if (firstIsSelf) {
            val first = parameterRanges.first()
            val firstText = method.text.substring(parameterStart + first.first, parameterStart + first.last + 1)
            val markers = Regex("\\b(?:pub|priv|mut|borrowed|owned|stat)\\b")
                .findAll(firstText)
                .map { it.value }
                .toList()
                .joinToString(" ")
            val selfOrigin = method.originAt(parameterStart + first.first)
            output.appendGenerated(
                listOf(markers, "$typeName *self").filter { it.isNotEmpty() }.joinToString(" "),
                selfOrigin
            )
            parameterRanges.drop(1).forEachIndexed { index, range ->
                output.appendGenerated(", ", method.originAt(parameterStart + range.first))
                appendTrimmed(output, method, parameterStart + range.first, parameterStart + range.last + 1)
            }
        } else {
            output.append(method, parameterStart, close)
        }

        output.appendGenerated(")", method.originAt(close))
        output.append(method, close + 1, method.text.length)
        return output.build()
    }

    private fun appendTrimmed(output: MappedTextBuilder, value: MappedText, start: Int, end: Int) {
        var left = start
        var right = end
        while (left < right && value.text[left].isWhitespace()) left++
        while (right > left && value.text[right - 1].isWhitespace()) right--
        output.append(value, left, right)
    }

    private fun splitParameterRanges(parameters: String): List<IntRange> {
        if (parameters.trim().isEmpty()) return emptyList()
        val masked = SourceMasker.mask(parameters)
        val result = mutableListOf<IntRange>()
        var start = 0
        var depth = 0
        for (index in parameters.indices) {
            when (masked[index]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    result += start until index
                    start = index + 1
                }
            }
        }
        result += start until parameters.length
        return result
    }

    private fun typeStem(typeName: String): String = if (typeName.endsWith("_t")) typeName.dropLast(2) else typeName
}

private class MethodCallLowerer(private val structTypes: Set<String>) {
    private data class Scope(val start: Int, var end: Int)
    private data class VariableType(
        val name: String,
        val type: String,
        val isPointer: Boolean,
        val offset: Int,
        val scope: Int
    )

    private var scopes: List<Scope> = emptyList()
    private var variableTypes: List<VariableType> = emptyList()

    fun lower(source: MappedText): MappedText {
        if (structTypes.isEmpty()) return source
        collectVariableTypes(source.text)
        return lowerCalls(source, 0)
    }

    private fun collectVariableTypes(source: String) {
        val masked = SourceMasker.mask(source)
        val builtScopes = mutableListOf(Scope(0, source.length))
        val stack = mutableListOf(0)
        masked.forEachIndexed { index, character ->
            when (character) {
                '{' -> {
                    builtScopes += Scope(index + 1, source.length)
                    stack += builtScopes.lastIndex
                }
                '}' -> if (stack.size > 1) builtScopes[stack.removeAt(stack.lastIndex)].end = index
            }
        }
        scopes = builtScopes
        val alternatives = structTypes.joinToString("|") { Regex.escape(it) }
        val declaration = Regex("\\b($alternatives)\\s*(\\*+)?\\s*([A-Za-z_]\\w*)\\b")
        variableTypes = declaration.findAll(masked).map { match ->
            val scope = builtScopes.indices
                .filter { match.range.first in builtScopes[it].start..builtScopes[it].end }
                .maxByOrNull { builtScopes[it].start } ?: 0
            VariableType(
                match.groupValues[3],
                match.groupValues[1],
                match.groupValues[2].isNotEmpty(),
                match.range.first,
                scope
            )
        }.toList()
    }

    private fun lowerCalls(source: MappedText, baseOffset: Int): MappedText {
        val masked = SourceMasker.mask(source.text)
        val output = MappedTextBuilder()
        var copyFrom = 0
        var index = 0

        while (index < source.text.length) {
            val operatorStart = when {
                masked[index] == '.' -> index
                masked[index] == '>' && index > 0 && masked[index - 1] == '-' -> index - 1
                else -> null
            }
            if (operatorStart == null) {
                index++
                continue
            }

            val isPointerAccess = masked[operatorStart] == '-'
            val methodStart = Delimiters.skipWhitespace(masked, index + 1)
            val methodMatch = Regex("[A-Za-z_]\\w*").find(masked, methodStart)
            if (methodMatch == null || methodMatch.range.first != methodStart) {
                index++
                continue
            }
            val open = Delimiters.skipWhitespace(masked, methodMatch.range.last + 1)
            if (open >= source.text.length || masked[open] != '(') {
                index++
                continue
            }
            val close = Delimiters.match(masked, open, '(', ')')
            if (close < 0) throw CPlusSyntaxException("unclosed method call ${methodMatch.value}")

            val leftStart = receiverStart(masked, operatorStart) ?: run {
                index++
                continue
            }
            val left = source.text.substring(leftStart, operatorStart).trim()
            val receiver = receiverType(left, baseOffset + leftStart)
            val typeName = left.takeIf { !isPointerAccess && it in structTypes } ?: receiver?.type
            if (typeName == null) {
                index++
                continue
            }

            val args = lowerCalls(source.slice(open + 1, close), baseOffset + open + 1)
            val stem = if (typeName.endsWith("_t")) typeName.dropLast(2) else typeName
            val origin = source.originAt(leftStart)
            val replacement = MappedTextBuilder()
            replacement.appendGenerated("${stem}__${methodMatch.value}(", origin)
            if (left in structTypes) {
                replacement.append(args)
            } else {
                val receiverExpression = left.removeSurrounding("(", ")").trim()
                val receiverArgument = when {
                    isPointerAccess || receiverExpression.startsWith("&") -> receiverExpression
                    receiver?.isPointer == true && receiverExpression.matches(Regex("[A-Za-z_]\\w*")) ->
                        receiverExpression
                    receiverExpression.matches(Regex("[A-Za-z_]\\w*")) -> "&$receiverExpression"
                    else -> "&($receiverExpression)"
                }
                replacement.appendGenerated(receiverArgument, origin)
                if (args.text.trim().isNotEmpty()) replacement.appendGenerated(", ", origin)
                replacement.append(args)
            }
            replacement.appendGenerated(")", source.originAt(close))

            output.append(source, copyFrom, leftStart)
            output.append(replacement.build())
            copyFrom = close + 1
            index = close + 1
        }

        output.append(source, copyFrom, source.text.length)
        return output.build()
    }

    private fun receiverType(left: String, offset: Int): VariableType? {
        val expression = left.removeSurrounding("(", ")").trim()
        val identifier = Regex("[A-Za-z_]\\w*").findAll(expression).lastOrNull()?.value ?: return null
        val activeScopes = scopes.indices
            .filter { offset in scopes[it].start..scopes[it].end }
            .sortedByDescending { scopes[it].start }
        activeScopes.forEach { scope ->
            variableTypes.asSequence()
                .filter { it.name == identifier && it.scope == scope && it.offset <= offset }
                .maxByOrNull { it.offset }
                ?.let { return it }
        }
        return null
    }

    private fun receiverStart(masked: String, dot: Int): Int? {
        var cursor = dot - 1
        while (cursor >= 0 && masked[cursor].isWhitespace()) cursor--
        if (cursor < 0) return null
        return if (masked[cursor] == ')') {
            Delimiters.opening(masked, cursor, '(', ')')
        } else if (masked[cursor].isIdentifierPart()) {
            while (cursor >= 0 && masked[cursor].isIdentifierPart()) cursor--
            cursor + 1
        } else {
            null
        }
    }
}

internal object SourceMasker {
    fun mask(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        var state = State.CODE
        while (index < chars.size) {
            when (state) {
                State.CODE -> when {
                    chars[index] == '/' && index + 1 < chars.size && chars[index + 1] == '/' -> {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        index += 2
                        state = State.LINE_COMMENT
                    }
                    chars[index] == '/' && index + 1 < chars.size && chars[index + 1] == '*' -> {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        index += 2
                        state = State.BLOCK_COMMENT
                    }
                    chars[index] == '"' -> {
                        chars[index] = ' '
                        index++
                        state = State.STRING
                    }
                    chars[index] == '\'' -> {
                        chars[index] = ' '
                        index++
                        state = State.CHAR
                    }
                    else -> index++
                }
                State.LINE_COMMENT -> {
                    if (chars[index] == '\n') state = State.CODE else chars[index] = ' '
                    index++
                }
                State.BLOCK_COMMENT -> {
                    if (chars[index] == '*' && index + 1 < chars.size && chars[index + 1] == '/') {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        index += 2
                        state = State.CODE
                    } else {
                        if (chars[index] != '\n') chars[index] = ' '
                        index++
                    }
                }
                State.STRING -> {
                    if (chars[index] == '\\') {
                        chars[index] = ' '
                        if (index + 1 < chars.size && chars[index + 1] != '\n') chars[index + 1] = ' '
                        index += 2
                    } else if (chars[index] == '"') {
                        chars[index] = ' '
                        index++
                        state = State.CODE
                    } else {
                        if (chars[index] != '\n') chars[index] = ' '
                        index++
                    }
                }
                State.CHAR -> {
                    if (chars[index] == '\\') {
                        chars[index] = ' '
                        if (index + 1 < chars.size && chars[index + 1] != '\n') chars[index + 1] = ' '
                        index += 2
                    } else if (chars[index] == '\'') {
                        chars[index] = ' '
                        index++
                        state = State.CODE
                    } else {
                        if (chars[index] != '\n') chars[index] = ' '
                        index++
                    }
                }
            }
        }
        return String(chars)
    }

    private enum class State { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR }
}

internal object Delimiters {
    fun match(source: String, openIndex: Int, open: Char, close: Char): Int {
        if (openIndex < 0 || openIndex >= source.length || source[openIndex] != open) return -1
        var depth = 0
        for (index in openIndex until source.length) {
            when (source[index]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    fun opening(source: String, closeIndex: Int, open: Char, close: Char): Int? {
        var depth = 0
        for (index in closeIndex downTo 0) {
            when (source[index]) {
                close -> depth++
                open -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    fun skipWhitespace(source: String, start: Int): Int {
        var index = start
        while (index < source.length && source[index].isWhitespace()) index++
        return index
    }
}

internal fun Char.isIdentifierPart(): Boolean = this == '_' || isLetterOrDigit()
