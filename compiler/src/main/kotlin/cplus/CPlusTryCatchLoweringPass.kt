package cplus

/** AST-based lowering for the specified statement-oriented checked-call / try-catch subset. */
class CPlusTryCatchLoweringPass {
    private data class Handler(val status: String, val label: String)
    private data class CatchArm(val codes: List<String>?, val name: String, val body: CPlusAstNode)
    private data class ResolvedThrowingCall(val function: CPlusThrowingFunction, val implicitReceiver: Boolean)
    private data class Edit(val start: Int, val end: Int, val replacement: MappedText)

    private lateinit var ast: CPlusAst
    private lateinit var source: MappedText
    private lateinit var functions: Map<String, CPlusThrowingFunction>
    private lateinit var semantics: CPlusSemanticIndex
    private lateinit var parents: Map<CPlusAstNode, CPlusAstNode>
    private lateinit var reservedNames: MutableSet<String>
    private var serial = 0
    private val diagnostics = mutableListOf<CPlusLoweringDiagnostic>()

    fun lower(
        ast: CPlusAst,
        source: MappedText,
        functions: Map<String, CPlusThrowingFunction>
    ): CPlusLoweringResult {
        require(ast.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }
        this.ast = ast
        this.source = source
        this.functions = functions
        this.semantics = CPlusSemanticAnalyzer().analyze(ast)
        this.parents = buildMap {
            fun visit(node: CPlusAstNode) {
                node.children.forEach { child ->
                    put(child, node)
                    visit(child)
                }
            }
            visit(ast.root)
        }
        this.reservedNames = Regex("[A-Za-z_]\\w*")
            .findAll(SourceMasker.mask(source.text)).map { it.value }.toMutableSet()
        this.serial = 0
        diagnostics.clear()

        val tries = ast.root.descendantsAndSelf().filter { it.kind == CPlusAstKind.TRY }.toList()
        if (tries.isEmpty()) return CPlusLoweringResult(source, emptyList())
        tries.filter { node ->
            generateSequence(parents[node]) { parents[it] }.none {
                it.syntaxKind in setOf("function_definition", "cplus_function_declaration", "cplus_method_definition")
            }
        }.forEach { node ->
            diagnostic("CPLUS_TRY_OUTSIDE_FUNCTION", "@try is only valid inside a function or method", node.span)
        }
        if (diagnostics.isNotEmpty()) return CPlusLoweringResult(source, diagnostics.toList())
        val rootEdits = tries.filter { node -> ancestorTry(node) == null }
            .mapNotNull { node ->
                val lowered = lowerTry(node, null)
                lowered?.let { Edit(node.span.startOffset, node.span.endOffset, it) }
            }
        if (diagnostics.isNotEmpty()) return CPlusLoweringResult(source, diagnostics.toList())
        return CPlusLoweringResult(applyEdits(source, 0, source.text.length, rootEdits), emptyList())
    }

    private fun lowerTry(node: CPlusAstNode, parentHandler: Handler?): MappedText? {
        val body = node.children.firstOrNull { it.fieldName == "body" && it.syntaxKind == "compound_statement" }
        val catchNodes = node.children.filter { it.kind == CPlusAstKind.CATCH }
        if (body == null || catchNodes.isEmpty()) {
            diagnostic("CPLUS_TRY_MALFORMED", "@try requires a braced body and at least one @catch", node.span)
            return null
        }
        val catches = catchNodes.mapNotNull(::catchArm)
        if (catches.size != catchNodes.size) return null
        if (catches.dropLast(1).any { it.codes == null }) {
            diagnostic("CPLUS_CATCH_ALL_ORDER", "catch-all @catch must be last", catches.first { it.codes == null }.body.span)
            return null
        }
        if (parentHandler == null && catches.last().codes != null) {
            diagnostic("CPLUS_TRY_CATCH_ALL_REQUIRED", "the outermost @try must end with a catch-all", catches.last().body.span)
            return null
        }

        val status = fresh("cplus_error")
        val catchLabel = fresh("cplus_catch")
        val endLabel = fresh("cplus_end")
        val bodyCode = lowerRegion(body, Handler(status, catchLabel))
        val catchBodies = catches.map { arm -> lowerRegion(arm.body, parentHandler) }
        if (diagnostics.isNotEmpty()) return null

        val output = MappedTextBuilder()
        val origin = source.originAt(node.span.startOffset)
        output.appendGenerated("{\n    error_t $status = 0;\n    {\n", origin)
        output.append(bodyCode)
        output.appendGenerated("\n    }\n    goto $endLabel;\n$catchLabel:\n", origin)
        catches.forEachIndexed { index, arm ->
            val condition = arm.codes?.joinToString(" || ") { "$status == $it" } ?: "1"
            output.appendGenerated(
                if (index == 0) "    if ($condition) {\n" else "    else if ($condition) {\n",
                origin
            )
            output.appendGenerated("        error_t ${arm.name} = $status;\n", origin)
            output.append(catchBodies[index])
            output.appendGenerated("\n    }\n", origin)
        }
        if (catches.last().codes != null) {
            val outer = parentHandler
            if (outer == null) {
                diagnostic("CPLUS_TRY_CATCH_ALL_REQUIRED", "unmatched error has no enclosing handler", node.span)
                return null
            }
            output.appendGenerated(
                "    else { ${outer.status} = $status; goto ${outer.label}; }\n",
                origin
            )
        }
        output.appendGenerated("$endLabel:\n    ;\n}", origin)
        return output.build()
    }

