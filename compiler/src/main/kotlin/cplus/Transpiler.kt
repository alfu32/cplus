package cplus

/** Converts the supported C-plus syntax into C while retaining source origins. */
class CPlusTranspiler {
    fun transpile(
        source: String,
        sourceName: String? = null,
        logger: CompilationLogger = SilentCompilationLogger
    ): TranscodedSource {
        val sourceFile = SourceFile(source, sourceName)
        val input = logger.pass("comptime-resolve") {
            ComptimeCompiler(sourceFile, logger).compile()
        }

        val typeNames = logger.pass("collect-struct-types") {
            StructTypeCollector().collect(input.text)
        }
        val calls = logger.pass("lower-method-calls") {
            MethodCallLowerer(typeNames).lower(input)
        }
        val structs = logger.pass("lower-struct-methods") {
            StructLowerer().lower(calls)
        }
        return logger.pass("emit-mapped-c") {
            MappedEmitter(sourceFile).emit(structs, CPlusPreamble.text)
        }
    }
}

class CPlusSyntaxException(
    message: String,
    val sourceSpan: SourceSpan? = null
) : IllegalArgumentException(message)

private object CPlusPreamble {
    val text = """
        /* C-plus source annotations are intentionally retained in generated C. */
        #ifndef CPLUS_ANNOTATIONS_DEFINED
        #define CPLUS_ANNOTATIONS_DEFINED
        #define pub
        #define priv
        #define mut
        #define borrowed
        #define owned
        #define stat
        #endif

    """.trimIndent() + "\n\n"
}

private class StructTypeCollector {
    private val structStart = Regex("\\btypedef\\s+struct(?:\\s+([A-Za-z_]\\w*))?\\s*\\{")
    private val aliasAfterStruct = Regex("\\s*([A-Za-z_]\\w*)\\s*;")

    fun collect(source: String): Set<String> {
        val masked = SourceMasker.mask(source)
        val types = linkedSetOf<String>()
        var search = 0
        while (true) {
            val match = structStart.find(masked, search) ?: break
            val openBrace = masked.indexOf('{', match.range.first)
            val closeBrace = Delimiters.match(masked, openBrace, '{', '}')
            if (openBrace < 0 || closeBrace < 0) break
            val alias = aliasAfterStruct.find(masked, closeBrace + 1) ?: break
            types += alias.groupValues[1]
            search = alias.range.last + 1
        }
        return types
    }
}

private class StructLowerer {
    private val structStart = Regex("\\btypedef\\s+struct(?:\\s+([A-Za-z_]\\w*))?\\s*\\{")
    private val aliasAfterStruct = Regex("\\s*([A-Za-z_]\\w*)\\s*;")

    fun lower(input: MappedText): MappedText {
        val masked = SourceMasker.mask(input.text)
        val output = MappedTextBuilder()
        var cursor = 0
        var search = 0

        while (true) {
            val match = structStart.find(masked, search) ?: break
            val openBrace = masked.indexOf('{', match.range.first)
            val closeBrace = Delimiters.match(masked, openBrace, '{', '}')
            if (openBrace < 0 || closeBrace < 0) break
            val alias = aliasAfterStruct.find(masked, closeBrace + 1) ?: break

            output.append(input, cursor, match.range.first)
            val body = input.slice(openBrace + 1, closeBrace)
            val members = StructMembers.extract(body, alias.groupValues[1])
            if (members.methods.isEmpty()) {
                output.append(input, match.range.first, alias.range.last + 1)
            } else {
                val origin = input.originAt(match.range.first)
                output.appendGenerated("typedef struct ${alias.groupValues[1]} {", origin)
                output.append(members.fields)
                output.appendGenerated("\n} ${alias.groupValues[1]};\n\n", origin)
                members.methods.forEachIndexed { index, method ->
                    if (index > 0) output.appendGenerated("\n\n")
                    output.append(method)
                }
                output.appendGenerated("\n", origin)
            }

            cursor = alias.range.last + 1
            search = cursor
        }

        output.append(input, cursor, input.text.length)
        return output.build()
    }
}

