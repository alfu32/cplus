package cplus

/**
 * A deliberately conservative source-level analysis for allocation intent.
 * It models declarations, direct allocator calls, annotated function contracts,
 * and straightforward pointer assignments without pretending to be a full C AST.
 */
internal class AllocationIntentAnalyzer {
    private val identifierCall = Regex("\\b([A-Za-z_]\\w*)\\s*\\(")
    private val declaration = Regex(
        """(?m)(?:^|[;{}])\s*(?<annotations>(?:(?:scratch|hot|warm|cold|owned|borrowed|mut|static|extern|register|const|volatile|unsigned|signed|long|short|struct|typedef)\s+)*)[A-Za-z_]\w*(?:\s*\*+)?\s*(?<name>[A-Za-z_]\w*)\s*(?:\[[^\]]*\])?\s*(?:=\s*(?<initializer>[^;]*))?;"""
    )
    private val assignment = Regex(
        """(?<![A-Za-z0-9_>.])(?:\*\s*)?(?<name>[A-Za-z_]\w*)\s*=(?!=)\s*(?<value>[^;]+);"""
    )
    private val returned = Regex("\\breturn\\s+(?<value>[^;]+);")
    private val allocatorCall = Regex("\\b(?:alloc|calloc|realloc)_(scratch|hot|warm|cold)(?:_aligned)?\\s*\\(")
    private val intentToken = Regex("\\b(scratch|hot|warm|cold)\\b")
    private val ownershipToken = Regex("\\b(owned|borrowed)\\b")

    fun analyze(input: MappedText): AllocationAnalysisResult {
        val masked = SourceMasker.mask(input.text)
        val signatures = collectFunctionSignatures(masked)
        val definitions = signatures.values.filter { it.bodyStart != null && it.bodyEnd != null }
        val symbols = mutableListOf<AllocationSymbol>()
        val diagnostics = linkedMapOf<String, CPlusDiagnostic>()

        signatures.values.forEach { function ->
            sourceSpan(input, function.returnSpanOffset)?.let { span ->
                if (function.returnIntent != AllocationIntent.NONE || function.returnOwnership != AllocationOwnership.NONE) {
                    symbols += AllocationSymbol(
                        function.name,
                        AllocationSymbolKind.FUNCTION_RETURN,
                        function.returnIntent,
                        function.returnOwnership,
                        function.returnIntent,
                        span
                    )
                }
            }
            function.parameters.forEach { parameter ->
                sourceSpan(input, parameter.offsetPlaceholder)?.let { span ->
                    val isOutput = parameter.ownership == AllocationOwnership.OWNED && parameter.text.count { it == '*' } >= 2
                    symbols += AllocationSymbol(
                        parameter.name,
                        AllocationSymbolKind.PARAMETER,
                        parameter.intent,
                        parameter.ownership,
                        if (isOutput) AllocationIntent.NONE else parameter.intent,
                        span
                    )
                }
            }
        }

        val functionRanges = definitions.mapNotNull { function ->
            val start = function.bodyStart ?: return@mapNotNull null
            val end = function.bodyEnd ?: return@mapNotNull null
            start until end
        }

        val globalState = linkedMapOf<String, VariableState>()
        val outsideFunctions = mutableListOf<IntRange>()
        var globalStart = 0
        while (globalStart < input.text.length) {
            val containingFunction = functionRanges.firstOrNull { globalStart in it }
            if (containingFunction != null) {
                globalStart = containingFunction.last + 1
            } else {
                val nextFunction = functionRanges.firstOrNull { it.first > globalStart }
                val end = nextFunction?.first ?: input.text.length
                if (end > globalStart) outsideFunctions += globalStart until end
                globalStart = end
            }
        }

        outsideFunctions.forEach { range ->
            analyzeRegion(
                input,
                masked,
                range.first,
                range.last + 1,
                null,
                signatures,
                symbols,
                diagnostics,
                globalState
            )
        }

        definitions.forEach { function ->
            val bodyStart = function.bodyStart ?: return@forEach
            val bodyEnd = function.bodyEnd ?: return@forEach
            val state = linkedMapOf<String, VariableState>()
            function.parameters.forEach { parameter ->
                val pointerDepth = parameter.text.count { it == '*' }
                val outputParameter = parameter.ownership == AllocationOwnership.OWNED && pointerDepth >= 2
                val initialProvenance = when {
                    parameter.intent == AllocationIntent.NONE -> null
                    outputParameter -> null
                    else -> Provenance(parameter.intent, "${parameter.intent.lowercase()} parameter '${parameter.name}'")
                }
                state[parameter.name] = VariableState(parameter.intent, initialProvenance)
            }
            analyzeRegion(
                input,
                masked,
                bodyStart,
                bodyEnd,
                function,
                signatures,
                symbols,
                diagnostics,
                state
            )
        }

        return AllocationAnalysisResult(
            symbols.distinctBy { listOf(it.name, it.kind, it.sourceSpan.file, it.sourceSpan.startOffset) },
            diagnostics.values.sortedWith(compareBy({ it.sourceSpan.file }, { it.sourceSpan.startOffset }, { it.message }))
        )
    }

