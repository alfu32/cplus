package cplus

/** Extracts grammar-recognized methods from struct bodies and emits ordinary C function definitions. */
class CPlusStructMethodLoweringPass {
    private data class RangeReplacement(val start: Int, val end: Int, val content: MappedText)

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
        val function = declarator.cplusNamedFunctionDeclarator()
            ?: throw CPlusSyntaxException("method in struct $typeName has an unsupported declarator")
        val name = function.children.firstOrNull { it.syntaxKind == "identifier" }
            ?: throw CPlusSyntaxException("method in struct $typeName is missing a name")
        val parameters = function.descendantsAndSelf().firstOrNull { it.syntaxKind == "parameter_list" }
            ?: throw CPlusSyntaxException("method in struct $typeName is missing a parameter list")
        val methodName = source.text.substring(name.span.startOffset, name.span.endOffset)
        val parameterNodes = parameters.children.filter {
            it.syntaxKind in setOf("parameter_declaration", "cplus_parameter_declaration", "variadic_parameter")
        }
        val replacements = mutableListOf<RangeReplacement>()
        val declaratorAttributes = declarator.descendantsAndSelf()
            .filter { it.syntaxKind == "attribute_specifier" }
            .sortedBy { it.span.startOffset }
            .toList()
        if (declaratorAttributes.isNotEmpty()) {
            // C-plus permits a GNU attribute after a complete declarator. For a
            // function definition, GCC requires the attribute before the
            // declarator (for example, `int __attribute__((noinline)) (*f())(int)`).
            // Keep the annotation and its source mapping, but move only attributes
            // owned by this declarator; declaration-level attributes remain where
            // the author placed them.
            val attributeText = declaratorAttributes.joinToString(" ") { attribute ->
                source.text.substring(attribute.span.startOffset, attribute.span.endOffset)
            }
            replacements += RangeReplacement(
                declarator.span.startOffset,
                declarator.span.startOffset,
                MappedText.generated("$attributeText ", source.originAt(declarator.span.startOffset))
            )
            declaratorAttributes.forEach { attribute ->
                replacements += RangeReplacement(
                    attribute.span.startOffset,
                    attribute.span.endOffset,
                    MappedText.generated("", source.originAt(attribute.span.startOffset))
                )
            }
        }
        replacements += RangeReplacement(
            name.span.startOffset,
            name.span.endOffset,
            MappedText.generated("${typeStem(typeName)}__$methodName", source.originAt(name.span.startOffset))
        )
        if (!isStatic) {
            val receiver = parameterNodes.firstOrNull()
                ?: throw CPlusSyntaxException("instance method $methodName has no self receiver")
            val receiverAnnotations = receiver.descendantsAndSelf()
                .filter { it.syntaxKind == "cplus_parameter_annotation" }
                .map { source.text.substring(it.span.startOffset, it.span.endOffset) }
                .toList()
            val receiverPrefix = receiverAnnotations.joinToString(" ")
            val receiverText = buildString {
                if (receiverPrefix.isNotEmpty()) append(receiverPrefix).append(' ')
                append(typeName).append(" *self")
            }
            replacements += RangeReplacement(
                receiver.span.startOffset,
                receiver.span.endOffset,
                MappedText.generated(receiverText, source.originAt(receiver.span.startOffset))
            )
        }
        val output = MappedTextBuilder()
        val declarationOrigin = source.originAt(access.span.startOffset)
        if (isStatic) output.appendGenerated("static ", declarationOrigin)
        appendReplacedRange(output, source, access.span.startOffset, method.span.endOffset, replacements)
        return output.build()
    }

    private fun appendReplacedRange(
        output: MappedTextBuilder,
        source: MappedText,
        start: Int,
        end: Int,
        replacements: List<RangeReplacement>
    ) {
        var cursor = start
        replacements.sortedBy { it.start }.forEach { replacement ->
            require(replacement.start >= cursor && replacement.end <= end) {
                "method replacement ${replacement.start}..${replacement.end} is outside $start..$end"
            }
            output.append(source, cursor, replacement.start)
            output.append(replacement.content)
            cursor = replacement.end
        }
        output.append(source, cursor, end)
    }

    private fun typeStem(typeName: String): String = if (typeName.endsWith("_t")) typeName.dropLast(2) else typeName
}

private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
    sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }
