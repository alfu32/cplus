package cplus

import java.lang.StringBuilder

/** Converts the currently supported C-plus syntax into C. */
class CPlusTranspiler {
    fun transpile(source: String): String {
        ComptimePass().resolve(source)
        val loweredStructs = StructLowerer().lower(source)
        val withCalls = MethodCallLowerer(loweredStructs.typeNames).lower(loweredStructs.source)
        return CPlusPreamble.text + withCalls
    }
}

class CPlusSyntaxException(message: String) : IllegalArgumentException(message)

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

private class ComptimePass {
    fun resolve(source: String) {
        val masked = SourceMasker.mask(source)
        val at = masked.indexOf('@')
        if (at >= 0) {
            val line = source.substring(0, at).count { it == '\n' } + 1
            val lineStart = source.lastIndexOf('\n', at - 1) + 1
            val column = at - lineStart + 1
            throw CPlusSyntaxException(
                "comptime/generic syntax beginning with '@' is reserved but not implemented " +
                    "(line $line, column $column)"
            )
        }
    }
}

private data class LoweredStructs(
    val source: String,
    val typeNames: Set<String>
)

private class StructLowerer {
    private val structStart = Regex("\\btypedef\\s+struct(?:\\s+([A-Za-z_]\\w*))?\\s*\\{")
    private val aliasAfterStruct = Regex("\\s*([A-Za-z_]\\w*)\\s*;")

    fun lower(source: String): LoweredStructs {
        val masked = SourceMasker.mask(source)
        val output = StringBuilder()
        val types = linkedSetOf<String>()
        var cursor = 0
        var search = 0

        while (true) {
            val match = structStart.find(masked, search) ?: break
            val openBrace = masked.indexOf('{', match.range.first)
            val closeBrace = Delimiters.match(masked, openBrace, '{', '}')
            if (openBrace < 0 || closeBrace < 0) break

            val alias = aliasAfterStruct.find(masked, closeBrace + 1) ?: break
            val typeName = alias.groupValues[1]
            types += typeName

            val body = source.substring(openBrace + 1, closeBrace)
            val members = StructMembers.extract(body, typeName)
            output.append(source, cursor, match.range.first)

            if (members.methods.isEmpty()) {
                output.append(source, match.range.first, alias.range.last + 1)
            } else {
                output.append("typedef struct ")
                output.append(typeName)
                output.append(" {")
                output.append(members.fields)
                output.append("\n} ")
                output.append(typeName)
                output.append(";\n\n")
                output.append(members.methods.joinToString("\n\n"))
                output.append('\n')
            }

            cursor = alias.range.last + 1
            search = cursor
        }

        output.append(source, cursor, source.length)
        return LoweredStructs(output.toString(), types)
    }
}

