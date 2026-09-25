package cplus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class CompilerSelectionTest {
    @Test
    fun explicitTccTakesPriorityOverPathAndCc() {
        val directory = Files.createTempDirectory("cplus-compiler-choice-")
        try {
            val tcc = executable(directory.resolve("chosen-tcc"))
            executable(directory.resolve("tcc"))
            executable(directory.resolve("gcc"))

            val selected = ExternalCompilerResolver.resolve(
                tcc = tcc.toString(),
                cc = "gcc",
                path = directory.toString(),
                windows = false
            )

            assertEquals(tcc, selected?.executable)
            assertEquals(ExternalCompilerOrigin.TCC_ENVIRONMENT, selected?.origin)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun systemTccTakesPriorityOverCcFallback() {
        val directory = Files.createTempDirectory("cplus-compiler-choice-")
        try {
            val tcc = executable(directory.resolve("tcc"))
            executable(directory.resolve("gcc"))

            val selected = ExternalCompilerResolver.resolve(
                tcc = null,
                cc = "gcc",
                path = directory.toString(),
                windows = false
            )

            assertEquals(tcc, selected?.executable)
            assertEquals(ExternalCompilerOrigin.SYSTEM_PATH, selected?.origin)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun ccFallbackSupportsQuotedExecutablePathsAndArguments() {
        val directory = Files.createTempDirectory("cplus compiler choice ")
        try {
            val compiler = executable(directory.resolve("my cc"))
            val selected = ExternalCompilerResolver.resolve(
                tcc = null,
                cc = "\"$compiler\" -pipe",
                path = "",
                windows = false
            )

            assertEquals(compiler, selected?.executable)
            assertEquals(listOf("-pipe"), selected?.arguments)
            assertEquals(ExternalCompilerOrigin.CC_ENVIRONMENT, selected?.origin)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun resolverReturnsNullWhenNoCompilerCanBeLocated() {
        assertNull(
            ExternalCompilerResolver.resolve(
                tcc = null,
                cc = "missing-cc",
                path = "",
                windows = false
            )
        )
    }

    @Test
    fun windowsSearchAddsExecutableExtensions() {
        val directory = Files.createTempDirectory("cplus-windows-compiler-choice-")
        try {
            val gcc = executable(directory.resolve("gcc.exe"))
            val selected = ExternalCompilerResolver.resolve(
                tcc = null,
                cc = "gcc",
                path = directory.toString(),
                windows = true,
                pathSeparator = ';'
            )

            assertEquals(gcc, selected?.executable)
            assertEquals(ExternalCompilerOrigin.CC_ENVIRONMENT, selected?.origin)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun missingCompilerGuideIncludesPlatformPackageManagerRecipes() {
        val previousOs = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Windows 11")
            val windowsGuide = CompilerInstallationGuide.forCurrentHost()
            assertTrue("winget install" in windowsGuide)
            assertTrue("pacman -S mingw-w64-ucrt-x86_64-gcc" in windowsGuide)

            System.setProperty("os.name", "Mac OS X")
            assertTrue("xcode-select --install" in CompilerInstallationGuide.forCurrentHost())
        } finally {
            if (previousOs == null) System.clearProperty("os.name") else System.setProperty("os.name", previousOs)
        }
    }

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        check(path.toFile().setExecutable(true))
        return path.toAbsolutePath().normalize()
    }
}
