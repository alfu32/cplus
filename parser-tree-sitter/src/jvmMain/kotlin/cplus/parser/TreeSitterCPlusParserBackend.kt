package cplus.parser

import cplus.CPlusParseOptions
import cplus.CPlusParseResult
import cplus.CPlusParserBackend
import cplus.CPlusSyntaxNode
import cplus.ParseCoverage
import cplus.ParserBackendId
import cplus.ParserDiagnostic
import cplus.ParserDiagnosticSeverity
import cplus.SourceSnapshot
import cplus.SourceSpan
import cplus.parser.treesitter.TreeSitterCPlus
import io.github.treesitter.ktreesitter.InputEdit
import io.github.treesitter.ktreesitter.InputEncoding
import io.github.treesitter.ktreesitter.Language
import io.github.treesitter.ktreesitter.Parser
import io.github.treesitter.ktreesitter.Point
import io.github.treesitter.ktreesitter.Tree

/**
 * Tree-sitter-backed structural parser. This adapter exposes only stable C-plus compiler types;
 * Tree-sitter node handles and grammar symbol IDs never escape this module.
 */
class TreeSitterCPlusParserBackend : CPlusParserBackend {
    override val id: ParserBackendId = ParserBackendId.TREE_SITTER

    override fun parse(source: SourceSnapshot, options: CPlusParseOptions): CPlusParseResult {
        // Options are reserved for dialect/target-conditioned grammar inputs; the C grammar itself
        // currently parses without target-specific preprocessor evaluation.
        @Suppress("UNUSED_VARIABLE")
        val requestedTargetOs = options.targetOs

        val parser = Parser(Language(TreeSitterCPlus.language()))
        val tree = parser.parse(source.text, InputEncoding.UTF_8)
        return adapt(source, tree)
    }

    internal fun adapt(source: SourceSnapshot, tree: Tree): CPlusParseResult {
        val errors = mutableListOf<ParserDiagnostic>()
        val offsets = JniModifiedUtf8Offsets(source.text)
        val root = tree.rootNode.toCompilerNode(source, offsets, errors)
        val hasErrors = errors.isNotEmpty()
        return CPlusParseResult(
            source = source,
            backend = id,
            coverage = if (hasErrors) ParseCoverage.PARTIAL else ParseCoverage.STRUCTURAL,
            root = root,
            diagnostics = errors,
            limitations = if (hasErrors) {
                listOf("Tree-sitter recovered from syntax that is not yet supported by the C-plus grammar")
            } else {
                emptyList()
            }
        )
    }

    /** Opens a single-document session that can reuse edited Tree-sitter trees across revisions. */
    fun openIncrementalSession(
        source: SourceSnapshot,
        options: CPlusParseOptions = CPlusParseOptions()
    ): TreeSitterCPlusParseSession = TreeSitterCPlusParseSession(this, source, options)
}

data class TreeSitterIncrementalParseResult(
    val parseResult: CPlusParseResult,
    val reusedPreviousTree: Boolean,
    val changedRanges: List<SourceSpan>
)

/**
 * Single-document, single-threaded incremental parser session. The caller supplies immutable
 * snapshots from the same source identity; one minimal replacement edit is applied to the prior
 * tree before reparsing so unchanged subtrees can be reused by Tree-sitter.
 */
