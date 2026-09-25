package cplus.parser

import cplus.AllocationAnalysisResult
import cplus.AllocationIntent
import cplus.AllocationOwnership
import cplus.AllocationSymbol
import cplus.AllocationSymbolKind
import cplus.CPlusAst
import cplus.CPlusAstNode
import cplus.CPlusDiagnostic

/**
 * AST-backed allocation-intent analysis for the prototype frontend.
 *
 * Reports allocator provenance on variable declarations and follows straightforward
 * initializer aliases within lexical scopes. Branch-sensitive and interprocedural flow
 * remains with the legacy analyzer until equivalent AST data-flow exists.
 */
class TreeSitterAllocationIntentAnalyzer {
    fun analyze(ast: CPlusAst): AllocationAnalysisResult {
        val symbols = mutableListOf<AllocationSymbol>()
        val diagnostics = mutableListOf<CPlusDiagnostic>()
        val source = ast.source.text
        val functionContracts = collectFunctionContracts(ast, source)
        functionContracts.values.forEach { contract ->
            if (contract.returnIntent != AllocationIntent.NONE || contract.returnOwnership != AllocationOwnership.NONE) {
                symbols += AllocationSymbol(
                    contract.name,
                    AllocationSymbolKind.FUNCTION_RETURN,
                    contract.returnIntent,
                    contract.returnOwnership,
                    contract.returnIntent,
                    ast.source.sourceFile.span(contract.nameSpan.startOffset, contract.nameSpan.endOffset)
                )
            }
        }

        fun visitDeclaration(declaration: CPlusAstNode, scope: MutableMap<String, VariableState>) {
            val annotations = declaration.children
                .filter { it.syntaxKind == "cplus_result_annotation" }
                .map { text(source, it) }
            val intent = annotations.asSequence().map(::allocationIntent).firstOrNull { it != AllocationIntent.NONE }
                ?: AllocationIntent.NONE
            val ownership = annotations.asSequence().map(::allocationOwnership).firstOrNull { it != AllocationOwnership.NONE }
                ?: AllocationOwnership.NONE

            declaration.children.filter { it.fieldName == "declarator" }.forEach { declarator ->
                if (declarator.descendantsAndSelf().any { it.syntaxKind == "function_declarator" }) return@forEach
                val initDeclarator = declarator.takeIf { it.syntaxKind == "init_declarator" }
                val nameDeclarator = initDeclarator?.children?.firstOrNull { it.fieldName == "declarator" } ?: declarator
                val nameNode = nameDeclarator.descendantsAndSelf()
                    .firstOrNull { it.syntaxKind == "identifier" }
                    ?: return@forEach
                val name = text(source, nameNode)
                val initializer = initDeclarator?.children?.firstOrNull { it.fieldName == "value" }
                val provenance = initializer?.let { infer(it, scope, source) }

                symbols += AllocationSymbol(
                    name = name,
                    kind = AllocationSymbolKind.VARIABLE,
                    intent = intent,
                    ownership = ownership,
                    knownProvenance = provenance?.intent ?: AllocationIntent.NONE,
                    sourceSpan = ast.source.sourceFile.span(nameNode.span.startOffset, nameNode.span.endOffset)
                )

                if (intent != AllocationIntent.NONE && provenance != null && intent != provenance.intent) {
                    diagnostics += CPlusDiagnostic(
                        "allocation intent mismatch: '$name' is declared ${intent.label()} but receives memory from ${provenance.source}",
                        ast.source.sourceFile.span(nameNode.span.startOffset, nameNode.span.endOffset)
                    )
                }
                scope[name] = VariableState(intent, ownership, provenance)
            }
        }

        fun applySimpleAssignment(node: CPlusAstNode, scope: MutableMap<String, VariableState>) {
            val targetNode = node.children.firstOrNull { it.fieldName == "left" }
                ?.takeIf { it.syntaxKind == "identifier" }
            val valueNode = node.children.firstOrNull { it.fieldName == "right" }
            if (targetNode == null || valueNode == null) return
            val name = text(source, targetNode)
            val current = scope[name] ?: return
            val provenance = infer(valueNode, scope, source)
            if (current.intent != AllocationIntent.NONE && provenance != null && current.intent != provenance.intent) {
                diagnostics += CPlusDiagnostic(
                    "allocation intent mismatch: '$name' is declared ${current.intent.label()} but receives memory from ${provenance.source}",
                    ast.source.sourceFile.span(targetNode.span.startOffset, targetNode.span.endOffset)
                )
            }
            scope[name] = current.copy(provenance = provenance)
        }

        fun checkCall(node: CPlusAstNode, scope: Map<String, VariableState>) {
            val functionNode = node.children.firstOrNull { it.fieldName == "function" } ?: return
            val signature = functionContracts[text(source, functionNode)] ?: return
            val argumentList = node.children.firstOrNull { it.fieldName == "arguments" } ?: return
            val arguments = argumentList.children.filter { it.named }
            signature.parameters.zip(arguments).forEach { (parameter, argument) ->
                if (parameter.intent == AllocationIntent.NONE) return@forEach
                val actual = infer(argument, scope, source) ?: return@forEach
                if (actual.intent == parameter.intent) return@forEach
                diagnostics += CPlusDiagnostic(
                    "allocation intent mismatch: argument for '${signature.name}.${parameter.name}' is ${actual.intent.label()} but the parameter expects ${parameter.intent.label()}",
                    ast.source.sourceFile.span(argument.span.startOffset, argument.span.endOffset)
                )
            }
        }

        fun visit(
            node: CPlusAstNode,
            scope: MutableMap<String, VariableState>,
            currentFunction: FunctionContract? = null
        ) {
            when (node.syntaxKind) {
                "declaration" -> {
                    visitDeclaration(node, scope)
                    node.children.filter { it.syntaxKind == "init_declarator" }
                        .flatMap { it.children.filter { child -> child.fieldName == "value" } }
                        .forEach { visit(it, scope, currentFunction) }
                }
                "call_expression" -> {
                    checkCall(node, scope)
                    node.children.forEach { visit(it, scope, currentFunction) }
                }
                "expression_statement" -> {
                    val expressions = node.children.filter { it.named }
                    if (expressions.size == 1 && expressions.single().syntaxKind == "assignment_expression") {
                        applySimpleAssignment(expressions.single(), scope)
                    }
                    node.children.forEach { visit(it, scope, currentFunction) }
                }
                "return_statement" -> {
                    val returnValue = node.children.firstOrNull { it.named }
                    val actual = returnValue?.let { infer(it, scope, source) }
                    if (currentFunction?.returnIntent != null && currentFunction.returnIntent != AllocationIntent.NONE &&
                        actual != null && actual.intent != currentFunction.returnIntent
                    ) {
                        diagnostics += CPlusDiagnostic(
                            "allocation intent mismatch: function '${currentFunction.name}' is annotated ${currentFunction.returnIntent.label()} but returns ${actual.source}",
                            ast.source.sourceFile.span(returnValue!!.span.startOffset, returnValue.span.endOffset)
                        )
                    }
                    node.children.forEach { visit(it, scope, currentFunction) }
                }
                "compound_statement" -> {
                    val blockScope = scope.toMutableMap()
                    node.children.forEach { visit(it, blockScope, currentFunction) }
                }
                "function_definition" -> {
                    val functionScope = mutableMapOf<String, VariableState>()
                    val name = node.children.firstOrNull { it.fieldName == "declarator" }
                        ?.descendantsAndSelf()
                        ?.firstOrNull { it.syntaxKind == "identifier" }
                        ?.let { text(source, it) }
                    val function = name?.let(functionContracts::get)
                    node.children.forEach { child ->
                        if (child.syntaxKind == "compound_statement") visit(child, functionScope, function)
                    }
                }
                "if_statement", "for_statement", "while_statement", "do_statement", "switch_statement" ->
                    node.children.forEach { visit(it, scope.toMutableMap(), currentFunction) }
                else -> node.children.forEach { visit(it, scope, currentFunction) }
            }
        }

        visit(ast.root, mutableMapOf())

        return AllocationAnalysisResult(
            symbols.distinctBy { listOf(it.name, it.kind, it.sourceSpan.file, it.sourceSpan.startOffset) },
            diagnostics.distinctBy { it.sourceSpan.file to it.sourceSpan.startOffset }
        )
    }

