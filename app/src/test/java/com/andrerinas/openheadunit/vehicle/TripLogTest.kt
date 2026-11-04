package com.andrerinas.openheadunit.vehicle

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [33])
class TripLogTest {

    private fun trip() = TripLog(JSONObject().put("version", "Standard 51 kWh"), 42, 80f)

    @Test
    fun `energy, regeneration and distance add up`() {
        val t = trip()
        t.tick(3600f, 100f, 10_000f, 70f, 10f, false, null)
        t.tick(360f, 50f, -5_000f, 69f, 10f, false, null)
        val s = t.segments.single()
        assertEquals(105f, s.km, 0.01f)
        assertEquals(10f, s.outKwh, 0.01f)
        assertEquals(0.5f, s.regenKwh, 0.01f)
    }

    @Test
    fun `a climate or temperature change starts a segment`() {
        val t = trip()
        t.tick(1f, 50f, 1000f, 70f, 10f, false, null)
        t.tick(1f, 50f, 1000f, 70f, 10.5f, false, null)
        assertEquals(1, t.segments.size)
        t.tick(1f, 50f, 1000f, 70f, 10.5f, true, null)
        assertEquals(2, t.segments.size)
        t.tick(1f, 50f, 1000f, 70f, 12f, true, null)
        assertEquals(3, t.segments.size)
    }

    @Test
    fun `arrival is the charge when the car last moved`() {
        val t = trip()
        t.tick(1f, 50f, 1000f, 70f, 10f, false, null)
        t.tick(100f, 0f, 500f, 69f, 10f, false, null)
        assertEquals(70f, t.arrivalPercent, 0.01f)
        assertEquals(100f, t.stoppedFor, 0.01f)
    }

    @Test
    fun `speeds fall into their buckets`() {
        assertEquals(0, TripLog.bucketOf(10f))
        assertEquals(1, TripLog.bucketOf(30f))
        assertEquals(6, TripLog.bucketOf(140f))
    }

    @Test
    fun `a saved trip comes back unchanged`() {
        val t = trip()
        t.tick(10f, 90f, 20_000f, 70f, 10f, true, TripLog.Model(200f, 0.39f, 900) { 15_000f })
        val back = TripLog.fromJson(JSONObject(t.toJson().toString()))
        assertEquals(42, back.mapsArrivalPercent)
        assertEquals(80f, back.startPercent, 0.01f)
        val s = back.segments.single()
        assertEquals(t.segments.single().outKwh, s.outKwh, 0.001f)
        assertEquals(900, s.modelAuxW)
        assertEquals(10f, s.speedSeconds[4], 0.01f)
        assertEquals("Standard 51 kWh", back.header.getString("version"))
    }
}