class TreeSitterCPlusParseSession internal constructor(
    private val backend: TreeSitterCPlusParserBackend,
    initialSource: SourceSnapshot,
    private val options: CPlusParseOptions
) {
    private val parser = Parser(Language(TreeSitterCPlus.language()))
    private var source = initialSource
    private var tree: Tree = parser.parse(initialSource.text, InputEncoding.UTF_8)

    init {
        @Suppress("UNUSED_VARIABLE")
        val requestedTargetOs = options.targetOs
    }

    fun current(): CPlusParseResult = backend.adapt(source, tree)

    fun update(updatedSource: SourceSnapshot): TreeSitterIncrementalParseResult {
        require(updatedSource.id == source.id) {
            "incremental parser session belongs to ${source.id.value}, not ${updatedSource.id.value}"
        }
        val previousTree = tree
        val edit = minimalEdit(source.text, updatedSource.text)
        if (edit != null) previousTree.edit(edit)
        val nextTree = parser.parse(updatedSource.text, InputEncoding.UTF_8, previousTree)
        val changed = previousTree.changedRanges(nextTree).map { range ->
            JniModifiedUtf8Offsets(updatedSource.text).span(
                updatedSource,
                range.startByte.toInt(),
                range.endByte.toInt()
            )
        }
        source = updatedSource
        tree = nextTree
        return TreeSitterIncrementalParseResult(backend.adapt(updatedSource, nextTree), true, changed)
    }

    private fun minimalEdit(oldText: String, newText: String): InputEdit? {
        if (oldText == newText) return null
        var start = 0
        val sharedLimit = minOf(oldText.length, newText.length)
        while (start < sharedLimit && oldText[start] == newText[start]) start++
        while (start > 0 && (splitsSurrogatePair(oldText, start) || splitsSurrogatePair(newText, start))) start--

        var suffix = 0
        while (suffix < oldText.length - start && suffix < newText.length - start &&
            oldText[oldText.lastIndex - suffix] == newText[newText.lastIndex - suffix]
        ) suffix++
        while (suffix > 0 && (
                splitsSurrogatePair(oldText, oldText.length - suffix) ||
                    splitsSurrogatePair(newText, newText.length - suffix)
                )) suffix--

        val oldEnd = oldText.length - suffix
        val newEnd = newText.length - suffix
        return InputEdit(
            modifiedUtf8Offset(oldText, start).toUInt(),
            modifiedUtf8Offset(oldText, oldEnd).toUInt(),
            modifiedUtf8Offset(newText, newEnd).toUInt(),
            pointAt(oldText, start),
            pointAt(oldText, oldEnd),
            pointAt(newText, newEnd)
        )
    }

    private fun splitsSurrogatePair(text: String, offset: Int): Boolean =
        offset in 1 until text.length && Character.isHighSurrogate(text[offset - 1]) &&
            Character.isLowSurrogate(text[offset])

    private fun modifiedUtf8Offset(text: String, utf16Offset: Int): Int {
        var bytes = 0
        for (index in 0 until utf16Offset) {
            bytes += when {
                text[index].code == 0 -> 2
                text[index].code <= 0x7f -> 1
                text[index].code <= 0x7ff -> 2
                else -> 3
            }
        }
        return bytes
    }

    private fun pointAt(text: String, utf16Offset: Int): Point {
        val prefix = text.substring(0, utf16Offset)
        val row = prefix.count { it == '\n' }
        val lineStart = prefix.lastIndexOf('\n') + 1
        val column = modifiedUtf8Offset(prefix, prefix.length) - modifiedUtf8Offset(prefix, lineStart)
        return Point(row.toUInt(), column.toUInt())
    }
}

private fun io.github.treesitter.ktreesitter.Node.toCompilerNode(
    source: SourceSnapshot,
    offsets: JniModifiedUtf8Offsets,
    diagnostics: MutableList<ParserDiagnostic>,
    fieldName: String? = null
): CPlusSyntaxNode {
    val span = offsets.span(source, startByte.toInt(), endByte.toInt())
    if (isError || isMissing) {
        diagnostics += ParserDiagnostic(
        code = if (isMissing) "TS_MISSING_NODE" else "TS_ERROR_NODE",
            message = if (isMissing) "expected $type" else "unrecognized or malformed syntax",
            severity = ParserDiagnosticSeverity.ERROR,
            span = span
        )
    }
    val mappedChildren = (0 until childCount.toInt()).mapNotNull { index ->
        child(index.toUInt())?.toCompilerNode(
            source,
            offsets,
            diagnostics,
            fieldNameForChild(index.toUInt())
        )
    }
    return CPlusSyntaxNode(
        kind = type,
        span = span,
        children = mappedChildren,
        fieldName = fieldName,
        named = isNamed,
        isError = isError,
        isMissing = isMissing,
        opaque = false
    )
}

/**
 * The JVM binding currently passes `String` through JNI GetStringUTFChars, whose modified UTF-8
 * encodes supplementary Unicode scalars as two three-byte surrogate sequences. Translate those
 * JNI byte positions directly instead of treating them as standard UTF-8 offsets.
 */
private class JniModifiedUtf8Offsets(text: String) {
    private val byteToUtf16: IntArray

    init {
        fun modifiedUtf8Width(character: Char): Int = when {
            character.code == 0 -> 2
            character.code <= 0x7f -> 1
            character.code <= 0x7ff -> 2
            else -> 3
        }
        byteToUtf16 = IntArray(text.sumOf(::modifiedUtf8Width) + 1) { -1 }
        byteToUtf16[0] = 0
        var byteOffset = 0
        var charOffset = 0
        while (charOffset < text.length) {
            val count = if (Character.isHighSurrogate(text[charOffset])) 2 else 1
            byteToUtf16[byteOffset] = charOffset
            repeat(count) { index -> byteOffset += modifiedUtf8Width(text[charOffset + index]) }
            charOffset += count
            byteToUtf16[byteOffset] = charOffset
        }
    }

    fun span(source: SourceSnapshot, startByte: Int, endByte: Int): SourceSpan = source.sourceFile.span(
        offset(startByte),
        offset(endByte)
    )

    private fun offset(byteOffset: Int): Int {
        require(byteOffset in byteToUtf16.indices) { "JNI parser byte offset $byteOffset is out of bounds" }
        var cursor = byteOffset
        while (cursor >= 0 && byteToUtf16[cursor] < 0) cursor--
        return byteToUtf16[cursor]
    }
}
