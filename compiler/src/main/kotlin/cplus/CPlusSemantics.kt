package cplus

enum class CPlusSymbolKind { STRUCT, TYPE_ALIAS, FIELD, INSTANCE_METHOD, STATIC_METHOD, FUNCTION }

/**
 * Ordered C declarator binding layers, from the declared identifier outward.
 *
 * The finite constructors compose recursively: `int (*value[2])(int)` is
 * `[ARRAY, POINTER, FUNCTION]`, while `int *(*value)(int)` is
 * `[POINTER, FUNCTION, POINTER]`.
 */
enum class CPlusDeclaratorLayer { POINTER, ARRAY, FUNCTION }

enum class CPlusThrowsConvention { ERROR_RETURN, ERROR_OUT_PARAMETER }

/** A source-spanned C declarator qualifier retained for later ABI/lowering passes. */
data class CPlusDeclaratorQualifier(
    val syntaxKind: String,
    val spelling: String,
    val span: SourceSpan
)

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
    val pointerDepth: Int = 0,
    /** Complete declarator binding shape, retained for later semantic/lowering consumers. */
    val declaratorLayers: List<CPlusDeclaratorLayer> = emptyList(),
    /** Attributes/calling-convention modifiers attached to this declaration's declarator. */
    val declaratorQualifiers: List<CPlusDeclaratorQualifier> = emptyList()
)

/** Callable signature attached to a C function-pointer typedef, kept distinct from its return type. */
data class CPlusFunctionType(
    val returnType: String?,
    val parameters: List<CPlusParameterSymbol>,
    val variadic: Boolean,
    val declaratorSpan: SourceSpan,
    /** Callable type returned by this function-pointer signature, when declarators compose. */
    val returnFunctionType: CPlusFunctionType? = null,
    /** Attributes/calling-convention modifiers attached to this function layer. */
    val declaratorQualifiers: List<CPlusDeclaratorQualifier> = emptyList()
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
    val functionType: CPlusFunctionType? = null,
    /** Complete declarator binding shape, including pointer/array/function layers. */
    val declaratorLayers: List<CPlusDeclaratorLayer> = emptyList(),
    /** Attributes/calling-convention modifiers attached to this parameter declarator. */
    val declaratorQualifiers: List<CPlusDeclaratorQualifier> = emptyList()
)

/**
 * Canonical type shape exposed to tooling and later semantic passes.
 * Layers are ordered from the declared value outward, so a pointer to an
 * array is represented as `[POINTER, ARRAY]`, not as a flattened depth.
 */
data class CPlusResolvedType(
    val name: String,
    val declaratorLayers: List<CPlusDeclaratorLayer> = emptyList(),
    val declaratorQualifiers: List<CPlusDeclaratorQualifier> = emptyList(),
    val callableReturn: CPlusResolvedType? = null
) {
    val pointerDepth: Int get() = declaratorLayers.count { it == CPlusDeclaratorLayer.POINTER }
    val isCallable: Boolean get() = declaratorLayers.contains(CPlusDeclaratorLayer.FUNCTION)
}

/** A resolved value declaration retained with its source location for tooling. */
data class CPlusScopedValueType(
    val name: String,
    val type: CPlusResolvedType,
    val declarationSpan: SourceSpan,
    val ownerType: String? = null
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
    val diagnostics: List<CPlusSemanticDiagnostic> = emptyList(),
    /** Canonical types for file-scope values, available to tooling consumers. */
    val valueTypes: Map<String, CPlusResolvedType> = emptyMap(),
    /** Canonical targets for typedef names, including pointer/array/function layers. */
    val typeAliases: Map<String, CPlusResolvedType> = emptyMap(),
    /** Local and parameter declarations, preserving duplicate names across scopes. */
    val scopedValueTypes: List<CPlusScopedValueType> = emptyList()
)

data class CPlusSemanticDiagnostic(
    val code: String,
    val message: String,
    val span: SourceSpan
)

/** Conservative declaration index. It resolves only method calls whose receiver type is explicit. */
class CPlusSemanticAnalyzer {
    private data class TypeAliasTarget(val name: String, val layers: List<CPlusDeclaratorLayer>)
    private data class ResolvedCType(
        val name: String,
        val layers: List<CPlusDeclaratorLayer>,
        val callableReturn: ResolvedCType? = null,
        val declaratorQualifiers: List<CPlusDeclaratorQualifier> = emptyList()
    ) {
        val pointerDepth: Int get() = layers.count { it == CPlusDeclaratorLayer.POINTER }

        fun public(): CPlusResolvedType = CPlusResolvedType(
            name = name,
            declaratorLayers = layers,
            declaratorQualifiers = declaratorQualifiers,
            callableReturn = callableReturn?.public()
        )
    }

