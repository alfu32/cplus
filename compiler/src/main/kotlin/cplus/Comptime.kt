package cplus

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

data class CPlusImportPaths(
    val standardLibraryRoots: List<Path> = emptyList(),
    val moduleRoots: List<Path> = emptyList()
)

private val primitiveSizes = mapOf(
    "char" to 1L,
    "short" to 2L,
    "int" to 4L,
    "long" to 8L,
    "float" to 4L,
    "double" to 8L
)
private val primitiveTypes = primitiveSizes.keys + setOf("void", "bool", "size_t")

/**
 * The comptime front-end. It deliberately produces mapped C-plus text rather than C so
 * the established method and source-mapping passes remain the single lowering path.
 */
internal class ComptimeCompiler(
    private val root: SourceFile,
    private val logger: CompilationLogger,
    private val importPaths: CPlusImportPaths = CPlusImportPaths(),
    private val targetOs: String = CPlusTarget.hostOs(),
    private val sourceManager: SourceManager = SourceManager()
) {
    private companion object {
        const val MAX_IMPORT_MODULES = 256
        const val MAX_COMPTIME_CALLS = 10_000
        const val MAX_COMPTIME_PASSES = 128
        const val MAX_GENERATED_BYTES = 8 * 1024 * 1024
        val mergingOperators = setOf(
            "++", "--", "->", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||",
            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "##", ".."
        )
    }

    private val modules = linkedMapOf<Path, ComptimeModuleResult>()
    private val importGraph = SourceImportGraph()
    private val tests = mutableListOf<ComptimeTestBlock>()
    private var comptimeCalls = 0

    fun compile(resolveTestBodies: Boolean = false): ComptimeCompilation {
        val result = logger.pass("comptime-parse-import-evaluate") {
            compileModule(root, ArrayDeque())
        }
        val runtime = logger.pass("comptime-materialize") {
            val output = MappedTextBuilder()
            result.emitInto(output, linkedSetOf())
            val materialized = output.build()
            if (materialized.text.toByteArray(Charsets.UTF_8).size > MAX_GENERATED_BYTES) {
                throw syntax("comptime generated output exceeds $MAX_GENERATED_BYTES bytes", root, 0)
            }
            materialized
        }
        val resolvedTests = tests.map { test ->
            if (!resolveTestBodies) test else test.copy(
                body = resolveRuntimeReferences(
                    test.body,
                    result.environment,
                    test.bodyStart,
                    test.source,
                    deferUnknown = false
                )
            )
        }
        return ComptimeCompilation(
            runtime,
            resolvedTests,
            result.compilerOptions,
            importGraph.edges(),
            importGraph.dependencyOrder(listOf(sourceIdFor(root)))
        )
    }

    private fun compileModule(source: SourceFile, stack: ArrayDeque<Path>): ComptimeModuleResult {
        val key = source.name?.let { Paths.get(it).toAbsolutePath().normalize() }
        if (key != null) {
            modules[key]?.let { return it }
            if (modules.size >= MAX_IMPORT_MODULES) {
                throw syntax("comptime import limit exceeded", source, 0)
            }
            if (key in stack) {
                val chain = (stack + key).joinToString(" -> ")
                throw syntax("comptime import cycle: $chain", source, 0)
            }
            stack.addLast(key)
        }
        try {
            val environment = ComptimeEnvironment().apply {
                values["os"] = CtString(CPlusTarget.normalizeOs(targetOs))
            }
            val importedModules = linkedMapOf<String, ComptimeModuleResult>()
            val compilerOptions = mutableListOf<String>()
            val seenSources = mutableSetOf<String>()
            var mapped = MappedText.identity(source)

            for (passIndex in 0 until MAX_COMPTIME_PASSES) {
                val passSource = SourceFile(mapped.text, source.name, mapped)
                val fingerprint = fingerprint(mapped.text)
                val firstComptimeToken = firstComptimeToken(passSource)
                if (!seenSources.add(fingerprint)) {
                    throw syntax(
                        "comptime expansion repeated a previous source state",
                        passSource,
                        firstComptimeToken ?: 0
                    )
                }

                val parsed = logger.pass("comptime-pass-${passIndex + 1}-parse") {
                    ComptimeParser(passSource).parse()
                }
                parsed.items.filterIsInstance<ComptimeTest>().forEach { item ->
                    tests += ComptimeTestBlock(
                        item.name,
                        parsed.mapped.slice(item.bodyStart, item.bodyEnd),
                        item.source,
                        item.bodyStart
                    )
                }
                parsed.items.filterIsInstance<ComptimeFlags>().forEach { item ->
                    compilerOptions += item.options
                }
                environment.registerRuntimeTypes(passSource)
                parsed.items.filterIsInstance<ComptimeImport>().forEach { item ->
                    val importedSource = loadImport(passSource, item)
                    val importerId = sourceIdFor(passSource)
                    val importedId = sourceIdFor(importedSource)
                    val cycle = importGraph.add(
                        SourceImportEdge(importerId, importedId, passSource.span(item.start, item.end))
                    )
                    if (cycle != null) {
                        val cycleRootEdge = importGraph.importsOf(sourceIdFor(root))
                            .firstOrNull { it.imported in cycle }
                        throw CPlusSyntaxException(
                            "comptime import cycle: ${cycle.joinToString(" -> ") { it.value }}",
                            cycleRootEdge?.location ?: passSource.span(item.start)
                        )
                    }
                    val imported = compileModule(importedSource, stack)
                    if (imported.identity !in importedModules) {
                        importedModules[imported.identity] = imported
                        environment.merge(imported.environment, passSource, item.start)
                    }
                }

                parsed.items.forEach(environment::register)

                val next = logger.pass("comptime-pass-${passIndex + 1}-expand") {
                    materializeModule(parsed, environment, deferUnknown = parsed.items.isNotEmpty())
                }
                if (next.text.toByteArray(Charsets.UTF_8).size > MAX_GENERATED_BYTES) {
                    throw syntax(
                        "comptime generated output exceeds $MAX_GENERATED_BYTES bytes",
                        passSource,
                        parsed.items.firstOrNull()?.start ?: 0
                    )
                }
                if (fingerprint(next.text) == fingerprint(mapped.text)) {
                    if (parsed.items.isEmpty()) {
                        val finalSource = SourceFile(next.text, source.name, next)
                        val unresolved = firstComptimeToken(finalSource)
                        if (unresolved != null) {
                            throw syntax(
                                "unresolved comptime syntax remains after expansion",
                                finalSource,
                                unresolved
                            )
                        }
                        val result = ComptimeModuleResult(
                            environment,
                            next,
                            key?.toString() ?: "<input:${source.name}>",
                            importedModules.values.toList(),
                            CompilerOptions.distinct(compilerOptions)
                        )
                        if (key != null) modules[key] = result
                        return result
                    }
                    // Retry deferred names strictly once expansion reaches a fixed point.
                    materializeModule(parsed, environment, deferUnknown = false)
                    throw syntax("comptime expansion made no progress", passSource, parsed.items.first().start)
                }
                mapped = next
            }

            val passSource = SourceFile(mapped.text, source.name, mapped)
            throw syntax(
                "comptime expansion exceeded the $MAX_COMPTIME_PASSES pass limit",
                passSource,
                firstComptimeToken(passSource) ?: 0
            )
        } finally {
            if (key != null) stack.removeLast()
        }
    }

    private fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(normalizeForFingerprint(text).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun normalizeForFingerprint(text: String): String {
        val normalized = StringBuilder(text.length)
        var index = 0
        var pendingSpace = false
        while (index < text.length) {
            val character = text[index]
            when {
                character.isWhitespace() -> {
                    pendingSpace = true
                    index++
                }
                character == '/' && text.getOrNull(index + 1) == '/' -> {
                    index += 2
                    while (index < text.length && text[index] != '\n') index++
                    pendingSpace = true
                }
                character == '/' && text.getOrNull(index + 1) == '*' -> {
                    index += 2
                    while (index + 1 < text.length && !(text[index] == '*' && text[index + 1] == '/')) index++
                    index = (index + 2).coerceAtMost(text.length)
                    pendingSpace = true
                }
                character == '"' || character == '\'' -> {
                    index = appendQuotedToken(text, index, character, normalized, pendingSpace)
                    pendingSpace = false
                }
                else -> {
                    val previous = normalized.lastOrNull()
                    if (pendingSpace && previous != null && needsTokenSeparator(previous, character)) {
                        normalized.append(' ')
                    }
                    normalized.append(character)
                    pendingSpace = false
                    index++
                }
            }
        }
        return normalized.toString()
    }

    private fun appendQuotedToken(
        source: String,
        start: Int,
        quote: Char,
        output: StringBuilder,
        pendingSpace: Boolean
    ): Int {
        var index = start
        val previous = output.lastOrNull()
        if (pendingSpace && previous != null && needsTokenSeparator(previous, quote)) output.append(' ')
        output.append(quote)
        index++
        while (index < source.length) {
            val character = source[index++]
            output.append(character)
            if (character == '\\' && index < source.length) {
                output.append(source[index++])
            } else if (character == quote) {
                break
            }
        }
        return index
    }

    private fun needsTokenSeparator(left: Char, right: Char): Boolean {
        if ((left.isLetterOrDigit() || left == '_') && (right.isLetterOrDigit() || right == '_')) return true
        return "$left$right" in mergingOperators ||
            (right == '"' || right == '\'') && (left.isLetterOrDigit() || left == '_')
    }

    private fun firstComptimeToken(source: SourceFile): Int? {
        val masked = SourceMasker.mask(source.text)
        var index = 0
        while (index < masked.length) {
            if (isKeywordAt(masked, index, "comptime")) return index
            if (masked[index] == '@' && masked.getOrNull(index + 1)?.isIdentifierStart() == true) {
                val end = identifierEnd(masked, index + 1)
                val name = masked.substring(index + 1, end)
                if (name !in setOf("assert", "assertEquals", "throws", "try", "catch")) return index
                index = end
            } else {
                index++
            }
        }
        return null
    }

    private fun loadImport(source: SourceFile, item: ComptimeImport): SourceFile {
        val path = resolveImportPath(source, item.start, item.path, listOf("cp", "c+"))
        if (path.extension() !in setOf("cp", "c+")) {
            throw syntax("C-plus imports must use .cp or .c+: $path", source, item.start)
        }
        return try {
            sourceManager.load(path).sourceFile
        } catch (error: Exception) {
            throw syntax("cannot read imported C-plus file $path: ${error.message}", source, item.start)
        }
    }

    private fun sourceIdFor(source: SourceFile): SourceId {
        val name = source.name ?: return SourceId.named("<anonymous-source>")
        return try {
            SourceId.fromPath(Paths.get(name))
        } catch (_: Exception) {
            SourceId.named(name)
        }
    }

    private fun materializeCImport(item: ComptimeCImport): MappedText {
        val path = resolveImportPath(item.source, item.start, item.path, listOf("c")).toAbsolutePath().normalize()
        if (path.extension() != "c") {
            throw syntax("C source imports must use a .c file: $path", item.source, item.start)
        }
        val includePath = path.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        return MappedText.generated("#include \"$includePath\"\n", SourceOrigin(item.source, item.start))
    }

    private fun resolveImportPath(
        source: SourceFile,
        offset: Int,
        requestedPath: String,
        extensionlessCandidates: List<String>
    ): Path {
        val (roots, relativePath, confined) = when {
            requestedPath.startsWith("stdlib:/") -> Triple(importPaths.standardLibraryRoots, requestedPath.removePrefix("stdlib:/"), true)
            requestedPath.startsWith("module:/") -> Triple(importPaths.moduleRoots, requestedPath.removePrefix("module:/"), true)
            requestedPath.startsWith("project:/") -> Triple(importPaths.moduleRoots, requestedPath.removePrefix("project:/"), true)
            else -> {
                val sourceName = source.name
                    ?: throw syntax("import requires a named source file", source, offset)
                val base = Paths.get(sourceName).toAbsolutePath().normalize().parent
                    ?: throw syntax("cannot determine the directory of $sourceName", source, offset)
                Triple(listOf(base), requestedPath, false)
            }
        }
        if (roots.isEmpty()) {
            val namespace = requestedPath.substringBefore(":/")
            throw syntax("no $namespace search path is configured for import '$requestedPath'", source, offset)
        }

        val requested = try {
            Paths.get(relativePath.replace('/', java.io.File.separatorChar))
        } catch (error: Exception) {
            throw syntax("invalid import path '$requestedPath': ${error.message}", source, offset)
        }
        val extension = requestedPath.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "")
        val candidates = if (extension.isNotEmpty()) {
            listOf(requested)
        } else {
            extensionlessCandidates.map { suffix -> requested.resolveSibling(requested.fileName.toString() + ".$suffix") }
        }
        for (rootPath in roots) {
            val normalizedRoot = rootPath.toAbsolutePath().normalize()
            for (candidate in candidates) {
                val resolved = (if (requested.isAbsolute) requested else normalizedRoot.resolve(candidate)).normalize()
                if (confined && !resolved.startsWith(normalizedRoot)) {
                    throw syntax("import path escapes its configured root: '$requestedPath'", source, offset)
                }
                if (Files.isRegularFile(resolved)) return resolved
            }
        }
        throw syntax("imported file does not exist: '$requestedPath'", source, offset)
    }

    private fun materializeModule(
        parsed: ParsedModule,
        environment: ComptimeEnvironment,
        deferUnknown: Boolean
    ): MappedText {
        val output = MappedTextBuilder()
        val input = parsed.mapped
        var cursor = 0
        parsed.items.sortedBy { it.start }.forEach { item ->
            output.append(resolveRuntimeReferences(input.slice(cursor, item.start), environment, cursor, parsed.source, deferUnknown))
            val replacement = try {
                when (item) {
                    is ComptimeImport,
                    is ComptimeFlags,
                    is ComptimeStruct,
                    is ComptimeValue,
                    is ComptimeFunction,
                    is ComptimeTypeGenerator,
                    is ComptimeTest -> MappedText.generated("")
                    is ComptimeCImport -> materializeCImport(item)
                    is ComptimeBlock -> evaluateBlock(item, environment)
                    is ComptimeInvocation -> evaluateInvocation(item, environment, item.start)
                    is ComptimeReference -> evaluateReference(item, environment, item.start)
                }
            } catch (error: CPlusSyntaxException) {
                if (!deferUnknown || !error.isUnknownComptimeLookup()) throw error
                null
            }
            if (replacement == null) output.append(input, item.start, item.end)
            else output.append(replacement)
            cursor = item.end
        }
        output.append(resolveRuntimeReferences(input.slice(cursor, input.text.length), environment, cursor, parsed.source, deferUnknown))
        return output.build()
    }

    private fun evaluateBlock(block: ComptimeBlock, environment: ComptimeEnvironment): MappedText {
        val output = MappedTextBuilder()
        val body = block.source.text.substring(block.bodyStart, block.bodyEnd)
        val masked = SourceMasker.mask(body)
        var cursor = 0
        splitTopLevelStatements(body, masked).forEach { range ->
            val rawStatement = body.substring(range.first, range.last + 1)
            val leadingWhitespace = rawStatement.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val statement = rawStatement.trim()
            if (statement.isEmpty()) return@forEach
            val statementOffset = block.bodyStart + range.first + leadingWhitespace
            if (statement.startsWith("@for ")) {
                output.append(evaluateFor(statement, block.source, statementOffset, environment))
            } else if (Regex("^@if(?:\\s|\\()").containsMatchIn(statement)) {
                output.append(evaluateIf(statement, block.source, statementOffset, environment))
            } else {
                val expression = statement.removeSuffix(";").trim()
                val value = evaluateExpression(expression, environment, block.source, statementOffset)
                if (value is CtEntity) output.append(value.text)
            }
        }
        return output.build()
    }

    private fun evaluateFor(
        statement: String,
        source: SourceFile,
        offset: Int,
        environment: ComptimeEnvironment
    ): MappedText {
        val match = Regex("@for\\s+([A-Za-z_]\\w*)\\s+in\\s+(.+?)\\s*\\{([\\s\\S]*)\\}\\s*;?").matchEntire(statement)
            ?: throw syntax("malformed comptime @for", source, offset)
        val variable = match.groupValues[1]
        val iterable = evaluateExpression(match.groupValues[2], environment, source, offset)
        val values = when (iterable) {
            is CtFields -> iterable.fields.map { CtFieldValue(it) }
            else -> throw syntax("@for expects a reflected field collection", source, offset)
        }
        val output = MappedTextBuilder()
        values.forEach { value ->
            val local = environment.with(variable, value)
            val body = match.groupValues[3]
            val expression = body.trim().removeSuffix(";").trim()
            val result = evaluateExpression(expression, local, source, offset)
            if (result is CtEntity) output.append(result.text)
        }
        return output.build()
    }

    private fun evaluateIf(
        statement: String,
        source: SourceFile,
        offset: Int,
        environment: ComptimeEnvironment
    ): MappedText {
        val branches = parseConditionalBranches(statement, source, offset)
        val selected = branches.firstOrNull { branch ->
            branch.condition == null || evaluateExpression(
                branch.condition,
                environment,
                source,
                branch.conditionOffset
            ).asBoolean()
        } ?: return MappedText.generated("")

        val selectedSource = statement.substring(selected.bodyStart, selected.bodyEnd)
        val maskedBody = SourceMasker.mask(selectedSource).trim()
        if (Regex("\\bcomptime\\s+flags\\b").containsMatchIn(maskedBody)) {
            return MappedText.identity(source).slice(offset + selected.bodyStart, offset + selected.bodyEnd)
        }

        val expression = selectedSource.trim().removeSuffix(";").trim()
        if (expression.isEmpty()) return MappedText.generated("")
        val value = evaluateExpression(expression, environment, source, offset + selected.bodyStart)
        return if (value is CtEntity) value.text else MappedText.generated("")
    }

    private fun parseConditionalBranches(
        statement: String,
        source: SourceFile,
        offset: Int
    ): List<ComptimeIfBranch> {
        val masked = SourceMasker.mask(statement)
        var cursor = skipWhitespace(masked, 0)
        if (!isKeywordAt(masked, cursor, "@if")) throw syntax("malformed comptime @if", source, offset)
        cursor += "@if".length
        val branches = mutableListOf<ComptimeIfBranch>()

        fun parseConditionAndBody(afterIf: Int): Pair<ComptimeIfBranch, Int> {
            val openParen = skipWhitespace(masked, afterIf)
            if (openParen >= masked.length || masked[openParen] != '(') {
                throw syntax("expected '(' after comptime if", source, offset + openParen)
            }
            val closeParen = Delimiters.match(masked, openParen, '(', ')')
            if (closeParen < 0) throw syntax("unclosed comptime if condition", source, offset + openParen)
            val openBrace = skipWhitespace(masked, closeParen + 1)
            if (openBrace >= masked.length || masked[openBrace] != '{') {
                throw syntax("expected '{' after comptime if condition", source, offset + openBrace)
            }
            val closeBrace = Delimiters.match(masked, openBrace, '{', '}')
            if (closeBrace < 0) throw syntax("unclosed comptime if branch", source, offset + openBrace)
            return ComptimeIfBranch(
                statement.substring(openParen + 1, closeParen),
                offset + openParen + 1,
                openBrace + 1,
                closeBrace
            ) to closeBrace + 1
        }

        val (first, firstEnd) = parseConditionAndBody(cursor)
        branches += first
        cursor = firstEnd
        while (true) {
            cursor = skipWhitespace(masked, cursor)
            if (cursor == masked.length) return branches
            if (masked[cursor] == ';') {
                cursor = skipWhitespace(masked, cursor + 1)
                if (cursor == masked.length) return branches
            }
            if (!isKeywordAt(masked, cursor, "@else")) {
                throw syntax("expected @else or end of comptime if chain", source, offset + cursor)
            }
            cursor = skipWhitespace(masked, cursor + "@else".length)
            if (isKeywordAt(masked, cursor, "if")) {
                cursor += "if".length
                val (branch, end) = parseConditionAndBody(cursor)
                branches += branch
                cursor = end
                continue
            }
            if (isKeywordAt(masked, cursor, "@if")) {
                cursor += "@if".length
                val (branch, end) = parseConditionAndBody(cursor)
                branches += branch
                cursor = end
                continue
            }
            val openBrace = cursor
            if (openBrace >= masked.length || masked[openBrace] != '{') {
                throw syntax("expected if condition or '{' after @else", source, offset + openBrace)
            }
            val closeBrace = Delimiters.match(masked, openBrace, '{', '}')
            if (closeBrace < 0) throw syntax("unclosed comptime else branch", source, offset + openBrace)
            branches += ComptimeIfBranch(null, offset + openBrace, openBrace + 1, closeBrace)
            cursor = skipWhitespace(masked, closeBrace + 1)
            if (cursor < masked.length && masked[cursor] == ';') cursor = skipWhitespace(masked, cursor + 1)
            if (cursor != masked.length) throw syntax("unexpected text after comptime else branch", source, offset + cursor)
            return branches
        }
    }

    private fun evaluateInvocation(item: ComptimeInvocation, environment: ComptimeEnvironment, offset: Int): MappedText {
        val function = environment.functions[item.name]
        val producesType = function is ComptimeTypeGenerator ||
            function is ComptimeFunction && function.resultKind == "type"
        if (producesType && (!item.typedef || item.alias == null)) {
            throw syntax("type generators must use typedef, e.g. 'comptime typedef name(args) alias;'", item.source, offset)
        }
        if (function != null && !producesType && item.typedef) {
            throw syntax("comptime typedef requires a type-producing comptime function", item.source, offset)
        }
        if (function != null && !item.typedef && item.alias != null) {
            throw syntax("a comptime invocation alias requires the typedef form", item.source, offset)
        }
        val value = evaluateCall(item.name, item.arguments, environment, item.source, offset, item.alias)
        if (value !is CtEntity) throw syntax("comptime call @${item.name} did not return a runtime entity", item.source, offset)
        return value.text
    }

    private fun evaluateReference(item: ComptimeReference, environment: ComptimeEnvironment, offset: Int): MappedText {
        val value = resolveValue(item.name, environment, item.source, offset)
        if (value !is CtEntity) throw syntax("@${item.name} is not a runtime entity", item.source, offset)
        return value.text
    }

    private fun resolveRuntimeReferences(
        input: MappedText,
        environment: ComptimeEnvironment,
        offset: Int,
        source: SourceFile,
        deferUnknown: Boolean
    ): MappedText {
        val masked = SourceMasker.mask(input.text)
        if ('@' !in masked && !masked.indices.any { isKeywordAt(masked, it, "comptime") }) return input
        val output = MappedTextBuilder()
        var cursor = 0
        var index = 0
        while (index < input.text.length) {
            if (isKeywordAt(masked, index, "comptime")) {
                val expressionStart = skipWhitespace(masked, index + "comptime".length)
                val expressionEnd = inlineExpressionEnd(masked, expressionStart)
                    ?: throw syntax("expected a scalar expression after comptime", source, offset + index)
                val expression = input.text.substring(expressionStart, expressionEnd)
                val value = try {
                    evaluateExpression(expression, environment, source, offset + expressionStart)
                } catch (error: CPlusSyntaxException) {
                    if (!deferUnknown || !error.isUnknownComptimeLookup()) throw error
                    index = expressionEnd
                    continue
                }
                if (value !is CtScalar && value !is CtTypeValue) {
                    throw syntax("inline comptime expression must produce a scalar value", source, offset + index)
                }
                output.append(input, cursor, index)
                output.appendGenerated(value.render(), input.originAt(index))
                cursor = expressionEnd
                index = cursor
                continue
            }
            if (masked[index] != '@' || index + 1 >= input.text.length || !input.text[index + 1].isIdentifierStart()) {
                index++
                continue
            }
            val nameEnd = identifierEnd(masked, index + 1)
            val name = input.text.substring(index + 1, nameEnd)
            val open = Delimiters.skipWhitespace(masked, nameEnd)
            val isCall = open < input.text.length && masked[open] == '('
            val callClose = if (isCall) Delimiters.match(masked, open, '(', ')') else -1
            if (isCall && callClose < 0) throw syntax("unclosed comptime call @$name", source, offset + index)
            val tokenEnd = if (isCall) callClose + 1 else nameEnd
            if (name in setOf("assert", "assertEquals", "throws", "try", "catch")) {
                // C-plus runtime markers are lowered after comptime materialization.
                index = nameEnd
                continue
            }
            val replacement = try {
                if (isCall) {
                    val close = callClose
                    val arguments = splitArguments(input.text.substring(open + 1, close))
                    val value = evaluateCall(name, arguments, environment, source, offset + index)
                    if (value !is CtScalar && value !is CtTypeValue) {
                        throw syntax("comptime value @$name(...) is not scalar in runtime code", source, offset + index)
                    }
                    Triple(index, close + 1, value.render())
                } else {
                    val value = resolveValue(name, environment, source, offset + index)
                    if (value !is CtScalar && value !is CtTypeValue) {
                        throw syntax("comptime entity @$name must be materialized in a comptime block", source, offset + index)
                    }
                    Triple(index, nameEnd, value.render())
                }
            } catch (error: CPlusSyntaxException) {
                if (!deferUnknown || !error.isUnknownComptimeLookup()) throw error
                index = tokenEnd
                continue
            }
            output.append(input, cursor, replacement.first)
            output.appendGenerated(replacement.third, input.originAt(index))
            cursor = replacement.second
            index = cursor
        }
        output.append(input, cursor, input.text.length)
        return output.build()
    }

    private fun inlineExpressionEnd(masked: String, start: Int): Int? {
        var expressionStart = start
        if (expressionStart < masked.length && masked[expressionStart] == '@') expressionStart++
        if (expressionStart >= masked.length || !masked[expressionStart].isIdentifierStart()) return null
        var parentheses = 0
        var brackets = 0
        var cursor = start
        var end = start
        while (cursor < masked.length) {
            when (masked[cursor]) {
                '(' -> parentheses++
                ')' -> if (parentheses == 0) return end else parentheses--
                '[' -> brackets++
                ']' -> if (brackets == 0) return end else brackets--
                ',', ';', '}' -> if (parentheses == 0 && brackets == 0) return end
            }
            if (!masked[cursor].isWhitespace()) end = cursor + 1
            cursor++
        }
        return end.takeIf { it > start }
    }

    private fun evaluateCall(
        name: String,
        arguments: List<String>,
        environment: ComptimeEnvironment,
        source: SourceFile,
        offset: Int,
        requestedAlias: String? = null
    ): CtValue {
        countComptimeCall(source, offset)
        val function = environment.functions[name]
            ?: throw syntax("unknown comptime function @$name", source, offset)
        if (arguments.size != function.parameters.size) {
            throw syntax("comptime function @$name expects ${function.parameters.size} arguments, got ${arguments.size}", source, offset)
        }
        val values = arguments.map { evaluateExpression(it, environment, source, offset) }
        val local = environment.withAll(function.parameters.map { it.name }.zip(values))
        return when (function) {
            is ComptimeFunction -> {
                val result = evaluateFunction(function, local, values, source, offset)
                if (function.resultKind == "type") {
                    val alias = requestedAlias
                        ?: throw syntax("type generator @${function.name} requires a typedef alias", source, offset)
                    materializeCodeType(result, alias, source, offset)
                } else result
            }
            is ComptimeTypeGenerator -> instantiateType(function, local, values, source, offset, requestedAlias)
        }
    }

    private fun evaluateCallValues(
        name: String,
        values: List<CtValue>,
        environment: ComptimeEnvironment,
        source: SourceFile,
        offset: Int
    ): CtValue {
        countComptimeCall(source, offset)
        val function = environment.functions[name]
            ?: throw syntax("unknown comptime function @$name", source, offset)
        if (values.size != function.parameters.size) {
            throw syntax("comptime function @$name expects ${function.parameters.size} arguments, got ${values.size}", source, offset)
        }
        val local = environment.withAll(function.parameters.map { it.name }.zip(values))
        return when (function) {
            is ComptimeFunction -> {
                if (function.resultKind == "type") {
                    throw syntax("type comptime function @${function.name} must be invoked with a typedef alias", source, offset)
                }
                evaluateFunction(function, local, values, source, offset)
            }
            is ComptimeTypeGenerator -> instantiateType(function, local, values, source, offset)
        }
    }

    private fun countComptimeCall(source: SourceFile, offset: Int) {
        comptimeCalls++
        if (comptimeCalls > MAX_COMPTIME_CALLS) {
            throw syntax("comptime evaluation call limit exceeded", source, offset)
        }
    }

    private fun evaluateExpression(
        expression: String,
        environment: ComptimeEnvironment,
        source: SourceFile,
        offset: Int
    ): CtValue = CtExpressionParser(
        expression,
        resolve = { name ->
            environment.values[name]?.let { resolveValue(name, environment, source, offset) }
                ?: environment.types[name]
                ?: if (name in primitiveTypes || name.endsWith("_t")) CtTypeValue(name)
                else throw syntax("unknown comptime name $name", source, offset)
        },
        call = { name, values -> evaluateCallValues(name, values, environment, source, offset) },
        property = { receiver, property -> reflect(receiver, property, source, offset) }
    ).parse()

    private fun reflect(receiver: CtValue, property: String, source: SourceFile, offset: Int): CtValue = when (receiver) {
        is CtTypeValue -> when (property) {
            "name" -> CtString(receiver.name)
            "size" -> CtInt(receiver.size ?: throw syntax("size is unavailable for type ${receiver.name}", source, offset))
            "align" -> CtInt(receiver.align ?: throw syntax("align is unavailable for type ${receiver.name}", source, offset))
            "fields" -> CtFields(receiver.fields)
            else -> throw syntax("unknown reflection property ${receiver.name}.$property", source, offset)
        }
        is CtFieldValue -> when (property) {
            "name" -> CtString(receiver.field.name)
            "type" -> CtTypeValue(receiver.field.type)
            else -> throw syntax("unknown field reflection property $property", source, offset)
        }
        else -> throw syntax("cannot reflect property .$property", source, offset)
    }

    private fun resolveValue(name: String, environment: ComptimeEnvironment, source: SourceFile, offset: Int): CtValue {
        val value = environment.values[name]
            ?: environment.types[name]
            ?: throw syntax("unknown comptime value @$name", source, offset)
        return when (value) {
            is CtLazyValue -> {
                if (!environment.evaluating.add(name)) {
                    throw syntax("recursive comptime value @${name}", source, offset)
                }
                try {
                    evaluateExpression(value.declaration.expression, environment, value.declaration.source, value.declaration.expressionStart)
                        .also { environment.values[name] = it }
                } finally {
                    environment.evaluating.remove(name)
                }
            }
            is CtLazyStruct -> materializeStruct(value.declaration, source, offset)
                .also { environment.values[name] = it }
            else -> value
        }
    }

    private fun evaluateFunction(
        function: ComptimeFunction,
        environment: ComptimeEnvironment,
        values: List<CtValue>,
        callSource: SourceFile,
        callOffset: Int
    ): CtValue {
        if (function.resultKind == "code" || function.resultKind == "type") {
            val masked = SourceMasker.mask(function.body)
            val returnStart = Regex("\\breturn\\s+").find(masked)?.range?.last?.plus(1)
                ?: throw syntax("comptime function @${function.name} must return an @code fragment", function.source, function.start)
            val codeMatch = Regex("@code\\s*\\{").find(masked, returnStart)
                ?: throw syntax("comptime function @${function.name} must return @code { ... }", function.source, function.start)
            val open = masked.indexOf('{', codeMatch.range.first)
            val close = Delimiters.match(masked, open, '{', '}')
            if (open < 0 || close < 0) {
                throw syntax("unclosed @code fragment returned by @${function.name}", function.source, function.start)
            }
            val semicolon = skipWhitespace(masked, close + 1)
            if (semicolon >= masked.length || masked[semicolon] != ';' || masked.substring(semicolon + 1).isNotBlank()) {
                throw syntax("@code fragment must be the single returned entity", function.source, function.start)
            }
            val fragment = MappedText.identity(function.source).slice(
                function.bodyStart + open + 1,
                function.bodyStart + close
            )
            val replacements = function.parameters.mapIndexed { index, parameter ->
                parameter.name to values[index].render()
            }.toMap()
            val substituted = substituteMapped(
                fragment,
                replacements,
                callSource,
                callOffset,
                bareIdentifiers = function.parameters.filter { it.type == "type" }.map { it.name }.toSet()
            )
            val materialized = if (function.resultKind == "type") {
                interpolateIdentifierCalls(substituted, environment, callSource, callOffset)
            } else {
                substituted
            }
            return CtEntity(materialized)
        }

        if (function.resultKind == "variable" || function.resultKind == "function") {
            val masked = SourceMasker.mask(function.body)
            val returnStart = Regex("\\breturn\\s+").find(masked)?.range?.last?.plus(1)
                ?: throw syntax("comptime function @${function.name} must return an entity", function.source, function.start)
            val fragmentStart: Int
            val fragmentEnd: Int
            if (function.resultKind == "function") {
                // `function` describes the comptime generator's result kind. The returned
                // runtime entity is already a C function definition, so no extra marker is
                // required. Keep the previous marker spellings accepted for compatibility.
                val legacyMarker = Regex("(?:function|@fn)\\s+").find(masked, returnStart)
                    ?.takeIf { it.range.first == returnStart }
                fragmentStart = legacyMarker?.range?.last?.plus(1) ?: returnStart
                val open = masked.indexOf('{', fragmentStart)
                val close = Delimiters.match(masked, open, '{', '}')
                if (open < 0 || close < 0) throw syntax("unclosed generated function from @${function.name}", function.source, function.start)
                fragmentEnd = close + 1
            } else {
                fragmentStart = returnStart
                val semicolon = SourceMasker.mask(function.body).indexOf(';', fragmentStart)
                if (semicolon < 0) throw syntax("variable entity @${function.name} must return a declaration", function.source, function.start)
                fragmentEnd = semicolon + 1
            }
            val fragment = MappedText.identity(function.source).slice(
                function.bodyStart + fragmentStart,
                function.bodyStart + fragmentEnd
            )
            val replacements = function.parameters.mapIndexed { index, parameter -> parameter.name to values[index].render() }.toMap()
            val substituted = substituteMapped(
                fragment,
                replacements,
                callSource,
                callOffset,
                bareIdentifiers = function.parameters.filter { it.type == "type" }.map { it.name }.toSet()
            )
            val materialized = if (function.resultKind == "function") {
                interpolateIdentifierCalls(substituted, environment, callSource, callOffset)
            } else {
                substituted
            }
            return CtEntity(materialized)
        }
        val match = Regex("return\\s+([\\s\\S]*?);(?:\\s*})?\\s*$").find(function.body.trim())
            ?: throw syntax("comptime function @${function.name} must return a value", function.source, function.start)
        return evaluateExpression(match.groupValues[1], environment, callSource, callOffset)
    }

    private fun materializeStruct(struct: ComptimeStruct, callSource: SourceFile, callOffset: Int): CtEntity {
        val body = MappedText.identity(struct.source).slice(struct.bodyStart, struct.bodyEnd)
        val mapped = MappedTextBuilder()
        val origin = SourceOrigin(callSource, callOffset)
        mapped.appendGenerated("typedef struct ${struct.runtimeAlias} {\n", origin)
        mapped.append(body)
        if (!body.text.endsWith("\n")) mapped.appendGenerated("\n")
        mapped.appendGenerated("} ${struct.runtimeAlias};\n", origin)
        return CtEntity(mapped.build())
    }

    private fun instantiateType(
        generator: ComptimeTypeGenerator,
        environment: ComptimeEnvironment,
        values: List<CtValue>,
        callSource: SourceFile,
        callOffset: Int,
        requestedAlias: String? = null
    ): CtValue {
        val returnMatch = Regex("return\\s+struct(?:\\s+[A-Za-z_]\\w*)?\\s*\\{([\\s\\S]*)\\}\\s*;?").find(generator.body)
            ?: throw syntax("type generator @${generator.name} must return struct { ... }", generator.source, generator.start)
        val typeArguments = values.joinToString("_") { it.typeName() }
        val generatedStem = "__${typeArguments}__${generator.name}"
        val alias = requestedAlias ?: "${generatedStem}_t"
        val bodyStart = generator.bodyStart + returnMatch.range.first + returnMatch.value.indexOf('{') + 1
        val bodyEnd = bodyStart + returnMatch.groupValues[1].length
        val body = MappedText.identity(generator.source).slice(bodyStart, bodyEnd)
        val replacements = generator.parameters.mapIndexed { index, parameter ->
            parameter.name to values[index].render()
        }.toMap()
        val substituted = substituteMapped(body, replacements, callSource, callOffset, replaceBareIdentifiers = true)
        val mapped = MappedTextBuilder()
        val origin = SourceOrigin(callSource, callOffset)
        mapped.appendGenerated("typedef struct ${generatedStem}_t {\n", origin)
        mapped.append(substituted)
        if (!substituted.text.endsWith("\n")) mapped.appendGenerated("\n")
        mapped.appendGenerated("} $alias;\n", origin)
        return CtEntity(mapped.build())
    }

    private fun materializeCodeType(
        value: CtValue,
        alias: String,
        callSource: SourceFile,
        callOffset: Int
    ): CtEntity {
        val entity = value as? CtEntity
            ?: throw syntax("type comptime function must return an @code struct fragment", callSource, callOffset)
        if (!alias.matches(Regex("[A-Za-z_]\\w*"))) {
            throw syntax("generated type alias '$alias' is not a valid C identifier", callSource, callOffset)
        }
        val masked = SourceMasker.mask(entity.text.text)
        val structStart = masked.indexOfFirst { !it.isWhitespace() }
        if (structStart < 0 || !masked.startsWith("struct", structStart) ||
            (structStart + 6 < masked.length && masked[structStart + 6].isIdentifierPart())
        ) {
            throw syntax("type comptime function must return one named struct in @code", callSource, callOffset)
        }
        val tagStart = skipWhitespace(masked, structStart + 6)
        val tagEnd = identifierEnd(masked, tagStart)
        if (tagEnd == tagStart) {
            throw syntax("type comptime function's struct result must have a tag", callSource, callOffset)
        }
        val open = skipWhitespace(masked, tagEnd)
        if (open >= masked.length || masked[open] != '{') {
            throw syntax("type comptime function must return one named struct in @code", callSource, callOffset)
        }
        val close = Delimiters.match(masked, open, '{', '}')
        if (close < 0) throw syntax("unclosed generated struct", callSource, callOffset)
        val tail = masked.substring(close + 1).trim().removeSuffix(";").trim()
        if (tail.isNotEmpty()) {
            throw syntax("type comptime function must return exactly one struct definition", callSource, callOffset)
        }

        val output = MappedTextBuilder()
        val origin = SourceOrigin(callSource, callOffset)
        output.appendGenerated("typedef ", origin)
        output.append(entity.text, structStart, close + 1)
        output.appendGenerated(" $alias;\n", origin)
        return CtEntity(output.build())
    }

    private fun interpolateIdentifierCalls(
        source: MappedText,
        environment: ComptimeEnvironment,
        callSource: SourceFile,
        callOffset: Int
    ): MappedText {
        val masked = SourceMasker.mask(source.text)
        val output = MappedTextBuilder()
        var cursor = 0
        var index = 0
        while (index < source.text.length) {
            if (masked[index] != '@' || index + 1 >= masked.length || !masked[index + 1].isIdentifierStart()) {
                index++
                continue
            }
            val nameEnd = identifierEnd(masked, index + 1)
            val open = skipWhitespace(masked, nameEnd)
            if (open >= masked.length || masked[open] != '(') {
                index = nameEnd
                continue
            }
            val close = Delimiters.match(masked, open, '(', ')')
            if (close < 0) throw syntax("unclosed comptime interpolation", callSource, callOffset)
            val touchesIdentifier = index > 0 && masked[index - 1].isIdentifierPart() ||
                close + 1 < masked.length && masked[close + 1].isIdentifierPart()
            if (!touchesIdentifier) {
                index = close + 1
                continue
            }
            val expression = source.text.substring(index, close + 1)
            val value = evaluateExpression(expression, environment, callSource, callOffset)
            val text = (value as? CtString)?.value
                ?: throw syntax("identifier interpolation must return a comptime string", callSource, callOffset)
            if (!text.matches(Regex("[A-Za-z_]\\w*"))) {
                throw syntax("identifier interpolation returned '$text', which is not a C identifier", callSource, callOffset)
            }
            output.append(source, cursor, index)
            output.appendGenerated(text, SourceOrigin(callSource, callOffset))
            cursor = close + 1
            index = cursor
        }
        output.append(source, cursor, source.text.length)
        return output.build()
    }

    private fun substituteMapped(
        source: MappedText,
        replacements: Map<String, String>,
        callSource: SourceFile,
        callOffset: Int,
        replaceBareIdentifiers: Boolean = false,
        bareIdentifiers: Set<String> = emptySet()
    ): MappedText {
        val masked = SourceMasker.mask(source.text)
        val output = MappedTextBuilder()
        var cursor = 0
        var index = 0
        while (index < source.text.length) {
            if (masked[index] == '@' && index + 1 < source.text.length && source.text[index + 1].isIdentifierStart()) {
                val end = identifierEnd(masked, index + 1)
                val name = source.text.substring(index + 1, end)
                val replacement = replacements[name]
                if (replacement != null) {
                    output.append(source, cursor, index)
                    output.appendGenerated(replacement, SourceOrigin(callSource, callOffset))
                    cursor = end
                    index = end
                    continue
                }
            } else if (masked[index].isIdentifierStart()) {
                val end = identifierEnd(masked, index)
                val name = source.text.substring(index, end)
                val replacement = replacements[name].takeIf { replaceBareIdentifiers || name in bareIdentifiers }
                if (replacement != null) {
                    output.append(source, cursor, index)
                    output.appendGenerated(replacement, SourceOrigin(callSource, callOffset))
                    cursor = end
                    index = end
                    continue
                }
            }
            index++
        }
        output.append(source, cursor, source.text.length)
        return output.build()
    }

    private fun splitTopLevelStatements(source: String, masked: String): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var start = 0
        var parens = 0
        var braces = 0
        var brackets = 0
        for (index in source.indices) {
            when (masked[index]) {
                '(' -> parens++
                ')' -> parens--
                '{' -> braces++
                '}' -> braces--
                '[' -> brackets++
                ']' -> brackets--
                ';' -> if (parens == 0 && braces == 0 && brackets == 0) {
                    result += start..index
                    start = index + 1
                }
            }
        }
        if (start < source.length) result += start until source.length
        return result
    }
}

