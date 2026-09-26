package cplus.parser

import cplus.CPlusAst
import cplus.CPlusImportPaths
import cplus.CPlusImportResolver
import cplus.CPlusLoweringDiagnostic
import cplus.CPlusMappedAstEmitter
import cplus.CPlusMappedEdit
import cplus.MappedText
import cplus.SourceOrigin

data class TreeSitterCImportResult(
    val source: MappedText,
    val diagnostics: List<CPlusLoweringDiagnostic>
)

/** Lowers AST-recognized legacy `@import("file.c")` directives to ordinary C includes. */
class TreeSitterCImportLowering(private val importPaths: CPlusImportPaths = CPlusImportPaths()) {
    fun lower(ast: CPlusAst, source: MappedText): TreeSitterCImportResult {
        val imports = ast.root.descendantsAndSelf()
            .filter { it.syntaxKind == "cplus_at_import" }
            .toList()
        if (imports.isEmpty()) return TreeSitterCImportResult(source, emptyList())

        val edits = mutableListOf<CPlusMappedEdit>()
        val resolver = CPlusImportResolver(importPaths)
        for (node in imports) {
            val literal = node.children.firstOrNull { it.syntaxKind == "string_literal" }
            val origin = source.originAt(node.span.startOffset)
            if (literal == null || origin == null) {
                return TreeSitterCImportResult(
                    source,
                    listOf(CPlusLoweringDiagnostic(
                        "CPLUS_IMPORT_SYNTAX",
                        "C import must contain a source string and retain a source origin",
                        node.span
                    ))
                )
            }
            val requestedPath = decodeStringLiteral(source.text.substring(literal.span.startOffset, literal.span.endOffset))
            val resolved = try {
                resolver.resolve(origin.file, requestedPath, listOf("c"))
            } catch (error: IllegalArgumentException) {
                return TreeSitterCImportResult(
                    source,
                    listOf(CPlusLoweringDiagnostic(
                        "CPLUS_IMPORT_RESOLUTION",
                        error.message ?: "cannot resolve C import '$requestedPath'",
                        origin.file.span(origin.offset, (origin.offset + 1).coerceAtMost(origin.file.text.length))
                    ))
                )
            }
            if (resolved.fileName.toString().substringAfterLast('.', "") != "c") {
                return TreeSitterCImportResult(
                    source,
                    listOf(CPlusLoweringDiagnostic(
                        "CPLUS_IMPORT_EXTENSION",
                        "C source imports must use a .c file: $resolved",
                        origin.file.span(origin.offset, (origin.offset + 1).coerceAtMost(origin.file.text.length))
                    ))
                )
            }
            val includePath = resolved.toAbsolutePath().normalize().toString()
            if (includePath.any { it == '\n' || it == '\r' || it == '\u0000' }) {
                return TreeSitterCImportResult(
                    source,
                    listOf(CPlusLoweringDiagnostic(
                        "CPLUS_IMPORT_PATH",
                        "C import paths cannot contain line breaks or NUL characters",
                        origin.file.span(origin.offset, (origin.offset + 1).coerceAtMost(origin.file.text.length))
                    ))
                )
            }
            val escaped = includePath.replace("\\", "\\\\").replace("\"", "\\\"")
            edits += CPlusMappedEdit(
                node.span,
                MappedText.generated("\n#include \"$escaped\"\n", SourceOrigin(origin.file, origin.offset))
            )
        }
        return TreeSitterCImportResult(CPlusMappedAstEmitter().emit(ast, source, edits), emptyList())
    }

    private fun decodeStringLiteral(literal: String): String {
        val content = literal.removeSurrounding("\"")
        val decoded = StringBuilder(content.length)
        var index = 0
        while (index < content.length) {
            val character = content[index++]
            if (character != '\\' || index == content.length) {
                decoded.append(character)
                continue
            }
            decoded.append(when (val escaped = content[index++]) {
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                else -> escaped
            })
        }
        return decoded.toString()
    }
}

private fun cplus.CPlusAstNode.descendantsAndSelf(): Sequence<cplus.CPlusAstNode> =
    sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }
