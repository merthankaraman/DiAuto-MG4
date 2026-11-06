package com.andrerinas.openheadunit.aap

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.Parcel
import com.andrerinas.openheadunit.utils.AppLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Title, cover and play state for the stock SAIC launcher and status bar.
 *
 * For the Android Auto source they read a media session owned by AllGo's
 * `RemoteUIMediaBrowserService`, which AllGo's own Android Auto feeds through a small binder
 * interface after binding with the action `com.allgo.action.aapservice` (CarPlay does the same
 * with its own action; see AllgoCarplay's MirroringControllerService). DiAuto binds the same way
 * and sends the phone's metadata, so the stock launcher shows them as it does for AllGo.
 * MG4 Dash reads DiAuto's own MediaSession and does not need this.
 */
class AllgoMediaCardBridge(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "allgo-media-card") }
    @Volatile private var binder: IBinder? = null
    @Volatile private var bound = false
    @Volatile private var sessionActive = false
    @Volatile private var lastMetadata: Parcelled? = null
    @Volatile private var lastPlaying: Boolean? = null
    @Volatile private var lastPositionSec = 0

    private class Parcelled(val write: (Parcel) -> Unit)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            AppLog.i("AllgoMediaCard: connected to the AllGo media service")
            executor.execute {
                if (sessionActive) sendSessionStatus(true)
                lastMetadata?.let { send(TX_METADATA, it.write) }
                lastPlaying?.let { sendPlayback(it, lastPositionSec) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            AppLog.i("AllgoMediaCard: AllGo media service disconnected")
        }
    }

    /** An Android Auto session is up: bind and tell the media service. */
    fun sessionStarted() {
        sessionActive = true
        if (!bound) {
            bound = try {
                context.bindService(
                    Intent(ACTION_AAP).setComponent(COMPONENT), connection, Context.BIND_AUTO_CREATE,
                )
            } catch (e: Exception) {
                AppLog.w("AllgoMediaCard: bind failed: ${e.message}")
                false
            }
            if (!bound) AppLog.i("AllgoMediaCard: AllGo media service not available")
        }
        executor.execute { sendSessionStatus(true) }
    }

    fun sessionEnded() {
        if (!sessionActive && !bound) return
        sessionActive = false
        lastMetadata = null
        lastPlaying = null
        executor.execute {
            sendSessionStatus(false)
            if (bound) {
                runCatching { context.unbindService(connection) }
                bound = false
                binder = null
            }
        }
    }

    fun metadata(title: String, artist: String, album: String, art: Bitmap?, durationMs: Long) {
        val artBytes = art?.let { bitmap ->
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, ART_JPEG_QUALITY, out)
                out.toByteArray()
            }
        } ?: ByteArray(0)
        // RemoteUIMediaPlaybackMetadata: song, album, artist, albumArtLength, art, playlist, duration, rating.
        val write: (Parcel) -> Unit = { p ->
            p.writeString(title)
            p.writeString(album)
            p.writeString(artist)
            p.writeInt(artBytes.size)
            p.writeInt(artBytes.size)
            p.writeByteArray(artBytes)
            p.writeString(album)
            p.writeInt(durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            p.writeInt(0)
        }
        lastMetadata = Parcelled(write)
        if (sessionActive) executor.execute { send(TX_METADATA, write) }
    }

    fun playback(isPlaying: Boolean, positionSec: Int) {
        lastPlaying = isPlaying
        lastPositionSec = positionSec
        if (sessionActive) executor.execute { sendPlayback(isPlaying, positionSec) }
    }

    private fun sendPlayback(isPlaying: Boolean, positionSec: Int) {
        // RemoteUIMediaPlaybackInfo: state (2 playing, 3 paused), mediaSource, playbackSeconds,
        // shuffle, repeat, repeatOne, elapsedTimeAvailable.
        send(TX_PLAYBACK) { p ->
            p.writeInt(if (isPlaying) STATE_PLAYING else STATE_PAUSED)
            p.writeString(null)
            p.writeInt(positionSec)
            p.writeInt(0)
            p.writeInt(0)
            p.writeInt(0)
            p.writeInt(1)
        }
    }

    private fun sendSessionStatus(active: Boolean) {
        val remote = binder ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(if (active) 1 else 0)
            remote.transact(TX_SESSION_STATUS, data, reply, 0)
            reply.readException()
        } catch (e: Exception) {
            AppLog.w("AllgoMediaCard: session status failed: ${e.message}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** A call with one parcelable argument, as the AIDL proxy writes it. */
    private fun send(code: Int, body: (Parcel) -> Unit) {
        val remote = binder ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(1)
            body(data)
            remote.transact(code, data, reply, 0)
            reply.readException()
        } catch (e: Exception) {
            AppLog.w("AllgoMediaCard: call $code failed: ${e.message}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        private val COMPONENT = ComponentName(
            "com.allgo.remoteui.mediabrowserservice",
            "com.allgo.remoteui.mediabrowserservice.RemoteUIMediaBrowserService",
        )
        private const val ACTION_AAP = "com.allgo.action.aapservice"
        private const val DESCRIPTOR = "com.allgo.remoteui.mediabrowserservice.RemoteUIMediaBrowserServiceInterface"
        private const val TX_METADATA = 1
        private const val TX_PLAYBACK = 2
        private const val TX_SESSION_STATUS = 3
        private const val STATE_PLAYING = 2
        private const val STATE_PAUSED = 3
        private const val ART_JPEG_QUALITY = 85
    }
}
