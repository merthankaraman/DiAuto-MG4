package com.andrerinas.openheadunit.aap

/**
 * Whether Android Auto takes the screen once the phone has connected. With the setting off it stays
 * in the background, unless the driver is already looking at it (a reconnect behind its overlay) or
 * at DiAuto's home screen.
 */
object ProjectionOpenPolicy {
    fun shouldOpen(settingOn: Boolean, projectionInFront: Boolean, homeInFront: Boolean): Boolean =
        settingOn || projectionInFront || homeInFront
}
