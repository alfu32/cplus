package cplus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class CPlusToolchainManagerTest {
    @Test
    fun parsesPublishedReferencesAndIgnoresCommentsAndUnknownKinds() {
        val references = CPlusToolchainManager.parseReferences(
            """
            # catalog
            x86_64-unknown-linux-gnu-dev
            x86_64-unknown-linux-gnu-rt
            malformed
            x86_64-unknown-linux-gnu-debug
            x86_64-unknown-linux-gnu-dev
            """.trimIndent()
        )
        assertEquals(
            listOf("x86_64-unknown-linux-gnu-dev", "x86_64-unknown-linux-gnu-rt"),
            references
        )
    }

    @Test
    fun selectsBothBundlesForABaseTripleAndOneForAnExactReference() {
        val available = listOf("x86_64-unknown-linux-gnu-dev", "x86_64-unknown-linux-gnu-rt")
        assertEquals(available, CPlusToolchainManager.selectReferences("x86_64-unknown-linux-gnu", available))
        assertEquals(listOf(available[0]), CPlusToolchainManager.selectReferences(available[0], available))
    }

    @Test
    fun listsLocalManifestReferencesThroughTheCli() {
        val root = Files.createTempDirectory("cplus-toolchains")
        val previous = System.getProperty("cplus.toolchains")
        try {
            System.setProperty("cplus.toolchains", root.toString())
            Files.createDirectories(root.resolve("x86_64-unknown-linux-gnu-dev"))
            Files.createDirectories(root.resolve("x86_64-unknown-linux-gnu-rt"))
            Files.writeString(root.resolve("x86_64-unknown-linux-gnu-dev/MANIFEST.json"), "{}")
            Files.writeString(root.resolve("x86_64-unknown-linux-gnu-rt/MANIFEST.json"), "{}")
            val output = StringBuilder()
            assertEquals(0, CPlusCli(output = output, errors = StringBuilder()).run(listOf("toolchain", "list", "local")))
            assertTrue(output.toString().contains("x86_64-unknown-linux-gnu-dev"), output.toString())
            assertTrue(output.toString().contains("x86_64-unknown-linux-gnu-rt"), output.toString())
        } finally {
            if (previous == null) System.clearProperty("cplus.toolchains") else System.setProperty("cplus.toolchains", previous)
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
