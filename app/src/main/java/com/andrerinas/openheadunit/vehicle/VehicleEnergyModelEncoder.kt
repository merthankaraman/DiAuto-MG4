package com.andrerinas.openheadunit.vehicle

import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream

/**
 * Wire encoder for Android Auto VehicleEnergyModel (sensor type 23/25).
 *
 * The layout follows a model a production car sends (Ford's, captured by the aa-proxy-rs project
 * and decoded 2026-10-29): 1 = battery and vehicle (2 mass kg, 3 current Wh, 4 capacity Wh,
 * 6 air density, 7 outside temperature), 2 = road-load coefficients, 4 = DC charging curve
 * (Wh → W), 8 = time to full per charger power. The road-load part keeps its earlier shape:
 * sending Wh/km (~150) and Cd (~0.36) as A/B/C made Maps burn most of the pack over short routes
 * (e.g. 53 km → ~14% remaining), so only a constant force derived from the car's range is set.
 *
 * Maps SoC = current_Wh / capacity_Wh.
 *
 * Which of the extra sections Maps actually uses is still being found out on the road: the full
 * layout of 2.2.0-beta5 made Maps plan short stops on slow AC chargers. [Layout] picks what is
 * sent, from the earlier working model (fuel/connector block on field 4) up to the full one.
 */
object VehicleEnergyModelEncoder {

    private const val MIN_WH_PER_KM = 100f
    private const val MAX_WH_PER_KM = 220f
    private const val DEFAULT_WH_PER_KM = 160f // typical MG4 Long Range ballpark

    /** Driver on board, on top of the kerb weight. */
    private const val DRIVER_KG = 75

    /** Battery-side share of a DC charger's power, and of an AC one through the on-board charger. */
    private const val DC_EFFICIENCY = 0.95
    private const val AC_EFFICIENCY = 0.88

    /** EU AC points (granny 10 A, 16 A single phase, 7.4, 11, 22 kW) and the DC points Ford sends. */
    private val AC_CHARGERS_W = intArrayOf(2_300, 3_700, 7_400, 11_000, 22_000)
    private val DC_CHARGERS_W = intArrayOf(22_500, 50_000, 62_500, 100_000, 150_000)

    /** What goes into the model, chosen in Settings > Car while the right layout is found. */
    enum class Layout(val key: String) {
        /** The model that worked: battery, road load, fuel/connector block on field 4. */
        LEGACY("legacy"),
        /** LEGACY plus mass, air density and outside temperature in the vehicle message. */
        VEHICLE("vehicle"),
        /** VEHICLE with the DC charging curve on field 4 instead of the connector block. */
        DC_CURVE("dc_curve"),
        /** VEHICLE, connector block kept, plus DC time-to-full rows on field 8. */
        DC_TIMES("dc_times"),
        /** The 2.2.0-beta5 model: DC curve on field 4, AC and DC rows on field 8. */
        FULL("full");

        companion object {
            fun of(key: String?): Layout = entries.firstOrNull { it.key == key } ?: DC_CURVE
        }
    }

    data class Snapshot(
        val capacityWh: Int,
        val currentWh: Int,
        val rangeMeters: Int,
        val batteryPercent: Double = 0.0,
        val maxChargePowerW: Int = 140_000,
        val peakMotorPowerW: Int = 150_000,
        val variant: Mg4Variant? = null,
        val outsideTempC: Float? = null,
        val layout: Layout = Layout.LEGACY,
        /** DiAuto's own road load, climate power and temperature (experimental); null = from the range. */
        val energyModel: Mg4MapsEnergyModel.Model? = null,
    )