    private fun analyzeRegion(
        input: MappedText,
        masked: String,
        start: Int,
        end: Int,
        function: FunctionSignature?,
        signatures: Map<String, FunctionSignature>,
        symbols: MutableList<AllocationSymbol>,
        diagnostics: MutableMap<String, CPlusDiagnostic>,
        state: MutableMap<String, VariableState>
    ) {
        if (end <= start) return
        val region = masked.substring(start, end)
        val declarations = declaration.findAll(region).mapNotNull { match ->
            val nameMatch = match.groups["name"] ?: return@mapNotNull null
            val name = nameMatch.value
            if (name in cKeywords) return@mapNotNull null
            val intent = parseIntent(match.groups["annotations"]?.value.orEmpty())
            val ownership = parseOwnership(match.groups["annotations"]?.value.orEmpty())
            val initializer = match.groups["initializer"]?.value?.trim().orEmpty()
            VariableDeclaration(
                name = name,
                intent = intent,
                ownership = ownership,
                initializer = initializer,
                nameOffset = start + nameMatch.range.first,
                statementStart = start + match.range.first,
                statementEnd = start + match.range.last + 1
            )
        }.toList()

        val events = mutableListOf<AnalysisEvent>()
        declarations.forEach { events += AnalysisEvent.Declare(it.nameOffset, it) }

        assignment.findAll(region).forEach { match ->
            val nameRange = match.groups["name"]?.range ?: return@forEach
            val absoluteNameStart = start + nameRange.first
            if (declarations.any { absoluteNameStart in it.statementStart until it.statementEnd }) return@forEach
            events += AnalysisEvent.Assign(
                absoluteNameStart,
                match.groups["name"]!!.value,
                match.groups["value"]!!.value.trim()
            )
        }

        returned.findAll(region).forEach { match ->
            if (function != null && function.returnIntent != AllocationIntent.NONE) {
                events += AnalysisEvent.Return(
                    start + match.range.first,
                    match.groups["value"]!!.value.trim(),
                    function.returnIntent,
                    function.name
                )
            }
        }

        identifierCall.findAll(region).forEach { match ->
            val name = match.groupValues[1]
            val signature = signatures[name] ?: return@forEach
            if (signature.parameters.isEmpty()) return@forEach
            val open = region.indexOf('(', match.range.first)
            val close = matching(region, open, '(', ')')
            if (close < 0) return@forEach
            val after = match.range.last + 1
            if (after < region.length && region[after] == '*') return@forEach
            val arguments = splitArguments(region, open + 1, close)
            events += AnalysisEvent.Call(start + match.range.first, signature, arguments.map { argument ->
                argument.copy(startOffset = argument.startOffset + start)
            })
        }

        events.sortedWith(compareBy<AnalysisEvent>({ it.offset }, { it.priority })).forEach { event ->
            when (event) {
                is AnalysisEvent.Declare -> {
                    val item = event.declaration
                    val inferred = infer(item.initializer, state, signatures)
                    if (item.intent != AllocationIntent.NONE && inferred != null) {
                        reportMismatch(
                            input,
                            diagnostics,
                            item.intent,
                            inferred,
                            item.nameOffset,
                            "allocation intent mismatch: '${item.name}' is declared ${item.intent.lowercase()} but receives memory from ${inferred.source}"
                        )
                    }
                    val provenance = inferred ?: if (item.intent != AllocationIntent.NONE && item.ownership == AllocationOwnership.BORROWED) {
                        Provenance(item.intent, "${item.intent.lowercase()} pointer '${item.name}'")
                    } else null
                    state[item.name] = VariableState(item.intent, provenance)
                    sourceSpan(input, item.nameOffset)?.let { span ->
                        symbols += AllocationSymbol(
                            item.name,
                            AllocationSymbolKind.VARIABLE,
                            item.intent,
                            item.ownership,
                            provenance?.intent ?: AllocationIntent.NONE,
                            span
                        )
                    }
                }

                is AnalysisEvent.Assign -> {
                    val existing = state[event.name]
                    val inferred = infer(event.value, state, signatures)
                    if (existing != null && existing.intent != AllocationIntent.NONE && inferred != null) {
                        reportMismatch(
                            input,
                            diagnostics,
                            existing.intent,
                            inferred,
                            event.offset,
                            "allocation intent mismatch: '${event.name}' is declared ${existing.intent.lowercase()} but receives memory from ${inferred.source}"
                        )
                    }
                    if (existing != null) state[event.name] = existing.copy(provenance = inferred)
                    else if (inferred != null) state[event.name] = VariableState(AllocationIntent.NONE, inferred)
                }

                is AnalysisEvent.Call -> {
                    event.signature.parameters.zip(event.arguments).forEach { (parameter, argument) ->
                        if (parameter.intent == AllocationIntent.NONE) return@forEach
                        val inferred = infer(argument.text, state, signatures)
                        if (inferred != null && inferred.intent != parameter.intent) {
                            reportMismatch(
                                input,
                                diagnostics,
                                parameter.intent,
                                inferred,
                                argument.startOffset,
                                "allocation intent mismatch: argument for '${event.signature.name}.${parameter.name}' is ${inferred.intent.lowercase()} but the parameter expects ${parameter.intent.lowercase()}"
                            )
                        }
                        if (parameter.ownership == AllocationOwnership.OWNED && parameter.text.count { it == '*' } >= 2) {
                            addressTarget(argument.text)?.let { target ->
                                val previous = state[target]
                                state[target] = (previous ?: VariableState(
                                    AllocationIntent.NONE,
                                    null
                                )).copy(provenance = Provenance(parameter.intent, "${parameter.intent.lowercase()} output from ${event.signature.name}()"))
                            }
                        }
                    }
                }

                is AnalysisEvent.Return -> {
                    val inferred = infer(event.value, state, signatures) ?: return@forEach
                    if (inferred.intent != event.expected) {
                        reportMismatch(
                            input,
                            diagnostics,
                            event.expected,
                            inferred,
                            event.offset,
                            "allocation intent mismatch: function '${event.name}' is annotated ${event.expected.lowercase()} but returns ${inferred.source}"
                        )
                    }
                }
            }
        }
    }

