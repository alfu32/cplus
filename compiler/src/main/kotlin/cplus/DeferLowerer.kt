package cplus

/** Lowers function-scoped `defer` statements while retaining the origins of moved source. */
internal class DeferLowerer {
    fun lower(input: MappedText): MappedText {
        if (!Regex("\\bdefer\\b").containsMatchIn(SourceMasker.mask(input.text))) return input

        val masked = maskDirectives(SourceMasker.mask(input.text))
        val closures = functionBodies(masked)
        val deferred = linkedMapOf<Int, MutableList<DeferredStatement>>()
        val removals = mutableListOf<IntRange>()
        var cursor = 0

        while (cursor < masked.length) {
            if (!isWordAt(masked, cursor, "defer")) {
                cursor++
                continue
            }
            val closure = closures
                .filter { cursor > it.open && cursor < it.close }
                .minByOrNull { it.close - it.open }
                ?: throw CPlusSyntaxException("defer is only valid inside a function body", originSpan(input, cursor))

            val statementStart = Delimiters.skipWhitespace(masked, cursor + "defer".length)
            if (statementStart >= masked.length) {
                throw CPlusSyntaxException("defer requires a statement or block", originSpan(input, cursor))
            }
            val statementEnd = parseStatementEnd(masked, statementStart)
            if (statementEnd <= statementStart) {
                throw CPlusSyntaxException("defer requires a complete statement", originSpan(input, cursor))
            }
            deferred.getOrPut(closure.open) { mutableListOf() } += DeferredStatement(statementStart, statementEnd)
            // Keep a valid statement in place if `defer` was the body of an if/loop.
            removals += cursor until statementEnd
            cursor = statementEnd
        }

        if (removals.isEmpty()) return input
        val insertions = closures.mapNotNull { closure ->
            val statements = deferred[closure.open] ?: return@mapNotNull null
            val indentation = lineIndent(masked, closure.open) + "    "
            val builder = MappedTextBuilder()
            statements.asReversed().forEach { statement ->
                builder.appendGenerated("\n$indentation")
                builder.append(input, statement.start, statement.end)
                if (statement.end == 0 || input.text.getOrNull(statement.end - 1) != '\n') {
                    builder.appendGenerated("\n")
                }
            }
            Insertion(closure.close, builder.build())
        }

        val events = mutableListOf<Edit>()
        removals.forEach { range ->
            val origin = input.originAt(range.first)
            events += Edit(range.first, range.last + 1, MappedText.generated(";", origin))
        }
        insertions.forEach { insertion -> events += Edit(insertion.offset, insertion.offset, insertion.text) }
        events.sortWith(compareBy<Edit> { it.start }.thenBy { it.end })

        val output = MappedTextBuilder()
        var position = 0
        events.forEach { edit ->
            if (edit.start < position) {
                throw IllegalStateException("overlapping defer edits at ${edit.start}")
            }
            output.append(input, position, edit.start)
            output.append(edit.replacement)
            position = edit.end
        }
        output.append(input, position, input.text.length)
        return output.build()
    }

    private fun functionBodies(masked: String): List<FunctionBody> {
        val result = mutableListOf<FunctionBody>()
        val braces = ArrayDeque<Int>()
        for (index in masked.indices) {
            when (masked[index]) {
                '{' -> {
                    if (isFunctionBodyOpening(masked, index)) braces.addLast(index)
                    else braces.addLast(-1)
                }
                '}' -> if (braces.isNotEmpty()) {
                    val open = braces.removeLast()
                    if (open >= 0) result += FunctionBody(open, index)
                }
            }
        }
        return result
    }

    private fun isFunctionBodyOpening(source: String, openBrace: Int): Boolean {
        var end = openBrace - 1
        while (end >= 0 && source[end].isWhitespace()) end--
        if (end < 0 || source[end] != ')') return false
        val openParen = Delimiters.opening(source, end, '(', ')') ?: return false
        var nameEnd = openParen - 1
        while (nameEnd >= 0 && source[nameEnd].isWhitespace()) nameEnd--
        var nameStart = nameEnd
        while (nameStart >= 0 && source[nameStart].isIdentifierPart()) nameStart--
        val name = source.substring(nameStart + 1, nameEnd + 1)
        if (name in CONTROL_KEYWORDS || name.isEmpty()) return false

        // A compound literal or a function-pointer initializer is not a function definition.
        var boundary = maxOf(
            source.lastIndexOf(';', startIndex = openBrace - 1),
            source.lastIndexOf('{', startIndex = openBrace - 1),
            source.lastIndexOf('}', startIndex = openBrace - 1)
        ) + 1
        val header = source.substring(boundary, openBrace)
        return '=' !in header && !header.trimStart().startsWith("return ")
    }

