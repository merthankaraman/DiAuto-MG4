package com.andrerinas.openheadunit.main

import android.content.Context
import com.andrerinas.openheadunit.R

/**
 * Car firmwares known not to run wireless Android Auto: their Bluetooth stack allows one app
 * connection at a time, so the phone never opens the Android Auto one (seen on SWI68 R46, fixed
 * in R63, and on SWI131 R05). Shown on the home screen for those who install without the README.
 */
object FirmwareCompat {
    private val version = Regex("""^(SWI\d+)-\d+-\d+R(\d+)""")

    /** The warning for [firmware] (ro.build.mt2712.version), or null when it is not a known bad one. */
    fun warning(context: Context, firmware: String): String? {
        val m = version.find(firmware.trim()) ?: return null
        val name = "${m.groupValues[1]} R${m.groupValues[2]}"
        val warning = when ("${m.groupValues[1]} ${m.groupValues[2].toInt()}") {
            "SWI68 46" -> context.getString(R.string.da_firmware_incompatible_update, name, "SWI68 R63")
            "SWI131 5" -> context.getString(R.string.da_firmware_incompatible, name)
            else -> return null
        }
        return warning + " " + context.getString(R.string.da_firmware_dongle_backup)
    }
}
