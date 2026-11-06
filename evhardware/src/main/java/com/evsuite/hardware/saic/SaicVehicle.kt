package com.evsuite.hardware.saic

import android.content.Context
import android.os.IBinder
import com.evsuite.hardware.A9Climate
import com.evsuite.hardware.GlassProbe
import com.evsuite.hardware.VsmGlass

/**
 * The vendor vehicle service the head unit's HVAC and charging screens talk to.
 *
 * One bound service exposes a *hub*: `getService(name)` hands back the binder for a named
 * sub-service. Both consumers below go through it, so the bind happens once.
 *
 * Binding contract: `com.saicmotor.sdk.vehiclesettings.manager.BaseManager`
 * (bind target), `IHubService` (the lookup), `AirConditionManager` and
 * `VehicleChargingManager` (the two names used here).
 */
object SaicHub {

    private const val PACKAGE = "com.saicmotor.service.vehicle"
    private const val ACTION = "com.saicmotor.service.vehicle.VehicleService"
    private const val DESCRIPTOR = "com.saicmotor.sdk.vehiclesettings.IHubService"
    private const val TX_GET_SERVICE = 1

    private val service = SaicService(PACKAGE, ACTION, "vehicle-hub")
    private val resolved = HashMap<String, IBinder>()

    val isReady: Boolean get() = service.isReady

    fun connect(context: Context) = service.connect(context)

    /** Runs [action] when the vehicle hub binder is available (immediately if already bound). */
    fun whenReady(action: () -> Unit) = service.whenReady(action)

    /** Sub-service binder, resolved once and re-resolved after the hub reconnects. */
    @Synchronized
    fun service(name: String): IBinder? {
        val hub = service.binder() ?: run { resolved.clear(); return null }
        resolved[name]?.takeIf { it.isBinderAlive }?.let { return it }
        val sub = SaicAidl.callBinder(hub, DESCRIPTOR, TX_GET_SERVICE, name) ?: return null
        resolved[name] = sub
        return sub
    }
}

/**
 * Climate control, as the stock HVAC app performs it.
 *
 * This is the write path the project deliberately did without until now: EVHardware could
 * only *read* climate through standard AOSP property ids that no MG4 confirmed, so there
 * was no honest way to change anything. These calls are the ones
 * the car's own HVAC screen makes, so what they do is not in doubt — only whether the
 * service answers on a given firmware, which the Diagnostic tab now reports.
 *
 * Temperatures are whole degrees Celsius. The stock UI clamps to 17–33, where the ends read
 * as LO and HI (`HvacActivity.onLongTouch`), so the same bounds apply here.
 */
object SaicClimate {

    private const val NAME = "aircondition"
    private const val DESCRIPTOR = "com.saicmotor.sdk.vehiclesettings.IAirConditionService"

    const val TEMP_MIN = 17
    const val TEMP_MAX = 33

    // Transaction codes used by the supported runtime IAirConditionService contract.
    private const val TX_SET_POWER = 4
    private const val TX_OPEN_POWER = 5
    private const val TX_CLOSE_POWER = 6
    private const val TX_SET_AUTO = 7
    private const val TX_SET_AC = 8
    private const val TX_SET_LOOP_MODE = 9
    private const val TX_SET_ECON = 13
    private const val TX_SET_AIR_VOLUME = 15
    private const val TX_SET_DRV_TEMP = 41
    private const val TX_SET_PSG_TEMP = 42
    private const val TX_OPEN_FRONT_DEFROST = 48
    private const val TX_CLOSE_FRONT_DEFROST = 49
    private const val TX_OPEN_BACK_DEFROST = 50
    private const val TX_CLOSE_BACK_DEFROST = 51
    private const val TX_GET_POWER = 53
    private const val TX_GET_AUTO = 54
    private const val TX_GET_AC = 55
    private const val TX_GET_LOOP_MODE = 56
    private const val TX_GET_ECON = 57
    private const val TX_GET_AIR_VOLUME = 59
    private const val TX_GET_MAX_AIR_VOLUME = 60
    private const val TX_GET_DRV_TEMP = 81
    private const val TX_GET_PSG_TEMP = 82
    private const val TX_GET_OUT_CAR_TEMP = 88
    private const val TX_GET_FRONT_DEFROST = 92
    private const val TX_GET_BACK_DEFROST = 93