    private fun allocationIntent(token: String): AllocationIntent = when (token) {
        "scratch" -> AllocationIntent.SCRATCH
        "hot" -> AllocationIntent.HOT
        "warm" -> AllocationIntent.WARM
        "cold" -> AllocationIntent.COLD
        else -> AllocationIntent.NONE
    }

    private fun allocationOwnership(token: String): AllocationOwnership = when (token) {
        "borrowed" -> AllocationOwnership.BORROWED
        "owned" -> AllocationOwnership.OWNED
        else -> AllocationOwnership.NONE
    }

    private fun allocatorIntent(name: String): AllocationIntent? {
        val prefix = when {
            name.startsWith("alloc_") -> name.removePrefix("alloc_")
            name.startsWith("calloc_") -> name.removePrefix("calloc_")
            name.startsWith("realloc_") -> name.removePrefix("realloc_")
            else -> return null
        }.removeSuffix("_aligned")
        return allocationIntent(prefix).takeIf { it != AllocationIntent.NONE }
    }

    private fun collectFunctionContracts(ast: CPlusAst, source: String): Map<String, FunctionContract> =
        ast.root.descendantsAndSelf()
            .filter { it.syntaxKind in setOf("declaration", "function_definition") }
            .mapNotNull { declaration ->
                val declarationNode = declaration.children.firstOrNull { it.fieldName == "declarator" } ?: return@mapNotNull null
                val functionDeclarator = declarationNode.descendantsAndSelf()
                    .filter { it.syntaxKind == "function_declarator" }
                    .firstOrNull { candidate ->
                        candidate.children.firstOrNull { it.fieldName == "declarator" }
                            ?.descendantsAndSelf()
                            ?.any { it.syntaxKind == "identifier" } == true
                    } ?: return@mapNotNull null
                val nameNode = functionDeclarator.children.firstOrNull { it.fieldName == "declarator" }
                    ?.descendantsAndSelf()
                    ?.firstOrNull { it.syntaxKind == "identifier" }
                    ?: return@mapNotNull null
                val parametersNode = functionDeclarator.children.firstOrNull { it.fieldName == "parameters" }
                    ?: return@mapNotNull null
                val parameters = parametersNode.children
                    .filter { it.syntaxKind in setOf("parameter_declaration", "cplus_parameter_declaration") }
                    .map { parameter ->
                        val parameterDeclarator = parameter.children.firstOrNull { it.fieldName == "declarator" }
                        val parameterName = parameterDeclarator?.descendantsAndSelf()
                            ?.firstOrNull { it.syntaxKind == "identifier" }
                            ?.let { source.substring(it.span.startOffset, it.span.endOffset) }.orEmpty()
                        val intent = parameter.descendantsAndSelf()
                            .filter { it.syntaxKind == "cplus_parameter_annotation" }
                            .mapNotNull { allocationIntent(source.substring(it.span.startOffset, it.span.endOffset)) }
                            .firstOrNull { it != AllocationIntent.NONE } ?: AllocationIntent.NONE
                        ParameterContract(parameterName, intent)
                    }
                val resultAnnotations = declaration.children
                    .filter { it.syntaxKind == "cplus_result_annotation" }
                    .map { text(source, it) }
                val returnIntent = resultAnnotations.map(::allocationIntent).firstOrNull { it != AllocationIntent.NONE }
                    ?: AllocationIntent.NONE
                val returnOwnership = resultAnnotations.map(::allocationOwnership).firstOrNull { it != AllocationOwnership.NONE }
                    ?: AllocationOwnership.NONE
                if (parameters.none { it.intent != AllocationIntent.NONE } && returnIntent == AllocationIntent.NONE &&
                    returnOwnership == AllocationOwnership.NONE
                ) return@mapNotNull null
                val name = source.substring(nameNode.span.startOffset, nameNode.span.endOffset)
                FunctionContract(name, nameNode.span, parameters, returnIntent, returnOwnership)
            }
            .groupBy { it.name }
            .mapValues { (name, declarations) ->
                declarations.reduce { selected, candidate ->
                    FunctionContract(
                        name,
                        selected.nameSpan,
                        selected.parameters.zip(candidate.parameters).map { (left, right) ->
                            ParameterContract(left.name.ifEmpty { right.name }, left.intent.takeUnless { it == AllocationIntent.NONE } ?: right.intent)
                        },
                        selected.returnIntent.takeUnless { it == AllocationIntent.NONE } ?: candidate.returnIntent,
                        selected.returnOwnership.takeUnless { it == AllocationOwnership.NONE } ?: candidate.returnOwnership
                    )
                }
            }

