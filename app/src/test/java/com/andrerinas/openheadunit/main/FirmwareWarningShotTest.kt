package com.andrerinas.openheadunit.main

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.view.ContextThemeWrapper
import com.andrerinas.openheadunit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The red home banner for firmwares known not to run wireless Android Auto. */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [33], qualifiers = "fr-w1920dp-h720dp-land-night-mdpi")
class FirmwareWarningShotTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun knownBadFirmwaresOnly() {
        assertTrue(FirmwareCompat.warning(context, "SWI68-29958-1300R46")!!.contains("SWI68 R63"))
        assertTrue(FirmwareCompat.warning(context, "SWI131-29958-1100R05")!!.contains("SWI131 R05"))
        assertNull(FirmwareCompat.warning(context, "SWI68-29958-1300R63"))
        assertNull(FirmwareCompat.warning(context, "SWI69-29958-1100R45"))
        assertNull(FirmwareCompat.warning(context, "?"))
    }

    @Test
    fun homeWithBanner() {
        val themed = ContextThemeWrapper(context, R.style.AppTheme)
        val root = LayoutInflater.from(themed).inflate(R.layout.da_home, null)
        root.findViewById<TextView>(R.id.home_version).text = "2.2.1-beta19"
        root.findViewById<TextView>(R.id.home_state).text = "Android Auto non connecté"
        root.findViewById<TextView>(R.id.home_main_label).text = "Connecter le téléphone"
        root.findViewById<TextView>(R.id.home_firmware_warning).apply {
            text = FirmwareCompat.warning(context, "SWI131-29958-1100R05")
            visibility = View.VISIBLE
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 720)
        val bmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/home_firmware_warning.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.home_firmware_warning).visibility)
    }
}
