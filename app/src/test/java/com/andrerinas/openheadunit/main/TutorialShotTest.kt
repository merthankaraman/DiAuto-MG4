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
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The first-start tutorial rendered on the MG4 panel, one PNG per step in app/build/screenshots/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(application = android.app.Application::class, qualifiers = "en-w1920dp-h720dp-land-night-mdpi", sdk = [28])
class TutorialShotTest {
    @Test
    fun shots() {
        val controller = Robolectric.buildActivity(DaTutorialActivity::class.java).setup()
        val a = controller.get()
        val out = File("build/screenshots").apply { mkdirs() }
        for (i in 0 until 6) {
            shadowOf(Looper.getMainLooper()).idle()
            val demo = a.findViewById<GestureDemoView>(com.andrerinas.openheadunit.R.id.tuto_demo)
            demo.freeze(0.35f)
            val root = a.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 720)
            val bmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "tuto_${i + 1}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (i in 1..3) {
                demo.freeze(0.8f)
                root.draw(Canvas(bmp))
                File(out, "tuto_${i + 1}b.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            a.findViewById<View>(com.andrerinas.openheadunit.R.id.tuto_next).performClick()
        }
    }
}
