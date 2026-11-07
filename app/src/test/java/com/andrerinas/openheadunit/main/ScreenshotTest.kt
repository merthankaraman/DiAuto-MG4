package com.andrerinas.openheadunit.main

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.ota.OtaController
import com.andrerinas.openheadunit.vehicle.BatteryCapacityResolver
import com.andrerinas.openheadunit.vehicle.Mg4DashBattery
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screens rendered on the MG4 panel (1920 × 720 dp, 160 dpi, French, dark), written to
 * app/build/screenshots/ to show design changes before they ship.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(application = Application::class, sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-night-mdpi")
class ScreenshotTest {

    private val context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)

    private fun save(view: View, width: Int, height: Int, name: String) {
        // Twice: MaterialButton places its icon during the first layout and asks for another.
        repeat(2) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun home(status: OtaController.Status, name: String) {
        val root = LayoutInflater.from(context).inflate(R.layout.da_home, null)
        root.findViewById<TextView>(R.id.home_update_hint).text = updateText(context, status)
        save(root, 1920, 720, name)
    }

    @Test fun homeUpToDate() = home(
        OtaController.Status(OtaController.State.UP_TO_DATE, "2.0.0-beta15", true, true),
        "home_update_up_to_date.png",
    )

    @Test fun homeUpdateAvailable() = home(
        OtaController.Status(OtaController.State.AVAILABLE, "2.0.0-beta16", true, true),
        "home_update_available.png",
    )

    @Test fun batterySettings() {
        val ctx = context
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.da_background))
            setPadding(48, 24, 48, 24)
        }
        fun header(text: String) = column.addView(TextView(ctx).apply {
            this.text = text
            textSize = 15f
            setTextColor(ContextCompat.getColor(ctx, R.color.da_accent))
            setPadding(24, 20, 24, 4)
        })
        fun rows(resolved: BatteryCapacityResolver.Resolved) {
            listOf(
                R.string.battery_net_capacity_kwh to batteryCapacityText(ctx, resolved),
                R.string.battery_soh_percent to batterySohText(ctx, resolved),
                R.string.battery_effective_capacity to
                    ctx.getString(R.string.battery_effective_capacity_value, resolved.effectiveKwh),
            ).forEach { (name, value) ->
                val item = LayoutInflater.from(ctx).inflate(R.layout.layout_setting_item, column, false)
                item.findViewById<TextView>(R.id.settingName).setText(name)
                item.findViewById<TextView>(R.id.settingValue).text = value
                item.setPadding(24, 8, 24, 8)
                column.addView(item)
            }
        }
        header("Avec MG4 Dash : batterie 51 kWh choisie, 47,0 kWh mesurés")
        rows(BatteryCapacityResolver.resolve(null, null, null, Mg4DashBattery.Reading(51, true, 47.0f)))
        header("Sans MG4 Dash, voiture muette")
        rows(BatteryCapacityResolver.resolve(null, null, null, null))
        header("Forcé à la main")
        rows(BatteryCapacityResolver.resolve(49f, 97.5f, null, null))
        save(column, 1920, 900, "battery_settings.png")
    }

    @Test fun batteryCapacityDialog() {
        val activityController = Robolectric.buildActivity(FragmentActivity::class.java)
        activityController.get().setTheme(R.style.AppTheme)
        val activity = activityController.setup().get()
        val auto = BatteryCapacityResolver.resolve(null, null, null, null)
        val labels = listOf(
            activity.getString(
                R.string.battery_auto_option,
                activity.getString(R.string.battery_value_kwh, auto.netKwh),
                batteryCapacitySource(activity, auto),
            ),
        ) + BatteryCapacityResolver.PACKS.map { (pack, usable) ->
            activity.getString(R.string.battery_pack_option, pack, usable)
        }
        val dialog = MaterialAlertDialogBuilder(activity, R.style.DarkAlertDialog)
            .setTitle(R.string.battery_net_capacity_kwh)
            .setSingleChoiceItems(labels.toTypedArray(), 0, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        shadowOf(Looper.getMainLooper()).idle()
        save(dialog.window!!.decorView, 720, 470, "battery_capacity_dialog.png")
    }

    @Test fun compatibilityReport() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        val report = listOf(
            "DiAuto MG4+ 2.1.0-beta10 (2067)",
            "Firmware: SWI69-29958-1100R45",
            "Car: EH32_CR EUUK",
            "Android: 9 (API 28), mt2712",
            "Integration: as AllGo update, rui=1.0, MG4Dash=1.4.2",
            "Decoders: H264 OMX.MTK.VIDEO.DECODER.AVC 1080p60; H265 OMX.MTK.VIDEO.DECODER.HEVC",
            "Video: 1080p@30 codec=H.264 view=SURFACE dpi=227",
            "Link: wifi wifiMode=3 primary=WIFI",
            "Audio: music over AA aac=false buffer=1000ms latency=8 focus=AUTO",
            "Screen: 1920x720 160dpi",
            "Bluetooth: on, paired=2, hands-free=connected, native AA BT failures=0",
            "Hotspot: on, band=5 GHz",
            "Session: AA connected, car source=70",
            "System: RAM 3847 MB, free 1210 MB, up 42 min",
            "Wheel keys: 300 299 301 287(long)",
        ).joinToString("\n")
        val dialog = com.andrerinas.openheadunit.utils.CompatibilityReport.dialog(activity, report)
        dialog.show()
        shadowOf(Looper.getMainLooper()).idle()
        save(dialog.window!!.decorView, 1400, 600, "compat_report_dialog.png")
    }
}
