package cplus.lsp

import cplus.*
import cplus.parser.TreeSitterCPlusParserBackend
import cplus.parser.TreeSitterCPlusParseSession
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Parser-backed stdio LSP transport and document service. */
class CPlusLspServer(
    input: InputStream = System.`in`,
    output: OutputStream = System.out
) {
    private val input = BufferedInputStream(input)
    private val output = BufferedOutputStream(output)
    private val sources = SourceManager()
    private val parser = TreeSitterCPlusParserBackend()
    private val documents = ConcurrentHashMap<String, LspDocument>()
    private val parseSessions = ConcurrentHashMap<String, TreeSitterCPlusParseSession>()
    private val openDocuments = ConcurrentHashMap.newKeySet<String>()
    private val importsByDocument = LinkedHashMap<String, LinkedHashSet<String>>()
    private val importersByDocument = LinkedHashMap<String, LinkedHashSet<String>>()
    private val cancelledRequests = ConcurrentHashMap.newKeySet<String>()
    private val requestExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val requestFutures = ConcurrentHashMap<String, Future<*>>()
    /** Fair ordering keeps a queued read before a later document mutation. */
    private val stateLock = ReentrantReadWriteLock(true)
    private val comptimeIndexer = CPlusComptimeIndexer()
    private var importPaths = CPlusImportPaths()

    fun serve() {
        try {
            while (true) {
            val message = readMessage() ?: return
            val parsed = try {
                Json.parse(message)
            } catch (_: Exception) {
                respondError(null, -32700, "invalid JSON")
                continue
            }
            val request = parsed as? Json.Object
            if (request == null) {
                respondError(null, -32600, "request must be a JSON object")
                continue
            }
            val method = request.string("method")
            if (method == null) {
                request.values["id"]?.let { respondError(it, -32600, "request method is required") }
                continue
            }
            val id = request.values["id"]
            if (id != null && hasInvalidParameters(method, request)) {
                respondError(id, -32602, "invalid parameters for $method")
                continue
            }
            when (method) {
                "initialize" -> {
                    withWriteState { configureWorkspace(request) }
                    respond(id, "{\"capabilities\":{" +
                        "\"textDocumentSync\":1," +
                        "\"documentSymbolProvider\":true," +
                        "\"workspaceSymbolProvider\":true," +
                        "\"completionProvider\":{\"triggerCharacters\":[\".\",\"->\"]}," +
                        "\"hoverProvider\":true," +
                        "\"definitionProvider\":true," +
                        "\"referencesProvider\":true}}")
                }
                "initialized" -> Unit
                "$/cancelRequest" -> request.cancelledRequestId()?.let { cancelRequest(it) }
                "shutdown" -> {
                    requestExecutor.shutdown()
                    requestExecutor.awaitTermination(5, TimeUnit.SECONDS)
                    respond(id, "null")
                    return
                }
                "exit" -> return
                "textDocument/didOpen", "textDocument/didChange" -> request.documentText()?.let { update ->
                    withWriteState {
                        if (updateDocument(update, opened = true)) {
                            refreshImports(update.uri)
                            refreshDependents(update.uri)
                        }
                    }
                }
                "textDocument/didClose" -> request.uri()?.let { uri ->
                    withWriteState {
                        openDocuments.remove(uri)
                        documents.remove(uri)
                        parseSessions.remove(uri)
                        clearImports(uri)
                        pruneUnreachableDocuments()
                        notify("textDocument/publishDiagnostics", "{\"uri\":${Json.string(uri)},\"diagnostics\":[]}")
                    }
                }
                "textDocument/documentSymbol" -> dispatchReadRequest(id, request) {
                    respondForDocument(id, request) { documentSymbols(request.uri()) }
                }
                // Workspace symbols are a bounded in-memory snapshot. Taking it synchronously
                // preserves wire-order semantics when a following didChange mutates the import
                // graph; document-scoped work remains on the cooperative executor below.
                "workspace/symbol" -> if (id != null) withReadState {
                    respond(id, workspaceSymbols(request.stringParameter("query") ?: ""))
                }
                "textDocument/completion" -> dispatchReadRequest(id, request) {
                    respondForDocument(id, request) { completions(request) }
                }
                "textDocument/hover" -> dispatchReadRequest(id, request) {
                    respondForDocument(id, request) { hover(request) }
                }
                "textDocument/definition" -> dispatchReadRequest(id, request) {
                    respondForDocument(id, request) { definition(request) }
                }
                "textDocument/references" -> dispatchReadRequest(id, request) {
                    respondForDocument(id, request) { references(request) }
                }
                else -> if (id != null) respondError(id, -32601, "method not supported: $method")
            }
            }
        } finally {
            requestExecutor.shutdownNow()
        }
    }

    private fun dispatchReadRequest(id: Json?, request: Json.Object, work: () -> Unit) {
        if (id == null) return
        val key = id.encode()
        val future = requestExecutor.submit {
            try {
                withReadState { work() }
            } finally {
                requestFutures.remove(key)
            }
        }
        requestFutures[key] = future
        if (key in cancelledRequests) future.cancel(true)
    }

    private fun cancelRequest(key: String) {
        cancelledRequests += key
        requestFutures.remove(key)?.cancel(true)
    }

    private inline fun <T> withReadState(action: () -> T): T {
        val lock = stateLock.readLock()
        lock.lock()
        return try {
            action()
        } finally {
            lock.unlock()
        }
    }

    private inline fun <T> withWriteState(action: () -> T): T {
        val lock = stateLock.writeLock()
        lock.lock()
        return try {
            action()
        } finally {
            lock.unlock()
        }
    }

    private fun configureWorkspace(request: Json.Object) {
        val root = request.rootUri()?.let(::pathFromUri)
        val project = root?.let(CPlusProject::find)
        importPaths = if (project != null) {
            project.importPaths(null, System.getenv("CPLUS_STDLIB"))
        } else {
            CPlusImportPaths(
                standardLibraryRoots = CPlusProject.defaultStandardLibraryRoots(null, System.getenv("CPLUS_STDLIB")),
                moduleRoots = listOfNotNull(root)
            )
        }
    }

    private fun hasInvalidParameters(method: String, request: Json.Object): Boolean = when (method) {
        "textDocument/documentSymbol" -> request.uri() == null
        "textDocument/completion", "textDocument/hover", "textDocument/definition", "textDocument/references" ->
            request.uri() == null || request.position() == null
        else -> false
    }

    private fun updateDocument(update: LspDocumentUpdate, opened: Boolean = false): Boolean {
        val current = documents[update.uri]
        if (current != null && update.version != null && update.version <= current.version) return false
        val version = update.version ?: ((current?.version ?: 0) + 1)
        val snapshot = sources.open(SourceId.named(update.uri), update.text)
        val parsed = parseDocument(update.uri, version, snapshot)
        if (opened) openDocuments += update.uri
        documents[update.uri] = parsed
        publishDiagnostics(update.uri, parsed.diagnostics)
        return true
    }

    private fun parseDocument(uri: String, version: Int, snapshot: SourceSnapshot): LspDocument {
        val materialized = runCatching {
            materializeCPlusForTools(snapshot.sourceFile, importPaths)
        }.getOrNull()?.takeIf { it.source.text != snapshot.text }
        val parsedSnapshot = if (materialized == null) snapshot else
            sources.open(SourceId.named("$uri#comptime"), materialized.source.text)
        val options = CPlusParseOptions(editorMode = true)
        val session = parseSessions[uri]
        val result = if (session == null) {
            parser.openIncrementalSession(parsedSnapshot, options).also { parseSessions[uri] = it }.current()
        } else {
            runCatching { session.update(parsedSnapshot).parseResult }.getOrElse {
                parser.openIncrementalSession(parsedSnapshot, options).also { parseSessions[uri] = it }.current()
            }
        }
        val diagnostics = result.diagnostics.map { diagnostic ->
            if (materialized == null) diagnostic else diagnostic.copy(
                span = materialized.mapping.toOriginalSpan(diagnostic.span)
            )
        }
        return LspDocument(
            uri = uri,
            version = version,
            snapshot = snapshot,
            parsedText = parsedSnapshot.text,
            mappedSource = materialized?.mapping,
            ast = CPlusAstAdapter().adapt(result),
            diagnostics = diagnostics
        )
    }

    private fun refreshImports(uri: String) {
        clearImports(uri)
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        pending += uri
        repeat(MAX_WORKSPACE_IMPORTS) {
            val currentUri = pending.removeFirstOrNull() ?: return@repeat
            if (!visited.add(currentUri)) return@repeat
            val document = documents[currentUri] ?: return@repeat
            for (span in comptimeIndexer.index(document.ast).imports) {
                val requested = importPath(document.snapshot.text.substring(span.startOffset, span.endOffset)) ?: continue
                val resolved = resolveImport(document, requested) ?: continue
                val importedUri = resolved.toUri().toString()
                importsByDocument.getOrPut(currentUri) { LinkedHashSet() }.add(importedUri)
                importersByDocument.getOrPut(importedUri) { LinkedHashSet() }.add(currentUri)
                if (documents.containsKey(importedUri)) {
                    pending += importedUri
                    continue
                }
                val text = runCatching { Files.readString(resolved, StandardCharsets.UTF_8) }.getOrNull() ?: continue
                val snapshot = sources.open(SourceId.fromPath(resolved), text)
                documents[importedUri] = parseDocument(importedUri, 0, snapshot)
                pending += importedUri
            }
        }
        pruneUnreachableDocuments()
    }

    /** Re-publish dependent snapshots so semantic diagnostics can later reuse this boundary. */
    private fun refreshDependents(uri: String) {
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque<String>()
        pending += uri
        while (pending.isNotEmpty()) {
            val changed = pending.removeFirst()
            if (!visited.add(changed)) continue
            for (dependent in importersByDocument[changed].orEmpty()) {
                documents[dependent]?.let { document ->
                    publishDiagnostics(dependent, document.diagnostics)
                    pending += dependent
                }
            }
        }
    }

    private fun clearImports(uri: String) {
        for (imported in importsByDocument.remove(uri).orEmpty()) {
            importersByDocument[imported]?.let { importers ->
                importers.remove(uri)
                if (importers.isEmpty()) importersByDocument.remove(imported)
            }
        }
    }

    private fun pruneUnreachableDocuments() {
        val reachable = mutableSetOf<String>()
        val pending = ArrayDeque<String>()
        pending.addAll(openDocuments)
        while (pending.isNotEmpty()) {
            val uri = pending.removeFirst()
            if (!reachable.add(uri)) continue
            pending.addAll(importsByDocument[uri].orEmpty())
        }
        for (uri in documents.keys.toList()) {
            if (uri !in reachable && uri !in openDocuments) {
                clearImports(uri)
                documents.remove(uri)
                parseSessions.remove(uri)
            }
        }
    }

    private fun resolveImport(document: LspDocument, requested: String): Path? = runCatching {
        CPlusImportResolver(importPaths).resolve(
            source = SourceFile(document.snapshot.text, pathFromUri(document.uri)?.toString() ?: document.uri),
            requestedPath = requested,
            extensionlessCandidates = listOf("cp", "c+")
        )
    }.getOrNull()

    private fun importPath(text: String): String? = IMPORT_LITERAL.find(text)?.groupValues?.get(1)?.let(::unescapeImport)

    private fun pathFromUri(value: String): Path? = runCatching {
        if (value.startsWith("file:", ignoreCase = true)) Path.of(URI(value)) else Path.of(value)
    }.getOrNull()

    private fun unescapeImport(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    private fun publishDiagnostics(uri: String, diagnostics: List<ParserDiagnostic>) {
        val encoded = diagnostics.joinToString(",") { diagnostic ->
            val span = diagnostic.span
            "{\"range\":{\"start\":{\"line\":${span.startLine - 1},\"character\":${span.startColumn - 1}}," +
                "\"end\":{\"line\":${span.endLine - 1},\"character\":${span.endColumn - 1}}}," +
                "\"severity\":${if (diagnostic.severity.name == "WARNING") 2 else 1}," +
                "\"code\":${Json.string(diagnostic.code)},\"source\":\"c-plus\",\"message\":${Json.string(diagnostic.message)}}"
        }
        notify("textDocument/publishDiagnostics", "{\"uri\":${Json.string(uri)},\"diagnostics\":[$encoded]}")
    }

    private fun documentSymbols(uri: String?): String {
        val document = uri?.let(documents::get) ?: return "[]"
        return document.symbols.joinToString(",", "[", "]") { symbol -> symbol.toJson() }
    }

    private fun workspaceSymbols(query: String): String {
        val normalized = query.trim().lowercase()
        return documents.values.asSequence()
            .flatMap { it.symbols.asSequence() }
            .filter { normalized.isEmpty() || it.name.lowercase().contains(normalized) }
            .sortedWith(compareBy<LspSymbol> { it.name.lowercase() }.thenBy { it.uri }.thenBy { it.selection.startOffset })
            .joinToString(",", "[", "]") { it.toJson() }
    }

    private fun completions(request: Json.Object): String {
        val document = request.uri()?.let(documents::get) ?: return "{\"isIncomplete\":false,\"items\":[]}"
        val prefix = document.wordAt(request.position())
        val offset = document.snapshot.offsetAt(request.position())
        val receiver = offset?.let { receiverType(document, it) }
        val visible = visibleSymbols(document).toList()
        val receiverScopes = receiver?.let { typeName ->
            visible.filter { it.name == typeName && it.kind == 23 }.map { it.scope }
        }.orEmpty()
        val symbols = visible.asSequence().filter { symbol ->
            receiver == null || symbol.ownerName == receiver ||
                (symbol.name != receiver && receiverScopes.any { it.contains(symbol.selection) })
        }
        val keywordNames = if (receiver == null) CPLUS_KEYWORDS.asSequence() else emptySequence()
        val names = (keywordNames + symbols.map { it.name })
            .filter { prefix.isEmpty() || it.startsWith(prefix) }
            .distinct()
            .sorted()
        val items = names.joinToString(",", "[", "]") { name ->
            "{\"label\":${Json.string(name)},\"kind\":${if (name in CPLUS_KEYWORDS) 14 else 6}}"
        }
        return "{\"isIncomplete\":false,\"items\":$items}"
    }

    private fun hover(request: Json.Object): String {
        val document = request.uri()?.let(documents::get) ?: return "null"
        val symbol = symbolAt(request) ?: return "null"
        val value = "`${symbol.detail}`"
        return "{\"contents\":{\"kind\":\"markdown\",\"value\":${Json.string(value)}},\"range\":${symbol.rangeJson()}}"
    }

    private fun definition(request: Json.Object): String {
        val symbol = symbolAt(request) ?: return "[]"
        return "[{\"uri\":${Json.string(symbol.uri)},\"range\":${symbol.selection.toRangeJson()}}]"
    }

    /**
     * Return identifier occurrences in the open document's resolved import closure.
     *
     * This deliberately uses normalized AST identifier nodes instead of a textual
     * regex, so comments and string literals cannot become false references. The
     * bounded closure is the same visibility boundary used by completion/definition.
     */
    private fun references(request: Json.Object): String {
        val document = request.uri()?.let(documents::get) ?: return "[]"
        val target = symbolAt(request)?.name ?: document.wordAt(request.position())
        if (target.isEmpty()) return "[]"
        val includeDeclaration = ((request.values["params"] as? Json.Object)
            ?.values?.get("context") as? Json.Object)
            ?.values?.get("includeDeclaration") as? Json.BooleanValue
        val declarationRanges = visibleSymbols(document)
            .filter { it.name == target }
            .map { it.uri to it.selection.startOffset }
            .toSet()
        return reachableDocuments(document.uri).asSequence()
            .mapNotNull(documents::get)
            .flatMap { candidate ->
                candidate.ast.root.flatten()
                    .filter { it.kind == CPlusAstKind.IDENTIFIER }
                    .filter { node ->
                        val text = candidate.parsedText.substring(node.span.startOffset, node.span.endOffset)
                        text == target
                    }
                    .map { node ->
                        val mapped = candidate.mappedSource?.toOriginalSpan(node.span) ?: node.span
                        val uri = mapped.file?.let { file ->
                            if (file.startsWith("file:", ignoreCase = true)) file
                            else runCatching { Path.of(file).toUri().toString() }.getOrNull()
                        } ?: candidate.uri
                        Triple(uri, mapped, node)
                    }
            }
            .filter { (uri, span, _) -> includeDeclaration?.value != false || uri to span.startOffset !in declarationRanges }
            .sortedWith(compareBy<Triple<String, SourceSpan, CPlusAstNode>> { it.first }.thenBy { it.second.startOffset })
            .joinToString(",", "[", "]") { (uri, span, _) ->
                "{\"uri\":${Json.string(uri)},\"range\":${span.toRangeJson()}}"
            }
    }

    private fun symbolAt(request: Json.Object): LspSymbol? {
        val document = request.uri()?.let(documents::get) ?: return null
        document.symbolAt(request.position())?.let { return it }
        val word = document.wordAt(request.position())
        if (word.isEmpty()) return null
        val offset = document.snapshot.offsetAt(request.position()) ?: return null
        val candidates = visibleSymbols(document)
            .filter { it.name == word }
            .toList()
        val callArity = callArity(document.snapshot.text, offset)
        val orderedCandidates = if (callArity == null) candidates else {
            candidates.sortedWith(compareBy<LspSymbol> {
                parameterArity(it)?.let { arity -> kotlin.math.abs(arity - callArity) } ?: Int.MAX_VALUE
            }.thenBy { it.selection.startOffset })
        }
        receiverType(document, offset)?.let { receiverType ->
            orderedCandidates.firstOrNull { it.ownerName == receiverType }?.let { return it }
        }
        return orderedCandidates
            .filter { it.uri == document.uri && it.scope.contains(offset) }
            .sortedWith(compareBy<LspSymbol> { it.scope.size() }.thenByDescending { it.selection.startOffset })
            .firstOrNull()
            ?: orderedCandidates
                .filter { it.uri == document.uri && it.selection.startOffset <= offset }
                .maxByOrNull { it.selection.startOffset }
            ?: orderedCandidates.firstOrNull()
    }

    private fun callArity(text: String, offset: Int): Int? {
        var cursor = offset.coerceIn(0, text.length)
        while (cursor < text.length && text[cursor].isIdentifierPart()) cursor++
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        if (cursor >= text.length || text[cursor] != '(') return null
        var depth = 0
        var arguments = 0
        var sawToken = false
        var index = cursor + 1
        while (index < text.length) {
            when (text[index]) {
                '(' , '[', '{' -> depth++
                ')' -> if (depth == 0) return if (sawToken) arguments + 1 else 0 else depth--
                ']' , '}' -> if (depth > 0) depth--
                ',' -> if (depth == 0) arguments++
                else -> if (!text[index].isWhitespace()) sawToken = true
            }
            index++
        }
        return null
    }

    private fun parameterArity(symbol: LspSymbol): Int? {
        if (symbol.kind !in setOf(6, 12)) return null
        val open = symbol.detail.indexOf('(')
        val close = symbol.detail.indexOf(')', open + 1)
        if (open < 0 || close < 0) return null
        val parameters = symbol.detail.substring(open + 1, close).trim()
        if (parameters.isEmpty() || parameters == "void") return 0
        var depth = 0
        var count = 1
        parameters.forEach { character ->
            when (character) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                ',' -> if (depth == 0) count++
            }
        }
        return count
    }

    /** Symbols visible from a document are local symbols plus its resolved import closure. */
    private fun visibleSymbols(document: LspDocument): Sequence<LspSymbol> {
        val reachable = reachableDocuments(document.uri)
        return reachable.asSequence()
            .mapNotNull(documents::get)
            .flatMap { candidate -> candidate.symbols.asSequence() }
            .distinctBy { it.uri to it.selection.startOffset }
    }

    private fun reachableDocuments(root: String): Set<String> {
        val reachable = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        pending += root
        while (pending.isNotEmpty()) {
            val uri = pending.removeFirst()
            if (!reachable.add(uri)) continue
            pending.addAll(importsByDocument[uri].orEmpty())
        }
        return reachable
    }

    private fun receiverType(document: LspDocument, offset: Int): String? {
        val prefix = document.snapshot.text.substring(0, offset)
        val receiver = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*(?:->|\\.)\\s*$")
            .find(prefix)?.groupValues?.get(1) ?: return null
        if (receiver == "self") {
            return document.symbols
                .asSequence()
                .filter { it.ownerName != null && it.scope.contains(offset) }
                .minByOrNull { it.scope.size() }
                ?.ownerName
        }
        return visibleSymbols(document)
            .asSequence()
            .filter { it.name == receiver && it.kind == 13 && it.selection.startOffset <= offset }
            .sortedWith(compareBy<LspSymbol> { !it.scope.contains(offset) }.thenByDescending { it.selection.startOffset })
            .mapNotNull(::declaredType)
            .map { resolveTypeAlias(it) }
            .firstOrNull()
    }

    private fun resolveTypeAlias(typeName: String): String {
        var current = typeName
        val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            val alias = documents.values.asSequence()
                .flatMap { it.symbols.asSequence() }
                .firstOrNull { it.kind == 26 && it.name == current } ?: break
            val underlying = Regex("typedef\\s+(?:struct\\s+|union\\s+)?([A-Za-z_][A-Za-z0-9_]*)")
                .find(alias.detail)?.groupValues?.get(1) ?: break
            if (underlying == current) break
            current = underlying
        }
        return current
    }

    private fun declaredType(symbol: LspSymbol): String? {
        val beforeName = symbol.detail.substringBeforeLast(symbol.name).trim()
        return Regex("(?:struct\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\*?\\s*$")
            .find(beforeName)?.groupValues?.get(1)
    }

    private fun notify(method: String, params: String) = write("{\"jsonrpc\":\"2.0\",\"method\":${Json.string(method)},\"params\":$params}")

    private fun respond(id: Json?, result: String) = write("{\"jsonrpc\":\"2.0\",\"id\":${id?.encode() ?: "null"},\"result\":$result}")

    private fun respondForDocument(id: Json?, request: Json.Object, result: () -> String) {
        val uri = request.uri()
        val version = uri?.let { documents[it]?.version }
        val encodedId = id?.encode()
        if (encodedId != null && encodedId in cancelledRequests) {
            cancelledRequests.remove(encodedId)
            return
        }
        val payload = result()
        if (uri != null && documents[uri]?.version != version) return
        respond(id, payload)
    }

    private fun respondError(id: Json?, code: Int, message: String) =
        write("{\"jsonrpc\":\"2.0\",\"id\":${id?.encode() ?: "null"},\"error\":{\"code\":$code,\"message\":${Json.string(message)}}}")

    private fun write(message: String) {
        synchronized(output) {
            val bytes = message.toByteArray(StandardCharsets.UTF_8)
            output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write(bytes)
            output.flush()
        }
    }

    private fun readMessage(): String? {
        var contentLength: Int? = null
        while (true) {
            val line = readHeaderLine() ?: return null
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull()
            }
        }
        val length = contentLength ?: return null
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) return null
            offset += count
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun readHeaderLine(): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(StandardCharsets.US_ASCII)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
    }
}

