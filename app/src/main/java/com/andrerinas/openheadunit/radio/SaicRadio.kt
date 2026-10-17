package com.andrerinas.openheadunit.radio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import com.andrerinas.openheadunit.utils.AppLog

/** One station as SAIC's radio service describes it (com.saicmotor.sdk.radio.bean.RadioBean). */
data class RadioStation(
    val name: String,
    val type: Int,
    val frequencyKhz: Int,
    val state: Int,
    val favourite: Boolean,
    val ensembleId: Int,
    val serviceId: Long,
    val secondaryServiceId: Int,
    val frequencyIndex: Int,
    val nowPlaying: String,
) {
    val isDab get() = type == SaicRadio.TYPE_DAB
    val playing get() = state == SaicRadio.STATE_PLAYING

    /** Stable id the phone app uses to ask for this station again. */
    val key get() = if (isDab) "dab:$frequencyIndex:$serviceId:$ensembleId:$secondaryServiceId" else "$type:$frequencyKhz"

    val label get() = name.trim().ifEmpty {
        if (type == SaicRadio.TYPE_AM) "$frequencyKhz kHz" else "%.1f FM".format(frequencyKhz / 1000f)
    }
}

/**
 * Client for SAIC's radio service (com.saicmotor.service.radio, IRadioAppService), the interface the
 * car's own radio and cluster use. Transaction codes and parcel layouts are read from the R71
 * firmware's radio SDK; MG4Control binds the same service the same way.
 */
object SaicRadio {
    const val TYPE_AM = 1
    const val TYPE_FM = 2
    const val TYPE_DAB = 4
    const val STATE_PLAYING = 1

    private const val PKG = "com.saicmotor.service.radio"
    private const val ACTION = "com.saicmotor.service.radio.radioservice"
    private const val DESCRIPTOR = "com.saicmotor.sdk.radio.IRadioAppService"
    private const val TX_GET_COLLECTION_LIST = 7
    private const val TX_TUNE = 8
    private const val TX_TUNE_DAB = 9
    private const val TX_NEXT = 13
    private const val TX_PREVIOUS = 14
    private const val TX_SET_MUTED = 18
    private const val TX_GET_CURRENT = 19
    private const val TX_GET_LAST_MODE = 20
    private const val TX_GET_RADIO_LIST = 22
    private const val TX_PLAY = 27
    private const val TX_GET_DAB_LOGO = 52

