package cplus

/** Stable JSON projection of resolved imports for CLI and editor integrations. */
object CPlusImportGraphJson {
    fun encode(source: TranscodedSource): String = buildString {
        append('{')
        append("\"schema\":\"cplus.imports.v1\",")
        append("\"dependencyOrder\":[")
        source.sourceOrder.forEachIndexed { index, sourceId ->
            if (index > 0) append(',')
            append(string(sourceId.value))
        }
        append("],\"imports\":[")
        source.sourceImports.forEachIndexed { index, edge ->
            if (index > 0) append(',')
            append('{')
            append("\"importer\":").append(string(edge.importer.value)).append(',')
            append("\"imported\":").append(string(edge.imported.value)).append(',')
            append("\"location\":")
            val span = edge.location
            append('{')
            append("\"file\":").append(span.file?.let(::string) ?: "null").append(',')
            append("\"startOffset\":").append(span.startOffset).append(',')
            append("\"endOffset\":").append(span.endOffset).append(',')
            append("\"startLine\":").append(span.startLine).append(',')
            append("\"startColumn\":").append(span.startColumn).append(',')
            append("\"endLine\":").append(span.endLine).append(',')
            append("\"endColumn\":").append(span.endColumn)
            append("}}")
        }
        append("]}")
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