private data class StructMembers(
    val fields: MappedText,
    val methods: List<MappedText>
) {
    companion object {
        fun extract(body: MappedText, typeName: String): StructMembers {
            val masked = SourceMasker.mask(body.text)
            val fields = MappedTextBuilder()
            val methods = mutableListOf<MappedText>()
            var segmentStart = 0
            var index = 0

            while (index < body.text.length) {
                when {
                    masked[index] == '(' -> {
                        val close = Delimiters.match(masked, index, '(', ')')
                        if (close >= 0) {
                            val after = Delimiters.skipWhitespace(masked, close + 1)
                            if (after < body.text.length && (masked[after] == '{' || masked[after] == ';')) {
                                val header = body.text.substring(segmentStart, close + 1).trim()
                                if (looksLikeMethod(header)) {
                                    val end = if (masked[after] == '{') {
                                        val methodClose = Delimiters.match(masked, after, '{', '}')
                                        if (methodClose < 0) {
                                            throw CPlusSyntaxException("unclosed method body in struct $typeName")
                                        }
                                        methodClose + 1
                                    } else {
                                        after + 1
                                    }
                                    methods += MethodLowerer.lower(body.slice(segmentStart, end), typeName)
                                    segmentStart = end
                                    index = end
                                    continue
                                }
                            }
                        }
                    }

                    masked[index] == ';' -> {
                        fields.append(body, segmentStart, index + 1)
                        segmentStart = index + 1
                    }
                }
                index++
            }

            fields.append(body, segmentStart, body.text.length)
            return StructMembers(fields.build(), methods)
        }

        private fun looksLikeMethod(header: String): Boolean {
            val beforeOpen = header.substringBeforeLast('(').trim()
            if (beforeOpen.contains("(*") || beforeOpen.contains("(&")) return false
            val name = Regex("([A-Za-z_]\\w*)\\s*$").find(beforeOpen)?.groupValues?.get(1) ?: return false
            return name !in setOf("if", "for", "while", "switch")
        }
    }
}

private object MethodLowerer {
    fun lower(method: MappedText, typeName: String): MappedText {
        val open = method.text.indexOf('(')
        val masked = SourceMasker.mask(method.text)
        val close = Delimiters.match(masked, open, '(', ')')
        if (open < 0 || close < 0) throw CPlusSyntaxException("malformed method in struct $typeName")

        val prefix = method.text.substring(0, open)
        val nameMatch = Regex("([A-Za-z_]\\w*)\\s*$").find(prefix)
            ?: throw CPlusSyntaxException("method is missing a name in struct $typeName")
        val methodName = nameMatch.groupValues[1]
        val annotations = prefix.substring(0, nameMatch.range.first)
        val isStatic = Regex("\\b(?:static|stat)\\b").containsMatchIn(annotations)
        val returnType = annotations.replace(Regex("\\bstatic\\b"), "").trim()
        if (returnType.isEmpty()) throw CPlusSyntaxException("method $methodName is missing a return type")

        val parameterStart = open + 1
        val parameterRanges = splitParameterRanges(method.text.substring(parameterStart, close))
        val firstParameter = parameterRanges.firstOrNull()?.let {
            method.text.substring(parameterStart + it.first, parameterStart + it.last + 1)
        }
        val firstIsSelf = !isStatic && firstParameter?.let { Regex("\\bself\\b").containsMatchIn(it) } == true
        val output = MappedTextBuilder()
        val headerOrigin = method.originAt(nameMatch.range.first) ?: method.firstOrigin()
        output.appendGenerated(if (isStatic) "static " else "", headerOrigin)
        output.appendGenerated("$returnType ", headerOrigin)
        output.appendGenerated("${typeStem(typeName)}__$methodName", method.originAt(nameMatch.range.first))
        output.appendGenerated("(", method.originAt(open))

        if (firstIsSelf) {
            val first = parameterRanges.first()
            val firstText = method.text.substring(parameterStart + first.first, parameterStart + first.last + 1)
            val markers = Regex("\\b(?:pub|priv|mut|borrowed|owned|stat)\\b")
                .findAll(firstText)
                .map { it.value }
                .toList()
                .joinToString(" ")
            val selfOrigin = method.originAt(parameterStart + first.first)
            output.appendGenerated(
                listOf(markers, "$typeName *self").filter { it.isNotEmpty() }.joinToString(" "),
                selfOrigin
            )
            parameterRanges.drop(1).forEachIndexed { index, range ->
                output.appendGenerated(", ", method.originAt(parameterStart + range.first))
                appendTrimmed(output, method, parameterStart + range.first, parameterStart + range.last + 1)
            }
        } else {
            output.append(method, parameterStart, close)
        }

        output.appendGenerated(")", method.originAt(close))
        output.append(method, close + 1, method.text.length)
        return output.build()
    }