    /** `AirConditionBinder.TEMPERATURE_ERROR_CONST`: the signal is not available. */
    private const val TEMP_ERROR = -10000f

    /** Loop mode: 0 = fresh air, 1 = recirculation (`openLoopInner` / `openLoopOutside`). */
    const val LOOP_OUTSIDE = 0
    const val LOOP_INNER = 1

    /**
     * Available when either path answers.
     *
     * Two paths, because the vendor hub this object talks to does not exist on the A9
     * generations, whose climate is reached through `carapi` instead — see [A9Climate]. The
     * split is made here, once, so nothing above has to know which car it is running on.
     */
    val isAvailable: Boolean get() = if (A9Climate.isPlatform) A9Climate.isAvailable else binder() != null

    private fun binder(): IBinder? = SaicHub.service(NAME)

    private val a9: Boolean get() = A9Climate.isPlatform

    // ── Writes ───────────────────────────────────────────────────────────────
    fun setPower(on: Boolean): Boolean =
        if (a9) A9Climate.setPower(on)
        else SaicAidl.callVoid(binder(), DESCRIPTOR, if (on) TX_OPEN_POWER else TX_CLOSE_POWER)

    fun setAc(on: Boolean): Boolean =
        if (a9) A9Climate.setAc(on)
        else SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_AC, if (on) 1 else 0)

    fun setAuto(on: Boolean): Boolean =
        if (a9) A9Climate.setAuto(on)
        else SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_AUTO, if (on) 1 else 0)

    /**
     * ECON has no counterpart on A9: the HVAC client exposes no such switch, and there is
     * nothing to substitute — reporting the write as refused is the truth.
     */
    fun setEcon(on: Boolean): Boolean =
        if (a9) false
        else SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_ECON, if (on) 1 else 0)

    fun setRecirculation(on: Boolean): Boolean =
        if (a9) A9Climate.setRecirculation(on)
        else SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_LOOP_MODE, if (on) LOOP_INNER else LOOP_OUTSIDE)

    fun setFanLevel(level: Int): Boolean =
        if (a9) A9Climate.setFanLevel(level)
        else SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_AIR_VOLUME, level)

    /** Driver-side target. The passenger side follows unless dual zone is on. */
    fun setDriverTemp(celsius: Int): Boolean =
        if (a9) A9Climate.setDriverTemp(celsius.coerceIn(TEMP_MIN, TEMP_MAX))
        else SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_DRV_TEMP, celsius.coerceIn(TEMP_MIN, TEMP_MAX))

    /**
     * The passenger's own target, which the A9 client does not expose separately — its
     * `setDriverTemperature` is the whole cabin unless dual zone is on, and there is no
     * honest way to set one side alone.
     */
    fun setPassengerTemp(celsius: Int): Boolean =
        if (a9) false
        else SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_PSG_TEMP, celsius.coerceIn(TEMP_MIN, TEMP_MAX))

    fun setFrontDefrost(on: Boolean): Boolean =
        if (a9) A9Climate.setFrontDefrost(on)
        else SaicAidl.callVoid(binder(), DESCRIPTOR, if (on) TX_OPEN_FRONT_DEFROST else TX_CLOSE_FRONT_DEFROST)

    fun setRearDefrost(on: Boolean): Boolean =
        if (a9) A9Climate.setRearDefrost(on)
        else SaicAidl.callVoid(binder(), DESCRIPTOR, if (on) TX_OPEN_BACK_DEFROST else TX_CLOSE_BACK_DEFROST)

    // ── Reads (null = the service did not answer) ────────────────────────────
    // The service answers -1 for a signal it holds no value for, which is not a state.
    fun powerOn(): Boolean? = if (a9) A9Climate.powerOn() else flag(TX_GET_POWER)
    fun acOn(): Boolean? = if (a9) A9Climate.acOn() else flag(TX_GET_AC)
    fun autoOn(): Boolean? = if (a9) A9Climate.autoOn() else flag(TX_GET_AUTO)
    fun econOn(): Boolean? = if (a9) null else flag(TX_GET_ECON)
    fun recirculationOn(): Boolean? =
        if (a9) A9Climate.recirculationOn() else level(TX_GET_LOOP_MODE)?.let { it == LOOP_INNER }
    fun fanLevel(): Int? = if (a9) A9Climate.fanLevel() else level(TX_GET_AIR_VOLUME)
    /** A9 states no maximum of its own; the catalogue's is the measured one. */
    fun fanLevelMax(): Int? = if (a9) null else level(TX_GET_MAX_AIR_VOLUME)
    fun driverTemp(): Int? = if (a9) A9Climate.driverTemp() else level(TX_GET_DRV_TEMP)
    fun passengerTemp(): Int? = if (a9) null else level(TX_GET_PSG_TEMP)
    fun frontDefrostOn(): Boolean? = if (a9) A9Climate.frontDefrostOn() else flag(TX_GET_FRONT_DEFROST)
    fun rearDefrostOn(): Boolean? = if (a9) A9Climate.rearDefrostOn() else flag(TX_GET_BACK_DEFROST)
    /** The service answers [TEMP_ERROR] for a reading it holds no value for, not an error. */
    fun outsideTempCelsius(): Float? =
        if (a9) A9Climate.outsideTemp()
        else SaicAidl.callFloat(binder(), DESCRIPTOR, TX_GET_OUT_CAR_TEMP)?.takeIf { it != TEMP_ERROR }

    private fun level(code: Int): Int? =
        SaicAidl.callInt(binder(), DESCRIPTOR, code)?.takeIf { it >= 0 }

    private fun flag(code: Int): Boolean? = level(code)?.let { it == 1 }
}

