package cplus

data class CPlusLoweringDiagnostic(val code: String, val message: String, val span: SourceSpan)

/** A declaration synthesized from a structured AST transformation. */
data class CPlusSynthesizedDeclaration(
    val kind: CPlusAstKind,
    val ownerType: String?,
    val sourceName: String,
    val generatedName: String,
    val isStatic: Boolean,
    val sourceSpan: SourceSpan,
    val mappedText: MappedText
)

data class CPlusLoweringResult(
    val source: MappedText,
    val diagnostics: List<CPlusLoweringDiagnostic>,
    val synthesizedDeclarations: List<CPlusSynthesizedDeclaration> = emptyList()
)

/** AST-backed, source-map-preserving lowering of function-scoped defer statements. */
class CPlusDeferLoweringPass {
    fun lower(ast: CPlusAst, source: MappedText): CPlusLoweringResult {
        require(ast.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }
        val functions = ast.root.descendantsAndSelf()
            .filter {
                it.kind in setOf(
                    CPlusAstKind.FUNCTION_DECLARATION,
                    CPlusAstKind.METHOD_DECLARATION,
                    // A named @test is lowered to a generated function by the harness. Treat
                    // its body as a function closure while the AST still contains the test so
                    // fixture-local defer statements are lowered before extraction.
                    CPlusAstKind.TEST
                )
            }
            .flatMap { function ->
                if (function.kind == CPlusAstKind.TEST) {
                    // The grammar deliberately keeps @test declaration children unlabelled,
                    // unlike function_definition.body. Only its direct compound statement is
                    // the generated fixture closure; nested blocks must remain inside it.
                    function.children.asSequence().filter { it.kind == CPlusAstKind.BLOCK }
                } else {
                    function.descendantsAndSelf()
                        .filter { it.kind == CPlusAstKind.BLOCK }
                        .filter { block -> block.fieldName == "body" }
                }
            }
            .toList()
        val testBodies = ast.root.descendantsAndSelf()
            .filter { it.kind == CPlusAstKind.TEST }
            .flatMap { test -> test.children.asSequence().filter { it.kind == CPlusAstKind.BLOCK } }
            .toSet()
        val deferred = ast.root.descendantsAndSelf().filter { it.kind == CPlusAstKind.DEFER }.toList()
        if (deferred.isEmpty()) return CPlusLoweringResult(source, emptyList())

        val diagnostics = mutableListOf<CPlusLoweringDiagnostic>()
        val byFunction = LinkedHashMap<CPlusAstNode, MutableList<Pair<CPlusAstNode, CPlusAstNode>>>()
        var deferSequence = 0
        val flags = mutableMapOf<CPlusAstNode, String>()
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
                var name: String
                do {
                    name = "cplus_defer_active_${deferSequence++}"
                } while (source.text.contains(name))
                flags[defer] = name
            }
        }
        if (diagnostics.isNotEmpty()) return CPlusLoweringResult(source, diagnostics)

        val replacements = linkedMapOf<CPlusAstNode, MappedText>()
        val insertionsBefore = linkedMapOf<CPlusAstNode, MappedText>()
        val insertionsAfter = linkedMapOf<CPlusAstNode, MappedText>()
        byFunction.forEach { (function, statements) ->
            // [functions] contains body nodes. Ordinary function bodies carry the `body`
            // field; @test bodies are direct, unlabelled compound children.
            val body = function.takeIf { it.kind == CPlusAstKind.BLOCK } ?: function.descendantsAndSelf().firstOrNull {
                it.kind == CPlusAstKind.BLOCK && it.fieldName == "body"
            } ?: return@forEach
            val closingBrace = body.children.lastOrNull { it.syntaxKind == "}" }
            val openingBrace = body.children.firstOrNull { it.syntaxKind == "{" }
            if (closingBrace == null || openingBrace == null) {
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
            val declarations = MappedTextBuilder()
            statements.forEach { (defer, _) ->
                declarations.appendGenerated(
                    "\n$indentation int ${flags.getValue(defer)} = 0;",
                    source.originAt(defer.span.startOffset)
                )
            }
            declarations.appendGenerated("\n", source.originAt(openingBrace.span.startOffset))
            insertionsAfter[openingBrace] = declarations.build()

            val insertion = MappedTextBuilder()
            statements.forEach { (defer, _) ->
                val origin = source.originAt(defer.span.startOffset)
                replacements[defer] = MappedText.generated("${flags.getValue(defer)} = 1;", origin)
            }
            statements.asReversed().forEach { (defer, statement) ->
                val origin = source.originAt(defer.span.startOffset)
                insertion.appendGenerated("\n${indentation}if (${flags.getValue(defer)}) {\n$indentation    ", origin)
                insertion.append(source, statement.span.startOffset, statement.span.endOffset)
                insertion.appendGenerated("\n$indentation}", origin)
            }
            if (testBodies.contains(body)) {
                // The test harness uses this label as the single assertion-failure
                // exit. It must precede cleanup so a failed assertion still runs
                // every registered defer before the generated function returns.
                val origin = source.originAt(closingBrace.span.startOffset)
                val marker = MappedText.generated(
                    "\n${indentation}cplus_test_finish:\n${indentation};",
                    origin
                )
                val combined = MappedTextBuilder()
                combined.append(marker)
                combined.append(insertion.build())
                insertionsBefore[closingBrace] = combined.build()
            } else {
                insertionsBefore[closingBrace] = insertion.build()
            }
        }
        if (diagnostics.isNotEmpty()) return CPlusLoweringResult(source, diagnostics)

        return CPlusLoweringResult(
            CPlusMappedAstEmitter().emit(
                ast,
                source,
                replacements,
                insertionsAfter = insertionsAfter,
                insertionsBefore = insertionsBefore
            ),
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
