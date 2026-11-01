package com.andrerinas.openheadunit.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Reads the SSID and passphrase of the hotspot this device is configured to run.
 *
 * Reflection throughout — neither `getSoftApConfiguration` nor `getWifiApConfiguration` is public
 * API. On the platform-signed MG4 car build (`android.uid.system`) the read normally succeeds; on
 * locked-down / non-privileged installs it can refuse, and callers then fall back to the manual
 * Connection-setup fields. Shared by `ShareHotspotQrDialog` and the Native AA hotspot transport.
 */
object HotspotConfigReader {

    /**
     * Android 9 SoftAP often stores SSID / PSK as quoted strings (`"secret"`). DiPlay's
     * `ManualHotspotManager.unquote` strips those before advertising; without this the phone
     * gets a passphrase that never matches the real network.
     */
    fun unquote(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    /** The configured hotspot as (ssid, passphrase), or null if it cannot be read. */
    fun getSystemHotspotConfig(context: Context): Pair<String, String>? {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            // 1. Try modern getSoftApConfiguration (API 30+)
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val getSoftApConfigurationMethod = wm.javaClass.getMethod("getSoftApConfiguration")
                    val softApConfig = getSoftApConfigurationMethod.invoke(wm)
                    if (softApConfig != null) {
                        val getSsidMethod = softApConfig.javaClass.getMethod("getSsid")
                        val getPassphraseMethod = softApConfig.javaClass.getMethod("getPassphrase")
                        val rawSsid = getSsidMethod.invoke(softApConfig) as? String
                        val rawPass = getPassphraseMethod.invoke(softApConfig) as? String
                        val ssid = unquote(rawSsid)
                    AppLog.hide(ssid)
                        AppLog.hide(ssid)
                        val pass = unquote(rawPass)
                        if (ssid.isNotEmpty()) {
                            AppLog.i(
                                "HotspotConfigReader: SoftApConfiguration ssid=$ssid " +
                                    "pskLen=${pass.length} quotedSsid=${rawSsid != ssid} " +
                                    "quotedPsk=${rawPass != null && rawPass != pass}"
                            )
                            return Pair(ssid, pass)
                        }
                    }
                } catch (e: Exception) {
                    AppLog.d("HotspotConfigReader: Failed to get soft ap config via reflection: ${e.message}")
                }
            }

            // 2. Try legacy getWifiApConfiguration (API < 30) — MG4 / Android 9 SoftAP path
            try {
                val getWifiApConfigurationMethod = wm.javaClass.getMethod("getWifiApConfiguration")
                val wifiConfig = getWifiApConfigurationMethod.invoke(wm)
                if (wifiConfig != null) {
                    val ssidField = wifiConfig.javaClass.getField("SSID")
                    val preSharedKeyField = wifiConfig.javaClass.getField("preSharedKey")
                    val rawSsid = ssidField.get(wifiConfig) as? String
                    val rawPass = preSharedKeyField.get(wifiConfig) as? String
                    val ssid = unquote(rawSsid)
                    AppLog.hide(ssid)
                    val pass = unquote(rawPass)
                    if (ssid.isNotEmpty() || pass.isNotEmpty()) {
                        AppLog.i(
                            "HotspotConfigReader: WifiApConfiguration ssid=$ssid " +
                                "pskLen=${pass.length} quotedSsid=${rawSsid != ssid} " +
                                "quotedPsk=${rawPass != null && rawPass != pass}"
                        )
                        return Pair(ssid, pass)
                    }
                }
            } catch (e: Exception) {
                AppLog.d("HotspotConfigReader: Failed to get wifi ap config via reflection: ${e.message}")
            }
        } catch (e: Exception) {
            AppLog.e("HotspotConfigReader: Failed to access WifiManager: ${e.message}")
        }
        return null
    }
}
