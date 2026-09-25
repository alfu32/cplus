package cplus

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Stable key for a source unit. File IDs are normalized absolute paths; virtual IDs are caller-named. */
data class SourceId(val value: String) {
    companion object {
        fun named(value: String): SourceId = SourceId(value)

        fun fromPath(path: Path): SourceId {
            val absolute = path.toAbsolutePath().normalize()
            val canonical = try {
                if (Files.exists(absolute)) absolute.toRealPath() else absolute
            } catch (_: Exception) {
                absolute
            }
            return SourceId(canonical.toString())
        }
    }
}

/** UTF-8 byte offsets are used by parser generators; compiler source spans use Kotlin UTF-16 offsets. */
class SourceCoordinateMap internal constructor(private val text: String) {
    private val ascii = text.all { it.code < 0x80 }
    private val utf16ToUtf8: IntArray?
    private val utf8ToUtf16: IntArray?
    val utf8Length: Int

    init {
        if (ascii) {
            utf8Length = text.length
            utf16ToUtf8 = null
            utf8ToUtf16 = null
        } else {
            var byteLength = 0
            var charOffset = 0
            while (charOffset < text.length) {
                val first = text[charOffset]
                val codePoint = when {
                    first.isHighSurrogate() -> {
                        require(charOffset + 1 < text.length && text[charOffset + 1].isLowSurrogate()) {
                            "source contains an unpaired UTF-16 surrogate at offset $charOffset"
                        }
                        Character.toCodePoint(first, text[charOffset + 1])
                    }
                    first.isLowSurrogate() -> throw IllegalArgumentException(
                        "source contains an unpaired UTF-16 surrogate at offset $charOffset"
                    )
                    else -> first.code
                }
                byteLength += utf8Width(codePoint)
                charOffset += if (codePoint > 0xFFFF) 2 else 1
            }

            utf8Length = byteLength
            val charToByte = IntArray(text.length + 1) { INVALID_OFFSET }
            val byteToChar = IntArray(byteLength + 1) { INVALID_OFFSET }
            charOffset = 0
            var byteOffset = 0
            while (charOffset < text.length) {
                val codePoint = Character.codePointAt(text, charOffset)
                charToByte[charOffset] = byteOffset
                byteToChar[byteOffset] = charOffset
                byteOffset += utf8Width(codePoint)
                charOffset += if (codePoint > 0xFFFF) 2 else 1
            }
            charToByte[text.length] = byteLength
            byteToChar[byteLength] = text.length
            utf16ToUtf8 = charToByte
            utf8ToUtf16 = byteToChar
        }
    }

    /** Converts only exact UTF-8 code-point boundaries; splitting a multibyte character is rejected. */
    fun utf8ByteToUtf16Offset(byteOffset: Int): Int {
        require(byteOffset in 0..utf8Length) { "UTF-8 byte offset $byteOffset is outside 0..$utf8Length" }
        if (ascii) return byteOffset
        return utf8ToUtf16!![byteOffset].takeIf { it != INVALID_OFFSET }
            ?: throw IllegalArgumentException("UTF-8 byte offset $byteOffset splits a code point")
    }

    /** Converts only exact UTF-16 code-point boundaries; splitting a surrogate pair is rejected. */
    fun utf16ToUtf8ByteOffset(utf16Offset: Int): Int {
        require(utf16Offset in 0..text.length) { "UTF-16 offset $utf16Offset is outside 0..${text.length}" }
        if (ascii) return utf16Offset
        return utf16ToUtf8!![utf16Offset].takeIf { it != INVALID_OFFSET }
            ?: throw IllegalArgumentException("UTF-16 offset $utf16Offset splits a surrogate pair")
    }

    private fun utf8Width(codePoint: Int): Int = when {
        codePoint <= 0x7F -> 1
        codePoint <= 0x7FF -> 2
        codePoint <= 0xFFFF -> 3
        else -> 4
    }

    private companion object {
        const val INVALID_OFFSET = -1
    }
}

/** Immutable source contents and coordinates for one revision of a source unit. */
class SourceSnapshot internal constructor(
    val id: SourceId,
    val revision: Long,
    val text: String
) {
    val sourceFile: SourceFile = SourceFile(text, id.value)
    val coordinates: SourceCoordinateMap = SourceCoordinateMap(text)

    fun spanFromUtf8Bytes(startByte: Int, endByte: Int): SourceSpan {
        require(endByte >= startByte) { "source span end precedes its start" }
        return sourceFile.span(
            coordinates.utf8ByteToUtf16Offset(startByte),
            coordinates.utf8ByteToUtf16Offset(endByte)
        )
    }
}

/** Owns current immutable snapshots; identical opens reuse the snapshot, edits create a new revision. */
class SourceManager {
    private val current = LinkedHashMap<SourceId, SourceSnapshot>()

    @Synchronized
    fun open(id: SourceId, text: String): SourceSnapshot {
        val previous = current[id]
        if (previous?.text == text) return previous
        val next = SourceSnapshot(id, previous?.revision?.plus(1) ?: 0L, text)
        current[id] = next
        return next
    }

    fun load(path: Path): SourceSnapshot {
        val id = SourceId.fromPath(path)
        return open(id, Files.readString(path, StandardCharsets.UTF_8))
    }

    @Synchronized
    fun current(id: SourceId): SourceSnapshot? = current[id]

    @Synchronized
    fun snapshots(): List<SourceSnapshot> = current.values.toList()
}
