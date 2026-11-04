package com.andrerinas.openheadunit.vehicle

import org.json.JSONArray
import org.json.JSONObject

/**
 * The figures of one recorded trip, for tuning the Maps energy model: no position, road or phone.
 * A new segment starts whenever the climate or the outside temperature changes, so their cost can be
 * told apart from the rest. Fed once a second by [TripRecorder].
 */
class TripLog(val header: JSONObject, val mapsArrivalPercent: Int, val startPercent: Float) {

    class Segment(val climateOn: Boolean?, val tempC: Float?) {
        var seconds = 0f
        var stoppedSeconds = 0f
        var km = 0f
        var outKwh = 0f
        var regenKwh = 0f
        var modelKwh = 0f
        var modelA: Float? = null
        var modelC: Float? = null
        var modelAuxW: Int? = null
        var startPercent: Float? = null
        var endPercent: Float? = null
        val speedSeconds = FloatArray(SPEED_BUCKETS)
        val speedKm = FloatArray(SPEED_BUCKETS)
        val speedKwh = FloatArray(SPEED_BUCKETS)
    }

    val segments = mutableListOf<Segment>()

    /** Charge when the car last moved: waiting parked afterwards is not part of the trip. */
    var arrivalPercent: Float = startPercent
        private set

    /** Seconds since the car last moved. */
    var stoppedFor = 0f
        private set

    var plugged = false

    val totalKm: Float get() = segments.sumOf { it.km.toDouble() }.toFloat()

    fun tick(dtS: Float, speedKmh: Float, powerW: Float?, percent: Float?, tempC: Float?, climateOn: Boolean?, model: Model?) {
        val current = segmentFor(climateOn, tempC, percent, model)
        val moving = speedKmh >= MOVING_KMH
        current.seconds += dtS
        if (moving) stoppedFor = 0f else {
            current.stoppedSeconds += dtS
            stoppedFor += dtS
        }
        val km = speedKmh * dtS / 3600f
        current.km += km
        val kwh = (powerW ?: 0f) * dtS / 3_600_000f
        if (kwh >= 0f) current.outKwh += kwh else current.regenKwh -= kwh
        model?.let { current.modelKwh += it.watts(speedKmh) * dtS / 3_600_000f }
        val bucket = bucketOf(speedKmh)
        current.speedSeconds[bucket] += dtS
        current.speedKm[bucket] += km
        current.speedKwh[bucket] += kwh
        percent?.let {
            if (current.startPercent == null) current.startPercent = it
            current.endPercent = it
            if (moving) arrivalPercent = it
        }
    }

    private fun segmentFor(climateOn: Boolean?, tempC: Float?, percent: Float?, model: Model?): Segment {
        val last = segments.lastOrNull()
        if (last != null && last.climateOn == climateOn && sameTemp(last.tempC, tempC)) return last
        return Segment(climateOn, tempC).also {
            it.startPercent = percent
            it.modelA = model?.a
            it.modelC = model?.c
            it.modelAuxW = model?.auxW
            segments += it
        }
    }

    private fun sameTemp(a: Float?, b: Float?): Boolean =
        a == b || (a != null && b != null && kotlin.math.abs(a - b) < TEMP_STEP_C)

    /** What the model sent to Maps expects, for comparing with what the battery really gave. */
    class Model(val a: Float, val c: Float, val auxW: Int, val watts: (Float) -> Float)

    fun toJson(reason: String? = null): JSONObject = JSONObject(header.toString()).apply {
        put("schema", 1)
        put("mapsArrivalPercent", mapsArrivalPercent)
        put("startPercent", startPercent.toDouble())
        put("arrivalPercent", arrivalPercent.toDouble())
        put("km", totalKm.toDouble())
        put("plugged", plugged)
        reason?.let { put("endReason", it) }
        put("speedBucketsKmh", JSONArray(SPEED_EDGES.toList()))
        put("segments", JSONArray().also { list ->
            segments.forEach { s ->
                list.put(JSONObject().apply {
                    put("climateOn", s.climateOn ?: JSONObject.NULL)
                    put("tempC", s.tempC?.toDouble() ?: JSONObject.NULL)
                    put("seconds", s.seconds.toDouble())
                    put("stoppedSeconds", s.stoppedSeconds.toDouble())
                    put("km", s.km.toDouble())
                    put("outKwh", s.outKwh.toDouble())
                    put("regenKwh", s.regenKwh.toDouble())
                    put("modelKwh", s.modelKwh.toDouble())
                    put("modelA", s.modelA?.toDouble() ?: JSONObject.NULL)
                    put("modelC", s.modelC?.toDouble() ?: JSONObject.NULL)
                    put("modelAuxW", s.modelAuxW ?: JSONObject.NULL)
                    put("startPercent", s.startPercent?.toDouble() ?: JSONObject.NULL)
                    put("endPercent", s.endPercent?.toDouble() ?: JSONObject.NULL)
                    put("speedSeconds", floats(s.speedSeconds))
                    put("speedKm", floats(s.speedKm))
                    put("speedKwh", floats(s.speedKwh))
                })
            }
        })
    }

    private fun floats(values: FloatArray) = JSONArray().also { a -> values.forEach { a.put(it.toDouble()) } }

    companion object {
        /** Below this, the car counts as stopped. */
        const val MOVING_KMH = 1f
        private const val TEMP_STEP_C = 1f
        val SPEED_EDGES = intArrayOf(0, 30, 50, 70, 90, 110, 130)
        private val SPEED_BUCKETS = SPEED_EDGES.size

        internal fun bucketOf(speedKmh: Float): Int =
            SPEED_EDGES.indexOfLast { speedKmh >= it }.coerceAtLeast(0)

        /** Back from a saved [toJson], after the app or the car restarted during the trip. */
        fun fromJson(json: JSONObject): TripLog {
            val header = JSONObject(json.toString()).apply {
                listOf("schema", "mapsArrivalPercent", "startPercent", "arrivalPercent", "km", "plugged",
                    "endReason", "speedBucketsKmh", "segments").forEach { remove(it) }
            }
            val log = TripLog(header, json.getInt("mapsArrivalPercent"), json.getDouble("startPercent").toFloat())
            log.arrivalPercent = json.getDouble("arrivalPercent").toFloat()
            log.plugged = json.optBoolean("plugged")
            val list = json.getJSONArray("segments")
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val segment = Segment(
                    if (o.isNull("climateOn")) null else o.getBoolean("climateOn"),
                    o.floatOrNull("tempC"),
                )
                segment.seconds = o.getDouble("seconds").toFloat()
                segment.stoppedSeconds = o.getDouble("stoppedSeconds").toFloat()
                segment.km = o.getDouble("km").toFloat()
                segment.outKwh = o.getDouble("outKwh").toFloat()
                segment.regenKwh = o.getDouble("regenKwh").toFloat()
                segment.modelKwh = o.getDouble("modelKwh").toFloat()
                segment.modelA = o.floatOrNull("modelA")
                segment.modelC = o.floatOrNull("modelC")
                segment.modelAuxW = if (o.isNull("modelAuxW")) null else o.getInt("modelAuxW")
                segment.startPercent = o.floatOrNull("startPercent")
                segment.endPercent = o.floatOrNull("endPercent")
                o.getJSONArray("speedSeconds").readInto(segment.speedSeconds)
                o.getJSONArray("speedKm").readInto(segment.speedKm)
                o.getJSONArray("speedKwh").readInto(segment.speedKwh)
                log.segments += segment
            }
            return log
        }

        private fun JSONObject.floatOrNull(key: String): Float? =
            if (isNull(key)) null else getDouble(key).toFloat()

        private fun JSONArray.readInto(target: FloatArray) {
            for (i in target.indices) target[i] = getDouble(i).toFloat()
        }
    }
}