/**
 * Charging and battery settings, as the stock charging screen performs them.
 *
 * `ChargingCloseSoc` is the charge limit in percent, `ReserChrg*` the scheduled window, and
 * `DrivingBatteryHeat` the battery pre-heat switch — the three things the vehicle's own
 * screen offers.
 *
 * The same binder also answers the energy counters the vehicle keeps for itself, which is the
 * only measured kilowatt-hour on a car that publishes no battery power. They are reads; this
 * object writes nothing but the charging settings the stock screen already writes.
 *
 * Binding contract: `VehicleChargingManager` and `IVehicleChargingService`.
 */
object SaicCharging {

    private const val NAME = "vehiclecharging"
    private const val DESCRIPTOR = "com.saicmotor.sdk.vehiclesettings.IVehicleChargingService"

    private const val TX_GET_SOC = 3
    private const val TX_GET_ENDURANCE_KM = 4
    private const val TX_GET_CHARGING_STATUS = 9
    private const val TX_GET_CLOSE_SOC = 13
    private const val TX_SET_CLOSE_SOC = 14
    private const val TX_GET_CONTROL_SWITCH = 17
    private const val TX_SET_CONTROL_SWITCH = 18
    private const val TX_GET_RESERVE_CONTROL = 19
    private const val TX_SET_RESERVE_CONTROL = 20
    private const val TX_GET_RESERVE_START_HOUR = 21
    private const val TX_SET_RESERVE_START_HOUR = 22
    private const val TX_GET_RESERVE_START_MINUTE = 23
    private const val TX_SET_RESERVE_START_MINUTE = 24
    private const val TX_GET_RESERVE_STOP_HOUR = 25
    private const val TX_SET_RESERVE_STOP_HOUR = 26
    private const val TX_GET_RESERVE_STOP_MINUTE = 27
    private const val TX_SET_RESERVE_STOP_MINUTE = 28
    private const val TX_GET_BATTERY_HEAT = 37
    private const val TX_SET_BATTERY_HEAT = 38
    private const val TX_GET_CONSUMPTION_PER_KM = 66
    private const val TX_GET_CONSUMPTION_PER_KM_VALID = 67
    private const val TX_GET_CONSUMPTION_PER_KM_LIST = 68
    private const val TX_GET_ACC_CONSUMPTION_AFTER_CHARGE = 73
    private const val TX_GET_ACC_CONSUMPTION_AFTER_START = 74
    private const val TX_GET_TOTAL_CONSUMPTION_AFTER_CHARGE = 75
    private const val TX_GET_TOTAL_CONSUMPTION_AFTER_START = 76
    private const val TX_GET_TOTAL_REGEN_AFTER_CHARGE = 77
    private const val TX_GET_TOTAL_REGEN_AFTER_START = 78

