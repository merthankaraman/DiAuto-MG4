package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarMediaSourcePolicyTest {

    @Test
    fun `music playing over Wi-Fi takes the car's media source`() {
        assertTrue(CarMediaSourcePolicy.shouldClaim(musicViaBluetooth = false, mediaPlaying = true))
    }

    @Test
    fun `a connection with nothing playing leaves the radio alone`() {
        assertFalse(CarMediaSourcePolicy.shouldClaim(musicViaBluetooth = false, mediaPlaying = false))
    }

    @Test
    fun `music over Bluetooth never switches the source`() {
        // The phone's A2DP stream is already the car's Bluetooth source.
        assertFalse(CarMediaSourcePolicy.shouldClaim(musicViaBluetooth = true, mediaPlaying = true))
        assertFalse(CarMediaSourcePolicy.shouldClaim(musicViaBluetooth = true, mediaPlaying = false))
    }
}
