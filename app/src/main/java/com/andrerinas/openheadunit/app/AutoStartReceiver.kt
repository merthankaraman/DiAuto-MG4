package com.andrerinas.openheadunit.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

class AutoStartReceiver : BroadcastReceiver() {

    // BLUETOOTH_CONNECT is checked at the top of onReceive (API 31+); lint cannot see that.
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == BluetoothDevice.ACTION_ACL_CONNECTED && AapService.isUserExitActive(context)) {
            AppLog.i("AutoStartReceiver: User exited Android Auto; ignoring Bluetooth auto-start.")
            return
        }
        // Use device-protected storage so the BT MACs are readable during locked boot
        val targetMacs = Settings.getAutoStartBtMacs(context)

        if (targetMacs.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.w("AutoStartReceiver: Missing BLUETOOTH_CONNECT — ignoring ACL event.")
            return
        }
        
        val isLocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && 
                      !(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
        
        // [FIX] Don't trigger auto-start if we are already connected!
        // This prevents activity restarts if BT reconnects during a session.
        if (!isLocked && com.andrerinas.openheadunit.App.provide(context).commManager.isConnected) {
            AppLog.d("AutoStartReceiver: Already connected to Android Auto. Ignoring BT event.")
            return
        }

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            val address = try {
                device.address
            } catch (_: SecurityException) {
                AppLog.w("AutoStartReceiver: SecurityException reading device address.")
                return
            }
            val name = try {
                device.name
            } catch (_: SecurityException) {
                null
            }
            AppLog.i("BT Device connected: ${name ?: "unnamed"} ($address)")

            if (targetMacs.contains(address)) {
                AppLog.i("MATCH! Starting AapService via Bluetooth Auto-start for $address (background, no UI)...")

                // Service only: Native AA handshake / SoftAP bring-up run without pulling
                // MainActivity to the front. ACL_CONNECTED can fire repeatedly (profiles
                // reconnecting); launching the UI each time steals the car screen. Projection
                // still opens on HandshakeComplete when a session actually lands.
                // Pass the MAC so Native AA can poke this phone immediately — waiting for the
                // user to pick it on the home screen is the awkward path this avoids.
                val serviceIntent = Intent(context, AapService::class.java).apply {
                    setAction(AapService.ACTION_BT_AUTO_START)
                    putExtra(AapService.EXTRA_MAC, address)
                }
                var serviceStarted = true
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    serviceStarted = false
                    AppLog.e("Failed to start AapService from background: ${e.message}")
                }

                // Only if the OS blocked the service start: quiet notification (tap to open),
                // not a full-screen intent that forces the UI over whatever the user was doing.
                if (!serviceStarted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Bluetooth auto-start")
                    }
                    showLaunchNotification(context, launchIntent, name?.takeIf { it.isNotEmpty() } ?: address)
                }
            }
        }
    }

    private fun showLaunchNotification(context: Context, launchIntent: Intent, deviceLabel: String) {
        try {
            val pendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, App.bootStartChannel)
                .setSmallIcon(R.drawable.ic_stat_aa)
                .setContentTitle(context.getString(R.string.auto_start_bt_label))
                .setContentText(context.getString(R.string.wifi_autostart_content, deviceLabel))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(BT_AUTO_START_NOTIFICATION_ID, notification)
            AppLog.i("AutoStartReceiver: Posted tap-to-open notification (service start was blocked).")
        } catch (e: Exception) {
            AppLog.e("AutoStartReceiver: Could not post launch notification: ${e.message}")
        }
    }

    private companion object {
        // WifiAutoStartReceiver uses 99; kept apart so one does not replace the other.
        const val BT_AUTO_START_NOTIFICATION_ID = 98
    }
}