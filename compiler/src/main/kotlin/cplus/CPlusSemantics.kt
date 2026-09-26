package cplus

enum class CPlusSymbolKind { STRUCT, TYPE_ALIAS, FIELD, INSTANCE_METHOD, STATIC_METHOD, FUNCTION }

enum class CPlusThrowsConvention { ERROR_RETURN, ERROR_OUT_PARAMETER }

data class CPlusThrowsMetadata(
    val convention: CPlusThrowsConvention,
    val errorParameterName: String?
)

data class CPlusSymbol(
    val name: String,
    val kind: CPlusSymbolKind,
    val ownerType: String?,
    val typeName: String?,
    val access: String?,
    val annotations: Set<String>,
    val parameters: List<CPlusParameterSymbol>,
    val span: SourceSpan,
    val throwsParameter: String? = null,
    val throwsMetadata: CPlusThrowsMetadata? = null,
    val functionType: CPlusFunctionType? = null,
    /** Original declaration spelling, retained for declarator forms not yet normalized semantically. */
    val declarationText: String? = null,
    /** Pointer indirection introduced by this declaration, excluding any aliased target type. */
    val pointerDepth: Int = 0
)

/** Callable signature attached to a C function-pointer typedef, kept distinct from its return type. */
data class CPlusFunctionType(
    val returnType: String?,
    val parameters: List<CPlusParameterSymbol>,
    val variadic: Boolean,
    val declaratorSpan: SourceSpan,
    /** Callable type returned by this function-pointer signature, when declarators compose. */
    val returnFunctionType: CPlusFunctionType? = null
)

data class CPlusParameterSymbol(
    val name: String,
    val typeName: String?,
    val annotations: Set<String>,
    val receiver: Boolean,
    val span: SourceSpan,
    /** Original declaration spelling, retaining pointer depth and qualifiers beyond [typeName]. */
    val declarationText: String? = null,
    /** Callable signature when this parameter is itself a function pointer/function parameter. */
    val functionType: CPlusFunctionType? = null
)

data class CPlusResolvedCall(
    val methodName: String,
    val ownerType: String,
    val staticCall: Boolean,
    val span: SourceSpan,
    val declaration: CPlusSymbol,
    val receiverSpan: SourceSpan,
    val memberAccessSpan: SourceSpan,
    val memberNameSpan: SourceSpan,
    val operatorSpan: SourceSpan,
    val pointerAccess: Boolean,
    /** The receiver expression is already pointer-valued, as in `(&value).method()`. */
    val receiverAlreadyPointer: Boolean,
    val argumentInsertionOffset: Int,
    val hasArguments: Boolean,
    /** True when an instance method is invoked as `Type.method(&value, ...)`. */
    val explicitReceiver: Boolean = false
)

data class CPlusCatchBinding(
    /** Null denotes a catch-all clause. */
    val codes: List<String>?,
    val typeName: String?,
    val parameterName: String?,
    val span: SourceSpan
)

data class CPlusSemanticIndex(
    val symbols: List<CPlusSymbol>,
    val resolvedCalls: List<CPlusResolvedCall>,
    val catchBindings: List<CPlusCatchBinding>,
    val diagnostics: List<CPlusSemanticDiagnostic> = emptyList()
)

data class CPlusSemanticDiagnostic(
    val code: String,
    val message: String,
    val span: SourceSpan
)

/** Conservative declaration index. It resolves only method calls whose receiver type is explicit. */
class CPlusSemanticAnalyzer {
    private enum class CDeclaratorLayer { POINTER, ARRAY, FUNCTION }
    private data class TypeAliasTarget(val name: String, val layers: List<CDeclaratorLayer>)
    private data class ResolvedCType(val name: String, val layers: List<CDeclaratorLayer>) {
        val pointerDepth: Int get() = layers.count { it == CDeclaratorLayer.POINTER }
    }

    private val comptimeOnlySyntax = setOf(
        "cplus_comptime_function_definition",
        "cplus_legacy_type_generator",
        "cplus_legacy_function_generator",
        "cplus_comptime_type_definition",
        "cplus_comptime_block",
        "cplus_comptime_conditional",
        "cplus_comptime_invocation",
        "cplus_comptime_value",
        "cplus_comptime_import",
        "cplus_at_import",
        "cplus_comptime_flags",
        "cplus_code_fragment"
    )