    /** The service's own `*V` companion signals: 1 is a value it stands behind. */
    private const val SIGNAL_VALID = 1

    val isAvailable: Boolean get() = binder() != null

    private fun binder(): IBinder? = SaicHub.service(NAME)

    /** State of charge in percent. */
    fun stateOfChargePercent(): Float? =
        SaicAidl.callFloat(binder(), DESCRIPTOR, TX_GET_SOC)?.takeIf { it >= 0f }

    /**
     * Remaining range in kilometres — the number on the car's own cluster, not a calculation.
     *
     * The service answers -1 for a range it holds no value for, and clamps its own signal
     * against a maximum before answering, so an implausible reading never reaches here as a
     * number. `read` already drops the negative.
     */
    fun rangeKm(): Int? = read(TX_GET_ENDURANCE_KM)

    fun chargingStatus(): Int? = read(TX_GET_CHARGING_STATUS)

    /** Charge limit in percent — charging stops here. */
    fun chargeLimitPercent(): Int? = read(TX_GET_CLOSE_SOC)
    fun setChargeLimitPercent(percent: Int): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_CLOSE_SOC, percent.coerceIn(0, 100))

    fun chargingEnabled(): Boolean? = read(TX_GET_CONTROL_SWITCH)?.let { it == 1 }
    fun setChargingEnabled(on: Boolean): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_CONTROL_SWITCH, if (on) 1 else 0)

    fun scheduleEnabled(): Boolean? = read(TX_GET_RESERVE_CONTROL)?.let { it == 1 }
    fun setScheduleEnabled(on: Boolean): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_RESERVE_CONTROL, if (on) 1 else 0)

    fun scheduleStartMinutes(): Int? = minutes(TX_GET_RESERVE_START_HOUR, TX_GET_RESERVE_START_MINUTE)
    fun scheduleStopMinutes(): Int? = minutes(TX_GET_RESERVE_STOP_HOUR, TX_GET_RESERVE_STOP_MINUTE)

    /** Hour and minute are two separate signals; both must land for the window to change. */
    fun setScheduleStart(minutesOfDay: Int): Boolean =
        setTime(TX_SET_RESERVE_START_HOUR, TX_SET_RESERVE_START_MINUTE, minutesOfDay)

    fun setScheduleStop(minutesOfDay: Int): Boolean =
        setTime(TX_SET_RESERVE_STOP_HOUR, TX_SET_RESERVE_STOP_MINUTE, minutesOfDay)

    fun batteryPreheatOn(): Boolean? = read(TX_GET_BATTERY_HEAT)?.let { it == 1 }
    fun setBatteryPreheat(on: Boolean): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_BATTERY_HEAT, if (on) 1 else 0)

    /**
     * Energy the car itself counted since the last ignition-on, in kWh.
     *
     * This is not derived from battery power, and that is the point: SWI68 publishes no
     * `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE`, so every kWh figure in EVSuite has had to be
     * modelled or refused. The charging service keeps its own counters, fed by the vehicle's
     * energy properties, and hands them out over the same binder the charge limit uses. A
     * counter read at the start of a trip and again at the end gives measured kilowatt-hours
     * with no pack capacity assumed and nothing integrated.
     *
     * The counter resets at ignition-on, so a trip that spans a stop cannot be measured by
     * subtracting the ends: the second reading starts again from zero and the difference goes
     * negative. Callers must treat a drop as a reset, not as regeneration.
     */
    fun consumedKwhSinceStart(): Float? = energy(TX_GET_TOTAL_CONSUMPTION_AFTER_START)

    /** Same counter, reset at the end of the last charge rather than at ignition-on. */
    fun consumedKwhSinceCharge(): Float? = energy(TX_GET_TOTAL_CONSUMPTION_AFTER_CHARGE)

    fun regeneratedKwhSinceStart(): Float? = energy(TX_GET_TOTAL_REGEN_AFTER_START)
    fun regeneratedKwhSinceCharge(): Float? = energy(TX_GET_TOTAL_REGEN_AFTER_CHARGE)

    /**
     * The `AccConsumption` counter, alongside `TotalConsumption`, over the same period.
     *
     * **What this counts is not established.** The service validates it against the same
     * bounds as the total and names it no further, so `Acc` reads equally as *accessory* —
     * the auxiliary share the total also contains, which would be a measured climate figure —
     * or as *accumulated*, a second framing of the same energy. The two readings differ by
     * everything: one is a breakdown, the other is a duplicate.
     *
     * Until a drive with the climate off and a drive with it on are compared on the car, this
     * is exposed as the raw counter under the vehicle's own name and nothing is attributed to
     * it. Naming it `climateKwh` here would be inventing the evidence.
     */
    fun accCounterKwhSinceStart(): Float? = energy(TX_GET_ACC_CONSUMPTION_AFTER_START)
    fun accCounterKwhSinceCharge(): Float? = energy(TX_GET_ACC_CONSUMPTION_AFTER_CHARGE)

    /**
     * Consumption per kilometre as the cluster shows it, or null when the car disowns it.
     *
     * The value carries a companion validity signal, and the service answers the last value
     * it held whatever that signal says. Reading one without the other reports a stale number
     * as a current one, so both are read and a value the car does not stand behind is dropped.
     */
    fun consumptionPerKm(): Float? =
        if (read(TX_GET_CONSUMPTION_PER_KM_VALID) != SIGNAL_VALID) null
        else SaicAidl.callFloat(binder(), DESCRIPTOR, TX_GET_CONSUMPTION_PER_KM)
            ?.takeIf { it.isFinite() && it >= 0f }

    /**
     * The last fifty per-kilometre readings, oldest first — the cluster's own history.
     *
     * The service persists them as a JSON array of floats and drops the oldest past fifty, so
     * this is a window the car maintains, not a series this app has to record.
     */
    fun consumptionPerKmHistory(): List<Float> =
        parseHistory(SaicAidl.callString(binder(), DESCRIPTOR, TX_GET_CONSUMPTION_PER_KM_LIST))

    /** Kept apart from the binder so the car's own wire format can be tested without one. */
    internal fun parseHistory(json: String?): List<Float> {
        if (json.isNullOrBlank()) return emptyList()
        return json.trim().removeSurrounding("[", "]")
            .split(',')
            .mapNotNull { it.trim().toFloatOrNull()?.takeIf(Float::isFinite) }
    }

    /** A counter the car never started is 0, and a negative one is not a counter. */
    private fun energy(code: Int): Float? =
        SaicAidl.callFloat(binder(), DESCRIPTOR, code)?.takeIf { it.isFinite() && it >= 0f }

    private fun read(code: Int): Int? =
        SaicAidl.callInt(binder(), DESCRIPTOR, code)?.takeIf { it >= 0 }

    private fun minutes(hourCode: Int, minuteCode: Int): Int? {
        val hour = read(hourCode) ?: return null
        val minute = read(minuteCode) ?: return null
        return hour * 60 + minute
    }

    private fun setTime(hourCode: Int, minuteCode: Int, minutesOfDay: Int): Boolean {
        val clamped = minutesOfDay.coerceIn(0, 24 * 60 - 1)
        val hourOk = SaicAidl.callVoid(binder(), DESCRIPTOR, hourCode, clamped / 60)
        val minuteOk = SaicAidl.callVoid(binder(), DESCRIPTOR, minuteCode, clamped % 60)
        return hourOk && minuteOk
    }
}

