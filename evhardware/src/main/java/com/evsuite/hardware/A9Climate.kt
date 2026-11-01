package com.evsuite.hardware

import android.os.IBinder

/**
 * Climate on the A9 platform (SWI69, SWI131, SWI132), where the vehicle-settings SDK is absent.
 *
 * `SaicClimate` talks to `IAirConditionService` through the vendor hub. That hub does not
 * exist on A9 — which is why every climate action shipped marked SWI68/SWI165 only, on cars
 * whose own HVAC screen works perfectly well. It works through `carapi` instead:
 * `queryClient(0x7)` hands back the HVAC binder and `CarHvacClient` wraps it, exactly as
 * `queryClient(0x8)` already builds the vehicle-settings client in [EVHardware].
 *
 * Reflection rather than raw AIDL here, unlike the rest of `saic`: the transaction codes of
 * `ICarHvacService` are not established anywhere, while the client class' method names are —
 * they are what the launcher calls. Guessing a code writes to the wrong signal; naming a
 * method that does not exist fails loudly and reads as unsupported.
 *
 * ## Switches, not assignments
 *
 * Several A9 settings are `switch…()` with no argument: they step to the next state. Aiming at
 * one is therefore read-step-read until it matches, the same shape as the seat heating, and
 * bounded so a firmware where the call does nothing gives up instead of looping. That makes
 * every such write **blocking** — up to a second or so — so none of this belongs on the main
 * thread.
 */
internal object A9Climate {

    private const val TAG = "EV_CLIMATE_A9"

    private const val SERVICE_CODE = 0x7
    private const val CLIENT_CLASS = "com.saicmotor.carapi.client.CarHvacClient"

    /** Time given to the HVAC controller to apply one step before the state is read back. */
    private const val STEP_SETTLE_MS = 400L

    @Volatile
    private var client: Any? = null

    /** True on the generations whose climate can only be reached this way. */
    val isPlatform: Boolean
        get() = when (FirmwareInfo.getGeneration()) {
            FirmwareInfo.Gen.SWI69, FirmwareInfo.Gen.SWI131, FirmwareInfo.Gen.SWI132 -> true
            else -> false
        }

    val isAvailable: Boolean get() = isPlatform && client() != null

