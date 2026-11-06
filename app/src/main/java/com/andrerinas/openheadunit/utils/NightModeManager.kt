package com.andrerinas.openheadunit.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.contract.LocationUpdateIntent
import com.andrerinas.openheadunit.location.LocationHolder
import java.util.Calendar

class NightModeManager(
    private val context: Context,
    private val settings: Settings,
    private val onUpdate: (Boolean) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var nightModeCalculator = NightMode(settings, false)
    
    // State
    private var lastEmittedValue: Boolean? = null
    private var currentLux: Float = -1f
    private var currentBrightness: Int = -1
    private var isFirstSensorReading = true
    private var isSensorRegistered = false
    private var isObserverRegistered = false
    private var isCarSignalObserverRegistered = false
    private var isCarSkinObserverRegistered = false
    
    private val handler = Handler(Looper.getMainLooper())

    // Debouncing
    private var pendingValue: Boolean? = null
    private val debounceRunnable = Runnable {
        pendingValue?.let { newValue ->
            if (lastEmittedValue != newValue) {
                lastEmittedValue = newValue
                onUpdate(newValue)
            }
        }
    }

    private val brightnessObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            update()
        }
    }

    // Separate observer instance for the car-signal key so unregister doesn't
    // accidentally remove the brightness observer when switching modes.
    // Car ILL+ is a binary signal already debounced by the car hardware, so emit
    // immediately instead of going through the 2s sensor-noise debounce.
    private val carSignalObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            update(debounce = false)
        }
    }

    // The car's own day/night skin (headlights or its night-mode setting): follow it at once.
    private val carSkinObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            update(debounce = false)
        }
    }

    // The car's lights have no change callback here: read them every few seconds, as MG4Dash does.
    private val lightsPoll = object : Runnable {
        override fun run() {
            update(debounce = false)
            handler.postDelayed(this, LIGHTS_POLL_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocationUpdateIntent.action) {
                // Keep the sensor values when recreating the calculator
                val oldLux = currentLux
                val oldBright = currentBrightness
                nightModeCalculator = NightMode(settings, true)
                nightModeCalculator.currentLux = oldLux
                nightModeCalculator.currentBrightness = oldBright
            }
            update()
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(LocationUpdateIntent.action)
        }
        
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        
        // Initial setup of sensors/observers based on current settings
        refreshListeners()

        // Initial update immediately
        // Reset lastEmittedValue to ensure initial state is sent
        lastEmittedValue = null
        update(debounce = false)
    }

    fun stop() {
        try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        if (isSensorRegistered) {
            sensorManager.unregisterListener(this)
            isSensorRegistered = false
        }
        if (isObserverRegistered) {
            context.contentResolver.unregisterContentObserver(brightnessObserver)
            isObserverRegistered = false
        }
        if (isCarSignalObserverRegistered) {
            context.contentResolver.unregisterContentObserver(carSignalObserver)
            isCarSignalObserverRegistered = false
        }
        if (isCarSkinObserverRegistered) {
            context.contentResolver.unregisterContentObserver(carSkinObserver)
            isCarSkinObserverRegistered = false
        }
        handler.removeCallbacks(debounceRunnable)
        handler.removeCallbacks(lightsPoll)
    }

    fun resendCurrentState() {
        // Force a fresh check and SEND even if value hasn't changed (e.g. new connection)
        nightModeCalculator = NightMode(settings, true)
        nightModeCalculator.currentLux = currentLux
        nightModeCalculator.currentBrightness = currentBrightness
        
        refreshListeners()
        lastEmittedValue = null // Invalidate cache to force emission
        update(debounce = false)
    }

    private fun refreshListeners() {
        // 1. Light Sensor
        if (settings.nightMode == Settings.NightMode.LIGHT_SENSOR) {
            if (!isSensorRegistered && lightSensor != null) {
                sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
                isSensorRegistered = true
            }
        } else {
            if (isSensorRegistered) {
                sensorManager.unregisterListener(this)
                isSensorRegistered = false
            }
        }

        // 2. Brightness Observer
        if (settings.nightMode == Settings.NightMode.SCREEN_BRIGHTNESS) {
            if (!isObserverRegistered) {
                context.contentResolver.registerContentObserver(
                    SystemSettings.System.getUriFor(SystemSettings.System.SCREEN_BRIGHTNESS),
                    false,
                    brightnessObserver
                )
                if (Build.VERSION.SDK_INT >= 28) {
                    context.contentResolver.registerContentObserver(
                        SystemSettings.System.getUriFor("screen_brightness_float"),
                        false,
                        brightnessObserver
                    )
                }
                isObserverRegistered = true
            }
        } else {
            if (isObserverRegistered) {
                context.contentResolver.unregisterContentObserver(brightnessObserver)
                isObserverRegistered = false
            }
        }

        // 3. Car Headlight Signal Observer (Settings.System.car_mode: 0 = day, 1 = night)
        if (settings.nightMode == Settings.NightMode.CAR_SIGNAL) {
            if (!isCarSignalObserverRegistered) {
                context.contentResolver.registerContentObserver(
                    SystemSettings.System.getUriFor("car_mode"),
                    false,
                    carSignalObserver
                )
                isCarSignalObserverRegistered = true
            }
        } else {
            if (isCarSignalObserverRegistered) {
                context.contentResolver.unregisterContentObserver(carSignalObserver)
                isCarSignalObserverRegistered = false
            }
        }

        // 4. The car's lights, for Auto (Like the car)
        handler.removeCallbacks(lightsPoll)
        if (settings.nightMode == Settings.NightMode.AUTO) handler.postDelayed(lightsPoll, LIGHTS_POLL_MS)

        // 5. The car's day/night skin, for Auto on cars that have one
        if (settings.nightMode == Settings.NightMode.AUTO && carSkinNight(context) != null) {
            if (!isCarSkinObserverRegistered) {
                context.contentResolver.registerContentObserver(
                    SystemSettings.System.getUriFor(CAR_SKIN_KEY), false, carSkinObserver
                )
                isCarSkinObserverRegistered = true
            }
        } else if (isCarSkinObserverRegistered) {
            context.contentResolver.unregisterContentObserver(carSkinObserver)
            isCarSkinObserverRegistered = false
        }
    }

    companion object {
        /**
         * The MG4's day/night skin, written by the car's CarAdapterService (AutoSkinObserver) from
         * its night-mode setting and the headlights: 0 = dark, 1 = light. The launcher, top bar
         * and the stock Android Auto follow it. Read at start too, so a car started with the
         * lights already on comes up dark.
         */
        const val CAR_SKIN_KEY = "SKIN_THEME_CONFIG"
        private const val LIGHTS_POLL_MS = 3_000L

        /**
         * Like the car: the lights first (the skin can be pinned to dark or light in the car's
         * settings and then never follows them), then the car's skin, else null.
         */
        fun carNight(context: Context): Boolean? =
            try { com.evsuite.hardware.EVHardware.getLightsOn() } catch (_: Throwable) { null }
                ?: carSkinNight(context)

        /**
         * Like the car: dark when the car's lights are on, and also from sunset to sunrise (as
         * MG4Dash does), so stopping with the lights off at night stays dark. The sun only counts
         * with a real position; without the car's lights at all, the sun decides alone.
         */
        fun autoNight(context: Context, settings: Settings, sunNight: () -> Boolean): Boolean {
            val car = carNight(context) ?: return sunNight()
            return car || (settings.hasSunriseLocation && sunNight())
        }

        /** True at night, false by day, null on a head unit without the key. */
        fun carSkinNight(context: Context): Boolean? = try {
            SystemSettings.System.getInt(context.contentResolver, CAR_SKIN_KEY) == 0
        } catch (_: Exception) {
            null
        }
    }

    // Made public so Service can force an update
    fun update(debounce: Boolean = true) {
        var isNight = false
        val threshold = settings.nightModeThresholdLux
        val thresholdBrightness = settings.nightModeThresholdBrightness

        when (settings.nightMode) {
            Settings.NightMode.LOCATION -> {
                // Dark/light depending on the saved place the device is inside, or the
                // user's outside-places default when outside all of them.
                isNight = com.andrerinas.openheadunit.location.GeofenceLocation
                    .resolveNight(context, settings)
            }
            Settings.NightMode.LIGHT_SENSOR -> {
                if (currentLux >= 0) {
                    // Hysteresis Logic
                    val hyst = 10.0f // 5 Lux buffer
                    val currentIsNight = lastEmittedValue ?: false
                    
                    isNight = if (currentIsNight) {
                        currentLux < (threshold + hyst)
                    } else {
                        currentLux < threshold
                    }
                }
            }
            Settings.NightMode.SCREEN_BRIGHTNESS -> {
                currentBrightness = readBrightness()
                if (currentBrightness >= 0) {
                    val hyst = 10
                    val currentIsNight = lastEmittedValue ?: false

                    isNight = if (currentIsNight) {
                        currentBrightness < (thresholdBrightness + hyst)
                    } else {
                        currentBrightness < thresholdBrightness
                    }
                    AppLog.d("NightModeManager: SCREEN_BRIGHTNESS brightness=$currentBrightness threshold=$thresholdBrightness isNight=$isNight")
                } else {
                    AppLog.w("NightModeManager: SCREEN_BRIGHTNESS read failed; keeping previous state")
                    isNight = lastEmittedValue ?: false
                }
            }
            Settings.NightMode.CAR_SIGNAL -> {
                // Many Chinese aftermarket head units (e.g. OLEDPRO) expose the car's
                // ILL+ headlight signal as Settings.System.car_mode (0 = day, 1 = night).
                val carMode = try {
                    SystemSettings.System.getInt(context.contentResolver, "car_mode")
                } catch (_: Exception) { -1 }
                if (carMode >= 0) {
                    isNight = carMode != 0
                    AppLog.d("NightModeManager: CAR_SIGNAL car_mode=$carMode isNight=$isNight")
                } else {
                    AppLog.w("NightModeManager: CAR_SIGNAL key 'car_mode' not present; keeping previous state")
                    isNight = lastEmittedValue ?: false
                }
            }
            // Auto follows the car's own day/night skin where there is one.
            Settings.NightMode.AUTO -> {
                isNight = autoNight(context, settings) { nightModeCalculator.isSunriseSunsetNight }
            }
            // Delegate to standard calculator for other modes (Day, Night, Manual)
            else -> {
                // Ensure calculator has latest settings reference/values
                isNight = nightModeCalculator.current
            }
        }

        // Apply
        if (debounce) {
            if (pendingValue != isNight) {
                pendingValue = isNight
                handler.removeCallbacks(debounceRunnable)
                handler.postDelayed(debounceRunnable, 2000)
            }
        } else {
            // Immediate update
            handler.removeCallbacks(debounceRunnable) // Cancel any pending debounce
            if (lastEmittedValue != isNight) {
                lastEmittedValue = isNight
                onUpdate(isNight)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            val newLux = event.values[0]
            
            // Only update if value changed significantly or it's the first reading
            if (kotlin.math.abs(newLux - currentLux) >= 1.0f || isFirstSensorReading) {
                currentLux = newLux
                nightModeCalculator.currentLux = currentLux
                
                if (isFirstSensorReading) {
                    isFirstSensorReading = false
                    update(debounce = false)
                } else {
                    update()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun readBrightness(): Int {
        // On API 28+ (Android 9+) the brightness slider primarily writes to
        // `screen_brightness_float` (0.0..1.0). The legacy integer SCREEN_BRIGHTNESS
        // may be stale, missing (when slider hits 0), or driven by auto-brightness,
        // which on some Android 10 ROMs makes it diverge from the slider. Prefer the
        // float when available and fall back to the integer otherwise.
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val f = SystemSettings.System.getFloat(
                    context.contentResolver, "screen_brightness_float"
                )
                if (!f.isNaN()) {
                    return (f * 255f).toInt().coerceIn(0, 255)
                }
            } catch (_: Exception) { /* fall through to integer */ }
        }
        return try {
            SystemSettings.System.getInt(
                context.contentResolver, SystemSettings.System.SCREEN_BRIGHTNESS
            ).coerceIn(0, 255)
        } catch (_: Exception) { -1 }
    }
}