package com.andrerinas.openheadunit.vehicle

import android.content.Context
import android.os.storage.StorageManager
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.utils.AppLog
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.SaicClimate
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records a trip the driver chose, with the battery % Google Maps forecast at departure, in a file
 * of its own that can go to a USB stick. It ends by itself once parked, charging or switched off, and
 * is saved along the way so a car switched off loses nothing.
 */
object TripRecorder {
    private const val TAG = "DiAuto-MG4"
    private const val DIR = "trips"
    private const val ACTIVE = "active.json"
    private const val SAVE_MS = 30_000L
    private const val PARKED_S = 300f
    private const val OFF_GAP_MS = 300_000L
    /** Below this, the trip hasn't really begun: waiting parked before leaving doesn't end it. */
    private const val STARTED_KM = 1f
    private const val MAX_STEP_S = 5f
    private const val CLIMATE_MS = 5_000L
    private const val USB_FOLDER = "DiAuto-MG4-trips"
    private const val ODOMETER_START = "odometerStartKm"

    @Volatile private var log: TripLog? = null
    private var loaded = false
    private var lastTickMs = 0L
    private var lastSaveMs = 0L
    private var climateReadMs = 0L
    private var climateOn: Boolean? = null
    private var tempC: Float? = null

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    /** Picks up a trip left active by a restart of the app or the car. */
    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val file = File(dir(context), ACTIVE)
        if (!file.exists()) return
        val saved = runCatching { TripLog.fromJson(JSONObject(file.readText())) }.getOrNull()
        if (saved == null) {
            file.delete()
            return
        }
        log = saved
        if (System.currentTimeMillis() - file.lastModified() > OFF_GAP_MS) finish(context, "car_off")
    }

    fun isActive(context: Context): Boolean {
        load(context)
        return log != null
    }

    /** False when the car's charge can't be read, or it is plugged in. */
    @Synchronized
    fun start(context: Context, mapsArrivalPercent: Int): Boolean {
        load(context)
        if (log != null) return false
        val reading = EVHardware.getCapacityReading()
        if (reading.plugged) return false
        val percent = reading.precisePercent ?: reading.percent?.toFloat() ?: return false
        val settings = App.provide(context).settings
        val battery = BatteryCapacityResolver.current(context)
        val variant = Mg4Variant.resolve(settings.mg4Variant, (battery.effectiveKwh * 1000).toInt())
        val header = JSONObject().apply {
            put("started", SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).format(Date()))
            put("startedWhileMoving", (EVHardware.getVehicleSpeedKmh() ?: 0f) >= TripLog.MOVING_KMH)
            put("diauto", BuildConfig.VERSION_NAME)
            put("firmware", FirmwareInfo.getDetectedString())
            EVHardware.getOdometerKm()?.let { put(ODOMETER_START, it.toDouble()) }
            put("version", variant.label)
            put("netKwh", battery.netKwh.toDouble())
            put("sohPercent", battery.sohPercent.toDouble())
            put("sohSource", battery.sohSource.name)
            put("capacitySentKwh", battery.effectiveKwh.toDouble())
            put("mapsModel", settings.mapsEnergyModel)
        }
        lastTickMs = 0L
        lastSaveMs = 0L
        climateReadMs = 0L
        log = TripLog(header, mapsArrivalPercent, percent)
        save(context)
        AppLog.i("$TAG trip recording started at $percent %, Maps forecast $mapsArrivalPercent %")
        return true
    }

    /** One reading, every second, while a trip is recorded. */
    @Synchronized
    fun tick(context: Context, reading: EVHardware.CapacityReading, nowMs: Long) {
        load(context)
        val trip = log ?: return
        // The car slept with the app still alive: as good as switched off.
        if (lastTickMs != 0L && nowMs - lastTickMs > OFF_GAP_MS) {
            finish(context, "car_off")
            return
        }
        val dt = if (lastTickMs == 0L) 0f else ((nowMs - lastTickMs) / 1000f).coerceIn(0f, MAX_STEP_S)
        lastTickMs = nowMs
        if (lastTickMs - climateReadMs >= CLIMATE_MS) {
            climateReadMs = lastTickMs
            climateOn = runCatching { SaicClimate.powerOn() }.getOrNull()
            tempC = runCatching { SaicClimate.outsideTempCelsius() ?: EVHardware.getOutsideTempCelsius() }.getOrNull()
                ?.takeIf { it in -40f..60f }
                ?.let { Math.round(it).toFloat() }
        }
        val speed = EVHardware.getVehicleSpeedKmh()?.coerceAtLeast(0f) ?: 0f
        trip.plugged = trip.plugged || reading.plugged
        trip.tick(dt, speed, reading.powerW, reading.precisePercent ?: reading.percent?.toFloat(), tempC, climateOn, model(trip))
        when {
            reading.plugged -> finish(context, "charging")
            trip.stoppedFor >= PARKED_S && trip.totalKm >= STARTED_KM -> finish(context, "parked")
            nowMs - lastSaveMs >= SAVE_MS -> {
                lastSaveMs = nowMs
                save(context)
            }
        }
    }

    private fun model(trip: TripLog): TripLog.Model? {
        if (!trip.header.optBoolean("mapsModel")) return null
        val variant = Mg4Variant.entries.firstOrNull { it.label == trip.header.optString("version") } ?: return null
        val model = Mg4MapsEnergyModel.build(variant, tempC, climateOn)
        return TripLog.Model(model.a, model.c, MapsModelTrip.auxWatts(model)) { MapsModelTrip.modelWatts(model, it) }
    }

    /** Ends the trip; false when it was too short to say anything and is not kept. */
    @Synchronized
    fun finish(context: Context, reason: String): Boolean {
        val trip = log ?: return false
        log = null
        val active = File(dir(context), ACTIVE)
        if (!trip.isRepresentative) {
            active.delete()
            AppLog.i("$TAG trip recording dropped ($reason): %.1f km, %.1f to %.1f %%".format(
                Locale.US, trip.totalKm, trip.startPercent, trip.arrivalPercent))
            return false
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH", Locale.US).format(Date())
        var target = File(dir(context), "trip_$stamp.json")
        var n = 2
        while (target.exists()) target = File(dir(context), "trip_${stamp}_${n++}.json")
        // The distance by the car's own odometer checks the one integrated from the speed; the
        // mileage itself doesn't leave the car.
        val json = trip.toJson(reason)
        val start = json.remove(ODOMETER_START) as? Number
        val end = runCatching { EVHardware.getOdometerKm() }.getOrNull()
        if (start != null && end != null) json.put("odometerKm", end - start.toDouble())
        target.writeText(json.toString(2))
        active.delete()
        AppLog.i("$TAG trip saved ($reason): %.1f km, %.1f to %.1f %%, Maps forecast %d %%".format(
            Locale.US, trip.totalKm, trip.startPercent, trip.arrivalPercent, trip.mapsArrivalPercent))
        return true
    }

    private fun save(context: Context) {
        val trip = log ?: return
        val temp = File(dir(context), "$ACTIVE.tmp")
        temp.writeText(trip.toJson().toString())
        temp.renameTo(File(dir(context), ACTIVE))
    }

    private fun savedTrips(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.startsWith("trip_") && f.name.endsWith(".json") }?.toList().orEmpty()

    fun savedCount(context: Context): Int = savedTrips(context).size

    class UsbMove(val count: Int, val folder: String)

    /** Trips copied to a USB stick, checked, then removed from the car; null with no stick or on failure. */
    fun moveToUsb(context: Context): UsbMove? {
        val target = usbFolder(context) ?: return null
        var moved = 0
        for (file in savedTrips(context)) {
            val copy = File(target, file.name)
            val ok = runCatching { file.copyTo(copy, overwrite = true).readBytes().contentEquals(file.readBytes()) }
                .onFailure { AppLog.w("$TAG trip copy failed: ${it.message}") }
                .getOrDefault(false)
            if (!ok) return null
            file.delete()
            moved++
        }
        AppLog.i("$TAG $moved trips moved to ${target.path}")
        return UsbMove(moved, target.path)
    }

    /**
     * Where the car lets DiAuto write on the stick: its root as the system apps see it, or else the
     * folder Android gives every app there, which is always writable.
     */
    private fun usbFolder(context: Context): File? {
        val volume = runCatching {
            context.getSystemService(StorageManager::class.java).storageVolumes
                .firstOrNull { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
        }.getOrNull()
        if (volume == null) {
            AppLog.w("$TAG no USB stick mounted")
            return null
        }
        val path = runCatching { volume.javaClass.getMethod("getPath").invoke(volume) as? String }.getOrNull()
        val candidates = listOfNotNull(
            volume.uuid?.let { File("/mnt/media_rw/$it") },
            path?.let(::File),
            context.getExternalFilesDirs(null).filterNotNull().firstOrNull { d -> path != null && d.path.startsWith(path) },
        )
        for (root in candidates) {
            val folder = File(root, USB_FOLDER)
            val usable = runCatching {
                (folder.isDirectory || folder.mkdirs()) &&
                    File(folder, ".write").let { probe -> probe.writeText("ok"); probe.delete() }
            }.getOrDefault(false)
            if (usable) return folder
            AppLog.w("$TAG can't write trips to $folder")
        }
        return null
    }
}
