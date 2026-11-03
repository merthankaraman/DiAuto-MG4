package com.andrerinas.openheadunit.vehicle

import com.andrerinas.openheadunit.vehicle.BatteryCapacityResolver.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryCapacityResolverTest {

    private fun resolve(
        manualNet: Float? = null,
        manualSoh: Float? = null,
        car: Float? = null,
        dash: Mg4DashBattery.Reading? = null,
        measured: Float? = null,
        type: Int? = null,
    ) = BatteryCapacityResolver.resolve(manualNet, manualSoh, car, dash, measured, type)

    @Test
    fun `the car's battery type code names the battery`() {
        // SWI69 Standard 51 kWh reads 16.
        val r = resolve(type = 16)
        assertEquals(51, r.pack)
        assertEquals(Source.CAR, r.netSource)
        // 7 / 9: 64 or 77, settled by the measurement, else 64.
        assertEquals(77, resolve(type = 7, measured = 70f).pack)
        assertEquals(64, resolve(type = 9).pack)
        // Unknown code: as before.
        assertEquals(Source.DEFAULT, resolve(type = 3).netSource)
    }

    @Test
    fun `a measured capacity gives the health of the known battery, never another battery`() {
        // A Standard 51 kWh known from its type code, measured 46.7 kWh.
        val r = resolve(type = 16, measured = 46.7f)
        assertEquals(51, r.pack)
        assertEquals(Source.MEASURED, r.sohSource)
        assertEquals(95.31f, r.sohPercent, 0.1f)
        // The car says nothing (issue #8): the battery stays the default one, what Maps gets is still the measurement.
        val unknown = resolve(measured = 46.7f)
        assertEquals(64, unknown.pack)
        assertEquals(Source.DEFAULT, unknown.netSource)
        assertEquals(46.7f, unknown.effectiveKwh, 0.01f)
    }

    @Test
    fun `DiAuto's own measurement wins over MG4 Dash's, and the car over both`() {
        val dash = Mg4DashBattery.Reading(pack = 51, packChosen = false, measuredKwh = 44f)
        assertEquals(46.7f / 61f * 100f, resolve(dash = dash, measured = 46.7f).sohPercent, 0.01f)
        assertEquals(Source.CAR, resolve(car = 64f, measured = 46.7f).netSource)
    }

    @Test
    fun `without any source the 64 kWh battery and 95 percent are used`() {
        val r = resolve()
        assertEquals(61f, r.netKwh, 0.001f)
        assertEquals(Source.DEFAULT, r.netSource)
        assertEquals(64, r.pack)
        assertEquals(95f, r.sohPercent, 0.001f)
        assertEquals(Source.DEFAULT, r.sohSource)
    }

    @Test
    fun `a capacity published by the car wins over MG4 Dash`() {
        val dash = Mg4DashBattery.Reading(pack = 51, packChosen = true, measuredKwh = 55f)
        val r = resolve(car = 64f, dash = dash)
        assertEquals(61f, r.netKwh, 0.001f)
        assertEquals(Source.CAR, r.netSource)
        assertEquals(Source.MEASURED, r.sohSource)
        assertEquals(55f / 61f * 100f, r.sohPercent, 0.01f)
    }

    @Test
    fun `nameplate figures from the car become usable ones, other figures are kept`() {
        assertEquals(49f, BatteryCapacityResolver.carUsableKwh(51f)!!, 0.001f)
        assertEquals(72.5f, BatteryCapacityResolver.carUsableKwh(77.2f)!!, 0.001f)
        assertEquals(58.0f, BatteryCapacityResolver.carUsableKwh(58f)!!, 0.001f)
        assertNull(BatteryCapacityResolver.carUsableKwh(0f))
        assertNull(BatteryCapacityResolver.carUsableKwh(0.064f))
        assertNull(BatteryCapacityResolver.carUsableKwh(Float.NaN))
    }

    @Test
    fun `MG4 Dash's measurement stands in when DiAuto has none, its chosen battery when the car says nothing`() {
        val dash = Mg4DashBattery.Reading(pack = 51, packChosen = true, measuredKwh = 47f)
        val r = resolve(dash = dash)
        assertEquals(49f, r.netKwh, 0.001f)
        assertEquals(Source.MEASURED, r.sohSource)
        assertEquals(51, r.pack)
        assertEquals(47f, r.effectiveKwh, 0.01f)
        assertEquals(64, resolve(dash = dash.copy(packChosen = false)).pack)
    }

    @Test
    fun `MG4 Dash without a measurement leaves the default health`() {
        val dash = Mg4DashBattery.Reading(pack = 77, packChosen = true, measuredKwh = null)
        val r = resolve(dash = dash)
        assertEquals(72.5f, r.netKwh, 0.001f)
        assertEquals(95f, r.sohPercent, 0.001f)
        assertEquals(Source.DEFAULT, r.sohSource)
    }

    @Test
    fun `settings forced by hand win over everything`() {
        val dash = Mg4DashBattery.Reading(pack = 64, packChosen = true, measuredKwh = 60f)
        val r = resolve(manualNet = 49f, manualSoh = 98.75f, car = 64f, dash = dash)
        assertEquals(49f, r.netKwh, 0.001f)
        assertEquals(Source.MANUAL, r.netSource)
        assertEquals(98.75f, r.sohPercent, 0.001f)
        assertEquals(Source.MANUAL, r.sohSource)
    }

    @Test
    fun `a measurement slightly above the 64 kWh figure does not read as a worn 77 kWh`() {
        assertEquals(64, BatteryCapacityResolver.packForMeasured(63f, listOf(64, 77)))
        assertEquals(77, BatteryCapacityResolver.packForMeasured(68f, listOf(64, 77)))
    }

    @Test
    fun `the nearest battery is picked from a usable capacity`() {
        assertEquals(51, BatteryCapacityResolver.nearestPack(46.5f))
        assertEquals(51, BatteryCapacityResolver.nearestPack(54.5f))
        assertEquals(64, BatteryCapacityResolver.nearestPack(57f))
        assertEquals(77, BatteryCapacityResolver.nearestPack(70f))
    }
}