/**
 * Windows and door locks, through the same hub as climate and charging.
 *
 * **Reading and writing the glass are two different scales, and this is the whole story of
 * the feature.** The service validates every value against a maximum of its own and silently
 * drops anything above it, and the two maxima are not the same:
 *
 *   • reading — `max_vehicle_window_get = 100`: a position in percent, 0 closed to 100 open.
 *   • writing — `max_vehicle_window_set = 7`: a three-bit command, not a position. A write
 *     of 100 is above the maximum, so `VehicleControlBinder.setDriveWindow` drops it without
 *     touching the property and without answering anything. The binder call still succeeds:
 *     the method returns void, so "accepted" and "ignored" look identical from here.
 *
 * That is why glass writes scaled as a percentage reported success and moved nothing. The
 * same shape holds elsewhere in the service — the tailgate is `get = 100`, `set = 1` — so a
 * status range and a command range simply are not the same thing on this interface.
 *
 * **The hub is the fallback, not the path.** No head-unit application writes this property,
 * so the firmware offers no example to read the seven values off, and the command is passed
 * through as the caller gave it with anything outside 0–7 refused here rather than sent to
 * be dropped. The VSM carries the same glass under a setter the car's own window switch
 * goes through, where the commands *are* established — [VsmGlass]. Every read and write
 * below prefers it and falls back to the hub only on a firmware whose VSM does not have it.
 *
 * The electric tailgate is deliberately absent: the launcher defines OPEN and CLOSE as the
 * same value (1.0f), so it is a pulse whose direction depends on state this cannot read.
 * Firing it blind could open a boot at the wrong moment.
 *
 * Binding contract: `IVehicleControlService` (codes) and
 * `VehicleControlBinder` (the CarCabinManager properties behind them);
 * `VehicleConstant` (value semantics).
 */
