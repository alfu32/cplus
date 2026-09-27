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
            primitiveTypedefAliases(parsed.root, parsed.source.text)
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
            primitiveTypedefAliases(parsed.root, parsed.source.text)
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

        val evaluator = ScalarEvaluator(parsed.source.text, values, scalarFunctions, targetOs, targetArch, primitiveTypedefAliases(parsed.root, parsed.source.text))
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
                            typeParameters = indexed.parameters.map { it.genericType },
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
        private val primitiveAliases: Map<String, String>
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
            visiting.remove(name)
            if (result != null) values[name] = result else failedValues += name
            return result
        }

        fun evaluate(node: CPlusSyntaxNode, locals: Map<String, Scalar> = emptyMap()): Scalar? = when (node.kind) {
            "expression" -> node.children.singleOrNull { it.named }?.let { evaluate(it, locals) } ?: unsupported(node)
            "number_literal" -> parseInteger(textOf(node))
                ?: fail(
                    "CPLUS_COMPTIME_SCALAR_LITERAL",
                    "integer literal is malformed or outside the supported signed 64-bit comptime range",
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

        private fun evaluateUnary(node: CPlusSyntaxNode, locals: Map<String, Scalar>): Scalar? {
            val operand = node.children.lastOrNull { it.named } ?: return unsupported(node)
            val value = evaluate(operand, locals) ?: return null
            val operator = text.substring(node.span.startOffset, operand.span.startOffset).trim()
            return when (operator) {
                "+" -> value.asInteger()?.let(Scalar::Integer) ?: unsupported(node)
                "-" -> value.asInteger()?.let { arithmetic(node) { Math.negateExact(it) } }
                    ?: unsupported(node)
                "!" -> value.asBoolean()?.let { Scalar.Bool(!it) } ?: unsupported(node)
                "~" -> value.asInteger()?.let { Scalar.Integer(it.inv()) } ?: unsupported(node)
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
                    a != null && b != null -> arithmetic(node) { Math.addExact(a, b) }
                    else -> unsupported(node)
                }
                "-" -> if (a != null && b != null) arithmetic(node) { Math.subtractExact(a, b) } else unsupported(node)
                "*" -> if (a != null && b != null) arithmetic(node) { Math.multiplyExact(a, b) } else unsupported(node)
                "/" -> when {
                    a == null || b == null || b == 0L -> arithmeticError(node, "division requires integer operands and a nonzero divisor")
                    a == Long.MIN_VALUE && b == -1L -> arithmeticError(node, "signed integer overflow in comptime division")
                    else -> Scalar.Integer(a / b)
                }
                "%" -> when {
                    a == null || b == null || b == 0L -> arithmeticError(node, "remainder requires integer operands and a nonzero divisor")
                    a == Long.MIN_VALUE && b == -1L -> arithmeticError(node, "signed integer overflow in comptime remainder")
                    else -> Scalar.Integer(a % b)
                }
                "==" -> Scalar.Bool(left == right)
                "!=" -> Scalar.Bool(left != right)
                "<" -> if (a != null && b != null) Scalar.Bool(a < b) else unsupported(node)
                "<=" -> if (a != null && b != null) Scalar.Bool(a <= b) else unsupported(node)
                ">" -> if (a != null && b != null) Scalar.Bool(a > b) else unsupported(node)
                ">=" -> if (a != null && b != null) Scalar.Bool(a >= b) else unsupported(node)
                "&" -> if (a != null && b != null) Scalar.Integer(a and b) else unsupported(node)
                "|" -> if (a != null && b != null) Scalar.Integer(a or b) else unsupported(node)
                "^" -> if (a != null && b != null) Scalar.Integer(a xor b) else unsupported(node)
                "<<" -> if (a != null && b != null && a >= 0L && b in 0L..62L) {
                    val factor = 1L shl b.toInt()
                    arithmetic(node) { Math.multiplyExact(a, factor) }
                } else arithmeticError(node, "signed left shift requires a nonnegative value and a shift count from 0 through 62")
                ">>" -> if (a != null && b != null && a >= 0L && b in 0L..62L) {
                    Scalar.Integer(a shr b.toInt())
                } else arithmeticError(node, "signed right shift requires a nonnegative value and a shift count from 0 through 62")
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
                if (function.typeParameters[index]) evaluateTypeArgument(argument, locals) ?: return null
                else evaluate(argument, locals) ?: return null
            }
            activeFunctions += name
            val functionLocals = function.parameterNames.zip(argumentValues).toMap()
            val result = evaluate(function.returnExpression, functionLocals)
            activeFunctions -= name
            return result
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
            val visitedAliases = mutableSetOf<String>()
            while (visitedAliases.add(type)) {
                type = primitiveAliases[type] ?: break
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
            if (signed < LONG_MIN || signed > LONG_MAX) return null
            return Scalar.Integer(signed.toLong())
        }

        private companion object {
            const val MAX_FUNCTION_DEPTH = 128
            val INTEGER_LITERAL = Regex(
                """([+-]?(?:0[xX][0-9a-fA-F]+|0[bB][01]+|0[0-7]*|[1-9][0-9]*))(?:[uU](?:[lL]{1,2})?|[lL]{1,2}[uU]?)?"""
            )
            val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
            val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
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
        val typeParameters: List<Boolean>,
        val returnExpression: CPlusSyntaxNode
    )

    private data class AbiLayout(val size: Long, val alignment: Long)

    private sealed interface Scalar {
        fun render(): kotlin.String
        fun asInteger(): Long? = (this as? Integer)?.value
        fun asBoolean(): Boolean? = when (this) {
            is Bool -> value
            is Integer -> value != 0L
            else -> null
        }

        data class Integer(val value: Long) : Scalar { override fun render() = value.toString() }
        data class Bool(val value: Boolean) : Scalar { override fun render() = if (value) "1" else "0" }
        data class String(val value: kotlin.String) : Scalar {
            override fun render() = buildString {
                append('"')
                value.forEach { character ->
                    append(when (character) {
                        '\\' -> "\\\\"
                        '"' -> "\\\""
                        '\n' -> "\\n"
                        '\r' -> "\\r"
                        '\t' -> "\\t"
                        '\u0007' -> "\\a"
                        '\b' -> "\\b"
                        '\u000c' -> "\\f"
                        '\u000b' -> "\\v"
                        else -> character.toString()
                    })
                }
                append('"')
            }
        }
        data class Type(val name: kotlin.String) : Scalar { override fun render() = name }
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