    private fun collectFunctionSignatures(masked: String): Map<String, FunctionSignature> {
        val result = linkedMapOf<String, FunctionSignature>()
        identifierCall.findAll(masked).forEach { match ->
            val name = match.groupValues[1]
            if (name in cKeywords) return@forEach
            val open = masked.indexOf('(', match.range.first)
            val close = matching(masked, open, '(', ')')
            if (close < 0) return@forEach
            var after = close + 1
            while (after < masked.length && masked[after].isWhitespace()) after++
            val isDefinition = after < masked.length && masked[after] == '{'
            if (!isDefinition && (after >= masked.length || masked[after] != ';')) return@forEach

            var prefixStart = match.range.first - 1
            while (prefixStart >= 0 && masked[prefixStart] !in ";{}") prefixStart--
            prefixStart++
            val prefix = masked.substring(prefixStart, match.range.first)
            if ('=' in prefix || Regex("\\b(return|if|while|for|switch|sizeof)\\b").containsMatchIn(prefix)) return@forEach
            if (prefix.isBlank() || !Regex("[A-Za-z_]\\w*").containsMatchIn(prefix)) return@forEach

            val parameters = splitArguments(masked, open + 1, close).mapNotNull { argument ->
                val text = argument.text.trim()
                if (text.isEmpty() || text == "void" || text == "...") return@mapNotNull null
                val nameMatch = Regex("([A-Za-z_]\\w*)\\s*(?:\\[[^]]*])?\\s*$").find(text)
                    ?: return@mapNotNull null
                val parameterName = nameMatch.groupValues[1]
                if (parameterName in cKeywords) return@mapNotNull null
                val annotations = text.substring(0, nameMatch.range.first)
                val originalOffset = argument.startOffset + argument.text.indexOf(parameterName)
                FunctionParameter(
                    parameterName,
                    parseIntent(annotations),
                    parseOwnership(annotations),
                    text,
                    originalOffset
                )
            }
            val taggedIntent = parseIntent(prefix)
            val taggedOwnership = parseOwnership(prefix)
            val returnNameOffset = match.range.first
            val signature = FunctionSignature(
                name = name,
                returnIntent = taggedIntent,
                returnOwnership = taggedOwnership,
                returnSpanOffset = returnNameOffset,
                parameters = parameters,
                bodyStart = if (isDefinition) after + 1 else null,
                bodyEnd = if (isDefinition) matching(masked, after, '{', '}') else null
            )

            val previous = result[name]
            if (previous == null || isDefinition || previous.returnIntent == AllocationIntent.NONE && taggedIntent != AllocationIntent.NONE) {
                result[name] = signature
            }
        }

        return result
    }

