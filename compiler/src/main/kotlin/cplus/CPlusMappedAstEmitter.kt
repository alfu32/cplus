package cplus

data class CPlusMappedEdit(val span: SourceSpan, val replacement: MappedText)

/** Applies non-overlapping, source-spanned edits while retaining all unchanged and moved origins. */
class CPlusMappedAstEmitter {
    fun emit(ast: CPlusAst, source: MappedText, edits: List<CPlusMappedEdit>): MappedText {
        require(ast.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }
        val ordered = edits.sortedWith(compareBy<CPlusMappedEdit> { it.span.startOffset }.thenBy { it.span.endOffset })
        val output = MappedTextBuilder()
        var cursor = 0
        ordered.forEach { edit ->
            val start = edit.span.startOffset
            val end = edit.span.endOffset
            require(start in cursor..source.text.length) {
                "overlapping or out-of-bounds AST edit at $start (previous edit ends at $cursor)"
            }
            require(end in start..source.text.length) { "AST edit range $start..$end is outside the source" }
            output.append(source, cursor, start)
            output.append(edit.replacement)
            cursor = end
        }
        output.append(source, cursor, source.text.length)
        return output.build()
    }
}