internal data class ComptimeCompilation(
    val runtime: MappedText,
    val tests: List<ComptimeTestBlock>,
    val compilerOptions: List<String>,
    val imports: List<SourceImportEdge>,
    val sourceOrder: List<SourceId>
)

internal data class ComptimeTestBlock(
    val name: String,
    val body: MappedText,
    val source: SourceFile,
    val bodyStart: Int
)

private data class ComptimeIfBranch(
    val condition: String?,
    val conditionOffset: Int,
    val bodyStart: Int,
    val bodyEnd: Int
)

private data class ComptimeModuleResult(
    val environment: ComptimeEnvironment,
    val runtime: MappedText,
    val identity: String,
    val runtimeDependencies: List<ComptimeModuleResult>,
    val ownCompilerOptions: List<String>
) {
    val compilerOptions: List<String>
        get() {
            val result = mutableListOf<String>()
            val emitted = mutableSetOf<String>()
            fun collect(module: ComptimeModuleResult) {
                if (!emitted.add(module.identity)) return
                module.runtimeDependencies.forEach(::collect)
                result += module.ownCompilerOptions
            }
            collect(this)
            return CompilerOptions.distinct(result)
        }

    fun emitInto(output: MappedTextBuilder, emitted: MutableSet<String>) {
        if (!emitted.add(identity)) return
        runtimeDependencies.forEach { it.emitInto(output, emitted) }
        output.append(runtime)
    }

}