    private fun lowerRegion(region: CPlusAstNode, handler: Handler?): MappedText {
        val start = region.span.startOffset
        val end = region.span.endOffset
        val nestedTries = region.descendantsAndSelf()
            .filter { it.kind == CPlusAstKind.TRY && it !== region && !hasTryBetween(it, region) }
            .toList()
        val tryEdits = nestedTries.mapNotNull { nested ->
            lowerTry(nested, handler)?.let { Edit(nested.span.startOffset, nested.span.endOffset, it) }
        }
        val callEdits = if (handler == null) emptyList() else checkedCallEdits(region, handler)
        return applyEdits(source, start, end, tryEdits + callEdits)
    }

    private fun checkedCallEdits(region: CPlusAstNode, handler: Handler): List<Edit> {
        val calls = region.descendantsAndSelf()
            .filter { it.kind == CPlusAstKind.CALL_EXPRESSION && !hasTryBetween(it, region) }
            .sortedBy { it.span.startOffset }
        val edits = mutableListOf<Edit>()
        val handledStatements = mutableSetOf<Pair<Int, Int>>()
        for (call in calls) {
            val resolved = resolveThrowingFunction(call) ?: continue
            val throwingFunction = resolved.function
            val statement = generateSequence(parents[call]) { parents[it] }
                .firstOrNull { it.syntaxKind in setOf("expression_statement", "declaration") }
            if (statement == null) {
                diagnostic(
                    "CPLUS_TRY_CALL_CONTEXT",
                    "checked calls in @try must be complete statements or error-out assignment values",
                    call.span
                )
                continue
            }
            val argumentList = call.descendantsAndSelf().firstOrNull { it.syntaxKind == "argument_list" }
            val closingParen = argumentList?.children?.lastOrNull { it.syntaxKind == ")" }
            if (argumentList == null || closingParen == null) {
                diagnostic("CPLUS_TRY_CALL_MALFORMED", "could not determine checked-call arguments", call.span)
                continue
            }
            val actualArguments = argumentList.children.count { it.named }
            val declaredArguments = throwingFunction.parameterCount - if (resolved.implicitReceiver) 1 else 0
            if (throwingFunction.convention == CPlusThrowsConvention.ERROR_OUT_PARAMETER &&
                actualArguments == declaredArguments) continue // Explicit error pointer: manual handling.

            val statementKey = statement.span.startOffset to statement.span.endOffset
            if (!handledStatements.add(statementKey)) {
                diagnostic("CPLUS_TRY_MULTIPLE_CHECKED_CALLS", "only one checked call is supported per statement", call.span)
                continue
            }
            val prefix = SourceMasker.mask(source.text.substring(statement.span.startOffset, call.span.startOffset)).trim()
            val suffixEnd = (statement.span.endOffset - 1).coerceAtLeast(closingParen.span.endOffset)
            val suffix = SourceMasker.mask(source.text.substring(closingParen.span.endOffset, suffixEnd)).trim()
            val assignmentValue = prefix.endsWith("=") &&
                (prefix.length == 1 || prefix[prefix.lastIndex - 1] !in "=!<>")
            if (suffix.isNotEmpty() || (prefix.isNotEmpty() &&
                    !(throwingFunction.convention == CPlusThrowsConvention.ERROR_OUT_PARAMETER && assignmentValue))) {
                diagnostic(
                    "CPLUS_TRY_CALL_CONTEXT",
                    "checked calls must be standalone statements, or error-out calls assigned to a result",
                    call.span
                )
                continue
            }
            if (throwingFunction.convention == CPlusThrowsConvention.ERROR_OUT_PARAMETER &&
                actualArguments != declaredArguments - 1) {
                diagnostic(
                    "CPLUS_TRY_ARGUMENT_COUNT",
                    "${throwingFunction.name} expects ${declaredArguments - 1} explicit arguments here; pass the error pointer explicitly for manual handling",
                    call.span
                )
                continue
            }
            if (throwingFunction.convention == CPlusThrowsConvention.ERROR_RETURN && assignmentValue) continue

            val replacement = MappedTextBuilder()
            val origin = source.originAt(call.span.startOffset)
            if (throwingFunction.convention == CPlusThrowsConvention.ERROR_RETURN) {
                replacement.appendGenerated("${handler.status} = ", origin)
                replacement.append(source, statement.span.startOffset, statement.span.endOffset)
            } else {
                replacement.appendGenerated("${handler.status} = 0;\n", origin)
                replacement.append(source, statement.span.startOffset, closingParen.span.startOffset)
                replacement.appendGenerated(
                    if (actualArguments == 0) "&${handler.status}" else ", &${handler.status}",
                    origin
                )
                replacement.append(source, closingParen.span.startOffset, statement.span.endOffset)
            }
            replacement.appendGenerated("\nif (${handler.status} != 0) goto ${handler.label};", origin)
            edits += Edit(statement.span.startOffset, statement.span.endOffset, replacement.build())
        }
        return edits
    }

