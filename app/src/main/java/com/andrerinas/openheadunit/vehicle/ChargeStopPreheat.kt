package com.andrerinas.openheadunit.vehicle

import android.content.Context
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog
import com.evsuite.hardware.EVHardware
import java.util.concurrent.Executors

/**
 * Battery heating on the way to a charger: switched on a set time before the next stop when that
 * stop is a charger, switched back off when the trip changes. Only heating DiAuto switched on is
 * ever switched off by it.
 */
object ChargeStopPreheatPolicy {

    /** A next stop closer than this when the route ends counts as reached, not cancelled. */
    const val ARRIVED_SECONDS = 180

    /**
     * The phone sends a stop's name or address, not its kind, so chargers are told by name: the
     * networks and the words a charging stop carries.
     */
    private val CHARGER_WORDS = listOf(
        "ionity", "supercharger", "tesla", "fastned", "electra", "allego", "powerdot", "atlante",
        "zunder", "izivia", "freshmile", "chargepoint", "charge point", "charging", "charger",
        "recharge", "borne", "ladestation", "ladepark", "ladesäule", "laadstation", "laadpaal",
        "snellader", "enbw", "ewe go", "bp pulse", "aral pulse", "shell recharge", "evgo",
        "electrify", "gridserve", "instavolt", "osprey", "plenitude", "be charge", "endesa x",
        "iberdrola", "wenea", "tesla destination",
    )

    /**
     * Battery heating is DRVNG_PTC_HEAT 0x2140f429 in zone 0 on every MT2712 firmware, written 1 / 2
     * and read 0 = on, as the cars' own apps do (decompiled SWI69, SWI132, SWI68, SWI133; measured
     * on an SWI69). Off on a firmware DiAuto does not recognise, where the property may differ.
     */
    fun supportedOnThisCar(): Boolean =
        com.evsuite.hardware.FirmwareInfo.getGeneration() != com.evsuite.hardware.FirmwareInfo.Gen.UNKNOWN

    fun isCharger(stop: String?): Boolean {
        if (stop.isNullOrBlank()) return false
        val s = stop.lowercase()
        return CHARGER_WORDS.any { s.contains(it) }
    }

    enum class Action { NONE, TURN_ON, TURN_OFF, RELEASE }

    /**
     * @param navigating a route is being followed
     * @param secondsToStop time to the next stop, or null if the phone has not said
     * @param heatingOn the car's switch, or null if unreadable
     * @param ownedByUs DiAuto switched the heating on for the current trip
     * @param lastSecondsToStop the last time to the next stop seen while navigating
     */
    fun decide(
        enabled: Boolean,
        navigating: Boolean,
        nextStopIsCharger: Boolean,
        secondsToStop: Int?,
        leadSeconds: Int,
        heatingOn: Boolean?,
        ownedByUs: Boolean,
        lastSecondsToStop: Int?,
    ): Action {
        if (ownedByUs) {
            // Switched off by hand since: the driver decides from here.
            if (heatingOn == false) return Action.RELEASE
            if (!enabled) return Action.TURN_OFF
            if (!navigating) {
                val arrived = lastSecondsToStop != null && lastSecondsToStop <= ARRIVED_SECONDS
                return if (arrived) Action.RELEASE else Action.TURN_OFF
            }
            return if (nextStopIsCharger) Action.NONE else Action.TURN_OFF
        }
        if (!enabled || !navigating || !nextStopIsCharger || heatingOn != false) return Action.NONE
        return if (secondsToStop != null && secondsToStop in 0..leadSeconds) Action.TURN_ON else Action.NONE
    }
}