    private fun infer(
        expression: String,
        state: Map<String, VariableState>,
        signatures: Map<String, FunctionSignature>
    ): Provenance? {
        val value = expression.trim().trim(';')
        if (value.isEmpty() || value == "NULL" || value == "0") return null
        val call = Regex("^(?:\\([^()]*\\)\\s*)?([A-Za-z_]\\w*)\\s*\\(").find(value)
        if (call != null) {
            val name = call.groupValues[1]
            val intent = signatures[name]?.returnIntent?.takeIf { it != AllocationIntent.NONE }
                ?: allocatorIntent(name)
            if (intent != AllocationIntent.NONE) return Provenance(intent, "$name()")
        }
        val reference = Regex("^\\s*&?\\s*\\*?\\s*([A-Za-z_]\\w*)\\s*(?:\\[[^]]*])?\\s*$").find(value)
        if (reference != null) {
            val name = reference.groupValues[1]
            val item = state[name] ?: return null
            return item.provenance ?: item.intent.takeIf { it != AllocationIntent.NONE }?.let {
                Provenance(it, "${it.lowercase()} pointer '$name'")
            }
        }
        return null
    }

    private fun allocatorIntent(name: String): AllocationIntent {
        val match = Regex("^(?:alloc|calloc|realloc)_(scratch|hot|warm|cold)(?:_aligned)?$").matchEntire(name)
            ?: return AllocationIntent.NONE
        return parseIntent(match.groupValues[1])
    }

    private fun reportMismatch(
        input: MappedText,
        diagnostics: MutableMap<String, CPlusDiagnostic>,
        expected: AllocationIntent,
        actual: Provenance,
        offset: Int,
        message: String
    ) {
        if (expected == AllocationIntent.NONE || actual.intent == expected) return
        val span = sourceSpan(input, offset) ?: return
        val diagnostic = CPlusDiagnostic(message, span)
        diagnostics["${span.file}:${span.startOffset}:$message"] = diagnostic
    }

