package com.andrerinas.openheadunit.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * DiAuto is installed as an update of AllGo's Android Auto (`com.allgo.app.androidauto`), so the
 * car's own launcher, rui and MG4 Dash treat it as the car's Android Auto. Up to 2.0.0-beta24 it was
 * `com.drivehub.diauto.mg4`: its settings are taken over once, then that copy is removed so two
 * DiAutos never answer the phone. Removing this update brings AllGo's app back from the system.
 */
object AllgoPackage {
    const val OLD_PACKAGE = "com.drivehub.diauto.mg4"
    private const val IMPORTED_MARKER = "imported-from-$OLD_PACKAGE"

    /** Copies the old DiAuto's preferences (same system uid). Before Settings is first read. */
    fun importOldSettings(context: Context) {
        if (context.packageName == OLD_PACKAGE) return
        val marker = File(context.filesDir, IMPORTED_MARKER)
        if (marker.exists()) return
        val old = try {
            context.packageManager.getApplicationInfo(OLD_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            marker.createNewFile()
            return
        }
        var copied = 0
        copied += copyPrefs(File(old.dataDir, "shared_prefs"), File(context.dataDir, "shared_prefs"))
        if (Build.VERSION.SDK_INT >= 24 && old.deviceProtectedDataDir != null) {
            val de = context.createDeviceProtectedStorageContext().dataDir
            copied += copyPrefs(File(old.deviceProtectedDataDir, "shared_prefs"), File(de, "shared_prefs"))
        }
        marker.createNewFile()
        AppLog.i("AllgoPackage: $copied settings files taken over from $OLD_PACKAGE")
    }

    private fun copyPrefs(from: File, to: File): Int {
        val files = from.listFiles { f -> f.isFile && f.name.endsWith(".xml") } ?: return 0
        to.mkdirs()
        var n = 0
        for (f in files) {
            runCatching { f.copyTo(File(to, f.name), overwrite = true); n++ }
                .onFailure { AppLog.w("AllgoPackage: ${f.name} not copied: ${it.message}") }
        }
        return n
    }

    /** Removes the old DiAuto, once its settings are here. */
    fun removeOldPackage(context: Context) {
        if (context.packageName == OLD_PACKAGE || !installed(context, OLD_PACKAGE)) return
        if (!File(context.filesDir, IMPORTED_MARKER).exists()) return
        uninstall(context, OLD_PACKAGE)
        AppLog.i("AllgoPackage: removing $OLD_PACKAGE")
    }

    /** "Back to AllGo": removes this update; the system's AllGo Android Auto takes its place again. */
    fun restoreAllgo(context: Context) {
        AppLog.i("AllgoPackage: removing this update to restore AllGo Android Auto")
        uninstall(context, context.packageName)
    }

    private fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun uninstall(context: Context, pkg: String) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
        val sender = PendingIntent.getBroadcast(
            context, pkg.hashCode(), Intent("$pkg.UNINSTALL_RESULT").setPackage(context.packageName), flags,
        ).intentSender
        runCatching { context.packageManager.packageInstaller.uninstall(pkg, sender) }
            .onFailure { AppLog.w("AllgoPackage: uninstall $pkg failed: ${it.message}") }
    }
}
