package com.andrerinas.openheadunit.aap

/**
 * When the car's media source is switched to Android Auto (radio paused, SAIC source set to AA).
 *
 * Only when the phone is actually playing music over Wi-Fi, as MG's own Android Auto does: the
 * switch used to happen on every connection, before anything played, and was then re-asserted
 * every eight seconds, which took the radio away for the whole session and gave the user no way
 * back. With music on Bluetooth the phone's A2DP stream is the car's own Bluetooth source, so there
 * is nothing to switch.
 */
object CarMediaSourcePolicy {
    fun shouldClaim(musicViaBluetooth: Boolean, mediaPlaying: Boolean): Boolean =
        !musicViaBluetooth && mediaPlaying
}