    private fun sourceSpan(input: MappedText, offset: Int): SourceSpan? =
        if (input.text.isEmpty()) null
        else input.originAt(offset.coerceIn(0, input.text.lastIndex))?.let { it.file.span(it.offset) }

    private fun parseIntent(text: String): AllocationIntent = intentToken.find(text)?.groupValues?.get(1)
        ?.uppercase()
        ?.let { runCatching { AllocationIntent.valueOf(it) }.getOrNull() }
        ?: AllocationIntent.NONE

    private fun parseOwnership(text: String): AllocationOwnership = when (ownershipToken.find(text)?.groupValues?.get(1)) {
        "owned" -> AllocationOwnership.OWNED
        "borrowed" -> AllocationOwnership.BORROWED
        else -> AllocationOwnership.NONE
    }

    private fun addressTarget(expression: String): String? =
        Regex("^\\s*&\\s*([A-Za-z_]\\w*)\\s*$").find(expression)?.groupValues?.get(1)

    private fun splitArguments(text: String, start: Int, end: Int): List<Argument> {
        if (start >= end || text.substring(start, end).isBlank()) return emptyList()
        val arguments = mutableListOf<Argument>()
        var itemStart = start
        var parens = 0
        var brackets = 0
        var braces = 0
        for (index in start until end) {
            when (text[index]) {
                '(' -> parens++
                ')' -> parens--
                '[' -> brackets++
                ']' -> brackets--
                '{' -> braces++
                '}' -> braces--
                ',' -> if (parens == 0 && brackets == 0 && braces == 0) {
                    arguments += argument(text, itemStart, index)
                    itemStart = index + 1
                }
            }
        }
        arguments += argument(text, itemStart, end)
        return arguments
    }

    private fun argument(text: String, start: Int, end: Int): Argument {
        val raw = text.substring(start, end)
        val leading = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        return Argument(raw.trim(), start + leading)
    }

    private fun matching(text: String, open: Int, opening: Char, closing: Char): Int {
        if (open !in text.indices || text[open] != opening) return -1
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                opening -> depth++
                closing -> if (--depth == 0) return index
            }
        }
        return -1
    }

    private fun AllocationIntent.lowercase(): String = name.lowercase(java.util.Locale.ROOT)

    private val cKeywords = setOf(
        "if", "else", "for", "while", "do", "switch", "case", "default", "return", "break", "continue",
        "sizeof", "_Alignof", "typedef", "struct", "union", "enum", "const", "volatile", "static", "extern",
        "register", "auto", "signed", "unsigned", "long", "short", "void", "char", "int", "float", "double"
    )

    private data class Argument(val text: String, val startOffset: Int)
    private data class Provenance(val intent: AllocationIntent, val source: String)
    private data class VariableState(
        val intent: AllocationIntent,
        val provenance: Provenance?
    )
    private data class VariableDeclaration(
        val name: String,
        val intent: AllocationIntent,
        val ownership: AllocationOwnership,
        val initializer: String,
        val nameOffset: Int,
        val statementStart: Int,
        val statementEnd: Int
    )
    private data class FunctionParameter(
        val name: String,
        val intent: AllocationIntent,
        val ownership: AllocationOwnership,
        val text: String,
        val offsetPlaceholder: Int
    )
    private data class FunctionSignature(
        val name: String,
        val returnIntent: AllocationIntent,
        val returnOwnership: AllocationOwnership,
        val returnSpanOffset: Int,
        val parameters: List<FunctionParameter>,
        val bodyStart: Int?,
        val bodyEnd: Int?
    )

    private sealed class AnalysisEvent(val offset: Int, val priority: Int) {
        class Declare(offset: Int, val declaration: VariableDeclaration) : AnalysisEvent(offset, 0)
        class Assign(offset: Int, val name: String, val value: String) : AnalysisEvent(offset, 1)
        class Call(offset: Int, val signature: FunctionSignature, val arguments: List<Argument>) : AnalysisEvent(offset, 2)
        class Return(offset: Int, val value: String, val expected: AllocationIntent, val name: String) : AnalysisEvent(offset, 3)
    }
}