    fun analyze(ast: CPlusAst): CPlusSemanticIndex {
        val runtimeNodes = buildList {
            fun collect(node: CPlusAstNode) {
                if (node.syntaxKind in comptimeOnlySyntax) return
                add(node)
                node.children.forEach(::collect)
            }
            collect(ast.root)
        }
        val symbols = mutableListOf<CPlusSymbol>()
        val methodsByType = LinkedHashMap<String, MutableList<CPlusSymbol>>()
        val typeAliases = LinkedHashMap<String, TypeAliasTarget>()
        val fieldDeclaratorLayers = LinkedHashMap<Pair<String, String>, List<CDeclaratorLayer>>()
        val anonymousStructNames = LinkedHashMap<Int, String>()
        val diagnostics = mutableListOf<CPlusSemanticDiagnostic>()

        runtimeNodes.asSequence()
            .filter { it.syntaxKind == "type_definition" }
            .forEach { typedef ->
                val type = typedef.children.firstOrNull { it.fieldName == "type" } ?: return@forEach
                val anonymousStruct = type.descendantsAndSelf().firstOrNull {
                    it.syntaxKind in setOf("struct_specifier", "union_specifier") &&
                        it.children.none { child -> child.syntaxKind == "type_identifier" }
                } ?: return@forEach
                val declarator = typedef.children.firstOrNull { it.fieldName == "declarator" } ?: return@forEach
                val alias = declarator.descendantsAndSelf().firstOrNull {
                    it.syntaxKind in setOf("identifier", "type_identifier")
                }?.text(ast.source.text) ?: return@forEach
                anonymousStructNames[anonymousStruct.span.startOffset] = alias
            }

        fun collectDeclarations(node: CPlusAstNode) {
            // These nodes are evaluated or materialized before runtime semantic analysis.
            // Their template bodies must not leak speculative symbols into this index.
            if (node.syntaxKind in comptimeOnlySyntax) return
            if (node.syntaxKind == "type_definition") {
                val typeNode = node.descendantsAndSelf().firstOrNull { it.fieldName == "type" }
                val composite = typeNode?.descendantsAndSelf()?.firstOrNull {
                    it.syntaxKind in setOf("struct_specifier", "union_specifier")
                }
                val taggedCompositeName = composite?.children?.firstOrNull {
                    it.syntaxKind == "type_identifier"
                }?.text(ast.source.text)
                val primitiveTarget = if (composite == null) {
                    typeNode?.descendantsAndSelf()?.firstOrNull {
                        it.syntaxKind == "type_identifier" || it.syntaxKind == "primitive_type"
                    }?.text(ast.source.text)
                } else null
                node.children.filter { it.fieldName == "declarator" }.forEach { declarator ->
                    val declaredName = declarator.children.firstOrNull { it.fieldName == "declarator" }
                        ?: declarator
                    val parameterLists = declaredName.descendantsAndSelf()
                        .filter { it.syntaxKind == "parameter_list" }.toList()
                    val isParameterIdentifier: (CPlusAstNode) -> Boolean = { candidate ->
                        parameterLists.any { it.span.startOffset <= candidate.span.startOffset && it.span.endOffset >= candidate.span.endOffset }
                    }
                    val alias = declaredName.descendantsAndSelf().firstOrNull {
                        it.syntaxKind == "identifier" && !isParameterIdentifier(it)
                    }
                        ?.text(ast.source.text)
                        ?: declaredName.descendantsAndSelf().firstOrNull {
                            it.syntaxKind == "type_identifier" && !isParameterIdentifier(it)
                        }
                        ?.text(ast.source.text)
                    if (alias != null) {
                        val functionType = functionTypeOf(declarator, typeNode, ast.source.text, alias)
                        val resolvedTarget = taggedCompositeName
                            ?: if (composite != null) anonymousStructNames[composite.span.startOffset] ?: alias
                            else primitiveTarget
                        if (resolvedTarget != null) {
                            val layers = declarator.declaratorLayers()
                            val pointerDepth = layers.count { it == CDeclaratorLayer.POINTER }
                            if (functionType == null && resolvedTarget != alias) {
                                typeAliases[alias] = TypeAliasTarget(resolvedTarget, layers)
                            }
                            symbols += CPlusSymbol(
                                alias,
                                CPlusSymbolKind.TYPE_ALIAS,
                                null,
                                resolvedTarget.takeUnless { functionType != null },
                                null,
                                emptySet(),
                                emptyList(),
                                declarator.span,
                                functionType = functionType,
                                declarationText = node.text(ast.source.text),
                                pointerDepth = pointerDepth
                            )
                        }
                    }
                }
            }
            if (node.syntaxKind == "struct_specifier" || node.syntaxKind == "union_specifier") {
                val typeName = node.children.firstOrNull { it.syntaxKind == "type_identifier" }
                    ?.text(ast.source.text)
                    ?: anonymousStructNames[node.span.startOffset]
                    ?: ""
                if (typeName.isNotEmpty()) {
                    symbols += CPlusSymbol(typeName, CPlusSymbolKind.STRUCT, null, null, null, emptySet(), emptyList(), node.span)
                    val body = node.children.firstOrNull { it.syntaxKind == "field_declaration_list" }
                    body?.children.orEmpty().forEach { member ->
                        when (member.syntaxKind) {
                            "field_declaration" -> {
                                val name = member.descendants().firstOrNull { it.syntaxKind == "field_identifier" }
                                    ?.text(ast.source.text) ?: return@forEach
                                val type = member.children.firstOrNull { it.fieldName == "type" }
                                    ?.text(ast.source.text)
                                val declarator = member.children.firstOrNull { it.fieldName == "declarator" }
                                val layers = declarator?.declaratorLayers().orEmpty()
                                fieldDeclaratorLayers[typeName to name] = layers
                                symbols += CPlusSymbol(
                                    name, CPlusSymbolKind.FIELD, typeName, type, null,
                                    emptySet(), emptyList(), member.span,
                                    pointerDepth = layers.count { it == CDeclaratorLayer.POINTER }
                                )
                            }
                            "cplus_method_definition", "cplus_throws_annotated_method" -> {
                                val methodNode = if (member.syntaxKind == "cplus_throws_annotated_method") {
                                    member.descendants().firstOrNull { it.syntaxKind == "cplus_method_definition" } ?: member
                                } else member
                                val nameNode = methodNode.descendants().firstOrNull { it.syntaxKind == "function_declarator" }
                                    ?.children?.firstOrNull { it.syntaxKind == "identifier" }
                                val name = nameNode?.text(ast.source.text) ?: return@forEach
                                val isStatic = methodNode.children.any { it.syntaxKind == "cplus_static_modifier" }
                                val access = methodNode.children.firstOrNull { it.syntaxKind == "cplus_access_modifier" }
                                    ?.text(ast.source.text)
                                val annotations = methodNode.descendants()
                                    .filter { it.syntaxKind == "cplus_parameter_annotation" }
                                    .map { it.text(ast.source.text) }
                                    .toSet()
                                val parameterList = methodNode.descendants().firstOrNull { it.syntaxKind == "parameter_list" }
                                val parameterNodes = parameterList?.children.orEmpty()
                                    .filter { it.syntaxKind in setOf("parameter_declaration", "cplus_parameter_declaration") }
                                val parameters = parameterNodes.mapIndexed { index, parameter ->
                                    val declarator = parameter.children.firstOrNull { it.fieldName == "declarator" }
                                    val nameNode = (declarator ?: parameter).descendantsAndSelf()
                                        .firstOrNull { it.syntaxKind == "identifier" }
                                    val parameterType = parameter.children.firstOrNull { it.fieldName == "type" }
                                        ?.descendantsAndSelf()?.firstOrNull {
                                            it.syntaxKind in setOf("type_identifier", "primitive_type")
                                        }?.text(ast.source.text)
                                    CPlusParameterSymbol(
                                        nameNode?.text(ast.source.text).orEmpty(),
                                        parameterType,
                                        parameter.descendants()
                                            .filter { it.syntaxKind == "cplus_parameter_annotation" }
                                            .map { it.text(ast.source.text) }
                                            .toSet(),
                                        receiver = index == 0 && nameNode?.text(ast.source.text) == "self",
                                        span = parameter.span
                                    )
                                }
                                val throwsAnnotation = member.descendants()
                                    .firstOrNull { it.syntaxKind == "cplus_throws_annotation" }
                                val throwsParameterName = throwsAnnotation?.children
                                    ?.firstOrNull { it.syntaxKind == "identifier" }
                                    ?.text(ast.source.text)
                                val throwsParameter = throwsAnnotation?.let { throwsParameterName.orEmpty() }
                                val symbol = CPlusSymbol(
                                    name,
                                    if (isStatic) CPlusSymbolKind.STATIC_METHOD else CPlusSymbolKind.INSTANCE_METHOD,
                                    typeName,
                                    null,
                                    access,
                                    annotations,
                                    parameters,
                                    member.span,
                                    throwsParameter,
                                    throwsAnnotation?.toThrowsMetadata(throwsParameterName)
                                )
                                symbols += symbol
                                methodsByType.getOrPut(typeName, ::mutableListOf) += symbol
                            }
                        }
                    }
                }
            } else if (node.syntaxKind in setOf("function_definition", "cplus_function_declaration")) {
                val functionDeclarator = node.descendants().firstOrNull { it.syntaxKind == "function_declarator" }
                val name = functionDeclarator?.children?.firstOrNull { it.syntaxKind == "identifier" }
                    ?.text(ast.source.text)
                val parameterNodes = functionDeclarator?.descendantsAndSelf()
                    ?.firstOrNull { it.syntaxKind == "parameter_list" }
                    ?.children.orEmpty()
                    .filter { it.syntaxKind in setOf("parameter_declaration", "cplus_parameter_declaration") }
                val parameters = parameterNodes.map { parameter ->
                    val declarator = parameter.children.firstOrNull { it.fieldName == "declarator" }
                    val parameterName = (declarator ?: parameter).descendantsAndSelf()
                        .firstOrNull { it.syntaxKind == "identifier" }
                        ?.text(ast.source.text).orEmpty()
                    val parameterType = parameter.children.firstOrNull { it.fieldName == "type" }
                        ?.descendantsAndSelf()?.firstOrNull {
                            it.syntaxKind in setOf("type_identifier", "primitive_type")
                        }?.text(ast.source.text)
                    CPlusParameterSymbol(
                        parameterName,
                        parameterType,
                        parameter.descendants()
                            .filter { it.syntaxKind == "cplus_parameter_annotation" }
                            .map { it.text(ast.source.text) }
                            .toSet(),
                        receiver = false,
                        span = parameter.span
                    )
                }
                val throwsAnnotation = node.descendants().firstOrNull { it.syntaxKind == "cplus_throws_annotation" }
                val throwsParameterName = throwsAnnotation?.children
                    ?.firstOrNull { it.syntaxKind == "identifier" }
                    ?.text(ast.source.text)
                val throwsParameter = throwsAnnotation?.let { throwsParameterName.orEmpty() }
                if (name != null) symbols += CPlusSymbol(
                    name, CPlusSymbolKind.FUNCTION, null, null,
                    node.descendants().firstOrNull { it.syntaxKind == "cplus_access_modifier" }?.text(ast.source.text),
                    emptySet(), parameters, node.span,
                    throwsParameter,
                    throwsAnnotation?.toThrowsMetadata(throwsParameterName)
                )
            }
            node.children.forEach(::collectDeclarations)
        }
        collectDeclarations(ast.root)

        fun canonicalType(type: String, declaredLayers: List<CDeclaratorLayer> = emptyList()): ResolvedCType? {
            var current = type
            var layers = declaredLayers
            val visited = mutableSetOf<String>()
            while (visited.add(current)) {
                val target = typeAliases[current] ?: return ResolvedCType(current, layers)
                current = target.name
                layers = layers + target.layers
            }
            return null
        }

        fun operatorText(node: CPlusAstNode): String? =
            node.children.firstOrNull { it.fieldName == "operator" }?.text(ast.source.text)
                ?: node.children.firstOrNull { !it.named }?.text(ast.source.text)

        fun receiverType(expression: CPlusAstNode, variables: Map<String, ResolvedCType?>): ResolvedCType? = when (expression.syntaxKind) {
            "identifier" -> variables[expression.text(ast.source.text)]
            "parenthesized_expression" -> expression.children.firstOrNull { it.named }
                ?.let { receiverType(it, variables) }
            "field_expression" -> {
                val base = expression.children.firstOrNull { it.named }
                val fieldName = expression.children.lastOrNull { it.syntaxKind == "field_identifier" }
                    ?.text(ast.source.text)
                val owner = base?.let { receiverType(it, variables) }
                    ?.takeIf { it.pointerDepth <= 1 }
                    ?.name
                val field = symbols.firstOrNull {
                    it.kind == CPlusSymbolKind.FIELD && it.ownerType == owner && it.name == fieldName
                }
                val knownTypeNames = methodsByType.keys + typeAliases.keys
                field?.typeName?.let { declaredType ->
                    Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(declaredType)
                        .map { it.value }
                        .firstOrNull { it in knownTypeNames }
                        ?.let { declaredFieldType ->
                            val layers = field.ownerType?.let { fieldDeclaratorLayers[it to field.name] }.orEmpty()
                            canonicalType(declaredFieldType, layers)
                        }
                }
            }
            "subscript_expression" -> {
                val argument = expression.children.firstOrNull { it.fieldName == "argument" }
                    ?: expression.children.firstOrNull { it.named }
                val baseType = argument?.let { receiverType(it, variables) }
                if (baseType == null) null else when (baseType.layers.firstOrNull()) {
                    CDeclaratorLayer.ARRAY, CDeclaratorLayer.POINTER -> baseType.copy(layers = baseType.layers.drop(1))
                    else -> null
                }
            }
            "unary_expression", "pointer_expression" -> {
                val argument = expression.children.firstOrNull { it.fieldName == "argument" }
                    ?: expression.children.lastOrNull { it.named }
                val operandType = argument?.let { receiverType(it, variables) }
                when (operatorText(expression)) {
                    "&" -> operandType?.copy(layers = listOf(CDeclaratorLayer.POINTER) + operandType.layers)
                    "*" -> operandType?.takeIf { it.layers.firstOrNull() == CDeclaratorLayer.POINTER }
                        ?.copy(layers = operandType.layers.drop(1))
                    else -> null
                }
            }
            else -> null
        }

        fun isAddressExpression(expression: CPlusAstNode): Boolean = when (expression.syntaxKind) {
            "parenthesized_expression" -> expression.children.firstOrNull { it.named }?.let(::isAddressExpression) == true
            "unary_expression", "pointer_expression" -> operatorText(expression) == "&"
            else -> false
        }

        val resolvedCalls = mutableListOf<CPlusResolvedCall>()
        val catchBindings = runtimeNodes.asSequence()
            .filter { it.syntaxKind == "cplus_catch_clause" }
            .map { clause ->
                val parameter = clause.children.firstOrNull { it.syntaxKind == "parameter_declaration" }
                val parameterName = parameter?.descendantsAndSelf()
                    ?.firstOrNull { it.syntaxKind == "identifier" }?.text(ast.source.text)
                val typeName = parameter?.descendantsAndSelf()
                    ?.firstOrNull { it.syntaxKind in setOf("type_identifier", "primitive_type") }
                    ?.text(ast.source.text)
                val codes = clause.children.filter { it.syntaxKind == "identifier" }
                    .map { it.text(ast.source.text) }.takeIf { it.isNotEmpty() }
                CPlusCatchBinding(codes, typeName, parameterName, clause.span)
            }.toList()
        fun visit(node: CPlusAstNode, variables: Map<String, ResolvedCType?>, enclosingType: String?) {
            if (node.syntaxKind in comptimeOnlySyntax) return
            val ownerType = if (node.syntaxKind in setOf("struct_specifier", "union_specifier")) {
                node.children.firstOrNull { it.syntaxKind == "type_identifier" }?.text(ast.source.text)
                    ?: anonymousStructNames[node.span.startOffset]
                    ?: enclosingType
            } else enclosingType
            if (node.syntaxKind == "call_expression") {
                val called = node.children.firstOrNull { it.fieldName == "function" }
                val memberAccess = called?.descendantsAndSelf()?.firstOrNull { it.syntaxKind == "field_expression" }
                if (memberAccess != null) {
                    val receiver = memberAccess.children.firstOrNull { it.named }
                    val memberName = memberAccess.children.lastOrNull { it.syntaxKind == "field_identifier" }
                        ?.text(ast.source.text)
                    val instanceType = receiver?.let { receiverType(it, variables) }
                    val staticType = receiver?.takeIf {
                        it.syntaxKind == "identifier" && it.text(ast.source.text) !in variables
                    }
                        ?.text(ast.source.text)?.takeIf { it in methodsByType }
                    val arguments = node.children.firstOrNull { it.fieldName == "arguments" }
                    val openingParen = arguments?.children?.firstOrNull { it.syntaxKind == "(" }
                    val closingParen = arguments?.children?.lastOrNull { it.syntaxKind == ")" }
                    val argumentExpressions = arguments?.children.orEmpty()
                        .filter { it.named && it.syntaxKind !in setOf("comment") }
                    val explicitReceiverType = argumentExpressions.firstOrNull()?.let { receiverType(it, variables) }
                    val typeQualifiedMethods = staticType?.let { methodsByType[it].orEmpty() }
                        .orEmpty().filter { it.name == memberName }
                    val matchingStatic = typeQualifiedMethods.firstOrNull { it.kind == CPlusSymbolKind.STATIC_METHOD }
                    val matchingInstance = typeQualifiedMethods.firstOrNull { it.kind == CPlusSymbolKind.INSTANCE_METHOD }
                    val explicitReceiver = matchingStatic == null && matchingInstance != null &&
                        explicitReceiverType?.let {
                            it.name == staticType && it.layers == listOf(CDeclaratorLayer.POINTER)
                        } == true
                    val knownInstanceOwner = instanceType?.name?.takeIf { it in methodsByType }
                    val owner = instanceType?.takeIf { it.name in methodsByType && it.pointerDepth <= 1 }?.name ?: staticType
                    val method = when {
                        staticType != null -> matchingStatic ?: matchingInstance
                        else -> knownInstanceOwner?.let { ownerType ->
                            methodsByType[ownerType]?.firstOrNull { it.name == memberName }
                        }
                    }
                    val isStatic = staticType != null && !explicitReceiver
                    val operator = memberAccess.children.firstOrNull { it.syntaxKind in setOf(".", "->") }
                    val memberNode = memberAccess.children.lastOrNull { it.syntaxKind == "field_identifier" }
                    if (method != null && receiver != null && operator != null && memberNode != null &&
                        openingParen != null && closingParen != null) {
                        val declarationIsStatic = method.kind == CPlusSymbolKind.STATIC_METHOD
                        if (declarationIsStatic != isStatic) {
                            diagnostics += if (declarationIsStatic) {
                                CPlusSemanticDiagnostic(
                                    "CPLUS_STATIC_METHOD_REQUIRES_TYPE_RECEIVER",
                                    "static method '${method.name}' must be called through type '${method.ownerType}'",
                                    node.span
                                )
                            } else {
                                CPlusSemanticDiagnostic(
                                    "CPLUS_INSTANCE_METHOD_REQUIRES_VALUE_RECEIVER",
                                    "instance method '${method.name}' requires an instance receiver of '${method.ownerType}'",
                                    node.span
                                )
                            }
                        } else if (!isStatic && !explicitReceiver && instanceType != null) {
                            val layers = instanceType.layers
                            val pointerDepth = instanceType.pointerDepth
                            val explicitAddressDot = operator.syntaxKind == "." && isAddressExpression(receiver) &&
                                layers == listOf(CDeclaratorLayer.POINTER)
                            val validReceiverShape = when (operator.syntaxKind) {
                                "." -> layers.isEmpty() || explicitAddressDot
                                // A C array expression decays to a pointer to its first element. This is
                                // valid for an array of structs, but not for an array of pointers or a
                                // pointer to an array; those shapes must be indexed/dereferenced first.
                                "->" -> layers == listOf(CDeclaratorLayer.POINTER) ||
                                    layers == listOf(CDeclaratorLayer.ARRAY)
                                else -> false
                            }
                            if (!validReceiverShape) {
                                val (code, message) = when {
                                    pointerDepth > 1 -> "CPLUS_METHOD_RECEIVER_POINTER_DEPTH" to
                                        "method '${method.name}' requires a direct struct value or pointer; receiver has pointer depth $pointerDepth"
                                    operator.syntaxKind == "." && pointerDepth == 1 && !explicitAddressDot ->
                                        "CPLUS_METHOD_RECEIVER_ACCESS_OPERATOR" to
                                            "pointer receiver for '${method.name}' must use '->' (or an explicit address receiver such as '(&value).${method.name}()')"
                                    operator.syntaxKind == "->" && pointerDepth == 0 && layers.isEmpty() ->
                                        "CPLUS_METHOD_RECEIVER_ACCESS_OPERATOR" to
                                            "value receiver for '${method.name}' must use '.' rather than '->'"
                                    else -> "CPLUS_METHOD_RECEIVER_DECLARATOR_SHAPE" to
                                        "receiver for '${method.name}' has declarator shape ${layers.joinToString(prefix = "[", postfix = "]")}; index or dereference it to obtain a struct value or direct pointer"
                                }
                                diagnostics += CPlusSemanticDiagnostic(code, message, node.span)
                            } else {
                                val innerArguments = ast.source.text.substring(openingParen.span.endOffset, closingParen.span.startOffset)
                                val callReceiver = if (explicitReceiver) argumentExpressions.first() else receiver
                                resolvedCalls += CPlusResolvedCall(
                                    method.name, owner!!, isStatic, node.span, method,
                                    callReceiver.span, memberAccess.span, memberNode.span, operator.span,
                                    if (explicitReceiver) false else operator.syntaxKind == "->",
                                    if (explicitReceiver) true else receiver.let(::isAddressExpression),
                                    openingParen.span.endOffset,
                                    SourceMasker.mask(innerArguments).trim().isNotEmpty(),
                                    explicitReceiver
                                )
                            }
                        } else {
                            val innerArguments = ast.source.text.substring(openingParen.span.endOffset, closingParen.span.startOffset)
                            val callReceiver = if (explicitReceiver) argumentExpressions.first() else receiver
                            resolvedCalls += CPlusResolvedCall(
                                method.name, owner!!, isStatic, node.span, method,
                                callReceiver.span, memberAccess.span, memberNode.span, operator.span,
                                if (explicitReceiver) false else operator.syntaxKind == "->",
                                if (explicitReceiver) true else receiver.let(::isAddressExpression),
                                openingParen.span.endOffset,
                                SourceMasker.mask(innerArguments).trim().isNotEmpty(),
                                explicitReceiver
                            )
                        }
                    }
                }
            }

            if (node.syntaxKind == "for_statement") {
                val loopVariables = variables.toMutableMap()
                node.children.forEach { child ->
                    visit(child, loopVariables, ownerType)
                    if (child.fieldName == "initializer") {
                        registerVariable(child, ast.source.text, loopVariables, ::canonicalType)
                    }
                }
                return
            }

            if (node.syntaxKind == "compound_statement") {
                val blockVariables = variables.toMutableMap()
                node.children.forEach { child ->
                    visit(child, blockVariables, ownerType)
                    registerVariable(child, ast.source.text, blockVariables, ::canonicalType)
                }
                return
            }

            val visibleVariables = variables.toMutableMap()
            if (node.syntaxKind in setOf("function_definition", "cplus_function_declaration", "cplus_method_definition")) {
                val parameterList = node.descendantsAndSelf().firstOrNull { it.syntaxKind == "parameter_list" }
                parameterList?.children.orEmpty()
                    .filter { it.syntaxKind in setOf("parameter_declaration", "cplus_parameter_declaration") }
                    .forEach { parameter ->
                        val parameterName = parameter.descendantsAndSelf()
                            .firstOrNull { it.syntaxKind == "identifier" && it.fieldName != "type" }
                            ?.text(ast.source.text)
                        val parameterType = parameter.children.firstOrNull { it.fieldName == "type" }
                            ?.descendantsAndSelf()?.firstOrNull { it.syntaxKind in setOf("type_identifier", "primitive_type") }
                            ?.text(ast.source.text)
                            ?: parameter.descendantsAndSelf().firstOrNull {
                                it.syntaxKind in setOf("type_identifier", "primitive_type")
                            }?.text(ast.source.text)
                        val declarator = parameter.children.firstOrNull { it.fieldName == "declarator" }
                        val resolvedParameterType = parameterType?.let {
                            canonicalType(it, declarator?.declaratorLayers().orEmpty())
                        } ?: ownerType.takeIf { parameterName == "self" }
                            ?.let { ResolvedCType(it, listOf(CDeclaratorLayer.POINTER)) }
                        if (parameterName != null) {
                            // Keep unresolved declarations in the scope so they still shadow type names.
                            visibleVariables[parameterName] = resolvedParameterType
                        }
                    }
            }
            registerVariable(node, ast.source.text, visibleVariables, ::canonicalType)
            node.children.forEach { visit(it, visibleVariables, ownerType) }
        }
        val globalVariables = mutableMapOf<String, ResolvedCType?>()
        fun collectFileScopeVariables(node: CPlusAstNode) {
            when (node.syntaxKind) {
                "declaration" -> registerVariable(node, ast.source.text, globalVariables, ::canonicalType)
                "translation_unit", "preproc_if", "preproc_ifdef", "preproc_elif", "preproc_else" ->
                    node.children.forEach(::collectFileScopeVariables)
            }
        }
        collectFileScopeVariables(ast.root)
        visit(ast.root, globalVariables, null)
        return CPlusSemanticIndex(symbols, resolvedCalls, catchBindings, diagnostics)
    }

