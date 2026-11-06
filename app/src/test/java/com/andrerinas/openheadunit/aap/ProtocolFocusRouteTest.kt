package com.andrerinas.openheadunit.aap

import android.media.AudioAttributes
import android.media.AudioManager
import com.andrerinas.openheadunit.aap.ProtocolFocusRoute.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolFocusRouteTest {

    // The phone's requests arrive on the control channel, which maps to STREAM_MUSIC.
    private val control = AudioManager.STREAM_MUSIC

    @Test
    fun `a notification chime ducks the radio instead of taking the car's source`() {
        val kind = ProtocolFocusRoute.kind(control, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        assertEquals(Kind.GUIDANCE, kind)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, kind.usage)
        for (bt in listOf(true, false)) {
            assertFalse(ProtocolFocusRoute.tagsCarSource(kind, musicViaBluetooth = bt))
            assertTrue(ProtocolFocusRoute.asksSystem(kind, musicViaBluetooth = bt))
        }
    }

    @Test
    fun `the assistant asks as the assistant, without the car source`() {
        val kind = ProtocolFocusRoute.kind(control, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        assertEquals(Kind.ASSISTANT, kind)
        assertEquals(AudioAttributes.USAGE_ASSISTANT, kind.usage)
        assertFalse(ProtocolFocusRoute.tagsCarSource(kind, musicViaBluetooth = false))
    }

    @Test
    fun `only music over Wi-Fi takes the car's media source`() {
        val kind = ProtocolFocusRoute.kind(control, AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(Kind.MEDIA, kind)
        assertTrue(ProtocolFocusRoute.tagsCarSource(kind, musicViaBluetooth = false))
        assertTrue(ProtocolFocusRoute.asksSystem(kind, musicViaBluetooth = false))
    }

    @Test
    fun `with music on Bluetooth the media request leaves focus and source alone`() {
        val kind = ProtocolFocusRoute.kind(control, AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(ProtocolFocusRoute.tagsCarSource(kind, musicViaBluetooth = true))
        assertFalse(ProtocolFocusRoute.asksSystem(kind, musicViaBluetooth = true))
    }

    @Test
    fun `separate streams keep their own usage`() {
        assertEquals(Kind.NOTIFICATION, ProtocolFocusRoute.kind(AudioManager.STREAM_NOTIFICATION, AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(Kind.CALL, ProtocolFocusRoute.kind(AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK))
        assertFalse(ProtocolFocusRoute.tagsCarSource(Kind.NOTIFICATION, musicViaBluetooth = false))
    }
}
