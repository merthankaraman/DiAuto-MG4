package com.andrerinas.openheadunit.utils

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import android.view.View
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config
import java.util.Locale

// Locale tests do not use TLS; the app bundles an Android-only Conscrypt native library.
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], manifest = Config.NONE)
class LocaleHelperTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
    private val manager get() = context.getSystemService(LocaleManager::class.java)

    @Before fun reset() {
        prefs.edit().clear().commit()
        if (android.os.Build.VERSION.SDK_INT >= 33) manager.applicationLocales = LocaleList.getEmptyLocaleList()
    }

    @Test fun legacyChoiceMigratesOnceAndSystemChangesStayAuthoritative() {
        prefs.edit().putString("app-language", "ru").commit()
        assertEquals("ru", Settings(context).appLanguage)
        assertEquals("ru", manager.applicationLocales[0].language)
        assertFalse(prefs.contains("app-language"))
        manager.applicationLocales = LocaleList(Locale("es"))
        assertEquals("es", Settings(context).appLanguage)
        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        assertEquals("", Settings(context).appLanguage)
    }

    @Test fun migrationDoesNotOverwriteAnAndroidSettingsChoice() {
        prefs.edit().putString("app-language", "ar").commit()
        manager.applicationLocales = LocaleList(Locale.SIMPLIFIED_CHINESE)
        assertEquals("zh-CN", Settings(context).appLanguage)
    }

    @Test fun pickerUpdatesPlatformAndCanRestoreSystemDefault() {
        Settings(context).appLanguage = "ar"
        assertEquals("ar", manager.applicationLocales[0].language)
        Settings(context).appLanguage = ""
        assertTrue(manager.applicationLocales.isEmpty)
    }

    @Test fun backupUsesThePlatformChoiceInsteadOfTheRemovedLegacyKey() {
        Settings(context).appLanguage = "es"
        val exported = SettingsBackupManager.exportFromContext(context)
        assertEquals("es", SettingsBackupManager.parseImportJson(exported).values["app-language"])
    }

    @Test fun connectionModeMigrationPreservesExplicitSingleModeChoices() {
        prefs.edit().putInt("primary-connection", Settings.ConnectionKind.USB_CABLE.value).commit()
        assertEquals(setOf(Settings.ConnectionMode.USB), Settings(context).connectionModes)

        prefs.edit().clear()
            .putStringSet("connection-modes", setOf(Settings.ConnectionMode.WIFI.key))
            .commit()
        assertEquals(setOf(Settings.ConnectionMode.WIFI), Settings(context).connectionModes)
    }

    @Test
    @Config(sdk = [29], qualifiers = "en")
    fun olderAndroidSwitchesResourcesAndRtlWithoutChangingTheProcessDefault() {
        val processDefault = Locale.getDefault()
        val settings = Settings(context)
        settings.appLanguage = "ar"
        val arabic = LocaleHelper.wrapContext(context)
        assertEquals("ar", arabic.resources.configuration.locales[0].language)
        assertEquals(View.LAYOUT_DIRECTION_RTL, arabic.resources.configuration.layoutDirection)
        settings.appLanguage = "zh-CN"
        val chinese = LocaleHelper.wrapContext(context)
        assertEquals("zh-CN", chinese.resources.configuration.locales[0].toLanguageTag())
        settings.appLanguage = ""
        val system = LocaleHelper.wrapContext(context)
        assertEquals("en", system.resources.configuration.locales[0].language)
        assertEquals(View.LAYOUT_DIRECTION_LTR, system.resources.configuration.layoutDirection)
        assertEquals(processDefault, Locale.getDefault())
    }
}
