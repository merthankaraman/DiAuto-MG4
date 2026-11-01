package com.andrerinas.openheadunit.main

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationManeuver.NavigationType
import com.andrerinas.openheadunit.hud.SaicGuidance
import com.andrerinas.openheadunit.hud.SaicNavigationMapper
import com.andrerinas.openheadunit.hud.SaicNavigationOutputs
import com.andrerinas.openheadunit.utils.Settings

/**
 * Test bench for the instrument cluster: pick any Android Auto maneuver type (with the roundabout
 * exit number or angle), press Send, and the cluster shows the icon the mapper picks for it, sent
 * through the same path as a real guidance. Without an Android Auto session.
 */
class NavTestActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var current: SaicGuidance? = null
    private lateinit var result: TextView
    private lateinit var maneuver: Spinner
    private lateinit var iconView: ManeuverIconView
    private lateinit var iconFile: TextView
    private lateinit var exitNumber: EditText
    private lateinit var exitAngle: EditText
    private lateinit var distance: EditText
    private lateinit var road: EditText

    // The cluster drops guidance that is not renewed for 30 s, so a test frame is renewed while shown.
    private val keepAlive = object : Runnable {
        override fun run() {
            current?.let {
                current = it.copy(updatedNs = System.nanoTime())
                SaicNavigationOutputs.update(current)
                handler.postDelayed(this, 10_000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(text: String) = TextView(this).apply { this.text = text; setPadding(0, pad / 2, 0, 0) }
        fun field(hint: String, value: String, numeric: Boolean) = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        }

        column.addView(TextView(this).apply {
            text = "Cluster navigation test"
            textSize = 22f
        })

        column.addView(label("Android Auto maneuver type"))
        maneuver = Spinner(this)
        maneuver.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            NavigationType.values().map { "${it.name} (${it.number})" }
        )
        iconView = ManeuverIconView(this)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(maneuver, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(iconView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(top)
        iconFile = TextView(this)
        column.addView(iconFile)

        column.addView(label("Roundabout exit number (optional)"))
        exitNumber = field("e.g. 2", "", true)
        column.addView(exitNumber)
        column.addView(label("Roundabout exit angle, 1-360 (optional, used by *_WITH_ANGLE)"))
        exitAngle = field("e.g. 90", "", true)
        column.addView(exitAngle)
        column.addView(label("Distance to the maneuver (meters)"))
        distance = field("meters", "300", true)
        column.addView(distance)
        column.addView(label("Street"))
        road = field("street", "Rue de Test", false)
        column.addView(road)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Send to cluster"
            setOnClickListener { send() }
        })
        buttons.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { stopGuidance() }
        })
        column.addView(buttons)

        val refresh = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updatePreview()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        exitNumber.addTextChangedListener(refresh)
        exitAngle.addTextChangedListener(refresh)
        maneuver.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updatePreview()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        result = TextView(this).apply { textSize = 18f; setPadding(0, pad, 0, 0) }
        column.addView(result)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        })
        SaicNavigationOutputs.start(this)
        if (!SaicNavigationOutputs.available(this)) {
            result.text = "com.saicmotor.adapterservice not found: no cluster on this device."
        }
    }

    private fun updatePreview() {
        val file = iconView.show(
            NavigationType.values()[maneuver.selectedItemPosition],
            exitNumber.text.toString().toIntOrNull(),
            exitAngle.text.toString().toIntOrNull()
        )
        iconFile.text = file
    }

    private fun send() {
        val type = NavigationType.values()[maneuver.selectedItemPosition]
        val number = exitNumber.text.toString().toIntOrNull()
        val angle = exitAngle.text.toString().toIntOrNull()
        val icon = SaicNavigationMapper.icon(type, number, angle, Settings(this).rightHandDrive)
        if (icon == null) {
            stopGuidance()
            result.text = "${type.name} -> no icon in the table: the cluster shows nothing."
            return
        }
        val meters = distance.text.toString().toIntOrNull() ?: 0
        val street = road.text.toString()
        current = SaicGuidance(icon, meters, street)
        SaicNavigationOutputs.start(this)
        SaicNavigationOutputs.update(current)
        handler.removeCallbacks(keepAlive)
        handler.postDelayed(keepAlive, 10_000)
        result.text = "${type.name} (exit ${number ?: "-"}, angle ${angle ?: "-"}) -> " +
            "${icon.name} (tbtType ${icon.id}), $meters m, \"$street\""
    }

    private fun stopGuidance() {
        handler.removeCallbacks(keepAlive)
        current = null
        SaicNavigationOutputs.stop()
        result.text = "Guidance stopped."
    }

    override fun onDestroy() {
        handler.removeCallbacks(keepAlive)
        if (current != null) SaicNavigationOutputs.stop()
        super.onDestroy()
    }
}
