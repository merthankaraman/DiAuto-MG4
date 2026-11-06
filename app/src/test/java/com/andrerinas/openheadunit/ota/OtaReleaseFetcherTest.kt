package com.andrerinas.openheadunit.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaReleaseFetcherTest {

    private fun cmp(a: String, b: String) = OtaReleaseFetcher.compareVersions(a, b)

    @Test
    fun `numbers compare numerically`() {
        assertTrue(cmp("1.9.10", "1.9.9") > 0)
        assertTrue(cmp("v1.9.7-beta0", "1.9.6-fix.1-debug") > 0)
    }

    @Test
    fun `a final version beats its betas`() {
        assertTrue(cmp("1.9.7", "1.9.7-beta3-debug") > 0)
        assertTrue(cmp("1.9.7-beta3", "1.9.7-debug") < 0)
    }

    @Test
    fun `betas follow their number`() {
        assertTrue(cmp("1.9.7-beta1", "1.9.7-beta0-debug") > 0)
        assertTrue(cmp("1.9.7-beta10", "1.9.7-beta9") > 0)
    }

    @Test
    fun `build suffixes are ignored`() {
        assertEquals(0, cmp("1.9.7-beta0", "1.9.7-beta0-debug"))
        assertEquals(0, cmp("1.9.7", "1.9.7-debug"))
        assertEquals(0, cmp("1.9.7", "1.9.7-phone-debug"))
    }
}