private data class LspDocument(
    val uri: String,
    val version: Int,
    val snapshot: SourceSnapshot,
    val parsedText: String,
    val mappedSource: MappedText?,
    val ast: CPlusAst,
    val diagnostics: List<ParserDiagnostic> = emptyList()
) {
    val symbols: List<LspSymbol> = LspSymbolIndex.build(uri, parsedText, mappedSource, ast)

    fun wordAt(position: LspPosition?): String {
        val offset = snapshot.offsetAt(position) ?: return ""
        var start = offset
        var end = offset
        while (start > 0 && snapshot.text[start - 1].isIdentifierPart()) start--
        while (end < snapshot.text.length && snapshot.text[end].isIdentifierPart()) end++
        return snapshot.text.substring(start, end)
    }

    fun symbolAt(position: LspPosition?): LspSymbol? {
        val offset = snapshot.offsetAt(position) ?: return null
        return symbols.firstOrNull { offset in it.selection.startOffset until it.selection.endOffset }
    }
}

private data class LspDocumentUpdate(
    val uri: String,
    val text: String,
    val version: Int?
)

private data class LspSymbol(
    val uri: String,
    val name: String,
    val kind: Int,
    val detail: String,
    val span: SourceSpan,
    val selection: SourceSpan,
    val scope: SourceSpan,
    val ownerName: String? = null
) {
    fun rangeJson(): String = span.toRangeJson()
    fun toJson(): String = "{\"name\":${Json.string(name)},\"kind\":$kind," +
        "\"detail\":${Json.string(detail)},\"location\":{\"uri\":${Json.string(uri)},\"range\":${span.toRangeJson()}}}"
}