object SaicVehicleControl {

    private const val NAME = "vehiclecontrol"
    private const val DESCRIPTOR = "com.saicmotor.sdk.vehiclesettings.IVehicleControlService"

    private const val TX_GET_DOOR_LOCK = 3
    private const val TX_SET_DOOR_LOCK = 4
    private const val TX_GET_DRIVER_WINDOW = 5
    private const val TX_SET_DRIVER_WINDOW = 6
    private const val TX_GET_PASSENGER_WINDOW = 7
    private const val TX_SET_PASSENGER_WINDOW = 8
    private const val TX_GET_LEFT_REAR_WINDOW = 9
    private const val TX_SET_LEFT_REAR_WINDOW = 10
    private const val TX_GET_RIGHT_REAR_WINDOW = 11
    private const val TX_SET_RIGHT_REAR_WINDOW = 12

    /** `VehicleConstant.DoorLockStateItem` — 1 locked, 2 unlocked. */
    private const val DOOR_LOCKED = 1
    private const val DOOR_UNLOCKED = 2

    /** Position read, in percent — `max_vehicle_window_get` in the service's own resources. */
    const val WINDOW_POSITION_MAX = 100

    /**
     * The largest command the service will pass on — `max_vehicle_window_set`. Anything
     * above it is dropped there in silence, which is why it is enforced here instead.
     */
    const val WINDOW_COMMAND_MAX = 7

