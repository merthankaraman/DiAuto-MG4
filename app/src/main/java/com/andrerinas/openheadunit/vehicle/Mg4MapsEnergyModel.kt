package com.andrerinas.openheadunit.vehicle

import kotlin.math.abs
import kotlin.math.max

/**
 * DiAuto's own energy model for Google Maps (experimental, Settings > Car).
 *
 * Instead of one constant force taken from the car's range, Maps gets how the car resists moving,
 * F(v) = A + C·v², with A raised in the cold, plus the climate and electronics power by outside
 * temperature. Google's servers then work out the energy of each stretch of the route themselves,
 * with the speed they expect there (traffic included) and the climb.
 *
 * Figures (2026-11-01, to be calibrated on the road): A = mass in running order × g × 0.0095;
 * C = ½ · 1.225 · Cd 0.27 · 2.35 m² ≈ 0.39 for every MG4; under 20 °C, A is divided by the cold
 * factor of the automobile-propre simulator, max(0.55, 0.72 + 0.0187 · (T − 5)).
 */
object Mg4MapsEnergyModel {

    /** Air, N/(m/s)². */
    const val C = 0.39f
    private const val ROLLING = 0.0095f
    private const val G = 9.81f

    /** Electronics, 12 V and ventilation: what is left with the climate off. */
    const val ELECTRONICS_W = 400

    /** Climate (heating or A/C) and electronics, W, by outside temperature (starting values). */
    val AUX_W: List<Pair<Float, Int>> = listOf(
        -10f to 2_500, 0f to 1_800, 10f to 900, 20f to 400, 30f to 800, 35f to 1_200,
    )

    /** The same with a heat pump: heating divided by an estimated COP of 1.5 / 2 / 2.5, A/C unchanged. */
    val AUX_HEAT_PUMP_W: List<Pair<Float, Int>> = listOf(
        -10f to 1_800, 0f to 1_100, 10f to 600, 20f to 400, 30f to 800, 35f to 1_200,
    )

    /** Standard and Comfort heat with a resistance, the others with a heat pump. */
    fun hasHeatPump(variant: Mg4Variant): Boolean =
        variant != Mg4Variant.STANDARD && variant != Mg4Variant.COMFORT

    /** The outside temperature sent is held until it moves this much, so Maps does not re-route for nothing. */
    const val TEMP_HYSTERESIS_C = 3f

    /** Kerb weight + 75 kg driver; the XPower is taken as a Luxury (within 15 N). */
    fun massKg(variant: Mg4Variant): Int = when (variant) {
        Mg4Variant.STANDARD -> 1_710
        Mg4Variant.LONG_RANGE -> 1_747
        Mg4Variant.COMFORT -> 1_723
        Mg4Variant.EXTENDED_RANGE -> 1_823
    }

    fun coldFactor(tempC: Float): Float =
        if (tempC >= 20f) 1f else max(0.55f, 0.72f + 0.0187f * (tempC - 5f)).coerceAtMost(1f)

    /** Rolling resistance, N, with the cold surcharge; 20 °C when the temperature is unknown. */
    fun a(variant: Mg4Variant, tempC: Float?): Float =
        massKg(variant) * G * ROLLING / coldFactor(tempC ?: 20f)

    data class Model(
        val a: Float,
        val c: Float,
        /** Sent as the outside temperature; null = not sent. */
        val tempC: Float?,
        /** Climate switched on; null = unreadable. */
        val climateOn: Boolean?,
        /** (outside °C, W) rows for Maps' auxiliary power table. */
        val aux: List<Pair<Float, Int>>,
    )

    /**
     * Test values, to see on the road what Maps really takes into account. Set over adb only
     * ([MapsModelTestReceiver]) and kept in memory: a restart of DiAuto clears them.
     */
    data class TestOverrides(
        val auxW: Int? = null,
        val aScale: Float = 1f,
        val cScale: Float = 1f,
        val tempC: Float? = null,
        val climateOn: Boolean? = null,
    ) {
        val active: Boolean get() = this != TestOverrides()
    }

    @Volatile var test = TestOverrides()

    fun build(variant: Mg4Variant, tempC: Float?, climateOn: Boolean?, test: TestOverrides = this.test): Model {
        val temp = test.tempC ?: tempC
        val climate = test.climateOn ?: climateOn
        val aux = when {
            test.auxW != null -> AUX_W.map { it.first to test.auxW }
            climate == false -> AUX_W.map { it.first to ELECTRONICS_W }
            hasHeatPump(variant) -> AUX_HEAT_PUMP_W
            else -> AUX_W
        }
        return Model(a(variant, temp) * test.aScale, C * test.cScale, temp, climate, aux)
    }

    /** The temperature to send: [held] until [live] has moved [TEMP_HYSTERESIS_C] away from it. */
    fun heldTemp(held: Float?, live: Float?): Float? = when {
        live == null -> held
        held == null || abs(live - held) >= TEMP_HYSTERESIS_C -> live
        else -> held
    }
}