private sealed interface ComptimeItem {
    val source: SourceFile
    val start: Int
    val end: Int
}

private data class ComptimeImport(
    override val source: SourceFile,
    override val start: Int,
    override val end: Int,
    val path: String
) : ComptimeItem

private data class ComptimeCImport(
    override val source: SourceFile,
    override val start: Int,
    override val end: Int,
    val path: String
) : ComptimeItem

private data class ComptimeFlags(
    override val source: SourceFile,
    override val start: Int,
    override val end: Int,
    val options: List<String>
) : ComptimeItem

private data class ComptimeBlock(
    override val source: SourceFile,
    override val start: Int,
    override val end: Int,
    val bodyStart: Int,
    val bodyEnd: Int
) : ComptimeItem

private data class ComptimeTest(
    override val source: SourceFile,
    override val start: Int,
    override val end: Int,
    val name: String,
    val bodyStart: Int,
    val bodyEnd: Int
) : ComptimeItem

private data class ComptimeValue(
    override val source: SourceFile,
    override val start: Int,
    override val end: Int,
    val name: String,
    val expression: String,
    val expressionStart: Int
) : ComptimeItem

private data class ComptimeParameter(val name: String, val type: String)

private sealed interface ComptimeFunctionLike : ComptimeItem {
    val name: String
    val parameters: List<ComptimeParameter>
    val body: String
    val bodyStart: Int
}

