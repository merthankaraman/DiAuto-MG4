package com.andrerinas.openheadunit.aap

/** What a user-initiated disconnect should do to the car's access point. */
enum class HotspotExitAction {
    /** Take the access point down, so the phone is actually put off the network. */
    DISABLE,

    /** Leave it alone and say what that costs: it is not ours to switch off. */
    WARN_LEFT_UP
}

/**
 * Whether ending a session by hand should also take down the access point the phone is sitting on.
 *
 * Closing the AAP socket and sending a `ByeByeRequest` does not make the phone leave the network:
 * it stays associated and Android Auto spins on its wireless-setup restart until it throttles
 * itself, because from its side the car walked away mid-session. The only lever this app has is
 * making the network go away.
 *
 * The gate is ownership rather than symptom. The access point usually is not ours — the user
 * switched it on, and switching one back on is best effort. Leaving somebody's hotspot off with no
 * way to restore it is worse than the stall, which they can clear themselves. So the access point
 * comes down only when they have already handed the app that job.
 */
object UserExitHotspotPolicy {

    /**
     * @param autoEnableHotspot [com.andrerinas.openheadunit.utils.Settings.autoEnableHotspot] — the
     *   user's standing permission for the app to drive this device's hotspot
     * @param teardownProvenUnsafe whether this device has already failed to bring its access point
     *   back after the app took it down.
     */
    fun onUserExit(
        autoEnableHotspot: Boolean,
        teardownProvenUnsafe: Boolean = false
    ): HotspotExitAction = when {
        // Permission is not the only question; capability is the other one, and unlike permission
        // it can only be learned by trying. Some radios will not host an access point again once it
        // has been taken down, and the app only finds out which kind it is by stranding the first.
        teardownProvenUnsafe -> HotspotExitAction.WARN_LEFT_UP
        autoEnableHotspot -> HotspotExitAction.DISABLE
        else -> HotspotExitAction.WARN_LEFT_UP
    }
}
