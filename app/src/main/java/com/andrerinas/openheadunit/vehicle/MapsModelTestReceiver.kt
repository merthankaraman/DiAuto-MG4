package com.andrerinas.openheadunit.vehicle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Test values for [Mg4MapsEnergyModel], sent over adb to see what Maps really takes into account.
 * Only the shell can send them (the manifest asks for android.permission.DUMP).
 *
 * `am broadcast -a com.allgo.app.androidauto.MAPS_MODEL_TEST -n <pkg>/com.andrerinas.openheadunit.vehicle.MapsModelTestReceiver`
 * with `--ei aux 2500` (W at every temperature, -1 = normal), `--ef a_scale 3`, `--ef c_scale 0.5`,
 * `--ef temp -5` (`--ef temp 999` = live), `--ei climate 0|1` (-1 = live), or `--ez reset true`.
 */
class MapsModelTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        var t = if (intent.getBooleanExtra("reset", false)) Mg4MapsEnergyModel.TestOverrides() else Mg4MapsEnergyModel.test
        if (intent.hasExtra("aux")) t = t.copy(auxW = intent.getIntExtra("aux", -1).takeIf { it >= 0 })
        if (intent.hasExtra("a_scale")) t = t.copy(aScale = intent.getFloatExtra("a_scale", 1f).coerceIn(0.1f, 10f))
        if (intent.hasExtra("c_scale")) t = t.copy(cScale = intent.getFloatExtra("c_scale", 1f).coerceIn(0f, 10f))
        if (intent.hasExtra("temp")) t = t.copy(tempC = intent.getFloatExtra("temp", 999f).takeIf { it in -40f..60f })
        if (intent.hasExtra("climate")) t = t.copy(climateOn = when (intent.getIntExtra("climate", -1)) { 0 -> false; 1 -> true; else -> null })
        Mg4MapsEnergyModel.test = t
        AppLog.i("DiAuto-MG4 Maps model test values: $t")
        Mg4EnergyReporter.resend()
    }
}
