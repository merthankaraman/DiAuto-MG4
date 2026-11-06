package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Test

class UserExitHotspotPolicyTest {

    @Test
    fun `the access point comes down when the app may drive it`() {
        assertEquals(HotspotExitAction.DISABLE, UserExitHotspotPolicy.onUserExit(autoEnableHotspot = true))
    }

    @Test
    fun `an access point the app was never given charge of is left up, not switched off`() {
        assertEquals(HotspotExitAction.WARN_LEFT_UP, UserExitHotspotPolicy.onUserExit(autoEnableHotspot = false))
    }

    @Test
    fun `a device that could not switch its access point back on is not asked to again`() {
        assertEquals(
            HotspotExitAction.WARN_LEFT_UP,
            UserExitHotspotPolicy.onUserExit(autoEnableHotspot = true, teardownProvenUnsafe = true)
        )
    }
}
