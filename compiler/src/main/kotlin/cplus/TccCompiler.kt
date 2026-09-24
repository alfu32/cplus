package cplus

import org.tinycc.DiagnosticListener
import org.tinycc.TinyCC
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
    NOTE,
    UNKNOWN
}

data class CompilerDiagnostic(
    val severity: DiagnosticSeverity,
    val message: String,
    val file: String?,
    val line: Int?,
    val column: Int?,
    val raw: String
)

data class TccCompilationResult(
    val exitCode: Int,
    val diagnostics: List<CompilerDiagnostic>
)

/** Uses an embedded TinyCC runtime when present, otherwise invokes system TinyCC. */
class TccCompiler {
    fun compileExecutable(
        source: TranscodedSource,
        output: Path,
        options: List<String>,
        logger: CompilationLogger = SilentCompilationLogger
    ): TccCompilationResult = logger.pass("tcc-compile") {
        val effectiveOptions = CompilerOptions.merge(options, source.compilerOptions)
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        if (!hasEmbeddedRuntimeForCurrentPlatform()) {
            return@pass compileWithExternalTcc(source, output, effectiveOptions)
        }
        if (effectiveOptions.any { it == "--target" || it.startsWith("--target=") || it == "-lraylib" } ||
            effectiveOptions.zipWithNext().any { (option, value) -> option == "-l" && value == "raylib" }
        ) {
            return@pass compileWithEmbeddedTccCli(source, output, effectiveOptions)
        }

        val rawDiagnostics = StringBuilder()
        val diagnostics = DiagnosticListener { message -> rawDiagnostics.append(message) }
        val nativeOptions = if (isLinuxHost() && effectiveOptions.none { it == "-static" || it == "-dynamic" || it == "-shared" }) {
            effectiveOptions + "-static"
        } else {
            effectiveOptions
        }
        val optionString = nativeOptions.joinToString(" ", transform = ::quoteOption)
        val exitCode = TinyCC.compile(
            source.code,
            TinyCC.OutputType.EXECUTABLE,
            output,
            optionString,
            diagnostics
        )
        TccCompilationResult(
            exitCode = exitCode,
            diagnostics = TccDiagnosticParser.parse(rawDiagnostics.toString(), source)
        )
    }

    private fun compileWithEmbeddedTccCli(
        source: TranscodedSource,
        output: Path,
        options: List<String>
    ): TccCompilationResult {
        val outputPath = output.toAbsolutePath().normalize()
        val sourceFile = Files.createTempFile(outputPath.parent, "cplus-", ".c")
        var packagedRaylibDirectory: Path? = null
        try {
            Files.writeString(sourceFile, source.code)
            val raylibTarget = targetOption(options) ?: hostTarget()
            val effectiveOptions = if (hasCustomSysroot(options)) {
                options
            } else {
                materializeBundledRaylib(options, raylibTarget, source.code)?.let { (rewritten, directory) ->
                    packagedRaylibDirectory = directory
                    rewritten
                } ?: options
            }
            val (compileOptions, linkOptions) = splitLinkOptions(effectiveOptions)
            val arguments = buildList {
                addAll(compileOptions)
                add("-o")
                add(outputPath.toString())
                add(sourceFile.toString())
                addAll(linkOptions)
            }
            val exitCode = TinyCC.executeTcc(*arguments.toTypedArray())
            val outputExists = Files.isRegularFile(outputPath) && Files.size(outputPath) > 0
            if (exitCode != 0) return TccCompilationResult(exitCode, emptyList())
            if (!outputExists) {
                return TccCompilationResult(
                    1,
                    listOf(targetDiagnostic("TinyCC did not produce the requested output file"))
                )
            }

            val target = targetOption(options)
            val outputFormatError = target?.let { targetOutputFormatError(it, outputPath, options) }
            if (outputFormatError != null) {
                return TccCompilationResult(
                    1,
                    listOf(targetDiagnostic(outputFormatError))
                )
            }
            return TccCompilationResult(0, emptyList())
        } finally {
            Files.deleteIfExists(sourceFile)
            packagedRaylibDirectory?.let(::deleteTree)
        }
    }

