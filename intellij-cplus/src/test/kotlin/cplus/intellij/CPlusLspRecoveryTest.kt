package cplus.intellij

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CPlusLspRecoveryTest {
    @Test
    fun retriesOnceUntilAnExplicitRestartOrConfigurationChange() {
        val recovery = CPlusLspRecovery()
        assertTrue(recovery.claimRestart())
        assertFalse(recovery.claimRestart())
        recovery.reset()
        assertTrue(recovery.claimRestart())
        assertFalse(recovery.claimRestart())
    }
}