    private fun functionTypeOf(
        declarator: CPlusAstNode,
        returnTypeNode: CPlusAstNode?,
        source: String,
        declaredName: String? = null
    ): CPlusFunctionType? {
        val functionDeclarators = declarator.descendantsAndSelf()
            .filter { it.syntaxKind == "function_declarator" }
            .toList()
        val functionDeclarator = if (declaredName == null) {
            functionDeclarators.firstOrNull()
        } else {
            functionDeclarators.filter { candidate ->
                candidate.children.firstOrNull { it.fieldName == "declarator" }
                    ?.descendantsAndSelf()
                    ?.any { it.syntaxKind in setOf("identifier", "type_identifier") && it.text(source) == declaredName } == true
            }.minByOrNull { it.span.endOffset - it.span.startOffset }
                ?: functionDeclarators.firstOrNull()
        } ?: return null
        fun buildSignature(node: CPlusAstNode): CPlusFunctionType {
            val parameterList = node.children.firstOrNull {
                it.fieldName == "parameters" || it.syntaxKind == "parameter_list"
            }
            val parameterNodes = parameterList?.children.orEmpty()
                .filter { it.syntaxKind in setOf("parameter_declaration", "cplus_parameter_declaration") }
            val parameters = parameterNodes.map { parameter ->
                val parameterDeclarator = parameter.children.firstOrNull { it.fieldName == "declarator" }
                val parameterName = parameterDeclarator
                    ?.descendantsAndSelf()?.firstOrNull { it.syntaxKind == "identifier" }
                    ?.text(source).orEmpty()
                val parameterTypeNode = parameter.children.firstOrNull { it.fieldName == "type" }
                val nestedFunctionType = parameterDeclarator?.let { nestedDeclarator ->
                    functionTypeOf(nestedDeclarator, parameterTypeNode, source, parameterName)
                }
                CPlusParameterSymbol(
                    parameterName,
                    parameterTypeNode?.text(source),
                    parameter.descendants().filter { it.syntaxKind == "cplus_parameter_annotation" }
                        .map { it.text(source) }.toSet(),
                    receiver = false,
                    span = parameter.span,
                    declarationText = parameter.text(source),
                    functionType = nestedFunctionType
                )
            }
            val returnedFunctionDeclarator = functionDeclarators
                .filter { candidate ->
                    candidate !== node &&
                        candidate.span.startOffset <= node.span.startOffset &&
                        candidate.span.endOffset >= node.span.endOffset
                }
                .minByOrNull { it.span.endOffset - it.span.startOffset }
            return CPlusFunctionType(
                returnTypeNode?.text(source),
                parameters,
                parameterList?.children.orEmpty().any { it.syntaxKind == "variadic_parameter" },
                node.span,
                returnedFunctionDeclarator?.let(::buildSignature)
            )
        }
        return buildSignature(functionDeclarator)
    }

