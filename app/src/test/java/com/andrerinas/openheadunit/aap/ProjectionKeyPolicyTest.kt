package com.andrerinas.openheadunit.aap

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionKeyPolicyTest {

    @Test
    fun `unmapped back key keeps system back behavior`() {
        assertFalse(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                actionToPhysicalKeyCode = emptyMap(),
                keyCode = KeyEvent.KEYCODE_BACK
            )
        )
    }

    @Test
    fun `mapped physical back key routes to projection`() {
        assertTrue(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                actionToPhysicalKeyCode = mapOf(KeyEvent.KEYCODE_BACK to KeyEvent.KEYCODE_BACK),
                keyCode = KeyEvent.KEYCODE_BACK
            )
        )
    }

    @Test
    fun `physical back can be mapped to another Android Auto action`() {
        assertTrue(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                actionToPhysicalKeyCode = mapOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS to KeyEvent.KEYCODE_BACK),
                keyCode = KeyEvent.KEYCODE_BACK
            )
        )
    }

    @Test
    fun `non-back keys do not use the back override`() {
        assertFalse(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                actionToPhysicalKeyCode = mapOf(KeyEvent.KEYCODE_BACK to KeyEvent.KEYCODE_BACK),
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER
            )
        )
    }

    @Test
    fun `steering wheel arrows do not drive the Android Auto UI`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB
        ).forEach { assertFalse(ProjectionKeyPolicy.shouldForwardCarKey(emptyMap(), it)) }
    }

    @Test
    fun `media keys still reach Android Auto`() {
        assertTrue(ProjectionKeyPolicy.shouldForwardCarKey(emptyMap(), KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(ProjectionKeyPolicy.shouldForwardCarKey(emptyMap(), KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    @Test
    fun `a navigation key learned in the keymap is forwarded`() {
        assertTrue(
            ProjectionKeyPolicy.shouldForwardCarKey(
                actionToPhysicalKeyCode = mapOf(KeyEvent.KEYCODE_MEDIA_NEXT to KeyEvent.KEYCODE_DPAD_RIGHT),
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT
            )
        )
    }
}
