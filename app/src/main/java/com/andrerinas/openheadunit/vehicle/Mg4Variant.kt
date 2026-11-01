package com.andrerinas.openheadunit.vehicle

import kotlin.math.abs

/**
 * The MG4 versions Google Maps is told about: usable pack, kerb weight, on-board AC charger and DC
 * charging curve. Figures are the public measurements collected by EVKX.net (2026-10-29): the
 * NMC packs share one curve, the LFP Standard has its own.
 */
enum class Mg4Variant(
    val key: String,
    val label: String,
    val usableKwh: Float,
    val kerbKg: Int,
    val acChargerW: Int,
    /** DC charging power in kW at 0, 5, 10 … 100 % SoC. */
    private val dcCurveKw: IntArray,
) {
    STANDARD("standard", "Standard 51 kWh", 50.8f, 1655, 6_600, intArrayOf(
        20, 76, 81, 85, 85, 86, 84, 79, 75, 72, 70, 67, 63, 51, 42, 41, 27, 24, 15, 12, 10,
    )),
    /** Luxury and XPower: same 64 kWh pack and charging speed. */
    LONG_RANGE("long_range", "Luxury / XPower 64 kWh", 61.7f, 1765, 11_000, NMC_CURVE),
    /** Same pack, no heat pump. Chosen by hand: the car does not tell it from a Luxury. */
    COMFORT("comfort", "Comfort 64 kWh", 61.7f, 1765, 11_000, NMC_CURVE),
    EXTENDED_RANGE("extended_range", "Extended Range 77 kWh", 74.4f, 1823, 11_000, NMC_CURVE);

    /** Peak DC power, W. */
    val maxDcW: Int get() = dcCurveKw.max() * 1000

    /** DC power the car accepts at [percent] % SoC, W (linear between the 5 % points). */
    fun dcPowerW(percent: Double): Int {
        val p = percent.coerceIn(0.0, 100.0) / 5.0
        val i = p.toInt().coerceAtMost(dcCurveKw.size - 2)
        val f = p - i
        return ((dcCurveKw[i] + (dcCurveKw[i + 1] - dcCurveKw[i]) * f) * 1000).toInt()
    }

    companion object {
        /** Setting value for "follow the car". */
        const val AUTO = "auto"

        /**
         * The version whose usable pack is nearest [capacityWh] (the car's own figure).
         */
        fun detect(capacityWh: Int): Mg4Variant =
            entries.minBy { abs(it.usableKwh * 1000 - capacityWh) }

        fun resolve(setting: String, capacityWh: Int): Mg4Variant =
            entries.firstOrNull { it.key == setting }
                ?: LONG_RANGE.takeIf { setting == "xpower" } // merged with Comfort / Luxury
                ?: detect(capacityWh)
    }
}

private val NMC_CURVE = intArrayOf(
    20, 130, 130, 132, 134, 135, 135, 135, 137, 137, 138, 123, 93, 69, 64, 60, 55, 35, 24, 8, 6,
)