    private fun CPlusAstNode.text(source: String): String = source.substring(span.startOffset, span.endOffset)

    private fun CPlusAstNode.toThrowsMetadata(parameterName: String?): CPlusThrowsMetadata =
        if (parameterName == null) {
            CPlusThrowsMetadata(CPlusThrowsConvention.ERROR_RETURN, null)
        } else {
            CPlusThrowsMetadata(CPlusThrowsConvention.ERROR_OUT_PARAMETER, parameterName)
        }

    private fun registerVariable(
        node: CPlusAstNode,
        source: String,
        variables: MutableMap<String, ResolvedCType?>,
        canonicalType: (String, List<CDeclaratorLayer>) -> ResolvedCType?
    ) {
        if (node.syntaxKind != "declaration") return
        if (node.descendantsAndSelf().any {
                it.syntaxKind == "storage_class_specifier" && it.text(source).trim() == "typedef"
            }) return
        val type = node.children.firstOrNull { it.fieldName == "type" }
            ?.descendantsAndSelf()?.firstOrNull { it.syntaxKind in setOf("type_identifier", "primitive_type") }
            ?.text(source)
            ?: node.children.firstOrNull { it.syntaxKind in setOf("type_identifier", "primitive_type") }?.text(source)
            ?: return
        node.children.filter { it.fieldName == "declarator" }.forEach { declarationItem ->
            val declarator = if (declarationItem.syntaxKind == "init_declarator") {
                declarationItem.children.firstOrNull { it.fieldName == "declarator" }
            } else {
                declarationItem
            } ?: return@forEach
            val variable = declarator.declaredIdentifier(source) ?: return@forEach
            // A plain function declaration introduces a function, not an object in value scope.
            // Function-pointer declarators do declare objects and retain their pointer indirection.
            val layers = declarator.declaratorLayers()
            if (layers.firstOrNull() == CDeclaratorLayer.FUNCTION) return@forEach
            val resolved = canonicalType(type, layers)
            variables[variable] = resolved
        }
    }

