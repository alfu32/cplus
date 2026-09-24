#include <string.h>

comptime import "stdlib:/io/file.cp";

@test "stdio facade formats and parses strings" {
    char formatted[32];
    int length = file_t.snprintf(formatted, sizeof(formatted), "%s:%d", "rank", 17);
    @assertEquals(7, length);
    @assert(strcmp(formatted, "rank:17") == 0);

    char label[16];
    int rank = 0;
    @assertEquals(2, file_t.sscanf(formatted, "%15[^:]:%d", label, &rank));
    @assert(strcmp(label, "rank") == 0);
    @assertEquals(17, rank);
}

@test "stdio facade preserves fopen failure results" {
    FILE* stream = file_t.fopen("", "r");
    @assert(stream == NULL);
}

@test "stdio facade writes reads and closes a temporary file" {
    FILE* stream = file_t.tmpfile();
    @assert(stream != NULL);
    defer file_t.fclose(stream);

    const char input[] = "file facade";
    size_t input_size = sizeof(input) - 1;
    @assertEquals(input_size, file_t.fwrite(input, 1, input_size, stream));
    @assertEquals(0, file_t.fflush(stream));
    @assertEquals((long)input_size, file_t.ftell(stream));

    file_t.rewind(stream);
    char output[sizeof(input)];
    memset(output, 0, sizeof(output));
    @assertEquals(input_size, file_t.fread(output, 1, input_size, stream));
    @assert(strcmp(input, output) == 0);
    @assertEquals(EOF, file_t.fgetc(stream));
    @assert(file_t.feof(stream));

    file_t.clearerr(stream);
    @assert(!file_t.feof(stream));
}

@test "stdio facade forwards formatted file operations" {
    FILE* stream = file_t.tmpfile();
    @assert(stream != NULL);
    defer file_t.fclose(stream);

    @assertEquals(7, file_t.fprintf(stream, "%s %d", "item", 42));
    file_t.rewind(stream);

    char label[16];
    int value = 0;
    @assertEquals(2, file_t.fscanf(stream, "%15s %d", label, &value));
    @assert(strcmp(label, "item") == 0);
    @assertEquals(42, value);
}