    private fun appendTrimmed(output: MappedTextBuilder, value: MappedText, start: Int, end: Int) {
        var left = start
        var right = end
        while (left < right && value.text[left].isWhitespace()) left++
        while (right > left && value.text[right - 1].isWhitespace()) right--
        output.append(value, left, right)
    }

    private fun splitParameterRanges(parameters: String): List<IntRange> {
        if (parameters.trim().isEmpty()) return emptyList()
        val masked = SourceMasker.mask(parameters)
        val result = mutableListOf<IntRange>()
        var start = 0
        var depth = 0
        for (index in parameters.indices) {
            when (masked[index]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    result += start until index
                    start = index + 1
                }
            }
        }
        result += start until parameters.length
        return result
    }

    private fun typeStem(typeName: String): String = if (typeName.endsWith("_t")) typeName.dropLast(2) else typeName
}

private class MethodCallLowerer(private val structTypes: Set<String>) {
    private val variableTypes = mutableMapOf<String, String>()

    fun lower(source: MappedText): MappedText {
        if (structTypes.isEmpty()) return source
        collectVariableTypes(source.text)
        return lowerCalls(source)
    }

    private fun collectVariableTypes(source: String) {
        val alternatives = structTypes.joinToString("|") { Regex.escape(it) }
        val declaration = Regex("\\b($alternatives)\\s*\\**\\s*([A-Za-z_]\\w*)\\b")
        declaration.findAll(source).forEach { match ->
            variableTypes[match.groupValues[2]] = match.groupValues[1]
        }
    }

    private fun lowerCalls(source: MappedText): MappedText {
        val masked = SourceMasker.mask(source.text)
        val output = MappedTextBuilder()
        var copyFrom = 0
        var index = 0

        while (index < source.text.length) {
            if (masked[index] != '.') {
                index++
                continue
            }

            val methodStart = Delimiters.skipWhitespace(masked, index + 1)
            val methodMatch = Regex("[A-Za-z_]\\w*").find(masked, methodStart)
            if (methodMatch == null || methodMatch.range.first != methodStart) {
                index++
                continue
            }
            val open = Delimiters.skipWhitespace(masked, methodMatch.range.last + 1)
            if (open >= source.text.length || masked[open] != '(') {
                index++
                continue
            }
            val close = Delimiters.match(masked, open, '(', ')')
            if (close < 0) throw CPlusSyntaxException("unclosed method call ${methodMatch.value}")

            val leftStart = receiverStart(masked, index) ?: run {
                index++
                continue
            }
            val left = source.text.substring(leftStart, index).trim()
            val typeName = left.takeIf { it in structTypes } ?: receiverType(left)
            if (typeName == null) {
                index++
                continue
            }

            val args = lowerCalls(source.slice(open + 1, close))
            val stem = if (typeName.endsWith("_t")) typeName.dropLast(2) else typeName
            val origin = source.originAt(leftStart)
            val replacement = MappedTextBuilder()
            replacement.appendGenerated("${stem}__${methodMatch.value}(", origin)
            if (left in structTypes) {
                replacement.append(args)
            } else {
                val receiver = left.removeSurrounding("(", ")").trim()
                replacement.appendGenerated(receiver, origin)
                if (args.text.trim().isNotEmpty()) replacement.appendGenerated(", ", origin)
                replacement.append(args)
            }
            replacement.appendGenerated(")", source.originAt(close))

            output.append(source, copyFrom, leftStart)
            output.append(replacement.build())
            copyFrom = close + 1
            index = close + 1
        }

        output.append(source, copyFrom, source.text.length)
        return output.build()
    }

