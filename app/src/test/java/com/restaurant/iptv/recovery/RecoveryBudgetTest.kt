package com.restaurant.iptv.recovery
import org.junit.Assert.*
import org.junit.Test
class RecoveryBudgetTest {
    @Test fun limitsRepeatedFailuresAndExpiresOldAttempts() {
        assertTrue(RecoveryBudget.mayRestart(emptyList(), 100))
        assertFalse(RecoveryBudget.mayRestart(listOf(1, 2, 3), 100))
        assertTrue(RecoveryBudget.mayRestart(listOf(1, 2, 3), RecoveryBudget.WINDOW_MS + 4))
        assertTrue(RecoveryBudget.mayRestart(listOf(1000, 2000, 3000), 100))
    }
}
