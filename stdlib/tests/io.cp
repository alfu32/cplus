#include <string.h>

comptime import "stdlib:/io/file.cp";

@test "var_io facade formats and parses strings" {
    char formatted[32];
    int length = var_io_t.snprintf(formatted, sizeof(formatted), "%s:%d", "rank", 17);
    @assertEquals(7, length);
    @assert(strcmp(formatted, "rank:17") == 0);

    char label[16];
    int rank = 0;
    @assertEquals(2, var_io_t.sscanf(formatted, "%15[^:]:%d", label, &rank));
    @assert(strcmp(label, "rank") == 0);
    @assertEquals(17, rank);
}

@test "var_io facade exposes standard stream handles" {
    var_io_t streams = var_io_t.standard();
    @assert(streams.input == stdin);
    @assert(streams.output == stdout);
    @assert(streams.error == stderr);
}

@test "stdio compatibility facade retains raw FILE operations" {
    FILE* stream = stdio_t.tmpfile();
    @assert(stream != NULL);
    defer stdio_t.fclose(stream);

    const char input[] = "raw stream";
    size_t input_size = sizeof(input) - 1;
    @assertEquals(input_size, stdio_t.fwrite(input, 1, input_size, stream));
}

@test "file facade preserves fopen failure results" {
    file_t stream = file_t.fopen("", "r");
    @assert(stream.stream == NULL);
}

@test "file facade close clears the stream handle" {
    file_t stream = file_t.tmpfile();
    @assert(stream.stream != NULL);
    @assertEquals(0, stream.fclose());
    @assert(stream.stream == NULL);
    @assertEquals(EOF, stream.fclose());
}

@test "file facade writes reads and closes a temporary file" {
    file_t stream = file_t.tmpfile();
    @assert(stream.stream != NULL);
    defer stream.fclose();

    const char input[] = "file facade";
    size_t input_size = sizeof(input) - 1;
    @assertEquals(input_size, stream.fwrite(input, 1, input_size));
    @assertEquals(0, stream.fflush());
    @assertEquals((long)input_size, stream.ftell());

    stream.rewind();
    char output[sizeof(input)];
    memset(output, 0, sizeof(output));
    @assertEquals(input_size, stream.fread(output, 1, input_size));
    @assert(strcmp(input, output) == 0);
    @assertEquals(EOF, stream.fgetc());
    @assert(stream.feof());

    stream.clearerr();
    @assert(!stream.feof());
}

@test "file facade forwards formatted stream operations" {
    file_t stream = file_t.tmpfile();
    @assert(stream.stream != NULL);
    defer stream.fclose();

    @assertEquals(7, stream.fprintf("%s %d", "item", 42));
    stream.rewind();

    char label[16];
    int value = 0;
    @assertEquals(2, stream.fscanf("%15s %d", label, &value));
    @assert(strcmp(label, "item") == 0);
    @assertEquals(42, value);
}