    private fun resolveThrowingFunction(call: CPlusAstNode): ResolvedThrowingCall? {
        val called = call.children.firstOrNull { it.fieldName == "function" } ?: call.children.firstOrNull()
        val name = called?.let { source.text.substring(it.span.startOffset, it.span.endOffset).trim() } ?: return null
        if (name.matches(Regex("[A-Za-z_]\\w*"))) {
            return functions[name]?.let { ResolvedThrowingCall(it, implicitReceiver = false) }
        }
        val method = semantics.resolvedCalls.firstOrNull { it.span == call.span } ?: return null
        val stem = if (method.ownerType.endsWith("_t")) method.ownerType.dropLast(2) else method.ownerType
        return functions["${stem}__${method.methodName}"]?.let {
            ResolvedThrowingCall(it, implicitReceiver = !method.staticCall && it.hasImplicitReceiver)
        }
    }

    private fun catchArm(node: CPlusAstNode): CatchArm? {
        val indexed = semantics.catchBindings.firstOrNull { it.span == node.span }
        val body = node.children.firstOrNull { it.fieldName == "body" }
        val name = indexed?.parameterName
        if (body == null || name == null || indexed.typeName != "error_t") {
            diagnostic("CPLUS_CATCH_BINDING", "@catch requires an error_t binding and braced body", node.span)
            return null
        }
        return CatchArm(indexed.codes, name, body)
    }

    private fun hasTryBetween(node: CPlusAstNode, stop: CPlusAstNode): Boolean {
        var current = parents[node]
        while (current != null && current !== stop) {
            if (current.kind == CPlusAstKind.TRY) return true
            current = parents[current]
        }
        return false
    }

    private fun ancestorTry(node: CPlusAstNode): CPlusAstNode? =
        generateSequence(parents[node]) { parents[it] }.firstOrNull { it.kind == CPlusAstKind.TRY }

    private fun applyEdits(input: MappedText, start: Int, end: Int, edits: List<Edit>): MappedText {
        val ordered = edits.sortedBy { it.start }
        val output = MappedTextBuilder()
        var cursor = start
        ordered.forEach { edit ->
            if (edit.start < cursor || edit.end < edit.start || edit.end > end) {
                diagnostic("CPLUS_TRY_OVERLAPPING_EDITS", "overlapping try/catch or checked-call edits", ast.source.sourceFile.span(edit.start, edit.end))
                return input.slice(start, end)
            }
            output.append(input, cursor, edit.start)
            output.append(edit.replacement)
            cursor = edit.end
        }
        output.append(input, cursor, end)
        return output.build()
    }

    private fun fresh(prefix: String): String {
        while (true) {
            val candidate = "${prefix}_${serial++}"
            if (reservedNames.add(candidate)) return candidate
        }
    }

    private fun diagnostic(code: String, message: String, span: SourceSpan) {
        diagnostics += CPlusLoweringDiagnostic(code, message, span)
    }
}

private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
    sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }
