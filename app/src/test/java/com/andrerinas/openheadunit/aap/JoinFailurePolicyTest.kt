package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinFailurePolicyTest {

    @Test
    fun `tries space out up to one minute`() {
        assertEquals(15_000L, JoinFailurePolicy.retryDelayMs(0))
        assertEquals(15_000L, JoinFailurePolicy.retryDelayMs(1))
        assertEquals(30_000L, JoinFailurePolicy.retryDelayMs(2))
        assertEquals(60_000L, JoinFailurePolicy.retryDelayMs(3))
        assertEquals(60_000L, JoinFailurePolicy.retryDelayMs(50))
    }

    @Test
    fun `the driver is told at the second failure, then from time to time`() {
        assertFalse(JoinFailurePolicy.shouldWarn(1))
        assertTrue(JoinFailurePolicy.shouldWarn(2))
        assertFalse(JoinFailurePolicy.shouldWarn(3))
        assertTrue(JoinFailurePolicy.shouldWarn(7))
    }
}