    fun encode(snapshot: Snapshot): ByteArray {
        require(snapshot.capacityWh > 0)
        require(snapshot.currentWh > 0)
        require(snapshot.rangeMeters > 0)

        val whPerKm = derivedWhPerKm(snapshot)
        // F ≈ (Wh/km · v_kmh) / v_ms = Wh/km · 3.6 at any speed; at REF_SPEED that is
        // the constant force that yields [whPerKm] with B=C=0.
        val constantForceN = (whPerKm * 3.6f).coerceIn(200f, 900f)

        val out = ByteArrayOutputStream()
        val cos = CodedOutputStream.newInstance(out)
        val layout = snapshot.layout
        val variant = snapshot.variant
        val model = snapshot.energyModel
        cos.writeByteArray(1, if (model != null) encodeModelBattery(snapshot, model) else encodeBattery(snapshot, withVehicle = layout != Layout.LEGACY))
        cos.writeByteArray(2, if (model != null) encodeRoadLoad(model.a, 0f, model.c) else encodeRoadLoad(constantForceN))
        val curveOnField4 = variant != null && (layout == Layout.DC_CURVE || layout == Layout.FULL)
        if (curveOnField4) {
            cos.writeByteArray(4, encodeChargeCurve(snapshot.capacityWh, variant!!))
        } else {
            cos.writeByteArray(4, encodeVehicleSpecs())
        }
        // 7 = climate and accessories power by outside temperature: {1 °C, 4 W}, as Ford sends it.
        model?.aux?.forEach { (tempC, watts) ->
            cos.writeByteArray(7, message { row ->
                row.writeFloat(1, tempC)
                row.writeByteArray(4, valueOf(watts))
            })
        }
        if (variant != null && (layout == Layout.DC_TIMES || layout == Layout.FULL)) {
            cos.writeByteArray(8, encodeChargeTimes(snapshot.capacityWh, variant, withAc = layout == Layout.FULL))
        }
        cos.flush()
        return out.toByteArray()
    }

    private fun derivedWhPerKm(snapshot: Snapshot): Float {
        val fromRange = snapshot.currentWh.toFloat() / snapshot.rangeMeters.toFloat() * 1000f
        return if (fromRange.isFinite() && fromRange > 0f) {
            fromRange.coerceIn(MIN_WH_PER_KM, MAX_WH_PER_KM)
        } else {
            DEFAULT_WH_PER_KM
        }
    }

    private fun encodeBattery(snapshot: Snapshot, withVehicle: Boolean): ByteArray = message { cos ->
        if (withVehicle) snapshot.variant?.let { cos.writeByteArray(2, valueOf(it.kerbKg + DRIVER_KG)) }
        // 3 = current Wh, 4 = capacity Wh. Float child is uncertainty — omit it.
        cos.writeByteArray(3, valueOf(snapshot.currentWh))
        cos.writeByteArray(4, valueOf(snapshot.capacityWh))
        if (withVehicle) snapshot.outsideTempC?.let { temp ->
            cos.writeFloat(6, airDensity(temp))
            cos.writeFloat(7, temp)
        }
        // 9 = peak motor power W, 10 = max charging rate W (not discharge).
        cos.writeUInt32(9, snapshot.peakMotorPowerW.coerceAtLeast(1))
        val maxCharge = snapshot.variant?.maxDcW ?: snapshot.maxChargePowerW
        cos.writeUInt32(10, maxCharge.coerceAtLeast(1))
    }

    /**
     * Field 1 for DiAuto's model: the mass is left out (its field is not confirmed: Ford's 1.2 looks
     * like a reserve in Wh), the temperature is the one the model was built for.
     */
    private fun encodeModelBattery(snapshot: Snapshot, model: Mg4MapsEnergyModel.Model): ByteArray = message { cos ->
        cos.writeByteArray(3, valueOf(snapshot.currentWh))
        cos.writeByteArray(4, valueOf(snapshot.capacityWh))
        model.tempC?.let { temp ->
            cos.writeFloat(6, airDensity(temp))
            cos.writeFloat(7, temp)
        }
        cos.writeUInt32(9, snapshot.peakMotorPowerW.coerceAtLeast(1))
        cos.writeUInt32(10, (snapshot.variant?.maxDcW ?: snapshot.maxChargePowerW).coerceAtLeast(1))
    }

    /** Dry air at sea level, kg/m³. */
    private fun airDensity(tempC: Float): Float = (101_325.0 / (287.05 * (tempC + 273.15))).toFloat()

    /**
     * Road-load: constant / linear / quadratic means (N, N·s/m, N·s²/m²).
     * Only the constant term is populated so arrival energy tracks the pack's
     * implied Wh/km without a huge v² term.
     */
    /** All three terms, from DiAuto's model. */
    private fun encodeRoadLoad(a: Float, b: Float, c: Float): ByteArray = message { cos ->
        cos.writeByteArray(1, encodeCoeff(a, 10f))
        cos.writeByteArray(2, encodeCoeff(b, 0.25f))
        cos.writeByteArray(3, encodeCoeff(c, 0.125f))
    }

    private fun encodeRoadLoad(constantForceN: Float): ByteArray = message { cos ->
        cos.writeByteArray(1, encodeCoeff(constantForceN, 10f))
        cos.writeByteArray(2, encodeCoeff(0f, 0.25f))
        cos.writeByteArray(3, encodeCoeff(0f, 0.125f))
    }

