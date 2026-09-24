#include <string.h>

comptime import "stdlib:/io/file.cp";

@test "var_io facade formats and parses strings" {
    char formatted[32];
    int length = format_t.snprintf(formatted, sizeof(formatted), "%s:%d", "rank", 17);
    @assertEquals(7, length);
    @assert(strcmp(formatted, "rank:17") == 0);

    char label[16];
    int rank = 0;
    @assertEquals(2, format_t.sscanf(formatted, "%15[^:]:%d", label, &rank));
    @assert(strcmp(label, "rank") == 0);
    @assertEquals(17, rank);
}

@test "var_io facade exposes standard stream handles" {
    var_io_t streams = var_io_t.standard();
    @assert(streams.input.stream == stdin);
    @assert(streams.output.stream == stdout);
    @assert(streams.error.stream == stderr);
    @assertEquals(STREAM_BORROWED, streams.input.is_owned);
    @assertEquals(STREAM_BORROWED, streams.output.is_owned);
    @assertEquals(STREAM_BORROWED, streams.error.is_owned);
}

@test "var_io writes through its configured output stream" {
    stream_t output = file_t.tmpfile();
    @assert(output.stream != NULL);
    defer output.fclose();

    var_io_t io = var_io_t.standard();
    io.output = stream_t.from_borrowed(output.stream);
    @assertEquals(8, io.printf("value=%d", 42));
    @assert(io.puts("line") != EOF);
    @assertEquals('!', io.putchar('!'));
    @assertEquals(0, output.fflush());
    output.rewind();

    char rendered[32];
    @assert(output.fgets(rendered, sizeof(rendered)) != NULL);
    @assert(strcmp(rendered, "value=42line\n") == 0);
    @assertEquals('!', output.fgetc());
}

@test "var_io reads and reports through configured streams" {
    stream_t input = file_t.tmpfile();
    stream_t error = file_t.tmpfile();
    @assert(input.stream != NULL && error.stream != NULL);
    defer input.fclose();
    defer error.fclose();

    @assert(input.fputs("73Z") >= 0);
    input.rewind();

    var_io_t io = var_io_t.standard();
    io.input = stream_t.from_borrowed(input.stream);
    io.error = stream_t.from_borrowed(error.stream);
    int value = 0;
    @assertEquals(1, io.scanf("%d", &value));
    @assertEquals(73, value);
    @assertEquals('Z', io.getchar());

    errno = EINVAL;
    io.perror("parse");
    error.rewind();
    char message[64];
    @assert(error.fgets(message, sizeof(message)) != NULL);
    @assert(strncmp(message, "parse: ", 7) == 0);
}

@test "stream close refuses borrowed handles" {
    FILE* raw = stdio_t.tmpfile();
    @assert(raw != NULL);
    stream_t borrowed_stream = stream_t.from_borrowed(raw);
    @assertEquals(EINVAL, borrowed_stream.freopen("", "r"));
    @assert(borrowed_stream.stream == raw);
    @assertEquals(EOF, borrowed_stream.fclose());
    @assert(borrowed_stream.stream == raw);
    @assertEquals((size_t)1, stdio_t.fwrite("x", 1, 1, raw));
    @assertEquals(0, stdio_t.fclose(raw));
}

@test "stdio compatibility facade retains raw FILE operations" {
    FILE* stream = stdio_t.tmpfile();
    @assert(stream != NULL);
    defer stdio_t.fclose(stream);

    const char input[] = "raw stream";
    size_t input_size = sizeof(input) - 1;
    @assertEquals(input_size, stdio_t.fwrite(input, 1, input_size, stream));
}

@test "file factory preserves fopen failure results" {
    stream_t stream = file_t.fopen("", "r");
    @assert(stream.stream == NULL);
    @assertEquals(STREAM_BORROWED, stream.is_owned);
}

@test "path facade reports invalid empty paths" {
    @assert(path_t.remove("") != 0);
    @assert(path_t.rename("", "") != 0);
}

@test "stream close clears an owned handle" {
    stream_t stream = file_t.tmpfile();
    @assert(stream.stream != NULL);
    @assertEquals(STREAM_OWNED, stream.is_owned);
    @assertEquals(0, stream.fclose());
    @assert(stream.stream == NULL);
    @assertEquals(STREAM_BORROWED, stream.is_owned);
    @assertEquals(EOF, stream.fclose());
}

@test "stream freopen reports failure and clears the stream" {
    stream_t stream = file_t.tmpfile();
    @assert(stream.stream != NULL);

    int error = stream.freopen("", "r");
    @assert(error != 0);
    @assert(stream.stream == NULL);
    @assert(stream.buffer == NULL);
}

@test "stream writes reads and closes a temporary file" {
    stream_t stream = file_t.tmpfile();
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

@test "file and memory backends share byte operation signatures" {
    stream_t file = file_t.tmpfile();
    @assert(file.stream != NULL);

    const unsigned char input[] = { 'x', 0, 'y' };
    @assertEquals((size_t)sizeof(input), file.write_bytes(input, sizeof(input)));
    @assertEquals(0, file.flush());
    @assertEquals(0, file.seek(0, SEEK_SET));

    unsigned char output[sizeof(input)];
    memset(output, 0, sizeof(output));
    @assertEquals((size_t)sizeof(output), file.read_bytes(output, sizeof(output)));
    @assert(memcmp(input, output, sizeof(input)) == 0);
    @assertEquals(0, file.close());
}

@test "stream forwards formatted I/O operations" {
    stream_t stream = file_t.tmpfile();
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