private object LspSymbolIndex {
    fun build(uri: String, parsedText: String, mappedSource: MappedText?, ast: CPlusAst): List<LspSymbol> {
        val symbols = mutableListOf<LspSymbol>()
        val declarationKinds = setOf(
            CPlusAstKind.STRUCT_DECLARATION, CPlusAstKind.UNION_DECLARATION,
            CPlusAstKind.ENUM_DECLARATION, CPlusAstKind.TYPE_ALIAS,
            CPlusAstKind.FUNCTION_DECLARATION, CPlusAstKind.METHOD_DECLARATION,
            CPlusAstKind.VARIABLE_DECLARATION, CPlusAstKind.FIELD_DECLARATION
        )
        fun mapSpan(span: SourceSpan): SourceSpan = mappedSource?.toOriginalSpan(span) ?: span
        fun visit(node: CPlusAstNode, scope: SourceSpan, ownerName: String? = null) {
            val nestedScope = if (
                node.kind in setOf(
                    CPlusAstKind.STRUCT_DECLARATION, CPlusAstKind.UNION_DECLARATION,
                    CPlusAstKind.FUNCTION_DECLARATION, CPlusAstKind.METHOD_DECLARATION
                ) || node.syntaxKind in setOf("compound_statement", "cplus_block")
            ) node.span else scope
            if (node.kind in declarationKinds) {
                val nameNode = namedNode(node, node.kind)
                val name = nameNode?.let { parsedText.substring(it.span.startOffset, it.span.endOffset) }?.trim()
                    ?.takeIf { it.matches(IDENTIFIER) }
                if (name != null) {
                    val kind = when (node.kind) {
                        CPlusAstKind.STRUCT_DECLARATION, CPlusAstKind.UNION_DECLARATION -> 23
                        CPlusAstKind.ENUM_DECLARATION -> 10
                        CPlusAstKind.TYPE_ALIAS -> 26
                        CPlusAstKind.METHOD_DECLARATION -> 6
                        CPlusAstKind.FUNCTION_DECLARATION -> 12
                        CPlusAstKind.FIELD_DECLARATION -> 8
                        else -> 13
                    }
                    val mappedDeclaration = mapSpan(node.span)
                    val symbolUri = originUri(mappedDeclaration.file) ?: uri
                    symbols += LspSymbol(
                        symbolUri, name, kind,
                        parsedText.substring(node.span.startOffset, node.span.endOffset)
                            .replace(Regex("\\s+"), " ").trim().take(160),
                        mapSpan(node.span), mapSpan(nameNode.span), mapSpan(nestedScope),
                        ownerName.takeIf {
                            node.kind == CPlusAstKind.METHOD_DECLARATION ||
                                node.kind == CPlusAstKind.FIELD_DECLARATION ||
                                (node.kind == CPlusAstKind.VARIABLE_DECLARATION &&
                                    node.syntaxKind == "field_declaration")
                        }
                    )
                }
            }
            val childOwner = if (node.kind == CPlusAstKind.STRUCT_DECLARATION ||
                node.kind == CPlusAstKind.UNION_DECLARATION) {
                node.let { namedNode(it, it.kind) }?.let { parsedText.substring(it.span.startOffset, it.span.endOffset).trim() }
                    ?.takeIf { it.matches(IDENTIFIER) }
            } else ownerName
            node.children.forEach { visit(it, nestedScope, childOwner) }
        }
        visit(ast.root, ast.root.span)
        return symbols.distinctBy { it.name to it.selection.startOffset }.toList()
    }

