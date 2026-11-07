package com.evsuite.hardware.telemetry

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One moment in the pack's life, including the ones a trip never sees.
 *
 * The trip store answers "what did that drive cost". Battery health asks a different question —
 * what did the charge do between two charges — and the answer spans the parking, the overnight
 * standing and the drives in between. None of that is in `trips.json`: a trip begins when the
 * car moves and a pack loses charge whether or not it does.
 *
 * Every field except the charge itself is nullable and stays that way. An entry with no charge
 * reading says nothing at all and is never written; everything else is recorded when the car
 * offers it and left absent when it does not.
 */
data class BatteryLedgerEntry(
    val atMs: Long,
    val socPercent: Float,
    val odometerKm: Float? = null,
    val outsideTempCelsius: Float? = null,
    /**
     * The vehicle's own energy counters, as they read at this moment — `SaicCharging`
     * transactions 75 and 77, reset by the car at the end of a charge. Measured, nothing
     * integrated here. A drop between two entries is that reset, never regeneration.
     */
    val vehicleConsumedKwh: Float? = null,
    val vehicleRegeneratedKwh: Float? = null,
    /**
     * This app's own running integral of pack power, cumulative since the ledger began.
     *
     * Cumulative rather than per-entry so a window is one subtraction, and so a missed sample
     * costs the window that contains it instead of every window after it.
     */
    val packEnergyKwh: Double? = null,
    /** Distance this app actually integrated, cumulative, beside [odometerKm] which is the car's. */
    val integratedDistanceKm: Double? = null,
    val chargingStatus: Int? = null,
    val chargePortConnected: Boolean? = null,
    val parked: Boolean? = null,
)

