package cplus

import java.nio.file.Files
import java.nio.file.Path

/** Locates the self-contained developer guide shipped beside the CLI. */
internal object CPlusDeveloperGuide {
    private const val fileName = "CPLUS-DEVELOPER-GUIDE.md"

    fun locate(): Path? {
        val configured = sequenceOf(System.getProperty("cplus.guide"), System.getenv("CPLUS_GUIDE"))
            .filterNotNull().filter(String::isNotBlank).map(Path::of)
        val home = sequenceOf(System.getProperty("cplus.home"), System.getenv("CPLUS_HOME"))
            .filterNotNull().filter(String::isNotBlank).map { Path.of(it).resolve("documentation") }
        val code = runCatching { Path.of(CPlusDeveloperGuide::class.java.protectionDomain.codeSource.location.toURI()) }
            .getOrNull()?.let { location ->
                val start = if (Files.isDirectory(location)) location else location.parent
                generateSequence(start) { it.parent }.map { it.resolve("documentation") }
            } ?: emptySequence()
        val working = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .map { it.resolve("documentation") }
        return (configured + home + code + working)
            .map { it.resolve(fileName).toAbsolutePath().normalize() }
            .firstOrNull(Files::isRegularFile)
    }

    fun read(): String = locate()?.let(Files::readString)
        ?: throw IllegalArgumentException("C-plus developer guide is not installed; expected documentation/$fileName")
}