    private fun infer(
        expression: CPlusAstNode,
        scope: Map<String, VariableState>,
        source: String
    ): Provenance? = when (expression.syntaxKind) {
        "call_expression" -> expression.children.firstOrNull { it.fieldName == "function" }
            ?.let { text(source, it) }
            ?.let { functionName -> allocatorIntent(functionName)?.let { Provenance(it, "$functionName()") } }
        "identifier" -> scope[text(source, expression)]?.let { state ->
            state.provenance ?: state.intent.takeIf { it != AllocationIntent.NONE }
                ?.let { Provenance(it, "${it.label()} pointer '${text(source, expression)}'") }
        }
        "parenthesized_expression", "cast_expression" -> expression.children
            .lastOrNull { it.named }
            ?.let { infer(it, scope, source) }
        else -> null
    }

    private fun text(source: String, node: CPlusAstNode): String =
        source.substring(node.span.startOffset, node.span.endOffset)

    private fun AllocationIntent.label(): String = name.lowercase()

    private data class VariableState(
        val intent: AllocationIntent,
        val ownership: AllocationOwnership,
        val provenance: Provenance?
    )

    private data class Provenance(val intent: AllocationIntent, val source: String)

    private data class FunctionContract(
        val name: String,
        val nameSpan: cplus.SourceSpan,
        val parameters: List<ParameterContract>,
        val returnIntent: AllocationIntent,
        val returnOwnership: AllocationOwnership
    )

    private data class ParameterContract(val name: String, val intent: AllocationIntent)

    private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
        sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }
}