    private fun namedNode(node: CPlusAstNode, kind: CPlusAstKind): CPlusAstNode? {
        val declarator = node.flatten().firstOrNull { it.syntaxKind in setOf("function_declarator", "cplus_method_declarator") }
        if (kind == CPlusAstKind.FUNCTION_DECLARATION || kind == CPlusAstKind.METHOD_DECLARATION) {
            return declarator?.flatten()?.firstOrNull { it.kind == CPlusAstKind.IDENTIFIER }
                ?: node.flatten().firstOrNull { it.kind == CPlusAstKind.IDENTIFIER }
        }
        return when (kind) {
            CPlusAstKind.TYPE_ALIAS -> node.flatten().lastOrNull { it.kind == CPlusAstKind.IDENTIFIER }
            CPlusAstKind.FIELD_DECLARATION, CPlusAstKind.VARIABLE_DECLARATION ->
                node.flatten().lastOrNull { it.kind == CPlusAstKind.IDENTIFIER }
            else -> node.children.firstOrNull { it.kind == CPlusAstKind.IDENTIFIER }
                ?: node.flatten().firstOrNull { it.kind == CPlusAstKind.IDENTIFIER }
        }
    }

    private fun originUri(file: String?): String? = file?.let {
        if (it.startsWith("file:", ignoreCase = true)) it
        else runCatching { Path.of(it).toUri().toString() }.getOrNull()
    }
}

private fun CPlusAstNode.flatten(): Sequence<CPlusAstNode> = sequence {
    yield(this@flatten)
    children.forEach { yieldAll(it.flatten()) }
}

private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_'

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val IMPORT_LITERAL = Regex("\\\"((?:[^\\\"\\\\]|\\\\.)*)\\\"")
private const val MAX_WORKSPACE_IMPORTS = 256
private val CPLUS_KEYWORDS = setOf(
    "pub", "priv", "static", "borrowed", "owned", "mut", "self", "comptime", "type", "function",
    "defer", "try", "catch", "throws", "test", "assert", "assertEquals", "if", "else", "for", "while",
    "return", "struct", "union", "enum", "typedef", "const", "volatile", "sizeof"
)

private fun SourceSpan.toRangeJson(): String =
    "{\"start\":{\"line\":${startLine - 1},\"character\":${startColumn - 1}}," +
    "\"end\":{\"line\":${endLine - 1},\"character\":${endColumn - 1}}}"

private fun SourceSpan.contains(offset: Int): Boolean =
    offset in startOffset until endOffset

private fun SourceSpan.contains(other: SourceSpan): Boolean =
    file == other.file && startOffset <= other.startOffset && other.endOffset <= endOffset

private fun SourceSpan.size(): Int = (endOffset - startOffset).coerceAtLeast(0)