    /**
     * The four windows, each with the pair of codes that reads and moves it.
     *
     * `setAllWindows` was here first and stays: closing the glass is one gesture, and asking
     * a caller to make four calls for it would let three succeed and one be forgotten. This
     * is the other half — a rule that vents the driver's window alone cannot be written with
     * an all-or-nothing call.
     */
    enum class Window(
        internal val readCode: Int,
        internal val writeCode: Int,
        /** The same window's number on the VSM, which addresses glass by area — [VsmGlass]. */
        internal val area: Int,
    ) {
        DRIVER(TX_GET_DRIVER_WINDOW, TX_SET_DRIVER_WINDOW, 0),
        PASSENGER(TX_GET_PASSENGER_WINDOW, TX_SET_PASSENGER_WINDOW, 1),
        REAR_LEFT(TX_GET_LEFT_REAR_WINDOW, TX_SET_LEFT_REAR_WINDOW, 2),
        REAR_RIGHT(TX_GET_RIGHT_REAR_WINDOW, TX_SET_RIGHT_REAR_WINDOW, 3)
    }

    private val WINDOW_READS = Window.entries.map { it.readCode }
    private val WINDOW_WRITES = Window.entries.map { it.writeCode }

    val isAvailable: Boolean get() = binder() != null

    private fun binder(): IBinder? = SaicHub.service(NAME)

    /**
     * The widest-open window, in percent — the one number a rule about glass wants. "Any
     * window open" is `> 0`, and a rule closing them cares about the worst case.
     */
    fun widestWindowPercent(): Int? =
        if (VsmGlass.isAvailable) Window.entries.mapNotNull { VsmGlass.percent(it.area) }.maxOrNull()
        else WINDOW_READS.mapNotNull { code -> SaicAidl.callFloat(binder(), DESCRIPTOR, code) }
            .takeIf { it.isNotEmpty() }
            ?.max()
            ?.toInt()
            ?.takeIf { it >= 0 }

    /**
     * One window's position in percent, 0 closed to 100 open. Null when it does not answer.
     */
    fun windowPercent(window: Window): Int? =
        if (VsmGlass.isAvailable) VsmGlass.percent(window.area)
        else SaicAidl.callFloat(binder(), DESCRIPTOR, window.readCode)?.toInt()?.takeIf { it >= 0 }

    /**
     * Sends one glass command to one window.
     *
     * [command] is a command in `0..`[WINDOW_COMMAND_MAX], **not** a position — see the class
     * KDoc. A value outside that range is refused here and returns false: the service would
     * accept the call and drop the value, which is indistinguishable from having moved.
     *
     * [VsmGlass.Command.UP] is held down rather than written once, because that is the only
     * way it moves anything — see [holdWindow]. The hub path has no such notion and cannot
     * acquire one: it is one write there whatever the value.
     */
    fun setWindow(window: Window, command: Int): Boolean {
        if (command !in 0..WINDOW_COMMAND_MAX) return false
        if (!VsmGlass.isAvailable)
            return SaicAidl.callVoid(binder(), DESCRIPTOR, window.writeCode, command.toFloat())
        return if (command == VsmGlass.Command.UP) VsmGlass.hold(listOf(window.area), command)
        else VsmGlass.send(listOf(window.area), command)
    }

    /**
     * Holds [command] on one window for [durationMs], then releases it.
     *
     * What [GlassProbe] sweeps with, and the reason a close works on the trims whose motors
     * have no position sensor: those obey nothing but a switch held down. Falls back to a
     * single write on the hub, which is all that path offers.
     */
    fun holdWindow(window: Window, command: Int, durationMs: Long = VsmGlass.HOLD_MS): Boolean {
        if (command !in 0..WINDOW_COMMAND_MAX) return false
        return if (VsmGlass.isAvailable) VsmGlass.hold(listOf(window.area), command, durationMs)
        else SaicAidl.callVoid(binder(), DESCRIPTOR, window.writeCode, command.toFloat())
    }

