package cplus.parser

import cplus.CPlusAst
import cplus.CPlusAstAdapter
import cplus.CPlusAstNode
import cplus.CPlusParseResult

/** Stable, editor-facing JSON representation of the parser-neutral AST (schema cplus.parse.v1). */
object CPlusParseJson {
    fun encode(result: CPlusParseResult): String {
        val ast = CPlusAstAdapter().adapt(result)
        return buildString {
            append('{')
            append("\"schema\":\"cplus.parse.v1\",")
            append("\"source\":").append(string(result.source.id.value)).append(',')
            append("\"revision\":").append(result.source.revision).append(',')
            append("\"offsetEncoding\":\"utf16\",")
            append("\"backend\":").append(string(result.backend.name.lowercase())).append(',')
            append("\"coverage\":").append(string(result.coverage.name.lowercase())).append(',')
            append("\"structurallyComplete\":").append(ast.structurallyComplete).append(',')
            append("\"diagnostics\":[")
            result.diagnostics.forEachIndexed { index, diagnostic ->
                if (index > 0) append(',')
                append('{')
                append("\"code\":").append(string(diagnostic.code)).append(',')
                append("\"message\":").append(string(diagnostic.message)).append(',')
                append("\"severity\":").append(string(diagnostic.severity.name.lowercase())).append(',')
                append("\"span\":")
                appendSpan(diagnostic.span)
                append('}')
            }
            append("],\"limitations\":[")
            result.limitations.forEachIndexed { index, limitation ->
                if (index > 0) append(',')
                append(string(limitation))
            }
            append("],\"ast\":")
            appendNode(ast.root)
            append('}')
        }
    }

    private fun StringBuilder.appendNode(node: CPlusAstNode) {
        append('{')
        append("\"kind\":").append(string(node.kind.name.lowercase())).append(',')
        append("\"syntaxKind\":").append(string(node.syntaxKind)).append(',')
        append("\"field\":").append(node.fieldName?.let(::string) ?: "null").append(',')
        append("\"named\":").append(node.named).append(',')
        append("\"opaque\":").append(node.opaque).append(',')
        append("\"recovered\":").append(node.recovered).append(',')
        append("\"span\":")
        appendSpan(node.span)
        append(",\"children\":[")
        node.children.forEachIndexed { index, child ->
            if (index > 0) append(',')
            appendNode(child)
        }
        append("]}")
    }

    private fun StringBuilder.appendSpan(span: cplus.SourceSpan) {
        append('{')
        append("\"file\":").append(span.file?.let(::string) ?: "null").append(',')
        append("\"startOffset\":").append(span.startOffset).append(',')
        append("\"endOffset\":").append(span.endOffset).append(',')
        append("\"startLine\":").append(span.startLine).append(',')
        append("\"startColumn\":").append(span.startColumn).append(',')
        append("\"endLine\":").append(span.endLine).append(',')
        append("\"endColumn\":").append(span.endColumn)
        append('}')
    }

    private fun string(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
