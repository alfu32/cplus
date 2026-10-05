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
    private val astDiagnosticProvider: (CPlusAst) -> List<ParserDiagnostic> = { it.unsupportedAstDiagnostics() },
    tracePath: Path? = System.getenv("CPLUS_LSP_TRACE")?.takeIf(String::isNotBlank)?.let { Path.of(it) },
    lifecyclePath: Path? = System.getenv("CPLUS_LSP_LIFECYCLE_FILE")
        ?.takeIf(String::isNotBlank)
        ?.let { Path.of(it) },
    private val configuredStdlibRoot: Path? = null,
    configuredTargetOs: String = CPlusTarget.hostOs()
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
    private val activeSemanticDocument = ThreadLocal<LspDocument?>()
    /** Optional method-level trace for host integration diagnostics; never touches protocol stdout. */
    private val tracePath = tracePath
    /** Optional process marker used only by isolated host-integration smoke tests. */
    private val lifecyclePath = lifecyclePath
    private val traceLock = Any()
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
    /** The URI spelling supplied by the editor; kept separate from real paths. */
    private var workspaceRootUri: String? = null
    private var targetOs: String = configuredTargetOs

    fun serve() {
        writeLifecycle("running")
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
            trace("in method=$method id=${id?.encode() ?: "notification"}")
            if (id != null && hasInvalidParameters(method, request)) {
                respondError(id, -32602, "invalid parameters for $method")
                continue
            }
            try { when (method) {
                "initialize" -> {
                    withWriteState { configureWorkspace(request) }
                    respond(id, "{\"capabilities\":{" +
                        "\"textDocumentSync\":1," +
                        "\"documentSymbolProvider\":true," +
                        "\"workspaceSymbolProvider\":true," +
                        "\"completionProvider\":{\"triggerCharacters\":[\".\",\">\",\"@\"]}," +
                        "\"signatureHelpProvider\":{\"triggerCharacters\":[\"(\",\",\"]}," +
                        "\"foldingRangeProvider\":true," +
                        "\"codeLensProvider\":{\"resolveProvider\":false}," +
                        "\"semanticTokensProvider\":{\"legend\":{\"tokenTypes\":[\"type\",\"struct\",\"enum\",\"typeParameter\",\"function\",\"method\",\"property\",\"variable\",\"parameter\",\"enumMember\",\"macro\"],\"tokenModifiers\":[\"declaration\"]},\"full\":true}," +
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
                        val old = documents.remove(uri)
                        parseSessions.remove(uri)
                        val path = pathFromUri(uri)
                        if (!importersByDocument[uri].isNullOrEmpty() && path != null && Files.isRegularFile(path)) {
                            val text = Files.readString(path, StandardCharsets.UTF_8)
                            documents[uri] = parseDocument(uri, old?.version ?: 0, sources.open(SourceId(uri), text))
                            refreshImports(uri)
                            refreshDependents(uri)
                        } else {
                            clearImports(uri)
                            refreshDependents(uri)
                        }
                        pruneUnreachableDocuments()
                        notify("textDocument/publishDiagnostics", "{\"uri\":${Json.string(uri)},\"diagnostics\":[]}")
                    }
                }
                "textDocument/didSave" -> request.uri()?.let { uri -> withWriteState { reloadDocument(uri) } }
                "workspace/didChangeWatchedFiles" -> withWriteState {
                    val params = request.values["params"] as? Json.Object
                    val changes = params?.values?.get("changes") as? Json.Array
                    changes?.values?.forEach { change ->
                        (change as? Json.Object)?.string("uri")?.let(::reloadDocument)
                    }
                }
                "workspace/didChangeConfiguration" -> withWriteState {
                    val params = request.values["params"] as? Json.Object
                    val settings = params?.values?.get("settings") as? Json.Object
                    val cplus = settings?.values?.get("cplus") as? Json.Object ?: settings
                    cplus?.string("targetOs")?.let { targetOs = it }
                    rebuildOpenDocuments()
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
                "textDocument/signatureHelp" -> dispatchReadRequest(id, request) { state ->
                    respondForDocument(id, request, state) { signatureHelp(request) }
                }
                "textDocument/foldingRange" -> dispatchReadRequest(id, request) { state ->
                    respondForDocument(id, request, state) { foldingRanges(request.uri()) }
                }
                "textDocument/codeLens" -> dispatchReadRequest(id, request) { state ->
                    respondForDocument(id, request, state) { testCodeLenses(request.uri()) }
                }
                "textDocument/semanticTokens/full" -> dispatchReadRequest(id, request) { state ->
                    respondForDocument(id, request, state) { semanticTokens(request.uri()) }
                }
                else -> if (id != null) respondError(id, -32601, "method not supported: $method")
            } } catch (error: Exception) {
                trace("dispatch failure method=$method error=$error")
                if (id != null) respondError(id, -32603, error.message ?: "language server operation failed")
                else notify("window/logMessage", "{\"type\":1,\"message\":${Json.string("$method failed: ${error.message}")}}")
            }
            }
        } finally {
            requestExecutor.shutdownNow()
            writeLifecycle("stopped")
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
                } catch (error: Exception) {
                    trace("request failure method=${request.string("method")} error=$error")
                    if (!state.cancelled.get()) respondError(id, -32603, error.message ?: "request failed")
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
        workspaceRootUri = request.rootUri()
        val root = request.rootUri()?.let(::pathFromUri)
        val project = root?.let(CPlusProject::find)
        val initialization = (request.values["params"] as? Json.Object)?.values?.get("initializationOptions") as? Json.Object
        initialization?.string("targetOs")?.let { targetOs = it }
        importPaths = if (project != null) {
            project.importPaths(configuredStdlibRoot, System.getenv("CPLUS_STDLIB"))
        } else {
            CPlusImportPaths(
                standardLibraryRoots = CPlusProject.defaultStandardLibraryRoots(configuredStdlibRoot, System.getenv("CPLUS_STDLIB")),
                moduleRoots = listOfNotNull(root)
            )
        }
    }

    private fun hasInvalidParameters(method: String, request: Json.Object): Boolean = when (method) {
        "textDocument/documentSymbol", "textDocument/foldingRange", "textDocument/codeLens",
        "textDocument/semanticTokens/full" -> request.uri() == null
        "textDocument/completion", "textDocument/hover", "textDocument/definition",
        "textDocument/references", "textDocument/documentHighlight", "textDocument/signatureHelp" ->
            request.uri() == null || request.position()?.let { it.line >= 0 && it.character >= 0 } != true
        else -> false
    }

    private fun updateDocument(update: LspDocumentUpdate, opened: Boolean = false): Boolean {
        val current = documents[update.uri]
        if (current != null && update.version != null && update.version <= current.version) return false
        val version = update.version ?: ((current?.version ?: 0) + 1)
        val snapshot = sources.open(SourceId(update.uri), update.text)
        if (opened) openDocuments += update.uri
        // Install the newest snapshot before evaluating imports, including cyclic imports.
        documents[update.uri] = LspDocument(update.uri, version, snapshot, snapshot.text, null,
            CPlusAst(snapshot, CPlusAstNode(CPlusAstKind.TRANSLATION_UNIT, "translation_unit",
                snapshot.sourceFile.span(0, snapshot.text.length), null, emptyList(), true, false, false),
                emptyList(), false))
        val parsed = parseDocument(update.uri, version, snapshot)
        documents[update.uri] = parsed
        publishDiagnostics(update.uri, parsed.diagnostics)
        return true
    }

    private fun parseDocument(uri: String, version: Int, snapshot: SourceSnapshot): LspDocument {
        val materialization = runCatching {
            materializeCPlusForTools(snapshot.sourceFile, importPaths, targetOs) { path ->
                documents.entries.firstOrNull { (candidate, _) ->
                    candidate in openDocuments && sameSourceFile(candidate, path.toString())
                }?.value?.snapshot?.sourceFile
            }
        }
        val materialized = materialization.getOrNull()?.takeIf { it.source.text != snapshot.text }
        val materializationDiagnostics = materialization.exceptionOrNull()?.let { error ->
            trace("materialization failure uri=$uri error=$error")
            listOf(ParserDiagnostic("CPLUS_TOOL_MATERIALIZATION", error.message ?: "comptime materialization failed",
                ParserDiagnosticSeverity.WARNING,
                (error as? CPlusSyntaxException)?.sourceSpan ?: snapshot.sourceFile.span(0, 0)))
        }.orEmpty()
        val parsedSnapshot = if (materialized == null) snapshot else
            sources.open(SourceId.named("$uri#comptime"), materialized.source.text)
        val options = CPlusParseOptions(editorMode = true, targetOs = targetOs)
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
        val semanticDiagnostics = withSemanticDocument(provisional) { ambiguousCallableDiagnostics(provisional) }
        val diagnostics = (parserDiagnostics + semanticDiagnostics).distinctBy {
            listOf(it.code, it.span.startOffset, it.span.endOffset, it.message)
        }.map { diagnostic ->
            if (materialized == null) diagnostic else diagnostic.copy(
                span = materialized.mapping.toOriginalSpan(diagnostic.span)
            )
        } + materializationDiagnostics
        return LspDocument(
            uri = uri,
            version = version,
            snapshot = snapshot,
            parsedText = parsedSnapshot.text,
            mappedSource = materialized?.mapping,
            ast = ast,
            diagnostics = diagnostics,
            editorAst = if (materialized == null) ast else CPlusAstAdapter().adapt(parser.parse(snapshot, options)),
            indexedSymbols = provisional.runtimeSymbols,
            scopedImports = scopedImportBindings(snapshot.text),
            activeImports = materialization.getOrNull()?.imports?.filter { sameSourceFile(it.importer.value, uri) }?.map { edge ->
                val span = edge.location
                val direct = if (sameSourceFile(span.file, uri) && span.startOffset >= 0 && span.endOffset <= snapshot.text.length)
                    importPath(snapshot.text.substring(span.startOffset, span.endOffset)) else null
                // Later expansion passes can move the edge's offset while the
                // graph still records its canonical destination. Recover the
                // original spelling only when it resolves to that actual edge.
                direct ?: originalImportRequests(snapshot.text).firstOrNull { requested ->
                    lexicalImportPath(pathFromUri(uri), requested)?.let { sameSourceFile(it.toString(), edge.imported.value) } == true
                } ?: edge.imported.value
            }
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
                // Keep the URI spelling supplied by the editor for protocol
                // locations.  The shared resolver may canonicalize through a
                // symlink (notably macOS' /var -> /private/var mapping), which
                // is correct for filesystem identity but undesirable for an
                // editor-facing document identity.
                val lexical = lexicalImportPath(pathFromUri(document.uri), requested)
                val importedPath = lexical ?: resolved
                // Relative imports retain the exact URI namespace supplied by
                // the editor.  Converting the source URI to a host Path and
                // back can change drive-letter, symlink, or separator spelling
                // on Windows and macOS even though it identifies the same
                // file.  The resolved Path remains authoritative for reading;
                // this URI is only the LSP document identity.
                val importedUri = lexicalImportUri(document.uri, requested)
                    ?: importedPath.toUri().toString()
                importsByDocument.getOrPut(currentUri) { LinkedHashSet() }.add(importedUri)
                importersByDocument.getOrPut(importedUri) { LinkedHashSet() }.add(currentUri)
                trace("import current=$currentUri requested=$requested resolved=$resolved imported=$importedUri")
                if (documents.containsKey(importedUri)) {
                    pending += importedUri
                    continue
                }
                val text = runCatching { Files.readString(resolved, StandardCharsets.UTF_8) }.getOrNull() ?: continue
                // Do not pass the path through SourceId.named/fromPath here:
                // those constructors intentionally canonicalize existing
                // files for compiler graph identity.  LSP snapshots need the
                // lexical URI so mapped definitions can be returned to the
                // client using the same path it opened/imported.
                val snapshot = sources.open(SourceId(importedUri), text)
                val imported = parseDocument(importedUri, 0, snapshot)
                documents[importedUri] = imported
                publishDiagnostics(importedUri, imported.diagnostics)
                pending += importedUri
            }
        }
        pruneUnreachableDocuments()
    }

    /** Re-materialize the transitive dependent closure from current editor snapshots. */
    private fun refreshDependents(uri: String) {
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque<String>()
        pending += uri
        while (pending.isNotEmpty()) {
            val changed = pending.removeFirst()
            if (!visited.add(changed)) continue
            for (dependent in importersByDocument[changed].orEmpty().toList()) {
                if (dependent == uri || dependent in visited) continue
                documents[dependent]?.let { document ->
                    val reparsed = parseDocument(dependent, document.version, document.snapshot)
                    documents[dependent] = reparsed
                    refreshImports(dependent)
                    publishDiagnostics(dependent, reparsed.diagnostics)
                    pending += dependent
                }
            }
        }
    }

    private fun reloadDocument(uri: String) {
        if (uri in openDocuments) {
            refreshImports(uri)
            refreshDependents(uri)
            return
        }
        val previous = documents[uri] ?: return
        val path = pathFromUri(uri) ?: return
        if (Files.isRegularFile(path)) {
            val text = Files.readString(path, StandardCharsets.UTF_8)
            documents[uri] = parseDocument(uri, previous.version + 1, sources.open(SourceId(uri), text))
            publishDiagnostics(uri, documents.getValue(uri).diagnostics)
            refreshImports(uri)
        } else {
            documents.remove(uri)
            parseSessions.remove(uri)
            clearImports(uri)
        }
        refreshDependents(uri)
        pruneUnreachableDocuments()
    }

    private fun rebuildOpenDocuments() {
        for (uri in openDocuments.toList()) {
            val previous = documents[uri] ?: continue
            val next = parseDocument(uri, previous.version, previous.snapshot)
            documents[uri] = next
            refreshImports(uri)
            publishDiagnostics(uri, next.diagnostics)
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
            CPlusImportResolver(importPaths, ::sourceAvailable).resolve(
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
            .firstOrNull(::sourceAvailable)
    }

    private fun sourceAvailable(path: Path): Boolean = Files.isRegularFile(path) ||
        openDocuments.any { sameSourceFile(it, path.toString()) }

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
                if (resolved.startsWith(normalizedRoot) && sourceAvailable(resolved)) return resolved
            }
        }
        return null
    }

    private fun lexicalImportUri(documentUri: String, requested: String): String? {
        if (!documentUri.startsWith("file:", ignoreCase = true)) return null
        return runCatching {
            if (!requested.startsWith("stdlib:/") &&
                !requested.startsWith("module:/") &&
                !requested.startsWith("project:/")
            ) {
                // URI resolution preserves the exact directory spelling sent by
                // the editor.  Do not round-trip through Path: on macOS that can
                // turn /var into /private/var, and on Windows it can alter the
                // drive-letter spelling.
                return@runCatching fileUri(URI(documentUri).resolve(requested).normalize())
            }

            val sourcePath = pathFromUri(documentUri) ?: return@runCatching null
            val importedPath = lexicalImportPath(sourcePath, requested) ?: return@runCatching null
            val rootUri = workspaceRootUri ?: return@runCatching importedPath.toUri().toString()
            val workspacePath = pathFromUri(rootUri) ?: return@runCatching importedPath.toUri().toString()
            val projectRoot = CPlusProject.find(workspacePath)?.root ?: workspacePath
            val moduleRoot = importPaths.moduleRoots.firstOrNull {
                importedPath.startsWith(it.toAbsolutePath().normalize())
            } ?: return@runCatching importedPath.toUri().toString()
            val projectRelativeRoot = projectRoot.relativize(moduleRoot.toAbsolutePath().normalize())
            val moduleRelativeFile = moduleRoot.toAbsolutePath().normalize().relativize(importedPath)
            val relative = projectRelativeRoot.resolve(moduleRelativeFile)
                .toString().replace(java.io.File.separatorChar, '/')
            fileUri(URI(rootUri).resolve(relative))
        }.getOrNull()
    }

    /** Normalize only the URI syntax, never the lexical filesystem path. */
    private fun fileUri(uri: URI): String {
        if (!uri.scheme.equals("file", ignoreCase = true) || uri.rawAuthority != null) {
            return uri.toString()
        }
        return "file://${uri.rawPath}"
    }

    private fun importPath(text: String): String? = IMPORT_LITERAL.find(text)?.groupValues?.get(1)?.let(::unescapeImport)

    private fun scopedImportBindings(text: String): List<LspImportBinding> =
        SCOPED_IMPORT.findAll(text)
            .filter { match ->
                val masked = maskToolingText(text)
                masked.getOrNull(match.range.first) != ' '
            }
            .map { match ->
                val path = unescapeImport(match.groupValues[1])
                val alias = match.groupValues[2]
                val aliasStart = match.groups[2]?.range?.first ?: match.range.first
                LspImportBinding(
                    alias = alias,
                    path = path,
                    importSpan = SourceFile(text).span(match.range.first, match.range.last + 1),
                    aliasSpan = SourceFile(text).span(aliasStart, aliasStart + alias.length)
                )
            }
            .toList()

    private fun importRequests(document: LspDocument): List<String> {
        document.activeImports?.let { return it.distinct() }
        val indexed = comptimeIndexer.index(document.editorAst).imports
            .mapNotNull { span -> importPath(document.snapshot.text.substring(span.startOffset, span.endOffset)) }
        // Keep the small lexical recovery boundary additive rather than using
        // it only when the AST index is empty. Native Tree-sitter hosts can
        // preserve an import as a recovery node, or expose a partial span,
        // while still returning other indexed comptime constructs. Import
        // discovery must therefore be host-independent during the migration.
        val recovered = originalImportRequests(document.snapshot.text)
        return (indexed + recovered).distinct()
    }

    private fun originalImportRequests(text: String): List<String> {
        val masked = maskToolingText(text)
        return IMPORT_RECOVERY_LITERAL.findAll(text).filter { masked[it.range.first] != ' ' }
            .map { unescapeImport(it.groupValues[1]) }.distinct().toList()
    }

    private fun pathFromUri(value: String): Path? = runCatching {
        if (value.startsWith("file:", ignoreCase = true)) Path.of(URI(value)) else Path.of(value)
    }.getOrNull()

    private fun unescapeImport(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    private fun publishDiagnostics(uri: String, diagnostics: List<ParserDiagnostic>) {
        // An imported origin is not a range in the requesting document. Its
        // own snapshot publishes that diagnostic; never underline a random
        // root line with an imported file's offsets.
        val encoded = diagnostics.filter { it.span.file == null || sameSourceFile(it.span.file, uri) }
            .joinToString(",") { diagnostic ->
            val span = diagnostic.span
            "{\"range\":{\"start\":{\"line\":${span.startLine - 1},\"character\":${span.startColumn - 1}}," +
                "\"end\":{\"line\":${span.endLine - 1},\"character\":${span.endColumn - 1}}}," +
                "\"severity\":${if (diagnostic.severity.name == "WARNING") 2 else 1}," +
                "\"code\":${Json.string(diagnostic.code)},\"source\":\"c-plus\",\"message\":${Json.string(diagnostic.message)}}"
        }
        val version = documents[uri]?.version?.let { ",\"version\":$it" }.orEmpty()
        notify("textDocument/publishDiagnostics", "{\"uri\":${Json.string(uri)},\"diagnostics\":[$encoded]$version}")
    }

    private fun documentSymbols(uri: String?): String {
        val document = uri?.let(readDocuments()::get) ?: return "[]"
        return document.symbolTreeJson
    }

    /** Original AST roles plus scoped declarations, never generated offsets or a name-only global scan. */
    private fun semanticTokens(uri: String?): String {
        val document = uri?.let(readDocuments()::get) ?: return "{\"data\":[]}"
        val byName = visibleSymbols(document).groupBy { it.name }
        val parameterSelections = document.editorAst.root.descendantsAndSelf()
            .filter { it.kind == CPlusAstKind.PARAMETER }
            .flatMap { it.declaredIdentifiers().asSequence() }
            .map { it.span.startOffset }.toSet()
        val encoded = mutableListOf<Int>()
        var previousLine = 0
        var previousColumn = 0
        val identifiers = document.editorAst.root.descendantsAndSelf()
            .filter { it.syntaxKind in setOf("identifier", "type_identifier", "field_identifier") &&
                it.span.endOffset > it.span.startOffset && it.span.startLine == it.span.endLine }
            .distinctBy { it.span.startOffset }.sortedBy { it.span.startOffset }
        for (node in identifiers) {
            val name = document.snapshot.text.substring(node.span.startOffset, node.span.endOffset)
            val candidates = byName[name].orEmpty().filter {
                !it.local || sameSourceUri(it.uri, document.uri) && it.scope.contains(node.span.startOffset) &&
                    it.selection.startOffset <= node.span.startOffset
            }
            val declaration = candidates.firstOrNull {
                sameSourceUri(it.uri, document.uri) && it.selection.startOffset == node.span.startOffset
            }
            val symbol = declaration ?: candidates.filter { it.local }.minByOrNull { it.scope.size() }
                ?: candidates.takeIf { it.map { candidate -> candidate.kind }.distinct().size == 1 }?.firstOrNull()
            val kind = when {
                symbol?.kind == 23 -> 1
                symbol?.kind == 10 -> 2
                symbol?.kind == 26 || node.syntaxKind == "type_identifier" -> 0
                symbol?.kind == 12 -> 4
                symbol?.kind == 6 -> 5
                symbol?.kind == 8 || node.syntaxKind == "field_identifier" -> 6
                symbol?.kind == 22 -> 9
                symbol?.kind == 13 && sameSourceUri(symbol.uri, document.uri) &&
                    symbol.selection.startOffset in parameterSelections -> 8
                symbol?.kind == 13 -> 7
                else -> continue // unresolved ordinary identifiers retain lexical highlighting
            }
            val line = node.span.startLine - 1
            val column = node.span.startColumn - 1
            val deltaLine = line - previousLine
            encoded += listOf(deltaLine, if (deltaLine == 0) column - previousColumn else column,
                node.span.size(), kind, if (declaration != null) 1 else 0)
            previousLine = line
            previousColumn = column
        }
        return "{\"data\":[${encoded.joinToString(",") }]}"
    }

    private fun workspaceSymbols(query: String): String {
        val normalized = query.trim().lowercase()
        return readDocuments().values.asSequence()
            .flatMap { document -> document.symbols.asSequence().map { displaySymbol(it, document.uri) } }
            .filter { normalized.isEmpty() || it.name.lowercase().contains(normalized) }
            .filter { !it.local }
            .distinctBy { listOf(it.uri, it.name, it.kind, it.selection.startOffset, it.detail) }
            .sortedWith(compareBy<LspSymbol> { it.name.lowercase() }.thenBy { it.uri }.thenBy { it.selection.startOffset })
            .joinToString(",", "[", "]") { it.toJson() }
    }

    private fun completions(request: Json.Object): String {
        val document = request.uri()?.let(readDocuments()::get) ?: return "{\"isIncomplete\":false,\"items\":[]}"
        val prefix = document.wordAt(request.position())
        val offset = document.snapshot.offsetAt(request.position())
        val scopedTarget = offset?.let { scopedImportTarget(document, it) }
        if (scopedTarget != null) {
            val names = scopedTarget.symbols.asSequence()
                .filter { it.ownerName == null && !it.local }
                .filter { prefix.isEmpty() || it.name.startsWith(prefix) }
                .distinctBy { it.name }
                .sortedBy { it.name }
                .joinToString(",", "[", "]") { symbol ->
                    "{\"label\":${Json.string(symbol.name)},\"kind\":${symbol.completionKind()}," +
                        "\"detail\":${Json.string(symbol.toolingDetail())}}"
                }
            return "{\"isIncomplete\":false,\"items\":$names}"
        }
        val receiverAccess = offset?.let { receiverAccess(document, it) }
        if (receiverAccess?.typeName == null && receiverAccess != null) {
            return "{\"isIncomplete\":false,\"items\":[]}"
        }
        val receiver = receiverAccess?.typeName
        val visible = visibleSymbols(document).toList()
        trace(
            "completion uri=${document.uri} prefix=$prefix visible=" +
                visible.joinToString(",") { it.name + "@" + it.uri }
        )
        val receiverScopes = receiver?.let { typeName ->
            visible.filter { it.name == typeName && it.kind == 23 }.map { it.scope }
        }.orEmpty()
        val symbols = visible.asSequence().filter { symbol ->
            if (receiver != null) symbol.ownerName?.let(::resolveTypeAlias) == receiver ||
                (symbol.kind in setOf(6, 8) && symbol.name != receiver &&
                    receiverScopes.any { it.contains(symbol.selection) })
            else symbol.ownerName == null && (!symbol.local ||
                (symbol.uri == document.uri && offset != null && symbol.scope.contains(offset) &&
                    symbol.selection.startOffset <= offset))
        }
        val keywordNames = if (receiver == null) CPLUS_KEYWORDS.asSequence() else emptySequence()
        val names = (keywordNames + symbols.map { it.name })
            .filter { prefix.isEmpty() || it.startsWith(prefix) }
            .distinct()
            .sorted()
        val items = names.joinToString(",", "[", "]") { name ->
            val symbol = symbols.firstOrNull { it.name == name }
            val detail = symbol?.toolingDetail()
            "{\"label\":${Json.string(name)},\"kind\":${if (name in CPLUS_KEYWORDS) 14 else symbol?.completionKind() ?: 6}" +
                (detail?.let { ",\"detail\":${Json.string(it)}" } ?: "") + "}"
        }
        return "{\"isIncomplete\":false,\"items\":$items}"
    }

    private fun hover(request: Json.Object): String {
        val document = request.uri()?.let(readDocuments()::get) ?: return "null"
        val symbol = symbolAt(request) ?: return "null"
        val value = "```c\n${symbol.toolingDetail()}\n```"
        val span = document.wordSpan(request.position()) ?: return "null"
        return "{\"contents\":{\"kind\":\"markdown\",\"value\":${Json.string(value)}},\"range\":${span.toRangeJson()}}"
    }

    private fun definition(request: Json.Object): String {
        val symbol = symbolAt(request) ?: return "[]"
        return "[{\"uri\":${Json.string(symbol.uri)},\"range\":${symbol.selection.toRangeJson()}}]"
    }

    /** Signatures are declaration-backed; unresolved/ambiguous calls are not fabricated. */
    private fun signatureHelp(request: Json.Object): String {
        val document = request.uri()?.let(readDocuments()::get) ?: return "null"
        val offset = document.snapshot.offsetAt(request.position()) ?: return "null"
        val text = document.snapshot.text
        val masked = maskToolingText(text)
        var depth = 0
        var open = -1
        for (index in (offset - 1) downTo 0) {
            when (masked[index]) {
                ')' -> depth++
                '(' -> if (depth == 0) { open = index; break } else depth--
            }
        }
        if (open < 0) return "null"
        val match = Regex("($IDENTIFIER)\\s*$").find(masked.substring(0, open)) ?: return "null"
        val name = match.groupValues[1]
        val receiver = receiverType(document, match.range.first)
        val candidates = visibleSymbols(document).filter {
            it.name == name && it.kind in setOf(6, 12) &&
                if (receiver == null) it.ownerName == null else it.ownerName == resolveTypeAlias(receiver)
        }.toList()
        if (candidates.isEmpty()) return "null"
        val arguments = splitCallArguments(text.substring(open + 1, offset))
        val active = if (text.substring(open + 1, offset).isBlank()) 0 else arguments.size - 1
        val signatures = candidates.joinToString(",", "[", "]") { symbol ->
            val labels = parameterLabels(symbol).let { if (isInstanceMethod(symbol)) it.drop(1) else it }
            "{\"label\":${Json.string(symbol.detail)},\"parameters\":[" +
                labels.joinToString(",") { "{\"label\":${Json.string(it)}}" } + "]}"
        }
        val selected = selectCallable(document, candidates, arguments)
        val selectedIndex = candidates.indexOf(selected).coerceAtLeast(0)
        return "{\"signatures\":$signatures,\"activeSignature\":$selectedIndex,\"activeParameter\":$active}"
    }

    private fun parameterLabels(symbol: LspSymbol): List<String> {
        val open = symbol.detail.indexOf('(')
        val close = matchingClosingParen(symbol.detail, open)
        if (close < 0) return emptyList()
        val text = symbol.detail.substring(open + 1, close).trim()
        return if (text == "void") emptyList() else splitCallArguments(text)
    }

    private fun foldingRanges(uri: String?): String {
        val document = uri?.let(readDocuments()::get) ?: return "[]"
        return document.editorAst.root.flatten().filter {
            it.kind in setOf(CPlusAstKind.BLOCK, CPlusAstKind.FIELD_LIST, CPlusAstKind.ENUMERATOR_LIST,
                CPlusAstKind.COMMENT, CPlusAstKind.PREPROCESSOR) && it.span.endLine > it.span.startLine
        }.distinctBy { it.span.startLine to it.span.endLine }.joinToString(",", "[", "]") {
            "{\"startLine\":${it.span.startLine - 1},\"endLine\":${it.span.endLine - 1}," +
                "\"kind\":${Json.string(if (it.kind == CPlusAstKind.COMMENT) "comment" else "region")}}"
        }
    }

    private fun testCodeLenses(uri: String?): String {
        val document = uri?.let(readDocuments()::get) ?: return "[]"
        return document.editorAst.root.flatten().filter { it.kind == CPlusAstKind.TEST }
            .mapNotNull { node ->
                val header = document.snapshot.text.substring(node.span.startOffset, node.span.endOffset).substringBefore('{')
                val spelling = header.removePrefix("@test").trim()
                val name = if (spelling.startsWith('"')) {
                    (runCatching { Json.parse(spelling) }.getOrNull() as? Json.StringValue)?.value
                } else spelling.takeIf { it.isNotEmpty() }
                name?.let {
                    "{\"range\":${node.span.toRangeJson()},\"command\":{\"title\":\"Run test\"," +
                        "\"command\":\"cplus.runTest\",\"arguments\":[${Json.string(document.uri)},${Json.string(it)}]}}"
                }
            }.joinToString(",", "[", "]")
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
            .distinctBy { it.uri to it.span.startOffset }
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
            .filter { sameSourceUri(it.uri, document.uri) }
            .distinctBy { it.span.startOffset }
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
                val astOccurrences = candidate.ast.root.identifierOccurrences()
                    .filter { it.node.kind == CPlusAstKind.IDENTIFIER }
                // A recovery tree can drop a type declaration's use together
                // with the damaged sibling that follows it (for example
                // `value.` in an incomplete member expression).  Type aliases
                // still have a stable lexical identity, so recover only this
                // bounded type-reference slice from code text.  Strings and
                // comments are masked by the scanner below; ordinary callable
                // references remain AST-only and therefore conservative.
                val lexicalOccurrences = if (selected?.kind in TYPE_SYMBOL_KINDS) {
                    lexicalIdentifierOccurrences(candidate, target)
                        .filter { lexical ->
                            astOccurrences.none { ast ->
                                ast.node.span.startOffset == lexical.node.span.startOffset
                            }
                        }
                } else {
                    emptySequence()
                }
                sequenceOf(astOccurrences, lexicalOccurrences).flatten()
                    .filter { node ->
                        val text = candidate.parsedText.substring(node.node.span.startOffset, node.node.span.endOffset)
                        text == target
                    }
                    .map { occurrence ->
                        val node = occurrence.node
                        val mapped = candidate.mappedSource?.toOriginalSpan(node.span) ?: node.span
                        val origin = mapped.file?.let { file ->
                            if (file.startsWith("file:", ignoreCase = true)) file
                            else runCatching { Path.of(file).toUri().toString() }.getOrNull()
                        } ?: candidate.uri
                        val uri = readDocuments().keys.firstOrNull { sameSourceUri(it, origin) } ?: origin
                        LspReference(
                            uri,
                            mapped,
                            occurrence.copy(callArity = callArity(candidate.parsedText, node.span.startOffset)),
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

        val text = readDocuments()[reference.uri]?.snapshot?.text ?: reference.document.snapshot.text
        val start = reference.span.startOffset.coerceIn(0, text.length)
        val end = reference.span.endOffset.coerceIn(start, text.length)
        val beforeText = text.substring(0, start)
        val before = beforeText.takeLast(3)
        val after = text.substring(end).dropWhile { it.isWhitespace() }
        // In `*pointer = value`, the assignment mutates the pointee; the
        // pointer variable itself is read to locate that pointee.  The
        // lexical assignment check below must not turn that read into a
        // write highlight.  Keep this deliberately narrow: a leading `*`
        // immediately before the identifier is the unary-dereference form,
        // while `pointer->field = value` already has no assignment directly
        // after the pointer identifier.
        val unaryDereference = beforeText.trimEnd().endsWith('*') &&
            !beforeText.trimEnd().endsWith("**")
        val compoundAssignment = Regex("^(?:\\+|-|\\*|/|%|&|\\||\\^|<<|>>)=").containsMatchIn(after)
        val increment = before.trimEnd().endsWith("++") || before.trimEnd().endsWith("--") ||
            after.trimStart().startsWith("++") || after.trimStart().startsWith("--")
        val assignment = !unaryDereference && after.startsWith("=") && !after.startsWith("==")
        return if (compoundAssignment || increment || assignment) 3 else 2
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
                val receiver = receiverType(reference.document, span.startOffset)
                if (receiver != null && resolveTypeAlias(receiver) != selected.ownerName) return false
            }
            val callArity = occurrence.callArity
            // C-plus instance calls omit the implicit receiver even though the
            // lowered declaration contains it as the first parameter.
            val selectedArity = callParameterArity(selected)
            if (callSite && callArity != null && selectedArity != null && callArity != selectedArity) return false
            val arguments = callArguments(reference.document.parsedText, occurrence.node.span.startOffset)
            if (arguments != null) {
                val candidates = visibleSymbols(reference.document).filter {
                    it.name == target && it.kind == selected.kind && it.ownerName == selected.ownerName
                }.toList()
                val resolved = selectCallable(reference.document, candidates, arguments) ?: return false
                return resolved.uri == selected.uri && resolved.selection.startOffset == selected.selection.startOffset
            }
            return true
        }
        if (selected.kind == 8 && selected.ownerName != null) {
            // Field references are selected by the receiver's resolved owner;
            // falling back to declaration order would leak same-named fields.
            val receiver = receiverType(reference.document, span.startOffset)
            return receiver != null && resolveTypeAlias(receiver) == selected.ownerName
        }
        if (selected.kind in TYPE_SYMBOL_KINDS && occurrence.node.syntaxKind == "type_identifier") {
            val shadow = visibleSymbols(reference.document).filter {
                it.name == target && it.uri == uri && it.scope.contains(span) &&
                    it.selection.startOffset <= span.startOffset
            }.minWithOrNull(compareBy<LspSymbol> { it.scope.size() }.thenByDescending { it.selection.startOffset })
            return shadow == null || shadow.kind in TYPE_SYMBOL_KINDS && shadow.uri == selected.uri &&
                shadow.selection.startOffset == selected.selection.startOffset
        }
        val scoped = declarations
            .filter { it.uri == uri && it.scope.contains(span) && it.selection.startOffset <= span.startOffset }
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
        scopedImportMember(document, offset, word)?.let { return it }
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
        val compatibleCandidates = if (callArguments == null) receiverCandidates else {
            receiverCandidates.filter { callableAcceptsArguments(document, it, callArguments) }
        }
        if (callArguments != null && isAmbiguousCallable(document, compatibleCandidates, callArguments)) return null
        if (receiverTypeName != null) compatibleCandidates.firstOrNull()?.let { return it }
        val scopedCandidates = compatibleCandidates
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
        val precedingCandidates = compatibleCandidates
            .filter { it.uri == document.uri && it.selection.startOffset <= offset }
        return if (callArguments != null) precedingCandidates.firstOrNull()
            ?: compatibleCandidates.firstOrNull()
        else precedingCandidates.maxByOrNull { it.selection.startOffset }
            ?: compatibleCandidates.firstOrNull()
    }

    /** Returns the imported module member under the cursor for `alias.member`. */
    private fun scopedImportMember(document: LspDocument, offset: Int, word: String): LspSymbol? {
        var memberStart = offset.coerceAtMost(document.snapshot.text.length)
        while (memberStart > 0 && document.snapshot.text[memberStart - 1].isIdentifierPart()) memberStart--
        val receiver = Regex("\\b($IDENTIFIER)\\s*\\.\\s*$")
            .find(document.snapshot.text.substring(0, memberStart))?.groupValues?.get(1)
            ?: return null
        val binding = document.scopedImports.firstOrNull { it.alias == receiver } ?: return null
        val target = scopedImportTarget(document, binding) ?: return null
        return target.symbols.asSequence()
            .filter { it.name == word && it.ownerName == null && !it.local }
            .map { displaySymbol(it, target.uri) }
            .minByOrNull { it.selection.startOffset }
    }

    /** Resolves the namespace before the cursor in `alias.` for completion. */
    private fun scopedImportTarget(document: LspDocument, offset: Int): LspDocument? {
        var memberStart = offset.coerceAtMost(document.snapshot.text.length)
        while (memberStart > 0 && document.snapshot.text[memberStart - 1].isIdentifierPart()) memberStart--
        val receiver = Regex("\\b($IDENTIFIER)\\s*\\.\\s*$")
            .find(document.snapshot.text.substring(0, memberStart))?.groupValues?.get(1)
            ?: return null
        val binding = document.scopedImports.firstOrNull { it.alias == receiver } ?: return null
        return scopedImportTarget(document, binding)
    }

    private fun scopedImportTarget(document: LspDocument, binding: LspImportBinding): LspDocument? {
        val importedUri = lexicalImportUri(document.uri, binding.path)
            ?: resolveImport(document, binding.path)?.toUri()?.toString()
        return readDocuments().entries.firstOrNull { (uri, _) ->
            importedUri != null && sameSourceUri(uri, importedUri)
        }?.value
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

    private fun callableAcceptsArguments(
        document: LspDocument,
        symbol: LspSymbol,
        arguments: List<String>
    ): Boolean {
        if (callParameterTypes(symbol) == null) return true
        return overloadScore(document, symbol, arguments) != null
    }

    private fun callArity(text: String, offset: Int): Int? {
        return callArguments(text, offset)?.size
    }

    private fun callArguments(text: String, offset: Int): List<String>? {
        val masked = maskToolingText(text)
        var cursor = offset.coerceIn(0, text.length)
        while (cursor < text.length && text[cursor].isIdentifierPart()) cursor++
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        if (cursor >= text.length || text[cursor] != '(') return null
        var depth = 0
        var start = cursor + 1
        val arguments = mutableListOf<String>()
        var index = cursor + 1
        while (index < text.length) {
            when (masked[index]) {
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
        var total = 0
        parameters.zip(arguments).forEach { (parameter, argument) ->
            val match = typeMatchScore(document, parameter, argument)
            if (!match.compatible) return null
            total += match.score
        }
        return total
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

    private fun typeMatchScore(document: LspDocument, expected: TypeShape, argument: String): TypeMatch {
        val actual = expressionType(document, argument) ?: return TypeMatch(50, true)
        val expectedBase = resolveTypeAlias(expected.base)
        val actualBase = resolveTypeAlias(actual.base)
        if (expected.callableArity != null) {
            return when {
                actual.callableArity == expected.callableArity &&
                    (expected.callableReturnBase == null ||
                        resolveTypeAlias(expected.callableReturnBase) == actualBase) -> TypeMatch(0, true)
                actual.callableArity != null -> TypeMatch(35, true)
                else -> TypeMatch(45, false)
            }
        }
        if (actual.callableArity != null) return TypeMatch(35, false)
        val expectedPointer = expected.pointerDepth > 0
        val actualPointer = actual.pointerDepth > 0
        val expectedArray = expected.arrayDepth > 0
        val actualArray = actual.arrayDepth > 0
        if (expectedPointer || expectedArray || actualPointer || actualArray) {
            val qualificationCompatible =
                (!actual.pointeeConst || expected.pointeeConst) &&
                    (!actual.pointeeVolatile || expected.pointeeVolatile)
            if (expectedBase == actualBase && expected.pointerDepth == actual.pointerDepth &&
                expected.arrayDepth == actual.arrayDepth && qualificationCompatible
            ) {
                val qualificationCost =
                    if (expected.pointeeConst && !actual.pointeeConst) 1 else 0
                val volatileCost =
                    if (expected.pointeeVolatile && !actual.pointeeVolatile) 1 else 0
                return TypeMatch(qualificationCost + volatileCost, true)
            }
            if (expected.pointerDepth == 1 && expected.arrayDepth == 0 &&
                actual.pointerDepth == 0 && actual.arrayDepth == 1 && expectedBase == actualBase
                && qualificationCompatible
            ) return TypeMatch(
                2 + (if (expected.pointeeConst && !actual.pointeeConst) 1 else 0) +
                    (if (expected.pointeeVolatile && !actual.pointeeVolatile) 1 else 0),
                true
            )
            if (expected.pointerDepth == 1 && expected.arrayDepth > 0 &&
                actual.pointerDepth == 0 && actual.arrayDepth == expected.arrayDepth + 1 &&
                expectedBase == actualBase && qualificationCompatible
            ) return TypeMatch(
                2 + (if (expected.pointeeConst && !actual.pointeeConst) 1 else 0) +
                    (if (expected.pointeeVolatile && !actual.pointeeVolatile) 1 else 0),
                true
            )
            if (expected.pointerDepth == 1 && expected.arrayDepth == 0 &&
                actual.pointerDepth == 1 && actual.arrayDepth == 0 &&
                (expectedBase == "void" || actualBase == "void") &&
                qualificationCompatible
            ) return TypeMatch(
                8 + (if (expected.pointeeConst && !actual.pointeeConst) 1 else 0) +
                    (if (expected.pointeeVolatile && !actual.pointeeVolatile) 1 else 0),
                true
            )
            return TypeMatch(0, false)
        }
        if (expectedBase == actualBase) return TypeMatch(0, true)
        numericConversionCost(expectedBase, actualBase)?.let { return TypeMatch(5 + it, true) }
        return TypeMatch(0, false)
    }

    private fun numericConversionCost(expected: String, actual: String): Int? {
        val expectedRank = numericRank(expected) ?: return null
        val actualRank = numericRank(actual) ?: return null
        val actualPromotedRank = if (actualRank <= INTEGER_PROMOTION_MAX_RANK) INT_RANK else actualRank

        // C's integer promotions happen before the usual arithmetic conversions.
        // Keep this as a ranking hint for tooling; the host compiler remains
        // authoritative for whether a particular conversion is legal.
        if (actualRank <= INTEGER_PROMOTION_MAX_RANK) {
            return when {
                expectedRank == INT_RANK -> 1
                expectedRank in INT_RANK..LONG_LONG_RANK -> 1 + expectedRank - INT_RANK
                expectedRank > LONG_LONG_RANK -> 4 + expectedRank - LONG_LONG_RANK
                else -> 20 + kotlin.math.abs(expectedRank - actualPromotedRank)
            }
        }
        if (actualRank == FLOAT_RANK && expectedRank == DOUBLE_RANK) return 1
        if (expectedRank >= actualRank) return 2 + expectedRank - actualRank
        return 20 + actualRank - expectedRank
    }

    private fun numericRank(typeName: String): Int? = when (typeName
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()) {
        "bool", "_bool" -> 0
        "char", "signed char", "unsigned char", "signed" -> 1
        "short", "short int", "signed short", "signed short int", "unsigned short", "unsigned short int" -> 2
        "int", "signed int", "unsigned", "unsigned int" -> INT_RANK
        "long", "long int", "signed long", "signed long int", "unsigned long", "unsigned long int" -> 4
        "long long", "long long int", "signed long long", "signed long long int",
        "unsigned long long", "unsigned long long int" -> LONG_LONG_RANK
        "float" -> FLOAT_RANK
        "double", "long double" -> DOUBLE_RANK
        else -> null
    }

    private companion object {
        const val INTEGER_PROMOTION_MAX_RANK = 2
        const val INT_RANK = 3
        const val LONG_LONG_RANK = 5
        const val FLOAT_RANK = 6
        const val DOUBLE_RANK = 7
    }

    private fun typeShape(declaration: String): TypeShape? {
        val firstPointer = declaration.indexOf('*')
        val pointeeConst = firstPointer >= 0 &&
            Regex("\\bconst\\b").containsMatchIn(declaration.substring(0, firstPointer))
        val pointeeVolatile = firstPointer >= 0 &&
            Regex("\\bvolatile\\b").containsMatchIn(declaration.substring(0, firstPointer))
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
                callableReturnBase = callable.groupValues[1],
                pointeeConst = pointeeConst,
                pointeeVolatile = pointeeVolatile
            )
        }
        val pointerDepth = cleaned.count { it == '*' }
        val arrayDepth = Regex("\\[[^]]*\\]").findAll(cleaned).count()
        val base = Regex("(?:(?:unsigned|signed)\\s+)?(?:char|short|int|long(?:\\s+long)?|float|double|bool|_Bool)")
            .find(cleaned)?.value
            ?: Regex("(?:struct\\s+|union\\s+|enum\\s+)?([A-Za-z_][A-Za-z0-9_]*)")
                .find(cleaned)?.groupValues?.get(1)
            ?: return null
        val alias = activeSemanticDocument.get()?.let { visibleSymbols(it) }
            ?.firstOrNull { it.kind == 26 && it.name == base }?.resolvedType
        return TypeShape(
            alias?.name ?: base,
            pointerDepth + (alias?.pointerDepth ?: 0),
            arrayDepth + (alias?.declaratorLayers?.count { it == CPlusDeclaratorLayer.ARRAY } ?: 0),
            pointeeConst = pointeeConst,
            pointeeVolatile = pointeeVolatile
        )
    }

    private fun expressionType(document: LspDocument, expression: String): TypeShape? {
        val value = expression.trim()
        if (value.length >= 2 && value.first() == '(' && value.last() == ')' &&
            matchingOuterParentheses(value)
        ) {
            return expressionType(document, value.substring(1, value.length - 1))
        }
        val cast = Regex("^\\(([^()]*)\\)(.+)$").matchEntire(value)
        if (cast != null) {
            typeShape(cast.groupValues[1])?.let { return it }
        }
        val call = callParts(value)
        if (call != null) {
            functionCallReturnType(
                document,
                call.first,
                document.snapshot.text.indexOf(value).coerceAtLeast(0),
                call.second
            )?.let { returned ->
                return TypeShape(
                    resolveTypeAlias(returned.typeName),
                    returned.pointerDepth,
                    returned.arrayDepth,
                    pointeeConst = returned.pointeeConst,
                    pointeeVolatile = returned.pointeeVolatile
                )
            }
        }
        val subscript = Regex("^(.+)\\[[^\\]]*\\]$").matchEntire(value)
        if (subscript != null) {
            val container = expressionType(document, subscript.groupValues[1]) ?: return null
            return when {
                container.arrayDepth > 0 -> container.copy(arrayDepth = container.arrayDepth - 1)
                container.pointerDepth > 0 -> container.copy(pointerDepth = container.pointerDepth - 1)
                else -> null
            }
        }
        splitTopLevelConditional(value)?.let { (condition, whenTrue, whenFalse) ->
            val conditionType = expressionType(document, condition)
            val trueType = expressionType(document, whenTrue)
            val falseType = expressionType(document, whenFalse)
            if (conditionType != null && trueType != null && falseType != null) {
                if (trueType.pointerDepth == falseType.pointerDepth &&
                    trueType.arrayDepth == falseType.arrayDepth &&
                    resolveTypeAlias(trueType.base) == resolveTypeAlias(falseType.base)
                ) {
                    return trueType.copy(
                        pointeeConst = trueType.pointeeConst || falseType.pointeeConst,
                        pointeeVolatile = trueType.pointeeVolatile || falseType.pointeeVolatile
                    )
                }
                numericBinaryType(trueType.base, falseType.base)?.let { return TypeShape(it, 0, 0) }
            }
        }
        splitTopLevelNumericBinary(value)?.let { (left, right) ->
            val leftType = expressionType(document, left)
            val rightType = expressionType(document, right)
            if (leftType != null && rightType != null &&
                leftType.pointerDepth == 0 && leftType.arrayDepth == 0 &&
                rightType.pointerDepth == 0 && rightType.arrayDepth == 0
            ) {
                numericBinaryType(leftType.base, rightType.base)?.let { return TypeShape(it, 0, 0) }
            }
        }
        if (value.startsWith("*")) {
            val pointed = expressionType(document, value.substring(1)) ?: return null
            if (pointed.pointerDepth == 0) return null
            return pointed.copy(pointerDepth = pointed.pointerDepth - 1)
        }
        if (value.length > 1 && value.first() in setOf('+', '-', '~', '!')) {
            val operand = expressionType(document, value.substring(1)) ?: return null
            return if (value.first() == '!') TypeShape("int", 0, 0) else operand
        }
        if (value.matches(Regex("(?:0[xX][0-9A-Fa-f]+|[0-9]+)(?:[uU]?[lL]{0,2}|[lL]{1,2}[uU])?"))) {
            val suffix = value.takeLastWhile { it == 'u' || it == 'U' || it == 'l' || it == 'L' }
            val base = when {
                suffix.count { it == 'l' || it == 'L' } >= 2 -> "long"
                suffix.any { it == 'l' || it == 'L' } -> "long"
                else -> "int"
            }
            return TypeShape(base, 0, 0)
        }
        if (value.matches(Regex("(?:[0-9]+\\.[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[fFlL]?"))) {
            val suffix = value.lastOrNull()
            return TypeShape(if (suffix == 'f' || suffix == 'F') "float" else "double", 0, 0)
        }
        if (value.startsWith("\"") && value.endsWith("\"")) return TypeShape("char", 0, 1)
        if (value.startsWith("'") && value.endsWith("'")) return TypeShape("int", 0, 0)
        val address = value.removePrefix("&").takeIf { value.startsWith("&") }
        val name = address ?: value
        if (!name.matches(IDENTIFIER)) {
            val member = receiverValue(
                document,
                name,
                document.snapshot.text.indexOf(name).coerceAtLeast(0)
            ) ?: return null
            return TypeShape(
                resolveTypeAlias(member.typeName),
                member.pointerDepth + if (address != null) 1 else 0,
                member.arrayDepth,
                pointeeConst = member.pointeeConst,
                pointeeVolatile = member.pointeeVolatile
            )
        }
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
            declared.arrayDepth,
            pointeeConst = declared.pointeeConst,
            pointeeVolatile = declared.pointeeVolatile
        )
    }

    private fun splitTopLevelNumericBinary(value: String): Pair<String, String>? {
        var parentheses = 0
        var brackets = 0
        value.forEachIndexed { index, character ->
            when (character) {
                '(' -> parentheses++
                ')' -> parentheses--
                '[' -> brackets++
                ']' -> brackets--
                '+', '-', '*', '/', '%' -> if (parentheses == 0 && brackets == 0 && index > 0) {
                    val previous = value[index - 1]
                    if (previous !in "+-*/%&|^!<>=") {
                        return value.substring(0, index).trim() to value.substring(index + 1).trim()
                    }
                }
            }
        }
        return null
    }

    private fun splitTopLevelConditional(value: String): Triple<String, String, String>? {
        var parentheses = 0
        var brackets = 0
        var question = -1
        value.forEachIndexed { index, character ->
            when (character) {
                '(' -> parentheses++
                ')' -> parentheses--
                '[' -> brackets++
                ']' -> brackets--
                '?' -> if (parentheses == 0 && brackets == 0 && question < 0) question = index
                ':' -> if (parentheses == 0 && brackets == 0 && question >= 0) {
                    return Triple(
                        value.substring(0, question).trim(),
                        value.substring(question + 1, index).trim(),
                        value.substring(index + 1).trim()
                    )
                }
            }
        }
        return null
    }

    private fun numericBinaryType(left: String, right: String): String? {
        val leftRank = numericRank(left) ?: return null
        val rightRank = numericRank(right) ?: return null
        val leftPromoted = if (leftRank <= INTEGER_PROMOTION_MAX_RANK) INT_RANK else leftRank
        val rightPromoted = if (rightRank <= INTEGER_PROMOTION_MAX_RANK) INT_RANK else rightRank
        return numericBaseForRank(maxOf(leftPromoted, rightPromoted))
    }

    private fun numericBaseForRank(rank: Int): String? = when (rank) {
        INT_RANK -> "int"
        4 -> "long"
        LONG_LONG_RANK -> "long long"
        FLOAT_RANK -> "float"
        DOUBLE_RANK -> "double"
        else -> null
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
            callableReturnBase = returnBase,
            pointeeConst = typeShape(symbol.detail.substring(0, nameOffset))?.pointeeConst == true,
            pointeeVolatile = typeShape(symbol.detail.substring(0, nameOffset))?.pointeeVolatile == true
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
        // The root snapshot may contain declarations materialized from its
        // imports.  Those symbols carry compiler-origin paths (which may be
        // canonical, such as macOS /private/var, while the editor opened
        // /var).  Apply the same live-document URI projection to local and
        // imported symbols; otherwise the materialized copy leaks a second
        // platform-specific URI into definition/completion results.
        return sequenceOf(document.symbols.asSequence().map { displaySymbol(it, document.uri) })
            .flatten()
            .plus(reachable.asSequence().filter { it != document.uri }
            .mapNotNull(readDocuments()::get)
            .flatMap { candidate -> candidate.symbols.asSequence().map { displaySymbol(it, candidate.uri) } })
            .distinctBy { listOf(it.uri, it.name, it.kind, it.selection.startOffset, it.detail) }
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
        } ?: symbol.uri.takeIf { it.isNotBlank() } ?: fallbackUri
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
        // A request inside `object.par` has the same receiver as one just
        // after `object.`. Strip only the unfinished identifier, not the access.
        var memberStart = offset
        while (memberStart > 0 && document.snapshot.text[memberStart - 1].isIdentifierPart()) memberStart--
        val prefix = document.snapshot.text.substring(0, memberStart)
        val explicitReceiverMatch = Regex(
            "(\\((?:&|\\*)[A-Za-z_][A-Za-z0-9_]*\\))\\s*(->|\\.)\\s*$"
        ).find(prefix)
        val match = explicitReceiverMatch ?: Regex(
            "((?:[A-Za-z_][A-Za-z0-9_]*|\\([^\\n]*\\))(?:(?:\\s*(?:->|\\.)\\s*[A-Za-z_][A-Za-z0-9_]*)|(?:\\s*\\([^()]*\\))|(?:\\s*\\[[^]\\n]*\\]))*)\\s*(->|\\.)\\s*$"
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
        val subscript = Regex("^(.+)\\[[^\\]]*\\]$").matchEntire(value)
        if (subscript != null) {
            val parent = receiverValue(document, subscript.groupValues[1], offset) ?: return null
            return when {
                parent.arrayDepth > 0 -> parent.copy(arrayDepth = parent.arrayDepth - 1)
                parent.pointerDepth > 0 -> parent.copy(pointerDepth = parent.pointerDepth - 1)
                else -> null
            }
        }
        val call = callParts(value)
        if (call != null) {
            return functionCallReturnType(document, call.first, offset, call.second)
        }
        if (value.matches(IDENTIFIER)) {
            val variable = visibleSymbols(document)
                .asSequence()
                .filter { it.name == value && it.kind == 13 && (!it.local ||
                    it.uri == document.uri && it.scope.contains(offset) && it.selection.startOffset <= offset) }
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
        val compatibleCandidates = candidates.filter { callableAcceptsArguments(document, it, arguments) }
        if (compatibleCandidates.isEmpty()) return null
        val knownArity = compatibleCandidates.mapNotNull(::callParameterArity)
        if (knownArity.isNotEmpty() && knownArity.none { it == arguments.size }) return null
        val ranked = compatibleCandidates.sortedWith(compareBy<LspSymbol> {
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
        maskToolingText(arguments).forEachIndexed { index, character ->
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
        return DeclaredType(
            resolveTypeAlias(shape.base),
            shape.pointerDepth,
                shape.arrayDepth,
                shape.pointeeConst,
                shape.pointeeVolatile
        )
    }

    private fun resolveTypeAlias(typeName: String): String {
        var current = typeName
        val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            val alias = (activeSemanticDocument.get()?.let { visibleSymbols(it) } ?: readDocuments().values.asSequence()
                .flatMap { it.symbols.asSequence() })
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
        val rawBeforeName = symbol.detail.substringBeforeLast(symbol.name)
        val firstPointer = rawBeforeName.indexOf('*')
        val pointeeConst = if (firstPointer >= 0) {
            Regex("\\bconst\\b").containsMatchIn(rawBeforeName.substring(0, firstPointer))
        } else {
            // A const scalar becomes a pointer-to-const when its address is
            // taken (`const int value; &value`).
            Regex("\\bconst\\b").containsMatchIn(rawBeforeName)
        }
        val pointeeVolatile = if (firstPointer >= 0) {
            Regex("\\bvolatile\\b").containsMatchIn(rawBeforeName.substring(0, firstPointer))
        } else {
            Regex("\\bvolatile\\b").containsMatchIn(rawBeforeName)
        }
        symbol.resolvedType?.let { resolved ->
            return DeclaredType(resolved.name, resolved.pointerDepth,
                resolved.declaratorLayers.count { it == CPlusDeclaratorLayer.ARRAY }, pointeeConst, pointeeVolatile)
        }
        val beforeName = rawBeforeName
            .replace(Regex("\\b(const|volatile|restrict)\\b"), " ")
            .trim()
        val match = Regex("(?:struct\\s+|union\\s+|enum\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*(\\*+)?\\s*$")
            .find(beforeName) ?: return null
        val arrayDepth = Regex("\\[[^]]*\\]").findAll(symbol.detail.substringAfter(symbol.name)).count()
        return DeclaredType(
            match.groupValues[1],
            match.groupValues[2].length,
            arrayDepth,
            pointeeConst,
            pointeeVolatile
        )
    }

    private fun notify(method: String, params: String) {
        trace("out method=$method")
        write("{\"jsonrpc\":\"2.0\",\"method\":${Json.string(method)},\"params\":$params}")
    }

    private fun respond(id: Json?, result: String) {
        trace("out response id=${id?.encode() ?: "null"}")
        write("{\"jsonrpc\":\"2.0\",\"id\":${id?.encode() ?: "null"},\"result\":$result}")
    }

    private fun respondForDocument(id: Json?, request: Json.Object, state: RequestState, result: () -> String) {
        val encodedId = id?.encode()
        if (state.cancelled.get() || wasCancelled(encodedId)) return
        val payload = withSemanticDocument(request.uri()?.let(readDocuments()::get), result)
        // Cancellation may arrive while parsing or indexing is in progress. Do
        // not publish a response that became obsolete during that work.
        if (state.cancelled.get() || wasCancelled(encodedId)) return
        respond(id, payload)
    }

    private fun <T> withSemanticDocument(document: LspDocument?, block: () -> T): T {
        val previous = activeSemanticDocument.get()
        activeSemanticDocument.set(document)
        return try { block() } finally {
            if (previous == null) activeSemanticDocument.remove() else activeSemanticDocument.set(previous)
        }
    }

    private fun wasCancelled(encodedId: String?): Boolean =
        encodedId != null && preCancelledRequests.remove(encodedId)

    private fun respondError(id: Json?, code: Int, message: String) {
        trace("out error id=${id?.encode() ?: "null"} code=$code")
        write("{\"jsonrpc\":\"2.0\",\"id\":${id?.encode() ?: "null"},\"error\":{\"code\":$code,\"message\":${Json.string(message)}}}")
    }

    private fun trace(event: String) {
        val path = tracePath ?: return
        runCatching {
            synchronized(traceLock) {
                path.parent?.let(Files::createDirectories)
                Files.writeString(
                    path,
                    event + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
                )
            }
        }
    }

    private fun writeLifecycle(state: String) {
        val path = lifecyclePath ?: return
        runCatching {
            synchronized(traceLock) {
                path.parent?.let(Files::createDirectories)
                Files.writeString(
                    path,
                    "pid=${ProcessHandle.current().pid()} state=$state${System.lineSeparator()}",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE
                )
            }
        }
    }

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
        require(length in 0..(16 * 1024 * 1024)) { "invalid or oversized LSP frame: $length bytes" }
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
    return toolingMappingDiagnostics()
}

private class RequestState(val key: String, val readView: ReadView) {
    val cancelled = AtomicBoolean(false)
    val future = AtomicReference<Future<*>>()
}

private data class ReadView(
    val documents: Map<String, LspDocument>,
    val importsByDocument: Map<String, Set<String>>
)

private data class DeclaredType(
    val typeName: String,
    val pointerDepth: Int,
    val arrayDepth: Int,
    val pointeeConst: Boolean = false,
    val pointeeVolatile: Boolean = false
)

private data class TypeShape(
    val base: String,
    val pointerDepth: Int,
    val arrayDepth: Int,
    val callableArity: Int? = null,
    val callableReturnBase: String? = null,
    val pointeeConst: Boolean = false,
    val pointeeVolatile: Boolean = false
)

private data class TypeMatch(val score: Int, val compatible: Boolean)

private data class LspDocument(
    val uri: String,
    val version: Int,
    val snapshot: SourceSnapshot,
    val parsedText: String,
    val mappedSource: MappedText?,
    val ast: CPlusAst,
    val diagnostics: List<ParserDiagnostic> = emptyList(),
    val editorAst: CPlusAst = ast,
    val activeImports: List<String>? = null,
    val scopedImports: List<LspImportBinding> = emptyList(),
    private val indexedSymbols: List<LspSymbol>? = null
) {
    val runtimeSymbols: List<LspSymbol> = indexedSymbols ?: LspSymbolIndex.build(uri, parsedText, mappedSource, ast)
    val symbols: List<LspSymbol> = runtimeSymbols +
        LspSymbolIndex.toolingDeclarations(uri, editorAst) +
        scopedImports.map { binding ->
            LspSymbol(
                uri = uri,
                name = binding.alias,
                kind = 23,
                detail = "import \"${binding.path}\" as ${binding.alias}",
                span = binding.importSpan,
                selection = binding.aliasSpan,
                scope = ast.root.span
            )
        }

    /** One immutable hierarchy per snapshot; never rescan every owner for every request. */
    val symbolTreeJson: String by lazy {
        val local = symbols.filter { sameSourceUri(it.uri, uri) }
        val owners = local.filter { it.kind in setOf(6, 10, 12, 23) }
        val children = LinkedHashMap<LspSymbol?, MutableList<LspSymbol>>()
        for (symbol in local) {
            val owner = owners.asSequence().filter {
                it !== symbol && it.span.contains(symbol.selection) && it.span.size() > symbol.span.size()
            }.minByOrNull { it.span.size() }
            children.getOrPut(owner) { mutableListOf() }.add(symbol)
        }
        fun encode(symbol: LspSymbol): String = symbol.toDocumentJson(children[symbol].orEmpty().map(::encode))
        children[null].orEmpty().joinToString(",", "[", "]", transform = ::encode)
    }

    fun wordAt(position: LspPosition?): String {
        val offset = snapshot.offsetAt(position) ?: return ""
        var start = offset
        var end = offset
        while (start > 0 && snapshot.text[start - 1].isIdentifierPart()) start--
        while (end < snapshot.text.length && snapshot.text[end].isIdentifierPart()) end++
        return snapshot.text.substring(start, end)
    }

    fun wordSpan(position: LspPosition?): SourceSpan? {
        val offset = snapshot.offsetAt(position) ?: return null
        var start = offset
        var end = offset
        while (start > 0 && snapshot.text[start - 1].isIdentifierPart()) start--
        while (end < snapshot.text.length && snapshot.text[end].isIdentifierPart()) end++
        return snapshot.sourceFile.span(start, end)
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

private data class LspImportBinding(
    val alias: String,
    val path: String,
    val importSpan: SourceSpan,
    val aliasSpan: SourceSpan
)

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
    val ownerName: String? = null,
    val access: String? = null,
    val annotations: Set<String> = emptySet(),
    val local: Boolean = false,
    val resolvedType: CPlusResolvedType? = null
) {
    fun rangeJson(): String = span.toRangeJson()
    fun toolingDetail(): String = buildString {
        append(detail)
        access?.takeIf { it.isNotBlank() }?.let { append(" [access=").append(it).append(']') }
        if (annotations.isNotEmpty()) {
            append(" [annotations=").append(annotations.sorted().joinToString(",")).append(']')
        }
    }
    fun toJson(): String = buildString {
        append("{\"name\":").append(Json.string(name))
        append(",\"kind\":").append(kind)
        append(",\"detail\":").append(Json.string(toolingDetail()))
        access?.let { append(",\"cplusAccess\":").append(Json.string(it)) }
        append(",\"cplusAnnotations\":[")
        append(annotations.sorted().joinToString(",") { Json.string(it) })
        append("],\"location\":{\"uri\":")
        append(Json.string(uri)).append(",\"range\":").append(span.toRangeJson()).append("}}")
    }
    fun toDocumentJson(children: List<String>): String = toJson().dropLast(1) +
        ",\"range\":${span.toRangeJson()},\"selectionRange\":${selection.toRangeJson()}," +
        "\"children\":[${children.joinToString(",")}] }"

    fun completionKind(): Int = when (kind) {
        6 -> 2; 12 -> 3; 8 -> 5; 13 -> 6; 10 -> 13; 22 -> 20; 23 -> 22; 26 -> 7
        else -> 6
    }
}

private object LspSymbolIndex {
    /** Templates/tests retain their original declaration identity alongside instantiated output. */
    fun toolingDeclarations(uri: String, ast: CPlusAst): List<LspSymbol> {
        val text = ast.source.text
        val result = mutableListOf<LspSymbol>()
        for (construct in CPlusComptimeIndexer().index(ast).constructs) {
            val symbolName = construct.symbol ?: continue
            if (!construct.moduleScope || !construct.activeThisPass ||
                construct.syntaxKind !in setOf("cplus_comptime_function_definition", "cplus_legacy_type_generator",
                    "cplus_legacy_function_generator", "cplus_comptime_value")) continue
            val node = ast.root.flatten().firstOrNull { it.span == construct.span && it.syntaxKind == construct.syntaxKind } ?: continue
            val selection = node.children.firstOrNull { it.fieldName == "name" }?.span ?: construct.span
            val end = construct.bodySpan?.startOffset ?: construct.span.endOffset
            result += LspSymbol(uri, symbolName.removePrefix("@"),
                if (construct.syntaxKind == "cplus_comptime_value") 14 else 12,
                text.substring(construct.span.startOffset, end).replace(Regex("\\s+"), " ").trim(),
                construct.span, selection, ast.root.span, annotations = setOf("comptime"))
        }
        for (node in ast.root.flatten().filter { it.kind == CPlusAstKind.TEST }) {
            val name = node.children.firstOrNull { it.fieldName == "name" } ?: continue
            val spelling = text.substring(name.span.startOffset, name.span.endOffset).trim().trim('"')
            result += LspSymbol(uri, spelling, 12, "@test \"$spelling\"", node.span, name.span, ast.root.span,
                annotations = setOf("test"))
        }
        return result
    }

    fun build(uri: String, parsedText: String, mappedSource: MappedText?, ast: CPlusAst): List<LspSymbol> {
        val symbols = mutableListOf<LspSymbol>()
        val semantics = runCatching { CPlusSemanticAnalyzer().analyze(ast) }.getOrNull()
        val semanticSymbols = semantics?.symbols.orEmpty()
        val declarationKinds = setOf(
            CPlusAstKind.STRUCT_DECLARATION, CPlusAstKind.UNION_DECLARATION,
            CPlusAstKind.ENUM_DECLARATION, CPlusAstKind.TYPE_ALIAS,
            CPlusAstKind.FUNCTION_DECLARATION, CPlusAstKind.METHOD_DECLARATION,
            CPlusAstKind.VARIABLE_DECLARATION, CPlusAstKind.FIELD_DECLARATION,
            CPlusAstKind.PARAMETER, CPlusAstKind.ENUMERATOR
        )
        fun mapSpan(span: SourceSpan): SourceSpan {
            val mapped = mappedSource ?: return span
            val start = mapped.originAt(span.startOffset)
            val last = mapped.originAt(span.endOffset - 1)
            // Expansion passes may create distinct SourceFile objects for the
            // same origin. Their object identity must not collapse lexical scopes.
            if (start != null && last != null && sameSourceFile(start.file.name, last.file.name) &&
                start.file.text == last.file.text && last.offset >= start.offset) {
                return start.file.span(start.offset, last.offset + 1)
            }
            return mapped.toOriginalSpan(span)
        }
        fun semanticKind(kind: CPlusAstKind): CPlusSymbolKind? = when (kind) {
            CPlusAstKind.STRUCT_DECLARATION, CPlusAstKind.UNION_DECLARATION -> CPlusSymbolKind.STRUCT
            CPlusAstKind.TYPE_ALIAS -> CPlusSymbolKind.TYPE_ALIAS
            CPlusAstKind.FIELD_DECLARATION -> CPlusSymbolKind.FIELD
            CPlusAstKind.METHOD_DECLARATION -> null
            CPlusAstKind.FUNCTION_DECLARATION -> CPlusSymbolKind.FUNCTION
            else -> null
        }
        fun semanticSymbol(node: CPlusAstNode, kind: CPlusAstKind, name: String, owner: String?): CPlusSymbol? {
            val targetKind = semanticKind(kind)
            return semanticSymbols.asSequence()
                .filter { it.name == name && (targetKind == null || it.kind == targetKind) }
                .filter { it.ownerType == owner || owner == null && it.ownerType == null }
                .filter { it.span.file == node.span.file && it.span.startOffset < node.span.endOffset && node.span.startOffset < it.span.endOffset }
                .minByOrNull { kotlin.math.abs(it.span.startOffset - node.span.startOffset) }
        }
        fun visit(node: CPlusAstNode, scope: SourceSpan, ownerName: String? = null) {
            val nestedScope = if (
                node.kind in setOf(
                    CPlusAstKind.STRUCT_DECLARATION, CPlusAstKind.UNION_DECLARATION,
                    CPlusAstKind.FUNCTION_DECLARATION, CPlusAstKind.METHOD_DECLARATION
                ) || node.syntaxKind in setOf("compound_statement", "cplus_block")
            ) node.span else scope
            if (node.kind in declarationKinds) {
                val nameNodes = node.declaredIdentifiers().ifEmpty { listOfNotNull(namedNode(node, node.kind)) }
                for (nameNode in nameNodes) {
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
                        CPlusAstKind.ENUMERATOR -> 22
                        else -> 13
                    }
                    val mappedDeclaration = mapSpan(node.span)
                    val symbolUri = originUri(mappedDeclaration.file)
                        ?.takeUnless { sameSourceUri(it, uri) }
                        ?: uri
                    val owner = ownerName.takeIf {
                        node.kind == CPlusAstKind.METHOD_DECLARATION ||
                            node.kind == CPlusAstKind.FIELD_DECLARATION ||
                            (node.kind == CPlusAstKind.VARIABLE_DECLARATION && node.syntaxKind == "field_declaration")
                    }
                    val semantic = semanticSymbol(node, node.kind, name, owner)
                    symbols += LspSymbol(
                        symbolUri, name, kind,
                        declarationDetail(node, nameNode, parsedText),
                        mapSpan(node.span), mapSpan(nameNode.span), mapSpan(nestedScope),
                        owner,
                        semantic?.access,
                        semantic?.annotations.orEmpty(),
                        local = scope != ast.root.span && node.kind in setOf(CPlusAstKind.PARAMETER, CPlusAstKind.VARIABLE_DECLARATION),
                        resolvedType = when (node.kind) {
                            CPlusAstKind.TYPE_ALIAS -> semantics?.typeAliases?.get(name)
                            CPlusAstKind.PARAMETER, CPlusAstKind.VARIABLE_DECLARATION -> semantics?.scopedValueTypes
                                ?.filter { it.name == name && it.declarationSpan.contains(nameNode.span) }
                                ?.minByOrNull { it.declarationSpan.size() }?.type ?: semantics?.valueTypes?.get(name)
                            else -> null
                        }
                    )
                }
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
        return symbols.distinctBy { listOf(it.uri, it.name, it.kind, it.selection.startOffset, it.detail) }.toList()
    }

    private fun declarationDetail(node: CPlusAstNode, nameNode: CPlusAstNode, text: String): String {
        val body = node.children.firstOrNull { it.fieldName == "body" && it.kind == CPlusAstKind.BLOCK }
        val end = body?.span?.startOffset ?: node.span.endOffset
        val declarators = node.children.filter { it.fieldName == "declarator" }
        val declarator = declarators.firstOrNull { it.span.contains(nameNode.span) }
        val spelling = if (declarators.size > 1 && declarator != null) {
            text.substring(node.span.startOffset, declarators.first().span.startOffset) +
                text.substring(declarator.span.startOffset, declarator.span.endOffset)
        } else text.substring(node.span.startOffset, end)
        return spelling.replace(Regex("\\s+"), " ").trim().take(2048)
    }

    private fun namedNode(node: CPlusAstNode, kind: CPlusAstKind): CPlusAstNode? {
        val declarator = node.flatten().firstOrNull { it.syntaxKind in setOf("function_declarator", "cplus_method_declarator") }
        if (kind == CPlusAstKind.FUNCTION_DECLARATION || kind == CPlusAstKind.METHOD_DECLARATION) {
            return declarator?.flatten()?.firstOrNull { it.kind == CPlusAstKind.IDENTIFIER }
                ?: node.flatten().firstOrNull { it.kind == CPlusAstKind.IDENTIFIER }
        }
        return when (kind) {
            CPlusAstKind.TYPE_ALIAS -> node.flatten().lastOrNull { it.kind == CPlusAstKind.IDENTIFIER }
            CPlusAstKind.FIELD_DECLARATION, CPlusAstKind.VARIABLE_DECLARATION, CPlusAstKind.PARAMETER ->
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
    // Unsaved leaves need canonical parent identity too: `/link/new.cp` and
    // `/real/new.cp` are the same overlay even before new.cp exists on disk.
    val leftComparable = runCatching { leftPath.toFile().canonicalFile.toPath() }.getOrDefault(leftPath)
    val rightComparable = runCatching { rightPath.toFile().canonicalFile.toPath() }.getOrDefault(rightPath)
    return leftComparable == rightComparable
}

private fun CPlusAstNode.flatten(): Sequence<CPlusAstNode> = sequence {
    yield(this@flatten)
    children.forEach { yieldAll(it.flatten()) }
}

private fun CPlusAstNode.identifierOccurrences(
    ancestors: List<String> = emptyList()
): Sequence<LspIdentifierOccurrence> = sequence {
    for (use in identifierUses()) yield(LspIdentifierOccurrence(use.node, ancestors + use.ancestors.map { it.syntaxKind }))
}

/** Preserve offsets while hiding delimiters in comments and literals. */
private fun maskToolingText(text: String): String {
    val output = text.toCharArray()
    var index = 0
    while (index < text.length) {
        val start = index
        when {
            text.startsWith("//", index) -> {
                index = text.indexOf('\n', index).takeIf { it >= 0 } ?: text.length
            }
            text.startsWith("/*", index) -> {
                index = text.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: text.length
            }
            text[index] == '\'' || text[index] == '"' -> {
                val quote = text[index++]
                while (index < text.length) {
                    val char = text[index++]
                    if (char == '\\') index = (index + 1).coerceAtMost(text.length)
                    else if (char == quote) break
                }
            }
            else -> { index++; continue }
        }
        for (masked in start until index) if (output[masked] != '\n' && output[masked] != '\r') output[masked] = ' '
    }
    return String(output)
}

private val TYPE_SYMBOL_KINDS = setOf(10, 23, 26)

/**
 * Recover identifier spans from code text when a parser recovery node hides a
 * type reference. This intentionally recognizes only identifiers and skips
 * comments/literals; it is not a replacement parser or a general reference
 * index.
 */
private fun lexicalIdentifierOccurrences(
    document: LspDocument,
    target: String
): Sequence<LspIdentifierOccurrence> = sequence {
    // The parser may be looking at a materialized comptime revision.  Scan
    // that same revision so offsets line up with the AST and its source map;
    // the caller maps recovered spans back to the editor snapshot afterward.
    val text = document.parsedText
    val sourceFile = SourceFile(text, document.snapshot.sourceFile.name)
    var index = 0
    var state = 0 // normal, line comment, block comment, string, character
    while (index < text.length) {
        val character = text[index]
        when (state) {
            1 -> {
                if (character == '\n') state = 0
                index++
            }
            2 -> {
                if (character == '*' && index + 1 < text.length && text[index + 1] == '/') {
                    state = 0
                    index += 2
                } else {
                    index++
                }
            }
            3, 4 -> {
                if (character == '\\') {
                    index = (index + 2).coerceAtMost(text.length)
                } else if ((state == 3 && character == '"') ||
                    (state == 4 && character == '\'')
                ) {
                    state = 0
                    index++
                } else {
                    index++
                }
            }
            else -> when {
                character == '/' && index + 1 < text.length && text[index + 1] == '/' -> {
                    state = 1
                    index += 2
                }
                character == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
                    state = 2
                    index += 2
                }
                character == '"' -> {
                    state = 3
                    index++
                }
                character == '\'' -> {
                    state = 4
                    index++
                }
                character == '_' || character.isLetter() -> {
                    val start = index++
                    while (index < text.length &&
                        (text[index] == '_' || text[index].isLetterOrDigit())
                    ) index++
                    if (text.substring(start, index) == target) {
                        val span = sourceFile.span(start, index)
                        yield(
                            LspIdentifierOccurrence(
                                CPlusAstNode(
                                    kind = CPlusAstKind.IDENTIFIER,
                                    syntaxKind = "type_identifier",
                                    span = span,
                                    fieldName = null,
                                    children = emptyList(),
                                    named = true,
                                    opaque = false,
                                    recovered = true
                                ),
                                emptyList()
                            )
                        )
                    }
                }
                else -> index++
            }
        }
    }
}

private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
    sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }

private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_'

private fun String.endsWithAny(vararg suffixes: String): Boolean = suffixes.any(::endsWith)

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val IMPORT_LITERAL = Regex("\\\"((?:[^\\\"\\\\]|\\\\.)*)\\\"")
private val SCOPED_IMPORT = Regex(
    "(?m)(?:\\bcomptime\\s+import|@import)\\s*(?:\\(\\s*)?\\\"((?:[^\\\"\\\\]|\\\\.)*)\\\"\\s*(?:\\))?\\s+as\\s+([A-Za-z_]\\w*)"
)
private val IMPORT_RECOVERY_LITERAL = Regex(
    "(?m)^\\s*(?:comptime\\s+)?(?:@import|import)\\s*(?:\\(\\s*)?\\\"((?:[^\\\"\\\\]|\\\\.)*)\\\""
)
private const val MAX_WORKSPACE_IMPORTS = 256
private const val MAX_TRACKED_REQUEST_IDS = 4096
private val CPLUS_KEYWORDS = setOf(
    "pub", "priv", "static", "stat", "borrowed", "owned", "mut", "scratch", "hot", "warm", "cold", "self", "comptime", "type", "function",
    "import", "flags", "code", "variable", "var", "fn", "os", "align", "fields", "name", "size",
    "defer", "try", "catch", "throws", "test", "assert", "assertEquals", "if", "else", "for", "while",
    "return", "struct", "union", "enum", "typedef", "const", "volatile", "sizeof",
    "auto", "break", "case", "char", "continue", "default", "do", "double", "extern", "float", "goto",
    "inline", "int", "long", "register", "restrict", "short", "signed", "switch", "unsigned", "void",
    "_Alignas", "_Alignof", "_Atomic", "_Bool", "_Complex", "_Generic", "_Imaginary", "_Noreturn",
    "_Static_assert", "_Thread_local", "bool", "size_t", "ptrdiff_t", "wchar_t", "char16_t", "char32_t"
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
    val end = text.indexOf('\n', lineStart).takeIf { it >= 0 } ?: text.length
    return (lineStart + position.character).coerceAtMost(end)
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