    /** Maximum DC power the pack takes, as (energy in the pack Wh, power W) every 5 %. */
    private fun encodeChargeCurve(capacityWh: Int, variant: Mg4Variant): ByteArray = message { cos ->
        for (percent in 5..100 step 5) {
            cos.writeByteArray(1, message { point ->
                point.writeUInt64(1, capacityWh * percent / 100L)
                point.writeUInt64(2, variant.dcPowerW(percent.toDouble()).toLong())
            })
        }
    }

    /**
     * Seconds to full, per charger power. As in the production model: AC rows are keyed by the
     * energy in the pack (every 5 kWh), DC rows by the energy still to add.
     */
    private fun encodeChargeTimes(capacityWh: Int, variant: Mg4Variant, withAc: Boolean): ByteArray = message { cos ->
        for (charger in if (withAc) AC_CHARGERS_W else IntArray(0)) {
            // The on-board charger tapers above 95 % too.
            val power = { percent: Double ->
                minOf(charger, variant.acChargerW) * AC_EFFICIENCY * (if (percent >= 95.0) 0.5 else 1.0)
            }
            cos.writeByteArray(1, chargerRow(charger, (0 until capacityWh step 5_000).map { wh ->
                wh.toLong() to secondsToFull(capacityWh, wh.toDouble(), power)
            }))
        }
        for (charger in DC_CHARGERS_W) {
            val power = { percent: Double ->
                minOf(charger * DC_EFFICIENCY, variant.dcPowerW(percent).toDouble())
            }
            cos.writeByteArray(1, chargerRow(charger, (40 downTo 1).map { step ->
                val toAdd = capacityWh * step / 40.0
                toAdd.toLong() to secondsToFull(capacityWh, capacityWh - toAdd, power)
            }))
        }
    }

    private fun chargerRow(chargerW: Int, points: List<Pair<Long, Long>>): ByteArray = message { cos ->
        cos.writeUInt64(1, chargerW.toLong())
        for ((wh, seconds) in points) {
            cos.writeByteArray(2, message { point ->
                if (wh != 0L) point.writeUInt64(1, wh)
                point.writeUInt64(2, seconds)
            })
        }
    }

    /**
     * Time from [fromWh] to a full pack at [powerW] (battery-side W, by SoC %), in 0.5 % steps.
     */
    private fun secondsToFull(capacityWh: Int, fromWh: Double, powerW: (Double) -> Double): Long {
        var seconds = 0.0
        var wh = fromWh.coerceIn(0.0, capacityWh.toDouble())
        val step = capacityWh * 0.005
        while (wh < capacityWh) {
            val percent = wh * 100.0 / capacityWh
            val watts = powerW(percent)
            val chunk = minOf(step, capacityWh - wh)
            seconds += chunk / watts.coerceAtLeast(100.0) * 3600.0
            wh += chunk
        }
        return seconds.toLong()
    }

    /** The earlier model's field 4: fuel type ELECTRIC, connectors Type 2 and CCS2. */
    private fun encodeVehicleSpecs(): ByteArray = message { cos ->
        cos.writeByteArray(1, valueOf(10)) // ELECTRIC
        cos.writeByteArray(2, valueOf(2)) // MENNEKES / Type 2
        cos.writeByteArray(2, valueOf(5)) // COMBO_2 / CCS2
    }

    private fun valueOf(value: Int): ByteArray = message { it.writeInt32(1, value) }

    private fun encodeCoeff(mean: Float, stddev: Float): ByteArray = message { cos ->
        cos.writeFloat(1, mean)
        cos.writeFloat(2, stddev)
    }

    private inline fun message(write: (CodedOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val cos = CodedOutputStream.newInstance(out)
        write(cos)
        cos.flush()
        return out.toByteArray()
    }

    /** Exposed for unit tests — Wh/km implied by the snapshot after clamping. */
    internal fun whPerKmForTest(snapshot: Snapshot): Float = derivedWhPerKm(snapshot)

    /** Exposed for unit tests — constant force (N) written into road-load field 1. */
    internal fun constantForceNForTest(snapshot: Snapshot): Float =
        (derivedWhPerKm(snapshot) * 3.6f).coerceIn(200f, 900f)

    /** Exposed for unit tests — seconds to full from [fromPercent] on a DC charger of [chargerW]. */
    internal fun dcSecondsToFullForTest(capacityWh: Int, variant: Mg4Variant, chargerW: Int, fromPercent: Double): Long =
        secondsToFull(capacityWh, capacityWh * fromPercent / 100.0) { percent ->
            minOf(chargerW * DC_EFFICIENCY, variant.dcPowerW(percent).toDouble())
        }
}