    private val comptimeOnlySyntax = setOf(
        "cplus_comptime_function_definition",
        "cplus_legacy_type_generator",
        "cplus_legacy_function_generator",
        "cplus_comptime_type_definition",
        "cplus_comptime_block",
        "cplus_comptime_conditional",
        "cplus_comptime_invocation",
        "cplus_legacy_comptime_invocation",
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
        val fieldDeclaratorLayers = LinkedHashMap<Pair<String, String>, List<CPlusDeclaratorLayer>>()
        val anonymousStructNames = LinkedHashMap<Int, String>()
        data class CallableReturn(val typeName: String, val declaratorLayers: List<CPlusDeclaratorLayer>)
        val functionReturns = LinkedHashMap<String, MutableSet<CallableReturn>>()
        val methodReturns = LinkedHashMap<Pair<String, String>, MutableSet<CallableReturn>>()
        val diagnostics = mutableListOf<CPlusSemanticDiagnostic>()
        val scopedValueTypes = mutableListOf<CPlusScopedValueType>()

        fun recordScopedValue(name: String, type: ResolvedCType?, span: SourceSpan, ownerType: String?) {
            type?.let { scopedValueTypes += CPlusScopedValueType(name, it.public(), span, ownerType) }
        }

        fun declaredReturn(node: CPlusAstNode): CallableReturn? {
            val typeNode = node.children.firstOrNull { it.fieldName == "type" } ?: return null
            val baseType = typeNode.descendantsAndSelf()
                .firstOrNull { it.syntaxKind in setOf("type_identifier", "primitive_type") }
                ?.text(ast.source.text)
                ?: return null
            val declarator = node.children.firstOrNull { it.fieldName == "declarator" } ?: return null
            val layers = declarator.declaratorLayers()
            if (layers.firstOrNull() != CPlusDeclaratorLayer.FUNCTION) return null
            return CallableReturn(baseType, layers.drop(1))
        }

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
                            val pointerDepth = layers.count { it == CPlusDeclaratorLayer.POINTER }
                            if (functionType == null && resolvedTarget != alias) {
                                typeAliases[alias] = TypeAliasTarget(resolvedTarget, layers)
                            } else if (functionType != null && functionType.returnType != null) {
                                // A function-pointer typedef is also a usable
                                // callable type for fields, parameters, and
                                // variables. Keep its complete declarator
                                // inference can recover the callable return
                                // without parsing the declaration spelling.
                                typeAliases[alias] = TypeAliasTarget(functionType.returnType, layers)
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
                                pointerDepth = pointerDepth,
                                declaratorLayers = layers,
                                declaratorQualifiers = node.declarationQualifiers(ast.source.text)
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
                                    pointerDepth = layers.count { it == CPlusDeclaratorLayer.POINTER },
                                    declaratorLayers = layers,
                                    declaratorQualifiers = member.declarationQualifiers(ast.source.text)
                                )
                            }
                            "cplus_method_definition", "cplus_throws_annotated_method" -> {
                                val methodNode = if (member.syntaxKind == "cplus_throws_annotated_method") {
                                    member.descendants().firstOrNull { it.syntaxKind == "cplus_method_definition" } ?: member
                                } else member
                                val nameNode = methodNode.cplusNamedFunctionDeclarator()
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
                                    val parameterTypeNode = parameter.children.firstOrNull { typeNode ->
                                        typeNode.fieldName == "type" && typeNode.descendantsAndSelf().any {
                                            it.syntaxKind in setOf("type_identifier", "primitive_type")
                                        }
                                    }
                                    val parameterType = parameterTypeNode
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
                                        span = parameter.span,
                                        declarationText = parameter.text(ast.source.text),
                                        functionType = declarator?.let {
                                            functionTypeOf(
                                                it,
                                                parameterTypeNode,
                                                ast.source.text,
                                                nameNode?.text(ast.source.text)
                                            )
                                        },
                                        declaratorLayers = declarator?.declaratorLayers().orEmpty(),
                                        declaratorQualifiers = parameter.declarationQualifiers(ast.source.text)
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
                                    throwsAnnotation?.toThrowsMetadata(throwsParameterName),
                                    declaratorLayers = methodNode.children
                                        .firstOrNull { it.fieldName == "declarator" }
                                        ?.declaratorLayers().orEmpty(),
                                    declaratorQualifiers = methodNode.declarationQualifiers(ast.source.text)
                                )
                                symbols += symbol
                                methodsByType.getOrPut(typeName, ::mutableListOf) += symbol
                                declaredReturn(methodNode)?.let { result ->
                                    methodReturns.getOrPut(typeName to name, ::linkedSetOf) += result
                                }
                            }
                        }
                    }
                }
            } else if (node.syntaxKind in setOf("function_definition", "cplus_function_declaration")) {
                val functionDeclarator = node.cplusNamedFunctionDeclarator()
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
                    val parameterTypeNode = parameter.children.firstOrNull { typeNode ->
                        typeNode.fieldName == "type" && typeNode.descendantsAndSelf().any {
                            it.syntaxKind in setOf("type_identifier", "primitive_type")
                        }
                    }
                    val parameterType = parameterTypeNode
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
                        span = parameter.span,
                        declarationText = parameter.text(ast.source.text),
                        functionType = declarator?.let {
                            functionTypeOf(
                                it,
                                parameterTypeNode,
                                ast.source.text,
                                parameterName
                            )
                        },
                        declaratorLayers = declarator?.declaratorLayers().orEmpty(),
                        declaratorQualifiers = parameter.declarationQualifiers(ast.source.text)
                    )
                }
                val throwsAnnotation = node.descendants().firstOrNull { it.syntaxKind == "cplus_throws_annotation" }
                val throwsParameterName = throwsAnnotation?.children
                    ?.firstOrNull { it.syntaxKind == "identifier" }
                    ?.text(ast.source.text)
                val throwsParameter = throwsAnnotation?.let { throwsParameterName.orEmpty() }
                if (name != null) {
                    symbols += CPlusSymbol(
                        name, CPlusSymbolKind.FUNCTION, null, null,
                        node.descendants().firstOrNull { it.syntaxKind == "cplus_access_modifier" }?.text(ast.source.text),
                        emptySet(), parameters, node.span,
                        throwsParameter,
                        throwsAnnotation?.toThrowsMetadata(throwsParameterName),
                        declaratorLayers = node.children
                            .firstOrNull { it.fieldName == "declarator" }
                            ?.declaratorLayers().orEmpty(),
                        declaratorQualifiers = node.declarationQualifiers(ast.source.text)
                    )
                    declaredReturn(node)?.let { result ->
                        functionReturns.getOrPut(name, ::linkedSetOf) += result
                    }
                }
            }
            node.children.forEach(::collectDeclarations)
        }
        collectDeclarations(ast.root)

        fun canonicalType(type: String, declaredLayers: List<CPlusDeclaratorLayer> = emptyList()): ResolvedCType? {
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

        fun canonicalCallableType(type: String, declaredLayers: List<CPlusDeclaratorLayer>): ResolvedCType? {
            val resolved = canonicalType(type, declaredLayers) ?: return null
            return resolved.copy(callableReturn = callableResult(resolved))
        }

        fun operatorText(node: CPlusAstNode): String? =
            node.children.firstOrNull { it.fieldName == "operator" }?.text(ast.source.text)
                ?: node.children.firstOrNull { !it.named }?.text(ast.source.text)

        fun isNullPointerConstant(expression: CPlusAstNode): Boolean {
            val text = expression.text(ast.source.text).trim()
            return text == "NULL" || text == "0" || text == "((void *)0)" || text == "((void*)0)"
        }

        fun uniqueFunctionReturn(name: String): ResolvedCType? = functionReturns[name]
            ?.singleOrNull()
            ?.let { result -> canonicalCallableType(result.typeName, result.declaratorLayers) }

        fun uniqueMethodReturn(owner: String, name: String): ResolvedCType? = methodReturns[owner to name]
            ?.singleOrNull()
            ?.let { result -> canonicalCallableType(result.typeName, result.declaratorLayers) }

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
                            canonicalCallableType(declaredFieldType, layers)
                        }
                }
            }
            "subscript_expression" -> {
                val argument = expression.children.firstOrNull { it.fieldName == "argument" }
                    ?: expression.children.firstOrNull { it.named }
                val baseType = argument?.let { receiverType(it, variables) }
                if (baseType == null) null else when (baseType.layers.firstOrNull()) {
                    CPlusDeclaratorLayer.ARRAY, CPlusDeclaratorLayer.POINTER -> baseType.copy(layers = baseType.layers.drop(1))
                    else -> null
                }
            }
            "call_expression" -> {
                val function = expression.children.firstOrNull { it.fieldName == "function" }
                val directName = function?.takeIf { it.syntaxKind == "identifier" }
                    ?.text(ast.source.text)
                val directReturn = directName?.let(::uniqueFunctionReturn)
                val variableReturn = directName?.let { variables[it]?.callableReturn }
                val expressionReturn = function?.let { receiverType(it, variables)?.callableReturn }
                if (directReturn != null) {
                    directReturn
                } else if (variableReturn != null) {
                    variableReturn
                } else if (expressionReturn != null) {
                    expressionReturn
                } else {
                    val memberAccess = function?.descendantsAndSelf()
                        ?.firstOrNull { it.syntaxKind == "field_expression" }
                    val receiver = memberAccess?.children?.firstOrNull { it.named }
                    val memberName = memberAccess?.children?.lastOrNull { it.syntaxKind == "field_identifier" }
                        ?.text(ast.source.text)
                    val staticOwner = receiver?.takeIf {
                        it.syntaxKind == "identifier" && it.text(ast.source.text) !in variables
                    }?.text(ast.source.text)?.takeIf { it in methodsByType }
                    val instanceOwner = receiver?.let { receiverType(it, variables) }
                        ?.name?.takeIf { it in methodsByType }
                    val owner = staticOwner ?: instanceOwner
                    if (owner != null && memberName != null) uniqueMethodReturn(owner, memberName) else null
                }
            }
            "cast_expression", "compound_literal_expression" -> expression.children
                .firstOrNull { it.fieldName == "type" }
                ?.let { typeDescriptor ->
                    val typeName = typeDescriptor.descendantsAndSelf()
                        .firstOrNull { it.syntaxKind in setOf("type_identifier", "primitive_type") }
                        ?.text(ast.source.text)
                    val abstractDeclarator = typeDescriptor.children.firstOrNull { it.fieldName == "declarator" }
                    typeName?.let {
                        canonicalType(it, abstractDeclarator?.declaratorLayers().orEmpty())
                    }
                }
            "conditional_expression" -> {
                val consequenceNode = expression.children.firstOrNull { it.fieldName == "consequence" }
                val alternativeNode = expression.children.firstOrNull { it.fieldName == "alternative" }
                val consequence = consequenceNode?.let { receiverType(it, variables) }
                val alternative = alternativeNode?.let { receiverType(it, variables) }
                when {
                    consequence != null && consequence == alternative -> consequence
                    consequence != null && alternativeNode != null && isNullPointerConstant(alternativeNode) -> consequence
                    alternative != null && consequenceNode != null && isNullPointerConstant(consequenceNode) -> alternative
                    else -> null
                }
            }
            "comma_expression" -> expression.children
                .lastOrNull { it.named }
                ?.let { receiverType(it, variables) }
            "unary_expression", "pointer_expression" -> {
                val argument = expression.children.firstOrNull { it.fieldName == "argument" }
                    ?: expression.children.lastOrNull { it.named }
                val operandType = argument?.let { receiverType(it, variables) }
                when (operatorText(expression)) {
                    "&" -> operandType?.copy(layers = listOf(CPlusDeclaratorLayer.POINTER) + operandType.layers)
                    "*" -> operandType?.takeIf { it.layers.firstOrNull() == CPlusDeclaratorLayer.POINTER }
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

        fun directMemberAccess(expression: CPlusAstNode): CPlusAstNode? = when (expression.syntaxKind) {
            "field_expression" -> expression
            "parenthesized_expression" -> expression.children.firstOrNull { it.named }
                ?.let(::directMemberAccess)
            else -> null
        }

        fun expressionType(expression: CPlusAstNode, variables: Map<String, ResolvedCType?>): ResolvedCType? {
            receiverType(expression, variables)?.let { return it }
            // The normalized node text intentionally masks literal contents for
            // source-rewrite safety. Type inference needs the original spelling.
            val text = ast.source.text.substring(expression.span.startOffset, expression.span.endOffset).trim()
            return when (expression.syntaxKind) {
                "number_literal", "literal" -> when {
                    text.startsWith("\"") -> ResolvedCType("char", listOf(CPlusDeclaratorLayer.POINTER))
                    text.startsWith("'") -> ResolvedCType("int", emptyList())
                    else -> ResolvedCType(
                        if (text.contains('.') || text.contains('e', true)) "double" else "int",
                        emptyList()
                    )
                }
                "char_literal" -> ResolvedCType("int", emptyList())
                "string_literal" -> ResolvedCType("char", listOf(CPlusDeclaratorLayer.POINTER))
                else -> null
            }
        }

        fun parameterType(parameter: CPlusParameterSymbol?): ResolvedCType? {
            parameter ?: return null
            val name = parameter.typeName ?: return null
            return canonicalCallableType(name, parameter.declaratorLayers)
                ?.copy(declaratorQualifiers = parameter.declaratorQualifiers)
        }

        fun overloadScore(
            method: CPlusSymbol,
            arguments: List<CPlusAstNode>,
            variables: Map<String, ResolvedCType?>,
            explicitReceiver: Boolean
        ): Int? {
            val parameters = if (method.kind == CPlusSymbolKind.INSTANCE_METHOD && !explicitReceiver) {
                method.parameters.drop(1)
            } else method.parameters
            if (parameters.size != arguments.size) return null
            var score = 0
            parameters.zip(arguments).forEach { (parameter, argument) ->
                val expected = parameterType(parameter)
                val actual = expressionType(argument, variables)
                val argumentText = ast.source.text.substring(argument.span.startOffset, argument.span.endOffset).trim()
                // Literal nodes can be wrapped differently by parser backends. Keep the
                // spelling-based fallback local to overload ranking so a string literal
                // cannot silently select an earlier numeric overload when no expression
                // type was inferred.
                if (expected?.name == "char" && expected.layers == listOf(CPlusDeclaratorLayer.POINTER) &&
                    argumentText.startsWith("\"")
                ) {
                    score += 100
                    return@forEach
                }
                if (expected?.name == "int" && expected.layers.isEmpty() &&
                    argumentText.firstOrNull()?.isDigit() == true
                ) {
                    score += 100
                    return@forEach
                }
                if (expected == null || actual == null) {
                    score += 1
                    return@forEach
                }
                if (expected.name == actual.name && expected.layers == actual.layers) {
                    score += 100
                } else if (expected.name == actual.name) {
                    score += 60
                } else if (expected.name in setOf("float", "double") && actual.name == "int") {
                    score += 20
                } else if (expected.name == "char" && actual.name == "char" &&
                    expected.layers == listOf(CPlusDeclaratorLayer.POINTER) &&
                    actual.layers == listOf(CPlusDeclaratorLayer.POINTER)
                ) {
                    score += 80
                }
            }
            return score
        }

        fun literalShapeOverload(
            candidates: List<CPlusSymbol>,
            arguments: List<CPlusAstNode>
        ): CPlusSymbol? {
            val raw = arguments.firstOrNull()?.let {
                ast.source.text.substring(it.span.startOffset, it.span.endOffset).trim()
            } ?: return null
            return when {
                raw.startsWith("\"") -> candidates.firstOrNull { candidate ->
                    parameterType(candidate.parameters.lastOrNull())?.let {
                        it.name == "char" && it.layers == listOf(CPlusDeclaratorLayer.POINTER)
                    } == true
                }
                raw.firstOrNull()?.isDigit() == true -> candidates.firstOrNull { candidate ->
                    parameterType(candidate.parameters.lastOrNull())?.let {
                        it.name == "int" && it.layers.isEmpty()
                    } == true
                }
                else -> null
            }
        }

        fun chooseOverload(
            candidates: List<CPlusSymbol>,
            arguments: List<CPlusAstNode>,
            variables: Map<String, ResolvedCType?>,
            explicitReceiver: Boolean
        ): CPlusSymbol? {
            return candidates.withIndex()
                .mapNotNull { indexed ->
                    overloadScore(indexed.value, arguments, variables, explicitReceiver)
                        ?.let { indexed.index to (indexed.value to it) }
                }
                .maxWithOrNull(compareBy<Pair<Int, Pair<CPlusSymbol, Int>>> { it.second.second }.thenByDescending { -it.first })
                ?.second?.first
                ?: candidates.firstOrNull { overloadScore(it, arguments, variables, explicitReceiver) != null }
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
                // Only resolve the member expression that is the called
                // expression itself. Searching all descendants resolves the
                // inner `box.get()` a second time when visiting
                // `box.get()()->method()`, producing duplicate lowering edits.
                val memberAccess = called?.let(::directMemberAccess)
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
                    val staticCandidates = typeQualifiedMethods.filter { it.kind == CPlusSymbolKind.STATIC_METHOD }
                    val instanceCandidates = typeQualifiedMethods.filter { it.kind == CPlusSymbolKind.INSTANCE_METHOD }
                    val matchingStatic = staticCandidates.firstOrNull()
                    val matchingInstance = instanceCandidates.firstOrNull()
                    val explicitReceiverArgument = argumentExpressions.firstOrNull()
                    val explicitReceiver = matchingStatic == null && matchingInstance != null &&
                        explicitReceiverArgument != null && (
                            explicitReceiverType?.let {
                                it.name == staticType && it.layers == listOf(CPlusDeclaratorLayer.POINTER)
                            } == true || isNullPointerConstant(explicitReceiverArgument)
                        )
                    val selectedStatic = literalShapeOverload(staticCandidates, argumentExpressions)
                        ?: chooseOverload(staticCandidates, argumentExpressions, variables, false)
                    val selectedInstance = literalShapeOverload(instanceCandidates, argumentExpressions)
                        ?: chooseOverload(instanceCandidates, argumentExpressions, variables, explicitReceiver)
                    val knownInstanceOwner = instanceType?.name?.takeIf { it in methodsByType }
                    val owner = instanceType?.takeIf { it.name in methodsByType && it.pointerDepth <= 1 }?.name ?: staticType
                    val callableField = instanceType?.let { receiverType ->
                        memberName?.let { fieldName ->
                            val field = symbols.firstOrNull {
                                it.kind == CPlusSymbolKind.FIELD &&
                                    it.ownerType == receiverType.name &&
                                    it.name == fieldName
                            }
                            fieldDeclaratorLayers[receiverType.name to fieldName]
                                ?.contains(CPlusDeclaratorLayer.FUNCTION) == true ||
                                field?.typeName?.let { fieldType ->
                                    symbols.firstOrNull {
                                        it.kind == CPlusSymbolKind.TYPE_ALIAS &&
                                            it.name == fieldType &&
                                            it.functionType != null
                                    } != null
                                } == true
                        }
                    } == true
                    // A function-pointer field is a normal C callable expression, not a C-plus
                    // method. This distinction matters after generic struct materialization,
                    // where a field and method can share a spelling across specializations.
                    val method = if (callableField) null else when {
                        staticType != null -> selectedStatic ?: selectedInstance ?: matchingStatic ?: matchingInstance
                        else -> knownInstanceOwner?.let { ownerType ->
                            val candidates = methodsByType[ownerType].orEmpty().filter { it.name == memberName }
                            literalShapeOverload(candidates, argumentExpressions)
                                ?: chooseOverload(candidates, argumentExpressions, variables, false)
                                ?: candidates.firstOrNull()
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
                                layers == listOf(CPlusDeclaratorLayer.POINTER)
                            val validReceiverShape = when (operator.syntaxKind) {
                                "." -> layers.isEmpty() || explicitAddressDot
                                // A C array expression decays to a pointer to its first element. This is
                                // valid for an array of structs, but not for an array of pointers or a
                                // pointer to an array; those shapes must be indexed/dereferenced first.
                                "->" -> layers == listOf(CPlusDeclaratorLayer.POINTER) ||
                                    layers == listOf(CPlusDeclaratorLayer.ARRAY)
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
                                val callReceiver = if (explicitReceiver) argumentExpressions.first() else receiver
                                resolvedCalls += CPlusResolvedCall(
                                    method.name, owner!!, isStatic, node.span, method,
                                    callReceiver.span, memberAccess.span, memberNode.span, operator.span,
                                    if (explicitReceiver) false else operator.syntaxKind == "->",
                                    if (explicitReceiver) true else receiver.let(::isAddressExpression),
                                    openingParen.span.endOffset,
                                    // Masking erases string and character literal contents, so
                                    // it cannot determine whether a literal-only argument exists.
                                    argumentExpressions.isNotEmpty(),
                                    explicitReceiver
                                )
                            }
                        } else {
                            val callReceiver = if (explicitReceiver) argumentExpressions.first() else receiver
                            resolvedCalls += CPlusResolvedCall(
                                method.name, owner!!, isStatic, node.span, method,
                                callReceiver.span, memberAccess.span, memberNode.span, operator.span,
                                if (explicitReceiver) false else operator.syntaxKind == "->",
                                if (explicitReceiver) true else receiver.let(::isAddressExpression),
                                openingParen.span.endOffset,
                                argumentExpressions.isNotEmpty(),
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
                        registerVariable(child, ast.source.text, loopVariables, ::canonicalType) { name, type ->
                            recordScopedValue(name, type, child.span, ownerType)
                        }
                    }
                }
                return
            }

            if (node.syntaxKind == "compound_statement") {
                val blockVariables = variables.toMutableMap()
                node.children.forEach { child ->
                    visit(child, blockVariables, ownerType)
                    registerVariable(child, ast.source.text, blockVariables, ::canonicalType) { name, type ->
                        recordScopedValue(name, type, child.span, ownerType)
                    }
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
                        val parameterTypeNode = parameter.children.firstOrNull { typeNode ->
                            typeNode.fieldName == "type" && typeNode.descendantsAndSelf().any {
                                it.syntaxKind in setOf("type_identifier", "primitive_type")
                            }
                        }
                        val parameterType = parameterTypeNode
                            ?.descendantsAndSelf()?.firstOrNull { it.syntaxKind in setOf("type_identifier", "primitive_type") }
                            ?.text(ast.source.text)
                            ?: parameter.descendantsAndSelf().firstOrNull {
                                it.syntaxKind in setOf("type_identifier", "primitive_type")
                            }?.text(ast.source.text)
                        val declarator = parameter.children.firstOrNull { it.fieldName == "declarator" }
                        val resolvedParameterType = parameterType?.let {
                            canonicalCallableType(it, declarator?.declaratorLayers().orEmpty())
                                ?.copy(declaratorQualifiers = parameter.declarationQualifiers(ast.source.text))
                        } ?: ownerType.takeIf { parameterName == "self" }
                            ?.let { ResolvedCType(it, listOf(CPlusDeclaratorLayer.POINTER)) }
                        if (parameterName != null) {
                            // Keep unresolved declarations in the scope so they still shadow type names.
                            visibleVariables[parameterName] = resolvedParameterType
                            recordScopedValue(parameterName, resolvedParameterType, parameter.span, ownerType)
                        }
                    }
            }
            registerVariable(node, ast.source.text, visibleVariables, ::canonicalType) { name, type ->
                recordScopedValue(name, type, node.span, ownerType)
            }
            node.children.forEach { visit(it, visibleVariables, ownerType) }
        }
        val globalVariables = mutableMapOf<String, ResolvedCType?>()
        fun collectFileScopeVariables(node: CPlusAstNode) {
            when (node.syntaxKind) {
                "declaration" -> registerVariable(node, ast.source.text, globalVariables, ::canonicalType) { name, type ->
                    recordScopedValue(name, type, node.span, null)
                }
                "translation_unit", "preproc_if", "preproc_ifdef", "preproc_elif", "preproc_else" ->
                    node.children.forEach(::collectFileScopeVariables)
            }
        }
        collectFileScopeVariables(ast.root)
        visit(ast.root, globalVariables, null)
        val publicValueTypes = globalVariables.mapNotNull { (name, type) ->
            type?.public()?.let { name to it }
        }.toMap()
        val publicTypeAliases = typeAliases.mapNotNull { (name, target) ->
            canonicalType(target.name, target.layers)?.public()?.let { name to it }
        }.toMap()
        return CPlusSemanticIndex(
            symbols,
            resolvedCalls,
            catchBindings,
            diagnostics,
            valueTypes = publicValueTypes,
            typeAliases = publicTypeAliases,
            scopedValueTypes = scopedValueTypes.distinctBy {
                listOf(it.name, it.declarationSpan.startOffset, it.declarationSpan.endOffset, it.ownerType)
            }
        )
    }

    private fun callableResult(resolved: ResolvedCType): ResolvedCType? {
        val functionLayer = resolved.layers.indexOfFirst { it == CPlusDeclaratorLayer.FUNCTION }
        if (functionLayer < 0) return null
        val result = ResolvedCType(resolved.name, resolved.layers.drop(functionLayer + 1))
        return result.copy(callableReturn = callableResult(result))
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
                    functionType = nestedFunctionType,
                    declaratorLayers = parameterDeclarator?.declaratorLayers().orEmpty(),
                    declaratorQualifiers = parameter.declarationQualifiers(source)
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
                returnedFunctionDeclarator?.let(::buildSignature),
                node.declaratorQualifiers(source)
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
        canonicalType: (String, List<CPlusDeclaratorLayer>) -> ResolvedCType?,
        onResolved: (String, ResolvedCType) -> Unit = { _, _ -> }
    ) {
        if (node.syntaxKind != "declaration") return
        if (node.descendantsAndSelf().any {
                it.syntaxKind == "storage_class_specifier" && it.text(source).trim() == "typedef"
            }) return
        val typeNode = node.children.firstOrNull { it.fieldName == "type" }
        val type = typeNode
            ?.descendantsAndSelf()?.firstOrNull { it.syntaxKind in setOf("type_identifier", "primitive_type") }
            ?.text(source)
            ?: node.children.firstOrNull { it.syntaxKind in setOf("type_identifier", "primitive_type") }?.text(source)
            ?: return
        node.children.filter { it.fieldName == "declarator" }.forEach { declarationItem ->
            val declarator = declarationItem.declarationBindingRoot() ?: return@forEach
            val variable = declarator.declaredIdentifier(source) ?: return@forEach
            // A plain function declaration introduces a function, not an object in value scope.
            // Function-pointer declarators do declare objects and retain their pointer indirection.
            val layers = declarator.declaratorLayers()
                .let { declaredLayers ->
                    // Tree-sitter C represents a typedef-name function-pointer
                    // object such as `widget_pointer_t (*factory)(void)` as an
                    // init_declarator whose `declarator` is a parenthesized
                    // pointer and whose parameter_list is a sibling. The
                    // parameter list is still a declarator binding layer even
                    // though it is not nested below the pointer node.
                    if (
                        declarationItem.syntaxKind == "init_declarator" &&
                        declarationItem.children.any { it.syntaxKind == "parameter_list" } &&
                        declarator.syntaxKind == "parenthesized_declarator"
                    ) {
                        declaredLayers + CPlusDeclaratorLayer.FUNCTION
                    } else {
                        declaredLayers
                    }
                }
                .let { declaredLayers ->
                    declaredLayers + declarationItem.declaratorPrefixPointerLayers(declarator)
                }
            if (layers.firstOrNull() == CPlusDeclaratorLayer.FUNCTION) return@forEach
            // Declarator layers are ordered from the declared identifier
            // outward. For `widget_t *(*factory)(void)`, the layers are
            // [POINTER, FUNCTION, POINTER]: the first pointer belongs to the
            // variable, while the layers after FUNCTION describe the value
            // returned by invoking it. Do not infer this from a raw `*` count;
            // that loses the binding structure of nested declarators.
            // The function layer may come from a typedef rather than from the
            // variable's own declarator. Resolve the complete type before
            // deriving the result of invoking it.
            val resolved = canonicalType(type, layers)?.let { canonical ->
                canonical.copy(
                    callableReturn = callableResult(canonical),
                    declaratorQualifiers = node.declarationQualifiers(source) +
                        declarationItem.declaratorQualifiers(source)
                )
            }
            variables[variable] = resolved
            resolved?.let { onResolved(variable, it) }
        }
    }

    private fun CPlusAstNode.descendants(): Sequence<CPlusAstNode> =
        children.asSequence().flatMap { sequenceOf(it) + it.descendants() }

    private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
        sequenceOf(this) + descendants()

private fun CPlusAstNode.declaratorLayers(): List<CPlusDeclaratorLayer> {
        val root = this
        val layers = mutableListOf<CPlusDeclaratorLayer>()
        var current = this
        fun result(): List<CPlusDeclaratorLayer> {
            val identifierOutward = layers.asReversed()
            if (root.syntaxKind != "cplus_method_declarator") return identifierOutward

            // The compact direct-return branch stores `*` as structural
            // children of the C-plus wrapper rather than manufacturing a
            // pointer_declarator. Those stars are outside the named function
            // layer, so they follow FUNCTION in identifier-outward order.
            val directReturnPointers = root.children.count { it.syntaxKind == "*" }
            return identifierOutward +
                List(directReturnPointers) { CPlusDeclaratorLayer.POINTER }
        }
        while (true) {
            when (current.syntaxKind) {
                "pointer_declarator" -> layers += CPlusDeclaratorLayer.POINTER
                "array_declarator" -> layers += CPlusDeclaratorLayer.ARRAY
                "function_declarator" -> layers += CPlusDeclaratorLayer.FUNCTION
                "abstract_pointer_declarator" -> layers += CPlusDeclaratorLayer.POINTER
                "abstract_array_declarator" -> layers += CPlusDeclaratorLayer.ARRAY
                "abstract_function_declarator" -> layers += CPlusDeclaratorLayer.FUNCTION
            }
            if (current.syntaxKind in setOf("identifier", "field_identifier", "type_identifier")) {
                return result()
            }
            current = current.nextDeclaratorChild() ?: return result()
        }
    }

/**
 * Select the AST node that owns a declaration's identifier.
 *
 * Tree-sitter's ordinary C grammar can expose an outer pointer token as the
 * `declarator` field of an `init_declarator`, with the function/array
 * declarator as its sibling. Starting semantic traversal at that token loses
 * the binding shape. The binding root keeps the structural node and leaves
 * any direct prefix pointer tokens for [declaratorPrefixPointerLayers].
 */
private fun CPlusAstNode.declarationBindingRoot(): CPlusAstNode? {
    if (syntaxKind != "init_declarator") return this
    val roots = children.filter { child ->
        child.syntaxKind in DECLARATOR_KINDS && child.span.startOffset < child.span.endOffset
    }
    return roots.firstOrNull { it.fieldName == "declarator" } ?: roots.firstOrNull()
}

private fun CPlusAstNode.declaratorPrefixPointerLayers(root: CPlusAstNode): List<CPlusDeclaratorLayer> {
    if (syntaxKind != "init_declarator") return emptyList()
    return children
        .filter { it.fieldName == "declarator" && it.syntaxKind == "*" && it.span.endOffset <= root.span.startOffset }
        .map { CPlusDeclaratorLayer.POINTER }
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
            "function_declarator", "attributed_declarator", "cplus_interpolated_identifier",
            "abstract_pointer_declarator", "abstract_array_declarator", "abstract_function_declarator",
            "abstract_parenthesized_declarator", "_abstract_declarator", "_declarator"
        )
        return children.firstOrNull { it.fieldName == "declarator" && it.syntaxKind in declaratorKinds }
            ?: children.firstOrNull { it.syntaxKind in declaratorKinds }
    }
}