    private fun client(): Any? {
        client?.let { return it }
        val loader = EVHardware.a9ClassLoader() ?: return null
        val binder: IBinder = EVHardware.a9ClientBinder(SERVICE_CODE) ?: return null
        return try {
            loader.loadClass(CLIENT_CLASS)
                .getConstructor(IBinder::class.java)
                .newInstance(binder)
                .also {
                    client = it
                    AppLogger.i(TAG, "CarHvacClient obtained via queryClient(0x7) ✓")
                }
        } catch (e: Exception) {
            AppLogger.d(TAG, "CarHvacClient unavailable: ${(e.cause ?: e).message}")
            null
        }
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    fun powerOn(): Boolean? = get("getHvacPowerStatus") as? Boolean
    fun acOn(): Boolean? = get("getACStatus") as? Boolean
    fun autoOn(): Boolean? = get("getAutoStatus") as? Boolean
    fun frontDefrostOn(): Boolean? = get("getFrontDefrostStatus") as? Boolean
    fun rearDefrostOn(): Boolean? = get("getRearDefrostStatus") as? Boolean
    fun fanLevel(): Int? = (get("getFanSpeed") as? Int)?.takeIf { it >= 0 }

    /** 0 outside air, 1 recirculated, 2 auto — the same encoding the property carries. */
    fun loopMode(): Int? = (get("getAirCirculationStatus") as? Int)?.takeIf { it >= 0 }

    fun recirculationOn(): Boolean? = loopMode()?.let { it == LOOP_INNER }

    /** A float on this platform, where the old SDK answered whole degrees. */
    fun driverTemp(): Int? =
        (get("getDriverTemperature") as? Float)?.takeIf { !it.isNaN() && it > 0f }?.toInt()

    /** Same read as the car's own launcher (getOutSideTemperature, e.g. 13.5). */
    fun outsideTemp(): Float? =
        (get("getOutSideTemperature") as? Float)?.takeIf { !it.isNaN() && it > -60f && it < 70f }

    // ── Writes ───────────────────────────────────────────────────────────────

    fun setPower(on: Boolean): Boolean =
        cycleTo("power", "getHvacPowerStatus", if (on) 1 else 0, maxSteps = 2, advance = "switchHvacPowerStatus")

    fun setAc(on: Boolean): Boolean =
        cycleTo("ac", "getACStatus", if (on) 1 else 0, maxSteps = 2, advance = "switchACStatus")

    fun setAuto(on: Boolean): Boolean =
        cycleTo("auto", "getAutoStatus", if (on) 1 else 0, maxSteps = 2, advance = "switchAutoStatus")

    fun setFrontDefrost(on: Boolean): Boolean =
        cycleTo("frontDefrost", "getFrontDefrostStatus", if (on) 1 else 0, 2, "switchFrontDefrostStatus")

    fun setRearDefrost(on: Boolean): Boolean =
        cycleTo("rearDefrost", "getRearDefrostStatus", if (on) 1 else 0, 2, "switchRearDefrostStatus")

    /** Three modes, so two steps reach any of them from any other. */
    fun setLoopMode(target: Int): Boolean {
        if (target !in LOOP_OUTSIDE..LOOP_AUTO) return false
        return cycleTo("loopMode", "getAirCirculationStatus", target, 3, "switchAirCirculationStatus")
    }

    fun setRecirculation(on: Boolean): Boolean = setLoopMode(if (on) LOOP_INNER else LOOP_OUTSIDE)

    fun setFanLevel(level: Int): Boolean = set("setFanSpeed", level)

    fun setDriverTemp(celsius: Int): Boolean = set("setDriverTemperature", celsius.toFloat())

    const val LOOP_OUTSIDE = 0
    const val LOOP_INNER = 1
    const val LOOP_AUTO = 2

    // ── Reflection plumbing ──────────────────────────────────────────────────

    private fun get(method: String): Any? {
        val c = client() ?: return null
        return try {
            c.javaClass.getMethod(method).invoke(c)
        } catch (e: Exception) {
            AppLogger.d(TAG, "$method(): ${(e.cause ?: e).message}")
            null
        }
    }

    private fun call(method: String): Boolean {
        val c = client() ?: return false
        return try {
            c.javaClass.getMethod(method).invoke(c)
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "$method(): ${(e.cause ?: e).message}")
            false
        }
    }

    private fun set(method: String, value: Any): Boolean {
        val c = client() ?: return false
        val type = if (value is Float) Float::class.javaPrimitiveType else Int::class.javaPrimitiveType
        return try {
            c.javaClass.getMethod(method, type).invoke(c, value)
            AppLogger.i(TAG, "$method($value) ✓")
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "$method($value): ${(e.cause ?: e).message}")
            false
        }
    }

    /**
     * Steps [advance] until [getter] reads [target].
     *
     * Bounded, because a firmware where the switch does nothing would otherwise spin forever;
     * and an unreadable state stops it at once rather than stepping blind, which on a
     * three-way switch would leave the setting somewhere nobody asked for.
     */
    private fun cycleTo(
        label: String,
        getter: String,
        target: Int,
        maxSteps: Int,
        advance: String,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): Boolean {
        var steps = 0
        while (steps <= maxSteps) {
            val current = when (val v = get(getter)) {
                is Boolean -> if (v) 1 else 0
                is Int -> v
                else -> -1
            }
            if (current == target) {
                AppLogger.i(TAG, "$label = $target reached in $steps step(s)")
                return true
            }
            if (current < 0) {
                AppLogger.w(TAG, "$label: state unreadable — not stepping blind")
                return false
            }
            if (!call(advance)) return false
            steps++
            sleep(STEP_SETTLE_MS)
        }
        AppLogger.w(TAG, "$label = $target not reached in $maxSteps step(s)")
        return false
    }
}
