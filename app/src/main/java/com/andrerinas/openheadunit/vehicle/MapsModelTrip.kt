package com.andrerinas.openheadunit.vehicle

import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the Maps energy model predicts against what the battery really gives, for the debug overlay
 * of the first trips (Settings > Car, test mode): integrated once a second while a phone is there.
 * The model's figure is ours, from the same A, C and auxiliaries; Google's own one stays on its
 * servers. Battery to wheels is assumed at [DRIVETRAIN].
 */
object MapsModelTrip {
    private const val DRIVETRAIN = 0.88f
    private const val MAX_STEP_S = 5f

    private var lastAtMs = 0L
    private var km = 0f
    private var wh = 0f
    private var modelWh = 0f
    @Volatile private var line: String = ""

    @Synchronized fun reset() {
        lastAtMs = 0L
        km = 0f
        wh = 0f
        modelWh = 0f
        line = ""
    }

    /** Climate and electronics at the model's temperature, W: between the table's rows. */
    fun auxWatts(model: Mg4MapsEnergyModel.Model): Int {
        val rows = model.aux
        val t = model.tempC ?: 20f
        if (t <= rows.first().first) return rows.first().second
        if (t >= rows.last().first) return rows.last().second
        val hi = rows.indexOfFirst { it.first >= t }
        val (t0, w0) = rows[hi - 1]
        val (t1, w1) = rows[hi]
        return (w0 + (w1 - w0) * (t - t0) / (t1 - t0)).roundToInt()
    }

    /** Battery power the model expects at [speedKmh], W. */
    fun modelWatts(model: Mg4MapsEnergyModel.Model, speedKmh: Float): Float {
        val v = speedKmh / 3.6f
        return (model.a + model.c * v * v) * v / DRIVETRAIN + auxWatts(model)
    }

    /** kWh/100 km for [watts] at [speedKmh]. */
    private fun per100(watts: Float, speedKmh: Float): Float = watts / speedKmh / 10f

    @Synchronized
    fun sample(nowMs: Long, model: Mg4MapsEnergyModel.Model, speedKmh: Float?, packW: Float?, percent: Double, currentWh: Int, capacityWh: Int) {
        val speed = (speedKmh ?: 0f).coerceAtLeast(0f)
        val dt = if (lastAtMs == 0L) 0f else ((nowMs - lastAtMs) / 1000f).coerceIn(0f, MAX_STEP_S)
        lastAtMs = nowMs
        val expected = modelWatts(model, speed)
        if (packW != null) {
            km += speed * dt / 3600f
            wh += packW * dt / 3600f
            modelWh += expected * dt / 3600f
        }
        line = text(model, speed, packW, expected, percent, currentWh, capacityWh)
    }

    private fun text(m: Mg4MapsEnergyModel.Model, speed: Float, packW: Float?, expected: Float, percent: Double, currentWh: Int, capacityWh: Int): String {
        val clim = when (m.climateOn) { true -> "on"; false -> "off"; null -> "?" }
        val temp = m.tempC?.let { "%.0f°C".format(Locale.US, it) } ?: "?°C"
        val test = if (Mg4MapsEnergyModel.test.active) "  TEST" else ""
        val now = buildString {
            append("%.0f km/h  ".format(Locale.US, speed))
            append(packW?.let { "%.1f kW".format(Locale.US, it / 1000f) } ?: "? kW")
            if (speed >= 5f) {
                packW?.let { append("  %.1f".format(Locale.US, per100(it, speed))) }
                append("  model %.1f kWh/100".format(Locale.US, per100(expected, speed)))
            }
        }
        val trip = buildString {
            append("%.1f km  %.2f kWh".format(Locale.US, km, wh / 1000f))
            if (km >= 1f) {
                append("  %.1f  model %.1f kWh/100".format(Locale.US, wh / km / 10f, modelWh / km / 10f))
                if (modelWh > 0f) append("  (%+.0f%%)".format(Locale.US, (wh - modelWh) * 100f / modelWh))
            }
        }
        return "Model  A %.0f N  C %.2f  %s  climate %s  aux %d W%s\n".format(Locale.US, m.a, m.c, temp, clim, auxWatts(m), test) +
            "Now  $now\n" +
            "Trip  $trip\n" +
            "Battery  %.1f %%  %.1f / %.1f kWh".format(Locale.US, percent, currentWh / 1000f, capacityWh / 1000f)
    }

    /** Empty until the model has been sent. */
    fun overlayText(): String = line
}
