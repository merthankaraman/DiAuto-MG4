package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.aap.SoftApCaptivePortalPolicy
import com.andrerinas.openheadunit.utils.AppLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Keeps phones from abandoning the head-unit SoftAP for "no internet".
 *
 * Wi‑Fi Direct is the cleaner local-only join, but on MG4 the supported Native AA path is the car
 * SoftAP. SoftAP looks like a normal AP with no upstream, so connectivity checks fail and many
 * phones drop Wi‑Fi after a while. Dongles avoid that with P2P / specifier joins; here we answer
 * the same checks the phone already sends:
 * 1. iptables REDIRECT SoftAP DNS → local spoof for captive hostnames only
 * 2. HTTP 204 on the SoftAP gateway for `/generate_204`
 *
 * Requires platform / system privileges for iptables (MG4 car flavor). Failures are logged and
 * ignored so an unprivileged build still runs wireless AA without this help.
 */
class SoftApCaptivePortalSpoof {

    private val running = AtomicBoolean(false)
    private var ifaceName: String? = null
    private var gatewayIpv4: String? = null
    private var httpPort: Int = 80

    /** The unit refused the iptables redirect once; it will again until the app restarts. */
    private var redirectRefused = false

    private var dnsSocket: DatagramSocket? = null
    private var httpServer: ServerSocket? = null
    private var dnsThread: Thread? = null
    private var httpThread: Thread? = null

