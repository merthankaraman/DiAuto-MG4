package com.andrerinas.openheadunit.vehicle

import android.content.Context
import android.os.SystemClock
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog
import com.evsuite.hardware.EVHardware
import kotlin.math.abs

/**
 * Usable capacity (when new) and state of health given to Google Maps, found automatically
 * unless the driver set them in Settings.
 *
 * Capacity: the one the car publishes, else the battery the measurement points to, else the 64 kWh
 * battery. State of health: the measured capacity over the usable one, else 100 %. The measurement
 * is DiAuto's own ([BatteryCapacityMeter]); until it has a sample, MG4 Dash's is read if it is there.
 */
object BatteryCapacityResolver {
    private const val TAG = "DiAuto-MG4"
    private const val CAR_RETRY_MS = 60_000L

    enum class Source { MANUAL, CAR, MEASURED, DEFAULT }

    data class Resolved(
        val netKwh: Float,
        val netSource: Source,
        /** Nameplate kWh behind [netKwh] when it is one of the MG4 batteries. */
        val pack: Int?,
        val sohPercent: Float,
        val sohSource: Source,
    ) {
        val effectiveKwh: Float get() = (netKwh * sohPercent / 100f).coerceIn(1f, 200f)
    }

    /** MG4 batteries: nameplate kWh to usable kWh (ev-database). */
    val PACKS = listOf(51 to 50.8f, 64 to 61.7f, 77 to 74.4f)
    const val DEFAULT_PACK = 64

    fun usableKwh(pack: Int): Float = PACKS.first { it.first == pack }.second

    /** The battery whose usable capacity is nearest to [kwh]. */
    fun nearestPack(kwh: Float): Int = PACKS.minByOrNull { abs(it.second - kwh) }!!.first

    /** Nameplate kWh of a usable figure, when it is one of [PACKS]. */
    fun packOf(netKwh: Float): Int? = PACKS.firstOrNull { abs(it.second - netKwh) < 0.05f }?.first

    /**
     * The battery a measured capacity belongs to: the one whose state of health it gives between
     * 80 and 102 %, the highest if two do (a worn 64 kWh and a new 51 kWh overlap below ~83 %),
     * else the nearest.
     */
    fun packForMeasured(kwh: Float, among: List<Int> = PACKS.map { it.first }): Int {
        val candidates = PACKS.filter { it.first in among }
        return candidates.filter { kwh / it.second in 0.80f..1.02f }.maxByOrNull { kwh / it.second }?.first
            ?: candidates.minByOrNull { abs(it.second - kwh) }!!.first
    }

    /**
     * Batteries the car's configuration byte allows (EVHardware.getBatteryTypeCode): 16 on an
     * SWI69 Standard 51 kWh (measured), 1 = LFP for the stock charging app; 7 and 9 are the packs
     * that take a charge target, so not the LFP one. null for an unknown code.
     */
    fun packsForTypeCode(code: Int?): List<Int>? = when (code) {
        1, 16 -> listOf(51)
        7, 9 -> listOf(64, 77)
        else -> null
    }

    fun resolve(
        manualNetKwh: Float?,
        manualSohPercent: Float?,
        carKwh: Float?,
        mg4Dash: Mg4DashBattery.Reading?,
        measuredKwh: Float? = null,
        carTypeCode: Int? = null,
    ): Resolved {
        val car = carKwh?.let(::carUsableKwh)
        val valid = { kwh: Float? -> kwh?.takeIf { it.isFinite() && it in 30f..100f } }
        val measured = valid(measuredKwh) ?: valid(mg4Dash?.measuredKwh)
        val typePacks = packsForTypeCode(carTypeCode)
        // The car's type settles the battery when it names one; between two, MG4 Dash's choice
        // or the measurement decides, else the larger share (64 kWh).
        val typePack = typePacks?.let { packs ->
            packs.singleOrNull()
                ?: mg4Dash?.pack?.takeIf { it in packs }
                ?: measured?.let { packForMeasured(it, packs) }
                ?: DEFAULT_PACK.takeIf { it in packs }
                ?: packs.first()
        }
        val (net, netSource) = when {
            manualNetKwh != null -> manualNetKwh.coerceIn(1f, 200f) to Source.MANUAL
            car != null -> car to Source.CAR
            typePack != null -> usableKwh(typePack) to Source.CAR
            mg4Dash?.packChosen == true && mg4Dash.pack != null -> usableKwh(mg4Dash.pack) to Source.MEASURED
            measured != null -> usableKwh(packForMeasured(measured)) to Source.MEASURED
            else -> usableKwh(DEFAULT_PACK) to Source.DEFAULT
        }
        val (soh, sohSource) = when {
            manualSohPercent != null -> manualSohPercent.coerceIn(1f, 100f) to Source.MANUAL
            measured != null -> (measured / net * 100f).coerceIn(1f, 100f) to Source.MEASURED
            else -> 100f to Source.DEFAULT
        }
        return Resolved(net, netSource, packOf(net), soh, sohSource)
    }

