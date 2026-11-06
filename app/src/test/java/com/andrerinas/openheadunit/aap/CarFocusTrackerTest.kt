package com.andrerinas.openheadunit.aap

import android.media.AudioManager
import com.andrerinas.openheadunit.aap.protocol.proto.Control.AudioFocusNotification.AudioFocusStateType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarFocusTrackerTest {

    private val tracker = CarFocusTracker()
    private val protocolMedia = Any()
    private val playbackMedia = Any()
    private val guidance = Any()

    @Test
    fun `choosing the radio pauses the phone`() {
        tracker.requested(protocolMedia, media = true)
        assertEquals(
            AudioFocusStateType.STATE_LOSS,
            tracker.onFocusChange(protocolMedia, AudioManager.AUDIOFOCUS_LOSS)
        )
    }

    @Test
    fun `transient car losses reach the phone with their own state`() {
        tracker.requested(protocolMedia, media = true)
        assertEquals(
            AudioFocusStateType.STATE_LOSS_TRANSIENT,
            tracker.onFocusChange(protocolMedia, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        )
        assertEquals(
            AudioFocusStateType.STATE_LOSS_TRANSIENT_CAN_DUCK,
            tracker.onFocusChange(protocolMedia, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        )
    }

    @Test
    fun `focus coming back after a transient loss resumes the phone`() {
        tracker.requested(protocolMedia, media = true)
        tracker.onFocusChange(protocolMedia, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(AudioFocusStateType.STATE_GAIN, tracker.onFocusChange(protocolMedia, AudioManager.AUDIOFOCUS_GAIN))
        // Said once: a second gain is not news.
        assertNull(tracker.onFocusChange(protocolMedia, AudioManager.AUDIOFOCUS_GAIN))
    }

    @Test
    fun `a gain the phone was never told it lost is not sent`() {
        tracker.requested(protocolMedia, media = true)
        assertNull(tracker.onFocusChange(protocolMedia, AudioManager.AUDIOFOCUS_GAIN))
    }

    @Test
    fun `our own newer request does not pause the phone`() {
        // The protocol GAIN, then the playback request when the track starts: Android tells the
        // first one it lost focus, to ourselves.
        tracker.requested(protocolMedia, media = true)
        tracker.requested(playbackMedia, media = true)
        assertNull(tracker.onFocusChange(protocolMedia, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
    }

    @Test
    fun `a guidance prompt over the music does not pause it`() {
        tracker.requested(playbackMedia, media = true)
        tracker.requested(guidance, media = false)
        assertNull(tracker.onFocusChange(playbackMedia, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        // The car taking focus during the prompt says nothing about the music either.
        assertNull(tracker.onFocusChange(guidance, AudioManager.AUDIOFOCUS_LOSS))
    }

    @Test
    fun `once the prompt is released the music request is the one the car takes from`() {
        tracker.requested(playbackMedia, media = true)
        tracker.requested(guidance, media = false)
        tracker.abandoned(guidance)
        // Android gives the music request its focus back: not news to the phone.
        assertNull(tracker.onFocusChange(playbackMedia, AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(AudioFocusStateType.STATE_LOSS, tracker.onFocusChange(playbackMedia, AudioManager.AUDIOFOCUS_LOSS))
    }

    @Test
    fun `the car taking focus reaches every request, but the phone hears it once`() {
        tracker.requested(protocolMedia, media = true)
        tracker.requested(playbackMedia, media = true)
        assertEquals(AudioFocusStateType.STATE_LOSS, tracker.onFocusChange(playbackMedia, AudioManager.AUDIOFOCUS_LOSS))
        // Android also tells the older request; that one is not the top, and the loss is already sent.
        assertNull(tracker.onFocusChange(protocolMedia, AudioManager.AUDIOFOCUS_LOSS))
    }

    @Test
    fun `the phone asking again clears an earlier loss`() {
        tracker.requested(protocolMedia, media = true)
        tracker.onFocusChange(protocolMedia, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        val again = Any()
        tracker.requested(again, media = true)
        // Granted over the protocol already: a gain on the new request is not sent a second time.
        assertNull(tracker.onFocusChange(again, AudioManager.AUDIOFOCUS_GAIN))
    }

    @Test
    fun `nothing is sent for a request that was abandoned or cleared`() {
        tracker.requested(protocolMedia, media = true)
        tracker.abandoned(protocolMedia)
        assertNull(tracker.onFocusChange(protocolMedia, AudioManager.AUDIOFOCUS_LOSS))
        tracker.requested(playbackMedia, media = true)
        tracker.clear()
        assertNull(tracker.onFocusChange(playbackMedia, AudioManager.AUDIOFOCUS_LOSS))
    }

    @Test
    fun `static focus forwards every change of its single request`() {
        assertEquals(AudioFocusStateType.STATE_LOSS, tracker.onUntrackedFocusChange(AudioManager.AUDIOFOCUS_LOSS))
        assertEquals(AudioFocusStateType.STATE_GAIN, tracker.onUntrackedFocusChange(AudioManager.AUDIOFOCUS_GAIN))
        assertNull(tracker.onUntrackedFocusChange(AudioManager.AUDIOFOCUS_GAIN))
    }
}
