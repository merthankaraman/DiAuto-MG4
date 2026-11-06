package com.andrerinas.openheadunit.utils

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock

/**
 * The 5 GHz channel the car's access point is brought up on (Android 9 `WifiConfiguration.apChannel`).
 *
 * Left at 0 the MG4 always picks channel 36, the busiest one: most home routers and phone hotspots
 * sit there too. Only 36-48 are offered: allowed indoors and out across Europe, no radar check
 * (DFS) that would hold the access point back for a minute.
 *
 * Auto never scans at start-up (a 5 GHz scan takes 2-4 s): it scores the scan results the car
 * already has, and keeps the last choice when they are older than [MAX_SCAN_AGE_MS]. A new scan is
 * asked for in the background for next time. The channel only changes when the access point
 * (re)starts anyway: restarting it just for this would bounce a phone about to connect.
 */
object WifiChannelPicker {
    val CHANNELS = listOf(36, 40, 44, 48)
    private const val MAX_SCAN_AGE_MS = 10 * 60_000L

    /** The channel to write into the access point configuration on its next start. */
    fun channelForNextStart(context: Context): Int {
        val settings = Settings(context)
        val fixed = settings.wifiChannel
        if (fixed != 0) return fixed
        val picked = leastBusy(context)
        if (picked != null && picked != settings.wifiAutoChannel) {
            AppLog.i("WifiChannelPicker: auto channel ${settings.wifiAutoChannel} -> $picked")
            settings.wifiAutoChannel = picked
        }
        return settings.wifiAutoChannel
    }

    /** The channel the access point was last configured with (0 in the configuration means 36). */
    fun configuredChannel(context: Context): Int {
        val stored = SoftApConfigCompat.legacyBandAndChannel(context)?.second ?: 0
        return if (stored in CHANNELS) stored else CHANNELS.first()
    }

    /** Refreshes the car's scan results for the next start; returns at once. */
    fun scanSoon(context: Context) {
        if (Settings(context).wifiChannel != 0) return
        try {
            @Suppress("DEPRECATION")
            val asked = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).startScan()
            AppLog.i("WifiChannelPicker: background scan requested=$asked")
        } catch (e: Exception) {
            AppLog.w("WifiChannelPicker: scan not possible: ${e.message}")
        }
    }

    /** Lowest score among [CHANNELS], or null without recent scan results. Ties keep the lower channel. */
    private fun leastBusy(context: Context): Int? {
        val results: List<ScanResult> = try {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).scanResults
        } catch (e: Exception) {
            AppLog.w("WifiChannelPicker: no scan results: ${e.message}")
            return null
        }
        val nowUs = SystemClock.elapsedRealtime() * 1000
        val fresh = results.filter { nowUs - it.timestamp <= MAX_SCAN_AGE_MS * 1000 && it.frequency in 5170..5250 }
        if (results.none { nowUs - it.timestamp <= MAX_SCAN_AGE_MS * 1000 }) {
            AppLog.i("WifiChannelPicker: scan results older than ${MAX_SCAN_AGE_MS / 60_000} min, keeping the last channel")
            return null
        }
        val scores = CHANNELS.associateWith { 0.0 }.toMutableMap()
        for (r in fresh) {
            // -90 dBm counts for 1, -50 dBm for 5: a network next to the car weighs more than a far one.
            val weight = ((r.level + 100) / 10.0).coerceIn(0.5, 6.0)
            for (ch in occupied(r)) scores[ch]?.let { scores[ch] = it + weight }
        }
        AppLog.i("WifiChannelPicker: ${fresh.size} networks on 36-48, scores $scores")
        return CHANNELS.minByOrNull { scores.getValue(it) }
    }

    /** The 20 MHz channels a network covers: 40/80/160 MHz ones spread over their whole block. */
    private fun occupied(r: ScanResult): List<Int> {
        val primary = (r.frequency - 5000) / 5
        val span = when (r.channelWidth) {
            ScanResult.CHANNEL_WIDTH_40MHZ -> 2
            ScanResult.CHANNEL_WIDTH_80MHZ -> 4
            ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 8
            else -> 1
        }
        if (span == 1) return listOf(primary)
        // Blocks are aligned on 36: 36-40, 44-48 for 40 MHz; 36-48 for 80 MHz; 36-64 for 160 MHz.
        val start = 36 + ((primary - 36) / (4 * span)) * 4 * span
        return (0 until span).map { start + it * 4 }
    }
}
