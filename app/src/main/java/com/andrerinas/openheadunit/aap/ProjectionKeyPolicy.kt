package com.andrerinas.openheadunit.aap

import android.view.KeyEvent

object ProjectionKeyPolicy {

    fun shouldRouteBackKeyToProjection(actionToPhysicalKeyCode: Map<Int, Int>, keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_BACK &&
                actionToPhysicalKeyCode.containsValue(KeyEvent.KEYCODE_BACK)
    }

    // The MG4's steering-wheel arrows already switch station etc. in the car; forwarded too, they
    // drop Android Auto into rotary focus mode and highlight whatever is on screen.
    private val NAVIGATION_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB
    )

    /** Whether a key the car delivered to the projection screen should go on to Android Auto. */
    fun shouldForwardCarKey(actionToPhysicalKeyCode: Map<Int, Int>, keyCode: Int): Boolean =
        keyCode !in NAVIGATION_KEYS || actionToPhysicalKeyCode.containsValue(keyCode)
}