    private fun CPlusAstNode.descendants(): Sequence<CPlusAstNode> =
        children.asSequence().flatMap { sequenceOf(it) + it.descendants() }

    private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
        sequenceOf(this) + descendants()

    private fun CPlusAstNode.declaratorLayers(): List<CDeclaratorLayer> {
        val layers = mutableListOf<CDeclaratorLayer>()
        var current = this
        while (true) {
            when (current.syntaxKind) {
                "pointer_declarator" -> layers += CDeclaratorLayer.POINTER
                "array_declarator" -> layers += CDeclaratorLayer.ARRAY
                "function_declarator" -> layers += CDeclaratorLayer.FUNCTION
            }
            if (current.syntaxKind in setOf("identifier", "field_identifier", "type_identifier")) {
                return layers.asReversed()
            }
            current = current.nextDeclaratorChild() ?: return emptyList()
        }
    }

    private fun CPlusAstNode.declaredIdentifier(source: String): String? {
        var current = this
        while (true) {
            if (current.syntaxKind in setOf("identifier", "field_identifier", "type_identifier")) {
                return current.text(source)
            }
            current = current.nextDeclaratorChild() ?: return null
        }
    }

    private fun CPlusAstNode.nextDeclaratorChild(): CPlusAstNode? {
        val declaratorKinds = setOf(
            "identifier", "field_identifier", "type_identifier", "pointer_declarator", "array_declarator", "parenthesized_declarator",
            "function_declarator", "attributed_declarator", "cplus_interpolated_identifier"
        )
        return children.firstOrNull { it.fieldName == "declarator" && it.syntaxKind in declaratorKinds }
            ?: children.firstOrNull { it.syntaxKind in declaratorKinds }
    }
}