    @Volatile private var binder: IBinder? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service }
        override fun onServiceDisconnected(name: ComponentName) { binder = null }
    }

    @Synchronized fun bind(context: Context) {
        if (bound) return
        bound = try {
            context.applicationContext.bindService(Intent(ACTION).setPackage(PKG), connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            AppLog.w("SaicRadio: bind failed: ${e.message}"); false
        }
        AppLog.i("SaicRadio: radio service bind requested=$bound")
    }

    @Synchronized fun unbind(context: Context) {
        if (!bound) return
        try { context.applicationContext.unbindService(connection) } catch (_: Exception) {}
        bound = false
        binder = null
    }

    val isReady get() = binder != null

    fun current(): RadioStation? = call(TX_GET_CURRENT) { reply -> if (reply.readInt() != 0) readStation(reply) else null }

    fun favourites(type: Int): List<RadioStation> = call(TX_GET_COLLECTION_LIST, { writeInt(type) }) { readStationList(it) }.orEmpty()

    fun stations(type: Int): List<RadioStation> = call(TX_GET_RADIO_LIST, { writeInt(type) }) { readStationList(it) }.orEmpty()

    fun dabLogo(): Bitmap? = call(TX_GET_DAB_LOGO) { reply -> if (reply.readInt() != 0) Bitmap.CREATOR.createFromParcel(reply) else null }

    /** As the launcher's radio widget (RadioOptionManager.play()): re-tune the last-mode station. */
    fun play(): Boolean {
        val last = call(TX_GET_LAST_MODE) { reply -> if (reply.readInt() != 0) readStation(reply) else null }
            ?: listOf(TYPE_DAB, TYPE_FM, TYPE_AM).firstNotNullOfOrNull { stations(it).firstOrNull() }
        return if (last != null) tune(last.key) else call(TX_PLAY) { it.readInt() != 0 } ?: false
    }

    /** As the launcher's radio widget (RadioOptionManager.stop(type) -> setMuted(false, type)). */
    fun pause() {
        val type = current()?.type ?: return
        call(TX_SET_MUTED, { writeInt(0); writeInt(type) }) { }
    }
    fun next(type: Int) { call(TX_NEXT, { writeInt(type) }) { } }
    fun previous(type: Int) { call(TX_PREVIOUS, { writeInt(type) }) { } }

    /** Tunes to a station key produced by [RadioStation.key]. */
    fun tune(key: String): Boolean {
        val parts = key.split(":")
        return try {
            if (parts[0] == "dab") {
                call(TX_TUNE_DAB, {
                    writeInt(parts[1].toInt()); writeLong(parts[2].toLong()); writeInt(parts[3].toInt()); writeInt(parts[4].toInt())
                }) { } != null
            } else {
                call(TX_TUNE, { writeInt(parts[0].toInt()); writeInt(parts[1].toInt()) }) { } != null
            }
        } catch (e: Exception) {
            AppLog.w("SaicRadio: bad station key $key: ${e.message}"); false
        }
    }

    private fun readStationList(reply: Parcel): List<RadioStation> {
        val count = reply.readInt()
        if (count < 0) return emptyList()
        return (0 until count).mapNotNull { if (reply.readInt() != 0) readStation(reply) else null }
    }

    /** Mirrors RadioBean(Parcel); every field is consumed so lists stay aligned. */
    private fun readStation(p: Parcel): RadioStation {
        p.readByte() // enabled
        val name = p.readString().orEmpty()
        p.readByte() // RDS
        p.readString() // cover
        val frequencyKhz = p.readInt()
        val type = p.readInt()
        val state = p.readInt()
        val favourite = p.readByte() != 0.toByte()
        val subStation = p.readString().orEmpty()
        p.readInt() // rssi
        val ensembleId = p.readInt()
        val serviceId = p.readLong()
        p.readInt() // pty
        val secondaryServiceId = p.readInt()
        val frequencyIndex = p.readInt()
        val serviceName = p.createCharArray()?.let { String(it) }.orEmpty()
        p.createCharArray() // ensemble name
        val dlPlus = p.createCharArray()?.let { String(it) }.orEmpty()
        val dls = p.createCharArray()?.let { String(it) }.orEmpty()
        p.readByte() // isTitle
        p.createTypedArrayList(Bitmap.CREATOR) // slideshow
        readSubRadios(p)
        p.readParcelable<Parcelable>(SaicRadio::class.java.classLoader) // RadioMetadata
        return RadioStation(
            name = serviceName.trim('\u0000', ' ').ifEmpty { name.ifEmpty { subStation } },
            type = type, frequencyKhz = frequencyKhz, state = state, favourite = favourite,
            ensembleId = ensembleId, serviceId = serviceId, secondaryServiceId = secondaryServiceId,
            frequencyIndex = frequencyIndex,
            nowPlaying = dls.trim('\u0000', ' ').ifEmpty { dlPlus.trim('\u0000', ' ') },
        )
    }

    /** DabSubRadioBean list: count, then per item a non-null marker and seven fields. */
    private fun readSubRadios(p: Parcel) {
        val count = p.readInt()
        repeat(maxOf(count, 0)) {
            if (p.readInt() != 0) {
                p.readString(); p.readString(); p.readInt(); p.readLong(); p.readInt(); p.readInt(); p.readByte()
            }
        }
    }

    private fun <T> call(code: Int, write: Parcel.() -> Unit = {}, read: (Parcel) -> T): T? {
        val b = binder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.write()
            b.transact(code, data, reply, 0)
            reply.readException()
            read(reply)
        } catch (e: Exception) {
            AppLog.w("SaicRadio: call $code failed: ${e.message}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