private data class ComptimeFunction(
    override val source: SourceFile,
    override val start: Int,
    override val end: Int,
    override val name: String,
    val resultKind: String,
    override val parameters: List<ComptimeParameter>,
    override val body: String,
    override val bodyStart: Int
) : ComptimeFunctionLike

private data class ComptimeTypeGenerator(
    override val source: SourceFile,
    override val start: Int,
    override val end: Int,
    override val name: String,
    override val parameters: List<ComptimeParameter>,
    override val body: String,
    override val bodyStart: Int
) : ComptimeFunctionLike

private data class ComptimeStruct(
    override val source: SourceFile,
    override val start: Int,
    override val end: Int,
    val tag: String,
    val runtimeAlias: String,
    val bodyStart: Int,
    val bodyEnd: Int
) : ComptimeItem

private data class ComptimeInvocation(
    override val source: SourceFile,
    override val start: Int,
    override val end: Int,
    val name: String,
    val arguments: List<String>,
    val alias: String?,
    val typedef: Boolean
) : ComptimeItem

private data class ComptimeReference(
    override val source: SourceFile,
    override val start: Int,
    override val end: Int,
    val name: String
) : ComptimeItem

private data class ParsedModule(
    val source: SourceFile,
    val mapped: MappedText,
    val items: List<ComptimeItem>
)

