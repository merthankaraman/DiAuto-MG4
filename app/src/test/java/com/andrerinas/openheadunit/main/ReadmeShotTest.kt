package com.andrerinas.openheadunit.main

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The real settings screens, in English, for the README: app/build/screenshots/readme_*.png. */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "en-w1920dp-h720dp-land-night-mdpi")
class ReadmeShotTest {
    @Test
    fun settingsTabs() {
        listOf("connection", "display", "sound", "car", "about").forEachIndexed { tab, name ->
            val intent = DaSettingsActivity.intent(org.robolectric.RuntimeEnvironment.getApplication())
                .putExtra(DaSettingsActivity.EXTRA_TAB, tab)
            val activity = Robolectric.buildActivity(DaSettingsActivity::class.java, intent).setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            val root = activity.window.decorView
            repeat(2) {
                root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 1920, 720)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val bmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File("build/screenshots").mkdirs()
            File("build/screenshots/readme_settings_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
