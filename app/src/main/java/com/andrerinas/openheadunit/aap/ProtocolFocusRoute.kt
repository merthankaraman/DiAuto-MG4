package com.andrerinas.openheadunit.aap

import android.media.AudioAttributes
import android.media.AudioManager

/**
 * Which kind of audio a focus request from the phone stands for, and so which audio attributes the
 * head unit asks the system with.
 *
 * The phone sends every request on the control channel, which maps to STREAM_MUSIC, so every one of
 * them used to go out as media tagged `key_car_source_type = 70`: a notification chime switched the
 * MG4's media source from the radio to Android Auto, and nothing switched it back. MG's own Android
 * Auto (AllGo) only tags the media request; guidance prompts ask with the navigation guidance usage
 * and may duck, the assistant asks with the assistant usage. The car then ducks or pauses the radio
 * and gives it back when the request is released.
 */
object ProtocolFocusRoute {

    enum class Kind(val usage: Int) {
        /** The phone's music (GAIN): the only request that takes the car's media source. */
        MEDIA(AudioAttributes.USAGE_MEDIA),
        /** Navigation prompts and notification chimes (GAIN_TRANSIENT_MAY_DUCK): duck the radio. */
        GUIDANCE(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE),
        /** The assistant (GAIN_TRANSIENT): pauses the radio until it is done. */
        ASSISTANT(AudioAttributes.USAGE_ASSISTANT),
        NOTIFICATION(AudioAttributes.USAGE_NOTIFICATION),
        CALL(AudioAttributes.USAGE_VOICE_COMMUNICATION)
    }

    fun kind(stream: Int, focusType: Int): Kind = when {
        stream == AudioManager.STREAM_NOTIFICATION -> Kind.NOTIFICATION
        stream == AudioManager.STREAM_VOICE_CALL -> Kind.CALL
        focusType == AudioManager.AUDIOFOCUS_GAIN -> Kind.MEDIA
        focusType == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> Kind.ASSISTANT
        else -> Kind.GUIDANCE
    }

    /** Only the phone's music over Wi-Fi switches the car's media source to Android Auto. */
    fun tagsCarSource(kind: Kind, musicViaBluetooth: Boolean): Boolean =
        kind == Kind.MEDIA && !musicViaBluetooth

    /**
     * With music on the car's Bluetooth (A2DP), the media request has nothing to take: the phone's
     * stream is already the car's Bluetooth source, and grabbing focus would only pause it.
     */
    fun asksSystem(kind: Kind, musicViaBluetooth: Boolean): Boolean =
        !(kind == Kind.MEDIA && musicViaBluetooth)
}