private class ComptimeParser(private val source: SourceFile) {
    private val masked = SourceMasker.mask(source.text)

    fun parse(): ParsedModule {
        val items = mutableListOf<ComptimeItem>()
        var index = 0
        var boundary = 0
        var braces = 0
        var parens = 0
        var brackets = 0
        while (index < source.text.length) {
            if (braces == 0 && parens == 0 && brackets == 0 &&
                masked[index] == '#' && isPreprocessorDirectiveStart(index)
            ) {
                index = preprocessorDirectiveEnd(index)
                boundary = index
                continue
            }
            if (braces == 0 && parens == 0 && brackets == 0 && isKeywordAt(masked, index, "comptime")) {
                val start = itemStart(boundary, index)
                if (start == index) {
                    val item = parseComptime(start, index)
                    if (item != null) {
                        if (items.lastOrNull()?.end ?: -1 > item.start) {
                            throw syntax("overlapping comptime declarations", source, index)
                        }
                        items += item
                        index = item.end
                        boundary = index
                        continue
                    }
                }
            }
            if (braces == 0 && parens == 0 && brackets == 0 && masked[index] == '@') {
                val start = itemStart(boundary, index)
                val item = parseAt(start, index)
                if (item != null) {
                    if (items.lastOrNull()?.end ?: -1 > item.start) {
                        throw syntax("overlapping comptime declarations", source, index)
                    }
                    items += item
                    index = item.end
                    boundary = index
                    continue
                }
            }
            when (masked[index]) {
                '{' -> braces++
                '}' -> if (braces > 0) {
                    braces--
                    if (braces == 0 && parens == 0 && brackets == 0) boundary = index + 1
                }
                '(' -> parens++
                ')' -> if (parens > 0) parens--
                '[' -> brackets++
                ']' -> if (brackets > 0) brackets--
                ';' -> if (braces == 0 && parens == 0 && brackets == 0) boundary = index + 1
            }
            index++
        }
        return ParsedModule(source, MappedText.identity(source), items)
    }

    private fun isPreprocessorDirectiveStart(offset: Int): Boolean {
        val lineStart = masked.lastIndexOf('\n', offset - 1) + 1
        return (lineStart until offset).all { masked[it].isWhitespace() }
    }

    private fun preprocessorDirectiveEnd(offset: Int): Int {
        var lineStart = offset
        while (lineStart < masked.length) {
            val newline = masked.indexOf('\n', lineStart)
            if (newline < 0) return masked.length
            val continued = newline > lineStart && (
                masked[newline - 1] == '\\' ||
                    masked[newline - 1] == '\r' && newline - 1 > lineStart && masked[newline - 2] == '\\'
                )
            lineStart = newline + 1
            if (!continued) return lineStart
        }
        return lineStart
    }

    private fun parseComptime(start: Int, at: Int): ComptimeItem? {
        val afterKeyword = skipWhitespace(masked, at + "comptime".length)
        if (afterKeyword >= masked.length) throw syntax("incomplete comptime construct", source, at)
        if (masked[afterKeyword] == '{') return parseBlockAt(start, afterKeyword)
        if (isKeywordAt(masked, afterKeyword, "import")) return parseComptimeImport(start, afterKeyword)
        if (isKeywordAt(masked, afterKeyword, "flags")) return parseComptimeFlags(start, afterKeyword)

        if (isKeywordAt(masked, afterKeyword, "typedef")) {
            val nameStart = skipWhitespace(masked, afterKeyword + "typedef".length)
            return parseInvocationAt(start, nameStart, typedef = true)
                ?: throw syntax("malformed comptime typedef invocation", source, at)
        }

        val firstNameEnd = identifierEnd(masked, afterKeyword)
        if (firstNameEnd == afterKeyword) throw syntax("expected comptime declaration or invocation", source, at)
        val afterFirstName = skipWhitespace(masked, firstNameEnd)
        if (afterFirstName < masked.length && masked[afterFirstName] == '(') {
            return parseInvocationAt(start, afterKeyword, typedef = false)
                ?: throw syntax("malformed comptime invocation", source, at)
        }

        val equals = masked.indexOf('=', afterKeyword).takeIf { it >= 0 }
        val boundaryEnd = listOfNotNull(
            equals,
            masked.indexOf(';', afterKeyword).takeIf { it >= 0 },
            masked.indexOf('{', afterKeyword).takeIf { it >= 0 }
        ).minOrNull() ?: masked.length
        val sigil = masked.indexOf('@', afterKeyword).takeIf { it in afterKeyword until boundaryEnd }
        if (sigil != null) return parseFunctionOrValue(start, sigil)

        return parseComptimeValue(start, afterKeyword)
    }

    private fun parseComptimeFlags(start: Int, flagsAt: Int): ComptimeFlags {
        val argumentsStart = skipHorizontalWhitespace(source.text, flagsAt + "flags".length)
        val (arguments, end) = readFlagsDirective(argumentsStart)
        val options = tokenizeFlags(arguments, argumentsStart)
        if (options.isEmpty()) throw syntax("comptime flags requires at least one compiler argument", source, flagsAt)
        return ComptimeFlags(source, start, end, options)
    }

    private fun skipHorizontalWhitespace(text: String, from: Int): Int {
        var index = from
        while (index < text.length && text[index] in setOf(' ', '\t', '\r')) index++
        return index
    }

    /** Reads a directive through its line ending or semicolon, honoring quotes and C comments. */
    private fun readFlagsDirective(from: Int): Pair<String, Int> {
        val arguments = StringBuilder()
        var index = from
        var quote: Char? = null
        while (index < source.text.length) {
            val character = source.text[index]
            if (quote != null) {
                arguments.append(character)
                if (character == '\\' && index + 1 < source.text.length) {
                    arguments.append(source.text[index + 1])
                    index += 2
                    continue
                }
                if (character == quote) quote = null
                index++
                continue
            }
            if (character == '"' || character == '\'') {
                quote = character
                arguments.append(character)
                index++
                continue
            }
            if (character == '/' && source.text.getOrNull(index + 1) == '/') {
                val newline = source.text.indexOf('\n', index)
                return arguments.toString() to if (newline < 0) source.text.length else newline + 1
            }
            if (character == '/' && source.text.getOrNull(index + 1) == '*') {
                val close = source.text.indexOf("*/", index + 2)
                if (close < 0) throw syntax("unclosed comment in comptime flags", source, index)
                arguments.append(' ')
                index = close + 2
                continue
            }
            if (character == '\n') {
                val trimmed = arguments.toString().trimEnd()
                if (trimmed.endsWith('\\')) {
                    arguments.setLength(trimmed.length - 1)
                    arguments.append(' ')
                    index++
                    continue
                }
                return arguments.toString() to index + 1
            }
            if (character == ';') return arguments.toString() to index + 1
            arguments.append(character)
            index++
        }
        return arguments.toString() to source.text.length
    }

