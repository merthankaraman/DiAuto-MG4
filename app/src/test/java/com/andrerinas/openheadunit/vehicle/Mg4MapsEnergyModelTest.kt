package com.andrerinas.openheadunit.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mg4MapsEnergyModelTest {

    @Test
    fun `A matches the spec table`() {
        val std = Mg4Variant.STANDARD
        assertEquals(159f, Mg4MapsEnergyModel.a(std, 25f), 1f)
        assertEquals(176f, Mg4MapsEnergyModel.a(std, 15f), 1f)
        assertEquals(221f, Mg4MapsEnergyModel.a(std, 5f), 1f)
        assertEquals(290f, Mg4MapsEnergyModel.a(std, -10f), 1f)
        assertEquals(163f, Mg4MapsEnergyModel.a(Mg4Variant.LONG_RANGE, null), 1f)
        assertEquals(271f, Mg4MapsEnergyModel.a(Mg4Variant.EXTENDED_RANGE, 0f), 1f)
    }

    @Test
    fun `climate off leaves only the electronics`() {
        val m = Mg4MapsEnergyModel.build(Mg4Variant.STANDARD, 0f, false, Mg4MapsEnergyModel.TestOverrides())
        assertTrue(m.aux.all { it.second == Mg4MapsEnergyModel.ELECTRONICS_W })
        val on = Mg4MapsEnergyModel.build(Mg4Variant.STANDARD, 0f, null, Mg4MapsEnergyModel.TestOverrides())
        assertEquals(Mg4MapsEnergyModel.AUX_W, on.aux)
    }

    @Test
    fun `only versions with a heat pump get its table`() {
        val off = Mg4MapsEnergyModel.TestOverrides()
        assertEquals(Mg4MapsEnergyModel.AUX_W, Mg4MapsEnergyModel.build(Mg4Variant.STANDARD, 0f, true, off).aux)
        assertEquals(Mg4MapsEnergyModel.AUX_W, Mg4MapsEnergyModel.build(Mg4Variant.COMFORT, 0f, true, off).aux)
        assertEquals(Mg4MapsEnergyModel.AUX_HEAT_PUMP_W, Mg4MapsEnergyModel.build(Mg4Variant.LONG_RANGE, 0f, true, off).aux)
        assertEquals(Mg4MapsEnergyModel.AUX_HEAT_PUMP_W, Mg4MapsEnergyModel.build(Mg4Variant.EXTENDED_RANGE, 0f, true, off).aux)
    }

    @Test
    fun `test values replace the live ones`() {
        val t = Mg4MapsEnergyModel.TestOverrides(auxW = 2500, aScale = 2f, cScale = 0.5f, tempC = 20f)
        val m = Mg4MapsEnergyModel.build(Mg4Variant.STANDARD, -5f, false, t)
        assertTrue(m.aux.all { it.second == 2500 })
        assertEquals(2 * Mg4MapsEnergyModel.a(Mg4Variant.STANDARD, 20f), m.a, 0.1f)
        assertEquals(0.195f, m.c, 0.001f)
        assertEquals(20f, m.tempC!!, 0f)
    }

    @Test
    fun `temperature is held within 3 degrees`() {
        assertEquals(10f, Mg4MapsEnergyModel.heldTemp(10f, 12f)!!, 0f)
        assertEquals(13f, Mg4MapsEnergyModel.heldTemp(10f, 13f)!!, 0f)
        assertEquals(10f, Mg4MapsEnergyModel.heldTemp(10f, null)!!, 0f)
        assertNull(Mg4MapsEnergyModel.heldTemp(null, null))
    }
}
