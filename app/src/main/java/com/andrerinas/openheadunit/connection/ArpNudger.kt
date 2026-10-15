package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * MG4: the SAIC firmware sets `net.ipv4.conf.{all,default}.arp_ignore = 8` (script `arp_update.sh`,
 * section "Disable arp", meant for the internal Ethernet). The car's kernel therefore NEVER answers ARP
 * requests, including those of the phone on the hotspot (ap0): the phone cannot resolve the hardware
 * address of 192.168.x.1 and its TCP connection to port 30518 fails with `EHOSTUNREACH` (seen in the
 * Pixel's logcat: CAR.SETUP.WIFI, "No route to host", IpReachabilityMonitor NUD_FAILED), without a single
 * packet ever reaching our ServerSocket.
 *
 * Without root this sysctl cannot be changed. Workaround: a Linux host updates (or unblocks) an EXISTING
 * neighbour entry (including INCOMPLETE/FAILED) when it receives a broadcast ARP request whose sender is
 * that IP address -- even if the request is not about the receiving host. So the car's kernel is made to
 * emit broadcast ARP requests (sender = hotspot address) by sending UDP datagrams to unused addresses of
 * the subnet. The phone thereby learns the car's hardware address.
 *
 * Sends no useful content: one UDP byte to port 9 (discard), never to a real device.
 */
object ArpNudger {
    private const val TAG = "ArpNudger"
    private const val INTERVAL_MS = 150L
    private const val MAX_DATAGRAMS = 50

    @Volatile
    private var job: Job? = null

    @Volatile
    private var runningFor: String? = null

    /** Starts (or extends) the ARP wake-up for the hotspot [hostIp] for [durationMs]. */
    @Synchronized
    fun start(scope: CoroutineScope, hostIp: String, durationMs: Long = 60_000L) {
        if (job?.isActive == true && runningFor == hostIp) {
            // already running for this hotspot: restart for a full duration
            stop()
        } else {
            stop()
        }
        val host = try {
            InetAddress.getByName(hostIp) as? Inet4Address
        } catch (e: Exception) {
            null
        }
        if (host == null) {
            AppLog.w("$TAG: invalid hotspot address ($hostIp), no ARP wake-up.")
            return
        }
        val raw = host.address
        val targets = targetAddresses(host, raw)
        if (targets.isEmpty()) {
            AppLog.w("$TAG: no usable addresses in the hotspot subnet; skipping ARP wake-up.")
            return
        }
        runningFor = hostIp
        job = scope.launch(Dispatchers.IO) {
            AppLog.i("$TAG: ARP wake-up active for ${host.hostAddress} (${durationMs / 1000}s) -- the car's kernel has arp_ignore=8.")
            val deadline = System.currentTimeMillis() + durationMs
            var sent = 0
            var errors = 0
            try {
                // source = hotspot address, so the ARP sender IP is the one the phone is looking for
                DatagramSocket(0, host).use { socket ->
                    for (target in targets) {
                        if (!isActive || System.currentTimeMillis() >= deadline) break
                        try {
                            socket.send(
                                DatagramPacket(byteArrayOf(0), 1, target, 9)
                            )
                            sent++
                        } catch (e: Exception) {
                            if (errors++ < 3) AppLog.w("$TAG: cannot send UDP: ${e.javaClass.simpleName}: ${e.message}")
                        }
                        delay(INTERVAL_MS)
                    }
                }
            } catch (e: Exception) {
                AppLog.w("$TAG: stopped on error ${e.javaClass.simpleName}: ${e.message}")
            }
            AppLog.i("$TAG: ARP wake-up finished ($sent datagrams, $errors errors).")
        }
    }

    private fun targetAddresses(host: Inet4Address, raw: ByteArray): List<InetAddress> {
        val prefixLength = try {
            val interfaceAddress = NetworkInterface.getByInetAddress(host)?.interfaceAddresses
                ?.firstOrNull { it.address == host }
                ?: return emptyList()
            interfaceAddress.networkPrefixLength.toInt()
        } catch (_: Exception) {
            return emptyList()
        }
        if (prefixLength !in 1..30) return emptyList()
        val hostValue = ((raw[0].toInt() and 0xff) shl 24) or
            ((raw[1].toInt() and 0xff) shl 16) or
            ((raw[2].toInt() and 0xff) shl 8) or
            (raw[3].toInt() and 0xff)
        val mask = -1 shl (32 - prefixLength)
        val network = hostValue and mask
        val broadcast = network or mask.inv()
        return (broadcast - MAX_DATAGRAMS..broadcast - 1)
            .filter { it > network && it != hostValue }
            .take(MAX_DATAGRAMS)
            .map { address ->
                InetAddress.getByAddress(
                    byteArrayOf(
                        (address ushr 24).toByte(),
                        (address ushr 16).toByte(),
                        (address ushr 8).toByte(),
                        address.toByte(),
                    ),
                )
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        runningFor = null
    }
}