    private fun tokenizeFlags(arguments: String, offset: Int): List<String> {
        val options = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var tokenStarted = false
        var index = 0
        while (index < arguments.length) {
            val character = arguments[index]
            if (quote != null) {
                when {
                    character == quote -> {
                        quote = null
                        tokenStarted = true
                        index++
                    }
                    character == '\\' && index + 1 < arguments.length && arguments[index + 1] in setOf(quote, '\\') -> {
                        token.append(arguments[index + 1])
                        tokenStarted = true
                        index += 2
                    }
                    else -> {
                        token.append(character)
                        tokenStarted = true
                        index++
                    }
                }
            } else when {
                character == '"' || character == '\'' -> {
                    quote = character
                    tokenStarted = true
                    index++
                }
                character.isWhitespace() -> {
                    if (tokenStarted) {
                        options += token.toString()
                        token.setLength(0)
                        tokenStarted = false
                    }
                    index++
                }
                character == '\\' && index + 1 < arguments.length &&
                    (arguments[index + 1].isWhitespace() || arguments[index + 1] in setOf('"', '\'', '\\')) -> {
                    token.append(arguments[index + 1])
                    tokenStarted = true
                    index += 2
                }
                else -> {
                    token.append(character)
                    tokenStarted = true
                    index++
                }
            }
        }
        if (quote != null) throw syntax("unclosed quote in comptime flags", source, offset)
        if (tokenStarted) options += token.toString()
        if (options.any { option -> option.isEmpty() || option.any { it.isISOControl() } }) {
            throw syntax("comptime flags arguments must be non-empty and contain no control characters", source, offset)
        }
        return options
    }

    private fun parseComptimeImport(start: Int, importAt: Int): ComptimeImport =
        parseImportPath(start, importAt, "import", allowC = false) as ComptimeImport

    private fun parseComptimeValue(start: Int, declarationStart: Int): ComptimeValue {
        val semicolon = masked.indexOf(';', declarationStart)
        if (semicolon < 0) throw syntax("comptime value declaration requires a semicolon", source, declarationStart)
        val equals = masked.indexOf('=', declarationStart).takeIf { it in declarationStart until semicolon }
        val lhsEnd = equals ?: semicolon
        val lhs = source.text.substring(declarationStart, lhsEnd).trim()
        val match = Regex("(.+?)\\s+@?([A-Za-z_]\\w*)").matchEntire(lhs)
            ?: throw syntax("expected 'comptime type name = expression;'", source, declarationStart)
        val expression = if (equals == null) "0" else source.text.substring(equals + 1, semicolon).trim()
        val expressionStart = if (equals == null) semicolon else {
            var cursor = equals + 1
            while (cursor < semicolon && masked[cursor].isWhitespace()) cursor++
            cursor
        }
        return ComptimeValue(source, start, semicolon + 1, match.groupValues[2], expression, expressionStart)
    }

    private fun itemStart(boundary: Int, at: Int): Int {
        var start = boundary.coerceAtMost(at)
        while (start < at && masked[start].isWhitespace()) start++
        return start
    }

    private fun parseAt(start: Int, at: Int): ComptimeItem? {
        val tail = masked.substring(at)
        return when {
            tail.startsWith("@import") && keywordBoundary(tail, 7) -> parseImport(start, at)
            tail.startsWith("@test") && keywordBoundary(tail, 5) -> parseTest(start, at)
            tail.startsWith("@{") || tail.startsWith("@ {") -> parseBlock(start, at)
            tail.startsWith("@type") && keywordBoundary(tail, 5) -> parseTypeGenerator(start, at)
            isStructDeclaration(start, at) -> parseStruct(start, at)
            isComptimeInvocationStatement(start, at) -> parseInvocation(start, at)
            isComptimeDeclaration(start, at) -> parseFunctionOrValue(start, at)
            isStandaloneInvocation(start, at) -> parseInvocation(start, at)
            isStandaloneReference(start, at) -> parseReference(start, at)
            else -> null
        }
    }

    private fun parseTest(start: Int, at: Int): ComptimeTest {
        val titleStart = skipWhitespace(source.text, at + "@test".length)
        val open = masked.indexOf('{', titleStart)
        if (open < 0) throw syntax("@test requires a braced test body", source, at)
        val titleText = source.text.substring(titleStart, open).trim()
            .removeSurrounding("(", ")")
            .trim()
        val name = if (titleText.length >= 2 && titleText.first() == '"' && titleText.last() == '"') {
            decodeTestName(titleText.substring(1, titleText.length - 1))
        } else {
            titleText
        }
        if (name.isBlank()) throw syntax("@test requires a non-empty name", source, at)
        val close = Delimiters.match(masked, open, '{', '}')
        if (close < 0) throw syntax("unclosed @test body", source, open)
        val semicolon = skipWhitespace(masked, close + 1).takeIf { it < masked.length && masked[it] == ';' }
        return ComptimeTest(source, start, (semicolon ?: close) + 1, name, open + 1, close)
    }

