package cplus

/** AST-backed receiver-call rewrite; method declarations are lowered by a later companion pass. */
class CPlusMethodCallLoweringPass {
    fun lower(ast: CPlusAst, source: MappedText, index: CPlusSemanticIndex): CPlusLoweringResult {
        require(ast.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }
        if (index.resolvedCalls.isEmpty()) return CPlusLoweringResult(source, emptyList())

        val edits = mutableListOf<CPlusMappedEdit>()
        index.resolvedCalls.forEach { call ->
            val origin = source.originAt(call.span.startOffset)
            val emittedName = "${typeStem(call.ownerType)}__${call.methodName}"
            if (call.staticCall) {
                edits += CPlusMappedEdit(call.memberAccessSpan, MappedText.generated(emittedName, origin))
            } else {
                val receiver = source.text.substring(call.receiverSpan.startOffset, call.receiverSpan.endOffset).trim()
                val receiverArgument = when {
                    call.pointerAccess || call.receiverAlreadyPointer -> receiver
                    receiver.matches(IDENTIFIER) -> "&$receiver"
                    else -> "&($receiver)"
                }
                edits += CPlusMappedEdit(call.operatorSpan, MappedText.generated("", source.originAt(call.operatorSpan.startOffset)))
                edits += CPlusMappedEdit(call.receiverSpan, MappedText.generated("", source.originAt(call.receiverSpan.startOffset)))
                edits += CPlusMappedEdit(call.memberNameSpan, MappedText.generated(emittedName, source.originAt(call.memberNameSpan.startOffset)))
                val separator = if (call.hasArguments) ", " else ""
                edits += CPlusMappedEdit(
                    ast.source.sourceFile.span(call.argumentInsertionOffset, call.argumentInsertionOffset),
                    MappedText.generated(receiverArgument + separator, origin)
                )
            }
        }

        return try {
            CPlusLoweringResult(CPlusMappedAstEmitter().emit(ast, source, edits), emptyList())
        } catch (failure: IllegalArgumentException) {
            CPlusLoweringResult(
                source,
                listOf(CPlusLoweringDiagnostic("CPLUS_METHOD_OVERLAPPING_EDITS", failure.message.orEmpty(), index.resolvedCalls.first().span))
            )
        }
    }

    private fun typeStem(name: String): String = if (name.endsWith("_t")) name.dropLast(2) else name

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
