package cplus

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CPlusFrontendDiagnosticRecoveryTest {
    @Test
    fun explicitAstTranscodeEmitsThePreservedStreamAndReportsMappedErrors(@TempDir directory: Path) {
        val source = directory.resolve("broken.cp")
        Files.writeString(source, "int retained;\nint broken( {\n")
        val output = StringBuilder()
        val errors = StringBuilder()
        val generated = directory.resolve("broken.c")
        val status = CPlusCli(output = output, errors = errors)
            .run(listOf("--frontend=tree-sitter", "transcode", source.toString(), "-o", generated.toString()))
        assertEquals(0, status, errors.toString())
        val emitted = Files.readString(generated)
        assertTrue(emitted.contains("int retained;"), emitted)
        assertTrue(emitted.contains("int broken( {"), emitted)
        assertTrue(errors.contains("error:") && errors.contains(source.toString()), errors.toString())
    }
}