private data class LspPosition(val line: Int, val character: Int)

private fun SourceSnapshot.offsetAt(position: LspPosition?): Int? {
    if (position == null || position.line < 0 || position.character < 0) return null
    var lineStart = 0
    repeat(position.line) {
        val newline = text.indexOf('\n', lineStart)
        if (newline < 0) return null
        lineStart = newline + 1
    }
    return (lineStart + position.character).coerceAtMost(text.length)
}

private fun Json.Object.uri(): String? {
    val params = values["params"] as? Json.Object ?: return null
    return (params.values["textDocument"] as? Json.Object)?.string("uri")
}

private fun Json.Object.rootUri(): String? =
    (values["params"] as? Json.Object)?.string("rootUri")

private fun Json.Object.documentText(): LspDocumentUpdate? {
    val params = values["params"] as? Json.Object ?: return null
    val document = params.values["textDocument"] as? Json.Object
    val uri = document?.string("uri") ?: return null
    val version = document.int("version")
    val directText = document.string("text")
    if (directText != null) return LspDocumentUpdate(uri, directText, version)
    val changes = params.values["contentChanges"] as? Json.Array ?: return null
    val text = (changes.values.firstOrNull() as? Json.Object)?.string("text") ?: return null
    return LspDocumentUpdate(uri, text, version)
}