private data class StructMembers(
    val fields: String,
    val methods: List<String>
) {
    companion object {
        fun extract(body: String, typeName: String): StructMembers {
            val masked = SourceMasker.mask(body)
            val fields = StringBuilder()
            val methods = mutableListOf<String>()
            var segmentStart = 0
            var index = 0

            while (index < body.length) {
                when {
                    masked[index] == '(' -> {
                        val close = Delimiters.match(masked, index, '(', ')')
                        if (close >= 0) {
                            val after = Delimiters.skipWhitespace(masked, close + 1)
                            if (after < body.length && (masked[after] == '{' || masked[after] == ';')) {
                                val header = body.substring(segmentStart, close + 1).trim()
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
                                    methods += MethodLowerer.lower(body.substring(segmentStart, end), typeName)
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

            fields.append(body, segmentStart, body.length)
            return StructMembers(fields.toString(), methods)
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
    fun lower(method: String, typeName: String): String {
        val open = method.indexOf('(')
        val masked = SourceMasker.mask(method)
        val close = Delimiters.match(masked, open, '(', ')')
        if (open < 0 || close < 0) throw CPlusSyntaxException("malformed method in struct $typeName")

        val prefix = method.substring(0, open).trim()
        val params = method.substring(open + 1, close)
        val suffix = method.substring(close + 1)
        val nameMatch = Regex("([A-Za-z_]\\w*)\\s*$").find(prefix)
            ?: throw CPlusSyntaxException("method is missing a name in struct $typeName")
        val methodName = nameMatch.groupValues[1]
        val annotations = prefix.substring(0, nameMatch.range.first)
        val isStatic = Regex("\\b(?:static|stat)\\b").containsMatchIn(annotations)
        val returnType = annotations.replace(Regex("\\bstatic\\b"), "").trim()
        if (returnType.isEmpty()) throw CPlusSyntaxException("method $methodName is missing a return type")

        val parameters = splitParameters(params)
        val firstIsSelf = !isStatic && parameters.firstOrNull()?.let { Regex("\\bself\\b").containsMatchIn(it) } == true
        val renderedParameters = if (firstIsSelf) {
            val markers = Regex("\\b(?:pub|priv|mut|borrowed|owned|stat)\\b")
                .findAll(parameters.first())
                .map { it.value }
                .toList()
                .joinToString(" ")
            val selfType = listOf(markers, "$typeName *self")
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            listOf(selfType) + parameters.drop(1).map(String::trim)
        } else {
            parameters.map(String::trim)
        }

        val stem = if (typeName.endsWith("_t")) typeName.dropLast(2) else typeName
        val storage = if (isStatic) "static " else ""
        val functionName = "${stem}__${methodName}"
        val renderedSuffix = suffix
        return "$storage$returnType $functionName(${renderedParameters.joinToString(", ")})$renderedSuffix"
    }

    private fun splitParameters(parameters: String): List<String> {
        if (parameters.trim().isEmpty()) return emptyList()
        val masked = SourceMasker.mask(parameters)
        val result = mutableListOf<String>()
        var start = 0
        var depth = 0
        for (index in parameters.indices) {
            when (masked[index]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    result += parameters.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        result += parameters.substring(start).trim()
        return result
    }
}

private class MethodCallLowerer(private val structTypes: Set<String>) {
    private val variableTypes = mutableMapOf<String, String>()

    fun lower(source: String): String {
        if (structTypes.isEmpty()) return source
        collectVariableTypes(source)
        return lowerCalls(source)
    }

    private fun collectVariableTypes(source: String) {
        if (structTypes.isEmpty()) return
        val alternatives = structTypes.joinToString("|") { Regex.escape(it) }
        val declaration = Regex("\\b($alternatives)\\s*\\**\\s*([A-Za-z_]\\w*)\\b")
        declaration.findAll(source).forEach { match ->
            variableTypes[match.groupValues[2]] = match.groupValues[1]
        }
    }

    private fun lowerCalls(source: String): String {
        val masked = SourceMasker.mask(source)
        val output = StringBuilder()
        var copyFrom = 0
        var index = 0

        while (index < source.length) {
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
            if (open >= source.length || masked[open] != '(') {
                index++
                continue
            }
            val close = Delimiters.match(masked, open, '(', ')')
            if (close < 0) throw CPlusSyntaxException("unclosed method call ${methodMatch.value}")

            val leftStart = receiverStart(masked, index) ?: run {
                index++
                continue
            }
            val left = source.substring(leftStart, index).trim()
            val args = source.substring(open + 1, close)
            val methodName = methodMatch.value
            val typeName = left.takeIf { it in structTypes } ?: receiverType(left)

            if (typeName == null) {
                index++
                continue
            }

            val stem = if (typeName.endsWith("_t")) typeName.dropLast(2) else typeName
            val loweredArgs = lowerCalls(args)
            val replacement = if (left in structTypes) {
                "${stem}__${methodName}($loweredArgs)"
            } else {
                val receiver = left.removeSurrounding("(", ")").trim()
                val separator = if (loweredArgs.trim().isEmpty()) "" else ", "
                "${stem}__${methodName}($receiver$separator$loweredArgs)"
            }

            output.append(source, copyFrom, leftStart)
            output.append(replacement)
            copyFrom = close + 1
            index = close + 1
        }

        output.append(source, copyFrom, source.length)
        return output.toString()
    }

    private fun receiverType(left: String): String? {
        val expression = left.removeSurrounding("(").trim()
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

private object SourceMasker {
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

private object Delimiters {
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

private fun Char.isIdentifierStart(): Boolean = this == '_' || isLetter()

private fun Char.isIdentifierPart(): Boolean = this == '_' || isLetterOrDigit()
