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
            try {
                val loweredMethod = lowerMethodFromAst(method, source, typeName, isStatic)
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

        val replacements = linkedMapOf<CPlusAstNode, MappedText>()
        val insertionsAfter = linkedMapOf<CPlusAstNode, MappedText>()
        methodsByTypeDefinition.forEach { (typeDefinition, loweredMethods) ->
            loweredMethods.forEach { (method, _) ->
                replacements[method] = MappedText.generated("", source.originAt(method.span.startOffset))
            }
            val insertion = MappedTextBuilder()
            insertion.appendGenerated("\n", source.originAt(typeDefinition.span.endOffset))
            loweredMethods.forEachIndexed { index, (_, loweredMethod) ->
                if (index > 0) insertion.appendGenerated("\n\n", loweredMethod.firstOrigin())
                insertion.append(loweredMethod)
            }
            insertion.appendGenerated("\n", source.originAt(typeDefinition.span.endOffset))
            insertionsAfter[typeDefinition] = insertion.build()
        }
        return try {
            CPlusLoweringResult(CPlusMappedAstEmitter().emit(ast, source, replacements, insertionsAfter), emptyList())
        } catch (failure: IllegalArgumentException) {
            CPlusLoweringResult(
                source,
                listOf(CPlusLoweringDiagnostic("CPLUS_METHOD_OVERLAPPING_EDITS", failure.message.orEmpty(), methods.first().span))
            )
        }
    }

    private fun lowerMethodFromAst(
        method: CPlusAstNode,
        source: MappedText,
        typeName: String,
        isStatic: Boolean
    ): MappedText {
        val access = method.children.firstOrNull { it.syntaxKind == "cplus_access_modifier" }
            ?: throw CPlusSyntaxException("method in struct $typeName is missing an access modifier")
        val declarator = method.children.firstOrNull { it.fieldName == "declarator" }
            ?: throw CPlusSyntaxException("method in struct $typeName is missing a declarator")
        val function = declarator.descendantsAndSelf().firstOrNull { it.syntaxKind == "function_declarator" }
            ?: throw CPlusSyntaxException("method in struct $typeName has an unsupported declarator")
        val name = function.children.firstOrNull { it.syntaxKind == "identifier" }
            ?: throw CPlusSyntaxException("method in struct $typeName is missing a name")
        val parameters = function.descendantsAndSelf().firstOrNull { it.syntaxKind == "parameter_list" }
            ?: throw CPlusSyntaxException("method in struct $typeName is missing a parameter list")
        val open = parameters.children.firstOrNull { it.syntaxKind == "(" }
            ?: throw CPlusSyntaxException("method in struct $typeName has a malformed parameter list")
        val close = parameters.children.lastOrNull { it.syntaxKind == ")" }
            ?: throw CPlusSyntaxException("method in struct $typeName has a malformed parameter list")

        val output = MappedTextBuilder()
        val declarationOrigin = source.originAt(access.span.startOffset)
        if (isStatic) output.appendGenerated("static ", declarationOrigin)
        output.append(source, access.span.startOffset, access.span.endOffset)
        output.appendGenerated(" ", source.originAt(access.span.endOffset))
        appendTrimmed(output, source, access.span.endOffset, declarator.span.startOffset)
        val declaratorPrefix = source.text.substring(declarator.span.startOffset, name.span.startOffset)
        val pointerReturn = declaratorPrefix.trimStart().startsWith("*")
        if (pointerReturn) {
            output.appendGenerated(" ", source.originAt(declarator.span.startOffset))
        }
        val prefixEnd = if (pointerReturn) {
            name.span.startOffset - declaratorPrefix.takeLastWhile(Char::isWhitespace).length
        } else {
            name.span.startOffset
        }
        output.append(source, declarator.span.startOffset, prefixEnd)
        if (!pointerReturn) output.appendGenerated(" ", source.originAt(name.span.startOffset))
        val methodName = source.text.substring(name.span.startOffset, name.span.endOffset)
        output.appendGenerated("${typeStem(typeName)}__$methodName", source.originAt(name.span.startOffset))
        output.append(source, name.span.endOffset, open.span.startOffset)
        output.appendGenerated("(", source.originAt(open.span.startOffset))

        val parameterNodes = parameters.children.filter {
            it.syntaxKind in setOf("parameter_declaration", "cplus_parameter_declaration", "variadic_parameter")
        }
        if (isStatic) {
            output.append(source, open.span.endOffset, close.span.startOffset)
        } else {
            val receiver = parameterNodes.firstOrNull()
                ?: throw CPlusSyntaxException("instance method $methodName has no self receiver")
            val receiverAnnotations = receiver.descendantsAndSelf()
                .filter { it.syntaxKind == "cplus_parameter_annotation" }
                .map { source.text.substring(it.span.startOffset, it.span.endOffset) }
                .toList()
            val receiverOrigin = source.originAt(receiver.span.startOffset)
            val receiverPrefix = receiverAnnotations.joinToString(" ")
            if (receiverPrefix.isNotEmpty()) output.appendGenerated("$receiverPrefix ", receiverOrigin)
            output.appendGenerated("$typeName *self", receiverOrigin)
            var previousEnd = receiver.span.endOffset
            parameterNodes.drop(1).forEach { parameter ->
                output.append(source, previousEnd, parameter.span.startOffset)
                output.append(source, parameter.span.startOffset, parameter.span.endOffset)
                previousEnd = parameter.span.endOffset
            }
            output.append(source, previousEnd, close.span.startOffset)
        }
        output.appendGenerated(")", source.originAt(close.span.startOffset))
        output.append(source, close.span.endOffset, method.span.endOffset)
        return output.build()
    }

    private fun appendTrimmed(output: MappedTextBuilder, source: MappedText, start: Int, end: Int) {
        var left = start
        var right = end
        while (left < right && source.text[left].isWhitespace()) left++
        while (right > left && source.text[right - 1].isWhitespace()) right--
        output.append(source, left, right)
    }

    private fun typeStem(typeName: String): String = if (typeName.endsWith("_t")) typeName.dropLast(2) else typeName
}

private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
    sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }
