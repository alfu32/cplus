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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Parser-backed stdio LSP transport and document service. */
class CPlusLspServer(
    input: InputStream = System.`in`,
    output: OutputStream = System.out,
    private val astDiagnosticProvider: (CPlusAst) -> List<ParserDiagnostic> = { it.unsupportedAstDiagnostics() }
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
    private val activeReadView = ThreadLocal<ReadView?>()
    /** Cancellations received before a request instance has been dispatched. */
    private val preCancelledRequests = ConcurrentHashMap.newKeySet<String>()
    private val seenRequestIds = ConcurrentHashMap.newKeySet<String>()
    /**
     * Keep the stdio reader responsive under request bursts. Two workers and a
     * finite queue provide cooperative back-pressure instead of allowing an
     * unbounded stream of stale editor requests to accumulate.
     */
    private val requestExecutor: ExecutorService = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(64),
        Executors.defaultThreadFactory(),
        ThreadPoolExecutor.AbortPolicy()
    )
    private val requestStates = ConcurrentHashMap<String, RequestState>()
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
                        "\"referencesProvider\":true," +
                        "\"documentHighlightProvider\":true}}")
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
                "textDocument/documentSymbol" -> dispatchReadRequest(id, request) { state ->
                    respondForDocument(id, request, state) { documentSymbols(request.uri()) }
                }
                // Workspace symbols are a bounded in-memory snapshot. Taking it synchronously
                // preserves wire-order semantics when a following didChange mutates the import
                // graph; document-scoped work remains on the cooperative executor below.
                "workspace/symbol" -> if (id != null) withReadState {
                    respond(id, workspaceSymbols(request.stringParameter("query") ?: ""))
                }
                "textDocument/completion" -> dispatchReadRequest(id, request) { state ->
                    respondForDocument(id, request, state) { completions(request) }
                }
                "textDocument/hover" -> dispatchReadRequest(id, request) { state ->
                    respondForDocument(id, request, state) { hover(request) }
                }
                "textDocument/definition" -> dispatchReadRequest(id, request) { state ->
                    respondForDocument(id, request, state) { definition(request) }
                }
                "textDocument/references" -> dispatchReadRequest(id, request) { state ->
                    respondForDocument(id, request, state) { references(request) }
                }
                "textDocument/documentHighlight" -> dispatchReadRequest(id, request) { state ->
                    respondForDocument(id, request, state) { documentHighlights(request) }
                }
                else -> if (id != null) respondError(id, -32601, "method not supported: $method")
            }
            }
        } finally {
            requestExecutor.shutdownNow()
        }
    }

    private fun dispatchReadRequest(id: Json?, request: Json.Object, work: (RequestState) -> Unit) {
        if (id == null) return
        val key = id.encode()
        rememberBounded(seenRequestIds, key)
        val readView = withReadState {
            ReadView(
                documents = documents.toMap(),
                importsByDocument = importsByDocument.mapValues { (_, imports) -> imports.toSet() }
            )
        }
        val state = RequestState(key, readView)
        val cancelledBeforeDispatch = preCancelledRequests.remove(key)
        if (cancelledBeforeDispatch) state.cancelled.set(true)
        requestStates[key] = state
        val future = try {
            requestExecutor.submit {
                try {
                    if (!state.cancelled.get()) withReadState {
                        activeReadView.set(state.readView)
                        try {
                            work(state)
                        } finally {
                            activeReadView.remove()
                        }
                    }
                } finally {
                    requestStates.remove(key, state)
                }
            }
        } catch (_: RejectedExecutionException) {
            requestStates.remove(key, state)
            respondError(id, -32001, "language server request queue is full")
            return
        }
        state.future.set(future)
        // A very fast task can finish and remove itself before the submitting
        // thread stores the Future. The state is already installed, so a
        // cancellation can still target the exact request instance.
        if (future.isDone) requestStates.remove(key, state)
    }

    private fun cancelRequest(key: String) {
        val state = requestStates[key]
        if (state == null) {
            // Preserve the LSP case where cancellation precedes a request, but
            // do not let cancellation after a completed request poison a reused
            // ID. The seen-ID set is bounded below.
            if (key !in seenRequestIds) rememberBounded(preCancelledRequests, key)
            return
        }
        state.cancelled.set(true)
        requestStates.remove(key, state)
        state.future.get()?.cancel(true)
    }

    private fun rememberBounded(set: MutableSet<String>, key: String) {
        if (set.size >= MAX_TRACKED_REQUEST_IDS) {
            set.iterator().asSequence().firstOrNull()?.let(set::remove)
        }
        set += key
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

    private fun readDocuments(): Map<String, LspDocument> =
        activeReadView.get()?.documents ?: documents

    private fun readImportsByDocument(): Map<String, Set<String>> =
        activeReadView.get()?.importsByDocument ?: importsByDocument

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
        "textDocument/completion", "textDocument/hover", "textDocument/definition",
        "textDocument/references", "textDocument/documentHighlight" ->
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
        val ast = CPlusAstAdapter().adapt(result)
        val parserDiagnostics = (result.diagnostics + astDiagnosticProvider(ast)).distinctBy {
            listOf(it.code, it.span.startOffset, it.span.endOffset, it.message)
        }
        val provisional = LspDocument(
            uri = uri,
            version = version,
            snapshot = parsedSnapshot,
            parsedText = parsedSnapshot.text,
            mappedSource = materialized?.mapping,
            ast = ast,
            diagnostics = parserDiagnostics
        )
        val semanticDiagnostics = ambiguousCallableDiagnostics(provisional)
        val diagnostics = (parserDiagnostics + semanticDiagnostics).distinctBy {
            listOf(it.code, it.span.startOffset, it.span.endOffset, it.message)
        }.map { diagnostic ->
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
            ast = ast,
            diagnostics = diagnostics
        )
    }

    /**
     * Report only ambiguity that is proven by the bounded callable scorer.
     * Unresolved calls and calls whose candidates have a unique score remain
     * compiler/delegation territory; the LSP must not invent errors for them.
     */
    private fun ambiguousCallableDiagnostics(document: LspDocument): List<ParserDiagnostic> {
        val symbols = visibleSymbols(document)
            .filter { it.kind in setOf(6, 12) }
        return document.ast.root.descendantsAndSelf()
            .filter { it.kind == CPlusAstKind.CALL_EXPRESSION }
            .mapNotNull { call ->
                if (call.span.endOffset <= call.span.startOffset ||
                    call.span.endOffset > document.parsedText.length
                ) return@mapNotNull null
                val expression = document.parsedText.substring(call.span.startOffset, call.span.endOffset)
                val (callee, arguments) = callParts(expression) ?: return@mapNotNull null
                val member = Regex("(.+?)\\s*(?:->|\\.)\\s*($IDENTIFIER)").matchEntire(callee)
                val candidates = if (member == null) {
                    if (!callee.matches(IDENTIFIER)) return@mapNotNull null
                    symbols.filter { it.name == callee && it.ownerName == null }.toList()
                } else {
                    val receiver = member.groupValues[1]
                    val owner = receiverValue(
                        document,
                        receiver,
                        call.span.startOffset + receiver.length
                    )?.typeName?.let(::resolveTypeAlias) ?: return@mapNotNull null
                    symbols.filter {
                        it.name == member.groupValues[2] &&
                            it.ownerName?.let(::resolveTypeAlias) == owner
                    }.toList()
                }
                if (!isAmbiguousCallable(document, candidates, arguments)) return@mapNotNull null
                val name = member?.groupValues?.get(2) ?: callee
                ParserDiagnostic(
                    code = "CPLUS_AMBIGUOUS_CALL",
                    message = "ambiguous call to '$name'; overload candidates have equal bounded match scores",
                    severity = ParserDiagnosticSeverity.ERROR,
                    span = call.span
                )
            }
            .toList()
            .distinctBy { listOf(it.span.startOffset, it.span.endOffset, it.message) }
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
            for (requested in importRequests(document)) {
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

    private fun resolveImport(document: LspDocument, requested: String): Path? {
        val sourcePath = pathFromUri(document.uri)
        lexicalImportPath(sourcePath, requested)?.let { return it }
        val resolved = runCatching {
            CPlusImportResolver(importPaths).resolve(
                source = SourceFile(document.snapshot.text, sourcePath?.toString() ?: document.uri),
                requestedPath = requested,
                extensionlessCandidates = listOf("cp", "c+")
            )
        }.getOrNull()
        if (resolved != null) return resolved

        // Keep the LSP import boundary usable when a host's URI/path provider
        // applies a different lexical normalization than the shared compiler
        // resolver (notably drive-letter and symlink forms on Windows/macOS).
        // Namespaced imports must still go through the configured roots and are
        // therefore deliberately excluded from this local relative fallback.
        if (sourcePath == null || requested.contains(":/")) return null
        val sourceDirectory = sourcePath.parent ?: return null
        val requestedPath = runCatching { Path.of(requested) }.getOrNull() ?: return null
        val hasExtension = requestedPath.fileName?.toString()?.substringAfterLast('.', "")?.isNotEmpty() == true
        val candidates = if (hasExtension) {
            listOf(requestedPath)
        } else {
            listOf(requestedPath.resolveSibling("${requestedPath.fileName}.cp"),
                requestedPath.resolveSibling("${requestedPath.fileName}.c+"))
        }
        return candidates
            .map { candidate -> (if (candidate.isAbsolute) candidate else sourceDirectory.resolve(candidate)).normalize() }
            .firstOrNull { Files.isRegularFile(it) }
    }

    /**
     * Resolve an editor import without canonicalizing the path used as its URI.
     * Compiler imports still use canonical paths for graph identity, but an LSP
     * location must remain stable relative to the URI the editor opened.
     */
    private fun lexicalImportPath(sourcePath: Path?, requested: String): Path? {
        val (roots, relative) = when {
            requested.startsWith("stdlib:/") -> importPaths.standardLibraryRoots to requested.removePrefix("stdlib:/")
            requested.startsWith("module:/") -> importPaths.moduleRoots to requested.removePrefix("module:/")
            requested.startsWith("project:/") -> importPaths.moduleRoots to requested.removePrefix("project:/")
            sourcePath != null -> listOfNotNull(sourcePath.parent) to requested
            else -> emptyList<Path>() to requested
        }
        if (roots.isEmpty()) return null
        val requestedPath = runCatching { Path.of(relative) }.getOrNull() ?: return null
        val extension = requestedPath.fileName?.toString()?.substringAfterLast('.', "").orEmpty()
        val candidates = if (extension.isNotEmpty()) {
            listOf(requestedPath)
        } else {
            listOf("cp", "c+").map { suffix ->
                requestedPath.resolveSibling("${requestedPath.fileName}.$suffix")
            }
        }
        for (root in roots) {
            val normalizedRoot = root.toAbsolutePath().normalize()
            for (candidate in candidates) {
                val resolved = (if (candidate.isAbsolute) candidate else normalizedRoot.resolve(candidate)).normalize()
                if (resolved.startsWith(normalizedRoot) && Files.isRegularFile(resolved)) return resolved
            }
        }
        return null
    }

    private fun importPath(text: String): String? = IMPORT_LITERAL.find(text)?.groupValues?.get(1)?.let(::unescapeImport)

    private fun importRequests(document: LspDocument): List<String> {
        val indexed = comptimeIndexer.index(document.ast).imports
            .mapNotNull { span -> importPath(document.snapshot.text.substring(span.startOffset, span.endOffset)) }
        if (indexed.isNotEmpty()) return indexed.distinct()

        // Some host builds of the recovery parser preserve a comptime import
        // only as an error/recovery node. Keep the AST index authoritative when
        // it recognizes the construct, but recover the small import boundary
        // here so workspace navigation remains portable while the grammar
        // migration is still in progress.
        return IMPORT_RECOVERY_LITERAL.findAll(document.snapshot.text)
            .map { unescapeImport(it.groupValues[1]) }
            .distinct()
            .toList()
    }

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
        val document = uri?.let(readDocuments()::get) ?: return "[]"
        return document.symbols.joinToString(",", "[", "]") { symbol -> symbol.toJson() }
    }

    private fun workspaceSymbols(query: String): String {
        val normalized = query.trim().lowercase()
        return readDocuments().values.asSequence()
            .flatMap { document -> document.symbols.asSequence().map { displaySymbol(it, document.uri) } }
            .filter { normalized.isEmpty() || it.name.lowercase().contains(normalized) }
            .sortedWith(compareBy<LspSymbol> { it.name.lowercase() }.thenBy { it.uri }.thenBy { it.selection.startOffset })
            .joinToString(",", "[", "]") { it.toJson() }
    }

    private fun completions(request: Json.Object): String {
        val document = request.uri()?.let(readDocuments()::get) ?: return "{\"isIncomplete\":false,\"items\":[]}"
        val prefix = document.wordAt(request.position())
        val offset = document.snapshot.offsetAt(request.position())
        val receiverAccess = offset?.let { receiverAccess(document, it) }
        if (receiverAccess?.typeName == null && receiverAccess != null) {
            return "{\"isIncomplete\":false,\"items\":[]}"
        }
        val receiver = receiverAccess?.typeName
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
        val document = request.uri()?.let(readDocuments()::get) ?: return "null"
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
        val document = request.uri()?.let(readDocuments()::get) ?: return "[]"
        val selected = symbolAt(request)
        val target = selected?.name ?: document.wordAt(request.position())
        if (target.isEmpty()) return "[]"
        val includeDeclaration = ((request.values["params"] as? Json.Object)
            ?.values?.get("context") as? Json.Object)
            ?.values?.get("includeDeclaration") as? Json.BooleanValue
        val visible = visibleSymbols(document).toList()
        return referenceLocations(document, selected, target, visible)
            .filter { reference ->
                includeDeclaration?.value != false || selected == null ||
                    (reference.uri to reference.span.startOffset) !=
                        (selected.uri to selected.selection.startOffset)
            }
            .sortedWith(compareBy<LspReference> { it.uri }.thenBy { it.span.startOffset })
            .joinToString(",", "[", "]") { reference ->
                "{\"uri\":${Json.string(reference.uri)},\"range\":${reference.span.toRangeJson()}}"
            }
    }

    /** Return read/write occurrences for the symbol under the requested position. */
    private fun documentHighlights(request: Json.Object): String {
        val document = request.uri()?.let(readDocuments()::get) ?: return "[]"
        val selected = symbolAt(request) ?: return "[]"
        val visible = visibleSymbols(document).toList()
        return referenceLocations(document, selected, selected.name, visible)
            .sortedWith(compareBy<LspReference> { it.uri }.thenBy { it.span.startOffset })
            .joinToString(",", "[", "]") { reference ->
                "{\"range\":${reference.span.toRangeJson()},\"kind\":${referenceHighlightKind(reference, selected)}}"
            }
    }

    private fun referenceLocations(
        document: LspDocument,
        selected: LspSymbol?,
        target: String,
        visible: List<LspSymbol>
    ): Sequence<LspReference> {
        return reachableDocuments(document.uri).asSequence()
            .mapNotNull(readDocuments()::get)
            .flatMap { candidate ->
                candidate.ast.root.identifierOccurrences()
                    .filter { it.node.kind == CPlusAstKind.IDENTIFIER }
                    .filter { node ->
                        val text = candidate.parsedText.substring(node.node.span.startOffset, node.node.span.endOffset)
                        text == target
                    }
                    .map { occurrence ->
                        val node = occurrence.node
                        val mapped = candidate.mappedSource?.toOriginalSpan(node.span) ?: node.span
                        val uri = mapped.file?.let { file ->
                            if (file.startsWith("file:", ignoreCase = true)) file
                            else runCatching { Path.of(file).toUri().toString() }.getOrNull()
                        } ?: candidate.uri
                        LspReference(
                            uri,
                            mapped,
                            occurrence.copy(callArity = callArity(candidate.snapshot.text, node.span.startOffset)),
                            candidate
                        )
                    }
            }
            .filter { reference ->
                selected == null || referenceMatches(selected, target, visible, reference)
            }
    }

    private fun referenceHighlightKind(reference: LspReference, selected: LspSymbol): Int {
        if (reference.uri == selected.uri &&
            reference.span.startOffset == selected.selection.startOffset
        ) return 3 // LSP DocumentHighlightKind.Write: declaration

        // A function name used as the right-hand side of a function-pointer
        // initializer or as a callback argument is read as a callable value.
        // The lexical `=` check below must not misclassify that use as a write;
        // only the pointer variable being initialized is written.
        if (selected.kind == 6 || selected.kind == 12) return 2

        val text = reference.document.snapshot.text
        val start = reference.span.startOffset.coerceIn(0, text.length)
        val end = reference.span.endOffset.coerceIn(start, text.length)
        val before = text.substring(0, start).takeLast(3)
        val after = text.substring(end).dropWhile { it.isWhitespace() }
        val compoundAssignment = before.trimEnd().endsWithAny(
            "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>="
        )
        val increment = before.trimEnd().endsWith("++") || before.trimEnd().endsWith("--") ||
            after.trimStart().startsWith("++") || after.trimStart().startsWith("--")
        val assignment = after.startsWith("=") && !after.startsWith("==")
        return if (compoundAssignment && !before.trimEnd().endsWith("==") || increment || assignment) 3 else 2
    }

    private fun referenceMatches(
        selected: LspSymbol,
        target: String,
        visible: List<LspSymbol>,
        reference: LspReference
    ): Boolean {
        val uri = reference.uri
        val span = reference.span
        val occurrence = reference.occurrence
        if (uri == selected.uri && span.startOffset == selected.selection.startOffset) return true
        val declarations = visible.filter { it.name == target }
        if (selected.kind == 6 || selected.kind == 12) {
            // Function references are bounded to call sites. Type-based callable
            // values are recognized only in initializer/argument contexts.
            val callSite = occurrence.ancestors.contains("call_expression")
            val valueSite = occurrence.ancestors.any {
                it == "initializer" || it == "init_declarator" || it == "argument_list"
            }
            if (!callSite && !valueSite) return false
            if (selected.ownerName != null) {
                val receiver = receiverType(reference.document, occurrence.node.span.startOffset)
                if (receiver != null && resolveTypeAlias(receiver) != selected.ownerName) return false
            }
            val callArity = occurrence.callArity
            // C-plus instance calls omit the implicit receiver even though the
            // lowered declaration contains it as the first parameter.
            val selectedArity = callParameterArity(selected)
            return !callSite || callArity == null || selectedArity == null || callArity == selectedArity
        }
        if (selected.kind == 8 && selected.ownerName != null) {
            // Field references are selected by the receiver's resolved owner;
            // falling back to declaration order would leak same-named fields.
            val receiver = receiverType(reference.document, occurrence.node.span.startOffset)
            return receiver != null && resolveTypeAlias(receiver) == selected.ownerName
        }
        val scoped = declarations
            .filter { it.scope.contains(span) && it.selection.startOffset <= span.startOffset }
            .minWithOrNull(compareBy<LspSymbol> { it.scope.size() }.thenByDescending { it.selection.startOffset })
            ?: declarations
                .filter { it.uri == uri && it.selection.startOffset <= span.startOffset }
                .maxByOrNull { it.selection.startOffset }
        return scoped?.uri == selected.uri && scoped.selection.startOffset == selected.selection.startOffset
    }

    private fun symbolAt(request: Json.Object): LspSymbol? {
        val document = request.uri()?.let(readDocuments()::get) ?: return null
        val direct = document.symbolAt(request.position())
        val word = document.wordAt(request.position())
        if (word.isEmpty()) return null
        val offset = document.snapshot.offsetAt(request.position()) ?: return null
        val callArguments = callArguments(document.snapshot.text, offset)
        if (direct != null && callArguments == null) return direct
        val candidates = visibleSymbols(document)
            .filter { it.name == word }
            .toList()
        val callArity = callArity(document.snapshot.text, offset)
        val orderedCandidates = if (callArity == null) candidates else {
            candidates.sortedWith(compareBy<LspSymbol> {
                overloadScore(document, it, callArguments)
                    ?: parameterArity(it)?.let { arity -> 100 + kotlin.math.abs(arity - callArity) }
                    ?: Int.MAX_VALUE
            }.thenBy { it.selection.startOffset })
        }
        val receiverTypeName = receiverType(document, offset)
        val receiverCandidates = receiverTypeName?.let { receiverType ->
            val resolvedReceiver = resolveTypeAlias(receiverType)
            orderedCandidates.filter {
                it.ownerName?.let(::resolveTypeAlias) == resolvedReceiver
            }
        } ?: orderedCandidates.filter { it.ownerName == null }
        if (callArguments != null && isAmbiguousCallable(document, receiverCandidates, callArguments)) return null
        if (receiverTypeName != null) receiverCandidates.firstOrNull()?.let { return it }
        val scopedCandidates = receiverCandidates
            .filter { it.uri == document.uri && it.scope.contains(offset) }
        if (scopedCandidates.isNotEmpty()) {
            return if (callArguments != null) {
                scopedCandidates.first()
            } else {
                scopedCandidates.minWithOrNull(
                    compareBy<LspSymbol> { it.scope.size() }
                        .thenByDescending { it.selection.startOffset }
                )
            }
        }
        val precedingCandidates = receiverCandidates
            .filter { it.uri == document.uri && it.selection.startOffset <= offset }
        return if (callArguments != null) precedingCandidates.firstOrNull()
            ?: receiverCandidates.firstOrNull()
        else precedingCandidates.maxByOrNull { it.selection.startOffset }
            ?: receiverCandidates.firstOrNull()
    }

    private fun isAmbiguousCallable(
        document: LspDocument,
        candidates: List<LspSymbol>,
        arguments: List<String>
    ): Boolean {
        val ranked = candidates.mapNotNull { candidate ->
            val score = overloadScore(document, candidate, arguments)
                ?: callParameterArity(candidate)?.let { arity ->
                    100 + kotlin.math.abs(arity - arguments.size)
                }
            score?.let { candidate to it }
        }.sortedBy { it.second }
        if (ranked.size < 2 || ranked[0].second != ranked[1].second) return false
        return ranked[0].first.selection.startOffset != ranked[1].first.selection.startOffset
    }

    private fun callArity(text: String, offset: Int): Int? {
        return callArguments(text, offset)?.size
    }

    private fun callArguments(text: String, offset: Int): List<String>? {
        var cursor = offset.coerceIn(0, text.length)
        while (cursor < text.length && text[cursor].isIdentifierPart()) cursor++
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        if (cursor >= text.length || text[cursor] != '(') return null
        var depth = 0
        var start = cursor + 1
        val arguments = mutableListOf<String>()
        var index = cursor + 1
        while (index < text.length) {
            when (text[index]) {
                '(' , '[', '{' -> depth++
                ')' -> if (depth == 0) {
                    val last = text.substring(start, index).trim()
                    if (last.isNotEmpty()) arguments += last
                    return arguments
                } else depth--
                ']' , '}' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    arguments += text.substring(start, index).trim()
                    start = index + 1
                }
            }
            index++
        }
        return null
    }

    private fun overloadScore(document: LspDocument, symbol: LspSymbol, arguments: List<String>?): Int? {
        if (arguments == null) return null
        val parameters = callParameterTypes(symbol) ?: return null
        if (parameters.size != arguments.size) return null
        return parameters.zip(arguments).sumOf { (parameter, argument) ->
            typeMatchScore(document, parameter, argument)
        }
    }

    private fun callParameterTypes(symbol: LspSymbol): List<TypeShape>? {
        val parameters = parameterTypes(symbol) ?: return null
        return if (isInstanceMethod(symbol) && parameters.isNotEmpty()) parameters.drop(1) else parameters
    }

    private fun callParameterArity(symbol: LspSymbol): Int? =
        callParameterTypes(symbol)?.size ?: parameterArity(symbol)

    private fun isInstanceMethod(symbol: LspSymbol): Boolean =
        symbol.ownerName != null && !Regex("\\bstatic\\b").containsMatchIn(symbol.detail)

    private fun parameterTypes(symbol: LspSymbol): List<TypeShape>? {
        if (symbol.kind !in setOf(6, 12)) return null
        val open = symbol.detail.indexOf('(')
        val close = matchingClosingParen(symbol.detail, open)
        if (open < 0 || close < 0) return null
        val parameters = symbol.detail.substring(open + 1, close).trim()
        if (parameters.isEmpty() || parameters == "void") return emptyList()
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        parameters.forEachIndexed { index, character ->
            when (character) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    result += parameters.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        result += parameters.substring(start).trim()
        return result.mapNotNull(::typeShape)
    }

    private fun matchingClosingParen(text: String, open: Int): Int {
        if (open < 0 || open >= text.length || text[open] != '(') return -1
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun typeMatchScore(document: LspDocument, expected: TypeShape, argument: String): Int {
        val actual = expressionType(document, argument) ?: return 50
        val expectedBase = resolveTypeAlias(expected.base)
        val actualBase = resolveTypeAlias(actual.base)
        if (expected.callableArity != null) {
            return when {
                actual.callableArity == expected.callableArity &&
                    (expected.callableReturnBase == null ||
                        resolveTypeAlias(expected.callableReturnBase) == actualBase) -> 0
                actual.callableArity != null -> 35
                else -> 45
            }
        }
        if (actual.callableArity != null) return 35
        val expectedPointers = expected.pointerDepth + expected.arrayDepth
        val actualPointers = actual.pointerDepth + actual.arrayDepth
        if (expectedPointers == 0 && actualPointers == 0) {
            val expectedNumeric = numericRank(expectedBase)
            val actualNumeric = numericRank(actualBase)
            if (expectedNumeric != null && actualNumeric != null) {
                // Prefer the closest standard scalar conversion when no exact
                // overload exists. This is deliberately only a ranking hint;
                // the selected C compiler remains authoritative for legality.
                return 5 + kotlin.math.abs(expectedNumeric - actualNumeric)
            }
        }
        return when {
            expectedBase == actualBase && expectedPointers == actualPointers -> 0
            expectedBase == actualBase -> 10
            expectedPointers == actualPointers -> 20
            else -> 40
        }
    }

    private fun numericRank(typeName: String): Int? = when (typeName) {
        "bool" -> 0
        "char", "signed", "unsigned" -> 1
        "short" -> 2
        "int" -> 3
        "long" -> 4
        "float" -> 5
        "double" -> 6
        else -> null
    }

    private fun typeShape(declaration: String): TypeShape? {
        val cleaned = declaration
            .replace(Regex("\\b(borrowed|owned|mut|const|volatile|restrict)\\b"), " ")
            .trim()
        val callable = Regex(
            "(?:struct\\s+|union\\s+|enum\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\(\\s*\\*[^)]*\\)\\s*\\((.*)\\)"
        ).find(cleaned)
        if (callable != null) {
            val parameters = callable.groupValues[2].trim()
            return TypeShape(
                base = callable.groupValues[1],
                pointerDepth = 1,
                arrayDepth = 0,
                callableArity = parameterCount(parameters),
                callableReturnBase = callable.groupValues[1]
            )
        }
        val pointerDepth = cleaned.count { it == '*' }
        val arrayDepth = Regex("\\[[^]]*\\]").findAll(cleaned).count()
        val base = Regex("(?:struct\\s+|union\\s+|enum\\s+)?([A-Za-z_][A-Za-z0-9_]*)")
            .find(cleaned)?.groupValues?.get(1) ?: return null
        return TypeShape(base, pointerDepth, arrayDepth)
    }

    private fun expressionType(document: LspDocument, expression: String): TypeShape? {
        val value = expression.trim()
        if (value.length >= 2 && value.first() == '(' && value.last() == ')' &&
            matchingOuterParentheses(value)
        ) {
            return expressionType(document, value.substring(1, value.length - 1))
        }
        if (value.startsWith("*")) {
            val pointed = expressionType(document, value.substring(1)) ?: return null
            if (pointed.pointerDepth == 0) return null
            return pointed.copy(pointerDepth = pointed.pointerDepth - 1)
        }
        if (value.matches(Regex("[0-9]+"))) return TypeShape("int", 0, 0)
        if (value.matches(Regex("[0-9]+\\.[0-9]+"))) return TypeShape("double", 0, 0)
        if (value.startsWith("\"") && value.endsWith("\"")) return TypeShape("char", 0, 1)
        if (value.startsWith("'") && value.endsWith("'")) return TypeShape("int", 0, 0)
        val address = value.removePrefix("&").takeIf { value.startsWith("&") }
        val name = address ?: value
        if (!name.matches(IDENTIFIER)) return null
        val function = visibleSymbols(document)
            .filter { it.kind in setOf(6, 12) && it.name == name }
            .minByOrNull { it.selection.startOffset }
        if (function != null) return functionTypeShape(function)
        val variable = visibleSymbols(document)
            .filter { it.kind == 13 && it.name == name }
            .sortedWith(compareBy<LspSymbol> { !it.scope.contains(document.snapshot.text.indexOf(value)) })
            .firstOrNull() ?: return null
        val declared = declaredTypeInfo(variable) ?: return null
        return TypeShape(
            resolveTypeAlias(declared.typeName),
            declared.pointerDepth + if (address != null) 1 else 0,
            declared.arrayDepth
        )
    }

    private fun matchingOuterParentheses(value: String): Boolean {
        var depth = 0
        value.forEachIndexed { index, character ->
            when (character) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0 && index != value.lastIndex) return false
                }
            }
        }
        return depth == 0
    }

    private fun functionTypeShape(symbol: LspSymbol): TypeShape? {
        val nameOffset = symbol.detail.indexOf(symbol.name)
        val open = symbol.detail.indexOf('(', nameOffset + symbol.name.length)
        val close = matchingClosingParen(symbol.detail, open)
        if (nameOffset <= 0 || open < 0 || close < 0) return null
        val returnBase = typeShape(symbol.detail.substring(0, nameOffset))?.base ?: return null
        return TypeShape(
            base = returnBase,
            pointerDepth = 1,
            arrayDepth = 0,
            callableArity = parameterCount(symbol.detail.substring(open + 1, close)),
            callableReturnBase = returnBase
        )
    }

    private fun parameterCount(parameters: String): Int {
        val trimmed = parameters.trim()
        if (trimmed.isEmpty() || trimmed == "void") return 0
        var depth = 0
        var count = 1
        trimmed.forEach { character ->
            when (character) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                ',' -> if (depth == 0) count++
            }
        }
        return count
    }

    private fun parameterArity(symbol: LspSymbol): Int? {
        if (symbol.kind !in setOf(6, 12)) return null
        val open = symbol.detail.indexOf('(')
        val close = matchingClosingParen(symbol.detail, open)
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
        return sequenceOf(document.symbols.asSequence())
            .flatten()
            .plus(reachable.asSequence().filter { it != document.uri }
            .mapNotNull(readDocuments()::get)
            .flatMap { candidate -> candidate.symbols.asSequence().map { displaySymbol(it, candidate.uri) } })
            .distinctBy { it.uri to it.selection.startOffset }
    }

    /**
     * Materialization records canonical filesystem origins, whereas an editor
     * opened/imported document is keyed by its lexical URI. Prefer the URI of
     * the corresponding live document so definition/workspace results retain
     * the path spelling the client supplied (including symlink and drive forms).
     */
    private fun displaySymbol(symbol: LspSymbol, fallbackUri: String): LspSymbol {
        val displayUri = readDocuments().keys.firstOrNull { candidate ->
            sameSourceUri(candidate, symbol.uri)
        } ?: fallbackUri
        return if (displayUri == symbol.uri) symbol else symbol.copy(uri = displayUri)
    }

    private fun reachableDocuments(root: String): Set<String> {
        val reachable = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        pending += root
        while (pending.isNotEmpty()) {
            val uri = pending.removeFirst()
            if (!reachable.add(uri)) continue
            pending.addAll(readImportsByDocument()[uri].orEmpty())
        }
        return reachable
    }

    private fun receiverType(document: LspDocument, offset: Int): String? {
        return receiverAccess(document, offset)?.typeName
    }

    private fun receiverAccess(document: LspDocument, offset: Int): ReceiverAccess? {
        val prefix = document.snapshot.text.substring(0, offset)
        val explicitReceiverMatch = Regex(
            "(\\((?:&|\\*)[A-Za-z_][A-Za-z0-9_]*\\))\\s*(->|\\.)\\s*$"
        ).find(prefix)
        val match = explicitReceiverMatch ?: Regex(
            "((?:[A-Za-z_][A-Za-z0-9_]*|\\([^\\n]*\\))(?:(?:\\s*(?:->|\\.)\\s*[A-Za-z_][A-Za-z0-9_]*)|(?:\\s*\\([^()]*\\)))*)\\s*(->|\\.)\\s*$"
        ).find(prefix) ?: return null
        val receiver = match.groupValues[1].trim()
        val operator = match.groupValues[2]
        val declared = receiverValue(document, receiver, offset)
        val explicitAddressReceiver = receiver.startsWith("(&") && receiver.endsWith(")")
        val operatorMatches = when (operator) {
            "->" -> declared?.pointerDepth ?: 0 > 0
            "." -> (declared?.pointerDepth == 0 && declared.arrayDepth == 0) || explicitAddressReceiver
            else -> false
        }
        return ReceiverAccess(
            declared?.typeName?.let(::resolveTypeAlias).takeIf { operatorMatches },
            operator
        )
    }

    /** Resolve the declared value type of an identifier or a previously selected field. */
    private fun receiverValue(document: LspDocument, expression: String, offset: Int): DeclaredType? {
        val value = expression.trim()
        if (value.length >= 2 && value.first() == '(' && value.last() == ')' &&
            matchingOuterParentheses(value)
        ) {
            return receiverValue(document, value.substring(1, value.length - 1), offset)
        }
        if (value.startsWith("&")) {
            return receiverValue(document, value.substring(1), offset)?.let {
                it.copy(pointerDepth = it.pointerDepth + 1)
            }
        }
        if (value.startsWith("*")) {
            return receiverValue(document, value.substring(1), offset)?.takeIf { it.pointerDepth > 0 }?.let {
                it.copy(pointerDepth = it.pointerDepth - 1)
            }
        }
        if (value == "self") {
            val owner = document.symbols
                .asSequence()
                .filter { it.ownerName != null && it.scope.contains(offset) }
                .minByOrNull { it.scope.size() }
                ?.ownerName
            return owner?.let { DeclaredType(it, 1, 0) }
        }
        val call = callParts(value)
        if (call != null) {
            return functionCallReturnType(document, call.first, offset, call.second)
        }
        if (value.matches(IDENTIFIER)) {
            val variable = visibleSymbols(document)
                .asSequence()
                .filter { it.name == value && it.kind == 13 && it.selection.startOffset <= offset }
                .sortedWith(compareBy<LspSymbol> { !it.scope.contains(offset) }
                    .thenByDescending { it.selection.startOffset })
                .firstOrNull()
            if (variable != null) return declaredTypeInfo(variable)
            return visibleSymbols(document)
                .asSequence()
                .firstOrNull { it.name == value && it.kind == 23 }
                ?.let { DeclaredType(it.name, 0, 0) }
        }

        val member = Regex("(.+?)\\s*(->|\\.)\\s*([A-Za-z_][A-Za-z0-9_]*)$").find(value)
            ?: return null
        val parent = receiverValue(document, member.groupValues[1], offset) ?: return null
        val access = member.groupValues[2]
        val accessMatches = when (access) {
            "->" -> parent.pointerDepth > 0
            "." -> parent.pointerDepth == 0 && parent.arrayDepth == 0
            else -> false
        }
        if (!accessMatches) return null
        val owner = resolveTypeAlias(parent.typeName)
        return visibleSymbols(document)
            .asSequence()
            .filter {
                it.kind == 8 && it.ownerName?.let(::resolveTypeAlias) == owner &&
                    it.name == member.groupValues[3]
            }
            .mapNotNull(::declaredTypeInfo)
            .firstOrNull()
    }

    /** Resolve a simple function or method call to its declared return shape. */
    private fun functionCallReturnType(
        document: LspDocument,
        callee: String,
        offset: Int,
        arguments: List<String> = emptyList()
    ): DeclaredType? {
        if (callee.matches(IDENTIFIER)) {
            return selectCallable(document, visibleSymbols(document)
                .asSequence()
                .filter { it.name == callee && it.kind in setOf(6, 12) }
                .toList(), arguments)?.let(::functionReturnType)
        }
        val member = Regex("(.+?)\\s*(->|\\.)\\s*([A-Za-z_][A-Za-z0-9_]*)$").find(callee)
            ?: return null
        val parent = receiverValue(document, member.groupValues[1], offset) ?: return null
        val operatorMatches = when (member.groupValues[2]) {
            "->" -> parent.pointerDepth > 0
            "." -> parent.pointerDepth == 0 && parent.arrayDepth == 0
            else -> false
        }
        if (!operatorMatches) return null
        val owner = resolveTypeAlias(parent.typeName)
        return selectCallable(document, visibleSymbols(document)
            .asSequence()
            .filter {
                it.kind == 6 && it.name == member.groupValues[3] &&
                    it.ownerName?.let(::resolveTypeAlias) == owner
            }
            .toList(), arguments)?.let(::functionReturnType)
    }

    /** Select a callable return declaration using the same bounded ranking as call navigation. */
    private fun selectCallable(
        document: LspDocument,
        candidates: List<LspSymbol>,
        arguments: List<String>
    ): LspSymbol? {
        if (candidates.isEmpty()) return null
        val knownArity = candidates.mapNotNull(::callParameterArity)
        if (knownArity.isNotEmpty() && knownArity.none { it == arguments.size }) return null
        val ranked = candidates.sortedWith(compareBy<LspSymbol> {
            overloadScore(document, it, arguments)
                ?: callParameterArity(it)?.let { arity -> 100 + kotlin.math.abs(arity - arguments.size) }
                ?: Int.MAX_VALUE
        }.thenBy { it.selection.startOffset })
        val matching = ranked.filter { callParameterArity(it) == null || callParameterArity(it) == arguments.size }
        if (matching.size > 1) {
            val firstScore = overloadScore(document, matching[0], arguments)
                ?: callParameterArity(matching[0])?.let { arity -> 100 + kotlin.math.abs(arity - arguments.size) }
            val secondScore = overloadScore(document, matching[1], arguments)
                ?: callParameterArity(matching[1])?.let { arity -> 100 + kotlin.math.abs(arity - arguments.size) }
            if (firstScore != null && firstScore == secondScore &&
                matching[0].selection.startOffset != matching[1].selection.startOffset
            ) {
                return null
            }
        }
        return matching.firstOrNull()
    }

    /** Parse a trailing call, including nested argument expressions. */
    private fun callParts(expression: String): Pair<String, List<String>>? {
        val value = expression.trim()
        if (!value.endsWith(')')) return null
        var depth = 0
        var open = -1
        for (index in value.indices.reversed()) {
            when (value[index]) {
                ')' -> depth++
                '(' -> {
                    depth--
                    if (depth == 0) {
                        open = index
                        break
                    }
                }
            }
        }
        if (open <= 0) return null
        val callee = value.substring(0, open).trim()
        if (callee.isEmpty()) return null
        return callee to splitCallArguments(value.substring(open + 1, value.length - 1))
    }

    private fun splitCallArguments(arguments: String): List<String> {
        if (arguments.trim().isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        arguments.forEachIndexed { index, character ->
            when (character) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    result += arguments.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        result += arguments.substring(start).trim()
        return result
    }

    private fun functionReturnType(symbol: LspSymbol): DeclaredType? {
        val nameOffset = symbol.detail.indexOf(symbol.name)
        if (nameOffset <= 0) return null
        val returnDeclaration = symbol.detail.substring(0, nameOffset)
            .replace(Regex("\\b(pub|priv|static|comptime)\\b"), " ")
        val shape = typeShape(returnDeclaration) ?: return null
        return DeclaredType(resolveTypeAlias(shape.base), shape.pointerDepth, shape.arrayDepth)
    }

    private fun resolveTypeAlias(typeName: String): String {
        var current = typeName
        val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            val alias = readDocuments().values.asSequence()
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
        return declaredTypeInfo(symbol)?.typeName
    }

    private fun declaredTypeInfo(symbol: LspSymbol): DeclaredType? {
        // Qualifiers may occur before the base type or after a pointer layer:
        // `const counter_t *p` and `counter_t *const p` have the same receiver
        // shape. Keep the base type and pointer depth, while leaving complete
        // C type checking to the selected compiler.
        val beforeName = symbol.detail.substringBeforeLast(symbol.name)
            .replace(Regex("\\b(const|volatile|restrict)\\b"), " ")
            .trim()
        val match = Regex("(?:struct\\s+|union\\s+|enum\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*(\\*+)?\\s*$")
            .find(beforeName) ?: return null
        val arrayDepth = Regex("\\[[^]]*\\]").findAll(symbol.detail.substringAfter(symbol.name)).count()
        return DeclaredType(match.groupValues[1], match.groupValues[2].length, arrayDepth)
    }

    private fun notify(method: String, params: String) = write("{\"jsonrpc\":\"2.0\",\"method\":${Json.string(method)},\"params\":$params}")

    private fun respond(id: Json?, result: String) = write("{\"jsonrpc\":\"2.0\",\"id\":${id?.encode() ?: "null"},\"result\":$result}")

    private fun respondForDocument(id: Json?, request: Json.Object, state: RequestState, result: () -> String) {
        val encodedId = id?.encode()
        if (state.cancelled.get() || wasCancelled(encodedId)) return
        val payload = result()
        // Cancellation may arrive while parsing or indexing is in progress. Do
        // not publish a response that became obsolete during that work.
        if (state.cancelled.get() || wasCancelled(encodedId)) return
        respond(id, payload)
    }

    private fun wasCancelled(encodedId: String?): Boolean =
        encodedId != null && preCancelledRequests.remove(encodedId)

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

private data class ReceiverAccess(val typeName: String?, val operator: String)

internal fun CPlusAst.unsupportedAstDiagnostics(): List<ParserDiagnostic> {
    val diagnostics = mutableListOf<ParserDiagnostic>()

    fun visit(node: CPlusAstNode, coveredByUnsupportedParent: Boolean) {
        val unsupported = node.named && (node.kind == CPlusAstKind.OTHER || node.opaque)
        if (unsupported && !coveredByUnsupportedParent) {
            diagnostics += ParserDiagnostic(
                code = "CPLUS_UNSUPPORTED_AST",
                message = "AST fragment '${node.syntaxKind}' has no compiler mapping; code generation continues",
                severity = ParserDiagnosticSeverity.WARNING,
                span = node.span
            )
        }
        node.children.forEach { child -> visit(child, coveredByUnsupportedParent || unsupported) }
    }

    visit(root, false)
    return diagnostics
}

private class RequestState(val key: String, val readView: ReadView) {
    val cancelled = AtomicBoolean(false)
    val future = AtomicReference<Future<*>>()
}

private data class ReadView(
    val documents: Map<String, LspDocument>,
    val importsByDocument: Map<String, Set<String>>
)

private data class DeclaredType(val typeName: String, val pointerDepth: Int, val arrayDepth: Int)

private data class TypeShape(
    val base: String,
    val pointerDepth: Int,
    val arrayDepth: Int,
    val callableArity: Int? = null,
    val callableReturnBase: String? = null
)

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
        // Mapped comptime declarations can carry ranges from an imported
        // source file while their generated nodes live in this document. Do
        // not use such a range as a hit-test against the root document; the
        // word-based lookup below can still resolve the imported declaration.
        return symbols.firstOrNull {
            sameSourceFile(it.selection.file, snapshot.sourceFile.name) &&
                offset in it.selection.startOffset until it.selection.endOffset
        }
    }
}

private data class LspDocumentUpdate(
    val uri: String,
    val text: String,
    val version: Int?
)

private data class LspIdentifierOccurrence(
    val node: CPlusAstNode,
    val ancestors: List<String>,
    val callArity: Int? = null
)

private data class LspReference(
    val uri: String,
    val span: SourceSpan,
    val occurrence: LspIdentifierOccurrence,
    val document: LspDocument
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
                    val symbolUri = originUri(mappedDeclaration.file)
                        ?.takeUnless { sameSourceUri(it, uri) }
                        ?: uri
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
                node.flatten().firstOrNull {
                    it.kind == CPlusAstKind.IDENTIFIER && it.fieldName == "declarator"
                } ?: node.flatten().lastOrNull { it.kind == CPlusAstKind.IDENTIFIER }
            else -> node.children.firstOrNull { it.kind == CPlusAstKind.IDENTIFIER }
                ?: node.flatten().firstOrNull { it.kind == CPlusAstKind.IDENTIFIER }
        }
    }

}

/**
 * Compare source identities across the representations used by LSP and the
 * compiler. Open documents are keyed by URIs, while mapped origins normally
 * carry canonical filesystem paths. Keeping this in one helper also makes
 * Windows drive-letter and URI normalization consistent for hit testing and
 * declaration locations.
 */
private fun sameSourceFile(left: String?, right: String?): Boolean {
    if (left == null || right == null) return left == right
    if (left == right) return true
    val leftUri = sourceUri(left) ?: return false
    val rightUri = sourceUri(right) ?: return false
    return sameSourceUri(leftUri, rightUri)
}

private fun originUri(file: String?): String? = file?.let(::sourceUri)

private fun sourceUri(file: String): String? {
    if (file.startsWith("file:", ignoreCase = true)) return file
    return runCatching { Path.of(file).toUri().toString() }.getOrNull()
}

private fun sameSourceUri(left: String, right: String): Boolean {
    if (left == right) return true
    val leftPath = runCatching { Path.of(URI(left)).toAbsolutePath().normalize() }.getOrNull()
    val rightPath = runCatching { Path.of(URI(right)).toAbsolutePath().normalize() }.getOrNull()
    if (leftPath == null || rightPath == null) return false
    val leftComparable = runCatching { leftPath.toRealPath() }.getOrDefault(leftPath)
    val rightComparable = runCatching { rightPath.toRealPath() }.getOrDefault(rightPath)
    return leftComparable == rightComparable
}

private fun CPlusAstNode.flatten(): Sequence<CPlusAstNode> = sequence {
    yield(this@flatten)
    children.forEach { yieldAll(it.flatten()) }
}

private fun CPlusAstNode.identifierOccurrences(
    ancestors: List<String> = emptyList()
): Sequence<LspIdentifierOccurrence> = sequence {
    if (kind == CPlusAstKind.IDENTIFIER) yield(LspIdentifierOccurrence(this@identifierOccurrences, ancestors))
    val nextAncestors = ancestors + syntaxKind
    children.forEach { child -> yieldAll(child.identifierOccurrences(nextAncestors)) }
}

private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
    sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }

private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_'

private fun String.endsWithAny(vararg suffixes: String): Boolean = suffixes.any(::endsWith)

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val IMPORT_LITERAL = Regex("\\\"((?:[^\\\"\\\\]|\\\\.)*)\\\"")
private val IMPORT_RECOVERY_LITERAL = Regex(
    "(?m)^\\s*(?:comptime\\s+)?(?:@import|import)\\s*(?:\\(\\s*)?\\\"((?:[^\\\"\\\\]|\\\\.)*)\\\""
)
private const val MAX_WORKSPACE_IMPORTS = 256
private const val MAX_TRACKED_REQUEST_IDS = 4096
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
