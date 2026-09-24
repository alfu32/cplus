package cplus

/** Target operating-system information available to comptime expressions as the string `os`. */
object CPlusTarget {
    @JvmStatic
    fun hostOs(): String = normalizeOs(System.getProperty("os.name"))

    /** Resolve `os` from TinyCC target arguments, falling back to the JVM host when absent. */
    @JvmStatic
    fun osFromCompilerOptions(options: List<String>): String {
        var index = 0
        while (index < options.size) {
            val option = options[index]
            when {
                option == "--target" -> {
                    val target = options.getOrNull(index + 1)
                        ?: throw IllegalArgumentException("--target requires a target triple")
                    if (target.isBlank()) throw IllegalArgumentException("--target requires a target triple")
                    return normalizeOs(target)
                }
                option.startsWith("--target=") -> {
                    val target = option.substringAfter('=')
                    if (target.isBlank()) throw IllegalArgumentException("--target requires a target triple")
                    return normalizeOs(target)
                }
            }
            index++
        }
        return hostOs()
    }

    /** Normalize a target triple or JVM OS name to its stable comptime spelling. */
    @JvmStatic
    fun normalizeOs(value: String?): String {
        val normalized = value.orEmpty().lowercase()
        return when {
            "windows" in normalized || "win32" in normalized || "mingw" in normalized || "w64" in normalized ||
                normalized == "win" || normalized.startsWith("win-") -> "windows"
            "android" in normalized -> "android"
            "ios" in normalized -> "ios"
            "linux" in normalized -> "linux"
            "freebsd" in normalized -> "freebsd"
            "openbsd" in normalized -> "openbsd"
            "netbsd" in normalized -> "netbsd"
            "dragonfly" in normalized -> "dragonfly"
            "sunos" in normalized || "solaris" in normalized -> "solaris"
            "macos" in normalized || "mac" in normalized || "darwin" in normalized ||
                "osx" in normalized || "apple" in normalized -> "macos"
            else -> "unknown"
        }
    }
}