private val DECLARATOR_QUALIFIER_KINDS = setOf(
    "attribute_specifier",
    "attribute_declaration",
    "alignas_qualifier",
    "type_qualifier",
    "ms_call_modifier",
    "ms_declspec_modifier",
    // These modifiers belong to a pointer declarator rather than to the
    // pointed-to type. Preserve them with the declarator metadata so later
    // ABI/lowering passes do not have to recover them from source text.
    "ms_pointer_modifier",
    "ms_restrict_modifier",
    "ms_signed_ptr_modifier",
    "ms_unsigned_ptr_modifier",
    "ms_unaligned_ptr_modifier"
)

/** Retain ABI and compiler qualifiers without interpreting platform-specific spellings. */
private fun CPlusAstNode.declaratorQualifiers(source: String): List<CPlusDeclaratorQualifier> {
    fun walk(node: CPlusAstNode): Sequence<CPlusAstNode> =
        sequenceOf(node) + node.children.asSequence().flatMap(::walk)
    return walk(this)
        .filter { it.syntaxKind in DECLARATOR_QUALIFIER_KINDS }
        .sortedBy { it.span.startOffset }
        .map { CPlusDeclaratorQualifier(it.syntaxKind, source.substring(it.span.startOffset, it.span.endOffset), it.span) }
        .toList()
}

