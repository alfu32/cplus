package cplus.parser

import cplus.CPlusAstAdapter
import cplus.CPlusAstNode
import cplus.CPlusImportPaths
import cplus.CPlusImportResolver
import cplus.CPlusLoweringDiagnostic
import cplus.CPlusMappedAstEmitter
import cplus.CPlusMappedEdit
import cplus.CPlusParseResult
import cplus.CPlusParserBackend
import cplus.CPlusSyntaxNode
import cplus.MappedText
import cplus.MappedTextBuilder
import cplus.ParserDiagnostic
import cplus.SourceFile
import cplus.SourceId
import cplus.SourceImportEdge
import cplus.SourceImportGraph
import cplus.SourceManager
import java.nio.file.Paths

data class TreeSitterComptimeImportResult(
    val source: MappedText?,
    val parserDiagnostics: List<ParserDiagnostic> = emptyList(),
    val loweringDiagnostics: List<CPlusLoweringDiagnostic> = emptyList(),
    val sourceOrder: List<SourceId> = emptyList(),
    val compilerOptionOrder: List<SourceId> = emptyList(),
    val sourceImports: List<SourceImportEdge> = emptyList()
)

/** Resolves parsed C-plus imports, rejects cycles, and emits each module once dependency-first. */
class TreeSitterComptimeImportLowering(
    private val backend: CPlusParserBackend,
    private val sourceManager: SourceManager,
    private val importPaths: CPlusImportPaths = CPlusImportPaths(),
    private val targetOs: String = cplus.CPlusTarget.hostOs()
) {
    fun lower(
        rootParse: CPlusParseResult,
        rootSource: MappedText,
        rootSourceFile: SourceFile? = null,
        alreadyIncludedSourceIds: Set<SourceId> = emptySet()
    ): TreeSitterComptimeImportResult {
        val rootFile = rootSourceFile ?: rootSource.firstOrigin()?.file ?: rootParse.source.sourceFile
        val rootId = sourceId(rootFile)
        val session = Session(alreadyIncludedSourceIds - rootId, rootSource)
        session.visit(rootFile, rootSource, rootParse)?.let { return it }
        val order = try {
            session.graph.dependencyOrder(listOf(rootId))
        } catch (error: IllegalStateException) {
            return TreeSitterComptimeImportResult(
                null,
                loweringDiagnostics = listOf(CPlusLoweringDiagnostic(
                    "CPLUS_IMPORT_CYCLE",
                    error.message ?: "comptime import cycle",
                    rootFile.span(0, 0)
                )),
                sourceImports = session.reportedEdges.toList()
            )
        }
        val compilerOptionOrder = try {
            session.graph.dependencyOrderInInsertionOrder(listOf(rootId))
        } catch (error: IllegalStateException) {
            return TreeSitterComptimeImportResult(
                null,
                loweringDiagnostics = listOf(CPlusLoweringDiagnostic(
                    "CPLUS_IMPORT_CYCLE",
                    error.message ?: "comptime import cycle",
                    rootFile.span(0, 0)
                )),
                sourceImports = session.reportedEdges.toList()
            )
        }
        val output = MappedTextBuilder()
        order.forEachIndexed { index, sourceId ->
            val module = session.moduleTexts[sourceId] ?: return@forEachIndexed
            if (index > 0) output.appendGenerated("\n", module.firstOrigin())
            output.append(module)
        }
        return TreeSitterComptimeImportResult(
            output.build(),
            sourceOrder = order,
            compilerOptionOrder = compilerOptionOrder,
            sourceImports = session.reportedEdges.toList()
        )
    }

    private inner class Session(alreadyIncludedSourceIds: Set<SourceId>, rootSource: MappedText) {
        val graph = SourceImportGraph()
        val moduleTexts = linkedMapOf<SourceId, MappedText>()
        val reportedEdges = linkedSetOf<SourceImportEdge>()
        private val active = linkedSetOf<SourceId>()
        private val resolver = CPlusImportResolver(importPaths)
        private var importedByteCount = 0L

        init {
            val emptyOrigin = rootSource.firstOrigin()
            alreadyIncludedSourceIds.forEach { sourceId ->
                moduleTexts[sourceId] = MappedText.generated("", emptyOrigin)
            }
        }

        fun visit(sourceFile: SourceFile, mapped: MappedText, parsed: CPlusParseResult): TreeSitterComptimeImportResult? {
            val id = sourceId(sourceFile)
            if (id in moduleTexts) return null
            if (!active.add(id)) {
                val origin = mapped.firstOrigin()
                return loweringFailure(
                    "CPLUS_IMPORT_CYCLE",
                    "comptime import cycle reaches ${id.value}",
                    origin?.file?.span(origin.offset, (origin.offset + 1).coerceAtMost(origin.file.text.length))
                        ?: sourceFile.span(0, 0)
                )
            }

            val ast = CPlusAstAdapter().adapt(parsed)
            val replacements = linkedMapOf<CPlusAstNode, MappedText>()
            val declarations = collectImports(ast.root, mapped.text)
            val duplicateAlias = declarations.mapNotNull { it.alias }
                .groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
            if (duplicateAlias != null) {
                active.remove(id)
                return loweringFailure(
                    "CPLUS_IMPORT_ALIAS_DUPLICATE",
                    "duplicate scoped import alias '${duplicateAlias.key}'",
                    mapped.toOriginalSpan(declarations.first { it.alias == duplicateAlias.key }.span)
                )
            }
            for (declaration in declarations) {
                val literalText = mapped.text.substring(declaration.literal.span.startOffset, declaration.literal.span.endOffset)
                val pathText = decodeStringLiteral(literalText)
                val extension = pathText.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "")
                // Legacy @import("name.c") belongs to the C include pass; extensionless legacy imports
                // keep their specified C-file meaning. Every explicit C-plus suffix is a module edge.
                if (!declaration.isComptime && extension !in setOf("cp", "c+")) {
                    if (declaration.alias != null) {
                        active.remove(id)
                        return loweringFailure(
                            "CPLUS_IMPORT_ALIAS_C_SOURCE",
                            "C source imports cannot declare a C-plus alias",
                            span = mapped.toOriginalSpan(declaration.span)
                        )
                    }
                    continue
                }

                val importOriginFile = mapped.originAt(declaration.span.startOffset)?.file ?: sourceFile
                val importOriginId = sourceId(importOriginFile)
                val importLocation = mapped.toOriginalSpan(declaration.span)
                if (!declaration.moduleScope) {
                    active.remove(id)
                    return loweringFailure(
                        "CPLUS_IMPORT_SCOPE",
                        "C-plus module imports are only valid at module scope",
                        importLocation
                    )
                }
                val target = try {
                    resolver.resolve(importOriginFile, pathText, listOf("cp", "c+"))
                } catch (error: IllegalArgumentException) {
                    active.remove(id)
                    return loweringFailure(
                        "CPLUS_IMPORT_RESOLUTION",
                        error.message ?: "cannot resolve C-plus import '$pathText'",
                        importLocation
                    )
                }
                if (target.fileName.toString().substringAfterLast('.', "") !in setOf("cp", "c+")) {
                    active.remove(id)
                    return loweringFailure(
                        "CPLUS_IMPORT_EXTENSION",
                        "C-plus imports must use .cp or .c+ files: $target",
                        importLocation
                    )
                }

                val targetId = SourceId.fromPath(target)
                val sourceEdge = SourceImportEdge(importOriginId, targetId, importLocation)
                var cycle = graph.add(sourceEdge)
                if (cycle == null && importOriginId != id) {
                    // The current root snapshot may already contain several imported modules.
                    // Add a root ordering edge so the newly imported dependency is emitted before
                    // that snapshot, while retaining the real importer edge for cycle checks.
                    cycle = graph.add(SourceImportEdge(id, targetId, importLocation))
                }
                if (cycle != null) {
                    active.remove(id)
                    return loweringFailure(
                        "CPLUS_IMPORT_CYCLE",
                        "comptime import cycle: ${cycle.joinToString(" -> ") { it.value }}",
                        importLocation
                    )
                }
                if (reportedEdges.none { it.importer == sourceEdge.importer && it.imported == sourceEdge.imported }) {
                    reportedEdges += sourceEdge
                }

                if (targetId !in moduleTexts) {
                    val imported = try {
                        sourceManager.load(target).sourceFile
                    } catch (error: Exception) {
                        active.remove(id)
                        return loweringFailure(
                            "CPLUS_IMPORT_READ",
                            "cannot read imported C-plus file $target: ${error.message}",
                            importLocation
                        )
                    }
                    importedByteCount += imported.text.toByteArray(Charsets.UTF_8).size
                    if (moduleTexts.size + active.size > MAX_MODULES || importedByteCount > MAX_IMPORTED_BYTES) {
                        active.remove(id)
                        return loweringFailure(
                            "CPLUS_IMPORT_LIMIT",
                            "C-plus import graph exceeds the module or byte limit",
                            importLocation
                        )
                    }
                    val childMapped = MappedText.identity(imported)
                    val child = parseAndSelectConditions(targetId, childMapped)
                    if (child.failure != null) {
                        active.remove(id)
                        return child.failure
                    }
                    val nested = visit(imported, child.mapped, child.parsed)
                    if (nested != null) {
                        active.remove(id)
                        return nested
                    }
                }
                replacements[declaration.node] = MappedText.generated("", mapped.originAt(declaration.span.startOffset))
            }

            val stripped = if (replacements.isEmpty()) mapped else CPlusMappedAstEmitter().emit(ast, mapped, replacements)
            val scopedAliases = declarations.mapNotNull { declaration ->
                val importedPath = decodeStringLiteral(declaration.path)
                if (declaration.isComptime || importedPath.endsWith(".cp") || importedPath.endsWith(".c+")) {
                    declaration.alias?.let { alias -> alias to declaration.path }
                } else null
            }.toMap()
            moduleTexts[id] = rewriteScopedReferences(stripped, scopedAliases)
            active.remove(id)
            return null
        }

        private fun parseAndSelectConditions(id: SourceId, initial: MappedText): ParsedModule {
            var mapped = initial
            var snapshot = sourceManager.open(id, mapped.text)
            var parsed = backend.parse(snapshot)
            if (parsed.diagnostics.isNotEmpty()) return ParsedModule(
                mapped,
                parsed,
                TreeSitterComptimeImportResult(
                    null,
                    parserDiagnostics = parsed.diagnostics.map { it.copy(span = mapSpan(mapped, it.span)) },
                    sourceImports = reportedEdges.toList()
                )
            )

            var passes = 0
            while (passes < MAX_CONDITIONAL_PASSES) {
                val selected = TreeSitterComptimeConditionalLowering().lower(parsed, mapped, targetOs)
                if (selected.diagnostics.isNotEmpty()) return ParsedModule(
                    mapped,
                    parsed,
                    TreeSitterComptimeImportResult(
                        null,
                        loweringDiagnostics = selected.diagnostics.map { it.copy(span = mapSpan(mapped, it.span)) },
                        sourceImports = reportedEdges.toList()
                    )
                )
                if (selected.source.text == mapped.text) break
                mapped = selected.source
                passes++
                snapshot = sourceManager.open(SourceId.named("${id.value}#selected-$passes"), mapped.text)
                parsed = backend.parse(snapshot)
                if (parsed.diagnostics.isNotEmpty()) return ParsedModule(
                    mapped,
                    parsed,
                    TreeSitterComptimeImportResult(
                        null,
                        parserDiagnostics = parsed.diagnostics.map { it.copy(span = mapSpan(mapped, it.span)) },
                        sourceImports = reportedEdges.toList()
                    )
                )
            }
            if (descendants(parsed.root).any { it.kind == "cplus_comptime_conditional" }) return ParsedModule(
                mapped,
                parsed,
                TreeSitterComptimeImportResult(
                    null,
                    loweringDiagnostics = listOf(CPlusLoweringDiagnostic(
                        "CPLUS_COMPTIME_CONDITIONAL_LIMIT",
                        "nested comptime conditionals exceeded the materialization pass limit",
                        mapSpan(mapped, descendants(parsed.root).first { it.kind == "cplus_comptime_conditional" }.span)
                    )),
                    sourceImports = reportedEdges.toList()
                )
            )
            return ParsedModule(mapped, parsed, null)
        }

        private fun loweringFailure(code: String, message: String, span: cplus.SourceSpan) =
            TreeSitterComptimeImportResult(
                null,
                loweringDiagnostics = listOf(CPlusLoweringDiagnostic(code, message, span)),
                sourceImports = reportedEdges.toList()
            )
    }

    private fun collectImports(root: CPlusAstNode, sourceText: String): List<ImportDeclaration> {
        val declarations = mutableListOf<ImportDeclaration>()
        fun visit(node: CPlusAstNode, parent: CPlusAstNode?, moduleScope: Boolean, dormant: Boolean) {
            if (dormant) return
            if (node.syntaxKind == "cplus_comptime_import") {
                val declaration = parent?.takeIf { it.syntaxKind == "cplus_comptime_declaration" } ?: node
                node.children.firstOrNull { it.syntaxKind == "string_literal" }?.let { literal ->
                    declarations += ImportDeclaration(
                        declaration, literal, declaration.span, isComptime = true, moduleScope = moduleScope,
                        alias = node.children.lastOrNull { it.syntaxKind == "identifier" }
                            ?.let { alias -> sourceText.substring(alias.span.startOffset, alias.span.endOffset) },
                        path = sourceText.substring(literal.span.startOffset, literal.span.endOffset)
                    )
                }
                return
            }
            if (node.syntaxKind == "cplus_at_import") {
                node.children.firstOrNull { it.syntaxKind == "string_literal" }?.let { literal ->
                    declarations += ImportDeclaration(
                        node, literal, node.span, isComptime = false, moduleScope = moduleScope,
                        alias = node.children.lastOrNull { it.syntaxKind == "identifier" }
                            ?.let { alias -> sourceText.substring(alias.span.startOffset, alias.span.endOffset) },
                        path = sourceText.substring(literal.span.startOffset, literal.span.endOffset)
                    )
                }
                return
            }
            val nestedModuleScope = moduleScope && node.syntaxKind !in NON_MODULE_IMPORT_CONTEXTS
            val nestedDormant = node.syntaxKind in DORMANT_COMPTIME_CONTEXTS
            node.children.forEach { visit(it, node, nestedModuleScope, nestedDormant) }
        }
        visit(root, null, moduleScope = true, dormant = false)
        return declarations.sortedBy { it.span.startOffset }
    }

    /** Lowers the first scoped-import slice: alias.member becomes the imported C symbol. */
    private fun rewriteScopedReferences(source: MappedText, aliases: Map<String, String>): MappedText {
        if (aliases.isEmpty()) return source
        val names = aliases.keys.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }
        val matches = Regex("\\b(?:$names)\\s*\\.\\s*([A-Za-z_]\\w*)")
            .findAll(maskSource(source.text))
            .toList()
        if (matches.isEmpty()) return source
        val edits = matches.map { match ->
            CPlusMappedEdit(
                cplus.SourceSpan(null, match.range.first, match.range.last + 1, 0, 0, 0, 0),
                MappedText.generated(match.groupValues[1], source.originAt(match.range.first))
            )
        }
        return CPlusMappedAstEmitter().emit(
            CPlusAstAdapter().adapt(backend.parse(sourceManager.open(SourceId.named("<scoped-import-rewrite>"), source.text))),
            source,
            edits
        )
    }

    private fun maskSource(input: String): String {
        val chars = input.toCharArray()
        var index = 0
        var state = MaskState.CODE
        while (index < chars.size) {
            when (state) {
                MaskState.CODE -> when {
                    chars[index] == '/' && index + 1 < chars.size && chars[index + 1] == '/' -> {
                        chars[index] = ' '; chars[index + 1] = ' '; index += 2; state = MaskState.LINE
                    }
                    chars[index] == '/' && index + 1 < chars.size && chars[index + 1] == '*' -> {
                        chars[index] = ' '; chars[index + 1] = ' '; index += 2; state = MaskState.BLOCK
                    }
                    chars[index] == '"' -> { chars[index] = ' '; index++; state = MaskState.STRING }
                    chars[index] == '\'' -> { chars[index] = ' '; index++; state = MaskState.CHAR }
                    else -> index++
                }
                MaskState.LINE -> { if (chars[index] == '\n') state = MaskState.CODE else chars[index] = ' '; index++ }
                MaskState.BLOCK -> {
                    if (chars[index] == '*' && index + 1 < chars.size && chars[index + 1] == '/') {
                        chars[index] = ' '; chars[index + 1] = ' '; index += 2; state = MaskState.CODE
                    } else { if (chars[index] != '\n') chars[index] = ' '; index++ }
                }
                MaskState.STRING, MaskState.CHAR -> {
                    val terminator = if (state == MaskState.STRING) '"' else '\''
                    if (chars[index] == '\\') {
                        chars[index] = ' '; if (index + 1 < chars.size && chars[index + 1] != '\n') chars[index + 1] = ' '; index += 2
                    } else if (chars[index] == terminator) {
                        chars[index] = ' '; index++; state = MaskState.CODE
                    } else { if (chars[index] != '\n') chars[index] = ' '; index++ }
                }
            }
        }
        return String(chars)
    }

    private enum class MaskState { CODE, LINE, BLOCK, STRING, CHAR }

    private fun decodeStringLiteral(literal: String): String {
        val content = literal.removeSurrounding("\"")
        val result = StringBuilder(content.length)
        var index = 0
        while (index < content.length) {
            val character = content[index++]
            if (character != '\\' || index == content.length) {
                result.append(character)
                continue
            }
            result.append(when (val escaped = content[index++]) {
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                else -> escaped
            })
        }
        return result.toString()
    }

    private fun sourceId(source: SourceFile): SourceId = try {
        source.name?.let { SourceId.fromPath(Paths.get(it)) } ?: SourceId.named("<anonymous-source>")
    } catch (_: Exception) {
        SourceId.named(source.name ?: "<anonymous-source>")
    }

    private fun mapSpan(mapped: MappedText, span: cplus.SourceSpan): cplus.SourceSpan {
        val start = mapped.originAt(span.startOffset)
            ?: (span.startOffset - 1).takeIf { it >= 0 }?.let(mapped::originAt)
            ?: return span
        if (span.endOffset <= span.startOffset) return start.file.span(start.offset, start.offset)
        val end = mapped.originAt(span.endOffset - 1)
        val mappedEnd = if (end?.file === start.file && end.offset >= start.offset) end.offset + 1 else start.offset + 1
        return start.file.span(start.offset, mappedEnd)
    }

    private fun descendants(root: CPlusSyntaxNode): Sequence<CPlusSyntaxNode> =
        sequenceOf(root) + root.children.asSequence().flatMap(::descendants)

    private data class ParsedModule(
        val mapped: MappedText,
        val parsed: CPlusParseResult,
        val failure: TreeSitterComptimeImportResult?
    )

    private data class ImportDeclaration(
        val node: CPlusAstNode,
        val literal: CPlusAstNode,
        val span: cplus.SourceSpan,
        val isComptime: Boolean,
        val moduleScope: Boolean,
        val alias: String? = null,
        val path: String = ""
    )

    private companion object {
        const val MAX_MODULES = 256
        const val MAX_IMPORTED_BYTES = 8L * 1024L * 1024L
        const val MAX_CONDITIONAL_PASSES = 64
        val NON_MODULE_IMPORT_CONTEXTS = setOf(
            "function_definition", "cplus_method_definition", "cplus_throws_annotated_method",
            "compound_statement", "field_declaration_list", "linkage_specification"
        )
        val DORMANT_COMPTIME_CONTEXTS = setOf(
            "cplus_comptime_block", "cplus_comptime_function_definition", "cplus_comptime_body",
            "cplus_legacy_type_generator", "cplus_legacy_function_generator", "cplus_code_fragment"
        )
    }
}
