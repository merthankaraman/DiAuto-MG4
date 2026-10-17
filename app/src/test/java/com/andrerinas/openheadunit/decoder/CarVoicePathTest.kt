package com.andrerinas.openheadunit.decoder

import org.junit.Assert.assertEquals
import org.junit.Test

/** The strings MG's own Android Auto (AllGo AapAudioRecorder) sets around every recording. */
class CarVoicePathTest {
    @Test
    fun `wireless sessions use the Wi-Fi voice path`() {
        assertEquals("SSE_AA_VR_Wifi=true", CarVoicePath.parameter(wireless = true, enabled = true))
        assertEquals("SSE_AA_VR_Wifi=false", CarVoicePath.parameter(wireless = true, enabled = false))
    }

    @Test
    fun `usb sessions use the USB voice path`() {
        assertEquals("SSE_AA_VR=true", CarVoicePath.parameter(wireless = false, enabled = true))
        assertEquals("SSE_AA_VR=false", CarVoicePath.parameter(wireless = false, enabled = false))
    }
}