    /** Each window individually, for the diagnostic — this is what confirms the scale. */
    fun windowPercents(): List<Float?> =
        WINDOW_READS.map { code -> SaicAidl.callFloat(binder(), DESCRIPTOR, code) }

    /**
     * Sends the same glass command to all four windows.
     *
     * @return true when every one of them was accepted.
     */
    fun setAllWindows(command: Int): Boolean {
        if (command !in 0..WINDOW_COMMAND_MAX) return false
        if (VsmGlass.isAvailable) {
            val areas = Window.entries.map { it.area }
            return if (command == VsmGlass.Command.UP) VsmGlass.hold(areas, command)
            else VsmGlass.send(areas, command)
        }
        val value = command.toFloat()
        // Every window attempted even if one refuses: three closed beats none closed, and
        // the caller still learns that it was not complete.
        return WINDOW_WRITES.map { code -> SaicAidl.callVoid(binder(), DESCRIPTOR, code, value) }
            .all { it }
    }

    fun doorsLocked(): Boolean? =
        doorLockState()
            ?.takeIf { it == DOOR_LOCKED || it == DOOR_UNLOCKED }
            ?.let { it == DOOR_LOCKED }

    /**
     * The raw lock state, for the diagnostic only.
     *
     * The service answers a status in `0..7` (`max_vehicle_lock_get`) of which only 1 and 2
     * are named, so [doorsLocked] discards everything else. Reporting "unreadable" without
     * saying what actually came back is what makes that a dead end: the number is what tells
     * whether the state is simply unnamed here or the read is not answering at all.
     */
    fun doorLockState(): Int? = SaicAidl.callInt(binder(), DESCRIPTOR, TX_GET_DOOR_LOCK)

    fun setDoorsLocked(locked: Boolean): Boolean =
        SaicAidl.callVoid(
            binder(), DESCRIPTOR, TX_SET_DOOR_LOCK,
            if (locked) DOOR_LOCKED else DOOR_UNLOCKED
        )
}

/**
 * The gear, read from the same hub as climate, charging and glass.
 *
 * A second source for something [com.evsuite.hardware.EVHardware.isVehicleInPark] already
 * reads. It exists because the primary path on SWI68/165 goes through the vendor
 * `VehicleConditionManager` object held in-process, and when that object never arrived the
 * gear was simply unknown — while the very same service was sitting on the hub, answering.
 *
 * Only gear 1 is named, and only because three independent places agree on it: EVHardware's
 * own `GEAR_PARK_VALUE`, two head-unit apps using `carGear == 1` as the condition for playing
 * video on the centre screen (`UsbVideoView`, `VideoListFragment`), and
 * `IBackUpBinder.isSecureForBackUp`, which accepts gears 1 and 3 alongside `speed == 0`.
 * Nothing pins 2, 3 and 4, so nothing here names them.
 *
 * `getCarSpeed` is on this interface too and is deliberately unused: the binder returns a
 * float with no unit in any source, and a speed read in the wrong unit would open the write
 * gate at 50 km/h believing it was 14. Speed keeps coming from the AOSP property, whose unit
 * is specified.
 *
 * Binding contract: `IVehicleConditionService` (codes) and
 * `VehicleConditionBinder` (the CarSensorManager signals behind them).
 */
object SaicVehicleCondition {

    private const val NAME = "vehiclecondition"
    private const val DESCRIPTOR = "com.saicmotor.sdk.vehiclesettings.IVehicleConditionService"

    private const val TX_GET_CAR_GEAR = 5

    val isAvailable: Boolean get() = binder() != null

    private fun binder(): IBinder? = SaicHub.service(NAME)

    /** Raw gear, or null when unreadable — the binder answers -1 when the signal is absent. */
    fun gearOrNull(): Int? = SaicAidl.callInt(binder(), DESCRIPTOR, TX_GET_CAR_GEAR)?.takeIf { it > 0 }
}