    private fun receiverType(left: String): String? {
        val expression = left.removeSurrounding("(", ")").trim()
        val identifier = Regex("[A-Za-z_]\\w*").findAll(expression).lastOrNull()?.value ?: return null
        return variableTypes[identifier]
    }

    private fun receiverStart(masked: String, dot: Int): Int? {
        var cursor = dot - 1
        while (cursor >= 0 && masked[cursor].isWhitespace()) cursor--
        if (cursor < 0) return null
        return if (masked[cursor] == ')') {
            Delimiters.opening(masked, cursor, '(', ')')
        } else if (masked[cursor].isIdentifierPart()) {
            while (cursor >= 0 && masked[cursor].isIdentifierPart()) cursor--
            cursor + 1
        } else {
            null
        }
    }
}

internal object SourceMasker {
    fun mask(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        var state = State.CODE
        while (index < chars.size) {
            when (state) {
                State.CODE -> when {
                    chars[index] == '/' && index + 1 < chars.size && chars[index + 1] == '/' -> {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        index += 2
                        state = State.LINE_COMMENT
                    }
                    chars[index] == '/' && index + 1 < chars.size && chars[index + 1] == '*' -> {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        index += 2
                        state = State.BLOCK_COMMENT
                    }
                    chars[index] == '"' -> {
                        chars[index] = ' '
                        index++
                        state = State.STRING
                    }
                    chars[index] == '\'' -> {
                        chars[index] = ' '
                        index++
                        state = State.CHAR
                    }
                    else -> index++
                }
                State.LINE_COMMENT -> {
                    if (chars[index] == '\n') state = State.CODE else chars[index] = ' '
                    index++
                }
                State.BLOCK_COMMENT -> {
                    if (chars[index] == '*' && index + 1 < chars.size && chars[index + 1] == '/') {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        index += 2
                        state = State.CODE
                    } else {
                        if (chars[index] != '\n') chars[index] = ' '
                        index++
                    }
                }
                State.STRING -> {
                    if (chars[index] == '\\') {
                        chars[index] = ' '
                        if (index + 1 < chars.size && chars[index + 1] != '\n') chars[index + 1] = ' '
                        index += 2
                    } else if (chars[index] == '"') {
                        chars[index] = ' '
                        index++
                        state = State.CODE
                    } else {
                        if (chars[index] != '\n') chars[index] = ' '
                        index++
                    }
                }
                State.CHAR -> {
                    if (chars[index] == '\\') {
                        chars[index] = ' '
                        if (index + 1 < chars.size && chars[index + 1] != '\n') chars[index + 1] = ' '
                        index += 2
                    } else if (chars[index] == '\'') {
                        chars[index] = ' '
                        index++
                        state = State.CODE
                    } else {
                        if (chars[index] != '\n') chars[index] = ' '
                        index++
                    }
                }
            }
        }
        return String(chars)
    }

    private enum class State { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR }
}

internal object Delimiters {
    fun match(source: String, openIndex: Int, open: Char, close: Char): Int {
        if (openIndex < 0 || openIndex >= source.length || source[openIndex] != open) return -1
        var depth = 0
        for (index in openIndex until source.length) {
            when (source[index]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    fun opening(source: String, closeIndex: Int, open: Char, close: Char): Int? {
        var depth = 0
        for (index in closeIndex downTo 0) {
            when (source[index]) {
                close -> depth++
                open -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    fun skipWhitespace(source: String, start: Int): Int {
        var index = start
        while (index < source.length && source[index].isWhitespace()) index++
        return index
    }
}

internal fun Char.isIdentifierPart(): Boolean = this == '_' || isLetterOrDigit()
