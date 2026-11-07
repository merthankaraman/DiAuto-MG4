package com.andrerinas.openheadunit.utils

import android.app.Application
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], manifest = Config.NONE)
class SettingsLogCaptureTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun reset() {
        prefs.edit().clear().commit()
    }

    @Test
    fun defaultsOffAndPreservesExplicitV4Choices() {
        val settings = Settings(context)
        assertFalse(settings.exporterCaptureEnabled)

        prefs.edit().putBoolean(Settings.KEY_LOG_CAPTURE_ENABLED_V2, true).commit()
        assertTrue(settings.exporterCaptureEnabled)

        prefs.edit().putBoolean(Settings.KEY_LOG_CAPTURE_ENABLED_V2, false).commit()
        assertFalse(settings.exporterCaptureEnabled)
    }
}
