package com.andrerinas.openheadunit.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class NightMode(private val settings: Settings, val hasGPSLocation: Boolean) {
    private val calculator = NightModeCalculator(settings)
    var currentLux: Float = -1f
    var currentBrightness: Int = -1

    /**
     * Pure sunrise/sunset night state, independent of [Settings.nightMode].
     * Use when a feature needs the astronomical day/night decision without being
     * affected by the user's Android Auto night-mode selection.
     */
    val isSunriseSunsetNight: Boolean
        get() = calculator.current

    var current: Boolean = false
        get()  {
            return when (settings.nightMode){
                Settings.NightMode.AUTO -> com.andrerinas.openheadunit.App.instance
                    ?.let { NightModeManager.autoNight(it, settings) { calculator.current } } ?: calculator.current
                Settings.NightMode.SUN -> calculator.current
                Settings.NightMode.DAY -> false
                Settings.NightMode.NIGHT -> true
                Settings.NightMode.MANUAL_TIME -> {
                    val now = Calendar.getInstance()
                    val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                    val start = settings.nightModeManualStart
                    val end = settings.nightModeManualEnd
                    
                    val isNight = if (start <= end) {
                        currentMinutes in start..end
                    } else {
                        // Rollover (e.g. 22:00 to 06:00)
                        currentMinutes >= start || currentMinutes <= end
                    }
                    
                    AppLog.d("NightMode Check: Now=$currentMinutes, Start=$start, End=$end, Result=$isNight")
                    isNight
                }
                Settings.NightMode.LIGHT_SENSOR -> {
                    if (currentLux >= 0) currentLux < settings.nightModeThresholdLux else false
                }
                Settings.NightMode.SCREEN_BRIGHTNESS -> {
                    if (currentBrightness >= 0) currentBrightness < settings.nightModeThresholdBrightness else false
                }
                Settings.NightMode.CAR_SIGNAL -> false // Handled directly in NightModeManager
                Settings.NightMode.LOCATION -> false // Handled directly in NightModeManager
            }
        }

    override fun toString(): String {
        return when (settings.nightMode){
            Settings.NightMode.AUTO -> "NightMode: car or ${calculator.current}"
            Settings.NightMode.SUN -> "NightMode: sun ${calculator.current}"
            Settings.NightMode.DAY -> "NightMode: DAY"
            Settings.NightMode.NIGHT -> "NightMode: NIGHT"
            Settings.NightMode.MANUAL_TIME -> {
                val startH = settings.nightModeManualStart / 60
                val startM = settings.nightModeManualStart % 60
                val endH = settings.nightModeManualEnd / 60
                val endM = settings.nightModeManualEnd % 60
                "NightMode: Manual (%02d:%02d - %02d:%02d)".format(startH, startM, endH, endM)
            }
            Settings.NightMode.LIGHT_SENSOR -> "NightMode: Sensor ($currentLux < ${settings.nightModeThresholdLux})"
            Settings.NightMode.SCREEN_BRIGHTNESS -> "NightMode: Brightness ($currentBrightness < ${settings.nightModeThresholdBrightness})"
            Settings.NightMode.CAR_SIGNAL -> "NightMode: Car ILL+ signal"
            Settings.NightMode.LOCATION -> "NightMode: Location (by area)"
        }
    }

    fun getCalculationInfo(): String {
        return calculator.getCalculationInfo()
    }
}

private class NightModeCalculator(private val settings: Settings) {
    private val twilightCalculator = TwilightCalculator()
    private val format = SimpleDateFormat("HH:mm", Locale.US)

    // When the user has chosen a fixed sunrise/sunset point, use it (works offline, no GPS);
    // otherwise use the last known location (updated from head unit / phone-forwarded GPS).
    // Shared by both the app theme and the Android Auto night mode Auto/sunrise calculations.
    private fun latitude(): Double =
        if (settings.useFixedSunriseLocation)
            settings.fixedSunriseLatitude else settings.lastKnownLocation.latitude

    private fun longitude(): Double =
        if (settings.useFixedSunriseLocation)
            settings.fixedSunriseLongitude else settings.lastKnownLocation.longitude

    fun getCalculationInfo(): String {
        val time = Calendar.getInstance().time
        twilightCalculator.calculateTwilight(time.time, latitude(), longitude())
        
        val sunrise = if (twilightCalculator.mSunrise > 0) format.format(Date(twilightCalculator.mSunrise)) else "--:--"
        val sunset = if (twilightCalculator.mSunset > 0) format.format(Date(twilightCalculator.mSunset)) else "--:--"
        return "$sunrise - $sunset"
    }

    var current: Boolean = false
        get()  {
            val time = Calendar.getInstance().time
            twilightCalculator.calculateTwilight(time.time, latitude(), longitude())
            return twilightCalculator.mState == TwilightCalculator.NIGHT
        }

    override fun toString(): String {
        val sunrise = if (twilightCalculator.mSunrise > 0) format.format(Date(twilightCalculator.mSunrise)) else "-1"
        val sunset = if (twilightCalculator.mSunset > 0) format.format(Date(twilightCalculator.mSunset)) else "-1"
        val mode = if (twilightCalculator.mState == TwilightCalculator.NIGHT) "NIGHT" else "DAY"
        return String.format(Locale.US, "%s, (%s - %s)", mode, sunrise, sunset)
    }
}