/**
 * Collect qualifiers attached either to a declaration's specifiers or to its
 * nested declarator.  C and Microsoft spellings may legally occur in both
 * locations; dropping the specifier-side form makes ABI metadata disappear
 * before lowering.
 */
private fun CPlusAstNode.declarationQualifiers(source: String): List<CPlusDeclaratorQualifier> {
    val declarator = children.firstOrNull { it.fieldName == "declarator" }
    return (
        children.filter { it.syntaxKind in DECLARATOR_QUALIFIER_KINDS }
            .map { CPlusDeclaratorQualifier(it.syntaxKind, source.substring(it.span.startOffset, it.span.endOffset), it.span) } +
            declarator?.declaratorQualifiers(source).orEmpty()
        )
        .distinctBy { it.span.startOffset to it.span.endOffset }
        .sortedBy { it.span.startOffset }
}

private val DECLARATOR_KINDS = setOf(
    "identifier", "field_identifier", "type_identifier", "pointer_declarator", "array_declarator",
    "parenthesized_declarator", "function_declarator", "attributed_declarator", "cplus_interpolated_identifier",
    "abstract_pointer_declarator", "abstract_array_declarator", "abstract_function_declarator",
    "abstract_parenthesized_declarator", "_abstract_declarator", "_declarator"
)

/**
 * Select the function declarator that binds a declaration's name.
 *
 * An ordinary function has one function declarator. A function-pointer
 * return, such as `int (*select(...))(int)`, has an outer declarator for the
 * returned callable type and a nested declarator that actually binds
 * `select`; semantic indexing must use the latter.
 */
internal fun CPlusAstNode.cplusNamedFunctionDeclarator(): CPlusAstNode? {
    fun descendantsAndSelf(node: CPlusAstNode): Sequence<CPlusAstNode> =
        sequenceOf(node) + node.children.asSequence().flatMap(::descendantsAndSelf)
    return descendantsAndSelf(this)
        .filter { it.syntaxKind == "function_declarator" }
        .firstOrNull { candidate ->
            candidate.children.any { it.syntaxKind == "identifier" }
        }
}