    @Synchronized
    fun start(apIface: String, gatewayIp: String) {
        if (apIface.isBlank() || gatewayIp.isBlank() || redirectRefused) return
        if (running.get() && ifaceName == apIface && gatewayIpv4 == gatewayIp) return
        stop()

        ifaceName = apIface
        gatewayIpv4 = gatewayIp
        running.set(true)

        if (!startHttp(gatewayIp)) {
            AppLog.w("SoftApCaptive: HTTP 204 listener failed on $gatewayIp — phones may still drop Wi‑Fi")
            running.set(false)
            return
        }
        if (!startDns(gatewayIp)) {
            AppLog.w("SoftApCaptive: DNS spoof listener failed — stopping HTTP helper")
            stopHttpOnly()
            running.set(false)
            return
        }
        if (!installIptables(apIface)) {
            // Without the redirect no check ever reaches the listeners (the MG4 refuses it), so
            // stop them and don't retry on every hotspot event until the app restarts.
            AppLog.w("SoftApCaptive: this unit refuses the iptables redirect - captive-portal help off until restart")
            redirectRefused = true
            stopSockets()
            ifaceName = null
            gatewayIpv4 = null
            running.set(false)
            return
        }
        AppLog.i(
            "SoftApCaptive: answering connectivity checks on $apIface ($gatewayIp) " +
                "dns→:${SoftApCaptivePortalPolicy.DNS_REDIRECT_PORT} http=:$httpPort"
        )
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false) && ifaceName == null) {
            stopSockets()
            return
        }
        val iface = ifaceName
        removeIptables(iface)
        stopSockets()
        ifaceName = null
        gatewayIpv4 = null
    }

    private fun stopHttpOnly() {
        try { httpServer?.close() } catch (_: Exception) {}
        httpServer = null
        httpThread = null
    }

    private fun stopSockets() {
        try { dnsSocket?.close() } catch (_: Exception) {}
        try { httpServer?.close() } catch (_: Exception) {}
        dnsSocket = null
        httpServer = null
        dnsThread = null
        httpThread = null
    }

    private fun startHttp(gatewayIp: String): Boolean {
        val ports = intArrayOf(80, 8080)
        for (port in ports) {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getByName(gatewayIp), port))
                server.soTimeout = 1_000
                httpServer = server
                httpPort = port
                httpThread = thread(name = "SoftApCaptive-HTTP", isDaemon = true) {
                    serveHttp(server)
                }
                return true
            } catch (e: Exception) {
                AppLog.w("SoftApCaptive: bind $gatewayIp:$port failed: ${e.message}")
            }
        }
        return false
    }

    private fun startDns(gatewayIp: String): Boolean {
        return try {
            val sock = DatagramSocket(null)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(InetAddress.getByName(gatewayIp), SoftApCaptivePortalPolicy.DNS_REDIRECT_PORT))
            sock.soTimeout = 1_000
            dnsSocket = sock
            val gatewayBytes = (InetAddress.getByName(gatewayIp) as Inet4Address).address
            dnsThread = thread(name = "SoftApCaptive-DNS", isDaemon = true) {
                serveDns(sock, gatewayBytes)
            }
            true
        } catch (e: Exception) {
            AppLog.w("SoftApCaptive: DNS bind failed: ${e.message}")
            false
        }
    }

    private fun serveHttp(server: ServerSocket) {
        while (running.get()) {
            var client: Socket? = null
            try {
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } ?: continue
                client = socket
                socket.soTimeout = 2_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: ""
                // Drain headers so clients finish the request.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(' ').getOrNull(1)
                val body = if (SoftApCaptivePortalPolicy.shouldAnswer204(path)) {
                    "HTTP/1.0 204 No Content\r\nConnection: close\r\n\r\n"
                } else {
                    "HTTP/1.0 204 No Content\r\nConnection: close\r\n\r\n"
                }
                socket.getOutputStream().write(body.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
            } catch (_: Exception) {
                // ignore per-connection errors
            } finally {
                try { client?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun serveDns(sock: DatagramSocket, gatewayBytes: ByteArray) {
        val buf = ByteArray(512)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val qname = DnsPackets.parseQueryName(packet.data, packet.length) ?: continue
                if (!SoftApCaptivePortalPolicy.shouldSpoofHost(qname)) {
                    // Let non-captive names fail closed on SoftAP (no upstream). Do not forge them.
                    continue
                }
                val response = DnsPackets.buildAResponse(packet.data, packet.length, gatewayBytes) ?: continue
                sock.send(
                    DatagramPacket(response, response.size, packet.address, packet.port)
                )
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
    }

    private fun installIptables(iface: String): Boolean {
        val safe = sanitizeIface(iface) ?: run {
            AppLog.w("SoftApCaptive: refusing iptables for unsafe iface name '$iface'")
            return false
        }
        val dnsPort = SoftApCaptivePortalPolicy.DNS_REDIRECT_PORT
        // DNS: every SoftAP client query hits us; we only answer captive hosts.
        val dnsRedirected = shell(
            "iptables -t nat -C PREROUTING -i $safe -p udp --dport 53 " +
                "-j REDIRECT --to-ports $dnsPort 2>/dev/null || " +
                "iptables -t nat -I PREROUTING -i $safe -p udp --dport 53 " +
                "-j REDIRECT --to-ports $dnsPort"
        )
        if (!dnsRedirected) return false
        if (httpPort != 80) {
            return shell(
                "iptables -t nat -C PREROUTING -i $safe -p tcp --dport 80 " +
                    "-j REDIRECT --to-ports $httpPort 2>/dev/null || " +
                    "iptables -t nat -I PREROUTING -i $safe -p tcp --dport 80 " +
                    "-j REDIRECT --to-ports $httpPort"
            )
        }
        return true
    }

    private fun removeIptables(iface: String?) {
        val safe = sanitizeIface(iface) ?: return
        val dnsPort = SoftApCaptivePortalPolicy.DNS_REDIRECT_PORT
        shell(
            "iptables -t nat -D PREROUTING -i $safe -p udp --dport 53 " +
                "-j REDIRECT --to-ports $dnsPort 2>/dev/null || true"
        )
        if (httpPort != 80) {
            shell(
                "iptables -t nat -D PREROUTING -i $safe -p tcp --dport 80 " +
                    "-j REDIRECT --to-ports $httpPort 2>/dev/null || true"
            )
        }
    }

    private fun sanitizeIface(value: String?): String? =
        value?.takeIf { it.matches(Regex("^[A-Za-z0-9._+-]+$")) }

    private fun shell(cmd: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val code = p.waitFor()
            if (code != 0) {
                AppLog.w("SoftApCaptive: shell exit $code for: ${cmd.take(120)}")
            }
            code == 0
        } catch (e: Exception) {
            AppLog.w("SoftApCaptive: shell failed: ${e.message}")
            false
        }
    }

    /**
     * Minimal DNS QNAME parse / A-record forge for captive probes only.
     * Not a general-purpose DNS stack.
     */
    object DnsPackets {
        fun parseQueryName(data: ByteArray, length: Int): String? {
            if (length < 13) return null
            var i = 12
            val labels = ArrayList<String>()
            while (i < length) {
                val labLen = data[i].toInt() and 0xff
                if (labLen == 0) break
                if (labLen and 0xc0 != 0) return null // compression in question — ignore
                i++
                if (i + labLen > length) return null
                labels.add(String(data, i, labLen, Charsets.US_ASCII))
                i += labLen
            }
            return if (labels.isEmpty()) null else labels.joinToString(".")
        }

        fun buildAResponse(query: ByteArray, length: Int, ipv4: ByteArray): ByteArray? {
            if (length < 12 || ipv4.size != 4) return null
            // Copy question; set QR/AA/RA, ANCOUNT=1
            val out = ByteArray(length + 16)
            System.arraycopy(query, 0, out, 0, length)
            out[2] = (out[2].toInt() or 0x80 or 0x04).toByte() // QR + AA
            out[3] = (out[3].toInt() or 0x80).toByte() // RA
            out[6] = 0
            out[7] = 1 // ANCOUNT
            var o = length
            // Name pointer to offset 12
            out[o++] = 0xc0.toByte()
            out[o++] = 0x0c
            out[o++] = 0
            out[o++] = 1 // TYPE A
            out[o++] = 0
            out[o++] = 1 // CLASS IN
            out[o++] = 0
            out[o++] = 0
            out[o++] = 0
            out[o++] = 30 // TTL 30s
            out[o++] = 0
            out[o++] = 4 // RDLENGTH
            out[o++] = ipv4[0]
            out[o++] = ipv4[1]
            out[o++] = ipv4[2]
            out[o++] = ipv4[3]
            return out.copyOf(o)
        }
    }
}