    private fun parseStatementEnd(source: String, rawStart: Int): Int {
        val start = Delimiters.skipWhitespace(source, rawStart)
        if (start >= source.length) return start
        if (source[start] == '{') return Delimiters.match(source, start, '{', '}').takeIf { it >= 0 }?.plus(1) ?: -1

        val keyword = listOf("if", "for", "while", "switch").firstOrNull { isWordAt(source, start, it) }
        if (keyword != null) {
            val paren = Delimiters.skipWhitespace(source, start + keyword.length)
            if (paren >= source.length || source[paren] != '(') return -1
            val conditionEnd = Delimiters.match(source, paren, '(', ')')
            if (conditionEnd < 0) return -1
            var end = parseStatementEnd(source, conditionEnd + 1)
            if (end < 0) return -1
            if (keyword == "if") {
                val elseStart = Delimiters.skipWhitespace(source, end)
                if (isWordAt(source, elseStart, "else")) {
                    end = parseStatementEnd(source, elseStart + "else".length)
                }
            }
            return end
        }
        if (isWordAt(source, start, "do")) {
            var end = parseStatementEnd(source, start + "do".length)
            if (end < 0) return -1
            val whileStart = Delimiters.skipWhitespace(source, end)
            if (!isWordAt(source, whileStart, "while")) return -1
            val paren = Delimiters.skipWhitespace(source, whileStart + "while".length)
            if (paren >= source.length || source[paren] != '(') return -1
            val conditionEnd = Delimiters.match(source, paren, '(', ')')
            if (conditionEnd < 0) return -1
            end = Delimiters.skipWhitespace(source, conditionEnd + 1)
            return if (end < source.length && source[end] == ';') end + 1 else -1
        }

        var parentheses = 0
        var brackets = 0
        var braces = 0
        for (index in start until source.length) {
            when (source[index]) {
                '(' -> parentheses++
                ')' -> if (parentheses > 0) parentheses--
                '[' -> brackets++
                ']' -> if (brackets > 0) brackets--
                '{' -> braces++
                '}' -> if (braces > 0) braces-- else return -1
                ';' -> if (parentheses == 0 && brackets == 0 && braces == 0) return index + 1
            }
        }
        return -1
    }

    private fun maskDirectives(masked: String): String {
        val chars = masked.toCharArray()
        var lineStart = 0
        while (lineStart < chars.size) {
            var lineEnd = newlineAtOrEnd(chars, lineStart)
            var first = lineStart
            while (first < lineEnd && chars[first].isWhitespace()) first++
            if (first < lineEnd && chars[first] == '#') {
                var continued: Boolean
                do {
                    val slash = (lineEnd - 1 downTo lineStart).firstOrNull { chars[it] != '\r' && chars[it].isWhitespace().not() }
                    continued = slash != null && chars[slash] == '\\'
                    for (index in lineStart until lineEnd) if (chars[index] != '\n') chars[index] = ' '
                    lineStart = if (lineEnd < chars.size) lineEnd + 1 else chars.size
                    if (continued && lineStart < chars.size) {
                        lineEnd = newlineAtOrEnd(chars, lineStart)
                    }
                } while (continued && lineStart < chars.size)
            } else {
                lineStart = if (lineEnd < chars.size) lineEnd + 1 else chars.size
            }
        }
        return String(chars)
    }

    private fun newlineAtOrEnd(source: CharArray, start: Int): Int {
        var index = start
        while (index < source.size && source[index] != '\n') index++
        return index
    }

    private fun lineIndent(source: String, offset: Int): String {
        val lineStart = source.lastIndexOf('\n', offset - 1) + 1
        return source.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
    }

    private fun originSpan(input: MappedText, offset: Int): SourceSpan? =
        input.originAt(offset)?.let { it.file.span(it.offset) }

    private fun isWordAt(source: String, offset: Int, word: String): Boolean =
        offset >= 0 && offset + word.length <= source.length && source.startsWith(word, offset) &&
            (offset == 0 || !source[offset - 1].isIdentifierPart()) &&
            (offset + word.length == source.length || !source[offset + word.length].isIdentifierPart())

    private data class FunctionBody(val open: Int, val close: Int)
    private data class DeferredStatement(val start: Int, val end: Int)
    private data class Insertion(val offset: Int, val text: MappedText)
    private data class Edit(val start: Int, val end: Int, val replacement: MappedText)

    private companion object {
        val CONTROL_KEYWORDS = setOf("if", "for", "while", "switch", "sizeof", "_Alignof", "_Generic")
    }
}
