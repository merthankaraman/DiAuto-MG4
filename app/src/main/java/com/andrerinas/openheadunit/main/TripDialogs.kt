package com.andrerinas.openheadunit.main

import android.app.Activity
import android.view.ViewGroup
import android.widget.Toast
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.vehicle.TripRecorder
import com.evsuite.hardware.EVHardware

/** The trip button: asks Maps' forecast to start a recording, or ends the one under way. */
object TripDialogs {

    fun buttonLabel(activity: Activity): Int =
        if (TripRecorder.isActive(activity)) R.string.trip_stop else R.string.trip_start

    fun toggle(activity: Activity, onChanged: () -> Unit = {}) {
        if (TripRecorder.isActive(activity)) {
            val saved = TripRecorder.finish(activity, "button")
            Toast.makeText(activity, if (saved) R.string.trip_saved else R.string.trip_too_short, Toast.LENGTH_LONG).show()
            onChanged()
            return
        }
        val reading = EVHardware.getCapacityReading()
        val current = reading.percent ?: reading.precisePercent?.let { Math.round(it) }
        if (current == null) {
            Toast.makeText(activity, R.string.trip_no_battery, Toast.LENGTH_LONG).show()
            return
        }
        val panel = TripPicker.show(activity, activity.window.decorView as ViewGroup, current) { forecast ->
            val started = TripRecorder.start(activity, forecast)
            Toast.makeText(activity, if (started) R.string.trip_started else R.string.trip_no_battery, Toast.LENGTH_LONG).show()
            onChanged()
        }
        if (panel == null) Toast.makeText(activity, R.string.trip_battery_low, Toast.LENGTH_LONG).show()
    }
}
