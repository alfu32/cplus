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
    val sourceImports: List<SourceImportEdge> = emptyList()
)

/** Resolves parsed C-plus imports, rejects cycles, and emits each module once dependency-first. */
class TreeSitterComptimeImportLowering(
    private val backend: CPlusParserBackend,
    private val sourceManager: SourceManager,
    private val importPaths: CPlusImportPaths = CPlusImportPaths(),
    private val targetOs: String = cplus.CPlusTarget.hostOs()
) {
    fun lower(rootParse: CPlusParseResult, rootSource: MappedText): TreeSitterComptimeImportResult {
        val session = Session()
        val rootFile = rootSource.firstOrigin()?.file ?: rootParse.source.sourceFile
        session.visit(rootFile, rootSource, rootParse)?.let { return it }
        val rootId = session.sourceId(rootFile)
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
                sourceImports = session.graph.edges()
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
            sourceImports = session.graph.edges()
        )
    }

    private inner class Session {
        val graph = SourceImportGraph()
        val moduleTexts = linkedMapOf<SourceId, MappedText>()
        private val active = linkedSetOf<SourceId>()
        private val resolver = CPlusImportResolver(importPaths)
        private var importedByteCount = 0L

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
            for (declaration in collectImports(ast.root)) {
                val literalText = mapped.text.substring(declaration.literal.span.startOffset, declaration.literal.span.endOffset)
                val pathText = decodeStringLiteral(literalText)
                val extension = pathText.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "")
                // Legacy @import("name.c") belongs to the C include pass; extensionless legacy imports
                // keep their specified C-file meaning. Every explicit C-plus suffix is a module edge.
                if (!declaration.isComptime && extension !in setOf("cp", "c+")) continue

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
                    resolver.resolve(sourceFile, pathText, listOf("cp", "c+"))
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
                val cycle = graph.add(SourceImportEdge(id, targetId, importLocation))
                if (cycle != null) {
                    active.remove(id)
                    return loweringFailure(
                        "CPLUS_IMPORT_CYCLE",
                        "comptime import cycle: ${cycle.joinToString(" -> ") { it.value }}",
                        importLocation
                    )
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
            moduleTexts[id] = stripped
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
                    sourceImports = graph.edges()
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
                        sourceImports = graph.edges()
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
                        sourceImports = graph.edges()
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
                    sourceImports = graph.edges()
                )
            )
            return ParsedModule(mapped, parsed, null)
        }

        fun sourceId(source: SourceFile): SourceId = try {
            source.name?.let { SourceId.fromPath(Paths.get(it)) } ?: SourceId.named("<anonymous-source>")
        } catch (_: Exception) {
            SourceId.named(source.name ?: "<anonymous-source>")
        }

        private fun loweringFailure(code: String, message: String, span: cplus.SourceSpan) =
            TreeSitterComptimeImportResult(
                null,
                loweringDiagnostics = listOf(CPlusLoweringDiagnostic(code, message, span)),
                sourceImports = graph.edges()
            )
    }

    private fun collectImports(root: CPlusAstNode): List<ImportDeclaration> {
        val declarations = mutableListOf<ImportDeclaration>()
        fun visit(node: CPlusAstNode, parent: CPlusAstNode?, moduleScope: Boolean, dormant: Boolean) {
            if (dormant) return
            if (node.syntaxKind == "cplus_comptime_import") {
                val declaration = parent?.takeIf { it.syntaxKind == "cplus_comptime_declaration" } ?: node
                node.children.firstOrNull { it.syntaxKind == "string_literal" }?.let { literal ->
                    declarations += ImportDeclaration(
                        declaration, literal, declaration.span, isComptime = true, moduleScope = moduleScope
                    )
                }
                return
            }
            if (node.syntaxKind == "cplus_at_import") {
                node.children.firstOrNull { it.syntaxKind == "string_literal" }?.let { literal ->
                    declarations += ImportDeclaration(node, literal, node.span, isComptime = false, moduleScope = moduleScope)
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
        val moduleScope: Boolean
    )

    private companion object {
        const val MAX_MODULES = 256
        const val MAX_IMPORTED_BYTES = 8L * 1024L * 1024L
        const val MAX_CONDITIONAL_PASSES = 64
        val NON_MODULE_IMPORT_CONTEXTS = setOf(
            "function_definition", "cplus_method_definition", "cplus_throws_annotated_method",
            "compound_statement", "field_declaration_list", "linkage_specification",
            "preproc_if", "preproc_ifdef", "preproc_else", "preproc_elif", "preproc_elifdef", "preproc_elifndef"
        )
        val DORMANT_COMPTIME_CONTEXTS = setOf(
            "cplus_comptime_block", "cplus_comptime_function_definition", "cplus_comptime_body",
            "cplus_legacy_type_generator", "cplus_legacy_function_generator", "cplus_code_fragment"
        )
    }
}
