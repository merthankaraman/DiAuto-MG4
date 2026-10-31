package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.BuildConfig
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket

/**
 * Who may open a wireless Android Auto session: only a phone that joined the car's own access
 * point. The listener can be up on every interface before the access point is known, which also
 * covers the car's Wi-Fi client (a home network) and the telematics link; without this check any
 * device there could pose as a phone, put its picture on the screen and ask for the microphone.
 */
object WirelessPeerPolicy {
    /** Access point interface names on the head units DiAuto runs on (ap0 on the MG4). */
    private val AP_PREFIXES = listOf("ap", "softap", "swlan")

    fun allowed(socket: Socket): Boolean {
        // Phone builds are desk tests over any network.
        if (BuildConfig.FLAVOR != "car") return true
        val local = socket.localAddress ?: return false
        if (local !is Inet4Address || socket.inetAddress !is Inet4Address) return false
        val iface = try { NetworkInterface.getByInetAddress(local)?.name } catch (_: Exception) { null } ?: return false
        return AP_PREFIXES.any { iface.startsWith(it) }
    }

    fun interfaceOf(socket: Socket): String =
        try { NetworkInterface.getByInetAddress(socket.localAddress)?.name } catch (_: Exception) { null } ?: "?"
}
