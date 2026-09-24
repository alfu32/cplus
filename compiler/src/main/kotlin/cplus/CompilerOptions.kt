package cplus

/** Preserves order while deduplicating compiler arguments as logical options. */
internal object CompilerOptions {
    private val pairedOptions = setOf(
        "-framework", "-l", "-L", "-F", "-I", "-D", "-U", "-include", "-isystem", "-iquote",
        "-isysroot", "--sysroot", "-sysroot", "--target", "-target", "-arch", "-Xlinker", "-Xclang"
    )

    fun distinct(arguments: List<String>): List<String> = merge(emptyList(), arguments)

    fun merge(base: List<String>, additional: List<String>): List<String> {
        val result = base.toMutableList()
        val seen = groups(base).toMutableSet()
        groups(additional).forEach { group ->
            if (seen.add(group)) result += group
        }
        return result
    }

    private fun groups(arguments: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            if (argument in pairedOptions && index + 1 < arguments.size) {
                result += listOf(argument, arguments[index + 1])
                index += 2
            } else {
                result += listOf(argument)
                index++
            }
        }
        return result
    }
}