    private fun materializeBundledRaylib(
        options: List<String>,
        target: String?,
        sourceCode: String
    ): Pair<List<String>, Path>? {
        val raylibInclude = Regex("""#\s*include\s*[<"](?:raylib|raymath|rlgl)\.h[>"]""")
        val linksRaylib = options.any { it == "-lraylib" } ||
            options.zipWithNext().any { (option, value) -> option == "-l" && value == "raylib" }
        if (target == null || (!linksRaylib && !raylibInclude.containsMatchIn(sourceCode))) return null
        val (includeRoot, libraryResource) = when {
            target.startsWith("linux-") ->
                "native/$target/tinycc/sysroot/usr/include" to "native/$target/tinycc/sysroot/usr/lib/libraylib.a"
            target.startsWith("windows-") ->
                "native/$target/tinycc/sysroot/include" to "native/$target/tinycc/sysroot/lib/libraylib.a"
            target.startsWith("macos-") ->
                "native/$target/tinycc/lib/tcc/include" to "native/$target/tinycc/lib/tcc/lib/libraylib.a"
            else -> return null
        }
        val classLoader = TccCompiler::class.java.classLoader
        val headerNames = listOf("raylib.h", "raymath.h", "rlgl.h")
        if (headerNames.none { classLoader.getResource("$includeRoot/$it") != null }) return null

        val directory = Files.createTempDirectory("cplus-raylib-")
        try {
            val includeDirectory = Files.createDirectories(directory.resolve("include"))
            headerNames.forEach { name ->
                classLoader.getResourceAsStream("$includeRoot/$name")?.use { stream ->
                    Files.copy(stream, includeDirectory.resolve(name))
                }
            }

            val rewritten = mutableListOf<String>()
            rewritten += "-I${includeDirectory.toAbsolutePath()}"
            val archive = classLoader.getResourceAsStream(libraryResource)?.use { stream ->
                directory.resolve("libraylib.a").also { Files.copy(stream, it) }
            }
            var index = 0
            while (index < options.size) {
                when {
                    archive != null && options[index] == "-lraylib" -> {
                        rewritten += archive.toAbsolutePath().toString()
                        index++
                    }
                    archive != null && options[index] == "-l" && options.getOrNull(index + 1) == "raylib" -> {
                        rewritten += archive.toAbsolutePath().toString()
                        index += 2
                    }
                    else -> rewritten += options[index++]
                }
            }
            return rewritten to directory
        } catch (error: Throwable) {
            deleteTree(directory)
            throw error
        }
    }

    private fun hasCustomSysroot(options: List<String>): Boolean =
        options.any { it == "--sysroot" || it.startsWith("--sysroot=") }

