package com.andrerinas.openheadunit.vehicle

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream

/**
 * What MG4 Dash knows about the battery, read from its own settings file.
 *
 * Both apps are platform-signed with the system shared user id, so DiAuto may read MG4 Dash's
 * preferences directly; on a phone build, or without MG4 Dash, [read] returns null.
 *
 * MG4 Dash measures the usable capacity while driving (`cap_samples`: last 30 days, otherwise all,
 * weighted by their charge drop), which stands in until DiAuto's own measurement has a sample, and
 * has a battery chosen in its settings (`battery_model`). When none was chosen, the one nearest to
 * the measurement is used instead of MG4 Dash's 51 kWh default.
 */
object Mg4DashBattery {
    const val PACKAGE = "app.mg4dash"
    private const val PREFS_FILE = "shared_prefs/mg4launcher.xml"
    private const val MONTH_MS = 30L * 24 * 3600_000L

    data class Reading(
        /** Nameplate kWh (51, 64 or 77), or null when MG4 Dash has neither a choice nor a measurement. */
        val pack: Int?,
        /** True when the battery was chosen in MG4 Dash's settings rather than inferred. */
        val packChosen: Boolean,
        /** Usable capacity measured by MG4 Dash today, kWh. */
        val measuredKwh: Float?,
    )

    @Volatile private var cached: Reading? = null
    @Volatile private var cachedStamp = Long.MIN_VALUE

    fun read(context: Context): Reading? {
        val file = prefsFile(context) ?: return null
        val stamp = file.lastModified()
        if (stamp == cachedStamp) return cached
        val reading = runCatching { file.inputStream().use { parse(it, System.currentTimeMillis()) } }.getOrNull()
        cached = reading
        cachedStamp = stamp
        return reading
    }

    private fun prefsFile(context: Context): File? = runCatching {
        val dataDir = context.packageManager.getApplicationInfo(PACKAGE, 0).dataDir
        File(dataDir, PREFS_FILE).takeIf { it.canRead() }
    }.getOrNull()

    internal fun parse(xml: InputStream, now: Long): Reading? {
        val parser = Xml.newPullParser()
        parser.setInput(xml, null)
        var model: Int? = null
        var samples: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.getAttributeValue(null, "name")) {
                "battery_model" -> model = parser.getAttributeValue(null, "value")?.toIntOrNull()
                "cap_samples" -> samples = parser.nextText()
            }
        }
        return fromValues(model, samples, now)
    }

    internal fun fromValues(model: Int?, samplesJson: String?, now: Long): Reading? {
        val measured = measuredKwh(samplesJson, now)
        val chosen = model?.takeIf { pack -> BatteryCapacityResolver.PACKS.any { it.first == pack } }
        val pack = chosen ?: measured?.let(BatteryCapacityResolver::nearestPack) ?: return null
        return Reading(pack, chosen != null, measured)
    }

    private fun measuredKwh(samplesJson: String?, now: Long): Float? {
        if (samplesJson.isNullOrBlank()) return null
        val all = runCatching {
            val arr = JSONArray(samplesJson)
            (0 until arr.length()).map { i ->
                arr.getJSONObject(i).let { o -> Triple(o.getLong("t"), o.getDouble("k"), o.getInt("d")) }
            }
        }.getOrNull()?.filter { it.third > 0 && it.second.isFinite() } ?: return null
        val recent = all.filter { now - it.first < MONTH_MS }.ifEmpty { all }
        val weight = recent.sumOf { it.third }
        if (weight == 0) return null
        return (recent.sumOf { it.second * it.third } / weight).toFloat()
    }
}
