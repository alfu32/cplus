package cplus

/** Target operating-system information available to comptime expressions as the string `os`. */
object CPlusTarget {
    @JvmStatic
    fun hostOs(): String = normalizeOs(System.getProperty("os.name"))

    /** Normalize the host architecture for compile-time ABI reflection. */
    @JvmStatic
    fun hostArch(): String = normalizeArch(System.getProperty("os.arch"))

    /**
     * Return the default signedness of C's plain `char` for the supported target
     * model, or null when the model intentionally does not guess.
     *
     * AArch64 Linux uses unsigned plain char by default; the supported x86,
     * Windows, and Apple targets use signed plain char unless the selected C
     * compiler is explicitly configured otherwise.
     */
    @JvmStatic
    fun plainCharIsUnsigned(os: String?, arch: String?): Boolean? {
        val normalizedOs = normalizeOs(os)
        val normalizedArch = normalizeArch(arch)
        if (normalizedOs !in setOf("linux", "windows", "macos")) return null
        if (normalizedArch !in setOf("x86_64", "arm64")) return null
        return normalizedOs == "linux" && normalizedArch == "arm64"
    }

    /** Normalize an architecture name or target triple to a stable spelling. */
    @JvmStatic
    fun normalizeArch(value: String?): String {
        val normalized = value.orEmpty().lowercase()
        val architecture = normalized.substringBefore('-')
        return when (architecture) {
            "x86_64", "amd64", "x64" -> "x86_64"
            "aarch64", "arm64" -> "arm64"
            "i386", "i486", "i586", "i686", "x86" -> "x86"
            "arm", "armv6", "armv7", "armv7l" -> "arm"
            "riscv64" -> "riscv64"
            else -> "unknown"
        }
    }

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
