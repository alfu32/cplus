package cplus

/** A resolved C-plus import and the source location that requested it. */
data class SourceImportEdge(
    val importer: SourceId,
    val imported: SourceId,
    val location: SourceSpan
)

/** Deterministic directed graph of C-plus source imports with canonical source identities. */
class SourceImportGraph {
    private val outgoing = LinkedHashMap<SourceId, LinkedHashMap<SourceId, SourceImportEdge>>()

    /** Adds an edge and returns a closed cycle path if the edge creates a cycle. */
    @Synchronized
    fun add(edge: SourceImportEdge): List<SourceId>? {
        val pathBack = findPath(edge.imported, edge.importer)
        outgoing.getOrPut(edge.importer, ::linkedMapOf).putIfAbsent(edge.imported, edge)
        return pathBack?.let { listOf(edge.importer) + it }
    }

    @Synchronized
    fun edges(): List<SourceImportEdge> = outgoing.values.flatMap { it.values }

    @Synchronized
    fun importsOf(source: SourceId): List<SourceImportEdge> = outgoing[source]?.values?.toList().orEmpty()

    /** Returns imported dependencies before importers, with stable ordering for independent nodes. */
    @Synchronized
    fun dependencyOrder(roots: Collection<SourceId> = emptyList()): List<SourceId> {
        val vertices = (outgoing.keys + outgoing.values.flatMap { it.keys } + roots)
            .distinct().sortedBy(SourceId::value)
        val state = mutableMapOf<SourceId, Int>()
        val result = mutableListOf<SourceId>()
        val path = ArrayDeque<SourceId>()

        fun visit(source: SourceId) {
            when (state[source]) {
                2 -> return
                1 -> {
                    val cycle = (path.dropWhile { it != source } + source).joinToString(" -> ") { it.value }
                    throw IllegalStateException("source import cycle: $cycle")
                }
            }
            state[source] = 1
            path.addLast(source)
            outgoing[source].orEmpty().keys.sortedBy(SourceId::value).forEach(::visit)
            path.removeLast()
            state[source] = 2
            result += source
        }

        vertices.forEach(::visit)
        return result
    }

    private fun findPath(start: SourceId, target: SourceId): List<SourceId>? {
        val visited = mutableSetOf<SourceId>()
        fun visit(current: SourceId): List<SourceId>? {
            if (current == target) return listOf(current)
            if (!visited.add(current)) return null
            for (edge in outgoing[current].orEmpty().values) {
                val suffix = visit(edge.imported) ?: continue
                return listOf(current) + suffix
            }
            return null
        }
        return visit(start)
    }
}
