package cplus

data class CPlusThrowsLoweringResult(
    val source: MappedText,
    val functions: Map<String, CPlusThrowingFunction>,
    val diagnostics: List<CPlusLoweringDiagnostic>
)

data class CPlusThrowingFunction(
    val name: String,
    val convention: CPlusThrowsConvention,
    val parameterCount: Int,
    val hasImplicitReceiver: Boolean,
    val errorParameterName: String?,
    val declarationSpan: SourceSpan
)

/** Extracts @throws contracts and removes only the annotation nodes using AST source spans. */
class CPlusThrowsLoweringPass {
    fun lower(ast: CPlusAst, source: MappedText): CPlusThrowsLoweringResult {
        require(ast.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }
        val annotations = ast.root.descendantsAndSelf()
            .filter { it.syntaxKind == "cplus_throws_annotation" }
            .toList()
        if (annotations.isEmpty()) return CPlusThrowsLoweringResult(source, emptyMap(), emptyList())

        val parents = mutableMapOf<CPlusAstNode, CPlusAstNode>()
        fun indexParents(node: CPlusAstNode) {
            node.children.forEach { child ->
                parents[child] = node
                indexParents(child)
            }
        }
        indexParents(ast.root)

        val symbolsBySpan = CPlusSemanticAnalyzer().analyze(ast).symbols
            .filter { it.throwsMetadata != null }
            .associateBy { it.span.startOffset to it.span.endOffset }
        val functions = linkedMapOf<String, CPlusThrowingFunction>()
        val diagnostics = mutableListOf<CPlusLoweringDiagnostic>()
        annotations.forEach { annotation ->
            val declaration = generateSequence(parents[annotation]) { parents[it] }
                .firstOrNull { (it.span.startOffset to it.span.endOffset) in symbolsBySpan }
            val symbol = declaration?.let { symbolsBySpan[it.span.startOffset to it.span.endOffset] }
            val metadata = symbol?.throwsMetadata
            if (symbol == null || metadata == null) {
                diagnostics += CPlusLoweringDiagnostic(
                    "CPLUS_THROWS_UNRESOLVED_DECLARATION",
                    "@throws must annotate a function or method declaration that can be resolved by the semantic index",
                    annotation.span
                )
                return@forEach
            }
            val signature = declaration.descendantsAndSelf()
                .firstOrNull { it.syntaxKind in setOf("cplus_function_declaration", "cplus_method_definition") }
                ?: declaration
            val typeNode = signature.children.firstOrNull { it.fieldName == "type" }
            val functionDeclarator = signature.descendantsAndSelf()
                .firstOrNull { it.syntaxKind == "function_declarator" }
            val functionNameNode = functionDeclarator?.children?.firstOrNull { it.syntaxKind == "identifier" }
            if (metadata.convention == CPlusThrowsConvention.ERROR_RETURN) {
                val hasPointerReturn = typeNode != null && functionNameNode != null &&
                    SourceMasker.mask(ast.source.text.substring(typeNode.span.endOffset, functionNameNode.span.startOffset)).contains('*')
                if (typeNode?.let { ast.source.text.substring(it.span.startOffset, it.span.endOffset).trim() } != "error_t" || hasPointerReturn) {
                    diagnostics += CPlusLoweringDiagnostic(
                        "CPLUS_THROWS_RETURN_TYPE",
                        "@throws() requires a function returning scalar error_t",
                        annotation.span
                    )
                    return@forEach
                }
            } else {
                val parameterNodes = functionDeclarator?.descendantsAndSelf()
                    ?.firstOrNull { it.syntaxKind == "parameter_list" }
                    ?.children.orEmpty()
                    .filter { it.syntaxKind in setOf("parameter_declaration", "cplus_parameter_declaration") }
                val errorParameter = parameterNodes.lastOrNull()
                val errorParameterText = errorParameter?.let {
                    ast.source.text.substring(it.span.startOffset, it.span.endOffset)
                }?.let(SourceMasker::mask)?.replace(
                    Regex("\\b(?:borrowed|owned|mut|const|volatile|register|restrict)\\b"), " "
                )?.replace(Regex("\\s+"), " ")?.trim()
                val expectedName = metadata.errorParameterName
                val validErrorOutParameter = expectedName != null &&
                    symbol.parameters.lastOrNull()?.name == expectedName &&
                    errorParameterText?.let {
                        Regex("error_t\\s*\\*+\\s*${Regex.escape(expectedName)}").matches(it)
                    } == true
                if (!validErrorOutParameter) {
                    diagnostics += CPlusLoweringDiagnostic(
                        "CPLUS_THROWS_ERROR_PARAMETER",
                        "@throws(name) requires name to be the final writable error_t pointer parameter",
                        annotation.span
                    )
                    return@forEach
                }
            }
            val cName = symbol.ownerType?.let { owner -> "${typeStem(owner)}__${symbol.name}" } ?: symbol.name
            val function = CPlusThrowingFunction(
                cName,
                metadata.convention,
                symbol.parameters.size,
                symbol.kind == CPlusSymbolKind.INSTANCE_METHOD,
                metadata.errorParameterName,
                symbol.span
            )
            val prior = functions.putIfAbsent(cName, function)
            if (prior != null && (
                    prior.convention != function.convention ||
                        prior.parameterCount != function.parameterCount ||
                        prior.errorParameterName != function.errorParameterName
                    )) {
                diagnostics += CPlusLoweringDiagnostic(
                    "CPLUS_THROWS_CONFLICTING_DECLARATION",
                    "conflicting @throws declarations for $cName",
                    annotation.span
                )
            }
        }
        if (diagnostics.isNotEmpty()) return CPlusThrowsLoweringResult(source, functions, diagnostics)

        val edits = annotations.map { annotation ->
            val replacement = MappedTextBuilder()
            for (index in annotation.span.startOffset until annotation.span.endOffset) {
                val character = source.text[index]
                replacement.appendGenerated(
                    if (character == '\n' || character == '\r') character.toString() else " ",
                    source.originAt(index)
                )
            }
            CPlusMappedEdit(annotation.span, replacement.build())
        }
        return CPlusThrowsLoweringResult(CPlusMappedAstEmitter().emit(ast, source, edits), functions, emptyList())
    }

    private fun typeStem(name: String): String = if (name.endsWith("_t")) name.dropLast(2) else name
}

private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
    sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }
