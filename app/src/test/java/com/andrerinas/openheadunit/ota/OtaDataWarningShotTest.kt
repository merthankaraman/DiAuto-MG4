package com.andrerinas.openheadunit.ota

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import org.robolectric.RuntimeEnvironment
import com.andrerinas.openheadunit.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "fr-w1920dp-h720dp-land-night-mdpi")
class OtaDataWarningShotTest {

    @Test
    fun confirmDialog() {
        val context: Context = RuntimeEnvironment.getApplication()
        val root = LayoutInflater.from(ContextThemeWrapper(context, R.style.AppTheme)).inflate(R.layout.dialog_ota_refresh, null)
        val text = OtaDialogs.withDataWarning(
            context.getString(R.string.ota_data_warning, "31.3 MB"),
            context.getString(R.string.ota_dialog_install_message, "2.2.1-beta30", "31.3 MB"))
        root.findViewById<TextView>(R.id.tvOtaRefreshMessage).text = text
        root.findViewById<TextView>(R.id.btnOtaRefresh).text = context.getString(R.string.ota_action_install_update)
        root.measure(View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val bmp = Bitmap.createBitmap(root.measuredWidth, root.measuredHeight, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF10151F.toInt())
        root.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/ota_data_warning.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(text.toString().contains("31.3 MB"))
    }
}
