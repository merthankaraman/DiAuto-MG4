package com.andrerinas.openheadunit.vehicle

import android.os.SystemClock
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapTransport
import com.andrerinas.openheadunit.aap.protocol.messages.RawVehicleEnergyModelEvent
import com.andrerinas.openheadunit.aap.protocol.messages.VehicleEnergyModelEvent
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Sends VehicleEnergyModel on sensor 23 and/or 25 while the phone has requested them.
 * Some AA/Maps builds start 25 instead of 23 — both must be served.
 */
object Mg4EnergyReporter {
    private const val TAG = "DiAuto-MG4"
    private const val INTERVAL_MS = 30_000L

    /** Outside temperature last put in DiAuto's model (see [Mg4MapsEnergyModel.heldTemp]). */
    @Volatile private var heldTemp: Float? = null
    @Volatile private var lastModel: Mg4MapsEnergyModel.Model? = null

    private val requestedTypes = ConcurrentHashMap.newKeySet<Int>()
    private val vemEverSent = AtomicBoolean(false)
    private val transportRef = AtomicReference<AapTransport?>(null)
    private var tickFuture: ScheduledFuture<*>? = null
    private var lastSentElapsed = 0L
    private var lastSkipLogMs = 0L
    private const val SKIP_LOG_EVERY_MS = 30_000L
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "diauto-mg4-vem").apply { isDaemon = true }
    }

    @Synchronized
    fun bind(transport: AapTransport) {
        transportRef.set(transport)
        MapsModelTrip.reset()
        // App.onCreate starts the provider only if the profile was already unlocked; a process born
        // during the locked boot never does, and no reading ever reached the phone. A connection
        // means the profile is unlocked, and start() is harmless when it is already running.
        App.instance?.let { app ->
            if (App.provide(app).settings.batteryForGoogleMaps) Mg4EnergyProvider.start(app)
        }
        if (tickFuture == null) {
            tickFuture = executor.scheduleWithFixedDelay(::tick, 1_000L, 1_000L, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun unbind(transport: AapTransport?) {
        // The cleanup of a session that ended can run after the next one has bound (it runs on
        // its own coroutine, and a second re-entrant pass sees no transport at all). Clearing then
        // dropped the sensors the new session's phone had just asked for, and Maps never got the
        // battery for the whole trip. Only the transport that is bound may unbind.
        if (transport == null || transportRef.get() !== transport) return
        transportRef.set(null)
        requestedTypes.clear()
        vemEverSent.set(false)
        tickFuture?.cancel(false)
        tickFuture = null
    }

    fun onSensorStart(type: Int) {
        if (type == 23 || type == 25) {
            requestedTypes.add(type)
            AppLog.i("$TAG phone requested VEM sensor type=$type (active=$requestedTypes)")
            sendNow(force = true)
        }
    }

    /** For the debug overlay: the last model sent against the battery's real power. */
    private fun sampleDebugTrip() {
        val settings = App.instance?.let { App.provide(it).settings } ?: return
        if (!settings.mapsModelOverlay) return
        val model = lastModel ?: return
        val snap = Mg4EnergyProvider.snapshot() ?: return
        runCatching {
            MapsModelTrip.sample(SystemClock.elapsedRealtime(), model,
                com.evsuite.hardware.EVHardware.getVehicleSpeedKmh(), com.evsuite.hardware.EVHardware.getCapacityReading().powerW,
                snap.batteryPercent, snap.currentWh, snap.capacityWh)
        }
    }

    /** Sends the model again now, e.g. after test values changed. */
    fun resend() = executor.execute { sendNow(force = true) }

    private fun tick() {
        sampleDebugTrip()
        if (requestedTypes.isEmpty()) return
        val due = SystemClock.elapsedRealtime() - lastSentElapsed >= INTERVAL_MS
        if (due || !vemEverSent.get()) sendNow(force = !vemEverSent.get())
    }

    private fun sendNow(force: Boolean) {
        if (requestedTypes.isEmpty() && !force) return
        val transport = transportRef.get() ?: return
        val snap = Mg4EnergyProvider.snapshot() ?: run {
            // Every second would fill the log; once in a while still shows it is waiting.
            val now = SystemClock.elapsedRealtime()
            if (now - lastSkipLogMs >= SKIP_LOG_EVERY_MS) {
                lastSkipLogMs = now
                AppLog.i("$TAG VEM skip: no battery snapshot yet")
            }
            // Kick a poll so the next 1s tick can send without waiting for the 5s cadence.
            Mg4EnergyProvider.refresh()
            return
        }
        val variant = App.instance?.let { Mg4Variant.resolve(App.provide(it).settings.mg4Variant, snap.capacityWh) }
            ?: Mg4Variant.detect(snap.capacityWh)
        val layout = App.instance?.let { VehicleEnergyModelEncoder.Layout.of(App.provide(it).settings.evModelLayout) }
            ?: VehicleEnergyModelEncoder.Layout.LEGACY
        val outsideTemp = if (snap.demo || layout == VehicleEnergyModelEncoder.Layout.LEGACY) null else runCatching {
            com.evsuite.hardware.saic.SaicClimate.outsideTempCelsius() ?: com.evsuite.hardware.EVHardware.getOutsideTempCelsius()
        }.getOrNull()
        val model = App.instance?.takeIf { !snap.demo && App.provide(it).settings.mapsEnergyModel }?.let {
            val live = (outsideTemp ?: runCatching {
                com.evsuite.hardware.saic.SaicClimate.outsideTempCelsius() ?: com.evsuite.hardware.EVHardware.getOutsideTempCelsius()
            }.getOrNull())?.takeIf { it in -40f..60f }
            heldTemp = Mg4MapsEnergyModel.heldTemp(heldTemp, live)
            val climate = runCatching { com.evsuite.hardware.saic.SaicClimate.powerOn() }.getOrNull()
            Mg4MapsEnergyModel.build(variant, heldTemp, climate)
        }
        lastModel = model
        val encoded = VehicleEnergyModelEncoder.Snapshot(
            capacityWh = snap.capacityWh,
            currentWh = snap.currentWh,
            rangeMeters = snap.rangeMeters,
            batteryPercent = snap.batteryPercent,
            variant = variant,
            outsideTempC = outsideTemp?.takeIf { it in -40f..60f },
            layout = layout,
            energyModel = model,
        )

        // Prefer whichever the phone started; if none yet but force, try both.
        val types = if (requestedTypes.isNotEmpty()) {
            requestedTypes.toSet()
        } else {
            setOf(23, 25)
        }

        var sentAny = false
        if (23 in types) {
            sentAny = transport.send(VehicleEnergyModelEvent(encoded)) || sentAny
        }
        if (25 in types) {
            sentAny = transport.send(RawVehicleEnergyModelEvent(encoded)) || sentAny
        }

        if (sentAny) {
            lastSentElapsed = SystemClock.elapsedRealtime()
            vemEverSent.set(true)
            AppLog.i(
                "$TAG vem tx capacity=${snap.capacityWh}Wh current=${snap.currentWh}Wh " +
                    "range=${snap.rangeKm}km battery=${"%.1f".format(snap.batteryPercent)}% " +
                    "variant=${variant.key} maxDc=${variant.maxDcW / 1000}kW layout=${layout.key} outside=${outsideTemp ?: "-"} types=$types" +
                    (model?.let { m ->
                        " model A=%.0fN C=%.3f temp=%s climate=%s aux=%s".format(m.a, m.c, m.tempC ?: "-", m.climateOn ?: "-",
                            m.aux.joinToString(",") { "${it.first.toInt()}:${it.second}" }) +
                            (if (Mg4MapsEnergyModel.test.active) " TEST ${Mg4MapsEnergyModel.test}" else "")
                    } ?: ""),
            )
        } else {
            AppLog.i("$TAG vem tx dropped (sensors $types not started or transport not alive)")
        }
    }
}
