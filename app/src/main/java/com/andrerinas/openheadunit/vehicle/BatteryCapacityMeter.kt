package com.andrerinas.openheadunit.vehicle

import android.content.Context
import com.andrerinas.openheadunit.utils.AppLog
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Measures the pack's real capacity while driving, as MG4Dash does, for cars that publish neither
 * their capacity nor the energy left (SWI69 included): energy out of the pack (BMS voltage ×
 * current, regeneration counted back) divided by the drop in charge %. The drop builds up over
 * trips and days; at 15 % a sample is kept, dated. Capacity = their average weighted by drop, over
 * the last 30 days (else all of them). Charging and gaps in the readings are left out.
 */
object BatteryCapacityMeter {
    private const val TAG = "DiAuto-MG4"
    private const val PREFS = "battery_capacity_meter"
    const val MIN_DROP = 15f
    private const val MONTH_MS = 30L * 24 * 3600_000L
    private const val MAX_SAMPLES = 300
    /** Readings further apart than this are a gap, not a stretch of driving. */
    private const val MAX_STEP_MS = 5_000L

    data class Sample(val at: Long, val kwh: Float, val drop: Float)

    private var accKwh = 0f
    private var accDrop = 0f
    private var loaded = false
    private var lastSoc: Float? = null
    private var lastAt = 0L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * One reading: pack power [powerW] (positive out), charge [socPercent], [nowMs] elapsed
     * realtime, [charging] while plugged in. Call it every few seconds.
     */
    @Synchronized
    fun reading(context: Context, powerW: Float?, socPercent: Float?, charging: Boolean, nowMs: Long) {
        load(context)
        val prev = lastSoc
        val dt = nowMs - lastAt
        if (charging || powerW == null || socPercent == null || prev == null || dt <= 0 || dt > MAX_STEP_MS ||
            abs(prev - socPercent) > 2f
        ) {
            lastSoc = socPercent.takeUnless { charging }
            lastAt = nowMs
            return
        }
        accKwh += powerW * dt / 3_600_000_000f
        accDrop += prev - socPercent
        lastSoc = socPercent
        lastAt = nowMs
        if (accDrop < MIN_DROP) {
            if (nowMs / 30_000 != (nowMs - dt) / 30_000) save(context)
            return
        }
        val kwh = accKwh / accDrop * 100f
        if (accKwh >= 3f && kwh in 30f..100f) {
            val list = samples(context) + Sample(System.currentTimeMillis(), kwh, accDrop)
            prefs(context).edit().putString("samples", toJson(list.takeLast(MAX_SAMPLES))).apply()
            AppLog.i("$TAG capacity sample %.1f kWh over %.1f %%".format(kwh, accDrop))
        } else {
            AppLog.i("$TAG capacity sample rejected: %.2f kWh for %.1f %%".format(accKwh, accDrop))
        }
        accKwh = 0f
        accDrop = 0f
        save(context)
    }

    /** Measured capacity in kWh, or null before the first sample. */
    fun capacityKwh(context: Context): Float? {
        val all = samples(context)
        val recent = all.filter { System.currentTimeMillis() - it.at < MONTH_MS }
        return weighted(recent) ?: weighted(all)
    }

    /** Drop built up towards the next sample, in %. */
    @Synchronized
    fun progressPercent(context: Context): Float {
        load(context)
        return accDrop.coerceAtLeast(0f)
    }

    private fun weighted(list: List<Sample>): Float? {
        val w = list.sumOf { it.drop.toDouble() }
        return if (w <= 0.0) null else (list.sumOf { (it.kwh * it.drop).toDouble() } / w).toFloat()
    }

    private fun load(context: Context) {
        if (loaded) return
        val p = prefs(context)
        accKwh = p.getFloat("acc_kwh", 0f)
        accDrop = p.getFloat("acc_drop", 0f)
        loaded = true
    }

    private fun save(context: Context) {
        prefs(context).edit().putFloat("acc_kwh", accKwh).putFloat("acc_drop", accDrop).apply()
    }

    fun samples(context: Context): List<Sample> = try {
        val a = JSONArray(prefs(context).getString("samples", "[]"))
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let { Sample(it.getLong("t"), it.getDouble("k").toFloat(), it.getDouble("d").toFloat()) }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun toJson(list: List<Sample>): String = JSONArray().also { a ->
        list.forEach { a.put(JSONObject().put("t", it.at).put("k", it.kwh.toDouble()).put("d", it.drop.toDouble())) }
    }.toString()

    /** Forget the measurement (with the battery reset in Settings). */
    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        accKwh = 0f
        accDrop = 0f
        lastSoc = null
    }
}
