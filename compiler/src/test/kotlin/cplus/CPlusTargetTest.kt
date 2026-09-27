package cplus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CPlusTargetTest {
    @Test
    fun `normalizes the shipped host and target spellings`() {
        assertEquals("linux", CPlusTarget.normalizeOs("x86_64-linux-gnu"))
        assertEquals("windows", CPlusTarget.normalizeOs("x86_64-w64-mingw32"))
        assertEquals("macos", CPlusTarget.normalizeOs("aarch64-apple-darwin"))
        assertEquals("x86_64", CPlusTarget.normalizeArch("amd64"))
        assertEquals("arm64", CPlusTarget.normalizeArch("aarch64"))
    }

    @Test
    fun `derives compile time os from target compiler options`() {
        assertEquals("windows", CPlusTarget.osFromCompilerOptions(listOf("--target=x86_64-w64-mingw32")))
        assertEquals("macos", CPlusTarget.osFromCompilerOptions(listOf("--target", "aarch64-apple-darwin")))
        assertEquals(CPlusTarget.hostOs(), CPlusTarget.osFromCompilerOptions(emptyList()))
    }

    @Test
    fun `rejects incomplete target options`() {
        assertThrows(IllegalArgumentException::class.java) {
            CPlusTarget.osFromCompilerOptions(listOf("--target"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CPlusTarget.osFromCompilerOptions(listOf("--target="))
        }
    }

    @Test
    fun `uses the supported target model for plain char signedness`() {
        assertFalse(CPlusTarget.plainCharIsUnsigned("linux", "x86_64")!!)
        assertTrue(CPlusTarget.plainCharIsUnsigned("linux", "arm64")!!)
        assertFalse(CPlusTarget.plainCharIsUnsigned("macos", "x86_64")!!)
        assertFalse(CPlusTarget.plainCharIsUnsigned("macos", "arm64")!!)
        assertFalse(CPlusTarget.plainCharIsUnsigned("windows", "x86_64")!!)
        assertFalse(CPlusTarget.plainCharIsUnsigned("windows", "arm64")!!)
        assertNull(CPlusTarget.plainCharIsUnsigned("freebsd", "x86_64"))
        assertNull(CPlusTarget.plainCharIsUnsigned("linux", "riscv64"))
    }
}
