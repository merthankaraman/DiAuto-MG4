package com.andrerinas.openheadunit.connection

import android.os.Build
import com.andrerinas.openheadunit.utils.AppLog
import java.io.File
import java.net.ServerSocket

/**
 * Network quirks of the MG4/SAIC firmware, detected on the car itself.
 *
 * The SAIC `arp_update.sh` script (section "Disable arp" + firewall):
 *  - sets `arp_ignore=8` on the SWI133 firmware: the car answers no ARP request, even on the hotspot
 *    (SWI132 sets 1, SWI68/SWI69 do not set it);
 *  - installs `iptables INPUT ... REJECT` after a whitelist of ports (TCP 6010, 8080, 30517, 30518...)
 *    on SWI132/SWI133; SWI68/SWI69 only drop a few specific ports.
 *
 * Rather than a table of software versions (the firmware exposes no readable "SWI" number),
 * the real rules are read: the TCP port announced to the phone is picked among the allowed ports.
 */
object CarNetworkQuirks {
    private const val TAG = "CarNetworkQuirks"

    /** Usual wireless Android Auto port, used when no restrictive firewall is detected. */
    const val STANDARD_PORT = 5288

    /** Port allowed by every known SAIC firmware; fallback when the script cannot be read. */
    const val SAIC_FALLBACK_PORT = 30518

    private val SCRIPT_PATHS = listOf("/system/bin/arp_update.sh", "/vendor/bin/arp_update.sh")

    /** Preference order among the allowed ports: the ones least likely to be taken already come first. */
    private val PREFERRED = listOf(30518, 30517, 8080, 6010)

    private val ACCEPT_TCP = Regex("""INPUT\s+-p\s+tcp\s+--dport\s+(\d+)\s+-j\s+ACCEPT""")
    private val DEFAULT_REJECT = Regex("""INPUT\s+-j\s+(REJECT|DROP)\b""")

    data class Firewall(val source: String, val rejectsByDefault: Boolean, val allowedTcp: List<Int>)

    /** Extracts the TCP whitelist and whether there is a default reject. Commented-out lines are ignored. */
    fun parseFirewall(text: String, source: String): Firewall? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        val allowed = lines.flatMap { ACCEPT_TCP.findAll(it).map { m -> m.groupValues[1].toInt() }.toList() }.distinct()
        val rejects = lines.any { DEFAULT_REJECT.containsMatchIn(it) }
        if (allowed.isEmpty() && !rejects) return null
        return Firewall(source, rejects, allowed)
    }

    /** Picks the port to announce. [isFree] tests whether a port can be bound; returns (port, reason). */
    fun pickPort(firewall: Firewall?, isSaic: Boolean, isFree: (Int) -> Boolean): Pair<Int, String> {
        if (firewall != null && firewall.rejectsByDefault && firewall.allowedTcp.isNotEmpty()) {
            val candidates = PREFERRED.filter { it in firewall.allowedTcp } +
                    firewall.allowedTcp.filter { it !in PREFERRED }
            val chosen = candidates.firstOrNull { isFree(it) }
                ?: return 0 to "no free TCP port allowed by ${firewall.source}"
            return chosen to "restrictive firewall read from ${firewall.source} (allowed TCP: ${
                firewall.allowedTcp.joinToString(
                    ","
                )
            })"
        }
        if (firewall != null) {
            return if (isFree(STANDARD_PORT)) {
                STANDARD_PORT to "firewall read from ${firewall.source} without a default reject"
            } else {
                0 to "standard TCP port $STANDARD_PORT is already in use"
            }
        }
        if (isSaic) {
            return if (isFree(SAIC_FALLBACK_PORT)) {
                SAIC_FALLBACK_PORT to "SAIC device, firewall script unreadable: falling back to a port allowed on every known firmware"
            } else {
                0 to "SAIC fallback TCP port $SAIC_FALLBACK_PORT is already in use"
            }
        }
        return if (isFree(STANDARD_PORT)) {
            STANDARD_PORT to "no SAIC firewall detected: standard port"
        } else {
            0 to "standard TCP port $STANDARD_PORT is already in use"
        }
    }

    @Volatile
    private var cachedPort: Int? = null

    @Volatile
    private var cachedWhy: String = ""

    @Volatile
    private var cachedArpNudge: Boolean? = null

    private fun isSaicDevice(): Boolean =
        Build.MODEL.equals("SAIC", ignoreCase = true) ||
                Build.DEVICE.contains("saic", ignoreCase = true) ||
                Build.MANUFACTURER.equals("AUTUS", ignoreCase = true)

    private fun readFirewall(): Firewall? {
        for (path in SCRIPT_PATHS) {
            try {
                val f = File(path)
                if (f.isFile && f.canRead()) {
                    parseFirewall(f.readText(), path)?.let { return it }
                }
            } catch (e: Exception) {
                AppLog.d("$TAG: cannot read $path (${e.javaClass.simpleName}: ${e.message})")
            }
        }
        return null
    }

    private fun canBind(port: Int): Boolean = try {
        ServerSocket().use { s ->
            s.bind(java.net.InetSocketAddress(port))
        }
        true
    } catch (e: Exception) {
        false
    }

    /** Automatically chosen wireless TCP port (computed once, then cached for the lifetime of the process). */
    fun autoWirelessPort(): Int {
        cachedPort?.let { return it }
        synchronized(this) {
            cachedPort?.let { return it }
            val (port, why) = pickPort(readFirewall(), isSaicDevice(), ::canBind)
            cachedWhy = why
            if (port != 0) cachedPort = port
            AppLog.i("$TAG: automatic wireless port = $port ($why).")
            return port
        }
    }

    @Synchronized
    fun invalidateCachedPort() {
        cachedPort = null
        cachedWhy = ""
    }

    /** Why the automatic choice was made (for diagnostics). */
    fun autoPortReason(): String {
        autoWirelessPort()
        return cachedWhy
    }

    /**
     * The ARP wake-up is only useful when the kernel does not answer ARP requests (arp_ignore >= 3);
     * when the value is unreadable it is enabled on SAIC devices (harmless).
     */
    fun needsArpNudge(): Boolean {
        cachedArpNudge?.let { return it }
        val value = listOf("all", "default").mapNotNull { scope ->
            try {
                File("/proc/sys/net/ipv4/conf/$scope/arp_ignore").readText().trim().toIntOrNull()
            } catch (e: Exception) {
                null
            }
        }.maxOrNull()
        val needed = if (value != null) value >= 3 else isSaicDevice()
        cachedArpNudge = needed
        AppLog.i("$TAG: arp_ignore=${value ?: "unreadable"} -> ARP wake-up ${if (needed) "needed" else "not needed"}.")
        return needed
    }
}
