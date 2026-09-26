package cplus

data class CPlusMappedEdit(val span: SourceSpan, val replacement: MappedText)

/** Applies non-overlapping, source-spanned edits while retaining all unchanged and moved origins. */
class CPlusMappedAstEmitter {
    /**
     * Emits a source-preserving AST with node replacements and additions attached to syntax nodes.
     * Trivia between child nodes is retained from [source] with its existing origin segments.
     */
    fun emit(
        ast: CPlusAst,
        source: MappedText,
        replacements: Map<CPlusAstNode, MappedText>,
        insertionsAfter: Map<CPlusAstNode, MappedText> = emptyMap(),
        insertionsBefore: Map<CPlusAstNode, MappedText> = emptyMap()
    ): MappedText {
        require(ast.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }
        require(replacements.keys.all { it.span.endOffset <= source.text.length }) {
            "AST replacement node is outside the source snapshot"
        }
        require(insertionsAfter.keys.all { it.span.endOffset <= source.text.length }) {
            "AST insertion node is outside the source snapshot"
        }
        require(insertionsBefore.keys.all { it.span.startOffset <= source.text.length }) {
            "AST insertion node is outside the source snapshot"
        }

        val output = MappedTextBuilder()
        fun emitNode(node: CPlusAstNode) {
            insertionsBefore[node]?.let(output::append)
            replacements[node]?.let { replacement ->
                output.append(replacement)
                insertionsAfter[node]?.let(output::append)
                return
            }

            val children = node.children
                .filter { it.span.endOffset > it.span.startOffset }
                .sortedWith(compareBy<CPlusAstNode> { it.span.startOffset }.thenBy { it.span.endOffset })
            var cursor = node.span.startOffset
            children.forEach { child ->
                require(child.span.startOffset >= cursor && child.span.endOffset <= node.span.endOffset) {
                    "AST child ${child.syntaxKind} overlaps or escapes ${node.syntaxKind}"
                }
                output.append(source, cursor, child.span.startOffset)
                emitNode(child)
                cursor = child.span.endOffset
            }
            output.append(source, cursor, node.span.endOffset)
            insertionsAfter[node]?.let(output::append)
        }

        emitNode(ast.root)
        return output.build()
    }

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
