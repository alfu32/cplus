package cplus

internal data class ErrorFunction(
    val name: String,
    val convention: Convention,
    val parameterCount: Int,
    val errorParameter: String?,
    val declarationSpan: SourceSpan
) {
    enum class Convention { RETURN, OUT_PARAMETER }
}

internal data class ErrorAnnotationsResult(
    val source: MappedText,
    val functions: Map<String, ErrorFunction>
)

/** Collects C-plus-only @throws metadata and blanks it without disturbing source offsets. */
internal class ErrorAnnotationCollector {
    private data class StructRange(val open: Int, val close: Int, val typeName: String)
    private data class Removal(val start: Int, val end: Int)

    fun collect(input: MappedText): ErrorAnnotationsResult {
        val masked = SourceMasker.mask(input.text)
        val annotation = Regex("@throws\\b")
        val functions = linkedMapOf<String, ErrorFunction>()
        val removals = mutableListOf<Removal>()
        val structs = structRanges(masked)

        annotation.findAll(masked).forEach { marker ->
            val open = Delimiters.skipWhitespace(masked, marker.range.last + 1)
            if (open >= masked.length || masked[open] != '(') {
                fail("@throws requires () or a named error-out parameter", input, marker.range.first)
            }
            val close = Delimiters.match(masked, open, '(', ')')
            if (close < 0) fail("unclosed @throws annotation", input, marker.range.first)
            val mode = input.text.substring(open + 1, close).trim()
            if (mode.isNotEmpty() && !mode.matches(Regex("[A-Za-z_]\\w*"))) {
                fail("@throws accepts either no argument or one error parameter name", input, marker.range.first)
            }

            var functionOpen = Delimiters.skipWhitespace(masked, close + 1)
            while (functionOpen < masked.length && masked[functionOpen] == '\n') {
                functionOpen = Delimiters.skipWhitespace(masked, functionOpen)
            }
            val paren = masked.indexOf('(', functionOpen)
            if (paren < 0) fail("@throws must annotate a function declaration", input, marker.range.first)
            val header = masked.substring(functionOpen, paren)
            if (';' in header || '{' in header || '}' in header) {
                fail("@throws must annotate a function declaration", input, marker.range.first)
            }
            val nameMatch = Regex("([A-Za-z_]\\w*)\\s*$").find(header)
                ?: fail("@throws declaration is missing a function name", input, marker.range.first)
            val functionName = nameMatch.groupValues[1]
            val paramsClose = Delimiters.match(masked, paren, '(', ')')
            if (paramsClose < 0) fail("unclosed parameter list for @throws function $functionName", input, marker.range.first)
            val parameterRanges = splitTopLevel(input.text.substring(paren + 1, paramsClose))
            val parameters = if (parameterRanges.size == 1 && parameterRanges.single().trim() == "void") emptyList() else parameterRanges
            val returnType = input.text.substring(functionOpen, paren - (header.length - nameMatch.range.first)).trim()
                .replace(Regex("\\b(?:pub|priv|static|extern|inline)\\b"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            val (convention, errorParameter) = if (mode.isEmpty()) {
                if (returnType != "error_t") fail("@throws() function $functionName must return error_t", input, marker.range.first)
                ErrorFunction.Convention.RETURN to null
            } else {
                val matching = parameters.withIndex().filter { (_, parameter) ->
                    Regex("\\b${Regex.escape(mode)}\\b").containsMatchIn(SourceMasker.mask(parameter))
                }
                if (matching.size != 1 || matching.single().index != parameters.lastIndex) {
                    fail("@throws($mode) requires '$mode' exactly once as the final parameter", input, marker.range.first)
                }
                val errorDecl = SourceMasker.mask(matching.single().value)
                    .replace(Regex("\\b(?:borrowed|owned|mut|const|volatile|register|restrict)\\b"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                val originalErrorDecl = SourceMasker.mask(matching.single().value)
                if (Regex("\\bconst\\b").containsMatchIn(originalErrorDecl) ||
                    !Regex("\\berror_t\\s*\\*+\\s*${Regex.escape(mode)}\\s*$").matches(errorDecl)) {
                    fail("@throws($mode) parameter must be a writable error_t pointer", input, marker.range.first)
                }
                ErrorFunction.Convention.OUT_PARAMETER to mode
            }

            val struct = structs.filter { marker.range.first in (it.open + 1) until it.close }.maxByOrNull { it.open }
            val cName = struct?.let { "${typeStem(it.typeName)}__$functionName" } ?: functionName
            val origin = input.originAt(marker.range.first)
            val span = origin?.file?.span(origin.offset) ?: SourceFile(input.text).span(marker.range.first)
            val declaration = ErrorFunction(cName, convention, parameters.size, errorParameter, span)
            val existing = functions[cName]
            if (existing != null && (existing.convention != declaration.convention ||
                    existing.parameterCount != declaration.parameterCount)) {
                fail("conflicting @throws declarations for $cName", input, marker.range.first)
            }
            functions[cName] = declaration
            removals += Removal(marker.range.first, close + 1)
        }

        if (removals.isEmpty()) return ErrorAnnotationsResult(input, functions)
        val output = MappedTextBuilder()
        var cursor = 0
        removals.forEach { removal ->
            output.append(input, cursor, removal.start)
            for (index in removal.start until removal.end) {
                val character = if (input.text[index] == '\n' || input.text[index] == '\r') input.text[index] else ' '
                output.appendGenerated(character.toString(), input.originAt(index))
            }
            cursor = removal.end
        }
        output.append(input, cursor, input.text.length)
        return ErrorAnnotationsResult(output.build(), functions)
    }

    private fun structRanges(masked: String): List<StructRange> {
        val result = mutableListOf<StructRange>()
        val starts = Regex("\\btypedef\\s+struct(?:\\s+([A-Za-z_]\\w*))?\\s*\\{")
        starts.findAll(masked).forEach { match ->
            val open = masked.indexOf('{', match.range.first)
            val close = Delimiters.match(masked, open, '{', '}')
            if (close < 0) return@forEach
            val alias = Regex("\\s*([A-Za-z_]\\w*)\\s*;").find(masked, close + 1) ?: return@forEach
            result += StructRange(open, close, alias.groupValues[1])
        }
        return result
    }

    private fun splitTopLevel(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val masked = SourceMasker.mask(text)
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        masked.forEachIndexed { index, ch ->
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    result += text.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        result += text.substring(start).trim()
        return result
    }

    private fun typeStem(type: String): String = if (type.endsWith("_t")) type.dropLast(2) else type

    private fun fail(message: String, source: MappedText, offset: Int): Nothing {
        val origin = source.originAt(offset)
        throw CPlusSyntaxException(message, origin?.file?.span(origin.offset))
    }
}

/** Lowers the deliberately small, statement-oriented @try/@catch subset into C control flow. */
internal class TryCatchLowerer(private val functions: Map<String, ErrorFunction>) {
    private data class Handler(val status: String, val catchLabel: String)
    private data class Catch(
        val codes: List<String>?,
        val type: String,
        val name: String,
        val bodyStart: Int,
        val bodyEnd: Int
    )
    private data class ParsedTry(val start: Int, val end: Int, val bodyStart: Int, val bodyEnd: Int, val catches: List<Catch>)
    private data class Edit(val start: Int, val end: Int, val replacement: MappedText)

    private var serial = 0
    private lateinit var usedNames: MutableSet<String>

    fun lower(input: MappedText): MappedText {
        val masked = SourceMasker.mask(input.text)
        if (functions.isEmpty() && !Regex("@(?:try|catch)\\b").containsMatchIn(masked)) return input
        usedNames = Regex("[A-Za-z_]\\w*").findAll(SourceMasker.mask(input.text)).map { it.value }.toMutableSet()
        val functionBodies = functionBodyRanges(masked)
        Regex("@try\\b").findAll(masked).forEach { marker ->
            if (functionBodies.none { marker.range.first in it }) {
                fail("@try is only valid inside a function or method", input, marker.range.first)
            }
        }
        return lowerRegion(input, null)
    }

    private fun functionBodyRanges(masked: String): List<IntRange> {
        val result = mutableListOf<IntRange>()
        val braces = ArrayDeque<Int>()
        for (index in masked.indices) {
            when (masked[index]) {
                '{' -> braces.addLast(if (isFunctionBodyOpening(masked, index)) index else -1)
                '}' -> if (braces.isNotEmpty()) {
                    val open = braces.removeLast()
                    if (open >= 0) result += open..index
                }
            }
        }
        return result
    }

    private fun isFunctionBodyOpening(source: String, brace: Int): Boolean {
        var end = brace - 1
        while (end >= 0 && source[end].isWhitespace()) end--
        if (end < 0 || source[end] != ')') return false
        val open = Delimiters.opening(source, end, '(', ')') ?: return false
        var nameEnd = open - 1
        while (nameEnd >= 0 && source[nameEnd].isWhitespace()) nameEnd--
        var nameStart = nameEnd
        while (nameStart >= 0 && source[nameStart].isIdentifierPart()) nameStart--
        val name = source.substring(nameStart + 1, nameEnd + 1)
        if (name.isEmpty() || name in setOf("if", "for", "while", "switch", "catch")) return false
        val boundary = maxOf(
            source.lastIndexOf(';', startIndex = brace - 1),
            source.lastIndexOf('{', startIndex = brace - 1),
            source.lastIndexOf('}', startIndex = brace - 1)
        ) + 1
        val header = source.substring(boundary, brace)
        return '=' !in header && !header.trimStart().startsWith("return ")
    }

    private fun lowerRegion(input: MappedText, parent: Handler?): MappedText {
        val masked = SourceMasker.mask(input.text)
        val output = MappedTextBuilder()
        var cursor = 0
        var search = 0
        while (true) {
            val marker = Regex("@try\\b").find(masked, search) ?: break
            appendPlainRegion(output, input, cursor, marker.range.first, parent)
            val construct = parseTry(input, masked, marker.range.first, parent == null)
            val status = fresh("cplus_status")
            val catchLabel = fresh("cplus_catch")
            val endLabel = fresh("cplus_end")
            val body = lowerRegion(input.slice(construct.bodyStart, construct.bodyEnd), Handler(status, catchLabel))
            val catchBodies = construct.catches.map { catch ->
                lowerRegion(input.slice(catch.bodyStart, catch.bodyEnd), parent)
            }
            output.append(emitTry(input, construct, body, catchBodies, status, catchLabel, endLabel, parent))
            cursor = construct.end
            search = cursor
        }
        appendPlainRegion(output, input, cursor, input.text.length, parent)
        return output.build()
    }

    private fun appendPlainRegion(
        output: MappedTextBuilder,
        source: MappedText,
        start: Int,
        end: Int,
        handler: Handler?
    ) {
        val region = source.slice(start, end)
        val orphan = Regex("@catch\\b").find(SourceMasker.mask(region.text))
        if (orphan != null) fail("@catch has no matching @try", region, orphan.range.first)
        output.append(lowerCalls(region, handler))
    }

    private fun parseTry(input: MappedText, masked: String, start: Int, isOutermost: Boolean): ParsedTry {
        val open = Delimiters.skipWhitespace(masked, start + "@try".length)
        if (open >= masked.length || masked[open] != '{') fail("@try requires a braced body", input, start)
        val close = Delimiters.match(masked, open, '{', '}')
        if (close < 0) fail("unclosed @try body", input, start)
        val catches = mutableListOf<Catch>()
        var cursor = Delimiters.skipWhitespace(masked, close + 1)
        while (cursor < masked.length && masked.startsWith("@catch", cursor) && isBoundary(masked, cursor + 6)) {
            val paren = Delimiters.skipWhitespace(masked, cursor + 6)
            if (paren >= masked.length || masked[paren] != '(') fail("@catch requires a parenthesized pattern", input, cursor)
            val parenClose = Delimiters.match(masked, paren, '(', ')')
            if (parenClose < 0) fail("unclosed @catch pattern", input, cursor)
            val brace = Delimiters.skipWhitespace(masked, parenClose + 1)
            if (brace >= masked.length || masked[brace] != '{') fail("@catch requires a braced body", input, cursor)
            val bodyClose = Delimiters.match(masked, brace, '{', '}')
            if (bodyClose < 0) fail("unclosed @catch body", input, cursor)
            catches += parseCatch(input, paren + 1, parenClose, brace + 1, bodyClose)
            cursor = Delimiters.skipWhitespace(masked, bodyClose + 1)
        }
        if (catches.isEmpty()) fail("@try requires at least one @catch", input, start)
        if (isOutermost && catches.last().codes != null) fail("the outermost @try must end with a catch-all", input, catches.last().bodyStart)
        if (catches.dropLast(1).any { it.codes == null }) fail("catch-all @catch must be last", input, start)
        return ParsedTry(start, cursor, open + 1, close, catches)
    }

    private fun parseCatch(input: MappedText, headerStart: Int, headerEnd: Int, bodyStart: Int, bodyEnd: Int): Catch {
        val header = input.text.substring(headerStart, headerEnd).trim()
        val parts = splitTopLevel(header)
        val binding = parts.lastOrNull().orEmpty().trim()
        val bindingMatch = Regex("(?:const\\s+)?error_t\\s+([A-Za-z_]\\w*)").matchEntire(binding)
            ?: fail("@catch binding must have the form 'error_t name'", input, headerStart)
        val codes = when {
            parts.size == 1 -> null
            parts.size == 2 -> parts.first().split('|').map(String::trim).also { alternatives ->
                if (alternatives.isEmpty() || alternatives.any { !it.matches(Regex("[A-Za-z_]\\w*")) }) {
                    fail("@catch alternatives must be C error-code identifiers separated by |", input, headerStart)
                }
            }
            else -> fail("@catch expects an error code list and one error_t binding", input, headerStart)
        }
        return Catch(codes, "error_t", bindingMatch.groupValues[1], bodyStart, bodyEnd)
    }

    private fun lowerCalls(
        body: MappedText,
        handler: Handler?
    ): MappedText {
        val status = handler?.status ?: return body
        val catchLabel = handler.catchLabel
        val masked = SourceMasker.mask(body.text)
        val edits = mutableListOf<Edit>()
        val seenCallStarts = mutableSetOf<Int>()

        functions.values.forEach { function ->
            val identifier = Regex("\\b${Regex.escape(function.name)}\\b")
            identifier.findAll(masked).forEach { match ->
                val open = Delimiters.skipWhitespace(masked, match.range.last + 1)
                if (open >= masked.length || masked[open] != '(') return@forEach
                val close = Delimiters.match(masked, open, '(', ')')
                if (close < 0) fail("unclosed call to @throws function ${function.name}", body, match.range.first)
                val args = splitTopLevel(body.text.substring(open + 1, close))
                if (function.convention == ErrorFunction.Convention.OUT_PARAMETER &&
                    args.size == function.parameterCount && function.parameterCount > 0) return@forEach
                if (!seenCallStarts.add(match.range.first)) return@forEach

                val statementStart = previousStatementBoundary(masked, match.range.first) + 1
                val statementEnd = nextStatementSemicolon(masked, close + 1)
                if (statementEnd < 0) fail("@throws call is embedded in an unsupported expression or incomplete statement", body, match.range.first)
                val prefix = body.text.substring(statementStart, match.range.first).trim()
                val betweenCallAndEnd = body.text.substring(close + 1, statementEnd - 1).trim()
                if (betweenCallAndEnd.isNotEmpty()) {
                    fail("@throws call is embedded in an unsupported expression; only complete calls or error-out assignment values are supported", body, match.range.first)
                }
                val callIsAssignmentValue = prefix.endsWith("=") &&
                    (prefix.length == 1 || prefix[prefix.lastIndex - 1] !in "=!<>" )
                if (prefix.isNotEmpty() && !(function.convention == ErrorFunction.Convention.OUT_PARAMETER && callIsAssignmentValue)) {
                    if (function.convention == ErrorFunction.Convention.RETURN && callIsAssignmentValue) {
                        // An explicitly captured error return is the manual-handling form.
                        return@forEach
                    }
                    fail("@throws call is embedded in an unsupported expression; use a complete statement or error-out assignment", body, match.range.first)
                }
                if (function.convention == ErrorFunction.Convention.OUT_PARAMETER &&
                    args.size != function.parameterCount - 1) {
                    fail("${function.name} expects ${function.parameterCount - 1} arguments here; pass the error pointer explicitly for manual handling", body, match.range.first)
                }

                val replacement = MappedTextBuilder()
                if (function.convention == ErrorFunction.Convention.RETURN) {
                    replacement.appendGenerated("$status = ", body.originAt(match.range.first))
                    replacement.append(body, statementStart, statementEnd)
                } else {
                    replacement.appendGenerated("$status = 0;\n", body.originAt(match.range.first))
                    replacement.append(body, statementStart, close)
                    replacement.appendGenerated(if (args.isEmpty()) " &$status" else ", &$status", body.originAt(match.range.first))
                    replacement.append(body, close, statementEnd)
                    replacement.appendGenerated("\nif ($status != 0) goto $catchLabel;", body.originAt(match.range.first))
                }
                if (function.convention == ErrorFunction.Convention.RETURN) {
                    replacement.appendGenerated("\nif ($status != 0) goto $catchLabel;", body.originAt(match.range.first))
                }
                edits += Edit(statementStart, statementEnd, replacement.build())
            }
        }

        if (edits.isEmpty()) return body
        edits.sortBy { it.start }
        val output = MappedTextBuilder()
        var cursor = 0
        edits.forEach { edit ->
            if (edit.start < cursor) fail("overlapping checked calls are unsupported", body, edit.start)
            output.append(body, cursor, edit.start)
            output.append(edit.replacement)
            cursor = edit.end
        }
        output.append(body, cursor, body.text.length)
        return output.build()
    }

    private fun emitTry(
        source: MappedText,
        construct: ParsedTry,
        body: MappedText,
        catchBodies: List<MappedText>,
        status: String,
        catchLabel: String,
        endLabel: String,
        parent: Handler?
    ): MappedText {
        val origin = source.originAt(construct.start)
        val output = MappedTextBuilder()
        output.appendGenerated("{\n    error_t $status = 0;\n    {", origin)
        output.append(body)
        output.appendGenerated("\n    }\n    goto $endLabel;\n$catchLabel:\n", origin)
        construct.catches.forEachIndexed { index, catch ->
            val condition = catch.codes?.joinToString(" || ") { "$status == $it" }
            output.appendGenerated(if (index == 0) "    if (${condition ?: "1"}) {\n" else "    else if (${condition ?: "1"}) {\n", origin)
            output.appendGenerated("        error_t ${catch.name} = $status;\n", origin)
            output.append(catchBodies[index])
            output.appendGenerated("\n    }\n", origin)
        }
        if (construct.catches.last().codes != null) {
            val parentHandler = parent ?: error("outermost try unexpectedly lacks a catch-all")
            output.appendGenerated(
                "    else { ${parentHandler.status} = $status; goto ${parentHandler.catchLabel}; }\n",
                origin
            )
        }
        output.appendGenerated("$endLabel:\n    ;\n}", origin)
        return output.build()
    }

    private fun previousStatementBoundary(source: String, before: Int): Int {
        var paren = 0
        var bracket = 0
        var index = before - 1
        while (index >= 0) {
            when (source[index]) {
                ')' -> paren++
                '(' -> if (paren > 0) paren--
                ']' -> bracket++
                '[' -> if (bracket > 0) bracket--
                ';', '{', '}' -> if (paren == 0 && bracket == 0) return index
            }
            index--
        }
        return -1
    }

    private fun nextStatementSemicolon(source: String, after: Int): Int {
        var paren = 0
        var bracket = 0
        var brace = 0
        for (index in after until source.length) {
            when (source[index]) {
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                '[' -> bracket++
                ']' -> if (bracket > 0) bracket--
                '{' -> brace++
                '}' -> if (brace > 0) brace-- else return -1
                ';' -> if (paren == 0 && bracket == 0 && brace == 0) return index + 1
            }
        }
        return -1
    }

    private fun splitTopLevel(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val masked = SourceMasker.mask(text)
        val values = mutableListOf<String>()
        var depth = 0
        var start = 0
        masked.forEachIndexed { index, character ->
            when (character) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    values += text.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        values += text.substring(start).trim()
        return values
    }

    private fun fresh(prefix: String): String {
        while (true) {
            val candidate = "${prefix}_${serial++}"
            if (usedNames.add(candidate)) return candidate
        }
    }

    private fun isBoundary(source: String, index: Int): Boolean =
        index >= source.length || !source[index].isLetterOrDigit() && source[index] != '_'

    private fun fail(message: String, source: MappedText, offset: Int): Nothing {
        val origin = source.originAt(offset)
        throw CPlusSyntaxException(message, origin?.file?.span(origin.offset))
    }
}