    /**
     * Usable kWh for a published capacity: a nameplate figure (51, 64 or 77) is turned into the
     * usable one, anything else is taken as usable. Values outside 20–120 kWh are ignored.
     */
    internal fun carUsableKwh(kwh: Float): Float? {
        if (!kwh.isFinite() || kwh !in 20f..120f) return null
        return PACKS.firstOrNull { abs(it.first - kwh) <= 0.6f }?.second ?: kwh
    }

    @Volatile private var carKwh: Float? = null
    @Volatile private var carReadAt = 0L
    @Volatile private var lastLogged: Resolved? = null

    /** Values in use now, with the stored manual settings. */
    fun current(context: Context): Resolved {
        val settings = App.provide(context).settings
        return resolve(
            manualNetKwh = settings.batteryNetCapacityKwh.takeIf { settings.batteryCapacityManual },
            manualSohPercent = settings.batteryStateOfHealthPercent.takeIf { settings.batterySohManual },
            carKwh = carCapacityKwh(),
            mg4Dash = Mg4DashBattery.read(context),
            measuredKwh = BatteryCapacityMeter.capacityKwh(context),
            carTypeCode = carBatteryType(),
        ).also(::logChange)
    }

    /** Car capacity, read once; retried every minute while the car has not answered. */
    @Volatile private var typeLogged = false
    @Volatile private var typeFailureLogged: String? = null

    /** Battery type code from the car's configuration, logged once (see EVHardware.getBatteryTypeCode). */
    @Volatile private var typeCode: Int? = null

    /** Read once: the configuration does not change while the car runs. */
    fun carBatteryType(): Int? {
        typeCode?.let { return it }
        val code = runCatching { EVHardware.getBatteryTypeCode() }.getOrNull()
        if (code == null) {
            val failure = EVHardware.lastVehicleConfigFailure
            if (failure != null && failure != typeFailureLogged) {
                typeFailureLogged = failure
                AppLog.w("$TAG battery type not read from the car configuration: $failure")
            }
            return null
        }
        typeCode = code
        if (!typeLogged) {
            typeLogged = true
            AppLog.i("$TAG battery type code from the car configuration: $code (bytes ${EVHardware.lastVehicleConfigBytes})")
        }
        return code
    }

    fun carCapacityKwh(): Float? {
        carKwh?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (carReadAt != 0L && now - carReadAt < CAR_RETRY_MS) return null
        carReadAt = now
        return runCatching { EVHardware.getBatteryCapacityKwh() }.getOrNull()
            ?.takeIf { carUsableKwh(it) != null }
            ?.also {
                carKwh = it
                AppLog.i("$TAG battery capacity published by the car: $it kWh")
            }
    }

    private fun logChange(resolved: Resolved) {
        if (resolved == lastLogged) return
        lastLogged = resolved
        AppLog.i(
            "$TAG battery net=${resolved.netKwh} kWh (${resolved.netSource}) " +
                "soh=${resolved.sohPercent} % (${resolved.sohSource}) effective=${resolved.effectiveKwh} kWh",
        )
    }
}
