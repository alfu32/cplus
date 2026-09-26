package cplus.parser

import cplus.AllocationAnalysisResult
import cplus.AllocationIntent
import cplus.AllocationOwnership
import cplus.AllocationSymbol
import cplus.AllocationSymbolKind
import cplus.CPlusAst
import cplus.CPlusAstNode
import cplus.CPlusDiagnostic
import cplus.CPlusSemanticAnalyzer

/**
 * AST-backed allocation-intent analysis for the prototype frontend.
 *
 * Reports allocator provenance on variable declarations and follows straightforward
 * initializer aliases within lexical scopes, and annotated function/method return values
 * at call sites. Branch-sensitive and general expression flow remains with the legacy
 * analyzer until equivalent AST data-flow exists.
 */
class TreeSitterAllocationIntentAnalyzer {
    fun analyze(ast: CPlusAst): AllocationAnalysisResult {
        val symbols = mutableListOf<AllocationSymbol>()
        val diagnostics = mutableListOf<CPlusDiagnostic>()
        val source = ast.source.text
        val functionContracts = collectFunctionContracts(ast, source)
        val methodContracts = collectMethodContracts(ast, source)
        val resolvedMethodCalls = CPlusSemanticAnalyzer().analyze(ast).resolvedCalls.associateBy { it.span.startOffset }
        val methodContractsByDeclaration = methodContracts
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
            contract.parameters.forEach parameterLoop@{ parameter ->
                val parameterSpan = parameter.nameSpan ?: return@parameterLoop
                if (parameter.intent != AllocationIntent.NONE || parameter.ownership != AllocationOwnership.NONE) {
                    symbols += AllocationSymbol(
                        parameter.name,
                        AllocationSymbolKind.PARAMETER,
                        parameter.intent,
                        parameter.ownership,
                        parameter.intent.takeUnless { parameter.isOutputPointer } ?: AllocationIntent.NONE,
                        ast.source.sourceFile.span(parameterSpan.startOffset, parameterSpan.endOffset)
                    )
                }
            }
        }
        methodContracts.values.forEach { contract ->
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
                val provenance = initializer?.let {
                    infer(it, scope, source, functionContracts, methodContractsByDeclaration, resolvedMethodCalls)
                }

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

        fun applySimpleAssignment(
            node: CPlusAstNode,
            scope: MutableMap<String, VariableState>,
            currentFunction: FunctionContract?
        ) {
            val leftNode = node.children.firstOrNull { it.fieldName == "left" }
            val valueNode = node.children.firstOrNull { it.fieldName == "right" }
            if (leftNode == null || valueNode == null) return
            val outputParameter = if (leftNode.syntaxKind == "pointer_expression" &&
                leftNode.children.firstOrNull { it.fieldName == "operator" }?.let { text(source, it) } == "*"
            ) {
                leftNode.children.firstOrNull { it.fieldName == "argument" }
                    ?.takeIf { it.syntaxKind == "identifier" }
                    ?.let { identifier -> currentFunction?.parameters?.firstOrNull { it.name == text(source, identifier) && it.isOutputPointer } }
                    ?.let { parameter -> parameter to leftNode }
            } else null
            if (outputParameter != null) {
                val (parameter, target) = outputParameter
                val provenance = infer(valueNode, scope, source, functionContracts, methodContractsByDeclaration, resolvedMethodCalls)
                if (parameter.intent != AllocationIntent.NONE && provenance != null && parameter.intent != provenance.intent) {
                    diagnostics += CPlusDiagnostic(
                        "allocation intent mismatch: '${parameter.name}' is declared ${parameter.intent.label()} but receives memory from ${provenance.source}",
                        ast.source.sourceFile.span(target.span.startOffset, target.span.endOffset)
                    )
                }
                return
            }
            val targetNode = leftNode.takeIf { it.syntaxKind == "identifier" } ?: return
            val name = text(source, targetNode)
            val current = scope[name] ?: return
            val provenance = infer(valueNode, scope, source, functionContracts, methodContractsByDeclaration, resolvedMethodCalls)
            if (current.intent != AllocationIntent.NONE && provenance != null && current.intent != provenance.intent) {
                diagnostics += CPlusDiagnostic(
                    "allocation intent mismatch: '$name' is declared ${current.intent.label()} but receives memory from ${provenance.source}",
                    ast.source.sourceFile.span(targetNode.span.startOffset, targetNode.span.endOffset)
                )
            }
            scope[name] = current.copy(provenance = provenance)
        }

        fun outputTargetName(argument: CPlusAstNode): String? {
            var expression = argument
            while (expression.syntaxKind == "parenthesized_expression") {
                expression = expression.children.firstOrNull { it.named } ?: return null
            }
            if (expression.syntaxKind !in setOf("pointer_expression", "unary_expression")) return null
            val operator = expression.children.firstOrNull { it.fieldName == "operator" }
                ?.let { text(source, it) }
                ?: expression.children.firstOrNull { !it.named }?.let { text(source, it) }
            if (operator != "&") return null
            return expression.children.firstOrNull { it.fieldName == "argument" }
                ?.takeIf { it.syntaxKind == "identifier" }
                ?.let { text(source, it) }
        }

        fun applyOutputCallEffect(
            argument: CPlusAstNode,
            parameter: ParameterContract,
            scope: MutableMap<String, VariableState>
        ) {
            if (!parameter.isOutputPointer) return
            val name = outputTargetName(argument) ?: return
            val current = scope[name] ?: return
            val before = current.provenance?.intent
                ?: current.intent.takeIf { it != AllocationIntent.NONE }
            val output = parameter.intent.takeIf { it != AllocationIntent.NONE }
            val joined = before?.takeIf { it == output }
            scope[name] = current.copy(
                provenance = joined?.let {
                    current.provenance ?: Provenance(it, "output from '${parameter.name}'")
                }
            )
        }

        fun checkCall(node: CPlusAstNode, scope: MutableMap<String, VariableState>) {
            val argumentList = node.children.firstOrNull { it.fieldName == "arguments" } ?: return
            val arguments = argumentList.children.filter { it.named }
            val methodCall = resolvedMethodCalls[node.span.startOffset]
            if (methodCall != null) {
                val method = methodCall.declaration
                val contract = methodContractsByDeclaration[method.span.startOffset]
                val explicitReceiver = methodCall.explicitReceiver && !methodCall.staticCall
                val hasReceiverParameter = !methodCall.staticCall && method.parameters.firstOrNull()?.receiver == true
                val parameters = if (hasReceiverParameter) {
                    method.parameters.drop(1)
                } else method.parameters
                val contracts = if (hasReceiverParameter && contract?.parameters?.firstOrNull()?.name == "self") {
                    contract.parameters.drop(1)
                } else contract?.parameters.orEmpty()
                val checkedArguments = if (explicitReceiver) arguments.drop(1) else arguments
                parameters.zip(checkedArguments).forEach { (parameter, argument) ->
                    val expected = parameter.annotations.asSequence().map(::allocationIntent)
                        .firstOrNull { it != AllocationIntent.NONE } ?: AllocationIntent.NONE
                    if (expected != AllocationIntent.NONE) {
                        val actual = infer(argument, scope, source, functionContracts, methodContractsByDeclaration, resolvedMethodCalls)
                        if (actual != null && actual.intent != expected) {
                            diagnostics += CPlusDiagnostic(
                                "allocation intent mismatch: argument for '${method.ownerType}.${method.name}.${parameter.name}' is ${actual.intent.label()} but the parameter expects ${expected.label()}",
                                ast.source.sourceFile.span(argument.span.startOffset, argument.span.endOffset)
                            )
                        }
                    }
                }
                contracts.zip(checkedArguments).forEach { (parameter, argument) ->
                    applyOutputCallEffect(argument, parameter, scope)
                }
                return
            }
            val functionNode = node.children.firstOrNull { it.fieldName == "function" } ?: return
            val signature = functionContracts[text(source, functionNode)] ?: return
            signature.parameters.zip(arguments).forEach { (parameter, argument) ->
                if (!parameter.isOutputPointer && parameter.intent != AllocationIntent.NONE) {
                    val actual = infer(argument, scope, source, functionContracts, methodContractsByDeclaration, resolvedMethodCalls)
                    if (actual != null && actual.intent != parameter.intent) {
                        diagnostics += CPlusDiagnostic(
                            "allocation intent mismatch: argument for '${signature.name}.${parameter.name}' is ${actual.intent.label()} but the parameter expects ${parameter.intent.label()}",
                            ast.source.sourceFile.span(argument.span.startOffset, argument.span.endOffset)
                        )
                    }
                }
                applyOutputCallEffect(argument, parameter, scope)
            }
        }

        fun mergePossibleScopes(
            target: MutableMap<String, VariableState>,
            incoming: Map<String, VariableState>,
            branches: List<Map<String, VariableState>>
        ) {
            incoming.forEach { (name, initial) ->
                val states = branches.map { it[name] ?: initial }
                val domains = states.map { it.provenance?.intent }.distinct()
                target[name] = if (domains.size == 1) {
                    initial.copy(provenance = states.first().provenance)
                } else {
                    // A known incoming value is not valid after a branch that can replace it
                    // with a different or unknown allocation domain.
                    initial.copy(provenance = null)
                }
            }
        }

        fun modifiedVariablesIn(node: CPlusAstNode): Set<String> = node.descendantsAndSelf()
            .flatMap { expression ->
                when (expression.syntaxKind) {
                    "assignment_expression" -> listOfNotNull(
                        expression.children.firstOrNull { it.fieldName == "left" }
                            ?.takeIf { it.syntaxKind == "identifier" }
                            ?.let { text(source, it) }
                    ).asSequence()
                    "update_expression" -> listOfNotNull(
                        expression.descendantsAndSelf()
                            .firstOrNull { it.syntaxKind == "identifier" }
                            ?.let { text(source, it) }
                    ).asSequence()
                    "call_expression" -> {
                        val arguments = expression.children.firstOrNull { it.fieldName == "arguments" }
                            ?.children.orEmpty().filter { it.named }
                        val methodCall = resolvedMethodCalls[expression.span.startOffset]
                        val contractAndArguments = if (methodCall != null) {
                            val method = methodCall.declaration
                            val contract = methodContractsByDeclaration[method.span.startOffset]
                            val hasReceiverParameter = !methodCall.staticCall && method.parameters.firstOrNull()?.receiver == true
                            val contracts = if (hasReceiverParameter && contract?.parameters?.firstOrNull()?.name == "self") {
                                contract.parameters.drop(1)
                            } else contract?.parameters.orEmpty()
                            val callArguments = if (methodCall.explicitReceiver && !methodCall.staticCall) {
                                arguments.drop(1)
                            } else arguments
                            contracts to callArguments
                        } else {
                            val functionNode = expression.children.firstOrNull { it.fieldName == "function" }
                            val contract = functionNode?.let { functionContracts[text(source, it)] }
                            contract?.parameters.orEmpty() to arguments
                        }
                        contractAndArguments.first.zip(contractAndArguments.second).asSequence()
                            .filter { (parameter, _) -> parameter.isOutputPointer }
                            .mapNotNull { (parameter, argument) ->
                                // Reuse the regular call-effect logic so aliases and unknown
                                // prior values are treated identically inside and outside branches.
                                outputTargetName(argument)
                            }
                    }
                    else -> emptySequence()
                }
            }.toSet()

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
                    // C does not specify argument evaluation order. Analyze nested expressions
                    // independently, then retain only allocation domains that agree across
                    // every possible single-argument effect. This intentionally loses some
                    // precision, but never carries a stale pre-call value forward.
                    val beforeCall = scope.toMap()
                    val functionExpression = node.children.firstOrNull { it.fieldName == "function" }
                    val arguments = node.children.firstOrNull { it.fieldName == "arguments" }
                        ?.children.orEmpty().filter { it.named }
                    val evaluatedExpressions = listOfNotNull(functionExpression) + arguments
                    val possibleEffects = evaluatedExpressions.map { expression ->
                        scope.toMutableMap().also { visit(expression, it, currentFunction) }
                    }
                    if (possibleEffects.isNotEmpty()) {
                        mergePossibleScopes(scope, beforeCall, possibleEffects)
                    }
                }
                "assignment_expression" -> {
                    node.children.firstOrNull { it.fieldName == "right" }
                        ?.let { visit(it, scope, currentFunction) }
                    applySimpleAssignment(node, scope, currentFunction)
                }
                "comma_expression" -> {
                    // The comma operator sequences its operands left-to-right.
                    node.children.filter { it.named }.forEach { visit(it, scope, currentFunction) }
                }
                "binary_expression" -> {
                    val left = node.children.firstOrNull { it.fieldName == "left" }
                    val right = node.children.firstOrNull { it.fieldName == "right" }
                    val operator = node.children.firstOrNull { it.fieldName == "operator" }
                        ?.let { text(source, it) }
                    if (left == null || right == null) {
                        node.children.forEach { visit(it, scope.toMutableMap(), currentFunction) }
                    } else if (operator == "&&" || operator == "||") {
                        // The left side always runs; the right side may be skipped.
                        visit(left, scope, currentFunction)
                        val afterLeft = scope.toMap()
                        val rightScope = scope.toMutableMap()
                        visit(right, rightScope, currentFunction)
                        mergePossibleScopes(scope, afterLeft, listOf(afterLeft, rightScope))
                    } else {
                        // Other binary operands may be evaluated in either order. Preserve only
                        // provenance that agrees with both independently analyzed possibilities.
                        val incoming = scope.toMap()
                        val paths = listOf(left, right).map { operand ->
                            scope.toMutableMap().also { visit(operand, it, currentFunction) }
                        }
                        mergePossibleScopes(scope, incoming, listOf(incoming) + paths)
                    }
                }
                "conditional_expression" -> {
                    node.children.firstOrNull { it.fieldName == "condition" }
                        ?.let { visit(it, scope, currentFunction) }
                    val incoming = scope.toMap()
                    val consequence = node.children.firstOrNull { it.fieldName == "consequence" }
                    val alternative = node.children.firstOrNull { it.fieldName == "alternative" }
                    val thenScope = scope.toMutableMap()
                    consequence?.let { visit(it, thenScope, currentFunction) }
                    val elseScope = scope.toMutableMap()
                    alternative?.let { visit(it, elseScope, currentFunction) }
                    mergePossibleScopes(scope, incoming, listOf(thenScope, elseScope))
                }
                "expression_statement" -> {
                    node.children.forEach { visit(it, scope, currentFunction) }
                }
                "return_statement" -> {
                    val returnValue = node.children.firstOrNull { it.named }
                    val actual = returnValue?.let {
                        infer(it, scope, source, functionContracts, methodContractsByDeclaration, resolvedMethodCalls)
                    }
                    if (currentFunction?.returnIntent != null && currentFunction.returnIntent != AllocationIntent.NONE &&
                        actual != null && actual.intent != currentFunction.returnIntent
                    ) {
                        diagnostics += CPlusDiagnostic(
                            "allocation intent mismatch: ${currentFunction.kind} '${currentFunction.name}' is annotated ${currentFunction.returnIntent.label()} but returns ${actual.source}",
                            ast.source.sourceFile.span(returnValue!!.span.startOffset, returnValue.span.endOffset)
                        )
                    }
                    node.children.forEach { visit(it, scope, currentFunction) }
                }
                "compound_statement" -> {
                    val blockScope = scope.toMutableMap()
                    val shadowedNames = node.children
                        .filter { it.syntaxKind == "declaration" }
                        .flatMap { declaredNames(it, source) }
                        .toSet()
                    node.children.forEach { visit(it, blockScope, currentFunction) }
                    scope.keys.filterNot { it in shadowedNames }.forEach { name ->
                        blockScope[name]?.let { scope[name] = it }
                    }
                }
                "function_definition" -> {
                    val functionScope = mutableMapOf<String, VariableState>()
                    val name = node.children.firstOrNull { it.fieldName == "declarator" }
                        ?.descendantsAndSelf()
                        ?.firstOrNull { it.syntaxKind == "identifier" }
                        ?.let { text(source, it) }
                    val function = name?.let(functionContracts::get)
                    function?.parameters.orEmpty().forEach { parameter ->
                        if (parameter.name.isNotEmpty()) {
                            val initialProvenance = parameter.intent.takeIf {
                                it != AllocationIntent.NONE && !parameter.isOutputPointer
                            }?.let { Provenance(it, "${it.label()} parameter '${parameter.name}'") }
                            functionScope[parameter.name] = VariableState(
                                parameter.intent,
                                parameter.ownership,
                                initialProvenance
                            )
                        }
                    }
                    node.children.forEach { child ->
                        if (child.syntaxKind == "compound_statement") visit(child, functionScope, function)
                    }
                }
                "cplus_method_definition" -> {
                    val function = methodContracts[node.span.startOffset]
                    val methodScope = mutableMapOf<String, VariableState>()
                    function?.parameters.orEmpty().forEach { parameter ->
                        if (parameter.name.isNotEmpty()) {
                            val initialProvenance = parameter.intent.takeIf {
                                it != AllocationIntent.NONE && !parameter.isOutputPointer
                            }?.let { Provenance(it, "${it.label()} parameter '${parameter.name}'") }
                            methodScope[parameter.name] = VariableState(
                                parameter.intent,
                                parameter.ownership,
                                initialProvenance
                            )
                        }
                    }
                    node.children.forEach { child ->
                        if (child.syntaxKind == "compound_statement") visit(child, methodScope, function)
                    }
                }
                "if_statement" -> {
                    val condition = node.children.firstOrNull { it.fieldName == "condition" }
                    condition?.let { visit(it, scope, currentFunction) }
                    val incoming = scope.toMap()
                    val consequence = node.children.firstOrNull { it.fieldName == "consequence" }
                    val alternative = node.children.firstOrNull { it.fieldName == "alternative" }
                    val thenScope = scope.toMutableMap()
                    consequence?.let { visit(it, thenScope, currentFunction) }
                    val elseScope = scope.toMutableMap()
                    alternative?.let { visit(it, elseScope, currentFunction) }
                    mergePossibleScopes(scope, incoming, listOf(thenScope, elseScope))
                }
                "for_statement", "while_statement", "do_statement" -> {
                    val outerNames = scope.keys.toSet()
                    val loopScope = scope.toMutableMap()
                    // A single structural walk catches diagnostics and declarations without
                    // pretending that one traversal computes a loop fixed point. New for-init
                    // variables stay local to this loop scope.
                    node.children.forEach { visit(it, loopScope, currentFunction) }
                    modifiedVariablesIn(node).intersect(outerNames).forEach { name ->
                        scope[name]?.let { state -> scope[name] = state.copy(provenance = null) }
                    }
                }
                "switch_statement" -> {
                    val outerNames = scope.keys.toSet()
                    val switchScope = scope.mapValues { (_, state) -> state.copy(provenance = null) }.toMutableMap()
                    val body = node.children.firstOrNull { it.fieldName == "body" }
                        ?: node.children.firstOrNull { it.syntaxKind == "compound_statement" }
                    node.children.filterNot { it === body }.forEach {
                        visit(it, switchScope.toMutableMap(), currentFunction)
                    }
                    val caseBranches = body?.children.orEmpty().filter { it.syntaxKind == "case_statement" }
                    if (caseBranches.isNotEmpty()) {
                        // A case may be entered directly or reached by fallthrough. Start each
                        // branch without outer provenance rather than sequencing sibling cases.
                        caseBranches.forEach { visit(it, switchScope.toMutableMap(), currentFunction) }
                    } else {
                        body?.let { visit(it, switchScope.toMutableMap(), currentFunction) }
                    }
                    // Cases can be skipped or fall through, so a single traversal cannot
                    // establish the post-switch allocation domain for a written outer value.
                    modifiedVariablesIn(node).intersect(outerNames).forEach { name ->
                        scope[name]?.let { state -> scope[name] = state.copy(provenance = null) }
                    }
                }
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
                        val parameterNameNode = parameterDeclarator?.descendantsAndSelf()
                            ?.firstOrNull { it.syntaxKind == "identifier" }
                        val parameterName = parameterNameNode?.let { source.substring(it.span.startOffset, it.span.endOffset) }.orEmpty()
                        val intent = parameter.descendantsAndSelf()
                            .filter { it.syntaxKind == "cplus_parameter_annotation" }
                            .mapNotNull { allocationIntent(source.substring(it.span.startOffset, it.span.endOffset)) }
                            .firstOrNull { it != AllocationIntent.NONE } ?: AllocationIntent.NONE
                        val ownership = parameter.descendantsAndSelf()
                            .filter { it.syntaxKind == "cplus_parameter_annotation" }
                            .map { source.substring(it.span.startOffset, it.span.endOffset) }
                            .map(::allocationOwnership)
                            .firstOrNull { it != AllocationOwnership.NONE } ?: AllocationOwnership.NONE
                        val pointerDepth = parameterDeclarator?.let { declarator ->
                            source.substring(declarator.span.startOffset, declarator.span.endOffset).count { it == '*' }
                        } ?: 0
                        ParameterContract(parameterName, parameterNameNode?.span, intent, ownership, pointerDepth)
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
                            ParameterContract(
                                left.name.ifEmpty { right.name },
                                left.nameSpan ?: right.nameSpan,
                                left.intent.takeUnless { it == AllocationIntent.NONE } ?: right.intent,
                                left.ownership.takeUnless { it == AllocationOwnership.NONE } ?: right.ownership,
                                maxOf(left.pointerDepth, right.pointerDepth)
                            )
                        },
                        selected.returnIntent.takeUnless { it == AllocationIntent.NONE } ?: candidate.returnIntent,
                        selected.returnOwnership.takeUnless { it == AllocationOwnership.NONE } ?: candidate.returnOwnership
                    )
                }
            }

    private fun collectMethodContracts(ast: CPlusAst, source: String): Map<Int, FunctionContract> =
        ast.root.descendantsAndSelf()
            .filter { it.syntaxKind == "cplus_method_definition" }
            .mapNotNull { method ->
                val functionDeclarator = method.descendantsAndSelf()
                    .firstOrNull { it.syntaxKind == "function_declarator" } ?: return@mapNotNull null
                val nameNode = functionDeclarator.children.firstOrNull { it.fieldName == "declarator" }
                    ?.descendantsAndSelf()?.firstOrNull { it.syntaxKind == "identifier" }
                    ?: return@mapNotNull null
                val parameterList = functionDeclarator.children.firstOrNull { it.fieldName == "parameters" }
                    ?: return@mapNotNull null
                val parameters = parameterList.children
                    .filter { it.syntaxKind in setOf("parameter_declaration", "cplus_parameter_declaration") }
                    .map { parameter ->
                        val declarator = parameter.children.firstOrNull { it.fieldName == "declarator" }
                        val parameterNameNode = declarator?.descendantsAndSelf()
                            ?.firstOrNull { it.syntaxKind == "identifier" }
                        val parameterName = parameterNameNode?.let { text(source, it) }.orEmpty()
                        val annotations = parameter.descendantsAndSelf()
                            .filter { it.syntaxKind == "cplus_parameter_annotation" }
                            .map { text(source, it) }
                            .toList()
                        val pointerDepth = declarator?.let { text(source, it).count { char -> char == '*' } } ?: 0
                        ParameterContract(
                            parameterName,
                            parameterNameNode?.span,
                            annotations.map(::allocationIntent).firstOrNull { it != AllocationIntent.NONE }
                                ?: AllocationIntent.NONE,
                            annotations.map(::allocationOwnership).firstOrNull { it != AllocationOwnership.NONE }
                                ?: AllocationOwnership.NONE,
                            pointerDepth
                        )
                    }
                val resultAnnotations = method.children
                    .filter { it.syntaxKind == "cplus_result_annotation" }
                    .map { text(source, it) }
                val returnIntent = resultAnnotations.map(::allocationIntent)
                    .firstOrNull { it != AllocationIntent.NONE } ?: AllocationIntent.NONE
                val returnOwnership = resultAnnotations.map(::allocationOwnership)
                    .firstOrNull { it != AllocationOwnership.NONE } ?: AllocationOwnership.NONE
                if (returnIntent == AllocationIntent.NONE && returnOwnership == AllocationOwnership.NONE) {
                    return@mapNotNull null
                }
                method.span.startOffset to FunctionContract(
                    text(source, nameNode),
                    nameNode.span,
                    parameters,
                    returnIntent,
                    returnOwnership,
                    "method"
                )
            }
            .toMap()

    private fun infer(
        expression: CPlusAstNode,
        scope: Map<String, VariableState>,
        source: String,
        functionContracts: Map<String, FunctionContract>,
        methodContracts: Map<Int, FunctionContract>,
        resolvedMethodCalls: Map<Int, cplus.CPlusResolvedCall>
    ): Provenance? = when (expression.syntaxKind) {
        "call_expression" -> {
            val resolvedMethod = resolvedMethodCalls[expression.span.startOffset]
            val contract = resolvedMethod?.let { methodContracts[it.declaration.span.startOffset] }
                ?: expression.children.firstOrNull { it.fieldName == "function" }
                    ?.let { functionContracts[text(source, it)] }
            val functionName = resolvedMethod?.let { "${it.ownerType}.${it.methodName}" }
                ?: expression.children.firstOrNull { it.fieldName == "function" }?.let { text(source, it) }
            val intent = contract?.returnIntent?.takeIf { it != AllocationIntent.NONE }
                ?: functionName?.let(::allocatorIntent)
            intent?.let { Provenance(it, "$functionName()") }
        }
        "identifier" -> scope[text(source, expression)]?.let { state ->
            state.provenance ?: state.intent.takeIf { it != AllocationIntent.NONE }
                ?.let { Provenance(it, "${it.label()} pointer '${text(source, expression)}'") }
        }
        "conditional_expression" -> {
            val consequence = expression.children.firstOrNull { it.fieldName == "consequence" }
            val alternative = expression.children.firstOrNull { it.fieldName == "alternative" }
            val left = consequence?.let {
                infer(it, scope, source, functionContracts, methodContracts, resolvedMethodCalls)
            }
            val right = alternative?.let {
                infer(it, scope, source, functionContracts, methodContracts, resolvedMethodCalls)
            }
            if (left != null && right != null && left.intent == right.intent) {
                Provenance(left.intent, "both conditional branches (${left.source}, ${right.source})")
            } else null
        }
        "assignment_expression" -> expression.children.firstOrNull { it.fieldName == "right" }
            ?.let { infer(it, scope, source, functionContracts, methodContracts, resolvedMethodCalls) }
        "comma_expression" -> expression.children
            .lastOrNull { it.named }
            ?.let { infer(it, scope, source, functionContracts, methodContracts, resolvedMethodCalls) }
        "parenthesized_expression", "cast_expression" -> expression.children
            .lastOrNull { it.named }
            ?.let { infer(it, scope, source, functionContracts, methodContracts, resolvedMethodCalls) }
        else -> null
    }

    private fun text(source: String, node: CPlusAstNode): String =
        source.substring(node.span.startOffset, node.span.endOffset)

    private fun declaredNames(declaration: CPlusAstNode, source: String): List<String> =
        declaration.children.filter { it.fieldName == "declarator" }
            .mapNotNull { declarator ->
                val initDeclarator = declarator.takeIf { it.syntaxKind == "init_declarator" }
                val nameDeclarator = initDeclarator?.children?.firstOrNull { it.fieldName == "declarator" }
                    ?: declarator
                nameDeclarator.descendantsAndSelf()
                    .firstOrNull { it.syntaxKind == "identifier" }
                    ?.let { text(source, it) }
            }

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
        val returnOwnership: AllocationOwnership,
        val kind: String = "function"
    )

    private data class ParameterContract(
        val name: String,
        val nameSpan: cplus.SourceSpan?,
        val intent: AllocationIntent,
        val ownership: AllocationOwnership,
        val pointerDepth: Int
    ) {
        val isOutputPointer: Boolean
            get() = ownership == AllocationOwnership.OWNED && pointerDepth >= 2
    }

    private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
        sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }
}
