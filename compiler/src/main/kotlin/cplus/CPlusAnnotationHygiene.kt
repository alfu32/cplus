package cplus

private val CPLUS_ANNOTATION_MACROS = mapOf(
    "pub" to "CPLUS_PUB",
    "priv" to "CPLUS_PRIV",
    "mut" to "CPLUS_MUT",
    "borrowed" to "CPLUS_BORROWED",
    "owned" to "CPLUS_OWNED",
    "stat" to "CPLUS_STAT",
    "scratch" to "CPLUS_SCRATCH",
    "hot" to "CPLUS_HOT",
    "warm" to "CPLUS_WARM",
    "cold" to "CPLUS_COLD"
)

/** Rewrites C-plus annotation tokens without touching comments or literals. */
fun rewriteCPlusAnnotationMacros(source: MappedText): MappedText {
    val output = MappedTextBuilder()
    var index = 0
    var quote: Char? = null
    var escaped = false
    while (index < source.text.length) {
        val current = source.text[index]
        if (quote != null) {
            output.append(source, index, index + 1)
            if (escaped) escaped = false
            else if (current == '\\') escaped = true
            else if (current == quote) quote = null
            index++
            continue
        }
        if (current == '"' || current == '\'') {
            quote = current
            output.append(source, index, index + 1)
            index++
            continue
        }
        if (current == '/' && index + 1 < source.text.length && source.text[index + 1] == '/') {
            val end = source.text.indexOf('\n', index).let { if (it < 0) source.text.length else it }
            output.append(source, index, end)
            index = end
            continue
        }
        if (current == '/' && index + 1 < source.text.length && source.text[index + 1] == '*') {
            val close = source.text.indexOf("*/", index + 2)
            val end = if (close < 0) source.text.length else close + 2
            output.append(source, index, end)
            index = end
            continue
        }
        if (current == '_' || current.isLetter()) {
            var end = index + 1
            while (end < source.text.length && (source.text[end] == '_' || source.text[end].isLetterOrDigit())) end++
            val replacement = CPLUS_ANNOTATION_MACROS[source.text.substring(index, end)]
            if (replacement == null) output.append(source, index, end)
            else output.appendGenerated(replacement, source.originAt(index))
            index = end
            continue
        }
        output.append(source, index, index + 1)
        index++
    }
    return output.build()
}
