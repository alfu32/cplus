package cplus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SourceManagerTest {
    @Test
    fun `ASCII offsets map identically without allocating conversion tables`() {
        val map = SourceCoordinateMap("int value;\n")

        assertEquals(11, map.utf8Length)
        for (offset in 0..11) {
            assertEquals(offset, map.utf16ToUtf8ByteOffset(offset))
            assertEquals(offset, map.utf8ByteToUtf16Offset(offset))
        }
    }

    @Test
    fun `Unicode byte spans map to UTF-16 source spans`() {
        val snapshot = SourceSnapshotTestFactory.snapshot("unicode.cp", "Aé😀\nB")
        val coordinates = snapshot.coordinates

        assertEquals(9, coordinates.utf8Length)
        assertEquals(1, coordinates.utf16ToUtf8ByteOffset(1))
        assertEquals(3, coordinates.utf16ToUtf8ByteOffset(2))
        assertEquals(7, coordinates.utf16ToUtf8ByteOffset(4))
        assertEquals(2, coordinates.utf8ByteToUtf16Offset(3))
        assertEquals(4, coordinates.utf8ByteToUtf16Offset(7))
        assertEquals(5, coordinates.utf8ByteToUtf16Offset(8))

        val span = snapshot.spanFromUtf8Bytes(3, 7)
        assertEquals("unicode.cp", span.file)
        assertEquals(2, span.startOffset)
        assertEquals(4, span.endOffset)
        assertEquals(1, span.startLine)
        assertEquals(3, span.startColumn)
        assertEquals(1, span.endLine)
        assertEquals(5, span.endColumn)
    }

    @Test
    fun `offsets inside UTF encodings are rejected rather than rounded`() {
        val map = SourceCoordinateMap("é😀")

        assertThrows(IllegalArgumentException::class.java) { map.utf8ByteToUtf16Offset(1) }
        assertEquals(1, map.utf8ByteToUtf16Offset(2))
        assertThrows(IllegalArgumentException::class.java) { map.utf8ByteToUtf16Offset(3) }
        assertThrows(IllegalArgumentException::class.java) { map.utf8ByteToUtf16Offset(4) }
        assertThrows(IllegalArgumentException::class.java) { map.utf8ByteToUtf16Offset(5) }
        assertEquals(3, map.utf8ByteToUtf16Offset(6))
        assertThrows(IllegalArgumentException::class.java) { map.utf16ToUtf8ByteOffset(2) }
        assertThrows(IllegalArgumentException::class.java) { map.utf8ByteToUtf16Offset(-1) }
    }

    @Test
    fun `source manager reuses unchanged snapshots and versions edits`() {
        val manager = SourceManager()
        val id = SourceId.named("memory://fixture.cp")
        val initial = manager.open(id, "int value;")

        assertEquals(0L, initial.revision)
        assertSame(initial, manager.open(id, "int value;"))

        val edited = manager.open(id, "long value;")
        assertEquals(1L, edited.revision)
        assertEquals("int value;", initial.text)
        assertEquals("long value;", edited.text)
        assertSame(edited, manager.current(id))
        assertEquals(listOf(edited), manager.snapshots())
    }

    @Test
    fun `load uses canonical path identity and UTF-8 contents`() {
        val path = Files.createTempFile("cplus-source-manager", ".cp")
        try {
            Files.writeString(path, "const char *word = \"café\";\n")
            val snapshot = SourceManager().load(path)

            assertEquals(path.toRealPath().toString(), snapshot.id.value)
            assertEquals("const char *word = \"café\";\n", snapshot.text)
            assertEquals(snapshot.text.toByteArray(Charsets.UTF_8).size, snapshot.coordinates.utf8Length)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `malformed UTF-16 is rejected at source registration`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            SourceCoordinateMap("bad\uD800text")
        }
        assertTrue(error.message.orEmpty().contains("unpaired UTF-16 surrogate"))
    }
}

private object SourceSnapshotTestFactory {
    fun snapshot(name: String, text: String): SourceSnapshot = SourceSnapshot(SourceId.named(name), 0, text)
}