object ChargeStopPreheater {
    private const val TAG = "Preheat"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diauto-preheat").apply { isDaemon = true }
    }

    @Volatile private var ownedByUs = false
    @Volatile private var lastSecondsToStop: Int? = null
    @Volatile private var lastAction: ChargeStopPreheatPolicy.Action? = null
    @Volatile private var turnedOnAtMs = 0L
    @Volatile private var offSinceMs = 0L
    /** How long the car may take to report the switch on. */
    private const val CONFIRM_MS = 30_000L
    /** How long the trip must stay changed before heating DiAuto switched on goes off. */
    private const val OFF_AFTER_MS = 60_000L
    @Volatile private var lastLoggedStop: String? = null

    /** Called on every navigation update; [nextStop] is the next stop's name or address. */
    /**
     * @param stopsAhead stops the phone gives a time for, the destination included. Maps names a
     *   charging stop it added by its place ("Aire de …"), not its network, so a stop before the
     *   destination on a route counts as a charging stop too.
     */
    fun update(context: Context, navigating: Boolean, nextStop: String?, secondsToStop: Int?, stopsAhead: Int) {
        val settings = App.provide(context).settings
        if (!settings.batteryPreheatBeforeCharger && !ownedByUs) return
        if (!ChargeStopPreheatPolicy.supportedOnThisCar()) return
        if (navigating && secondsToStop != null) lastSecondsToStop = secondsToStop
        val charger = ChargeStopPreheatPolicy.isCharger(nextStop) || stopsAhead >= 2
        // Only with the option on, once per stop: how chargers are named is still being learned.
        val summary = "\"${nextStop?.take(40) ?: "-"}\" of $stopsAhead, ${secondsToStop?.div(60) ?: "?"} min, charger=$charger"
        if (navigating && summary != lastLoggedStop) {
            lastLoggedStop = summary
            AppLog.i("$TAG: next stop $summary")
        }
        val lead = settings.batteryPreheatMinutes * 60
        executor.execute {
            val now = android.os.SystemClock.elapsedRealtime()
            val read = runCatching { EVHardware.isDrivingBatteryHeatOn() }.getOrNull()
            // The car reports the switch a few seconds after the write; until then "off" is stale.
            val heating = if (ownedByUs && read == false && now - turnedOnAtMs < CONFIRM_MS) true else read
            val action = ChargeStopPreheatPolicy.decide(
                enabled = settings.batteryPreheatBeforeCharger,
                navigating = navigating,
                nextStopIsCharger = charger,
                secondsToStop = secondsToStop,
                leadSeconds = lead,
                heatingOn = heating,
                ownedByUs = ownedByUs,
                lastSecondsToStop = lastSecondsToStop,
            )
            if (action != lastAction && action != ChargeStopPreheatPolicy.Action.NONE) {
                AppLog.i("$TAG: $action (next stop charger=$charger, ${secondsToStop?.div(60) ?: "-"} min away, " +
                    "lead ${lead / 60} min, heating=${heating ?: "?"})")
            }
            lastAction = action
            // A route's state blinks out for a moment now and then: switch off only once the trip
            // has really changed for a while.
            if (action != ChargeStopPreheatPolicy.Action.TURN_OFF) offSinceMs = 0L
            when (action) {
                ChargeStopPreheatPolicy.Action.TURN_ON ->
                    if (EVHardware.setDrivingBatteryHeat(true)) {
                        ownedByUs = true
                        turnedOnAtMs = now
                    }
                ChargeStopPreheatPolicy.Action.TURN_OFF -> {
                    if (offSinceMs == 0L) {
                        offSinceMs = now
                        // No more navigation events may come once a route ends: look again later.
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            update(context, navigating, nextStop, secondsToStop, stopsAhead)
                        }, OFF_AFTER_MS + 1_000L)
                    }
                    if (now - offSinceMs >= OFF_AFTER_MS) {
                        AppLog.i("$TAG: trip changed for ${OFF_AFTER_MS / 1000} s, battery heating off")
                        EVHardware.setDrivingBatteryHeat(false)
                        release()
                        offSinceMs = 0L
                    }
                }
                ChargeStopPreheatPolicy.Action.RELEASE -> release()
                ChargeStopPreheatPolicy.Action.NONE -> {}
            }
            if (!navigating && !ownedByUs) lastSecondsToStop = null
        }
    }

    private fun release() {
        ownedByUs = false
        lastSecondsToStop = null
    }
}
