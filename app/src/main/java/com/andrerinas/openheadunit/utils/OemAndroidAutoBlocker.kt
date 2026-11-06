package com.andrerinas.openheadunit.utils

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock

/**
 * Keeps OEM AllGo Android Auto from winning the USB phone while DiAuto is running.
 *
 * On MG4 both AA and CarPlay are AllGo, but different packages:
 * - Android Auto → [com.allgo.app.androidauto] (`ProjectionActivity`)
 * - CarPlay → `com.allgo.carplay.service` (never touched)
 * - Shared manager → `com.allgo.rui` (never touched — stopping it would break CarPlay)
 * - SAIC bridge → [com.saicmotor.caradapter] (USB attach / AOA for AA)
 *
 * Runtime [forceStop] only — never `pm disable` — so uninstalling DiAuto leaves OEM AA working.
 */
object OemAndroidAutoBlocker {

    val TARGET_PACKAGES: List<String> = listOf(
        "com.allgo.app.androidauto",
        "com.saicmotor.caradapter",
    )

    private const val MIN_INTERVAL_MS = 2_000L
    @Volatile private var lastBlockAt = 0L

    fun blockIfEnabled(context: Context, reason: String) {
        val settings = try {
            com.andrerinas.openheadunit.App.provide(context).settings
        } catch (_: Exception) {
            return
        }
        if (!settings.blockOemAndroidAutoWhileRunning) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockAt < MIN_INTERVAL_MS) return
        lastBlockAt = now

        var stopped = 0
        for (pkg in TARGET_PACKAGES) {
            if (!isInstalled(context, pkg)) continue
            // DiAuto now carries AllGo's Android Auto package name: never stop itself.
            if (pkg == context.packageName) continue
            if (forceStop(context, pkg)) {
                stopped++
                AppLog.i("OemAndroidAutoBlocker: force-stopped $pkg ($reason)")
            }
        }
        if (stopped == 0) {
            AppLog.d("OemAndroidAutoBlocker: no OEM AA packages to stop ($reason)")
        }
    }

    private fun isInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * [ActivityManager.forceStopPackage] is hidden; available to the platform-signed system UID
     * car build. Returns false when the call is refused (phone flavor / no privilege).
     */
    private fun forceStop(context: Context, packageName: String): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val method = ActivityManager::class.java.getMethod("forceStopPackage", String::class.java)
            method.invoke(am, packageName)
            true
        } catch (e: Exception) {
            AppLog.w("OemAndroidAutoBlocker: cannot force-stop $packageName: ${e.message}")
            false
        }
    }
}
