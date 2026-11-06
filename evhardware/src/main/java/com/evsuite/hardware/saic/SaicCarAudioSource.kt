package com.evsuite.hardware.saic

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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

    @Volatile
    private var lastClaimElapsedMs = 0L

    /**
     * Whether [claimAndroidAuto] ran in the last [windowMs]. A focus loss the app sees right then
     * comes from that claim taking over its own other requests, not from the car or the user.
     */
    fun claimedWithin(windowMs: Long): Boolean =
        SystemClock.elapsedRealtime() - lastClaimElapsedMs < windowMs

    /**
     * Every focus change of the car-source request. Once the phone's own requests are released,
     * this is the only focus the app holds, so a source the car (or a launcher) picks shows up
     * here and nowhere else.
     */
    @Volatile
    var onFocusChange: ((Int) -> Unit)? = null

    private val quietListener = AudioManager.OnAudioFocusChangeListener { change ->
        AppLogger.i(TAG, "car-source AA focus change=$change")
        onFocusChange?.invoke(change)
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

    @Volatile
    private var allgoAudioManager: AudioManager? = null

    /**
     * An AudioManager whose focus requests carry AllGo's package name. A package context alone is
     * not enough: AudioManager sends [Context.getOpPackageName], which createPackageContext keeps as
     * this app's, so the car's MEDIA.SOURCE saw `com.drivehub.diauto.mg4` and the stock launcher
     * saved source type -1 (not Android Auto) and sent the steering-wheel arrows to the radio.
     * AllGo shares the system uid with this app, so AudioService accepts the name.
     */
    fun audioManager(context: Context): AudioManager? {
        allgoAudioManager?.let { return it }
        val base = attributionContext(context)
        val am = try {
            val identity = object : ContextWrapper(base) {
                override fun getPackageName(): String = PKG_ALLGO_ANDROID_AUTO
                override fun getOpPackageName(): String = PKG_ALLGO_ANDROID_AUTO
                override fun getApplicationContext(): Context = this
            }
            AudioManager::class.java.getConstructor(Context::class.java).newInstance(identity)
        } catch (e: Exception) {
            AppLogger.w(TAG, "AllGo-named AudioManager unavailable (${e.message}); using this app's name")
            base.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }
        allgoAudioManager = am
        return am
    }

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
        // One request on one AudioManager for the whole process: a new client on every claim made
        // the previous one lose focus (change=-1) to this app itself.
        val am = aaFocusAudioManager
            ?: audioManager(context)
            ?: return false
        return try {
            val request = aaFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(mediaAttributes(SRC_ANDROID_AUTO))
                .setWillPauseWhenDucked(false)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(quietListener)
                .build()
            aaFocusRequest = request
            aaFocusAudioManager = am
            lastClaimElapsedMs = SystemClock.elapsedRealtime()
            val result = am.requestAudioFocus(request)
            aaFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            val lastOk = setLastSource(am, SRC_ANDROID_AUTO)
            val current = readCurrentSourceType(am)
            AppLogger.i(
                TAG,
                "claimAndroidAuto focus=$result granted=$aaFocusGranted " +
                    "setLastSource=$lastOk " +
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
