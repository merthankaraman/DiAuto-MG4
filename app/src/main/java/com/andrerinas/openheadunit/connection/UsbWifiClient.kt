package com.andrerinas.openheadunit.connection

import android.content.Context
import android.net.wifi.WifiManager
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog

/**
 * With Android Auto on the USB cable the car's Wi-Fi is free: its client is switched on so the car
 * joins a known network, typically the phone's hotspot, and has internet. Over Wi-Fi the car stays
 * the hotspot the phone is on, and in client mode it stops wireless projection, so the client is
 * switched off again when the USB session ends, but only when DiAuto switched it on.
 */
object UsbWifiClient {
    private const val TAG = "UsbWifiClient"

    @Volatile private var enabledByUs = false

    fun onSessionStarted(context: Context, wireless: Boolean) {
        if (wireless || !App.provide(context).settings.usbWifiClient) return
        val wifi = wifiManager(context) ?: return
        if (wifi.isWifiEnabled) {
            AppLog.i("$TAG: USB session, the car's Wi-Fi client is already on")
            return
        }
        @Suppress("DEPRECATION")
        val ok = runCatching { wifi.setWifiEnabled(true) }.getOrElse {
            AppLog.w("$TAG: could not switch the Wi-Fi client on: ${it.message}")
            false
        }
        enabledByUs = ok
        AppLog.i("$TAG: USB session, car Wi-Fi client switched on=$ok (joins known networks)")
    }

    fun onSessionEnded(context: Context) {
        if (!enabledByUs) return
        enabledByUs = false
        @Suppress("DEPRECATION")
        val ok = runCatching { wifiManager(context)?.setWifiEnabled(false) }.getOrNull()
        AppLog.i("$TAG: USB session over, car Wi-Fi client switched off=$ok")
    }

    private fun wifiManager(context: Context): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
}
