package com.andrerinas.openheadunit.aap

import android.media.AudioManager
import com.andrerinas.openheadunit.aap.protocol.proto.Control.AudioFocusNotification.AudioFocusStateType

/**
 * Decides which head-unit audio focus changes the phone has to hear about.
 *
 * The phone is always granted focus over the protocol, and nothing used to tell it when the car
 * took that focus away: choosing the radio left Android Auto playing (or, with the old periodic
 * re-claim, took the radio straight back). MG's own Android Auto forwards those losses so the phone
 * pauses, and only takes the car again when the phone asks to play.
 *
 * The catch is that this app holds several focus requests at once (the protocol request, the
 * playback request, …) and every new one makes Android notify the previous one of a loss. Those
 * losses are self-inflicted and must not reach the phone, or it would pause its own music the
 * moment it starts. Android notifies the request that was on top when another app takes focus, so
 * only changes reaching our most recent request are taken as coming from the car.
 *
 * Only media requests are forwarded: a loss reaching a speech request (an assistant reply, a
 * guidance prompt) says nothing about the phone's music, and MG's app leaves those alone too.
 */
class CarFocusTracker {

    private data class Held(val token: Any, val media: Boolean)

    private val held = ArrayList<Held>()
    /**
     * The last state the phone was told. Android notifies every request in its stack when another
     * app takes focus, so once the top one has been forwarded the next one down reports the same
     * loss again; the phone hears it once.
     */
    private var lastSent: AudioFocusStateType? = null

    /**
     * A focus request was made; it is now our most recent one. The phone asked for focus again and
     * was granted it over the protocol, so an earlier forwarded loss no longer stands.
     */
    @Synchronized
    fun requested(token: Any, media: Boolean) {
        held.removeAll { it.token === token }
        held.add(Held(token, media))
        lastSent = null
    }

    /** A focus request was abandoned by this app. */
    @Synchronized
    fun abandoned(token: Any) {
        held.removeAll { it.token === token }
    }

    /** The phone was told of a loss that the user now overrides; returns whether there was one. */
    @Synchronized
    fun takeBackAfterLoss(): Boolean {
        if (lastSent == null) return false
        lastSent = null
        return true
    }

    @Synchronized
    fun clear() {
        held.clear()
        lastSent = null
    }

    /**
     * Android reported [focusChange] to the request behind [token]. Returns the state to send to the
     * phone, or null when the phone does not need to know.
     */
    @Synchronized
    fun onFocusChange(token: Any, focusChange: Int): AudioFocusStateType? {
        val top = held.lastOrNull()
        // Android drops a request from its stack on a permanent loss, whoever caused it.
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) held.removeAll { it.token === token }
        if (top == null || top.token !== token || !top.media) return null
        return forState(focusChange)
    }

    /**
     * For a focus request that is the only one this app holds (static audio focus), so every change
     * it sees comes from another app.
     */
    @Synchronized
    fun onUntrackedFocusChange(focusChange: Int): AudioFocusStateType? = forState(focusChange)

    private fun forState(focusChange: Int): AudioFocusStateType? {
        val state = when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> AudioFocusStateType.STATE_LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusStateType.STATE_LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusStateType.STATE_LOSS_TRANSIENT_CAN_DUCK
            // A gain only matters after a loss the phone was told about: otherwise it already has it.
            AudioManager.AUDIOFOCUS_GAIN -> if (lastSent == null) return null else AudioFocusStateType.STATE_GAIN
            else -> return null
        }
        if (state == lastSent) return null
        lastSent = if (state == AudioFocusStateType.STATE_GAIN) null else state
        return state
    }
}