    private fun deleteTree(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun hostTarget(): String? {
        val os = System.getProperty("os.name").lowercase()
        val nativeOs = when {
            "win" in os -> "windows"
            "mac" in os || "darwin" in os -> "macos"
            "linux" in os -> "linux"
            else -> return null
        }
        val arch = when (System.getProperty("os.arch").lowercase()) {
            "amd64", "x86_64", "x64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> return null
        }
        return "$nativeOs-$arch"
    }

    /** Static link archives must follow the source/object files that reference them. */
    private fun splitLinkOptions(options: List<String>): Pair<List<String>, List<String>> {
        val compile = mutableListOf<String>()
        val link = mutableListOf<String>()
        var index = 0
        while (index < options.size) {
            val option = options[index]
            when {
                option == "-l" || option == "-framework" -> {
                    link += option
                    options.getOrNull(index + 1)?.let(link::add)
                    index += if (index + 1 < options.size) 2 else 1
                }
                option.startsWith("-l") || option.startsWith("-Wl,") -> {
                    link += option
                    index++
                }
                option.endsWith(".a") || option.endsWith(".so") || option.endsWith(".dylib") || option.endsWith(".lib") -> {
                    link += option
                    index++
                }
                else -> {
                    compile += option
                    index++
                }
            }
        }
        return compile to link
    }

    private fun targetOption(options: List<String>): String? {
        val targetFlag = options.indexOf("--target")
        return when {
            targetFlag >= 0 -> options.getOrNull(targetFlag + 1)
            else -> options.firstOrNull { it.startsWith("--target=") }?.substringAfter('=')
        }
    }

    private fun targetDiagnostic(message: String) = CompilerDiagnostic(
        DiagnosticSeverity.ERROR,
        message,
        null,
        null,
        null,
        message
    )

    private fun targetOutputFormatError(target: String, output: Path, options: List<String>): String? {
        val header = Files.newInputStream(output).use { it.readNBytes(4) }
        val compileOnly = "-c" in options
        val formatMatches = when {
            target.startsWith("linux-") -> header.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
            target.startsWith("windows-") && compileOnly ->
                header.size >= 2 && when {
                    target.endsWith("x86_64") -> header[0] == 0x64.toByte() && header[1] == 0x86.toByte()
                    else -> header[0] == 0x64.toByte() && header[1] == 0xaa.toByte()
                }
            target.startsWith("windows-") -> header.size >= 2 && header[0] == 'M'.code.toByte() && header[1] == 'Z'.code.toByte()
            target.startsWith("macos-") -> header.contentEquals(byteArrayOf(0xcf.toByte(), 0xfa.toByte(), 0xed.toByte(), 0xfe.toByte()))
            else -> true
        }
        return if (formatMatches) null else "TinyCC output format does not match requested target '$target'"
    }

    private fun compileWithExternalTcc(
        source: TranscodedSource,
        output: Path,
        options: List<String>
    ): TccCompilationResult {
        val outputPath = output.toAbsolutePath().normalize()
        val sourceFile = Files.createTempFile(outputPath.parent, "cplus-", ".c")
        try {
        Files.writeString(sourceFile, source.code)
        val tcc = System.getenv("TCC")?.takeIf(String::isNotBlank) ?: "tcc"
        val (compileOptions, linkOptions) = splitLinkOptions(options)
        val command = buildList {
            add(tcc)
            addAll(compileOptions)
            add("-o")
            add(outputPath.toString())
            add(sourceFile.toString())
            addAll(linkOptions)
        }
            val process = try {
                ProcessBuilder(command).redirectErrorStream(true).start()
            } catch (error: IOException) {
                throw IllegalStateException(
                    "No embedded TinyCC runtime is available for this platform and external 'tcc' could not be started. " +
                        "Install TinyCC and make it available on PATH, or set TCC to its executable path.",
                    error
                )
            }
            val diagnostics = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            return TccCompilationResult(
                exitCode = exitCode,
                diagnostics = TccDiagnosticParser.parse(diagnostics, source)
            )
        } finally {
            Files.deleteIfExists(sourceFile)
        }
    }

    private fun hasEmbeddedRuntimeForCurrentPlatform(): Boolean {
        val os = System.getProperty("os.name").lowercase()
        val nativeOs = when {
            "win" in os -> "windows"
            "mac" in os || "darwin" in os -> "macos"
            "linux" in os -> "linux"
            else -> return false
        }
        val arch = when (System.getProperty("os.arch").lowercase()) {
            "amd64", "x86_64", "x64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> return false
        }
        val resource = "native/$nativeOs-$arch/files.list"
        return TccCompiler::class.java.classLoader.getResource(resource) != null
    }

    private fun isLinuxHost(): Boolean = System.getProperty("os.name").lowercase().contains("linux")

    private fun quoteOption(option: String): String {
        if (option.isEmpty()) return "\"\""
        if (option.none { it.isWhitespace() || it == '"' || it == '\\' }) return option
        return buildString {
            append('"')
            option.forEach { character ->
                if (character == '"' || character == '\\') append('\\')
                append(character)
            }
            append('"')
        }
    }
}

private object TccDiagnosticParser {
    private val diagnosticStart = Regex(
        """(?<![A-Za-z0-9_./\\-])(?:<[^>\n]+>|[^:\n]+):\d+(?::\d+)?:\s*(?:warning|error|note|fatal error):"""
    )
    private val diagnosticLine = Regex(
        "^(.+?):(\\d+)(?::(\\d+))?:\\s*(?:(warning|error|note|fatal error):\\s*)?(.*)$"
    )

    fun parse(raw: String, source: TranscodedSource): List<CompilerDiagnostic> {
        if (raw.isBlank()) return emptyList()
        val normalized = raw.replace("\r", "")
        val starts = diagnosticStart.findAll(normalized).toList()
        if (starts.isEmpty()) return listOf(
            CompilerDiagnostic(DiagnosticSeverity.UNKNOWN, normalized.trim(), null, null, null, normalized)
        )
        return starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1)?.range?.first ?: normalized.length
            parseRecord(normalized.substring(start.range.first, end).trim(), source)
        }
    }

    private fun parseRecord(record: String, source: TranscodedSource): CompilerDiagnostic {
        val match = diagnosticLine.matchEntire(record)
        if (match == null) {
            return CompilerDiagnostic(DiagnosticSeverity.UNKNOWN, record, null, null, null, record)
        }

        val file = match.groupValues[1]
        val line = match.groupValues[2].toIntOrNull()
        val column = match.groupValues[3].toIntOrNull()
        val severity = when (match.groupValues[4]) {
            "warning" -> DiagnosticSeverity.WARNING
            "error", "fatal error" -> DiagnosticSeverity.ERROR
            "note" -> DiagnosticSeverity.NOTE
            else -> DiagnosticSeverity.UNKNOWN
        }
        val mapped = if (file == "<string>" && line != null) {
            source.sourceMap.sourceForGeneratedLine(line) ?: source.sourceMap.sourceForSourceLine(line)
        } else null
        return CompilerDiagnostic(
            severity = severity,
            message = match.groupValues[5].trim(),
            file = mapped?.file ?: file,
            line = mapped?.startLine ?: line,
            column = mapped?.startColumn ?: column,
            raw = record
        )
    }
}
