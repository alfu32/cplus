package cplus

/** AST-backed receiver-call rewrite; method declarations are lowered by a later companion pass. */
class CPlusMethodCallLoweringPass {
    private data class ResolvedCallNode(val node: CPlusAstNode, val call: CPlusResolvedCall)

    fun lower(ast: CPlusAst, source: MappedText, index: CPlusSemanticIndex): CPlusLoweringResult {
        require(ast.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }
        if (index.resolvedCalls.isEmpty()) return CPlusLoweringResult(source, emptyList())

        val callNodesBySpan = ast.root.descendantsAndSelf()
            .filter { it.syntaxKind == "call_expression" }
            .associateBy { it.span }
        val calls = index.resolvedCalls.mapNotNull { call ->
            callNodesBySpan[call.span]?.let { ResolvedCallNode(it, call) }
        }
        if (calls.size != index.resolvedCalls.size) {
            val missing = index.resolvedCalls.first { call -> callNodesBySpan[call.span] == null }
            return CPlusLoweringResult(
                source,
                listOf(CPlusLoweringDiagnostic(
                    "CPLUS_METHOD_CALL_NODE_MISSING",
                    "resolved method call did not map to a call-expression AST node",
                    missing.span
                ))
            )
        }

        val replacements = linkedMapOf<CPlusAstNode, MappedText>()
        val diagnostics = mutableListOf<CPlusLoweringDiagnostic>()
        calls.filter { candidate ->
            calls.none { parent -> parent !== candidate && parent.node.span.containsSpan(candidate.node.span) }
        }.forEach { topLevel ->
            try {
                replacements[topLevel.node] = lowerCall(topLevel, calls, source)
            } catch (failure: IllegalArgumentException) {
                diagnostics += CPlusLoweringDiagnostic(
                    "CPLUS_METHOD_CALL_MALFORMED",
                    failure.message.orEmpty(),
                    topLevel.call.span
                )
            }
        }
        if (diagnostics.isNotEmpty()) return CPlusLoweringResult(source, diagnostics)

        return try {
            CPlusLoweringResult(CPlusMappedAstEmitter().emit(ast, source, replacements), emptyList())
        } catch (failure: IllegalArgumentException) {
            CPlusLoweringResult(
                source,
                listOf(CPlusLoweringDiagnostic("CPLUS_METHOD_CALL_EMISSION", failure.message.orEmpty(), calls.first().call.span))
            )
        }
    }

    private fun lowerCall(
        resolved: ResolvedCallNode,
        allCalls: List<ResolvedCallNode>,
        source: MappedText
    ): MappedText {
        val call = resolved.call
        require(call.argumentInsertionOffset in resolved.node.span.startOffset..resolved.node.span.endOffset) {
            "method call has an invalid argument-list insertion point"
        }
        val output = MappedTextBuilder()
        val nameOrigin = source.originAt(call.memberNameSpan.startOffset)
        val callOrigin = source.originAt(call.span.startOffset)
        val emittedName = "${typeStem(call.ownerType)}__${call.methodName}"
        output.appendGenerated(emittedName, nameOrigin)
        output.appendGenerated("(", callOrigin)

        if (!call.staticCall && !call.explicitReceiver) {
            val receiverText = source.text.substring(call.receiverSpan.startOffset, call.receiverSpan.endOffset)
            val receiverStart = call.receiverSpan.startOffset + receiverText.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val receiverEnd = call.receiverSpan.startOffset + receiverText.indexOfLast { !it.isWhitespace() }.let { if (it < 0) 0 else it + 1 }
            require(receiverStart < receiverEnd) { "method call has an empty receiver expression" }
            val receiver = source.text.substring(receiverStart, receiverEnd)
            when {
                call.pointerAccess || call.receiverAlreadyPointer -> appendRange(output, receiverStart, receiverEnd, allCalls, source)
                receiver.matches(IDENTIFIER) -> {
                    output.appendGenerated("&", source.originAt(receiverStart))
                    appendRange(output, receiverStart, receiverEnd, allCalls, source)
                }
                else -> {
                    output.appendGenerated("&(", source.originAt(receiverStart))
                    appendRange(output, receiverStart, receiverEnd, allCalls, source)
                    output.appendGenerated(")", source.originAt(receiverEnd - 1))
                }
            }
            if (call.hasArguments) output.appendGenerated(", ", callOrigin)
        }
        appendRange(output, call.argumentInsertionOffset, resolved.node.span.endOffset, allCalls, source)
        return output.build()
    }

    /** Emits a source interval while recursively lowering any resolved calls wholly inside it. */
    private fun appendRange(
        output: MappedTextBuilder,
        start: Int,
        end: Int,
        calls: List<ResolvedCallNode>,
        source: MappedText
    ) {
        require(start in 0..source.text.length && end in start..source.text.length) {
            "method-call source interval $start..$end is out of bounds"
        }
        val nested = calls.filter { it.node.span.startOffset >= start && it.node.span.endOffset <= end }
            .filter { candidate ->
                calls.none { parent ->
                    parent !== candidate && parent.node.span.startOffset >= start && parent.node.span.endOffset <= end &&
                        parent.node.span.containsSpan(candidate.node.span)
                }
            }
            .sortedBy { it.node.span.startOffset }
        var cursor = start
        nested.forEach { nestedCall ->
            require(nestedCall.node.span.startOffset >= cursor) { "overlapping method-call AST nodes" }
            output.append(source, cursor, nestedCall.node.span.startOffset)
            output.append(lowerCall(nestedCall, calls, source))
            cursor = nestedCall.node.span.endOffset
        }
        output.append(source, cursor, end)
    }

    private fun typeStem(name: String): String = if (name.endsWith("_t")) name.dropLast(2) else name

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
    sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }

private fun SourceSpan.containsSpan(other: SourceSpan): Boolean =
    startOffset <= other.startOffset && endOffset >= other.endOffset