private fun Json.Object.position(): LspPosition? {
    val params = values["params"] as? Json.Object ?: return null
    val position = params.values["position"] as? Json.Object ?: return null
    val line = position.int("line") ?: return null
    val character = position.int("character") ?: return null
    return LspPosition(line, character)
}

private fun Json.Object.stringParameter(name: String): String? =
    (values["params"] as? Json.Object)?.string(name)

private fun Json.Object.cancelledRequestId(): String? =
    (values["params"] as? Json.Object)?.values?.get("id")?.encode()

private sealed interface Json {
    fun encode(): String

    data class Object(val values: Map<String, Json>) : Json {
        override fun encode() = values.entries.joinToString(",", "{", "}") { (key, value) -> "${string(key)}:${value.encode()}" }
        fun string(key: String): String? = (values[key] as? StringValue)?.value
        fun int(key: String): Int? = (values[key] as? NumberValue)?.value?.toIntOrNull()
    }
    data class Array(val values: List<Json>) : Json { override fun encode() = values.joinToString(",", "[", "]", transform = Json::encode) }
    data class StringValue(val value: String) : Json { override fun encode() = string(value) }
    data class NumberValue(val value: String) : Json { override fun encode() = value }
    data class BooleanValue(val value: Boolean) : Json { override fun encode() = value.toString() }
    data object Null : Json { override fun encode() = "null" }

