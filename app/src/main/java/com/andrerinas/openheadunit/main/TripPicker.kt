package com.andrerinas.openheadunit.main

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.andrerinas.openheadunit.R

/**
 * The battery % Maps forecasts at arrival, set with two large digits over Android Auto so the forecast
 * stays in sight. A trip must use at least [MIN_DROP] % to be usable, so the value can't go above that.
 */
object TripPicker {
    const val MIN_DROP = 10
    private const val DEFAULT_DROP = 20

    /** The highest forecast allowed, or null when the battery is too low to record a usable trip. */
    fun maxForecast(currentPercent: Int): Int? = (currentPercent - MIN_DROP).takeIf { it >= 0 }

    fun defaultForecast(currentPercent: Int): Int? =
        maxForecast(currentPercent)?.let { (currentPercent - DEFAULT_DROP).coerceIn(0, it) }

    fun step(value: Int, delta: Int, max: Int): Int = (value + delta).coerceIn(0, max)

    /** Adds the panel to [root] and returns it. */
    fun show(activity: Activity, root: ViewGroup, currentPercent: Int, onGo: (Int) -> Unit): View? {
        // Tapped twice: the panel is already there.
        root.findViewById<View>(R.id.trip_go)?.let { return root }
        val max = maxForecast(currentPercent) ?: return null
        var value = defaultForecast(currentPercent) ?: return null
        val panel = activity.layoutInflater.inflate(R.layout.da_trip_picker, root, false)
        val tens = panel.findViewById<TextView>(R.id.trip_tens)
        val units = panel.findViewById<TextView>(R.id.trip_units)
        fun refresh() {
            tens.text = (value / 10).toString()
            units.text = (value % 10).toString()
        }
        panel.findViewById<TextView>(R.id.trip_picker_note).text =
            activity.getString(R.string.trip_picker_note, currentPercent, MIN_DROP)
        listOf(
            R.id.trip_tens_up to 10,
            R.id.trip_tens_down to -10,
            R.id.trip_units_up to 1,
            R.id.trip_units_down to -1,
        ).forEach { (id, delta) ->
            panel.findViewById<View>(id).setOnClickListener {
                value = step(value, delta, max)
                refresh()
            }
        }
        panel.findViewById<View>(R.id.trip_close).setOnClickListener { root.removeView(panel) }
        panel.findViewById<View>(R.id.trip_go).setOnClickListener {
            root.removeView(panel)
            onGo(value)
        }
        refresh()
        root.addView(panel)
        return panel
    }
}
