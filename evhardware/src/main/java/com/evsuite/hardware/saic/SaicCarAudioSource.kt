package com.evsuite.hardware.saic

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import com.evsuite.hardware.AppLogger

/**
 * MG4 A9 / eh32 media stack (`Media_eh32_ll`) does not expose the SWI68
 * `com.saicmotor.service.media` CPAA binders. The amp and the media app follow
 * [AudioManager] focus whose [AudioAttributes] carry `key_car_source_type`.
 *
 * Observed values (same numeric codes as the old `MediaConstants` sources):
 * 5 BT, 6 USB1, 7 USB2, 50 CarPlay (`0x32`), **70 Android Auto (`0x46`)**.
 *
 * Stock media tags BT/USB focus that way; AllGo AA is recognised when the focus
 * holder publishes type 70. [addBundle] is an OEM/SystemApi on this firmware.
 */
object SaicCarAudioSource {

    private const val TAG = "EV_MEDIA"
    const val KEY_CAR_SOURCE_TYPE = "key_car_source_type"
    /** SystemUI / launcher only treat source 70 as AA when this package holds focus. */
    const val PKG_ALLGO_ANDROID_AUTO = "com.allgo.app.androidauto"
    const val SRC_BT = 5
    const val SRC_USB1 = 6
    const val SRC_USB2 = 7
    const val SRC_CARPLAY = 50
    const val SRC_ANDROID_AUTO = 70

    @Volatile
    private var aaFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var aaFocusAudioManager: AudioManager? = null

    @Volatile
    private var aaFocusGranted = false

    private val quietListener = AudioManager.OnAudioFocusChangeListener { change ->
        AppLogger.i(TAG, "car-source AA focus change=$change")
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            aaFocusGranted = false
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            aaFocusGranted = true
        }
    }

    /**
     * Context whose [Context.getPackageName] is AllGo AA when we share
     * `android.uid.system` — SystemUI's media card keys off that package name.
     */
    fun attributionContext(context: Context): Context {
        val app = context.applicationContext
        return try {
            app.createPackageContext(PKG_ALLGO_ANDROID_AUTO, Context.CONTEXT_IGNORE_SECURITY)
        } catch (e: Exception) {
            AppLogger.w(TAG, "AllGo AA package context unavailable: ${e.message}")
            app
        }
    }

    fun audioManager(context: Context): AudioManager? =
        attributionContext(context).getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * MEDIA / music attributes tagged with a car source id so the head-unit
     * audio policy routes the stream (and pauses radio/BT) like the stock apps.
     */
    fun mediaAttributes(sourceType: Int = SRC_ANDROID_AUTO): AudioAttributes {
        val builder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        attachSourceType(builder, sourceType)
        return builder.build()
    }

    /** Attach [KEY_CAR_SOURCE_TYPE] when the OEM Builder API is present. */
    fun attachSourceType(builder: AudioAttributes.Builder, sourceType: Int): AudioAttributes.Builder {
        val bundle = Bundle().apply { putInt(KEY_CAR_SOURCE_TYPE, sourceType) }
        return try {
            val method = builder.javaClass.getMethod("addBundle", Bundle::class.java)
            @Suppress("UNCHECKED_CAST")
            (method.invoke(builder, bundle) as AudioAttributes.Builder)
        } catch (e: Exception) {
            AppLogger.w(TAG, "AudioAttributes.addBundle unavailable: ${e.message}")
            builder
        }
    }

    /**
     * Claim Android Auto as the car media source via AudioFocus (eh32 path).
     * Requests focus under AllGo's package name so SystemUI switches the media
     * card off radio (`mMediaType=6`); safe to call repeatedly.
     */
    @Synchronized
    fun claimAndroidAuto(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val focusCtx = attributionContext(context)
        val am = focusCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        return try {
            val request = if (aaFocusAudioManager === am) {
                aaFocusRequest
            } else {
                aaFocusAudioManager?.let { previousManager ->
                    aaFocusRequest?.let { previousRequest ->
                        runCatching { previousManager.abandonAudioFocusRequest(previousRequest) }
                    }
                }
                null
            } ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(mediaAttributes(SRC_ANDROID_AUTO))
                .setWillPauseWhenDucked(false)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(quietListener)
                .build()
                .also {
                    aaFocusRequest = it
                    aaFocusAudioManager = am
                }
            val result = am.requestAudioFocus(request)
            aaFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            val lastOk = setLastSource(am, SRC_ANDROID_AUTO)
            val current = readCurrentSourceType(am)
            AppLogger.i(
                TAG,
                "claimAndroidAuto focus=$result granted=$aaFocusGranted " +
                    "pkg=${focusCtx.packageName} setLastSource=$lastOk " +
                    "currentSourceType=$current",
            )
            aaFocusGranted || current == SRC_ANDROID_AUTO || lastOk
        } catch (e: Exception) {
            AppLogger.w(TAG, "claimAndroidAuto failed: ${e.message}")
            false
        }
    }

    /**
     * OEM `AudioManager.setLastSource(int)` — SystemUI's media card uses
     * [getLastSource] (70 = AA) when wiring the RemoteUI controller.
     */
    fun setLastSource(audioManager: AudioManager, sourceType: Int): Boolean {
        return try {
            val method = audioManager.javaClass.getMethod("setLastSource", Int::class.javaPrimitiveType)
            method.invoke(audioManager, sourceType)
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "setLastSource($sourceType) unavailable: ${e.message}")
            false
        }
    }

    @Synchronized
    fun releaseAndroidAuto(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val request = aaFocusRequest ?: return
        val am = aaFocusAudioManager
            ?: audioManager(context)
            ?: return
        runCatching { am.abandonAudioFocusRequest(request) }
        aaFocusRequest = null
        aaFocusAudioManager = null
        aaFocusGranted = false
    }

    fun isAndroidAuto(context: Context?): Boolean {
        if (aaFocusGranted) return true
        val ctx = context ?: return false
        val am = audioManager(ctx) ?: return false
        if (readCurrentSourceType(am) == SRC_ANDROID_AUTO) return true
        return readLastSource(am) == SRC_ANDROID_AUTO
    }

    fun readLastSource(audioManager: AudioManager): Int? {
        return try {
            val method = audioManager.javaClass.getMethod("getLastSource")
            method.invoke(audioManager) as? Int
        } catch (_: Exception) {
            null
        }
    }

    /** OEM `AudioManager.getCurrentSource()` → bundle [KEY_CAR_SOURCE_TYPE], or null. */
    fun readCurrentSourceType(audioManager: AudioManager): Int? {
        return try {
            val method = audioManager.javaClass.getMethod("getCurrentSource")
            val attrs = method.invoke(audioManager) as? AudioAttributes ?: return null
            val bundleMethod = attrs.javaClass.getMethod("getBundle")
            val bundle = bundleMethod.invoke(attrs) as? Bundle ?: return null
            if (!bundle.containsKey(KEY_CAR_SOURCE_TYPE)) return null
            bundle.getInt(KEY_CAR_SOURCE_TYPE)
        } catch (_: Exception) {
            null
        }
    }
}