    private fun decodeTestName(value: String): String {
        val decoded = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character != '\\' || index >= value.length) {
                decoded.append(character)
                continue
            }
            when (val escaped = value[index++]) {
                'n' -> decoded.append('\n')
                'r' -> decoded.append('\r')
                't' -> decoded.append('\t')
                else -> decoded.append(escaped)
            }
        }
        return decoded.toString()
    }

    private fun parseImport(start: Int, at: Int): ComptimeItem =
        parseImportPath(start, at, "@import", allowC = true)

    private fun parseImportPath(
        start: Int,
        keywordAt: Int,
        keyword: String,
        allowC: Boolean
    ): ComptimeItem {
        var cursor = skipWhitespace(source.text, keywordAt + keyword.length)
        val parenthesized = cursor < source.text.length && source.text[cursor] == '('
        if (parenthesized) cursor = skipWhitespace(source.text, cursor + 1)
        if (cursor >= source.text.length || source.text[cursor] != '"') {
            throw syntax("malformed $keyword; expected $keyword \"file.cp\" or $keyword(\"file.c\")", source, keywordAt)
        }

        val pathStart = ++cursor
        var escaped = false
        while (cursor < source.text.length && (source.text[cursor] != '"' || escaped)) {
            if (source.text[cursor] == '\\' && !escaped) escaped = true else escaped = false
            cursor++
        }
        if (cursor >= source.text.length) throw syntax("unclosed import path", source, pathStart - 1)
        val path = buildString {
            var index = pathStart
            while (index < cursor) {
                val character = source.text[index++]
                if (character == '\\' && index < cursor && source.text[index] in setOf('\\', '"')) {
                    append(source.text[index++])
                } else {
                    append(character)
                }
            }
        }
        cursor = skipWhitespace(source.text, cursor + 1)
        if (parenthesized) {
            if (cursor >= source.text.length || source.text[cursor] != ')') {
                throw syntax("expected ')' after import path", source, cursor.coerceAtMost(source.text.length))
            }
            cursor = skipWhitespace(source.text, cursor + 1)
        }
        if (cursor < source.text.length && source.text[cursor] == ';') cursor++

        val extension = path.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "")
        return when (extension) {
            "cp", "c+" -> ComptimeImport(source, start, cursor, path)
            "c" -> if (allowC) ComptimeCImport(source, start, cursor, path) else {
                throw syntax("comptime import accepts only .cp or .c+ files; use @import or #include for C", source, keywordAt)
            }
            "" -> if (allowC) ComptimeCImport(source, start, cursor, path) else ComptimeImport(source, start, cursor, path)
            else -> throw syntax("unsupported import extension '$extension'", source, keywordAt)
        }
    }

    private fun parseBlock(start: Int, at: Int): ComptimeBlock {
        val open = masked.indexOf('{', at)
        return parseBlockAt(start, open)
    }

    private fun parseBlockAt(start: Int, open: Int): ComptimeBlock {
        val close = Delimiters.match(masked, open, '{', '}')
        if (close < 0) throw syntax("unclosed comptime block", source, open)
        return ComptimeBlock(source, start, close + 1, open + 1, close)
    }

    private fun parseTypeGenerator(start: Int, at: Int): ComptimeTypeGenerator {
        val typeName = skipWhitespace(masked, at + 5)
        val nameStart = if (typeName < masked.length && masked[typeName] == '@') {
            typeName + 1
        } else {
            typeName
        }
        val nameEnd = identifierEnd(masked, nameStart)
        val open = skipWhitespace(masked, nameEnd)
        if (open >= masked.length || masked[open] != '(') throw syntax("@type generator requires parameters", source, at)
        val close = Delimiters.match(masked, open, '(', ')')
        if (close < 0) throw syntax("unclosed @type parameter list", source, at)
        val bodyOpen = skipWhitespace(masked, close + 1)
        val bodyClose = Delimiters.match(masked, bodyOpen, '{', '}')
        if (bodyOpen >= masked.length || masked[bodyOpen] != '{' || bodyClose < 0) {
            throw syntax("@type generator requires a body", source, at)
        }
        return ComptimeTypeGenerator(
            source,
            start,
            bodyClose + 1,
            source.text.substring(nameStart, nameEnd),
            parseTypeParameters(source.text.substring(open + 1, close), open + 1),
            source.text.substring(bodyOpen + 1, bodyClose),
            bodyOpen + 1
        )
    }

    private fun parseStruct(start: Int, at: Int): ComptimeStruct {
        val tagAt = masked.indexOf('@', start)
        val tagStart = tagAt + 1
        val tagEnd = identifierEnd(masked, tagStart)
        val open = skipWhitespace(masked, tagEnd)
        val close = Delimiters.match(masked, open, '{', '}')
        if (open >= masked.length || masked[open] != '{' || close < 0) throw syntax("unclosed comptime struct", source, at)
        val aliasMatch = Regex("\\s*([A-Za-z_]\\w*)\\s*;").find(masked, close + 1)
            ?: throw syntax("comptime struct requires a runtime alias", source, at)
        return ComptimeStruct(source, start, aliasMatch.range.last + 1, source.text.substring(tagStart, tagEnd), aliasMatch.groupValues[1], open + 1, close)
    }

    private fun parseFunctionOrValue(start: Int, at: Int): ComptimeItem? {
        val nameStart = at + 1
        val nameEnd = identifierEnd(masked, nameStart)
        if (nameEnd == nameStart) return null
        val afterName = skipWhitespace(masked, nameEnd)
        val prefix = source.text.substring(start, at).trim()
        if (prefix.contains('=') || prefix.endsWith("return")) return null
        if (afterName < masked.length && masked[afterName] == '(') {
            val close = Delimiters.match(masked, afterName, '(', ')')
            if (close < 0) throw syntax("unclosed comptime function parameter list", source, at)
            val bodyOpen = skipWhitespace(masked, close + 1)
            if (bodyOpen >= masked.length || masked[bodyOpen] != '{') return null
            val bodyClose = Delimiters.match(masked, bodyOpen, '{', '}')
            if (bodyClose < 0) throw syntax("unclosed comptime function @${source.text.substring(nameStart, nameEnd)}", source, at)
            val declaredResultKind = prefix.split(Regex("\\s+")).lastOrNull().orEmpty()
            val resultKind = when (declaredResultKind) {
                "@var" -> "variable"
                "@fn" -> "function"
                "@code" -> "code"
                else -> declaredResultKind
            }
            return ComptimeFunction(
                source,
                start,
                bodyClose + 1,
                source.text.substring(nameStart, nameEnd),
                resultKind,
                parseParameters(source.text.substring(afterName + 1, close), afterName + 1),
                source.text.substring(bodyOpen + 1, bodyClose),
                bodyOpen + 1
            ).also {
                if (resultKind.isEmpty()) throw syntax("comptime function is missing a result kind", source, at)
            }
        }
        val semicolon = masked.indexOf(';', afterName)
        if (semicolon < 0) return null
        val declaration = source.text.substring(start, semicolon + 1)
        val match = Regex("[A-Za-z_][\\w\\s\\*]*@([A-Za-z_]\\w*)\\s*(?:=\\s*(.*?))?;").matchEntire(declaration.trim())
            ?: return null
        val expression = match.groupValues[2].ifBlank { "0" }
        val expressionStart = if (match.groupValues[2].isBlank()) {
            source.text.indexOf(';', start).coerceAtLeast(start)
        } else {
            source.text.indexOf(match.groupValues[2], start).coerceAtLeast(start)
        }
        return ComptimeValue(source, start, semicolon + 1, match.groupValues[1], expression, expressionStart)
    }

    private fun parseInvocation(start: Int, at: Int): ComptimeInvocation? {
        val nameStart = at + 1
        return parseInvocationAt(
            start,
            nameStart,
            typedef = source.text.substring(start, at).trim() == "typedef"
        )
    }

    private fun parseInvocationAt(start: Int, rawNameStart: Int, typedef: Boolean): ComptimeInvocation? {
        val nameStart = if (rawNameStart < masked.length && masked[rawNameStart] == '@') rawNameStart + 1 else rawNameStart
        val nameEnd = identifierEnd(masked, nameStart)
        val open = skipWhitespace(masked, nameEnd)
        if (nameEnd == nameStart || open >= masked.length || masked[open] != '(') return null
        val close = Delimiters.match(masked, open, '(', ')')
        if (close < 0) throw syntax("unclosed comptime call", source, nameStart)
        val suffixStart = skipWhitespace(masked, close + 1)
        val aliasEnd = if (suffixStart < masked.length && masked[suffixStart] != ';') {
            identifierEnd(masked, suffixStart)
        } else suffixStart
        val end = skipWhitespace(masked, aliasEnd)
        if (end >= masked.length || masked[end] != ';') return null
        val alias = source.text.substring(suffixStart, aliasEnd).trim().takeIf { it.isNotEmpty() }
        return ComptimeInvocation(
            source,
            start,
            end + 1,
            source.text.substring(nameStart, nameEnd),
            splitArguments(source.text.substring(open + 1, close)),
            alias,
            typedef
        )
    }

    private fun parseReference(start: Int, at: Int): ComptimeReference? {
        val nameStart = at + 1
        val nameEnd = identifierEnd(masked, nameStart)
        val after = skipWhitespace(masked, nameEnd)
        if (nameEnd == nameStart || after >= masked.length || masked[after] != ';') return null
        return ComptimeReference(source, start, after + 1, source.text.substring(nameStart, nameEnd))
    }

    private fun isStructDeclaration(start: Int, at: Int): Boolean =
        Regex("typedef\\s+struct\\s+@[A-Za-z_]\\w*\\s*\\{").containsMatchIn(masked.substring(start))

    private fun isComptimeDeclaration(start: Int, at: Int): Boolean =
        source.text.substring(start, at).trim().isNotEmpty()

    /**
     * File-scope type-generator invocations use C's typedef declaration form;
     * the called comptime function determines the generated entity kind.
     */
    private fun isComptimeInvocationStatement(start: Int, at: Int): Boolean =
        source.text.substring(start, at).trim() == "typedef"

    private fun isStandaloneInvocation(start: Int, at: Int): Boolean = source.text.substring(start, at).trim().isEmpty()

    private fun isStandaloneReference(start: Int, at: Int): Boolean = source.text.substring(start, at).trim().isEmpty()

    private fun parseParameters(text: String, offset: Int): List<ComptimeParameter> =
        splitArguments(text).filter { it.isNotBlank() }.map { parameter ->
            val trimmed = parameter.trim()
            val typeParameter = Regex("(?:@type|type)\\s+([A-Za-z_]\\w*)\\s*").matchEntire(trimmed)
            if (typeParameter != null) {
                ComptimeParameter(typeParameter.groupValues[1], "type")
            } else {
                val sigiled = Regex("(.+?)@([A-Za-z_]\\w*)\\s*$").matchEntire(trimmed)
                if (sigiled != null) {
                    ComptimeParameter(sigiled.groupValues[2], sigiled.groupValues[1].trim())
                } else {
                    val ordinary = Regex("(.+?)\\s+([A-Za-z_]\\w*)\\s*$").matchEntire(trimmed)
                        ?: throw syntax("comptime parameters must be typed names", source, offset)
                    ComptimeParameter(ordinary.groupValues[2], ordinary.groupValues[1].trim())
                }
            }
        }

    private fun parseTypeParameters(text: String, offset: Int): List<ComptimeParameter> =
        splitArguments(text).filter { it.isNotBlank() }.map { parameter ->
            val match = Regex("(?:@type|type)\\s+([A-Za-z_]\\w*)\\s*").matchEntire(parameter.trim())
                ?: throw syntax("type generator parameters must use 'type T'", source, offset)
            ComptimeParameter(match.groupValues[1], "type")
        }

    private fun keywordBoundary(text: String, length: Int): Boolean =
        text.length == length || !text[length].isIdentifierPart()

}

internal data class LegacyComptimeScan(
    val nodes: List<CPlusSyntaxNode>,
    val diagnostics: List<ParserDiagnostic>
)

/** Adapts the existing comptime scanner to the backend-neutral syntax model without claiming full C parsing. */
internal fun parseLegacyComptimeSyntax(snapshot: SourceSnapshot): LegacyComptimeScan {
    val source = snapshot.sourceFile
    return try {
        val items = ComptimeParser(source).parse().items
        val nodes = items.map { item ->
            val kind = when (item) {
                is ComptimeImport -> "comptime_import"
                is ComptimeCImport -> "c_import_expression"
                is ComptimeFlags -> "comptime_flags"
                is ComptimeBlock -> "comptime_block"
                is ComptimeTest -> "test_declaration"
                is ComptimeValue -> "comptime_value_declaration"
                is ComptimeFunction -> "comptime_function_declaration"
                is ComptimeTypeGenerator -> "comptime_type_declaration"
                is ComptimeStruct -> "comptime_struct_declaration"
                is ComptimeInvocation -> "comptime_invocation"
                is ComptimeReference -> "comptime_reference"
            }
            CPlusSyntaxNode(kind, source.span(item.start, item.end))
        }
        LegacyComptimeScan(nodes, emptyList())
    } catch (error: CPlusSyntaxException) {
        val span = error.sourceSpan ?: source.span(0, 0)
        LegacyComptimeScan(
            nodes = emptyList(),
            diagnostics = listOf(ParserDiagnostic("legacy.comptime.syntax", error.message ?: "invalid comptime syntax", ParserDiagnosticSeverity.ERROR, span))
        )
    }
}

private class ComptimeEnvironment(
    val values: LinkedHashMap<String, CtValue> = linkedMapOf(),
    val functions: LinkedHashMap<String, ComptimeFunctionLike> = linkedMapOf(),
    val types: LinkedHashMap<String, CtTypeValue> = linkedMapOf()
) {
    val evaluating: MutableSet<String> = linkedSetOf()
    fun register(item: ComptimeItem) {
        when (item) {
            is ComptimeValue -> {
                if (item.name in values || item.name in functions) throw syntax("duplicate comptime name @${item.name}", item.source, item.start)
                values[item.name] = CtLazyValue(item)
            }
            is ComptimeFunction -> {
                if (item.name in values || item.name in functions) throw syntax("duplicate comptime name @${item.name}", item.source, item.start)
                functions[item.name] = item
            }
            is ComptimeTypeGenerator -> {
                if (item.name in values || item.name in functions) throw syntax("duplicate comptime name @${item.name}", item.source, item.start)
                functions[item.name] = item
            }
            is ComptimeStruct -> {
                if (item.tag in values || item.tag in functions) throw syntax("duplicate comptime name @${item.tag}", item.source, item.start)
                values[item.tag] = CtLazyStruct(item)
                types[item.runtimeAlias] = CtTypeValue(item.runtimeAlias, fields = extractFields(item.source, item.bodyStart, item.bodyEnd))
            }
            else -> Unit
        }
    }

    fun registerRuntimeTypes(source: SourceFile) {
        val masked = SourceMasker.mask(source.text)
        val pattern = Regex("\\btypedef\\s+struct(?:\\s+[A-Za-z_]\\w*)?\\s*\\{")
        var search = 0
        while (true) {
            val match = pattern.find(masked, search) ?: break
            val open = masked.indexOf('{', match.range.first)
            val close = Delimiters.match(masked, open, '{', '}')
            if (open < 0 || close < 0) break
            val alias = Regex("\\s*([A-Za-z_]\\w*)\\s*;").find(masked, close + 1)
                ?: break
            types[alias.groupValues[1]] = CtTypeValue(
                alias.groupValues[1],
                fields = extractFields(source, open + 1, close)
            )
            search = alias.range.last + 1
        }
    }

    fun merge(other: ComptimeEnvironment, source: SourceFile, offset: Int) {
        other.values.forEach { (name, value) ->
            val existing = values[name]
            if (existing != null && existing != value) throw syntax("duplicate imported comptime name @$name", source, offset)
            if (existing == null) values[name] = value
        }
        other.functions.forEach { (name, function) ->
            val existing = functions[name]
            if (existing != null && existing != function) throw syntax("duplicate imported comptime function @$name", source, offset)
            if (existing == null) functions[name] = function
        }
        other.types.forEach { (name, type) ->
            val existing = types[name]
            if (existing != null && existing != type) throw syntax("duplicate imported type $name", source, offset)
            if (existing == null) types[name] = type
        }
    }

    fun with(name: String, value: CtValue): ComptimeEnvironment = withAll(listOf(name to value))

    fun withAll(entries: List<Pair<String, CtValue>>): ComptimeEnvironment {
        val copy = ComptimeEnvironment(LinkedHashMap(values), LinkedHashMap(functions), LinkedHashMap(types))
        entries.forEach { (name, value) -> copy.values[name] = value }
        return copy
    }
}

