package cplus.parser

import cplus.CPlusLoweringDiagnostic
import cplus.CPlusParseResult
import cplus.CPlusSyntaxNode
import cplus.MappedText
import cplus.MappedTextBuilder
import java.math.BigInteger

data class TreeSitterScalarLoweringResult(
    val source: MappedText,
    val diagnostics: List<CPlusLoweringDiagnostic>
)

data class TreeSitterComptimeScalarArgumentResult(
    val renderedValue: String?,
    val diagnostics: List<CPlusLoweringDiagnostic>
)

/** AST-only materialization for scalar comptime values and inline scalar expressions. */
class TreeSitterComptimeScalarLowering(
    targetOs: String = cplus.CPlusTarget.hostOs(),
    targetArch: String = cplus.CPlusTarget.hostArch()
) {
    private val targetOs = cplus.CPlusTarget.normalizeOs(targetOs)
    private val targetArch = cplus.CPlusTarget.normalizeArch(targetArch)

    /** Evaluate an integer/bool AST expression for an entity-generator argument in module scope. */
    fun evaluateEntityArgument(parsed: CPlusParseResult, expression: CPlusSyntaxNode): TreeSitterComptimeScalarArgumentResult {
        val declarations = collectScalarDeclarations(parsed)
        if (declarations.scopedValues.isNotEmpty()) return TreeSitterComptimeScalarArgumentResult(
            null,
            declarations.scopedValues.map {
                diagnostic(
                    "CPLUS_COMPTIME_VALUE_SCOPE",
                    "comptime scalar declarations are only supported at module scope in this prototype",
                    it.span
                )
            }
        )
        if (declarations.diagnostics.isNotEmpty()) return TreeSitterComptimeScalarArgumentResult(null, declarations.diagnostics)
        val evaluator = ScalarEvaluator(
            parsed.source.text,
            declarations.values,
            declarations.scalarFunctions,
            targetOs,
            targetArch,
            primitiveTypedefAliases(parsed.root, parsed.source.text),
            aggregateLayouts(parsed.root, parsed.source.text, targetOs, targetArch)
        )
        val value = evaluator.evaluate(expression)
        if (value != null && value !is Scalar.Integer && value !is Scalar.Bool) {
            evaluator.diagnostics += diagnostic(
                "CPLUS_COMPTIME_SCALAR_ARGUMENT_TYPE",
                "entity-generator scalar arguments must evaluate to an integer or bool",
                expression.span
            )
        }
        return TreeSitterComptimeScalarArgumentResult(
            renderedValue = value?.takeIf { it is Scalar.Integer || it is Scalar.Bool }?.render(),
            diagnostics = evaluator.distinctDiagnostics()
        )
    }

    /** Validate a pure scalar expression used as a statement in a comptime block; its value is discarded. */
    fun validateDiscardedBlockExpression(
        parsed: CPlusParseResult,
        expression: CPlusSyntaxNode
    ): List<CPlusLoweringDiagnostic> {
        val declarations = collectScalarDeclarations(parsed)
        if (declarations.diagnostics.isNotEmpty()) return declarations.diagnostics
        val evaluator = ScalarEvaluator(
            parsed.source.text,
            declarations.values,
            declarations.scalarFunctions,
            targetOs,
            targetArch,
            primitiveTypedefAliases(parsed.root, parsed.source.text),
            aggregateLayouts(parsed.root, parsed.source.text, targetOs, targetArch)
        )
        val containingBlock = descendants(parsed.root)
            .filter { it.kind == "cplus_comptime_block" && it.span.startOffset <= expression.span.startOffset && it.span.endOffset >= expression.span.endOffset }
            .minByOrNull { it.span.endOffset - it.span.startOffset }
        val body = containingBlock?.children?.firstOrNull { it.kind == "compound_statement" }
        val localValues = linkedMapOf<String, Scalar>()
        val precedingDeclarations = body?.children.orEmpty()
            .filter { it.kind == "cplus_comptime_declaration" && it.span.endOffset <= expression.span.startOffset }
            .flatMap { wrapper -> wrapper.children.filter { it.kind == "cplus_comptime_value" } }
            .sortedBy { it.span.startOffset }
        for (declaration in precedingDeclarations) {
            val nameNode = declaration.children.firstOrNull { it.fieldName == "name" } ?: continue
            val name = parsed.source.text.substring(nameNode.span.startOffset, nameNode.span.endOffset).removePrefix("@")
            val typeNode = declaration.children.firstOrNull { it.fieldName == "type" }
            val type = typeNode?.let {
                parsed.source.text.substring(it.span.startOffset, it.span.endOffset).replace(Regex("\\s+"), " ").trim()
            }
            if (type !in SUPPORTED_INTEGER_TYPES) {
                evaluator.diagnostics += diagnostic(
                    "CPLUS_COMPTIME_SCALAR_TYPE",
                    "comptime block scalar declarations currently require an integer or bool type",
                    declaration.span
                )
                continue
            }
            if (name in localValues) {
                evaluator.diagnostics += diagnostic(
                    "CPLUS_COMPTIME_VALUE_DUPLICATE",
                    "duplicate comptime block scalar name '$name'",
                    declaration.span
                )
                continue
            }
            val initializer = declaration.children.firstOrNull {
                it.named && it.fieldName == null && it !== nameNode
            }
            val value = initializer?.let { evaluator.evaluate(it, localValues) }
            if (value == null) {
                if (evaluator.diagnostics.isEmpty()) evaluator.diagnostics += diagnostic(
                    "CPLUS_COMPTIME_SCALAR_INITIALIZER",
                    "comptime block scalar declarations require an initializer",
                    declaration.span
                )
            } else {
                localValues[name] = value
            }
        }
        val value = if (evaluator.diagnostics.isEmpty()) evaluator.evaluate(expression, localValues) else null
        if (value == null && evaluator.diagnostics.isEmpty()) {
            evaluator.diagnostics += diagnostic(
                "CPLUS_COMPTIME_SCALAR_UNSUPPORTED",
                "comptime block expression statements must evaluate to a supported scalar value",
                expression.span
            )
        }
        return evaluator.distinctDiagnostics()
    }

    fun lower(parsed: CPlusParseResult, source: MappedText): TreeSitterScalarLoweringResult {
        require(parsed.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }

        val declarations = collectScalarDeclarations(parsed)
        val values = declarations.values
        val scalarFunctions = declarations.scalarFunctions
        val inlineExpressions = declarations.inlineExpressions
        val identifierSplices = declarations.identifierSplices
        val scopedValues = declarations.scopedValues
        val declarationDiagnostics = declarations.diagnostics
        if (scopedValues.isNotEmpty()) {
            return TreeSitterScalarLoweringResult(
                source,
                scopedValues.map {
                    diagnostic(
                        "CPLUS_COMPTIME_VALUE_SCOPE",
                        "comptime scalar declarations are only supported at module scope in this prototype",
                        it.span
                    )
                }
            )
        }
        if (declarationDiagnostics.isNotEmpty()) return TreeSitterScalarLoweringResult(source, declarationDiagnostics)
        if (values.isEmpty() && scalarFunctions.isEmpty() && inlineExpressions.isEmpty() && identifierSplices.isEmpty()) {
            return TreeSitterScalarLoweringResult(source, emptyList())
        }

        val evaluator = ScalarEvaluator(
            parsed.source.text,
            values,
            scalarFunctions,
            targetOs,
            targetArch,
            primitiveTypedefAliases(parsed.root, parsed.source.text),
            aggregateLayouts(parsed.root, parsed.source.text, targetOs, targetArch)
        )
        val replacements = mutableListOf<Replacement>()
        scalarFunctions.forEach { function ->
            replacements += Replacement(
                function.replacement.span.startOffset,
                function.replacement.span.endOffset,
                MappedText.generated("", source.originAt(function.replacement.span.startOffset))
            )
        }
        values.forEach { declaration ->
            if (declaration.name.isBlank()) {
                evaluator.diagnostics += diagnostic(
                    "CPLUS_COMPTIME_VALUE_NAME", "comptime scalar name must not be empty", declaration.node.span
                )
            }
            if (declaration.expression == null) {
                evaluator.diagnostics += diagnostic(
                    "CPLUS_COMPTIME_VALUE_INITIALIZER",
                    "AST scalar materialization requires an initializer",
                    declaration.node.span
                )
            }
            val declaredType = declaration.type?.let {
                parsed.source.text.substring(it.span.startOffset, it.span.endOffset).replace(Regex("\\s+"), " ").trim()
            }
            if (declaredType !in SUPPORTED_INTEGER_TYPES) {
                evaluator.diagnostics += diagnostic(
                    "CPLUS_COMPTIME_SCALAR_TYPE",
                    "AST scalar materialization does not support comptime type '${declaredType ?: "<missing>"}'",
                    declaration.node.span
                )
            }
            val scalar = evaluator.evaluateName(declaration.name)
            if (scalar != null) {
                replacements += Replacement(
                    declaration.replacement.span.startOffset,
                    declaration.replacement.span.endOffset,
                    MappedText.generated("", source.originAt(declaration.replacement.span.startOffset))
                )
            }
        }
        inlineExpressions.forEach { inline ->
            val expression = inline.children.firstOrNull { it.named }
            if (expression == null) {
                evaluator.diagnostics += diagnostic(
                    "CPLUS_COMPTIME_SCALAR_EXPRESSION", "comptime expression has no AST expression", inline.span
                )
            } else {
                val scalar = evaluator.evaluate(expression)
                if (scalar != null) replacements += Replacement(
                    inline.span.startOffset,
                    inline.span.endOffset,
                    MappedText.generated(scalar.render(), source.originAt(inline.span.startOffset))
                )
            }
        }
        identifierSplices.forEach { splice ->
            val calls = descendants(splice).filter { it.kind == "cplus_at_call_expression" }.toList()
            if (calls.isEmpty()) {
                evaluator.diagnostics += diagnostic(
                    "CPLUS_COMPTIME_IDENTIFIER_SPLICE",
                    "interpolated identifiers must contain at least one scalar comptime call",
                    splice.span
                )
                return@forEach
            }
            val spliceValues = mutableListOf<Pair<CPlusSyntaxNode, kotlin.String>>()
            calls.forEach callLoop@{ call ->
                val value = evaluator.evaluate(call)
                val generatedName = (value as? Scalar.String)?.value
                if (generatedName == null) {
                    if (value != null) evaluator.diagnostics += diagnostic(
                        "CPLUS_COMPTIME_IDENTIFIER_SPLICE_TYPE",
                        "identifier splice function must return a comptime string",
                        call.span
                    )
                    return@callLoop
                }
                if (!generatedName.matches(C_IDENTIFIER_FRAGMENT)) {
                    evaluator.diagnostics += diagnostic(
                        "CPLUS_COMPTIME_IDENTIFIER_SPLICE_FRAGMENT",
                        "identifier splice returned '$generatedName', which contains characters outside [A-Za-z0-9_]",
                        call.span
                    )
                    return@callLoop
                }
                spliceValues += call to generatedName
            }
            if (spliceValues.size != calls.size) return@forEach
            spliceValues.forEach { (call, generatedName) ->
                replacements += Replacement(
                    call.span.startOffset,
                    call.span.endOffset,
                    MappedText.generated(generatedName, source.originAt(call.span.startOffset))
                )
            }
        }
        if (evaluator.diagnostics.isNotEmpty()) return TreeSitterScalarLoweringResult(source, evaluator.distinctDiagnostics())

        val output = MappedTextBuilder()
        var cursor = 0
        replacements.sortedBy { it.start }.forEach { replacement ->
            if (replacement.start < cursor) {
                evaluator.diagnostics += diagnostic(
                    "CPLUS_COMPTIME_SCALAR_OVERLAP",
                    "overlapping comptime scalar source ranges cannot be materialized",
                    parsed.source.sourceFile.span(replacement.start, replacement.end)
                )
                return@forEach
            }
            output.append(source, cursor, replacement.start)
            output.append(replacement.content)
            cursor = replacement.end
        }
        if (evaluator.diagnostics.isNotEmpty()) return TreeSitterScalarLoweringResult(source, evaluator.distinctDiagnostics())
        output.append(source, cursor, source.text.length)
        return TreeSitterScalarLoweringResult(output.build(), emptyList())
    }

    private fun collectScalarDeclarations(parsed: CPlusParseResult): ScalarDeclarations {
        val values = mutableListOf<ValueDeclaration>()
        val scalarFunctions = mutableListOf<ScalarFunctionDeclaration>()
        val inlineExpressions = mutableListOf<CPlusSyntaxNode>()
        val identifierSplices = mutableListOf<CPlusSyntaxNode>()
        val scopedValues = mutableListOf<CPlusSyntaxNode>()
        val diagnostics = mutableListOf<CPlusLoweringDiagnostic>()
        val text = parsed.source.text
        val comptimeIndex = cplus.CPlusComptimeIndexer().index(cplus.CPlusAstAdapter().adapt(parsed))

        fun collect(node: CPlusSyntaxNode, parent: CPlusSyntaxNode?, dormant: Boolean, moduleScope: Boolean) {
            val isGeneratorDefinition = node.kind in GENERATOR_DEFINITIONS
            val isDormant = dormant || (node.kind in DORMANT_REGIONS && !isGeneratorDefinition)
            if (!isDormant && node.kind == "cplus_comptime_function_definition" && moduleScope) {
                val indexed = comptimeIndex.constructs.firstOrNull {
                    it.syntaxKind == node.kind && it.span.startOffset == node.span.startOffset
                }
                val resultType = node.children.firstOrNull { it.fieldName == "result_kind" }
                    ?.let { text.substring(it.span.startOffset, it.span.endOffset).trim() }
                if (resultType in SUPPORTED_INTEGER_TYPES || resultType == "string") {
                    val body = node.children.firstOrNull { it.fieldName == "body" }
                    val returnStatements = body?.let(::descendants)
                        ?.filter { it.kind == "return_statement" }
                        ?.toList().orEmpty()
                    val bodyItems = body?.children.orEmpty().filter { it.named && it.kind != "comment" }
                    val returnStatement = returnStatements.singleOrNull()
                    val returnExpression = returnStatement?.children?.singleOrNull { it.named && it.kind != "comment" }
                    if (body == null || bodyItems.size != 1 || bodyItems.singleOrNull()?.span != returnStatement?.span || returnExpression == null) {
                        diagnostics += diagnostic(
                            "CPLUS_COMPTIME_SCALAR_FUNCTION_BODY",
                            "scalar comptime functions in this prototype must contain exactly one return expression",
                            node.span
                        )
                    } else if (indexed == null || indexed.parameters.any { parameter ->
                            parameter.name == null ||
                                if (parameter.genericType) false else parameter.typeText !in SUPPORTED_INTEGER_TYPES
                        }
                    ) {
                        diagnostics += diagnostic(
                            "CPLUS_COMPTIME_SCALAR_FUNCTION_PARAMETER",
                            "scalar comptime functions require named integer or bool parameters",
                            node.span
                        )
                    } else {
                        scalarFunctions += ScalarFunctionDeclaration(
                            node = node,
                            replacement = parent?.takeIf { it.kind == "cplus_comptime_declaration" } ?: node,
                            name = indexed.symbol.orEmpty(),
                            parameterNames = indexed.parameters.mapNotNull { it.name },
                            parameterTypes = indexed.parameters.map { it.typeText.orEmpty() },
                            typeParameters = indexed.parameters.map { it.genericType },
                            resultType = resultType.orEmpty(),
                            returnExpression = returnExpression
                        )
                    }
                }
            } else if (!isDormant && node.kind == "cplus_comptime_value") {
                val nameNode = node.children.firstOrNull { it.fieldName == "name" }
                if (nameNode != null) {
                    if (!moduleScope) {
                        scopedValues += node
                        node.children.forEach { collect(it, node, isDormant, false) }
                        return
                    }
                    val replacement = parent?.takeIf { it.kind == "cplus_comptime_declaration" } ?: node
                    val name = text.substring(nameNode.span.startOffset, nameNode.span.endOffset).removePrefix("@")
                    val type = node.children.firstOrNull { it.fieldName == "type" }
                    val expression = node.children.firstOrNull {
                        it.named && it.fieldName == null && it !== nameNode
                    }
                    values += ValueDeclaration(node, replacement, name, type, expression)
                }
            } else if (!isDormant && node.kind == "cplus_comptime_expression") {
                inlineExpressions += node
            } else if (!isDormant && node.kind == "cplus_interpolated_identifier") {
                identifierSplices += node
            }
            val nestedModuleScope = moduleScope && node.kind in MODULE_SCOPE_NODES
            val childrenAreDormant = isDormant || isGeneratorDefinition
            node.children.forEach { collect(it, node, childrenAreDormant, nestedModuleScope) }
        }

        collect(parsed.root, null, false, true)
        return ScalarDeclarations(values, scalarFunctions, inlineExpressions, identifierSplices, scopedValues, diagnostics)
    }

    private class ScalarEvaluator(
        private val text: String,
        declarations: List<ValueDeclaration>,
        scalarFunctions: List<ScalarFunctionDeclaration>,
        private val targetOs: String,
        private val targetArch: String,
        private val primitiveAliases: Map<String, String>,
        private val aggregateLayouts: Map<String, TreeSitterAbiAggregateLayout>
    ) {
        val diagnostics = mutableListOf<CPlusLoweringDiagnostic>()
        private val byName = linkedMapOf<String, ValueDeclaration>()
        private val functionsByName = linkedMapOf<String, ScalarFunctionDeclaration>()
        private val values = linkedMapOf<String, Scalar>()
        private val failedValues = linkedSetOf<String>()
        private val visiting = linkedSetOf<String>()
        private val activeFunctions = linkedSetOf<String>()

        init {
            declarations.forEach { declaration ->
                if (byName.putIfAbsent(declaration.name, declaration) != null) {
                    diagnostics += diagnostic(
                        "CPLUS_COMPTIME_VALUE_DUPLICATE",
                        "duplicate comptime scalar name '${declaration.name}'",
                        declaration.node.span
                    )
                }
            }
            scalarFunctions.forEach { function ->
                if (functionsByName.putIfAbsent(function.name, function) != null) {
                    diagnostics += diagnostic(
                        "CPLUS_COMPTIME_SCALAR_FUNCTION_DUPLICATE",
                        "duplicate scalar comptime function '${function.name}'",
                        function.node.span
                    )
                }
            }
        }

        fun evaluateName(name: String): Scalar? {
            values[name]?.let { return it }
            if (name in failedValues) return null
            val declaration = byName[name] ?: return null
            if (!visiting.add(name)) {
                diagnostics += diagnostic(
                    "CPLUS_COMPTIME_VALUE_CYCLE",
                    "cyclic comptime scalar dependency involving '$name'",
                    declaration.node.span
                )
                return null
            }
            val expression = declaration.expression
            val result = expression?.let { evaluate(it) }
            val converted = result?.let { convertDeclaredInteger(declaration, it) }
            visiting.remove(name)
            if (converted != null) values[name] = converted else failedValues += name
            return converted
        }

        private fun convertDeclaredInteger(declaration: ValueDeclaration, value: Scalar): Scalar? {
            val typeNode = declaration.type ?: return value
            val declarationNode = declaration.node
            val declaredType = textOf(typeNode).replace(Regex("\\s+"), " ").trim()
            val type = integerType(declaredType) ?: return if (declaredType == "char") {
                fail(
                    "CPLUS_COMPTIME_SCALAR_TYPE",
                    "plain char signedness is unavailable for target $targetArch-$targetOs",
                    typeNode
                )
            } else {
                value
            }
            return when {
                type.isBoolean -> Scalar.Bool(value.asBoolean() ?: return fail(
                    "CPLUS_COMPTIME_SCALAR_TYPE",
                    "comptime integer declaration '${declaration.name}' requires an integer or bool initializer",
                    declarationNode
                ))

                value.asInteger() == null -> fail(
                    "CPLUS_COMPTIME_SCALAR_TYPE",
                    "comptime integer declaration '${declaration.name}' requires an integer initializer",
                    declarationNode
                )

                type.isUnsigned -> {
                    val modulus = BigInteger.ONE.shiftLeft(type.bits)
                    val converted = value.asInteger()!!.mod(modulus)
                    Scalar.Integer(converted, type)
                }

                else -> {
                    val integer = value.asInteger()!!
                    if (integer < type.minimum || integer > type.maximum) {
                        fail(
                            "CPLUS_COMPTIME_SCALAR_WIDTH",
                            "value ${value.asInteger()} does not fit comptime type '${type.name}' on target $targetArch-$targetOs",
                            declarationNode
                        )
                    } else {
                        Scalar.Integer(integer, type)
                    }
                }
            }
        }

        fun evaluate(node: CPlusSyntaxNode, locals: Map<String, Scalar> = emptyMap()): Scalar? = when (node.kind) {
            "expression" -> node.children.singleOrNull { it.named }?.let { evaluate(it, locals) } ?: unsupported(node)
            "number_literal" -> parseInteger(textOf(node))
                ?: fail(
                    "CPLUS_COMPTIME_SCALAR_LITERAL",
                    "integer literal is malformed or does not fit a supported C integer type on target $targetArch-$targetOs",
                    node
                )
            "true" -> Scalar.Bool(true)
            "false" -> Scalar.Bool(false)
            "string_literal" -> evaluateStringLiteral(node)
            "concatenated_string" -> {
                val literals = node.children.filter { it.kind == "string_literal" }
                if (literals.isEmpty()) unsupported(node) else {
                    literals.map { evaluateStringLiteral(it) ?: return null }
                        .filterIsInstance<Scalar.String>()
                        .joinToString(separator = "") { it.value }
                        .let(Scalar::String)
                }
            }
            "identifier" -> {
                val name = textOf(node).removePrefix("@")
                locals[name] ?: evaluateName(name) ?: if (name in failedValues) null else
                    fail("CPLUS_COMPTIME_SCALAR_NAME", "unknown comptime scalar '$name'", node)
            }
            "type_identifier" -> {
                val name = textOf(node)
                locals[name] ?: evaluateName(name) ?: if (name in failedValues) null else
                    fail("CPLUS_COMPTIME_SCALAR_NAME", "unknown comptime value '$name'", node)
            }
            "cplus_type_argument" -> {
                // The C grammar must preserve the type-vs-expression ambiguity in generic
                // invocation arguments. Once the generator parameter is known to be scalar,
                // a bare identifier wrapped as a type argument is still eligible for value
                // lookup (for example `emit(count)` where `count` is a comptime scalar).
                val descriptor = node.children.singleOrNull { it.kind == "type_descriptor" }
                val baseType = descriptor?.children?.firstOrNull { it.fieldName == "type" }
                val declarator = descriptor?.children?.firstOrNull { it.fieldName == "declarator" }
                if (baseType != null && declarator == null && baseType.kind in setOf("type_identifier", "identifier")) {
                    evaluate(baseType, locals)
                } else {
                    unsupported(node)
                }
            }
            "parenthesized_expression" -> node.children.firstOrNull { it.named }?.let { evaluate(it, locals) } ?: unsupported(node)
            "cast_expression" -> evaluateCast(node, locals)
            "unary_expression" -> evaluateUnary(node, locals)
            "binary_expression" -> evaluateBinary(node, locals)
            "conditional_expression" -> evaluateConditional(node, locals)
            "call_expression", "cplus_at_call_expression" -> evaluateCall(node, locals)
            "field_expression" -> evaluateField(node, locals)
            else -> unsupported(node)
        }

        private fun evaluateField(node: CPlusSyntaxNode, locals: Map<String, Scalar>): Scalar? {
            val receiver = node.children.firstOrNull { it.fieldName == "argument" }
                ?: return unsupported(node)
            val field = node.children.firstOrNull { it.fieldName == "field" }
                ?: return unsupported(node)
            val value = evaluate(receiver, locals) ?: return null
            val fieldName = textOf(field)
            return when (value) {
                is Scalar.Type -> when (fieldName) {
                    "name" -> Scalar.String(value.name)
                    "size", "align" -> {
                        val layout = reflectedLayout(value.name)
                            ?: return fail(
                                "CPLUS_COMPTIME_REFLECTION_LAYOUT",
                                "${value.name}.$fieldName is unavailable for target ABI $targetArch-$targetOs",
                                field
                            )
                        Scalar.Integer(if (fieldName == "size") layout.size else layout.alignment)
                    }
                    else -> fail(
                        "CPLUS_COMPTIME_REFLECTION_PROPERTY",
                        "unknown reflected type property '${value.name}.$fieldName'",
                        field
                    )
                }
                else -> fail(
                    "CPLUS_COMPTIME_REFLECTION_RECEIVER",
                    "property reflection requires a comptime type value",
                    receiver
                )
            }
        }

        private fun evaluateCast(node: CPlusSyntaxNode, locals: Map<String, Scalar>): Scalar? {
            val descriptor = node.children.firstOrNull { it.fieldName == "type" }
                ?: return unsupported(node)
            val valueNode = node.children.firstOrNull { it.fieldName == "value" }
                ?: return unsupported(node)
            val declarator = descriptor.children.firstOrNull { it.fieldName == "declarator" }
            if (declarator != null) {
                return fail(
                    "CPLUS_COMPTIME_SCALAR_CAST",
                    "pointer, array, and function casts are not supported in scalar comptime expressions",
                    descriptor
                )
            }
            val typeNode = descriptor.children.firstOrNull { it.fieldName == "type" }
                ?: return unsupported(descriptor)
            val type = integerType(textOf(typeNode))
                ?: return fail(
                    "CPLUS_COMPTIME_SCALAR_CAST",
                    "comptime scalar casts require a supported integer or bool target type",
                    typeNode
                )
            val value = evaluate(valueNode, locals)
                ?: return null
            val integer = value.asInteger()
                ?: return fail(
                    "CPLUS_COMPTIME_SCALAR_CAST",
                    "comptime scalar casts require an integer or bool source value",
                    valueNode
                )
            if (type.isBoolean) return Scalar.Integer(if (integer == BigInteger.ZERO) 0L else 1L, type)
            if (type.isUnsigned) {
                val converted = integer.mod(BigInteger.ONE.shiftLeft(type.bits))
                return Scalar.Integer(converted, type)
            }
            if (integer < type.minimum || integer > type.maximum) {
                return fail(
                    "CPLUS_COMPTIME_SCALAR_CAST",
                    "value $integer does not fit comptime cast target '${type.name}'",
                    node
                )
            }
            return Scalar.Integer(integer, type)
        }

        private fun evaluateUnary(node: CPlusSyntaxNode, locals: Map<String, Scalar>): Scalar? {
            val operand = node.children.lastOrNull { it.named } ?: return unsupported(node)
            val value = evaluate(operand, locals) ?: return null
            val operator = text.substring(node.span.startOffset, operand.span.startOffset).trim()
            return when (operator) {
                "+" -> promoteInteger(node, value)
                "-" -> {
                    val promoted = promoteInteger(node, value) ?: return null
                    val integer = promoted as? Scalar.Integer ?: return unsupported(node)
                    val type = integer.type ?: return unsupported(node)
                    normalizeArithmeticResult(
                        integer.value.negate(),
                        type,
                        node
                    )
                }
                "!" -> value.asBoolean()?.let { Scalar.Bool(!it) } ?: unsupported(node)
                "~" -> {
                    val promoted = promoteInteger(node, value) ?: return null
                    val promotedInteger = promoted as? Scalar.Integer ?: return unsupported(node)
                    val type = promotedInteger.type ?: return unsupported(node)
                    val integer = promotedInteger.value
                    val mask = BigInteger.ONE.shiftLeft(type.bits).subtract(BigInteger.ONE)
                    val result = if (type.isUnsigned) integer.xor(mask) else integer.not()
                    normalizeArithmeticResult(result, type, node)
                }
                else -> unsupported(node)
            }
        }

        private fun evaluateBinary(node: CPlusSyntaxNode, locals: Map<String, Scalar>): Scalar? {
            val operands = node.children.filter { it.named }
            if (operands.size != 2) return unsupported(node)
            val left = evaluate(operands[0], locals) ?: return null
            val operator = text.substring(operands[0].span.endOffset, operands[1].span.startOffset).trim()
            if (operator == "&&" || operator == "||") {
            val leftTruth = left.asBoolean() ?: return unsupported(operands[0])
                if (operator == "&&" && !leftTruth) return Scalar.Bool(false)
                if (operator == "||" && leftTruth) return Scalar.Bool(true)
                val right = evaluate(operands[1], locals) ?: return null
                val rightTruth = right.asBoolean() ?: return unsupported(operands[1])
                return Scalar.Bool(if (operator == "&&") leftTruth && rightTruth else leftTruth || rightTruth)
            }
            val right = evaluate(operands[1], locals) ?: return null
            val a = left.asInteger()
            val b = right.asInteger()
            return when (operator) {
                "+" -> when {
                    left is Scalar.String && right is Scalar.String -> Scalar.String(left.value + right.value)
                    a != null && b != null -> integerArithmetic(node, left, right) { leftValue, rightValue ->
                        leftValue.add(rightValue)
                    }
                    else -> unsupported(node)
                }
                "-" -> if (a != null && b != null) integerArithmetic(node, left, right) { leftValue, rightValue ->
                    leftValue.subtract(rightValue)
                } else unsupported(node)
                "*" -> if (a != null && b != null) integerArithmetic(node, left, right) { leftValue, rightValue ->
                    leftValue.multiply(rightValue)
                } else unsupported(node)
                "/" -> when {
                    a == null || b == null || b == BigInteger.ZERO -> arithmeticError(node, "division requires integer operands and a nonzero divisor")
                    else -> integerArithmetic(node, left, right) { leftValue, rightValue ->
                        leftValue.divide(rightValue)
                    }
                }
                "%" -> when {
                    a == null || b == null || b == BigInteger.ZERO -> arithmeticError(node, "remainder requires integer operands and a nonzero divisor")
                    else -> integerArithmetic(node, left, right) { leftValue, rightValue ->
                        leftValue.remainder(rightValue)
                    }
                }
                "==" -> compareIntegers(left, right, node) { leftValue, rightValue -> leftValue == rightValue }
                    ?: Scalar.Bool(left == right)
                "!=" -> compareIntegers(left, right, node) { leftValue, rightValue -> leftValue != rightValue }
                    ?: Scalar.Bool(left != right)
                "<" -> compareIntegers(left, right, node) { leftValue, rightValue -> leftValue < rightValue }
                    ?: unsupported(node)
                "<=" -> compareIntegers(left, right, node) { leftValue, rightValue -> leftValue <= rightValue }
                    ?: unsupported(node)
                ">" -> compareIntegers(left, right, node) { leftValue, rightValue -> leftValue > rightValue }
                    ?: unsupported(node)
                ">=" -> compareIntegers(left, right, node) { leftValue, rightValue -> leftValue >= rightValue }
                    ?: unsupported(node)
                "&" -> if (a != null && b != null) integerArithmetic(node, left, right) { leftValue, rightValue ->
                    leftValue.and(rightValue)
                } else unsupported(node)
                "|" -> if (a != null && b != null) integerArithmetic(node, left, right) { leftValue, rightValue ->
                    leftValue.or(rightValue)
                } else unsupported(node)
                "^" -> if (a != null && b != null) integerArithmetic(node, left, right) { leftValue, rightValue ->
                    leftValue.xor(rightValue)
                } else unsupported(node)
                "<<" -> integerShift(node, left, right, leftShift = true)
                ">>" -> integerShift(node, left, right, leftShift = false)
                else -> unsupported(node)
            }
        }

        private fun evaluateConditional(node: CPlusSyntaxNode, locals: Map<String, Scalar>): Scalar? {
            val condition = node.children.firstOrNull { it.fieldName == "condition" }
                ?: return unsupported(node)
            val conditionValue = evaluate(condition, locals) ?: return null
            val conditionIsTrue = conditionValue.asBoolean() ?: return unsupported(condition)
            val selected = if (conditionIsTrue) {
                node.children.firstOrNull { it.fieldName == "consequence" }
            } else {
                node.children.firstOrNull { it.fieldName == "alternative" }
            } ?: return unsupported(node)
            return evaluate(selected, locals)
        }

        private fun compareIntegers(
            left: Scalar,
            right: Scalar,
            node: CPlusSyntaxNode,
            comparison: (BigInteger, BigInteger) -> Boolean
        ): Scalar? {
            if (left.asInteger() == null || right.asInteger() == null) return null
            val commonType = commonIntegerType(left, right)
                ?: return fail(
                    "CPLUS_COMPTIME_SCALAR_TYPE",
                    "integer comparison requires supported integer operands",
                    node
                )
            val leftValue = convertForComparison(left.asInteger()!!, commonType)
            val rightValue = convertForComparison(right.asInteger()!!, commonType)
            return Scalar.Bool(comparison(leftValue, rightValue))
        }

        private fun integerArithmetic(
            node: CPlusSyntaxNode,
            left: Scalar,
            right: Scalar,
            operation: (BigInteger, BigInteger) -> BigInteger
        ): Scalar? {
            val commonType = commonIntegerType(left, right)
                ?: return fail(
                    "CPLUS_COMPTIME_SCALAR_TYPE",
                    "integer arithmetic requires supported integer operands",
                    node
                )
            val leftValue = convertForComparison(left.asInteger()!!, commonType)
            val rightValue = convertForComparison(right.asInteger()!!, commonType)
            val result = operation(leftValue, rightValue)
            return normalizeArithmeticResult(result, commonType, node)
        }

        private fun promoteInteger(node: CPlusSyntaxNode, value: Scalar): Scalar? {
            val integer = value.asInteger()
                ?: return unsupported(node)
            val type = promotedIntegerType(value)
                ?: return fail(
                    "CPLUS_COMPTIME_SCALAR_TYPE",
                    "unary integer operator requires a supported integer operand",
                    node
                )
            val normalized = convertForComparison(integer, type)
            return Scalar.Integer(normalized, type)
        }

        private fun integerShift(
            node: CPlusSyntaxNode,
            left: Scalar,
            right: Scalar,
            leftShift: Boolean
        ): Scalar? {
            val leftInteger = left.asInteger()
            val rightInteger = right.asInteger()
            if (leftInteger == null || rightInteger == null) {
                return arithmeticError(node, "shift requires integer operands")
            }
            val leftType = promotedIntegerType(left)
                ?: return fail(
                    "CPLUS_COMPTIME_SCALAR_TYPE",
                    "shift requires a supported integer left operand",
                    node
                )
            val shiftCount = rightInteger
            if (shiftCount.signum() < 0 || shiftCount >= BigInteger.valueOf(leftType.bits.toLong())) {
                return arithmeticError(
                    node,
                    "shift count must be between 0 and ${leftType.bits - 1} for ${leftType.name}"
                )
            }
            val leftValue = convertForComparison(leftInteger, leftType)
            if (!leftType.isUnsigned && leftValue.signum() < 0) {
                return arithmeticError(
                    node,
                    "signed shift requires a nonnegative left operand"
                )
            }
            val count = shiftCount.toInt()
            val result = if (leftShift) leftValue.shiftLeft(count) else leftValue.shiftRight(count)
            return normalizeArithmeticResult(result, leftType, node)
        }

        private fun normalizeArithmeticResult(
            value: BigInteger,
            type: ComptimeIntegerType,
            node: CPlusSyntaxNode
        ): Scalar? {
            val normalized = if (type.isUnsigned) {
                value.mod(BigInteger.ONE.shiftLeft(type.bits))
            } else {
                if (value < type.minimum || value > type.maximum) {
                    return arithmeticError(node, "integer overflow in comptime ${type.name} arithmetic")
                }
                value
            }
            return Scalar.Integer(normalized, type)
        }

        private fun commonIntegerType(left: Scalar, right: Scalar): ComptimeIntegerType? {
            val leftType = promotedIntegerType(left) ?: return null
            val rightType = promotedIntegerType(right) ?: return null
            if (leftType.isUnsigned == rightType.isUnsigned) {
                return if (leftType.rank >= rightType.rank) leftType else rightType
            }
            val unsigned = if (leftType.isUnsigned) leftType else rightType
            val signed = if (leftType.isUnsigned) rightType else leftType
            return when {
                signed.bits > unsigned.bits -> signed
                unsigned.rank >= signed.rank -> unsigned
                else -> integerTypeByRank(signed.rank, unsigned = true)
            }
        }

        private fun promotedIntegerType(value: Scalar): ComptimeIntegerType? {
            val type = when (value) {
                is Scalar.Bool -> integerType("int")
                is Scalar.Integer -> value.type ?: integerType("int")
                else -> null
            } ?: return null
            if (type.isBoolean || type.rank < 3) {
                return if (!type.isUnsigned || type.bits < 32) integerType("int") else integerType("unsigned int")
            }
            return type
        }

        private fun convertForComparison(value: BigInteger, type: ComptimeIntegerType): BigInteger {
            val integer = value
            return if (type.isUnsigned) {
                integer.mod(BigInteger.ONE.shiftLeft(type.bits))
            } else {
                integer
            }
        }

        private fun evaluateStringLiteral(node: CPlusSyntaxNode): Scalar? {
            val raw = textOf(node)
            if (!raw.startsWith('"') || !raw.endsWith('"') || raw.length < 2) {
                return fail(
                    "CPLUS_COMPTIME_SCALAR_STRING",
                    "only ordinary quoted C string literals are supported in comptime strings",
                    node
                )
            }
            val decoded = StringBuilder(raw.length - 2)
            var index = 1
            val end = raw.lastIndex
            while (index < end) {
                val character = raw[index++]
                if (character != '\\') {
                    decoded.append(character)
                    continue
                }
                if (index >= end) return fail(
                    "CPLUS_COMPTIME_SCALAR_STRING",
                    "incomplete escape in comptime string literal",
                    node
                )
                decoded.append(when (val escaped = raw[index++]) {
                    'a' -> '\u0007'
                    'b' -> '\b'
                    'f' -> '\u000c'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'v' -> '\u000b'
                    '\\' -> '\\'
                    '\'' -> '\''
                    '"' -> '"'
                    '?' -> '?'
                    else -> return fail(
                        "CPLUS_COMPTIME_SCALAR_STRING",
                        "unsupported escape \\$escaped in comptime string literal",
                        node
                    )
                })
            }
            return Scalar.String(decoded.toString())
        }

        private fun evaluateCall(node: CPlusSyntaxNode, locals: Map<String, Scalar>): Scalar? {
            val callee = node.children.firstOrNull { it.fieldName == "function" }
                ?: node.children.firstOrNull { it.kind == "identifier" }
                ?: return unsupported(node)
            val name = textOf(callee).removePrefix("@")
            val function = functionsByName[name]
                ?: return fail(
                    "CPLUS_COMPTIME_SCALAR_FUNCTION_NAME",
                    "unknown scalar comptime function '$name'",
                    callee
                )
            val arguments = node.children.firstOrNull { it.kind == "argument_list" }
                ?.children.orEmpty()
                .filter { it.named && it.kind != "comment" }
            if (arguments.size != function.parameterNames.size) {
                return fail(
                    "CPLUS_COMPTIME_SCALAR_FUNCTION_ARITY",
                    "scalar comptime function '$name' expects ${function.parameterNames.size} argument(s), got ${arguments.size}",
                    node
                )
            }
            if (name in activeFunctions) {
                return fail(
                    "CPLUS_COMPTIME_SCALAR_FUNCTION_RECURSION",
                    "recursive scalar comptime function '$name' is not supported",
                    node
                )
            }
            if (activeFunctions.size >= MAX_FUNCTION_DEPTH) {
                return fail(
                    "CPLUS_COMPTIME_SCALAR_FUNCTION_DEPTH",
                    "scalar comptime function evaluation exceeded the depth limit",
                    node
                )
            }
            val argumentValues = arguments.mapIndexed { index, argument ->
                if (function.typeParameters[index]) {
                    evaluateTypeArgument(argument, locals) ?: return null
                } else {
                    val value = evaluate(argument, locals) ?: return null
                    convertFunctionIntegerValue(function, index, value, argument) ?: return null
                }
            }
            activeFunctions += name
            val functionLocals = function.parameterNames.zip(argumentValues).toMap()
            val result = evaluate(function.returnExpression, functionLocals)
            activeFunctions -= name
            if (result == null) return null
            if (function.resultType == "string") {
                return if (result is Scalar.String) result else fail(
                    "CPLUS_COMPTIME_SCALAR_FUNCTION_RETURN",
                    "scalar comptime function '$name' must return a string value",
                    node
                )
            }
            val returnType = integerType(function.resultType)
                ?: return fail(
                    "CPLUS_COMPTIME_SCALAR_FUNCTION_RETURN",
                    "scalar comptime function '$name' has an unsupported integer return type",
                    node
                )
            return convertIntegerValue(
                result,
                returnType,
                node,
                "CPLUS_COMPTIME_SCALAR_FUNCTION_RETURN",
                "return value from '$name'"
            )
        }

        private fun convertFunctionIntegerValue(
            function: ScalarFunctionDeclaration,
            index: Int,
            value: Scalar,
            argument: CPlusSyntaxNode
        ): Scalar? {
            val type = integerType(function.parameterTypes[index])
                ?: return fail(
                    "CPLUS_COMPTIME_SCALAR_FUNCTION_PARAMETER",
                    "scalar comptime function '${function.name}' has an unsupported integer parameter type",
                    argument
                )
            return convertIntegerValue(
                value,
                type,
                argument,
                "CPLUS_COMPTIME_SCALAR_FUNCTION_CONVERSION",
                "argument for '${function.name}'"
            )
        }

        private fun convertIntegerValue(
            value: Scalar,
            type: ComptimeIntegerType,
            node: CPlusSyntaxNode,
            code: String,
            context: String
        ): Scalar? {
            val integer = value.asInteger()
                ?: return fail(code, "$context requires an integer or bool value", node)
            if (type.isBoolean) return Scalar.Integer(if (integer == BigInteger.ZERO) 0L else 1L, type)
            if (type.isUnsigned) {
                val converted = integer.mod(BigInteger.ONE.shiftLeft(type.bits))
                return Scalar.Integer(converted, type)
            }
            if (integer < type.minimum || integer > type.maximum) {
                return fail(code, "$context does not fit '${type.name}'", node)
            }
            return Scalar.Integer(integer, type)
        }

        private fun evaluateTypeArgument(node: CPlusSyntaxNode, locals: Map<String, Scalar>): Scalar? {
            val raw = textOf(node).trim()
            (locals[raw] as? Scalar.Type)?.let { return it }
            if (node.kind in setOf("primitive_type", "type_identifier", "identifier") &&
                raw.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))
            ) return Scalar.Type(raw)
            if (node.kind == "cplus_type_argument") {
                val descriptor = node.children.singleOrNull { it.kind == "type_descriptor" }
                val baseType = descriptor?.children?.firstOrNull { it.fieldName == "type" }
                val declarator = descriptor?.children?.firstOrNull { it.fieldName == "declarator" }
                if (baseType != null && baseType.kind in setOf("primitive_type", "type_identifier", "identifier") &&
                    (declarator == null || isPointerOnly(declarator))
                ) return Scalar.Type(raw)
            }
            return fail(
                "CPLUS_COMPTIME_REFLECTION_TYPE_ARGUMENT",
                "this reflection step accepts primitive or named type arguments",
                node
            )
        }

        private fun isPointerOnly(node: CPlusSyntaxNode): Boolean = when (node.kind) {
            "abstract_pointer_declarator" -> node.children.all { child ->
                !child.named || child.kind in POINTER_QUALIFIER_NODES ||
                    child.kind == "abstract_pointer_declarator" && isPointerOnly(child)
            }
            "abstract_parenthesized_declarator" -> node.children.filter { it.named }.singleOrNull()
                ?.let(::isPointerOnly) == true
            else -> false
        }

        private fun reflectedLayout(rawType: String): AbiLayout? {
            if (targetArch !in SUPPORTED_LAYOUT_ARCHITECTURES || targetOs !in SUPPORTED_LAYOUT_OPERATING_SYSTEMS) {
                return null
            }
            var type = rawType.replace(Regex("\\b(const|volatile|restrict)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim()
            aggregateLayouts[type]?.let { aggregate ->
                return AbiLayout(aggregate.size, aggregate.alignment)
            }
            val visitedAliases = mutableSetOf<String>()
            while (visitedAliases.add(type)) {
                type = primitiveAliases[type] ?: break
            }
            aggregateLayouts[type]?.let { aggregate ->
                return AbiLayout(aggregate.size, aggregate.alignment)
            }
            if ('*' in type) {
                val pointerSize = when (targetArch) {
                    "x86_64", "arm64" -> 8L
                    else -> return null
                }
                return AbiLayout(pointerSize, pointerSize)
            }
            val canonical = canonicalPrimitiveType(type, linkedSetOf()) ?: return null
            val size = when (canonical) {
                "char", "bool" -> 1L
                "short" -> 2L
                "int", "float" -> 4L
                "long" -> when {
                    targetOs == "windows" -> 4L
                    else -> 8L
                }
                "long long", "double" -> 8L
                else -> return null
            }
            return AbiLayout(size, size)
        }

        private fun integerType(rawType: String): ComptimeIntegerType? {
            var normalized = rawType.replace(Regex("\\s+"), " ").trim()
            val visited = mutableSetOf<String>()
            while (visited.add(normalized)) {
                val alias = primitiveAliases[normalized] ?: break
                normalized = alias.replace(Regex("\\s+"), " ").trim()
            }
            val longBits = if (targetOs == "windows") 32 else 64
            return when (normalized) {
                "_Bool", "bool" -> ComptimeIntegerType(normalized, 1, false, true)
                "signed char" -> ComptimeIntegerType(normalized, 8, false, rank = 1)
                "unsigned char" -> ComptimeIntegerType(normalized, 8, true, rank = 1)
                "short", "short int", "signed short", "signed short int" ->
                    ComptimeIntegerType(normalized, 16, false, rank = 2)
                "unsigned short", "unsigned short int" -> ComptimeIntegerType(normalized, 16, true, rank = 2)
                "int", "signed", "signed int" -> ComptimeIntegerType(normalized, 32, false, rank = 3)
                "unsigned", "unsigned int" -> ComptimeIntegerType(normalized, 32, true, rank = 3)
                "long", "long int", "signed long", "signed long int" ->
                    ComptimeIntegerType(normalized, longBits, false, rank = 4)
                "unsigned long", "unsigned long int" -> ComptimeIntegerType(normalized, longBits, true, rank = 4)
                "long long", "long long int", "signed long long", "signed long long int" ->
                    ComptimeIntegerType(normalized, 64, false, rank = 5)
                "unsigned long long", "unsigned long long int" -> ComptimeIntegerType(normalized, 64, true, rank = 5)
                "char" -> cplus.CPlusTarget.plainCharIsUnsigned(targetOs, targetArch)?.let { unsigned ->
                    ComptimeIntegerType(normalized, 8, unsigned, rank = 1)
                }
                else -> null
            }
        }

        private fun integerTypeByRank(rank: Int, unsigned: Boolean): ComptimeIntegerType = when (rank) {
            1 -> integerType(if (unsigned) "unsigned char" else "signed char")!!
            2 -> integerType(if (unsigned) "unsigned short" else "short")!!
            3 -> integerType(if (unsigned) "unsigned int" else "int")!!
            4 -> integerType(if (unsigned) "unsigned long" else "long")!!
            else -> integerType(if (unsigned) "unsigned long long" else "long long")!!
        }

        private fun canonicalPrimitiveType(type: String, visited: MutableSet<String>): String? {
            if (!visited.add(type)) return null
            val alias = primitiveAliases[type]
            if (alias != null) return canonicalPrimitiveType(alias, visited)
            val normalized = type.replace(Regex("\\s+"), " ").trim()
            return when (normalized) {
                "char", "signed char", "unsigned char" -> "char"
                "_Bool", "bool" -> "bool"
                "short", "short int", "signed short", "signed short int", "unsigned short", "unsigned short int" -> "short"
                "int", "signed", "signed int", "unsigned", "unsigned int" -> "int"
                "long", "long int", "signed long", "signed long int", "unsigned long", "unsigned long int" -> "long"
                "long long", "long long int", "signed long long", "signed long long int", "unsigned long long", "unsigned long long int" -> "long long"
                "float" -> "float"
                "double" -> "double"
                else -> null
            }
        }

        private fun arithmetic(node: CPlusSyntaxNode, operation: () -> Long): Scalar? = try {
            Scalar.Integer(operation())
        } catch (_: ArithmeticException) {
            arithmeticError(node, "integer overflow in comptime scalar expression")
        }

        private fun arithmeticError(node: CPlusSyntaxNode, message: String): Scalar? =
            fail("CPLUS_COMPTIME_SCALAR_ARITHMETIC", message, node)

        private fun unsupported(node: CPlusSyntaxNode): Scalar? =
            fail("CPLUS_COMPTIME_SCALAR_UNSUPPORTED", "unsupported AST node '${node.kind}' in scalar comptime expression", node)

        private fun fail(code: String, message: String, node: CPlusSyntaxNode): Scalar? {
            diagnostics += diagnostic(code, message, node.span)
            return null
        }

        private fun textOf(node: CPlusSyntaxNode): String = text.substring(node.span.startOffset, node.span.endOffset)

        fun distinctDiagnostics(): List<CPlusLoweringDiagnostic> = diagnostics.distinctBy {
            Triple(it.code, it.span.startOffset, it.message)
        }

        private fun parseInteger(raw: String): Scalar? {
            val literal = INTEGER_LITERAL.matchEntire(raw) ?: return null
            val signedValue = literal.groupValues[1]
            val suffix = literal.groupValues[2]
            val isNegative = signedValue.startsWith('-')
            val value = signedValue.removePrefix("-").removePrefix("+")
            val radix = when {
                value.startsWith("0x", true) -> 16
                value.startsWith("0b", true) -> 2
                value.length > 1 && value.startsWith('0') -> 8
                else -> 10
            }
            val digits = when (radix) {
                16, 2 -> value.drop(2)
                8 -> value.drop(1)
                else -> value
            }
            val magnitude = digits.toBigIntegerOrNull(radix) ?: return null
            val signed = if (isNegative) magnitude.negate() else magnitude
            val type = literalIntegerType(suffix, radix, magnitude) ?: return null
            val materialized = if (isNegative && type.isUnsigned) {
                signed.mod(BigInteger.ONE.shiftLeft(type.bits))
            } else {
                signed
            }
            if (!type.isUnsigned && (materialized < type.minimum || materialized > type.maximum)) return null
            return Scalar.Integer(materialized, type)
        }

        private fun literalIntegerType(
            suffix: String,
            radix: Int,
            magnitude: BigInteger
        ): ComptimeIntegerType? {
            val decimal = radix == 10
            val candidates = when (suffix.lowercase()) {
                "" -> if (decimal) {
                    listOf("int", "long", "long long")
                } else {
                    listOf("int", "unsigned int", "long", "unsigned long", "long long", "unsigned long long")
                }
                "u" -> listOf("unsigned int", "unsigned long", "unsigned long long")
                "l" -> if (decimal) {
                    listOf("long", "long long")
                } else {
                    listOf("long", "unsigned long", "long long", "unsigned long long")
                }
                "ul", "lu" -> listOf("unsigned long", "unsigned long long")
                "ll" -> if (decimal) {
                    listOf("long long")
                } else {
                    listOf("long long", "unsigned long long")
                }
                "ull", "llu" -> listOf("unsigned long long")
                else -> emptyList()
            }
            return candidates.asSequence()
                .mapNotNull(::integerType)
                .firstOrNull { type -> magnitude <= type.maximum }
        }

        private companion object {
            const val MAX_FUNCTION_DEPTH = 128
            val INTEGER_LITERAL = Regex(
                """([+-]?(?:0[xX][0-9a-fA-F]+|0[bB][01]+|0[0-7]*|[1-9][0-9]*))(([uU](?:[lL]{1,2})?|[lL]{1,2}[uU]?))?"""
            )
        }
    }

    private data class ValueDeclaration(
        val node: CPlusSyntaxNode,
        val replacement: CPlusSyntaxNode,
        val name: String,
        val type: CPlusSyntaxNode?,
        val expression: CPlusSyntaxNode?
    )

    private data class ScalarDeclarations(
        val values: List<ValueDeclaration>,
        val scalarFunctions: List<ScalarFunctionDeclaration>,
        val inlineExpressions: List<CPlusSyntaxNode>,
        val identifierSplices: List<CPlusSyntaxNode>,
        val scopedValues: List<CPlusSyntaxNode>,
        val diagnostics: List<CPlusLoweringDiagnostic>
    )

    private data class ScalarFunctionDeclaration(
        val node: CPlusSyntaxNode,
        val replacement: CPlusSyntaxNode,
        val name: String,
        val parameterNames: List<String>,
        val parameterTypes: List<String>,
        val typeParameters: List<Boolean>,
        val resultType: String,
        val returnExpression: CPlusSyntaxNode
    )

    private data class AbiLayout(val size: Long, val alignment: Long)

    private data class ComptimeIntegerType(
        val name: String,
        val bits: Int,
        val isUnsigned: Boolean,
        val isBoolean: Boolean = false,
        val rank: Int = 3
    ) {
        val minimum: BigInteger = if (isUnsigned) BigInteger.ZERO else BigInteger.ONE.shiftLeft(bits - 1).negate()
        val maximum: BigInteger = if (isUnsigned) BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
        else BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE)
    }

    private sealed interface Scalar {
        fun render(): kotlin.String
        fun asInteger(): BigInteger? = when (this) {
            is Integer -> value
            is Bool -> if (value) BigInteger.ONE else BigInteger.ZERO
            else -> null
        }
        fun asBoolean(): Boolean? = when (this) {
            is Bool -> value
            is Integer -> value != BigInteger.ZERO
            else -> null
        }

        data class Integer(val value: BigInteger, val type: ComptimeIntegerType? = null) : Scalar {
            constructor(value: Long, type: ComptimeIntegerType? = null) : this(BigInteger.valueOf(value), type)

            override fun render(): kotlin.String {
                val suffix = if (value > LONG_MAX && type?.isUnsigned == true) when (type.name) {
                    "unsigned long" -> "UL"
                    else -> "ULL"
                } else ""
                return value.toString() + suffix
            }
        }
        data class Bool(val value: Boolean) : Scalar { override fun render() = if (value) "1" else "0" }
        data class String(val value: kotlin.String) : Scalar {
            override fun render() = buildString {
                append('"')
                value.toByteArray(Charsets.UTF_8).forEach { byte ->
                    val octet = byte.toInt() and 0xff
                    append(when (octet) {
                        0x07 -> "\\a"
                        0x08 -> "\\b"
                        0x09 -> "\\t"
                        0x0a -> "\\n"
                        0x0b -> "\\v"
                        0x0c -> "\\f"
                        0x0d -> "\\r"
                        0x22 -> "\\\""
                        0x5c -> "\\\\"
                        in 0x20..0x7e -> octet.toChar().toString()
                        else -> "\\" + octet.toString(8).padStart(3, '0')
                    })
                }
                append('"')
            }
        }
        data class Type(val name: kotlin.String) : Scalar { override fun render() = name }

        private companion object {
            val LONG_MAX: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
        }
    }

    private data class Replacement(val start: Int, val end: Int, val content: MappedText)

    private fun descendants(node: CPlusSyntaxNode): Sequence<CPlusSyntaxNode> =
        sequenceOf(node) + node.children.asSequence().flatMap(::descendants)

    private fun primitiveTypedefAliases(root: CPlusSyntaxNode, text: String): Map<String, String> =
        descendants(root).asSequence()
            .filter { it.kind == "type_definition" }
            .mapNotNull { typedef ->
                val type = typedef.children.firstOrNull { it.fieldName == "type" } ?: return@mapNotNull null
                val declarator = typedef.children.firstOrNull { it.fieldName == "declarator" } ?: return@mapNotNull null
                val identifiers = descendants(declarator).filter { it.kind in setOf("identifier", "type_identifier") }
                val alias = identifiers.firstOrNull() ?: return@mapNotNull null
                if (descendants(declarator).any { it.kind in setOf("array_declarator", "function_declarator") }) {
                    return@mapNotNull null
                }
                val target = text.substring(type.span.startOffset, type.span.endOffset)
                    .replace(Regex("\\b(const|volatile|restrict)\\b"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                val pointerDepth = descendants(declarator).count { it.kind == "pointer_declarator" }
                text.substring(alias.span.startOffset, alias.span.endOffset) to target + "*".repeat(pointerDepth)
            }
            .toMap()

    private fun aggregateLayouts(
        root: CPlusSyntaxNode,
        text: String,
        targetOs: String,
        targetArch: String
    ): Map<String, TreeSitterAbiAggregateLayout> =
        computeTreeSitterAbiAggregateLayouts(root, text, targetOs, targetArch)

    private companion object {
        val DORMANT_REGIONS = setOf(
            "cplus_comptime_function_definition", "cplus_legacy_function_generator",
            "cplus_legacy_type_generator", "cplus_comptime_block", "cplus_code_fragment"
        )
        val GENERATOR_DEFINITIONS = setOf(
            "cplus_comptime_function_definition", "cplus_legacy_function_generator", "cplus_legacy_type_generator"
        )
        val MODULE_SCOPE_NODES = setOf(
            "translation_unit", "cplus_comptime_declaration", "preproc_if", "preproc_else", "preproc_elif",
            "preproc_ifdef", "preproc_ifndef", "preproc_elifdef", "preproc_elifndef"
        )
        val SUPPORTED_INTEGER_TYPES = setOf(
            "char", "signed char", "unsigned char", "short", "short int", "signed short", "signed short int",
            "unsigned short", "unsigned short int", "int", "signed", "signed int", "unsigned", "unsigned int",
            "long", "long int", "signed long", "signed long int", "unsigned long", "unsigned long int",
            "long long", "long long int", "signed long long", "signed long long int",
            "unsigned long long", "unsigned long long int", "bool", "_Bool"
        )
        val POINTER_QUALIFIER_NODES = setOf("type_qualifier", "const", "volatile", "restrict")
        val SUPPORTED_LAYOUT_ARCHITECTURES = setOf("x86_64", "arm64")
        val SUPPORTED_LAYOUT_OPERATING_SYSTEMS = setOf("linux", "windows", "macos")
        val C_IDENTIFIER_FRAGMENT = Regex("[A-Za-z0-9_]+")
        fun diagnostic(code: String, message: String, span: cplus.SourceSpan) =
            CPlusLoweringDiagnostic(code, message, span)
    }
}