/** The ledger file's envelope, stating its own version exactly as the trip history does. */
data class BatteryLedgerFile(
    val schemaVersion: Int = SCHEMA_VERSION,
    val entries: List<BatteryLedgerEntry>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Bounded app-private ledger, replaced atomically after every successful write.
 *
 * Same shape as [EnergyTripHistoryStore] and for the same reasons: a file this build cannot
 * read is quarantined rather than deleted, and the bound is a count rather than a byte budget
 * because an entry here is a fixed handful of numbers.
 */
class BatteryLedgerStore(
    private val target: File,
    private val maxEntries: Int = MAX_ENTRIES,
    private val gson: Gson = Gson(),
) {
    /** Oldest first, which is the order every reader of this file walks it in. */
    fun read(): List<BatteryLedgerEntry> {
        if (!target.exists()) return emptyList()
        val text = runCatching { target.readText() }.getOrNull() ?: return emptyList()
        return parse(text) ?: run {
            quarantine()
            emptyList()
        }
    }

    fun append(entry: BatteryLedgerEntry): Boolean = write(bound(read() + entry))

    fun clear(): Boolean = write(emptyList())

    @Suppress("SENSELESS_COMPARISON")
    private fun parse(text: String): List<BatteryLedgerEntry>? {
        val envelope = runCatching { gson.fromJson(text, LedgerEnvelope::class.java) }
            .getOrNull() ?: return null
        if (envelope.schemaVersion != BatteryLedgerFile.SCHEMA_VERSION) return null
        // Gson writes straight through Kotlin's nullability: a truncated file can hold an entry
        // with no charge reading at all, and the comparison against null is not senseless here.
        return envelope.entries.orEmpty()
            .filter { it != null && it.socPercent != null && it.socPercent.isFinite() }
            .sortedBy { it.atMs }
    }

    private data class LedgerEnvelope(
        val schemaVersion: Int = 0,
        val entries: List<BatteryLedgerEntry>? = null,
    )

    /** Newest entries win: a health estimate is about the pack as it is now. */
    private fun bound(entries: List<BatteryLedgerEntry>): List<BatteryLedgerEntry> =
        if (entries.size <= maxEntries) entries
        else entries.subList(entries.size - maxEntries.coerceAtLeast(1), entries.size)

    private fun quarantine() {
        val kept = File(target.parentFile, "${target.name}.quarantine.${System.currentTimeMillis()}")
        if (target.renameTo(kept)) return
        if (runCatching { target.copyTo(kept, overwrite = true) }.isSuccess) target.delete()
        else kept.delete()
    }

    private fun write(entries: List<BatteryLedgerEntry>): Boolean {
        val bytes = gson.toJson(BatteryLedgerFile(entries = entries)).toByteArray(Charsets.UTF_8)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    companion object {
        /** About a year of ordinary use at one entry per point of charge moved. */
        const val MAX_ENTRIES = 2_000
        const val FILE_NAME = "battery-ledger.json"
    }
}

/**
 * Turns the 1 Hz sample stream into the handful of entries a health estimate needs.
 *
 * Two jobs, and they are one class because the second depends on the first. It keeps the
 * running integrals — pack energy and distance — that no single sample carries, and it decides
 * when the ledger has learnt something worth a disk write. Writing every sample would be 3 600
 * rewrites an hour of a file that would say the same thing as one.
 *
 * An entry is earned by a change: the charge moved by a point, the charging state changed, or
 * enough quiet time passed that the next reader cannot tell a parked car from a stopped app.
 *
 * **The integrals are cumulative and survive a restart by being re-seeded from the last entry,
 * not by being recomputed.** Whatever happened while this process was not running is lost, and
 * that is exactly what [BatteryLedgerEntry.integratedDistanceKm] against the car's own odometer
 * lets a reader detect — a window whose integral covers less ground than the odometer moved is
 * refused rather than believed.
 */
class BatteryLedgerRecorder(
    private val store: BatteryLedgerStore,
    private val socStepPercent: Float = SOC_STEP_PERCENT,
    private val quietIntervalMs: Long = QUIET_INTERVAL_MS,
    private val maxSampleGapMs: Long = MAX_SAMPLE_GAP_MS,
) {
    private var last: EnergySnapshot? = null
    private var lastEntry: BatteryLedgerEntry? = null
    private var packEnergyKwh: Double = 0.0
    private var integratedDistanceKm: Double = 0.0
    private var hasPowerInterval = false
    private var hasSpeedInterval = false
    private var seeded = false

    /** @return true when this sample earned an entry and it was written. */
    fun observe(snapshot: EnergySnapshot): Boolean {
        seed()
        integrate(snapshot)
        val soc = snapshot.socPercent?.takeIf { it.isFinite() } ?: return false
        val previous = lastEntry
        val earned = previous == null ||
            kotlin.math.abs(soc - previous.socPercent) >= socStepPercent ||
            snapshot.chargingStatus != previous.chargingStatus ||
            snapshot.timestampMs - previous.atMs >= quietIntervalMs
        if (!earned) return false
        val entry = BatteryLedgerEntry(
            atMs = snapshot.timestampMs,
            socPercent = soc,
            odometerKm = snapshot.odometerKm,
            outsideTempCelsius = snapshot.outsideTempCelsius,
            vehicleConsumedKwh = snapshot.vehicleConsumedKwh,
            vehicleRegeneratedKwh = snapshot.vehicleRegeneratedKwh,
            packEnergyKwh = packEnergyKwh.takeIf { hasPowerInterval },
            integratedDistanceKm = integratedDistanceKm.takeIf { hasSpeedInterval },
            chargingStatus = snapshot.chargingStatus,
            chargePortConnected = snapshot.chargePortConnected,
            parked = snapshot.parked,
        )
        if (!store.append(entry)) return false
        lastEntry = entry
        return true
    }

    private fun seed() {
        if (seeded) return
        seeded = true
        val previous = store.read().lastOrNull() ?: return
        lastEntry = previous
        packEnergyKwh = previous.packEnergyKwh ?: 0.0
        integratedDistanceKm = previous.integratedDistanceKm ?: 0.0
        hasPowerInterval = previous.packEnergyKwh != null
        hasSpeedInterval = previous.integratedDistanceKm != null
    }

    /** Trapezoid over adjacent samples only, with the same five-second bound trips use. */
    private fun integrate(snapshot: EnergySnapshot) {
        val previous = last
        last = snapshot
        if (previous == null) return
        val gapMs = snapshot.timestampMs - previous.timestampMs
        if (gapMs <= 0L || gapMs > maxSampleGapMs) return
        val hours = gapMs / 3_600_000.0
        val previousPower = previous.batteryPowerKw
        val power = snapshot.batteryPowerKw
        if (previousPower != null && power != null) {
            hasPowerInterval = true
            packEnergyKwh += (previousPower + power) / 2.0 * hours
        }
        val previousSpeed = previous.speedKmh
        val speed = snapshot.speedKmh
        if (previousSpeed != null && speed != null) {
            hasSpeedInterval = true
            integratedDistanceKm += (previousSpeed + speed) / 2.0 * hours
        }
    }

    companion object {
        /** One point of charge is the smallest step this car's gauge actually takes. */
        const val SOC_STEP_PERCENT = 1.0f
        const val QUIET_INTERVAL_MS = 15 * 60 * 1000L
        const val MAX_SAMPLE_GAP_MS = 5_000L
    }
}