    companion object {
        fun string(value: String): String = buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
                }
            }
            append('"')
        }

        fun parse(text: String): Json = Reader(text).parse()

        private class Reader(private val text: String) {
            private var index = 0
            fun parse(): Json { skip(); return value().also { skip(); require(index == text.length) } }
            private fun value(): Json {
                skip()
                return when (text.getOrNull(index)) {
                    '{' -> objectValue()
                    '[' -> arrayValue()
                    '"' -> StringValue(stringValue())
                    't' -> literal("true", BooleanValue(true))
                    'f' -> literal("false", BooleanValue(false))
                    'n' -> literal("null", Null)
                    else -> NumberValue(numberValue())
                }
            }
            private fun objectValue(): Json.Object {
                index++
                val values = linkedMapOf<String, Json>(); skip()
                if (text.getOrNull(index) == '}') { index++; return Json.Object(values) }
                while (true) {
                    val key = stringValue(); skip(); require(text[index++] == ':'); values[key] = value(); skip()
                    if (text[index++] == '}') return Json.Object(values)
                    require(text[index - 1] == ','); skip()
                }
            }
            private fun arrayValue(): Json.Array {
                index++; val values = mutableListOf<Json>(); skip()
                if (text.getOrNull(index) == ']') { index++; return Json.Array(values) }
                while (true) { values += value(); skip(); if (text[index++] == ']') return Json.Array(values); require(text[index - 1] == ','); skip() }
            }
            private fun stringValue(): String {
                require(text[index++] == '"'); val result = StringBuilder()
                while (true) {
                    val character = text[index++]
                    when (character) {
                        '"' -> return result.toString()
                        '\\' -> when (val escaped = text[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b'); 'f' -> result.append('\u000c'); 'n' -> result.append('\n'); 'r' -> result.append('\r'); 't' -> result.append('\t')
                            'u' -> result.append(text.substring(index, index + 4).toInt(16).toChar()).also { index += 4 }
                            else -> error("invalid JSON escape")
                        }
                        else -> result.append(character)
                    }
                }
            }
            private fun numberValue(): String { val start = index; while (text.getOrNull(index)?.let { it == '-' || it == '+' || it == '.' || it.isDigit() || it in "eE" } == true) index++; return text.substring(start, index) }
            private fun <T : Json> literal(expected: String, result: T): T { require(text.startsWith(expected, index)); index += expected.length; return result }
            private fun skip() { while (text.getOrNull(index)?.isWhitespace() == true) index++ }
        }
    }
}
