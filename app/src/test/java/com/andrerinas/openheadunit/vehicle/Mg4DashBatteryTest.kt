package com.andrerinas.openheadunit.vehicle

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config

// The parser needs Android's XmlPullParser and org.json; no TLS involved.
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], manifest = Config.NONE)
class Mg4DashBatteryTest {

    private val now = 1_800_000_000_000L
    private val day = 24 * 3600_000L

    private fun prefs(model: Int?, samples: String?) = buildString {
        append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
        append("    <float name=\"battery_kwh\" value=\"49.0\" />\n")
        if (model != null) append("    <int name=\"battery_model\" value=\"$model\" />\n")
        if (samples != null) {
            append("    <string name=\"cap_samples\">")
            append(samples.replace("\"", "&quot;"))
            append("</string>\n")
        }
        append("    <boolean name=\"vertical_dock\" value=\"true\" />\n</map>\n")
    }.byteInputStream()

    private fun sample(daysAgo: Int, kwh: Double, drop: Int) =
        """{"t":${now - daysAgo * day},"k":$kwh,"d":$drop,"m":120.0}"""

    @Test
    fun `MG4 Dash's measurement is weighted by charge drop`() {
        val samples = "[${sample(3, 47.0, 20)},${sample(10, 48.0, 30)}]"
        val r = Mg4DashBattery.parse(prefs(51, samples), now)!!
        val measured = (47.0 * 20 + 48.0 * 30) / 50
        assertEquals(51, r.pack)
        assertTrue(r.packChosen)
        assertEquals(measured.toFloat(), r.measuredKwh!!, 0.001f)
    }

    @Test
    fun `without a chosen battery the nearest one to the measurement is used`() {
        val r = Mg4DashBattery.parse(prefs(null, "[${sample(2, 60.0, 25)}]"), now)!!
        assertEquals(64, r.pack)
        assertFalse(r.packChosen)
    }

    @Test
    fun `samples older than 30 days are left out while recent ones exist`() {
        val samples = "[${sample(60, 40.0, 20)},${sample(5, 47.0, 20)}]"
        assertEquals(47f, Mg4DashBattery.parse(prefs(51, samples), now)!!.measuredKwh!!, 0.001f)
        val old = "[${sample(60, 40.0, 20)},${sample(45, 42.0, 20)}]"
        assertEquals(41f, Mg4DashBattery.parse(prefs(51, old), now)!!.measuredKwh!!, 0.001f)
    }

    @Test
    fun `a chosen battery without measurement gives no capacity`() {
        val r = Mg4DashBattery.parse(prefs(77, null), now)!!
        assertEquals(77, r.pack)
        assertNull(r.measuredKwh)
    }

    @Test
    fun `nothing known gives no reading`() {
        assertNull(Mg4DashBattery.parse(prefs(null, null), now))
        assertNull(Mg4DashBattery.parse(prefs(null, "[]"), now))
        assertNull(Mg4DashBattery.parse(prefs(null, "not json"), now))
    }
}
