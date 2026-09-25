package cplus

/** Extracts grammar-recognized methods from struct bodies and emits ordinary C function definitions. */
class CPlusStructMethodLoweringPass {
    fun lower(ast: CPlusAst, source: MappedText): CPlusLoweringResult {
        require(ast.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }
        val parents = mutableMapOf<CPlusAstNode, CPlusAstNode>()
        fun indexParents(node: CPlusAstNode) {
            node.children.forEach { child ->
                parents[child] = node
                indexParents(child)
            }
        }
        indexParents(ast.root)

        val methods = ast.root.descendantsAndSelf()
            .filter { it.kind == CPlusAstKind.METHOD_DECLARATION }
            .toList()
        if (methods.isEmpty()) return CPlusLoweringResult(source, emptyList())

        val diagnostics = mutableListOf<CPlusLoweringDiagnostic>()
        val methodsByTypeDefinition = linkedMapOf<CPlusAstNode, MutableList<Pair<CPlusAstNode, MappedText>>>()
        methods.forEach { method ->
            val containingStruct = generateSequence(parents[method]) { parents[it] }
                .firstOrNull { it.syntaxKind == "struct_specifier" }
            val typeDefinition = generateSequence(parents[method]) { parents[it] }
                .firstOrNull { it.syntaxKind == "type_definition" }
            val typeName = containingStruct?.children?.firstOrNull {
                it.fieldName == "name" && it.syntaxKind == "type_identifier"
            }?.let { source.text.substring(it.span.startOffset, it.span.endOffset) }
                ?: typeDefinition?.children?.firstOrNull {
                    it.fieldName == "declarator" && it.syntaxKind == "type_identifier"
                }?.let { source.text.substring(it.span.startOffset, it.span.endOffset) }

            if (typeName == null || typeDefinition == null) {
                diagnostics += CPlusLoweringDiagnostic(
                    "CPLUS_METHOD_UNNAMED_OWNER",
                    "struct methods require a named struct typedef owner",
                    method.span
                )
                return@forEach
            }
            val wrapper = parents[method]
            if (wrapper?.syntaxKind == "cplus_throws_annotated_method") {
                diagnostics += CPlusLoweringDiagnostic(
                    "CPLUS_METHOD_ANNOTATION_NOT_LOWERED",
                    "remove/collect @throws metadata and reparse before AST struct-method lowering",
                    wrapper.span
                )
                return@forEach
            }
            val isStatic = method.children.any { it.syntaxKind == "cplus_static_modifier" }
            if (!isStatic) {
                val firstParameter = method.descendantsAndSelf()
                    .firstOrNull { it.syntaxKind == "parameter_list" }
                    ?.children?.firstOrNull { it.syntaxKind in setOf("parameter_declaration", "cplus_parameter_declaration") }
                val receiverName = firstParameter?.descendantsAndSelf()
                    ?.firstOrNull { it.syntaxKind == "identifier" }
                    ?.let { source.text.substring(it.span.startOffset, it.span.endOffset) }
                if (receiverName != "self") {
                    diagnostics += CPlusLoweringDiagnostic(
                        "CPLUS_METHOD_RECEIVER_NAME",
                        "the first parameter of an instance method must be named self; use a static method for receiver-free declarations",
                        firstParameter?.span ?: method.span
                    )
                    return@forEach
                }
            }
            val methodSource = source.slice(method.span.startOffset, method.span.endOffset)
            try {
                val loweredMethod = MethodLowerer.lower(methodSource, typeName)
                methodsByTypeDefinition.getOrPut(typeDefinition, ::mutableListOf) += method to loweredMethod
            } catch (failure: CPlusSyntaxException) {
                diagnostics += CPlusLoweringDiagnostic(
                    "CPLUS_METHOD_MALFORMED",
                    failure.message.orEmpty(),
                    method.span
                )
            }
        }
        if (diagnostics.isNotEmpty()) return CPlusLoweringResult(source, diagnostics)

        val edits = mutableListOf<CPlusMappedEdit>()
        methodsByTypeDefinition.forEach { (typeDefinition, loweredMethods) ->
            loweredMethods.forEach { (method, _) ->
                edits += CPlusMappedEdit(method.span, MappedText.generated("", source.originAt(method.span.startOffset)))
            }
            val insertion = MappedTextBuilder()
            insertion.appendGenerated("\n", source.originAt(typeDefinition.span.endOffset))
            loweredMethods.forEachIndexed { index, (_, loweredMethod) ->
                if (index > 0) insertion.appendGenerated("\n\n", loweredMethod.firstOrigin())
                insertion.append(loweredMethod)
            }
            insertion.appendGenerated("\n", source.originAt(typeDefinition.span.endOffset))
            edits += CPlusMappedEdit(
                ast.source.sourceFile.span(typeDefinition.span.endOffset, typeDefinition.span.endOffset),
                insertion.build()
            )
        }
        return try {
            CPlusLoweringResult(CPlusMappedAstEmitter().emit(ast, source, edits), emptyList())
        } catch (failure: IllegalArgumentException) {
            CPlusLoweringResult(
                source,
                listOf(CPlusLoweringDiagnostic("CPLUS_METHOD_OVERLAPPING_EDITS", failure.message.orEmpty(), methods.first().span))
            )
        }
    }
}

private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
    sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }
