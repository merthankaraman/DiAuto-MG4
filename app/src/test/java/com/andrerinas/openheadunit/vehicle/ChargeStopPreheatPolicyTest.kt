package com.andrerinas.openheadunit.vehicle

import com.andrerinas.openheadunit.vehicle.ChargeStopPreheatPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeStopPreheatPolicyTest {

    private fun decide(
        enabled: Boolean = true,
        navigating: Boolean = true,
        charger: Boolean = true,
        seconds: Int? = 20 * 60,
        heatingOn: Boolean? = false,
        owned: Boolean = false,
        last: Int? = null,
    ) = ChargeStopPreheatPolicy.decide(enabled, navigating, charger, seconds, 30 * 60, heatingOn, owned, last)

    @Test
    fun `chargers are told by name`() {
        assertTrue(ChargeStopPreheatPolicy.isCharger("IONITY Aire de Beaune"))
        assertTrue(ChargeStopPreheatPolicy.isCharger("Tesla Supercharger Mâcon"))
        assertTrue(ChargeStopPreheatPolicy.isCharger("Borne de recharge, Rue de la Gare"))
        assertFalse(ChargeStopPreheatPolicy.isCharger("12 Rue de la Paix, Paris"))
        assertFalse(ChargeStopPreheatPolicy.isCharger(null))
    }

    @Test
    fun `heating comes on within the lead time before a charger`() {
        assertEquals(Action.TURN_ON, decide())
        assertEquals(Action.NONE, decide(seconds = 45 * 60))
        assertEquals(Action.NONE, decide(charger = false))
        assertEquals(Action.NONE, decide(enabled = false))
    }

    @Test
    fun `heating the driver switched on is never touched`() {
        assertEquals(Action.NONE, decide(heatingOn = true))
        assertEquals(Action.NONE, decide(heatingOn = true, charger = false))
        assertEquals(Action.NONE, decide(heatingOn = true, navigating = false))
    }

    @Test
    fun `heating DiAuto switched on goes off when the trip changes`() {
        assertEquals(Action.TURN_OFF, decide(heatingOn = true, owned = true, charger = false))
        assertEquals(Action.TURN_OFF, decide(heatingOn = true, owned = true, navigating = false, last = 15 * 60))
        assertEquals(Action.TURN_OFF, decide(heatingOn = true, owned = true, enabled = false))
    }

    @Test
    fun `heating stays on when the charger is reached`() {
        assertEquals(Action.RELEASE, decide(heatingOn = true, owned = true, navigating = false, last = 60))
        assertEquals(Action.NONE, decide(heatingOn = true, owned = true))
    }

    @Test
    fun `a driver switching it off takes over`() {
        assertEquals(Action.RELEASE, decide(heatingOn = false, owned = true))
    }
}
