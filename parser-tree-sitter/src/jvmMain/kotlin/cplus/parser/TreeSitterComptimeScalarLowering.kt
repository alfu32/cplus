package cplus.parser

import cplus.CPlusLoweringDiagnostic
import cplus.CPlusParseResult
import cplus.CPlusSyntaxNode
import cplus.MappedText
import cplus.MappedTextBuilder

data class TreeSitterScalarLoweringResult(
    val source: MappedText,
    val diagnostics: List<CPlusLoweringDiagnostic>
)

/** AST-only materialization for scalar comptime values and inline scalar expressions. */
class TreeSitterComptimeScalarLowering {
    fun lower(parsed: CPlusParseResult, source: MappedText): TreeSitterScalarLoweringResult {
        require(parsed.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }

        val values = mutableListOf<ValueDeclaration>()
        val scalarFunctions = mutableListOf<ScalarFunctionDeclaration>()
        val inlineExpressions = mutableListOf<CPlusSyntaxNode>()
        val scopedValues = mutableListOf<CPlusSyntaxNode>()
        val declarationDiagnostics = mutableListOf<CPlusLoweringDiagnostic>()
        val comptimeIndex = cplus.CPlusComptimeIndexer().index(cplus.CPlusAstAdapter().adapt(parsed))
        fun collect(node: CPlusSyntaxNode, parent: CPlusSyntaxNode?, dormant: Boolean, moduleScope: Boolean) {
            val isGeneratorDefinition = node.kind in GENERATOR_DEFINITIONS
            val isDormant = dormant || (node.kind in DORMANT_REGIONS && !isGeneratorDefinition)
            if (!isDormant && node.kind == "cplus_comptime_function_definition" && moduleScope) {
                val indexed = comptimeIndex.constructs.firstOrNull {
                    it.syntaxKind == node.kind && it.span.startOffset == node.span.startOffset
                }
                val resultType = node.children.firstOrNull { it.fieldName == "result_kind" }
                    ?.let { parsed.source.text.substring(it.span.startOffset, it.span.endOffset).trim() }
                if (resultType in SUPPORTED_INTEGER_TYPES) {
                    val body = node.children.firstOrNull { it.fieldName == "body" }
                    val returnStatements = body?.let(::descendants)
                        ?.filter { it.kind == "return_statement" }
                        ?.toList().orEmpty()
                    val bodyItems = body?.children.orEmpty().filter { it.named && it.kind != "comment" }
                    val returnStatement = returnStatements.singleOrNull()
                    val returnExpression = returnStatement?.children?.singleOrNull { it.named && it.kind != "comment" }
                    if (body == null || bodyItems.size != 1 || bodyItems.singleOrNull()?.span != returnStatement?.span || returnExpression == null) {
                        declarationDiagnostics += diagnostic(
                            "CPLUS_COMPTIME_SCALAR_FUNCTION_BODY",
                            "scalar comptime functions in this prototype must contain exactly one return expression",
                            node.span
                        )
                    } else if (indexed == null || indexed.parameters.any { parameter ->
                            parameter.genericType || parameter.name == null || parameter.typeText !in SUPPORTED_INTEGER_TYPES
                        }
                    ) {
                        declarationDiagnostics += diagnostic(
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
                    val name = parsed.source.text.substring(nameNode.span.startOffset, nameNode.span.endOffset).removePrefix("@")
                    val type = node.children.firstOrNull { it.fieldName == "type" }
                    val expression = node.children.firstOrNull {
                        it.named && it.fieldName == null && it !== nameNode
                    }
                    values += ValueDeclaration(node, replacement, name, type, expression)
                }
            } else if (!isDormant && node.kind == "cplus_comptime_expression") {
                inlineExpressions += node
            }
            val nestedModuleScope = moduleScope && node.kind in MODULE_SCOPE_NODES
            val childrenAreDormant = isDormant || isGeneratorDefinition
            node.children.forEach { collect(it, node, childrenAreDormant, nestedModuleScope) }
        }
        collect(parsed.root, null, false, true)
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
        if (values.isEmpty() && scalarFunctions.isEmpty() && inlineExpressions.isEmpty()) {
            return TreeSitterScalarLoweringResult(source, emptyList())
        }

        val evaluator = ScalarEvaluator(parsed.source.text, values, scalarFunctions)
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

    private class ScalarEvaluator(
        private val text: String,
        declarations: List<ValueDeclaration>,
        scalarFunctions: List<ScalarFunctionDeclaration>
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
                ?: fail("CPLUS_COMPTIME_SCALAR_LITERAL", "only integer scalar literals are currently supported", node)
            "true" -> Scalar.Bool(true)
            "false" -> Scalar.Bool(false)
            "identifier" -> {
                val name = textOf(node).removePrefix("@")
                locals[name] ?: evaluateName(name) ?: if (name in failedValues) null else
                    fail("CPLUS_COMPTIME_SCALAR_NAME", "unknown comptime scalar '$name'", node)
            }
            "parenthesized_expression" -> node.children.firstOrNull { it.named }?.let { evaluate(it, locals) } ?: unsupported(node)
            "unary_expression" -> evaluateUnary(node, locals)
            "binary_expression" -> evaluateBinary(node, locals)
            "call_expression" -> evaluateCall(node, locals)
            else -> unsupported(node)
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
            if (operator == "&&" && left.asBoolean() == false) return Scalar.Bool(false)
            if (operator == "||" && left.asBoolean() == true) return Scalar.Bool(true)
            val right = evaluate(operands[1], locals) ?: return null
            val a = left.asInteger()
            val b = right.asInteger()
            return when (operator) {
                "+" -> if (a != null && b != null) arithmetic(node) { Math.addExact(a, b) } else unsupported(node)
                "-" -> if (a != null && b != null) arithmetic(node) { Math.subtractExact(a, b) } else unsupported(node)
                "*" -> if (a != null && b != null) arithmetic(node) { Math.multiplyExact(a, b) } else unsupported(node)
                "/" -> if (a != null && b != null && b != 0L) Scalar.Integer(a / b) else arithmeticError(node, "division requires integer operands and a nonzero divisor")
                "%" -> if (a != null && b != null && b != 0L) Scalar.Integer(a % b) else arithmeticError(node, "remainder requires integer operands and a nonzero divisor")
                "==" -> Scalar.Bool(left == right)
                "!=" -> Scalar.Bool(left != right)
                "<" -> if (a != null && b != null) Scalar.Bool(a < b) else unsupported(node)
                "<=" -> if (a != null && b != null) Scalar.Bool(a <= b) else unsupported(node)
                ">" -> if (a != null && b != null) Scalar.Bool(a > b) else unsupported(node)
                ">=" -> if (a != null && b != null) Scalar.Bool(a >= b) else unsupported(node)
                "&&" -> if (left.asBoolean() != null && right.asBoolean() != null)
                    Scalar.Bool(left.asBoolean()!! && right.asBoolean()!!) else unsupported(node)
                "||" -> if (left.asBoolean() != null && right.asBoolean() != null)
                    Scalar.Bool(left.asBoolean()!! || right.asBoolean()!!) else unsupported(node)
                "&" -> if (a != null && b != null) Scalar.Integer(a and b) else unsupported(node)
                "|" -> if (a != null && b != null) Scalar.Integer(a or b) else unsupported(node)
                "^" -> if (a != null && b != null) Scalar.Integer(a xor b) else unsupported(node)
                "<<" -> if (a != null && b != null && b in 0L..63L) Scalar.Integer(a shl b.toInt()) else unsupported(node)
                ">>" -> if (a != null && b != null && b in 0L..63L) Scalar.Integer(a shr b.toInt()) else unsupported(node)
                else -> unsupported(node)
            }
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
            val argumentValues = arguments.map { argument -> evaluate(argument, locals) ?: return null }
            activeFunctions += name
            val functionLocals = function.parameterNames.zip(argumentValues).toMap()
            val result = evaluate(function.returnExpression, functionLocals)
            activeFunctions -= name
            return result
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
            val value = raw.replace(Regex("(?i)[ul]+$"), "")
            val parsed = when {
                value.startsWith("0x", true) -> value.drop(2).toLongOrNull(16)
                value.startsWith("0b", true) -> value.drop(2).toLongOrNull(2)
                value.length > 1 && value.startsWith('0') -> value.drop(1).toLongOrNull(8)
                else -> value.toLongOrNull()
            }
            return parsed?.let(Scalar::Integer)
        }

        private companion object {
            const val MAX_FUNCTION_DEPTH = 128
        }
    }

    private data class ValueDeclaration(
        val node: CPlusSyntaxNode,
        val replacement: CPlusSyntaxNode,
        val name: String,
        val type: CPlusSyntaxNode?,
        val expression: CPlusSyntaxNode?
    )

    private data class ScalarFunctionDeclaration(
        val node: CPlusSyntaxNode,
        val replacement: CPlusSyntaxNode,
        val name: String,
        val parameterNames: List<String>,
        val returnExpression: CPlusSyntaxNode
    )

    private sealed interface Scalar {
        fun render(): String
        fun asInteger(): Long? = (this as? Integer)?.value
        fun asBoolean(): Boolean? = when (this) {
            is Bool -> value
            is Integer -> value != 0L
        }

        data class Integer(val value: Long) : Scalar { override fun render() = value.toString() }
        data class Bool(val value: Boolean) : Scalar { override fun render() = if (value) "1" else "0" }
    }

    private data class Replacement(val start: Int, val end: Int, val content: MappedText)

    private fun descendants(node: CPlusSyntaxNode): Sequence<CPlusSyntaxNode> =
        sequenceOf(node) + node.children.asSequence().flatMap(::descendants)

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
        fun diagnostic(code: String, message: String, span: cplus.SourceSpan) =
            CPlusLoweringDiagnostic(code, message, span)
    }
}
