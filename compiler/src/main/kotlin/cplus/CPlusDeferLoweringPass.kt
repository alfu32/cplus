package cplus

data class CPlusLoweringDiagnostic(val code: String, val message: String, val span: SourceSpan)

data class CPlusLoweringResult(val source: MappedText, val diagnostics: List<CPlusLoweringDiagnostic>)

/** AST-backed, source-map-preserving lowering of function-scoped defer statements. */
class CPlusDeferLoweringPass {
    fun lower(ast: CPlusAst, source: MappedText): CPlusLoweringResult {
        require(ast.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }
        val functions = ast.root.descendantsAndSelf()
            .filter { it.kind in setOf(CPlusAstKind.FUNCTION_DECLARATION, CPlusAstKind.METHOD_DECLARATION) }
            .flatMap { function ->
                function.descendantsAndSelf()
                    .filter { it.kind == CPlusAstKind.BLOCK }
                    .filter { block -> block.fieldName == "body" }
            }
            .toList()
        val deferred = ast.root.descendantsAndSelf().filter { it.kind == CPlusAstKind.DEFER }.toList()
        if (deferred.isEmpty()) return CPlusLoweringResult(source, emptyList())

        val diagnostics = mutableListOf<CPlusLoweringDiagnostic>()
        val byFunction = LinkedHashMap<CPlusAstNode, MutableList<Pair<CPlusAstNode, CPlusAstNode>>>()
        deferred.forEach { defer ->
            val owner = functions.filter { defer.span.startOffset in it.span.startOffset until it.span.endOffset }
                .minByOrNull { it.span.endOffset - it.span.startOffset }
            val body = defer.children.firstOrNull { it.fieldName == "body" }
            if (owner == null || body == null) {
                diagnostics += CPlusLoweringDiagnostic(
                    "CPLUS_DEFER_OUTSIDE_FUNCTION",
                    "defer must appear inside a function or method body",
                    defer.span
                )
            } else {
                byFunction.getOrPut(owner, ::mutableListOf) += defer to body
            }
        }
        if (diagnostics.isNotEmpty()) return CPlusLoweringResult(source, diagnostics)

        val replacements = linkedMapOf<CPlusAstNode, MappedText>()
        val insertionsBefore = linkedMapOf<CPlusAstNode, MappedText>()
        byFunction.forEach { (function, statements) ->
            val body = function.descendantsAndSelf().firstOrNull {
                it.kind == CPlusAstKind.BLOCK && it.fieldName == "body"
            } ?: return@forEach
            val closingBrace = body.children.lastOrNull { it.syntaxKind == "}" }
            if (closingBrace == null) {
                diagnostics += CPlusLoweringDiagnostic(
                    "CPLUS_DEFER_MALFORMED_FUNCTION",
                    "could not locate the closing brace for deferred statements",
                    body.span
                )
                return@forEach
            }
            val nestedDefers = statements.flatMap { (_, deferredBody) ->
                deferredBody.descendantsAndSelf().filter { it.kind == CPlusAstKind.DEFER }.toList()
            }
            if (nestedDefers.isNotEmpty()) {
                diagnostics += CPlusLoweringDiagnostic(
                    "CPLUS_DEFER_NESTED_DEFER",
                    "a deferred statement cannot contain another defer in this lowering pass",
                    nestedDefers.first().span
                )
                return@forEach
            }

            val indentation = indentationAt(source.text, body.span.startOffset) + "    "
            val insertion = MappedTextBuilder()
            statements.asReversed().forEach { (defer, statement) ->
                val origin = source.originAt(defer.span.startOffset)
                insertion.appendGenerated("\n$indentation", origin)
                insertion.append(source, statement.span.startOffset, statement.span.endOffset)
                if (source.text.getOrNull(statement.span.endOffset - 1) != '\n') {
                    insertion.appendGenerated("\n", origin)
                }
                replacements[defer] = MappedText.generated(";", origin)
            }
            insertionsBefore[closingBrace] = insertion.build()
        }
        if (diagnostics.isNotEmpty()) return CPlusLoweringResult(source, diagnostics)

        return CPlusLoweringResult(
            CPlusMappedAstEmitter().emit(ast, source, replacements, insertionsBefore = insertionsBefore),
            emptyList()
        )
    }

    private fun indentationAt(text: String, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        return text.substring(lineStart, offset).takeWhile(Char::isWhitespace)
    }
}

private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
    sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }
