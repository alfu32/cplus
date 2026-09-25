package cplus.intellij

data class CPlusTestFixture(val name: String, val start: Int, val end: Int)

/** Lightweight scanner for fixture headers and balanced bodies, ignoring comments and literals. */
internal object CPlusTestFixtures {
    fun find(source: String): List<CPlusTestFixture> {
        val fixtures = mutableListOf<CPlusTestFixture>()
        val header = Regex("@test\\s+(?:\"((?:\\\\.|[^\"\\\\])*)\"|([^{}\\n]+?))\\s*\\{")
        for (match in header.findAll(source)) {
            val start = match.range.first
            val open = match.range.last
            val end = matchingBrace(source, open) ?: continue
            val name = match.groups[1]?.value?.replace(Regex("\\\\([\\\\\"])"), "$1")
                ?: match.groups[2]?.value?.trim().orEmpty()
            if (name.isNotEmpty()) fixtures += CPlusTestFixture(name, start, end)
        }
        return fixtures
    }

    private fun matchingBrace(source: String, open: Int): Int? {
        var depth = 0
        var quote: Char? = null
        var lineComment = false
        var blockComment = false
        var index = open
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when {
                lineComment -> if (current == '\n') lineComment = false
                blockComment -> if (current == '*' && next == '/') { blockComment = false; index++ }
                quote != null -> when {
                    current == '\\' -> index++
                    current == quote -> quote = null
                }
                current == '/' && next == '/' -> { lineComment = true; index++ }
                current == '/' && next == '*' -> { blockComment = true; index++ }
                current == '\'' || current == '"' -> quote = current
                current == '{' -> depth++
                current == '}' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
            index++
        }
        return null
    }
}