private fun extractFields(source: SourceFile, start: Int, end: Int): List<CtField> {
    val text = source.text.substring(start, end)
    val masked = SourceMasker.mask(text)
    val fields = mutableListOf<CtField>()
    var segmentStart = 0
    var braces = 0
    text.indices.forEach { index ->
        when (masked[index]) {
            '{' -> braces++
            '}' -> if (braces > 0) braces--
            ';' -> if (braces == 0) {
                val declaration = text.substring(segmentStart, index).trim()
                val name = Regex("([A-Za-z_]\\w*)\\s*(?:\\[[^]]*])?$").find(declaration)?.groupValues?.get(1)
                if (name != null && '(' !in declaration) {
                    val type = declaration.substring(0, declaration.lastIndexOf(name))
                        .replace(Regex("\\b(pub|priv|mut|borrowed|owned|stat|static)\\b"), "")
                        .trim()
                    fields += CtField(name, type)
                }
                segmentStart = index + 1
            }
        }
    }
    return fields
}

private sealed interface CtValue {
    fun render(): String
    fun typeName(): String = render().replace(Regex("[^A-Za-z0-9_]+"), "_")
}

private interface CtScalar : CtValue

private data class CtInt(val value: Long) : CtScalar {
    override fun render(): String = value.toString()
}

private data class CtBool(val value: Boolean) : CtScalar {
    override fun render(): String = if (value) "1" else "0"
}

private data class CtString(val value: String) : CtScalar {
    override fun render(): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

private data class CtTypeValue(
    val name: String,
    val fields: List<CtField> = emptyList(),
    val size: Long? = primitiveSizes[name],
    val align: Long? = primitiveSizes[name]
) : CtValue {
    override fun render(): String = name
    override fun typeName(): String = name.replace(Regex("[^A-Za-z0-9_]+"), "_")
}

private data class CtEntity(val text: MappedText) : CtValue {
    override fun render(): String = text.text
}

private data class CtField(val name: String, val type: String)
private data class CtFields(val fields: List<CtField>) : CtValue {
    override fun render(): String = "<fields>"
}
private data class CtFieldValue(val field: CtField) : CtValue {
    override fun render(): String = field.name
}

private class CtExpressionParser(
    private val text: String,
    private val resolve: (String) -> CtValue,
    private val call: (String, List<CtValue>) -> CtValue,
    private val property: (CtValue, String) -> CtValue
) {
    private enum class Kind { IDENTIFIER, NUMBER, STRING, OPERATOR, PUNCTUATION, END }
    private data class Token(val kind: Kind, val text: String)

    private val tokens = tokenize(text)
    private var index = 0

    fun parse(): CtValue {
        val value = parseOr()
        if (peek().kind != Kind.END) fail("unexpected '${peek().text}'")
        return value
    }

    private fun parseOr(): CtValue {
        var value = parseAnd()
        while (match("||")) value = CtBool(value.asBoolean() || parseAnd().asBoolean())
        return value
    }

    private fun parseAnd(): CtValue {
        var value = parseEquality()
        while (match("&&")) value = CtBool(value.asBoolean() && parseEquality().asBoolean())
        return value
    }

    private fun parseEquality(): CtValue {
        var value = parseComparison()
        while (true) {
            value = when {
                match("==") -> CtBool(value == parseComparison())
                match("!=") -> CtBool(value != parseComparison())
                else -> return value
            }
        }
    }

    private fun parseComparison(): CtValue {
        var value = parseAdditive()
        while (true) {
            value = when {
                match("<") -> CtBool(value.asLong() < parseAdditive().asLong())
                match("<=") -> CtBool(value.asLong() <= parseAdditive().asLong())
                match(">") -> CtBool(value.asLong() > parseAdditive().asLong())
                match(">=") -> CtBool(value.asLong() >= parseAdditive().asLong())
                else -> return value
            }
        }
    }

    private fun parseAdditive(): CtValue {
        var value = parseMultiplicative()
        while (true) {
            value = when {
                match("+") -> plus(value, parseMultiplicative())
                match("-") -> CtInt(value.asLong() - parseMultiplicative().asLong())
                else -> return value
            }
        }
    }

    private fun parseMultiplicative(): CtValue {
        var value = parseUnary()
        while (true) {
            value = when {
                match("*") -> CtInt(value.asLong() * parseUnary().asLong())
                match("/") -> {
                    val divisor = parseUnary().asLong()
                    if (divisor == 0L) fail("division by zero")
                    CtInt(value.asLong() / divisor)
                }
                match("%") -> CtInt(value.asLong() % parseUnary().asLong())
                else -> return value
            }
        }
    }

    private fun parseUnary(): CtValue = when {
        match("-") -> CtInt(-parseUnary().asLong())
        match("+") -> CtInt(parseUnary().asLong())
        match("!") -> CtBool(!parseUnary().asBoolean())
        else -> parsePrimary()
    }

    private fun parsePrimary(): CtValue {
        if (match("(")) {
            val value = parseOr()
            expect(")")
            return parseProperties(value)
        }
        val at = match("@")
        val token = consume()
        val value = when (token.kind) {
            Kind.NUMBER -> CtInt(token.text.toLongOrNull() ?: fail("invalid integer ${token.text}"))
            Kind.STRING -> CtString(unescape(token.text))
            Kind.IDENTIFIER -> when (token.text) {
                "true" -> CtBool(true)
                "false" -> CtBool(false)
                else -> {
                    if (peek().text == "(") {
                        consume()
                        val arguments = mutableListOf<CtValue>()
                        if (peek().text != ")") {
                            do arguments += parseOr() while (match(","))
                        }
                        expect(")")
                        call(token.text, arguments)
                    } else {
                        resolve(token.text)
                    }
                }
            }
            else -> fail("expected comptime value, got '${token.text}'")
        }
        if (at && token.kind != Kind.IDENTIFIER) fail("@ must prefix an identifier")
        return parseProperties(value)
    }

    private fun parseProperties(initial: CtValue): CtValue {
        var value = initial
        while (match(".")) {
            val name = consume()
            if (name.kind != Kind.IDENTIFIER) fail("expected a reflection property")
            value = property(value, name.text)
        }
        return value
    }

    private fun plus(left: CtValue, right: CtValue): CtValue = when {
        left is CtString && right is CtString -> CtString(left.value + right.value)
        else -> CtInt(left.asLong() + right.asLong())
    }

    private fun CtValue.asLong(): Long = when (this) {
        is CtInt -> value
        is CtBool -> if (value) 1 else 0
        else -> fail("expected an integer comptime value")
    }

    private fun peek(): Token = tokens[index]
    private fun consume(): Token = tokens[index++]
    private fun match(value: String): Boolean = if (peek().text == value) {
        index++
        true
    } else false

    private fun expect(value: String) {
        if (!match(value)) fail("expected '$value'")
    }

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    private fun unescape(value: String): String = value
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    private fun tokenize(input: String): List<Token> {
        val result = mutableListOf<Token>()
        var cursor = 0
        while (cursor < input.length) {
            val character = input[cursor]
            when {
                character.isWhitespace() -> cursor++
                character == '@' -> {
                    result += Token(Kind.OPERATOR, "@")
                    cursor++
                }
                character.isIdentifierStart() -> {
                    val end = identifierEnd(input, cursor)
                    result += Token(Kind.IDENTIFIER, input.substring(cursor, end))
                    cursor = end
                }
                character.isDigit() -> {
                    val end = input.indexOfFirstFrom(cursor) { !it.isDigit() }
                    result += Token(Kind.NUMBER, input.substring(cursor, end))
                    cursor = end
                }
                character == '"' -> {
                    var end = cursor + 1
                    while (end < input.length) {
                        if (input[end] == '\\') end++
                        if (end < input.length && input[end] == '"') {
                            end++
                            break
                        }
                        end++
                    }
                    if (end > input.length || input.getOrNull(end - 1) != '"') fail("unterminated string")
                    result += Token(Kind.STRING, input.substring(cursor + 1, end - 1))
                    cursor = end
                }
                else -> {
                    val two = input.substring(cursor, (cursor + 2).coerceAtMost(input.length))
                    if (two in setOf("==", "!=", "<=", ">=", "&&", "||")) {
                        result += Token(Kind.OPERATOR, two)
                        cursor += 2
                    } else {
                        result += Token(
                            if (character in "()[],.") Kind.PUNCTUATION else Kind.OPERATOR,
                            character.toString()
                        )
                        cursor++
                    }
                }
            }
        }
        result += Token(Kind.END, "<end>")
        return result
    }
}

private fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
    for (index in start until length) if (predicate(this[index])) return index
    return length
}

private data class CtLazyValue(val declaration: ComptimeValue) : CtValue {
    override fun render(): String = "<lazy:${declaration.name}>"
}

private data class CtLazyStruct(val declaration: ComptimeStruct) : CtValue {
    override fun render(): String = "<struct:${declaration.tag}>"
}

private fun CtValue.asBoolean(): Boolean = when (this) {
    is CtBool -> value
    is CtInt -> value != 0L
    else -> true
}

private fun syntax(message: String, source: SourceFile, offset: Int): CPlusSyntaxException =
    CPlusSyntaxException(message, source.span(offset))

private fun isKeywordAt(text: String, offset: Int, keyword: String): Boolean =
    offset >= 0 && offset + keyword.length <= text.length &&
        text.startsWith(keyword, offset) &&
        (offset == 0 || !text[offset - 1].isIdentifierPart()) &&
        (offset + keyword.length == text.length || !text[offset + keyword.length].isIdentifierPart())

private fun CPlusSyntaxException.isUnknownComptimeLookup(): Boolean =
    message?.startsWith("unknown comptime function @") == true ||
        message?.startsWith("unknown comptime value @") == true ||
        message?.startsWith("unknown comptime name ") == true

private fun Path.extension(): String = fileName.toString().substringAfterLast('.', "")

private fun Char.isIdentifierStart(): Boolean = isLetter() || this == '_'

private fun identifierEnd(source: String, start: Int): Int {
    var index = start.coerceIn(0, source.length)
    while (index < source.length && source[index].isIdentifierPart()) index++
    return index
}

private fun skipWhitespace(source: String, start: Int): Int = Delimiters.skipWhitespace(source, start)

private fun splitArguments(text: String): List<String> {
    if (text.trim().isEmpty()) return emptyList()
    val masked = SourceMasker.mask(text)
    val result = mutableListOf<String>()
    var start = 0
    var depth = 0
    text.indices.forEach { index ->
        when (masked[index]) {
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
