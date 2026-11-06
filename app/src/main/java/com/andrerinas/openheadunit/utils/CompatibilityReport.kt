package com.andrerinas.openheadunit.utils

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.os.Build
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * A short description of this install, to paste into a GitHub issue (copy, share or scan the QR
 * code). Car, firmware, Android, decoders, DiAuto's settings and its place in the car's system,
 * and the last steering wheel key codes. Nothing that identifies the person: no VIN, no Bluetooth
 * or Wi-Fi addresses, no phone or network names, no passwords, no location.
 */
object CompatibilityReport {
    private const val ALLGO = "com.allgo.app.androidauto"
    private const val RUI = "com.allgo.rui"
    private const val MG4DASH = "app.mg4dash"
    private const val OLD_DIAUTO = "com.drivehub.diauto.mg4"

    fun build(context: Context): String {
        val settings = App.provide(context).settings
        val lines = mutableListOf<String>()
        lines += "DiAuto MG4+ ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        lines += "Firmware: ${prop("ro.build.mt2712.version") ?: Build.DISPLAY}"
        prop("ro.product.carmode")?.let { lines += "Car: $it" }
        lines += "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.HARDWARE}"
        lines += "Integration: ${integration(context)}"
        lines += "Decoders: ${decoders()}"
        lines += "Video: ${resolution(settings.resolutionId)}@${settings.fpsLimit} " +
            "codec=${settings.videoCodec} view=${settings.viewMode} dpi=${settings.dpiPixelDensity}"
        lines += "Link: ${settings.lastConnectionType.ifBlank { "-" }} " +
            "primary=${settings.primaryConnection}"
        lines += "Audio: ${if (settings.musicViaBluetooth) "music over Bluetooth" else "music over AA"} " +
            "aac=${settings.useAacAudio} buffer=${settings.mediaAudioBufferMillis}ms " +
            "latency=${settings.audioLatencyMultiplier} focus=${settings.playbackFocusMode}"
        lines += "Screen: ${screen(context)}"
        lines += "Bluetooth: ${bluetooth(context)}, native AA BT failures=${settings.nativeAaServerFailures}"
        lines += "Hotspot: ${hotspot(context)}"
        lines += "Session: AA ${if (com.allgo.app.androidauto.RelayState.isSessionUp()) "connected" else "not connected"}, " +
            "car source=${carSource(context)}"
        lines += "System: ${memory(context)}, up ${android.os.SystemClock.elapsedRealtime() / 60_000} min"
        val keys = SteeringKeyLog.recent()
        lines += "Wheel keys: ${if (keys.isEmpty()) "-" else keys.joinToString(" ")}"
        return lines.joinToString("\n")
    }

    fun show(context: Context) {
        dialog(context, build(context)).show()
    }

    fun dialog(context: Context, report: String): androidx.appcompat.app.AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_compat_report, null)
        view.findViewById<TextView>(R.id.tv_report_text).text = report
        QrCodeGenerator.generateQrCode(report, 600)?.let {
            view.findViewById<ImageView>(R.id.img_report_qr).setImageBitmap(it)
        }
        return MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(R.string.compat_report_title)
            .setView(view)
            .setPositiveButton(R.string.compat_report_copy) { _, _ -> copy(context, report) }
            .setNeutralButton(R.string.compat_report_share) { _, _ -> share(context, report) }
            .setNegativeButton(R.string.close, null)
            .create()
    }

    private fun copy(context: Context, report: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("DiAuto compatibility report", report))
        Toast.makeText(context, R.string.compat_report_copied, Toast.LENGTH_SHORT).show()
    }

    private fun share(context: Context, report: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
        runCatching {
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { copy(context, report) }
    }

    private fun integration(context: Context): String {
        val pm = context.packageManager
        val parts = mutableListOf<String>()
        parts += if (context.packageName == ALLGO) {
            val info = runCatching { pm.getApplicationInfo(ALLGO, 0) }.getOrNull()
            val update = info != null && info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (update) "as AllGo update" else "as AllGo"
        } else {
            "own package"
        }
        parts += "rui=" + (version(pm, RUI) ?: "no")
        parts += "MG4Dash=" + (version(pm, MG4DASH) ?: "no")
        if (context.packageName != OLD_DIAUTO && version(pm, OLD_DIAUTO) != null) parts += "old DiAuto still installed"
        return parts.joinToString(", ")
    }

    private fun version(pm: PackageManager, pkg: String): String? =
        runCatching { pm.getPackageInfo(pkg, 0).versionName ?: "?" }.getOrNull()

    /** Video decoders by type, hardware first, with whether 1080p60 is reported. */
    private fun decoders(): String = runCatching {
        val out = mutableListOf<String>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                val short = when (type) {
                    "video/avc" -> "H264"
                    "video/hevc" -> "H265"
                    else -> continue
                }
                val caps = runCatching { info.getCapabilitiesForType(type).videoCapabilities }.getOrNull()
                val fps60 = caps?.let { runCatching { it.areSizeAndRateSupported(1920, 1080, 60.0) }.getOrNull() }
                out += "$short ${info.name}${if (fps60 == true) " 1080p60" else ""}"
            }
        }
        out.joinToString("; ").ifEmpty { "-" }
    }.getOrDefault("-")

    private fun screen(context: Context): String {
        val m = context.resources.displayMetrics
        return "${m.widthPixels}x${m.heightPixels} ${m.densityDpi}dpi"
    }

    /** On/off, how many paired devices (a count, never names or addresses), hands-free link up. */
    @SuppressLint("MissingPermission")
    private fun bluetooth(context: Context): String = runCatching {
        val adapter = BluetoothHelper.getBluetoothAdapter(context) ?: return "none"
        if (!adapter.isEnabled) return "off"
        val paired = runCatching { adapter.bondedDevices?.size }.getOrNull() ?: 0
        val hfp = runCatching {
            adapter.getProfileConnectionState(16 /* HEADSET_CLIENT */) ==
                android.bluetooth.BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)
        "on, paired=$paired, hands-free=${if (hfp) "connected" else "no"}"
    }.getOrDefault("?")

    /** Up or not, and its band: no network name, no password. */
    private fun hotspot(context: Context): String = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val up = wifi.javaClass.getMethod("isWifiApEnabled").invoke(wifi) as Boolean
        if (!up) return "off"
        val config = wifi.javaClass.getMethod("getWifiApConfiguration").invoke(wifi)
        val band = runCatching { config?.javaClass?.getField("apBand")?.getInt(config) }.getOrNull()
        "on, band=" + when (band) { 0 -> "2.4 GHz"; 1 -> "5 GHz"; null -> "?"; else -> "$band" }
    }.getOrDefault("?")

    private fun carSource(context: Context): String = runCatching {
        val am = com.evsuite.hardware.saic.SaicCarAudioSource.audioManager(context) ?: return "?"
        com.evsuite.hardware.saic.SaicCarAudioSource.readCurrentSourceType(am)?.toString() ?: "?"
    }.getOrDefault("?")

    private fun memory(context: Context): String = runCatching {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        "RAM ${info.totalMem / 1_048_576} MB, free ${info.availMem / 1_048_576} MB"
    }.getOrDefault("RAM ?")

    private fun resolution(id: Int): String =
        Settings.Resolution.values().firstOrNull { it.id == id }?.resName ?: "id$id"

    @SuppressLint("PrivateApi")
    private fun prop(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, name) as String
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
