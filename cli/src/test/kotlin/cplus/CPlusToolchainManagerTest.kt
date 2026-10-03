package cplus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readText

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

    @Test
    fun resolvesInstalledDevelopmentSysrootFromCanonicalAndCPlusTargets() {
        val root = Files.createTempDirectory("cplus-toolchain-sysroot")
        val previous = System.getProperty("cplus.toolchains")
        try {
            System.setProperty("cplus.toolchains", root.toString())
            val sysroot = root.resolve("x86_64-unknown-linux-gnu-dev")
            Files.createDirectories(sysroot)
            Files.writeString(sysroot.resolve("MANIFEST.json"), "{\"reference\": \"x86_64-unknown-linux-gnu-dev\"}")
            assertEquals(sysroot, CPlusToolchainManager.developmentSysrootFor("x86_64-unknown-linux-gnu"))
            assertEquals(sysroot, CPlusToolchainManager.developmentSysrootFor("linux-x86_64"))
            assertEquals("arm64-apple-darwin", CPlusToolchainManager.canonicalTriple("macos-aarch64"))
        } finally {
            if (previous == null) System.clearProperty("cplus.toolchains") else System.setProperty("cplus.toolchains", previous)
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun writesToolchainLockEntriesInAlphabeticalOrderAndReadsThemBack() {
        val root = Files.createTempDirectory("cplus-lock")
        try {
            val path = root.resolve("cplus.lock")
            CPlusLockfile.writeToolchains(
                path,
                mapOf(
                    "z-target-rt" to CPlusToolchainLockEntry("0.1.1", "z-digest"),
                    "a-target-dev" to CPlusToolchainLockEntry("0.1.1", "a-digest", "a-target", "dev")
                )
            )
            val text = path.readText()
            assertTrue(text.indexOf("a-target-dev") < text.indexOf("z-target-rt"), text)
            assertEquals("0.1.1", CPlusLockfile.read(path)["a-target-dev"]?.release)
            assertEquals("a-digest", CPlusLockfile.read(path)["a-target-dev"]?.sha256)
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun writesPackageLockDependenciesInAlphabeticalOrder() {
        val root = Files.createTempDirectory("cplus-package-lock")
        try {
            val path = root.resolve("cplus.lock")
            CPlusLockfile.writeDependencies(
                path,
                mapOf(
                    "zeta" to CPlusPackageReference("zeta", "https://example.test/zeta.zip"),
                    "alpha" to CPlusPackageReference("alpha", "../alpha", "1.0.0")
                )
            )
            val text = path.readText()
            assertTrue(text.indexOf("alpha") < text.indexOf("zeta"), text)
            assertTrue("version = \"1.0.0\"" in text, text)
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
