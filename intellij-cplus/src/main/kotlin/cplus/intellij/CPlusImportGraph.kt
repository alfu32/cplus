package cplus.intellij

import com.google.gson.JsonParser

data class CPlusImportEdge(
    val importer: String,
    val imported: String,
    val line: Int,
    val column: Int
) {
    override fun toString(): String = "$imported  (from $importer:$line:$column)"
}

data class CPlusImportGraph(
    val dependencyOrder: List<String>,
    val imports: List<CPlusImportEdge>
)

internal object CPlusImportGraphJson {
    fun decode(json: String): CPlusImportGraph {
        val root = JsonParser.parseString(json).asJsonObject
        require(root.get("schema")?.asString == "cplus.imports.v1") { "unsupported C-plus import graph schema" }
        val order = root.getAsJsonArray("dependencyOrder")?.map { it.asString }
            ?: throw IllegalArgumentException("import graph is missing dependencyOrder")
        val imports = root.getAsJsonArray("imports")?.map { element ->
            val edge = element.asJsonObject
            val location = edge.getAsJsonObject("location")
                ?: throw IllegalArgumentException("import graph edge is missing location")
            CPlusImportEdge(
                edge.get("importer")?.asString ?: throw IllegalArgumentException("import graph edge is missing importer"),
                edge.get("imported")?.asString ?: throw IllegalArgumentException("import graph edge is missing imported"),
                location.get("startLine")?.asInt ?: throw IllegalArgumentException("import graph edge is missing startLine"),
                location.get("startColumn")?.asInt ?: throw IllegalArgumentException("import graph edge is missing startColumn")
            )
        } ?: throw IllegalArgumentException("import graph is missing imports")
        return CPlusImportGraph(order, imports)
    }
}
