package com.andrerinas.openheadunit.vehicle

import android.content.Context
import android.os.SystemClock
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.FirmwareInfo
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Read-only MG4 bridge for Android Auto VehicleEnergyModel.
 *
 * SoC/range come only from [EVHardware.getBatterySocPercent] /
 * [EVHardware.getRemainingRangeKm] (Saic + CPM in one place). Pack Wh from net × SOH
 * ([BatteryCapacityResolver]).
 */
object Mg4EnergyProvider {
    private const val TAG = "DiAuto-MG4"
    private const val POLL_MS = 5_000L
    private const val METER_MS = 1_000L
    private const val DASH_CHECK_MS = 60_000L
    private const val STALE_MS = 3 * 60_000L
    /** Cover SAIC CarService / CPM bind ladder (retries out to 60s). */
    private const val INITIAL_READ_TIMEOUT_MS = 60_000L
    private const val INITIAL_RETRY_MS = 500L

    data class EnergySnapshot(
        val capacityWh: Int,
        val currentWh: Int,
        val rangeMeters: Int,
        val batteryPercent: Double,
        val rangeKm: Int,
        val demo: Boolean = false,
    )

    @Volatile private var app: Context? = null
    @Volatile private var latest: EnergySnapshot? = null
    @Volatile private var latestMillis = 0L
    @Volatile private var started = false
    @Volatile private var initialized = false
    private var pollFuture: ScheduledFuture<*>? = null
    private var meterFuture: ScheduledFuture<*>? = null
    private var dashMeasures = false
    private var dashCheckedAt = 0L
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "diauto-mg4-energy").apply { isDaemon = true }
    }

    fun available(context: Context): Boolean {
        val settings = App.provide(context).settings
        if (settings.batteryDemoMode) return true
        return carHardwarePresent()
    }

    /** True when car telemetry may be read — independent of demo mode / firmware gate. */
    fun carHardwarePresent(): Boolean = true

    @Synchronized
    fun start(context: Context) {
        app = context.applicationContext
        if (started) {
            executor.execute(::poll)
            return
        }
        started = true
        val settings = App.provide(context.applicationContext).settings
        // Demo: publish immediately on the caller thread so Service Discovery can see a snapshot.
        if (settings.batteryDemoMode) {
            initialized = true
            publishFromPercent(
                settings = settings,
                percent = settings.batteryDemoPercent.toDouble().coerceIn(1.0, 100.0),
                rangeKm = settings.batteryDemoRangeKm.coerceAtLeast(1),
                demo = true,
            )
        }
        executor.execute {
            if (!settings.batteryDemoMode) {
                try {
                    EVHardware.init(context.applicationContext)
                    initialized = true
                    AppLog.i(
                        "$TAG energy start firmware=${FirmwareInfo.getDetectedString()} " +
                            "gen=${FirmwareInfo.getGeneration()} " +
                            "cpm=${EVHardware.isCarPropertyManagerReady()}",
                    )
                    EVHardware.whenCarPropertyManagerReady {
                        AppLog.i("$TAG CarPropertyManager ready — refreshing SoC/range")
                        refresh()
                    }
                    EVHardware.whenSaicHubReady {
                        AppLog.i("$TAG SaicHub ready — refreshing SoC/range")
                        refresh()
                    }
                    primeFirstReading()
                } catch (error: Throwable) {
                    AppLog.e("$TAG EVHardware init failed; EV energy disabled: ${error.message}")
                }
            } else {
                poll()
            }
            if (pollFuture == null) {
                pollFuture = executor.scheduleWithFixedDelay(
                    ::poll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS,
                )
            }
            if (meterFuture == null && !settings.batteryDemoMode) {
                meterFuture = executor.scheduleWithFixedDelay(
                    ::readMeter, METER_MS, METER_MS, TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    fun refresh() {
        if (!started) return
        executor.execute(::poll)
    }

    @Synchronized
    fun stop() {
        pollFuture?.cancel(false)
        pollFuture = null
        meterFuture?.cancel(false)
        meterFuture = null
    }

    fun snapshot(): EnergySnapshot? =
        latest?.takeIf { SystemClock.elapsedRealtime() - latestMillis <= STALE_MS }

    /**
     * One-shot car SoC/range for the Charging info screen, ignoring demo mode.
     * Capacity Wh uses [netCapacityKwh] × [sohPercent] (pending UI values OK).
     */
    fun readCarSnapshot(
        context: Context,
        netCapacityKwh: Float,
        sohPercent: Float,
    ): EnergySnapshot? {
        return runCatching {
            if (!initialized) {
                EVHardware.init(context.applicationContext)
                initialized = true
            }
            val percent = EVHardware.getBatterySocPercent()?.toDouble() ?: return null
            val rangeKm = EVHardware.getRemainingRangeKm()
                ?: latest?.rangeKm?.takeIf { it > 0 }
                ?: return null
            if (rangeKm <= 0) return null
            val capacityKwh = (netCapacityKwh * sohPercent / 100f).toDouble().coerceIn(1.0, 200.0)
            val fraction = (percent / 100.0).coerceIn(0.01, 1.0)
            EnergySnapshot(
                capacityWh = (capacityKwh * 1000).roundToInt().coerceAtLeast(1),
                currentWh = (capacityKwh * fraction * 1000).roundToInt().coerceAtLeast(1),
                rangeMeters = (rangeKm * 1000).coerceAtLeast(1),
                batteryPercent = percent,
                rangeKm = rangeKm,
                demo = false,
            )
        }.getOrNull()
    }

    private fun poll() {
        try {
            pollSafely()
        } catch (error: Throwable) {
            AppLog.e("$TAG telemetry read failed: ${error.message}")
        }
    }

    /** DiAuto's own capacity measurement, every second as MG4 Dash's, unless MG4 Dash has one to read. */
    private fun readMeter() {
        val ctx = app ?: return
        if (!initialized) return
        runCatching {
            val now = SystemClock.elapsedRealtime()
            if (dashCheckedAt == 0L || now - dashCheckedAt >= DASH_CHECK_MS) {
                dashCheckedAt = now
                dashMeasures = Mg4DashBattery.read(ctx)?.measuredKwh != null
            }
            if (dashMeasures) return
            val percent = EVHardware.getBatterySocPercent() ?: return
            BatteryCapacityMeter.reading(
                ctx, EVHardware.getPackPowerW(), percent,
                charging = EVHardware.isChargePortConnected() == true,
                nowMs = now,
            )
        }
    }

    private fun primeFirstReading() {
        val deadline = SystemClock.elapsedRealtime() + INITIAL_READ_TIMEOUT_MS
        do {
            pollSafely()
            if (latest != null) return
            try {
                Thread.sleep(INITIAL_RETRY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        } while (SystemClock.elapsedRealtime() < deadline)
        AppLog.i(
            "$TAG first SoC/range still pending after ${INITIAL_READ_TIMEOUT_MS}ms; " +
                "cpm=${EVHardware.isCarPropertyManagerReady()}; periodic poll continues",
        )
    }

    private fun pollSafely() {
        val ctx = app ?: return
        val settings = App.provide(ctx).settings
        if (!settings.batteryForGoogleMaps) return

        if (settings.batteryDemoMode) {
            publishFromPercent(
                settings = settings,
                percent = settings.batteryDemoPercent.toDouble().coerceIn(1.0, 100.0),
                rangeKm = settings.batteryDemoRangeKm.coerceAtLeast(1),
                demo = true,
            )
            return
        }

        if (!initialized) return

        val percent = EVHardware.getBatterySocPercent()?.toDouble() ?: return
        // Prefer live range; keep last good range so a late range does not block SoC.
        val rangeKm = EVHardware.getRemainingRangeKm()?.takeIf { it > 0 }
            ?: latest?.rangeKm?.takeIf { it > 0 }
            ?: return

        publishFromPercent(settings, percent, rangeKm, demo = false)
    }

    /**
     * Capacity Wh is net × SOH from [BatteryCapacityResolver] (car, MG4 Dash, default or the
     * driver's values). Live Wh from the car is ignored so Maps SoC stays consistent with it.
     */
    private fun publishFromPercent(
        settings: Settings,
        percent: Double,
        rangeKm: Int,
        demo: Boolean,
    ) {
        val capacityKwh = (app?.let { BatteryCapacityResolver.current(it).effectiveKwh }
            ?: settings.batteryEffectiveNetCapacityKwh).toDouble().coerceIn(1.0, 200.0)
        val fraction = (percent / 100.0).coerceIn(0.01, 1.0)
        publish(
            capacityWh = (capacityKwh * 1000).roundToInt().coerceAtLeast(1),
            currentWh = (capacityKwh * fraction * 1000).roundToInt().coerceAtLeast(1),
            rangeKm = rangeKm,
            batteryPercent = percent,
            demo = demo,
        )
    }

    private fun publish(
        capacityWh: Int,
        currentWh: Int,
        rangeKm: Int,
        batteryPercent: Double,
        demo: Boolean,
    ) {
        latest = EnergySnapshot(
            capacityWh = capacityWh,
            currentWh = currentWh,
            rangeMeters = (rangeKm * 1000).coerceAtLeast(1),
            batteryPercent = batteryPercent,
            rangeKm = rangeKm,
            demo = demo,
        )
        latestMillis = SystemClock.elapsedRealtime()
        val mode = if (demo) "demo" else "car"
        AppLog.i(
            "$TAG battery ${batteryPercent.roundToInt()}% range ${rangeKm}km " +
                "netCap=${capacityWh}Wh ($mode)",
        )
    }
}
