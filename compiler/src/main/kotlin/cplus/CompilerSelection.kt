package cplus

import java.nio.file.Files
import java.nio.file.Path

enum class ExternalCompilerOrigin(val label: String) {
    TCC_ENVIRONMENT("the TCC environment variable"),
    SYSTEM_PATH("PATH (system tcc)"),
    CC_ENVIRONMENT("the CC environment variable")
}

data class ExternalCompiler(
    val executable: Path,
    val arguments: List<String>,
    val origin: ExternalCompilerOrigin
) {
    fun displayCommand(): String = (listOf(executable.toString()) + arguments).joinToString(" ")
}

/** Selects an explicit TCC, then system tcc, and finally the conventional CC fallback. */
object ExternalCompilerResolver {
    fun resolve(
        tcc: String? = System.getenv("TCC"),
        cc: String? = System.getenv("CC"),
        path: String? = System.getenv("PATH"),
        windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
        pathSeparator: Char = if (windows) ';' else java.io.File.pathSeparatorChar
    ): ExternalCompiler? {
        if (!tcc.isNullOrBlank()) {
            resolveCommand(tcc, path, windows, pathSeparator)?.let {
                return it.copy(origin = ExternalCompilerOrigin.TCC_ENVIRONMENT)
            }
        }

        findExecutable("tcc", path, windows, pathSeparator)?.let {
            return ExternalCompiler(it, emptyList(), ExternalCompilerOrigin.SYSTEM_PATH)
        }

        if (!cc.isNullOrBlank()) {
            resolveCommand(cc, path, windows, pathSeparator)?.let {
                return it.copy(origin = ExternalCompilerOrigin.CC_ENVIRONMENT)
            }
        }
        return null
    }

    private fun resolveCommand(value: String, path: String?, windows: Boolean, pathSeparator: Char): ExternalCompiler? {
        val parts = tokenize(value)
        val executable = parts.firstOrNull() ?: return null
        val resolved = findExecutable(executable, path, windows, pathSeparator) ?: return null
        return ExternalCompiler(resolved, parts.drop(1), ExternalCompilerOrigin.TCC_ENVIRONMENT)
    }

    private fun findExecutable(value: String, path: String?, windows: Boolean, pathSeparator: Char): Path? {
        val candidate = Path.of(value)
        val hasDirectory = candidate.isAbsolute || value.contains('/') || value.contains('\\')
        val directories = if (hasDirectory) {
            listOf(candidate.parent ?: Path.of("."))
        } else {
            path.orEmpty().split(pathSeparator).filter(String::isNotBlank).map(Path::of)
        }
        val extension = Path.of(value).fileName.toString().substringAfterLast('.', "")
        val names = if (windows && extension.isEmpty()) {
            listOf(value, "$value.exe", "$value.cmd", "$value.bat", "$value.com")
        } else {
            listOf(value)
        }

        for (directory in directories) {
            for (name in names) {
                val pathCandidate = if (hasDirectory) Path.of(name) else directory.resolve(name)
                if (Files.isRegularFile(pathCandidate) && (windows || Files.isExecutable(pathCandidate))) {
                    return pathCandidate.toAbsolutePath().normalize()
                }
            }
        }
        return null
    }

    private fun tokenize(command: String): List<String> {
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        val text = command.trim()
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '\\' && quote == '"' && text.getOrNull(index + 1) in listOf('"', '\\')) {
                token.append(text[index + 1])
                index += 2
                continue
            } else if (quote != null && character == quote) {
                quote = null
            } else if (quote == null && (character == '"' || character == '\'')) {
                quote = character
            } else if (quote == null && character.isWhitespace()) {
                if (token.isNotEmpty()) {
                    result += token.toString()
                    token.setLength(0)
                }
            } else {
                token.append(character)
            }
            index++
        }
        if (quote != null) return emptyList()
        if (token.isNotEmpty()) result += token.toString()
        return result
    }
}

object CompilerInstallationGuide {
    fun forCurrentHost(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> windows()
            os.contains("mac") || os.contains("darwin") -> macos()
            os.contains("linux") -> linux()
            else -> "Install a C compiler and its platform C development headers, then put it on PATH or set CC to its executable."
        }
    }

    private fun linux(): String {
        val release = runCatching { Files.readAllLines(Path.of("/etc/os-release")) }.getOrDefault(emptyList())
            .associate { line ->
                val key = line.substringBefore('=')
                key to line.substringAfter('=', "").trim('"')
            }
        val ids = (release["ID"].orEmpty() + " " + release["ID_LIKE"].orEmpty()).lowercase()
        val recipe = when {
            listOf("debian", "ubuntu", "mint").any(ids::contains) ->
                "sudo apt-get update && sudo apt-get install tcc build-essential libc6-dev"
            listOf("fedora", "rhel", "centos", "rocky", "alma").any(ids::contains) ->
                "sudo dnf install tcc gcc glibc-devel"
            listOf("arch", "manjaro", "endeavouros").any(ids::contains) ->
                "sudo pacman -S tcc base-devel glibc"
            ids.contains("suse") -> "sudo zypper install tcc gcc glibc-devel"
            else -> null
        }
        val options = recipe ?: """
            Debian/Ubuntu: sudo apt-get update && sudo apt-get install tcc build-essential libc6-dev
            Fedora/RHEL:   sudo dnf install tcc gcc glibc-devel
            Arch:          sudo pacman -S tcc base-devel glibc
            Arch/AUR:      yay -S tcc (if your derivative lacks the repository package)
            openSUSE:      sudo zypper install tcc gcc glibc-devel
        """.trimIndent()
        return "Install TinyCC and the system C development files with:\n$options"
    }

    private fun macos(): String = """
        Install Apple Command Line Tools (provides the macOS SDK headers):
          xcode-select --install
        Set C-plus to use Apple's clang:
          export CC=/usr/bin/clang
        Or install LLVM with Homebrew and set CC to its clang:
          brew install llvm
          export CC="$(brew --prefix llvm)/bin/clang"
        TinyCC can also be used if installed separately; set TCC=/path/to/tcc.
    """.trimIndent()

    private fun windows(): String = """
        Install MSYS2 with winget:
          winget install --exact --id MSYS2.MSYS2
        In the MSYS2 UCRT64 terminal, install the compiler and Windows C runtime headers:
          pacman -S mingw-w64-ucrt-x86_64-gcc
        Add C:\msys64\ucrt64\bin to PATH, or set CC to its gcc.exe.
        The C-plus socket facade links WinSock automatically. Install optional libraries such as Raylib separately.
    """.trimIndent()
}
