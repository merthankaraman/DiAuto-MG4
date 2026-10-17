package com.andrerinas.openheadunit.radio

import android.content.Context
import android.graphics.Bitmap
import com.andrerinas.openheadunit.utils.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Serves the car radio to the MG4 Radio app on the phone over the head unit hotspot: state, DAB logo
 * (the cover art), station lists and play/pause/next/previous/tune. Only peers on the access point's
 * own subnet are answered.
 */
object RadioBridgeServer {
    const val PORT = 30519
    private var server: ServerSocket? = null

    @Synchronized fun start(context: Context) {
        if (server != null) return
        SaicRadio.bind(context)
        server = try { ServerSocket(PORT) } catch (e: Exception) {
            AppLog.w("RadioBridge: could not listen on $PORT: ${e.message}"); return
        }
        val socket = server ?: return
        thread(name = "RadioBridge", isDaemon = true) {
            AppLog.i("RadioBridge: listening on $PORT")
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                thread(name = "RadioBridge-client", isDaemon = true) { client.use { handle(it) } }
            }
        }
    }

    @Synchronized fun stop(context: Context) {
        try { server?.close() } catch (_: Exception) {}
        server = null
        SaicRadio.unbind(context)
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5_000
        if (!fromHotspot(client.inetAddress)) return
        val requestLine = client.getInputStream().bufferedReader().readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val path = parts[1].substringBefore("?")
        val query = parts[1].substringAfter("?", "").split("&").filter { it.contains("=") }
            .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        val out = client.getOutputStream()
        if (!SaicRadio.isReady) return respond(out, 503, "application/json", """{"error":"radio service not bound"}""".toByteArray())
        when (path) {
            "/radio/state" -> json(out, SaicRadio.current()?.let { stationJson(it) } ?: JSONObject())
            "/radio/logo.png" -> {
                val logo = SaicRadio.dabLogo()
                if (logo == null) respond(out, 404, "text/plain", ByteArray(0))
                else respond(out, 200, "image/png", ByteArrayOutputStream().also { logo.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray())
            }
            "/radio/favourites" -> json(out, listJson(listOf(SaicRadio.TYPE_DAB, SaicRadio.TYPE_FM, SaicRadio.TYPE_AM).flatMap { SaicRadio.favourites(it) }))
            "/radio/stations" -> json(out, listJson(SaicRadio.stations(query["type"]?.toIntOrNull() ?: SaicRadio.TYPE_DAB)))
            "/radio/play" -> command(out) { SaicRadio.play() }
            "/radio/pause" -> command(out) { SaicRadio.pause() }
            "/radio/next" -> command(out) { SaicRadio.current()?.let { SaicRadio.next(it.type) } }
            "/radio/previous" -> command(out) { SaicRadio.current()?.let { SaicRadio.previous(it.type) } }
            "/radio/tune" -> command(out) { query["key"]?.let { SaicRadio.tune(it) } }
            else -> respond(out, 404, "text/plain", ByteArray(0))
        }
    }

    private fun command(out: OutputStream, action: () -> Unit) {
        action()
        json(out, JSONObject().put("ok", true))
    }

    private fun stationJson(s: RadioStation) = JSONObject()
        .put("key", s.key).put("name", s.label).put("type", s.type).put("frequencyKhz", s.frequencyKhz)
        .put("playing", s.playing).put("favourite", s.favourite).put("nowPlaying", s.nowPlaying)

    private fun listJson(list: List<RadioStation>) = JSONObject().put("stations", JSONArray(list.map { stationJson(it) }))

    private fun json(out: OutputStream, body: JSONObject) = respond(out, 200, "application/json", body.toString().toByteArray())

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray) {
        val reason = when (status) { 200 -> "OK"; 404 -> "Not Found"; else -> "Unavailable" }
        out.write("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    /** True when [peer] is on the subnet of this unit's access point (ap*), i.e. a phone on the hotspot. */
    private fun fromHotspot(peer: InetAddress): Boolean = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && it.name.startsWith("ap") }
            .flatMap { it.interfaceAddresses }
            .any { a ->
                val local = a.address as? Inet4Address ?: return@any false
                val bits = a.networkPrefixLength.toInt()
                val mask = if (bits == 0) 0 else -1 shl (32 - bits)
                peer is Inet4Address && (toInt(peer) and mask) == (toInt(local) and mask)
            }
    } catch (e: Exception) { false }

    private fun toInt(a: Inet4Address) = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
}
