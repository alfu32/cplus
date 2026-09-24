#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>

comptime import "stdlib:/io/memory_stream.cp";

@test "memory stream preserves arbitrary binary bytes" {
    memory_stream_t stream;
    @assert(stream.init() == MEMORY_STREAM_OK);

    const unsigned char input[] = { 0x41, 0x00, 0x42, 0xff };
    @assertEquals((size_t)sizeof(input), stream.write_bytes(input, sizeof(input)));
    @assertEquals(4L, stream.size());
    @assertEquals(4L, stream.tell());
    @assertEquals(0, stream.seek(0, SEEK_SET));

    unsigned char output[sizeof(input)];
    memset(output, 0, sizeof(output));
    @assertEquals((size_t)sizeof(output), stream.read_bytes(output, sizeof(output)));
    @assert(memcmp(input, output, sizeof(input)) == 0);
    @assertEquals((size_t)0, stream.read_bytes(output, sizeof(output)));
    @assertEquals(0, stream.close());
}

@test "memory stream copies source and supports self-appending across growth" {
    unsigned char source[] = { 'a', 0, 'b', 'c' };
    memory_stream_t stream;
    @assert(stream.init_copy(source, sizeof(source)) == MEMORY_STREAM_OK);
    source[0] = 'z';
    @assertEquals((unsigned char)'a', stream.buffer()[0]);

    @assertEquals((size_t)4, stream.capacity);
    @assertEquals(0, stream.seek(4, SEEK_SET));
    const unsigned char* original = stream.buffer();
    @assertEquals((size_t)4, stream.write_bytes(original, 4));
    @assertEquals(8L, stream.size());
    @assert(memcmp(stream.buffer(), stream.buffer() + 4, 4) == 0);

    @assertEquals(0, stream.close());
}

@test "memory stream zero-fills seek gaps and truncated growth" {
    memory_stream_t stream;
    @assert(stream.init() == MEMORY_STREAM_OK);
    @assertEquals((size_t)3, stream.write_bytes("abc", 3));
    @assertEquals(0, stream.seek(5, SEEK_SET));
    @assertEquals((size_t)1, stream.write_bytes("z", 1));
    @assertEquals(6L, stream.size());

    const unsigned char expected[] = { 'a', 'b', 'c', 0, 0, 'z' };
    @assert(memcmp(stream.buffer(), expected, sizeof(expected)) == 0);
    @assert(stream.truncate(8) == MEMORY_STREAM_OK);
    @assertEquals((unsigned char)0, stream.buffer()[6]);
    @assertEquals((unsigned char)0, stream.buffer()[7]);
    @assert(stream.truncate(2) == MEMORY_STREAM_OK);
    @assertEquals(2L, stream.size());
    @assertEquals(0, stream.close());
}

@test "memory stream clear reuses allocation and close guards later operations" {
    memory_stream_t stream;
    @assert(stream.init() == MEMORY_STREAM_OK);
    @assertEquals((size_t)5, stream.write_bytes("hello", 5));
    size_t previous_capacity = stream.capacity;
    stream.clear();
    @assertEquals(0L, stream.size());
    @assertEquals(0L, stream.tell());
    @assertEquals(previous_capacity, stream.capacity);
    @assertEquals((size_t)1, stream.write_bytes("!", 1));
    @assertEquals(0, stream.close());

    errno = 0;
    @assertEquals(EOF, stream.close());
    @assertEquals(EBADF, errno);
    errno = 0;
    @assertEquals((size_t)0, stream.read_bytes(NULL, 1));
    @assertEquals(EBADF, errno);
    @assertEquals(-1, stream.seek(0, SEEK_SET));
    @assertEquals(-1L, stream.tell());
    @assertEquals(EOF, stream.flush());
}

@test "memory stream rejects invalid ranges without moving the cursor" {
    memory_stream_t stream;
    @assert(stream.init() == MEMORY_STREAM_OK);
    @assertEquals(-1, stream.seek(-1, SEEK_SET));
    @assertEquals(-1, stream.seek(0, 999));
    @assertEquals(0L, stream.tell());
    @assert(stream.truncate((size_t)LONG_MAX + 1) == MEMORY_STREAM_ERROR_RANGE);
    @assertEquals(0, stream.close());
}
