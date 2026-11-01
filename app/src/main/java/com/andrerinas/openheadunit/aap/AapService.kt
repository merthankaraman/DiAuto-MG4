package com.andrerinas.openheadunit.aap

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.app.BootCompleteReceiver
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.ToastUtils
import com.andrerinas.openheadunit.aap.protocol.messages.NightModeEvent
import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import com.andrerinas.openheadunit.connection.CommManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
import com.andrerinas.openheadunit.connection.UsbAccessoryMode
import com.andrerinas.openheadunit.connection.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.UsbReceiver
import com.andrerinas.openheadunit.location.GpsLocationService
import com.andrerinas.openheadunit.utils.LocaleHelper
import com.andrerinas.openheadunit.utils.LogExporter
import com.andrerinas.openheadunit.utils.NightModeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.view.View
import android.view.WindowManager
import android.media.AudioManager
import com.andrerinas.openheadunit.utils.HotspotManager
import com.andrerinas.openheadunit.connection.NativeAaHandshakeManager
import com.andrerinas.openheadunit.connection.SoftApCredentialsProvider
import com.andrerinas.openheadunit.main.BackgroundNotification
import android.annotation.SuppressLint
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.protoUint32ToLong
import com.evsuite.hardware.saic.SaicCarAudioSource
import com.evsuite.hardware.saic.SaicMediaPlayer
import com.evsuite.hardware.saic.SaicRadio
import java.net.ServerSocket

/**
 * Top-level foreground service that manages the Android Auto connection lifecycle.
 *
 * Responsibilities:
 * - Manages the [CommManager] connection state machine (USB and WiFi)
 * - Drives [AapProjectionActivity] via intents and connection state flow
 * - Runs the [WirelessServer] the phone connects to over the car hotspot
 * - Keeps a foreground notification updated to reflect the current connection state
 * - Manages car mode, night mode, media session, and GPS location service
 *
 * Connection types:
 * - **USB**: [UsbReceiver] detects attach → [checkAlreadyConnectedUsb] → [connectUsbWithRetry]
 * - **Wireless**: the Bluetooth handshake sends the car hotspot to the phone, and [WirelessServer] accepts its socket
 */
class AapService : Service(), UsbReceiver.Listener {

    // SupervisorJob prevents a child coroutine failure from cancelling the whole scope
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stationRestoreJob: Job? = null

    private lateinit var uiModeManager: UiModeManager
    private lateinit var usbReceiver: UsbReceiver
    private var nightModeManager: NightModeManager? = null
    // The car hotspot's credential source for native Android Auto.
    private var softApCredentialsProvider: SoftApCredentialsProvider? = null
    private var nativeAaHandshakeManager: NativeAaHandshakeManager? = null
    // @Volatile: the handshake can now ask for a repair from Dispatchers.IO, so this is no
    // longer read only from Main.
    @Volatile private var wirelessServer: WirelessServer? = null
    /**
     * SoftAP / P2P IPv4 the wireless TCP server should bind to (DiPlay-style), or null for
     * the wildcard used by helper / head-unit-server modes before Native AA credentials exist.
     */
    @Volatile private var wirelessBindHost: String? = null
    // Rebuild bookkeeping for WirelessServerRestartPolicy. The handshake asks about every 4s while a
    // phone keeps arriving, so without a bound a port that cannot bind becomes a rebuild loop.
    private var lastWirelessRebuildAtMs = 0L
    private var wirelessRebuildsInWindow = 0
    private var wirelessRebuildWindowStartedAtMs = 0L
    private var mediaSession: MediaSessionCompat? = null

    private inline fun <T> safeMediaSessionCall(crossinline block: (MediaSessionCompat) -> T): T? {
        if (isDestroying) return null
        val session = mediaSession ?: return null
        return try {
            block(session)
        } catch (e: Exception) {
            // Catching binder death: DeadObjectException or DeadSystemException
            AppLog.e("MediaSession call failed (Binder dead?): ${e.message}")
            null
        }
    }
    private var permanentFocusRequest: android.media.AudioFocusRequest? = null

    private var lastAaMediaMetadata: MediaPlayback.MediaMetaData? = null
    /** Title, cover and play state for the stock SAIC launcher (MG4 Dash reads the MediaSession). */
    private val allgoMediaCard by lazy { AllgoMediaCardBridge(this) }
    private var lastAaPlaybackPositionMs: Long = 0L
    private var lastAaPlaybackIsPlaying: Boolean? = null
    private var mediaSessionIsPlaying = false
    private var mediaMetadataDecodeJob: Job? = null
    /** Decoded on a background thread in [scheduleApplyAaMediaMetadata]; reused for notification updates on position ticks. */
    private var cachedAaAlbumArtBitmap: Bitmap? = null
    private var settingsPrefs: SharedPreferences? = null
    private val settings: Settings by lazy { App.provide(this).settings }

    /**
     * TCP port of the wireless server and of the address the phone is told to dial. Chosen automatically on the
     * car from its real firewall rules -- see CarNetworkQuirks.
     */
    val wirelessPort: Int
        get() = com.andrerinas.openheadunit.connection.CarNetworkQuirks.autoWirelessPort()
    private val mediaNotification by lazy { BackgroundNotification(this) }

    private val settingsPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Settings.KEY_SYNC_MEDIA_SESSION_AA_METADATA) {
                serviceScope.launch(Dispatchers.Main) {
                    refreshMediaSessionMetadataForPrefsChange()
                }
            }

            if (key == Settings.KEY_LOG_SOURCE || key == Settings.KEY_LOG_LEVEL || key == Settings.KEY_LOG_CAPTURE_ENABLED) {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        syncLogBackendState()
                    } catch (e: Exception) {
                        AppLog.e("LogExporter: failed to sync state", e)
                    }
                }
            }

            if (key == Settings.KEY_MEDIA_VOLUME_OFFSET || key == Settings.KEY_ASSISTANT_VOLUME_OFFSET || key == Settings.KEY_NAVIGATION_VOLUME_OFFSET) {
                serviceScope.launch(Dispatchers.Main) {
                    commManager.updateAudioGains()
                }
            }

            // "Wireless Android Auto" switched in the settings: bring the wireless route up or down.
            if (key == Settings.KEY_CONNECTION_MODES && wirelessSettingChanged() && !commManager.isConnected) {
                serviceScope.launch(Dispatchers.Main) { initWifiMode(force = true) }
            }
        }

    private fun syncLogBackendState() {
        AppLog.init(settings, this@AapService)

        if (settings.logSource == Settings.LogSource.APPLOG_FILE) {
            if (LogExporter.isCapturing) {
                LogExporter.stopCapture()
                AppLog.d("LogExporter: stopped because logSource=APPLOG_FILE")
            }
            return
        }

        val newLogLevel = settings.exporterLogLevel
        val exporterCaptureEnabled = settings.exporterCaptureEnabled
        val isCapturing = LogExporter.isCapturing
        val currentLogLevel = LogExporter.currentLevel

        if (!exporterCaptureEnabled || newLogLevel == LogExporter.LogLevel.SILENT) {
            if (isCapturing) {
                LogExporter.stopCapture()
                AppLog.d("LogExporter: stopped (enabled=$exporterCaptureEnabled, level=${newLogLevel.name})")
            }
        } else if (!isCapturing || currentLogLevel != newLogLevel) {
            LogExporter.startCapture(this@AapService, newLogLevel)
            AppLog.d("LogExporter: started with level ${newLogLevel.name}")
        }
    }

    /**
     * Set to `true` before calling [stopSelf] or entering [onDestroy] to suppress any
     * flow observers that would otherwise update the already-dismissed notification.
     */
    private var isDestroying = false
    private var hasEverConnected = false
    // Set by every Connected, cleared by the Disconnected that follows. onConnected() runs on each
    // TCP connect (it pauses the radio, takes the WifiLock, starts GPS…), so its teardown has to
    // run for a connection that failed before TransportStarted too, not only after a session.
    private var connectedSinceLastDisconnect = false
    private var accessoryHandshakeFailures = 0
    private var wifiLock: WifiManager.WifiLock? = null

    private var wirelessInitialized = false
    private var activeWirelessEnabled: Boolean? = null

    private fun wirelessEnabled(): Boolean =
        App.provide(this).settings.connectionModes.contains(Settings.ConnectionMode.WIFI)

    private fun wirelessSettingChanged(): Boolean = activeWirelessEnabled != wirelessEnabled()

    /**
     * Partial wake lock acquired when the service starts from boot/screen-on.
     * Keeps the CPU active while the head unit runs without ACC, making the
     * service harder for MediaTek's background power saving to kill.
     */
    private var bootWakeLock: PowerManager.WakeLock? = null

    /**
     * Runtime-registered receiver for MEDIA_BUTTON intents.
     * Unlike manifest-registered receivers, runtime receivers are NOT affected by
     * Android 8+ implicit broadcast restrictions — this is a critical difference
     * that makes steering wheel controls work on China headunits.
     */
    private val mediaButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_MEDIA_BUTTON == intent.action) {
                AppLog.i("Runtime MEDIA_BUTTON receiver fired")
                safeMediaSessionCall {
                    MediaButtonReceiver.handleIntent(it, intent)
                }
            }
        }
    }

    /**
     * Guards against duplicate [UsbAccessoryMode.connectAndSwitch] calls AND duplicate
     * [connectUsbWithRetry] calls for devices already in accessory mode.
     *
     * Set to `true` synchronously on the main thread before launching any background
     * USB connect/switch coroutine. Checked in [checkAlreadyConnectedUsb] to prevent
     * multiple concurrent connection attempts on the same device.
     * Cleared in the coroutine's finally block, or on disconnect.
     */
    private val isSwitchingToAccessory = AtomicBoolean(false)

    /**
     * True while a forced USB attach is tearing down a wireless session so AOA can proceed.
     * [onDisconnected] must not re-init Native AA / SoftAP in that window — that was racing
     * the USB connect after v1.6 made wireless handoff succeed more often.
     */
    @Volatile
    private var preemptingWirelessForUsb = false

    /**
     * Set when the phone sends VIDEO_FOCUS_NATIVE (user tapped "Exit" in AA).
     * Suppresses [scheduleReconnectIfNeeded] so we don't try to reconnect to a
     * stale dongle that hasn't re-enumerated yet.
     * Cleared on USB detach (dongle reset complete) or on fresh USB attach.
     */
    @Volatile
    private var userExitedAA = false
    @Volatile private var userExitCooldownUntil = 0L

    /** In-flight poke that may be waiting out [USER_EXIT_COOLDOWN_MS]. */
    private var nativeAaPokeJob: Job? = null
    private var phoneReconnectJob: Job? = null
    @Volatile private var lastWatchdogPokeMs = 0L
    @Volatile private var lastPhoneBtConnectMs = 0L

    /** Sticky SAIC SRC_AA claim while Wi‑Fi AA music is the active session. */
    private var saicAaMediaClaimJob: Job? = null

    /**
     * Automatic reconnect / discovery restarts after unexpected disconnects.
     * Reset on a successful handshake so a later mid-drive drop can retry again.
     */
    @Volatile private var automaticReconnectAttempts = 0

    private val commManager get() = App.provide(this).commManager

    fun updateMediaSessionState(isPlaying: Boolean) {
        mediaSessionIsPlaying = isPlaying
        var actions = PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_PAUSE

        var state: Int

        if (isPlaying) {
            state = PlaybackStateCompat.STATE_PLAYING
            actions = actions or PlaybackStateCompat.ACTION_PAUSE
        } else {
            state = PlaybackStateCompat.STATE_STOPPED
            actions = actions or PlaybackStateCompat.ACTION_PLAY
        }

        safeMediaSessionCall {
            it.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(state, lastAaPlaybackPositionMs, if (isPlaying) 1.0f else 0.0f)
                    .setActions(actions)
                    .build()
            )
        }
        if (lastAaMediaMetadata != null) {
            allgoMediaCard.playback(isPlaying, (lastAaPlaybackPositionMs / 1000L).toInt())
        }
        AppLog.d(
            "MediaSession: State updated to ${if (isPlaying) "PLAYING" else "STOPPED"}, positionMs=$lastAaPlaybackPositionMs"
        )
    }

    private fun applyPlaceholderMediaMetadata() {
        val aa = getString(R.string.video) // "Android Auto"
        safeMediaSessionCall {
            it.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, aa)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.media_session_aa_status_placeholder))
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, aa)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, aa)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, getString(R.string.media_session_aa_status_placeholder))
                    .build()
            )
        }
    }

    private fun refreshMediaSessionMetadataForPrefsChange() {
        if (isDestroying) return
        val sync = App.provide(this).settings.syncMediaSessionWithAaMetadata
        if (!sync) {
            applyPlaceholderMediaMetadata()
            cachedAaAlbumArtBitmap = null
            mediaNotification.cancel()
        } else {
            val last = lastAaMediaMetadata
            if (last != null) {
                scheduleApplyAaMediaMetadata(last)
            } else {
                applyPlaceholderMediaMetadata()
                cachedAaAlbumArtBitmap = null
                mediaNotification.cancel()
            }
        }
    }

    private fun onAaMediaMetadataFromPhone(meta: MediaPlayback.MediaMetaData) {
        if (isDestroying) return
        lastAaMediaMetadata = meta
        if (!App.provide(this).settings.syncMediaSessionWithAaMetadata) return
        // Avoid showing a previous track's art with new title/artist until decode finishes.
        cachedAaAlbumArtBitmap = null
        scheduleApplyAaMediaMetadata(meta)
    }

    private fun onAaPlaybackStatusFromPhone(status: MediaPlayback.MediaPlaybackStatus) {
        if (isDestroying) return
        if (status.hasPlaybackSeconds()) {
            lastAaPlaybackPositionMs = status.playbackSeconds.protoUint32ToLong() * 1000L
        }
        val isPlayingFromStatus = resolveIsPlayingFromStatus(status)
        lastAaPlaybackIsPlaying = isPlayingFromStatus
        mediaSessionIsPlaying = isPlayingFromStatus

        if (!App.provide(this).settings.syncMediaSessionWithAaMetadata) return
        updateMediaSessionState(isPlayingFromStatus)
        lastAaMediaMetadata?.let { updateMediaNotification(it) }
    }

    private fun resolveIsPlayingFromStatus(status: MediaPlayback.MediaPlaybackStatus): Boolean {
        if (!status.hasState()) return lastAaPlaybackIsPlaying ?: mediaSessionIsPlaying
        return when (status.state) {
            MediaPlayback.MediaPlaybackStatus.State.PLAYING -> true
            MediaPlayback.MediaPlaybackStatus.State.STOPPED,
            MediaPlayback.MediaPlaybackStatus.State.PAUSED -> false
        }
    }

    private fun updateMediaNotification(meta: MediaPlayback.MediaMetaData) {
        if (!App.provide(this).settings.syncMediaSessionWithAaMetadata) return
        mediaNotification.notify(
            metadata = meta,
            playbackSeconds = lastAaPlaybackPositionMs / 1000L,
            isPlaying = lastAaPlaybackIsPlaying ?: mediaSessionIsPlaying,
            albumArtBitmap = cachedAaAlbumArtBitmap
        )
    }

    private fun scheduleApplyAaMediaMetadata(meta: MediaPlayback.MediaMetaData) {
        mediaMetadataDecodeJob?.cancel()
        mediaMetadataDecodeJob = serviceScope.launch(Dispatchers.Default) {
            val bytes = if (meta.hasAlbumArt() && !meta.albumArt.isEmpty) meta.albumArt.toByteArray() else null
            val bitmap = bytes?.let { decodeAlbumArt(it) }
            if (!isActive) return@launch
            withContext(Dispatchers.Main) {
                if (isDestroying) return@withContext
                if (!App.provide(this@AapService).settings.syncMediaSessionWithAaMetadata) return@withContext
                // Drop stale decode results if newer metadata arrived while we were decoding.
                if (lastAaMediaMetadata !== meta) return@withContext
                cachedAaAlbumArtBitmap = bitmap
                applyAaMediaMetadataToSession(meta, bitmap)
                updateMediaNotification(meta)
            }
        }
    }

    private fun decodeAlbumArt(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                opts.inJustDecodeBounds = false
                opts.inSampleSize = 1
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
            var sampleSize = 1
            val maxDim = 720
            while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }
            opts.inJustDecodeBounds = false
            opts.inSampleSize = sampleSize
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun notifyAllgoRelay() {
        // Not settings.bluetoothAddress: that is the car's own address, and the car's Settings would
        // cut the phone's hands-free link for not being it.
        AllgoRelayNotifier.sessionStarted(
            this,
            settings.autoStartBluetoothDeviceName.takeIf { it.isNotBlank() },
            settings.autoStartBluetoothDeviceMacs.firstOrNull(),
        )
    }

    private fun applyAaMediaMetadataToSession(meta: MediaPlayback.MediaMetaData, albumArt: Bitmap?) {
        val session = mediaSession ?: return
        val title = when {
            meta.hasSong() && meta.song.isNotBlank() -> meta.song
            else -> getString(R.string.video)
        }
        val artist = when {
            meta.hasArtist() && meta.artist.isNotBlank() -> meta.artist
            else -> getString(R.string.media_session_aa_status_placeholder)
        }
        val aaLabel = getString(R.string.video)
        val b = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
            .putString(
                MediaMetadataCompat.METADATA_KEY_ALBUM,
                if (meta.hasAlbum() && meta.album.isNotBlank()) meta.album else aaLabel,
            )
        if (meta.hasDurationSeconds()) {
            val durationSec = meta.durationSeconds.protoUint32ToLong()
            if (durationSec > 0L) {
                b.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationSec * 1000L)
            }
        }
        if (albumArt != null) {
            b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
        }
        safeMediaSessionCall { it.setMetadata(b.build()) }
        allgoMediaCard.sessionStarted()
        // The relay loses its state when it restarts; every track change tells it again.
        notifyAllgoRelay()
        allgoMediaCard.metadata(
            title, artist,
            if (meta.hasAlbum() && meta.album.isNotBlank()) meta.album else aaLabel,
            albumArt,
            if (meta.hasDurationSeconds()) meta.durationSeconds.protoUint32ToLong() * 1000L else 0L,
        )
        allgoMediaCard.playback(lastAaPlaybackIsPlaying ?: mediaSessionIsPlaying, (lastAaPlaybackPositionMs / 1000L).toInt())
    }

    // Receives ACTION_REQUEST_NIGHT_MODE_UPDATE broadcasts sent by the key-binding handler
    // when the user presses the night-mode toggle key.
    private val nightModeUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_REQUEST_NIGHT_MODE_UPDATE) {
                AppLog.i("Received request to resend night mode state")
                nightModeManager?.resendCurrentState()
            }
        }
    }

    private val sensorRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_REFRESH_SENSORS) {
                AppLog.i("AapService: Received request to refresh all sensors")
                // Re-send current states
                nightModeManager?.resendCurrentState()
            } else if (intent.action == ACTION_RESTART_AUDIO) {
                AppLog.i("AapService: Received request to restart audio")
                commManager.restartAudio()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Wake detection for hibernate/quick boot head units
    // -------------------------------------------------------------------------

    /**
     * Timestamp (elapsedRealtime) when the screen last turned off.
     * Used to measure how long the device was asleep and distinguish a normal
     * screen timeout from a hibernate wake (car ACC off → on).
     */
    private var screenOffTimestamp = 0L

    /**
     * Debounce: last time [onHibernateWake] actually ran.
     * Prevents double-triggering when both BootCompleteReceiver and this dynamic
     * receiver fire for the same wake event.
     */
    private var lastWakeHandledTimestamp = 0L

    /**
     * Runtime-registered receiver for system wake/boot/power/screen events.
     *
     * On Chinese head units with Quick Boot (hibernate/resume), standard broadcasts
     * like BOOT_COMPLETED and USB_DEVICE_ATTACHED often don't fire after waking.
     * This receiver serves two purposes:
     *
     * 1. **Diagnostic logging:** Logs every received system event with the
     *    "WakeDetect:" prefix so users can export logs and we can see which
     *    broadcasts their specific head unit sends (or doesn't send) on wake.
     *
     * 2. **Universal wake detection:** Uses ACTION_SCREEN_ON (which fires on ALL
     *    devices after hibernate) combined with screen-off duration tracking to
     *    detect hibernate wakes and trigger auto-start — regardless of which OEM
     *    boot/ACC intents the device sends.
     *
     * ACTION_SCREEN_ON can only be received by dynamically registered receivers,
     * not manifest-declared ones — that's why the manifest-based BootCompleteReceiver
     * can't catch it and we need this service-based approach.
     */
    private val wakeDetectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return

            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffTimestamp = SystemClock.elapsedRealtime()
                    AppLog.i("WakeDetect: SCREEN_OFF")
                }
                Intent.ACTION_SCREEN_ON -> {
                    val now = SystemClock.elapsedRealtime()
                    val offDuration = if (screenOffTimestamp > 0) now - screenOffTimestamp else -1L
                    val offSec = if (offDuration >= 0) offDuration / 1000 else -1L
                    screenOffTimestamp = 0

                    AppLog.i("WakeDetect: SCREEN_ON (screen was off for ${offSec}s)")

                    val settings = App.provide(this@AapService).settings

                    // "Start on screen on" — triggers on every SCREEN_ON, designed for
                    // head units that never truly power off (quick boot / always-on).
                    if (settings.autoStartOnScreenOn) {
                        AppLog.i("WakeDetect: start-on-screen-on enabled, triggering auto-start")
                        onScreenOnAutoStart()
                    } else if (offDuration > HIBERNATE_WAKE_THRESHOLD_MS) {
                        // Hibernate wake detection — only for longer sleeps
                        AppLog.i("WakeDetect: hibernate wake detected (off for ${offSec}s > ${HIBERNATE_WAKE_THRESHOLD_MS / 1000}s threshold)")
                        onHibernateWake("SCREEN_ON after ${offSec}s sleep")
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    AppLog.i("WakeDetect: USER_PRESENT")
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    AppLog.i("WakeDetect: POWER_CONNECTED")
                    // On some head units, power connected = ACC on = car started.
                    // Only check USB (don't launch UI) since this could also be a
                    // charger being plugged in on a phone/tablet.
                    onPossibleWake("POWER_CONNECTED")
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    AppLog.i("WakeDetect: POWER_DISCONNECTED")
                }
                Intent.ACTION_SHUTDOWN -> {
                    AppLog.i("WakeDetect: SHUTDOWN (system shutting down, not hibernating)")
                    tearDownBeforeShutdown { goAsync() }
                }
                else -> {
                    // OEM boot/ACC/wake intents — log with extras for diagnostics
                    AppLog.i("WakeDetect: $action")
                    val extras = intent.extras
                    if (extras != null && !extras.isEmpty) {
                        val extrasStr = extras.keySet().joinToString { "$it=${extras.get(it)}" }
                        AppLog.i("WakeDetect: extras: $extrasStr")
                    }
                    // Any OEM boot/ACC intent received dynamically = definite wake
                    onHibernateWake(action)
                }
            }
        }
    }

    /**
     * Called when we've confidently detected a hibernate wake (screen was off for
     * a long time, or an OEM boot/ACC intent was received by the dynamic receiver).
     */
    private fun onHibernateWake(trigger: String) {
        // Debounce: don't re-trigger within 10 seconds (covers BootCompleteReceiver + this)
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeHandledTimestamp < 10_000) {
            AppLog.i("WakeDetect: wake already handled ${(now - lastWakeHandledTimestamp) / 1000}s ago, skipping ($trigger)")
            return
        }
        lastWakeHandledTimestamp = now

        if (commManager.isConnected ||
            commManager.connectionState.value is CommManager.ConnectionState.Connecting ||
            isSwitchingToAccessory.get()) {
            AppLog.i("WakeDetect: already connected/connecting, skipping ($trigger)")
            return
        }

        val settings = App.provide(this).settings

        if (settings.autoStartOnBoot) {
            AppLog.i("WakeDetect: launching UI (trigger=$trigger)")
            launchUiOnCarStart("wake $trigger")
        }

        if (settings.autoStartOnUsb) {
            AppLog.i("WakeDetect: checking USB devices (trigger=$trigger)")
            checkAlreadyConnectedUsb(force = true)
        }
    }

    /**
     * Called on events that MIGHT indicate a wake (e.g. POWER_CONNECTED) but aren't
     * conclusive alone. Only checks USB — does not launch the UI.
     */
    private fun onPossibleWake(trigger: String) {
        if (commManager.isConnected ||
            commManager.connectionState.value is CommManager.ConnectionState.Connecting ||
            isSwitchingToAccessory.get()) return

        val settings = App.provide(this).settings
        if (settings.autoStartOnUsb) {
            AppLog.i("WakeDetect: possible wake, checking USB (trigger=$trigger)")
            checkAlreadyConnectedUsb(force = true)
        }
    }

    /**
     * Called on every SCREEN_ON when "Start on screen on" is enabled.
     * Designed for head units that never truly power off — screen on = car turned on.
     *
     * If the connection is still active (e.g. brief screen toggle), returns to the
     * projection activity. Otherwise launches the main UI and checks USB.
     */
    private fun onScreenOnAutoStart() {
        // Debounce: don't re-trigger within 5 seconds
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeHandledTimestamp < 5_000) {
            AppLog.i("WakeDetect: screen-on auto-start already handled recently, skipping")
            return
        }
        lastWakeHandledTimestamp = now

        // Acquire wake lock to resist power saving cleanup on Quick Boot devices
        acquireBootWakeLock()

        if (commManager.isConnected) {
            // Connection still alive — return to projection screen
            if (App.isPiPActive) {
                AppLog.i("WakeDetect: connection active, but PiP is active. Skipping return to full screen.")
                return
            }
            AppLog.i("WakeDetect: connection active, returning to projection")
            try {
                val projectionIntent = AapProjectionActivity.intent(this).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(projectionIntent)
            } catch (e: Exception) {
                AppLog.e("WakeDetect: failed to launch projection: ${e.message}")
            }
            return
        }

        if (commManager.connectionState.value is CommManager.ConnectionState.Connecting ||
            isSwitchingToAccessory.get()) {
            AppLog.i("WakeDetect: already connecting, skipping screen-on auto-start")
            return
        }

        // Not connected — launch UI (which triggers auto-connect via HomeFragment)
        AppLog.i("WakeDetect: launching UI on screen on")
        launchUiOnCarStart("screen on")

        val settings = App.provide(this).settings
        if (settings.autoStartOnUsb) {
            AppLog.i("WakeDetect: checking USB devices on screen on")
            checkAlreadyConnectedUsb(force = true)
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        AppLog.i("AapService creating...")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1, createNotification())
            }
        } catch (e: Exception) {
            AppLog.e("ForegroundServiceStartNotAllowedException/Exception caught in onCreate: ${e.message}", e)
            stopSelf()
            return
        }
        setupCarMode()
        setupNightMode()
        observeConnectionState()
        registerReceivers()

        // Initialize MediaSession early and set it active immediately.
        // This ensures media button routing works even BEFORE an AA connection,
        // which is critical for keymap configuration and early button presses.
        if (mediaSession == null) {
            setupMediaSession()
        }
        safeMediaSessionCall { it.isActive = true }
        updateMediaSessionState(false) // Set initial PlaybackState so system knows our actions

        commManager.onAaMediaMetadata = { meta -> onAaMediaMetadataFromPhone(meta) }
        commManager.onAaPlaybackStatus = { status -> onAaPlaybackStatusFromPhone(status) }
        settingsPrefs = getSharedPreferences("settings", MODE_PRIVATE).also { prefs ->
            prefs.registerOnSharedPreferenceChangeListener(settingsPreferenceListener)
        }

        AppLog.init(settings, this)
        syncLogBackendState()
        com.andrerinas.openheadunit.utils.OemAndroidAutoBlocker.blockIfEnabled(this, "service-start")
        com.andrerinas.openheadunit.utils.AllgoPackage.removeOldPackage(this)

        nativeAaHandshakeManager = NativeAaHandshakeManager(this, serviceScope)
        softApCredentialsProvider = SoftApCredentialsProvider(this, serviceScope, App.provide(this).settings).also { provider ->
            provider.setCredentialsListener { ssid, psk, ip, bssid -> onNativeCredentials(ssid, psk, ip, bssid) }
            provider.setInvalidatedListener { nativeAaHandshakeManager?.invalidateCredentials() }
        }

        initWifiMode()
        startPhoneReconnectWatchdog()
        checkAlreadyConnectedUsb()
    }

    /** Enables Android Automotive UI mode so the system uses car-optimised layouts. */
    private fun setupCarMode() {
        try {
            val mgr = getSystemService(UI_MODE_SERVICE) as? UiModeManager
            if (mgr != null) {
                uiModeManager = mgr
                mgr.enableCarMode(0)
            }
        } catch (e: Exception) {
            AppLog.w("AapService: Failed to enable car mode: ${e.message}")
        }
    }

    /** Initialises [NightModeManager] and forwards night-mode changes to Android Auto via AAP. */
    private fun setupNightMode() {
        nightModeManager = NightModeManager(this, App.provide(this).settings) { isNight ->
            AppLog.i("NightMode update: $isNight")
            commManager.send(NightModeEvent(isNight))
            // Also notify local components (for AA monochrome filter)
            val intent = Intent(ACTION_NIGHT_MODE_CHANGED).apply {
                setPackage(packageName)
                putExtra("isNight", isNight)
            }
            sendBroadcast(intent)
        }
    }

    /**
     * Single observer for all [CommManager.ConnectionState] transitions.
     *
     * Uses [hasEverConnected] to skip the initial [ConnectionState.Disconnected] emission
     * from StateFlow replay, avoiding a spurious disconnect on startup.
     */
    private fun observeConnectionState() {
        serviceScope.launch {
            commManager.connectionState.collect { state ->
                when (state) {
                    is CommManager.ConnectionState.Connected -> {
                        connectedSinceLastDisconnect = true
                        onConnected()
                    }
                    is CommManager.ConnectionState.HandshakeComplete -> {
                        automaticReconnectAttempts = 0
                        launchAapProjectionActivity()
                    }
                    is CommManager.ConnectionState.TransportStarted -> {
                        hasEverConnected = true
                        notifyAllgoRelay()
                        com.andrerinas.openheadunit.connection.UsbWifiClient.onSessionStarted(
                            this@AapService, commManager.isWirelessSession)
                        accessoryHandshakeFailures = 0
                        automaticReconnectAttempts = 0
                        sendBroadcast(Intent(ACTION_REQUEST_NIGHT_MODE_UPDATE).apply {
                            setPackage(packageName)
                        })
                    }
                    is CommManager.ConnectionState.Error -> {
                        // Nothing may be counted here, and nothing new may be hung off this branch.
                        // connectionState is a MutableStateFlow, so collection is conflated, and
                        // startHandshake() calls disconnect() with no suspension point after
                        // emitting Error — the value is already Disconnected by the time any
                        // collector resumes, so this branch does not run while the Disconnected one
                        // below runs normally. Anything that has to count failures counts them
                        // where they happen; the silent-peer streak lives in CommManager for that
                        // reason.
                        if (state.message.contains("Handshake failed")) {
                            onHandshakeFailed()
                        }
                    }
                    is CommManager.ConnectionState.Disconnected -> {
                        com.andrerinas.openheadunit.connection.UsbWifiClient.onSessionEnded(this@AapService)
                        if (hasEverConnected || connectedSinceLastDisconnect) {
                            connectedSinceLastDisconnect = false
                            onDisconnected(state)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Performs the permanent audio focus request used for AA audio sink.
     *
     * This logic was previously executed in onCreate(); it has been moved here so
     * the caller can decide when to acquire focus (for example, immediately before
     * starting the AA handshake) to avoid stealing audio during autostart.
     *
     * The permanent AUDIOFOCUS_GAIN is only appropriate for Static Audio Focus mode,
     * where the phone must believe focus is always held. In the default (dynamic) mode
     * focus is instead acquired on demand via the AA protocol
     * (AapControl.audioFocusRequest -> AapAudio.requestFocusChange), so grabbing a
     * permanent gain here would needlessly evict other media (e.g. the car radio) the
     * moment the phone connects, before AA plays anything.
     *
     * Whether to take it at all is PlaybackFocusPolicy's call, the same as for the dynamic path:
     * on a head unit that is also the phone's Bluetooth A2DP sink, evicting the sink makes it
     * AVRCP-pause that same phone, so the session starts with the projected audio stopped.
     */
    private fun requestPermanentAudioFocus() {
        if (!settings.enableAudioSink) {
            AppLog.d("Audio Sink disabled - skipping permanent audio focus request.")
            return
        }
        if (!settings.staticAudioFocus) {
            AppLog.d("Static Audio Focus disabled - skipping permanent audio focus request; focus will be acquired on demand.")
            return
        }

        // One probe at connect is enough: the sink only pauses on a focus-loss *event*, so a
        // Bluetooth link that comes up later in the session never sees one.
        val mode = settings.effectivePlaybackFocusMode(this)
        val btMediaLinkActive = BluetoothHelper.isA2dpMediaLinkActive(this)
        if (!PlaybackFocusPolicy.shouldAcquirePermanent(
                mode = mode,
                staticAudioFocus = true,
                audioSinkEnabled = true,
                btMediaLinkActive = btMediaLinkActive)) {
            AppLog.i("AapService: Static Audio Focus - leaving system audio focus alone " +
                    "(mode=$mode, bluetoothMedia=$btMediaLinkActive)")
            return
        }
        AppLog.i("AapService: Static Audio Focus - acquiring permanent system audio focus " +
                "(mode=$mode, bluetoothMedia=$btMediaLinkActive)")

        try {
            val audioManager = SaicCarAudioSource.audioManager(this)
                ?: getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (permanentFocusRequest == null) {
                    // eh32 amp routes by key_car_source_type on the focus attrs (70 = AA).
                    val attrs = SaicCarAudioSource.mediaAttributes()
                    permanentFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener { focusChange ->
                            AppLog.d("AapService: Permanent audio focus changed: $focusChange")
                            // The only focus held in static mode, so every change is the car's.
                            commManager.onPermanentFocusChange(focusChange)
                        }
                        .build()
                }
                val res = audioManager.requestAudioFocus(permanentFocusRequest!!)
                AppLog.d("AapService: requestPermanentAudioFocus: result=$res")
            } else {
                @Suppress("DEPRECATION")
                val res = audioManager.requestAudioFocus(
                    { focusChange -> AppLog.d("AapService: Permanent audio focus changed: $focusChange") },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
                AppLog.d("AapService: requestPermanentAudioFocus (legacy): result=$res")
            }
        } catch (e: Exception) {
            AppLog.e("AapService: requestPermanentAudioFocus failed", e)
        }
    }

    /**
     * Releases any permanent audio focus previously requested by [requestPermanentAudioFocus].
     *
     * This is invoked on disconnect to return audio focus to the phone or other media
     * apps so that playback can resume normally. Supports both the modern
     * AudioFocusRequest API (API >= O) and the legacy abandonAudioFocus path.
     */
    private fun releasePermanentAudioFocus() {
        try {
            val audioManager = SaicCarAudioSource.audioManager(this)
                ?: getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                permanentFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                    AppLog.d("AapService: abandoned permanent audio focus request")
                    permanentFocusRequest = null
                }
            } else {
                @Suppress("DEPRECATION")
                try {
                    audioManager.abandonAudioFocus(null)
                    AppLog.d("AapService: abandoned legacy audio focus (null listener)")
                } catch (e: Exception) {
                    // Some devices may not accept a null listener; ignore failures
                    AppLog.e("AapService: releasePermanentAudioFocus failed", e)
                }
            }
        } catch (e: Exception) {
            AppLog.e("AapService: Failed to abandon audio focus", e)
        }
    }

    /**
     * Called by [CommManager.ConnectionState.Connected] observer:
     * 1. Refreshes the foreground notification.
     * 2. Activates a [MediaSessionCompat] so media keys are routed to Android Auto.
     * 3. Starts the SSL handshake ([CommManager.startHandshake]) **in parallel** with
     *    launching [AapProjectionActivity], hiding multi-second handshake latency behind
     *    activity-inflation time.
     *
     * The inbound message loop ([CommManager.startReading]) is intentionally NOT started
     * here. It is deferred until [AapProjectionActivity] confirms its render surface is
     * ready (via [CommManager.ConnectionState.HandshakeComplete] observer), guaranteeing
     * that [VideoDecoder.setSurface] is always called before the first video frame arrives.
     */
    private fun onConnected() {
        isSwitchingToAccessory.set(false)
        updateNotification()
        acquireWifiLock()

        // Silent audio hack removed to prevent mixing/resampling stuttering issues

        // Register the comprehensive steering wheel key receiver
        App.provide(this).carKeysManager.registerReceivers(this)

        // Reactivate the existing MediaSession (created in onCreate, kept alive across disconnects)
        safeMediaSessionCall { it.isActive = true }
        updateMediaSessionState(true)
        applyPlaceholderMediaMetadata()

        // Link audio focus state changes to our MediaSession state
        commManager.onAudioFocusStateChanged = { isPlaying ->
            updateMediaSessionState(isPlaying)
        }

        // Acquire permanent audio focus just before starting the AA handshake so we
        // don't steal audio during service autostart but still obtain focus when a
        // real connection is beginning.
        requestPermanentAudioFocus()

        maybeSwitchCarToBtMusic()
        // Wi‑Fi music: the car's media source is taken when the phone starts playing, not here —
        // a connection alone (or a probe that never becomes a session) must not stop the radio.
        commManager.onMediaPlaybackStarted = { claimCarMediaSourceForAndroidAuto("music started") }
        commManager.onSpeechEndedDuringMedia = { checkCarSourceAfterPrompt() }
        com.evsuite.hardware.saic.SaicCarAudioSource.onFocusChange = { change ->
            commManager.onCarSourceFocusChange(change)
        }
        maybeReleaseCarBtMediaForWifiAudio()

        // Start GpsLocationService and NightModeManager sensor tracking
        AppLog.i("AapService: Starting GpsLocationService and NightModeManager since connection is established")
        startService(GpsLocationService.intent(this))
        nightModeManager?.start()

        serviceScope.launch { commManager.startHandshake() }
    }

    /**
     * Optional hybrid test: SAIC launcher path (`requestBtAudioFocus` then `play`) so the car
     * amp is on Bluetooth Music while AA video stays on Wi‑Fi. Requires hybrid
     * ([Settings.musicViaBluetooth]) and [Settings.forceBtMusicOnConnect].
     */
    private fun maybeSwitchCarToBtMusic() {
        if (!settings.musicViaBluetooth || !settings.forceBtMusicOnConnect) return
        serviceScope.launch(Dispatchers.IO) {
            SaicMediaPlayer.connectBluetooth(this@AapService)
            repeat(12) { attempt ->
                if (SaicMediaPlayer.switchToBluetooth()) {
                    AppLog.i("AapService: car media source switched to BT Music (attempt ${attempt + 1})")
                    return@launch
                }
                delay(250)
            }
            AppLog.w("AapService: could not switch car to BT Music (SAIC BT music service not ready)")
        }
    }

    /**
     * Wi‑Fi AA media: drop car A2DP/AVRCP only after Native AA has left the BT bootstrap path.
     * Releasing during Service Discovery / early connect tears the phone session (EOF).
     * MG may re-attach A2DP after RFCOMM close — retry a few times. HFP stays up.
     */
    private fun maybeReleaseCarBtMediaForWifiAudio() {
        if (settings.musicViaBluetooth) return
        serviceScope.launch(Dispatchers.IO) {
            // Wait for AAP TransportStarted, then settle past BT→Wi‑Fi handoff.
            val started = withTimeoutOrNull(20_000L) {
                while (commManager.connectionState.value !is CommManager.ConnectionState.TransportStarted) {
                    when (commManager.connectionState.value) {
                        is CommManager.ConnectionState.Disconnected,
                        is CommManager.ConnectionState.Error -> return@withTimeoutOrNull false
                        else -> delay(200)
                    }
                }
                true
            } == true
            if (!started) {
                AppLog.i("AapService: Wi‑Fi media A2DP release skipped — no TransportStarted")
                return@launch
            }
            delay(3_000L)
            for (attempt in 1..3) {
                if (settings.musicViaBluetooth) return@launch
                if (commManager.connectionState.value !is CommManager.ConnectionState.TransportStarted) {
                    AppLog.i("AapService: Wi‑Fi media A2DP release stopped — session gone")
                    return@launch
                }
                val state = BluetoothHelper.a2dpMediaLinkState(this@AapService)
                if (!HybridMusicPolicy.needsReleaseBluetoothMediaLink(false, state)) {
                    AppLog.i("AapService: Wi‑Fi media A2DP already down (attempt $attempt)")
                    break
                }
                val asked = BluetoothHelper.releaseCarBluetoothMediaLink(
                    this@AapService,
                    settings.bluetoothAddress,
                )
                AppLog.i(
                    "AapService: Wi‑Fi media releaseCarBluetoothMediaLink " +
                        "attempt=$attempt asked=$asked a2dp=$state",
                )
                delay(3_000L)
            }
            // Radio keeps SAIC amp priority even while DiAuto decodes Wi‑Fi PCM. If music is
            // already playing, pause the tuner and claim the Allgo-style AA media source (SRC_AA);
            // otherwise leave the radio alone until it starts. Then re-take Android focus now that
            // A2DP is gone (AUTO skipped it earlier).
            if (CarMediaSourcePolicy.shouldClaim(settings.musicViaBluetooth, commManager.isMediaPlaying)) {
                silenceSaicRadioForWifiAudio()
                claimSaicAndroidAutoMediaSource()
            }
            commManager.reclaimPlaybackFocusIfNeeded()
        }
    }

    /**
     * Claim Android Auto as the car media source so the amp leaves radio/BT.
     * SWI68 CPAA binder when present; on eh32 [SaicCarAudioSource] AudioFocus + type 70.
     */
    /**
     * Picking the Bluetooth source in a launcher reconnects the phone's A2DP link in the middle of
     * a Wi-Fi music session; the phone then hops between A2DP and Android Auto and the music cuts.
     * The music plays over Android Auto, so the link is dropped again, as at connect time.
     */
    private fun releaseReturnedCarBtMedia(reason: String) {
        val state = BluetoothHelper.a2dpMediaLinkState(this)
        if (settings.musicViaBluetooth || state != true) return
        val asked = BluetoothHelper.releaseCarBluetoothMediaLink(this, settings.bluetoothAddress)
        AppLog.i("AapService: $reason — car Bluetooth music link is back (a2dp=$state), releasing it: asked=$asked")
    }

    private suspend fun claimSaicAndroidAutoMediaSource() {
        commManager.markOwnFocusRequest()
        try {
            SaicMediaPlayer.connectProjection(this)
            repeat(12) { attempt ->
                if (SaicMediaPlayer.switchToAndroidAuto()) {
                    AppLog.i(
                        "AapService: car media source switched to Android Auto " +
                            "(attempt ${attempt + 1})",
                    )
                    withContext(Dispatchers.Main) {
                        updateMediaSessionState(true)
                        if (lastAaMediaMetadata == null) applyPlaceholderMediaMetadata()
                    }
                    return
                }
                delay(250)
            }
            AppLog.w("AapService: could not switch car to AA media source")
        } catch (e: Exception) {
            AppLog.w("AapService: AA media source switch failed: ${e.message}")
        }
    }

    /**
     * After a guidance or voice prompt the car is meant to go back to the source it played, but it
     * sometimes stays elsewhere while the phone keeps playing: silence with "playing" on screen.
     * Checked once, shortly after the prompt; a user who picked the radio has already paused the
     * phone through the focus loss, so the music is no longer playing and nothing is re-claimed.
     */
    private fun checkCarSourceAfterPrompt() {
        if (settings.musicViaBluetooth) return
        val lossBefore = commManager.lastCarFocusLossMs
        serviceScope.launch(Dispatchers.IO) {
            delay(PROMPT_SOURCE_CHECK_DELAY_MS)
            if (!commManager.isMediaPlaying) return@launch
            // The user (or the car) picked another source meanwhile; that choice stands.
            if (commManager.lastCarFocusLossMs != lossBefore) return@launch
            if (SaicMediaPlayer.isAndroidAutoSource()) return@launch
            AppLog.i("AapService: the car stayed off Android Auto after a prompt while music plays")
            claimCarMediaSourceForAndroidAuto("prompt ended")
        }
    }

    /**
     * Pauses the radio and makes Android Auto the car's media source, once, when the phone starts
     * playing music over Wi-Fi (see [CarMediaSourcePolicy]). If the user then picks the radio, the
     * focus loss is passed on to the phone, which pauses; nothing here takes the radio back until
     * the phone plays again.
     *
     * The claim can report success while the car stays on its previous source (at connect time the
     * Bluetooth media service is still letting go of the A2DP link), and the music then starts in
     * silence. So the source is checked again a few seconds later, while the music still plays.
     */
    /**
     * After next / previous, the old track's audio still buffered here (Wi-Fi jitter buffer plus the
     * AudioTrack, 1–2 s) would play before the new one. Dropped shortly after the key, once the
     * phone has stopped sending the old track.
     */
    private fun flushSkippedTrackAudio() {
        if (settings.musicViaBluetooth) return
        serviceScope.launch {
            delay(TRACK_SKIP_FLUSH_DELAY_MS)
            commManager.flushMediaForTrackChange()
        }
    }

    /**
     * A PLAY aimed at this app (steering wheel, launcher handing the source back): undo a car focus
     * loss the phone was told about, and take the car's source back, so the phone restarts at once.
     */
    private fun resumeForUserPlay(reason: String) {
        if (commManager.resumeAudioFromUser()) {
            AppLog.i("AapService: $reason after a car focus loss — resuming Android Auto music")
            claimCarMediaSourceForAndroidAuto(reason)
        }
    }

    private fun claimCarMediaSourceForAndroidAuto(reason: String) {
        if (!CarMediaSourcePolicy.shouldClaim(settings.musicViaBluetooth, mediaPlaying = true)) return
        saicAaMediaClaimJob?.cancel()
        saicAaMediaClaimJob = serviceScope.launch(Dispatchers.IO) {
            AppLog.i("AapService: $reason — taking the car's media source for Android Auto")
            releaseReturnedCarBtMedia(reason)
            silenceSaicRadioForWifiAudio()
            claimSaicAndroidAutoMediaSource()
            val lossBefore = commManager.lastCarFocusLossMs
            for (checkDelayMs in CLAIM_CHECK_DELAYS_MS) {
                delay(checkDelayMs)
                if (!commManager.isMediaPlaying) return@launch
                // A source the user picked since the claim is theirs to keep.
                if (commManager.lastCarFocusLossMs != lossBefore) return@launch
                if (SaicMediaPlayer.isAndroidAutoSource()) continue
                AppLog.i("AapService: $reason — the car is still not on Android Auto, claiming again")
                silenceSaicRadioForWifiAudio()
                claimSaicAndroidAutoMediaSource()
            }
        }
    }

    /**
     * MG4 amp follows the SAIC media source. While radio is playing, Wi‑Fi AA AudioTrack
     * does not become the audible source — [SaicRadio.pause] is the launcher's mute path.
     */
    private suspend fun silenceSaicRadioForWifiAudio() {
        try {
            SaicRadio.connect(this)
            repeat(8) { attempt ->
                val playing = SaicRadio.isPlaying()
                if (playing == false) {
                    AppLog.i("AapService: SAIC radio already paused (attempt ${attempt + 1})")
                    return
                }
                val ok = SaicRadio.pause()
                AppLog.i(
                    "AapService: pause SAIC radio for Wi‑Fi AA audio " +
                        "attempt=${attempt + 1} ok=$ok playingWas=$playing",
                )
                if (ok) return
                delay(250)
            }
            AppLog.w("AapService: could not pause SAIC radio (service not ready)")
        } catch (e: Exception) {
            AppLog.w("AapService: pause SAIC radio failed: ${e.message}")
        }
    }

    private fun launchAapProjectionActivity() {
        if (App.isPiPActive) {
            AppLog.i("AapService: Skipping projection launch because PiP is active")
            return
        }

        val intent = AapProjectionActivity.intent(this).apply {
            putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }

        val canOverlay = AppPermissions.isOverlayGranted(this)
        when (ActivityLaunchPolicy.chooseLaunchStrategy(Build.VERSION.SDK_INT, canOverlay)) {
            ActivityLaunchPolicy.LaunchStrategy.DIRECT -> {
                try { startActivity(intent) }
                catch (e: Exception) { AppLog.e("Projection launch failed: ${e.message}") }
            }
            ActivityLaunchPolicy.LaunchStrategy.OVERLAY -> {
                if (!launchViaOverlayTrampoline(intent)) {
                    AppLog.w("Projection overlay trampoline failed, trying direct")
                    try { startActivity(intent) }
                    catch (e: Exception) { AppLog.e("Projection direct fallback failed: ${e.message}") }
                }
            }
            ActivityLaunchPolicy.LaunchStrategy.NOTIFICATION -> launchProjectionViaNotification(intent)
        }
    }

    private fun launchProjectionViaNotification(launchIntent: Intent) {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val fullScreenPi = PendingIntent.getActivity(this, PROJECTION_LAUNCH_NOTIFICATION_ID, launchIntent, piFlags)

        val notification = NotificationCompat.Builder(this, App.bootStartChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.android_auto_starting))
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(PROJECTION_LAUNCH_NOTIFICATION_ID, notification)
        serviceScope.launch {
            delay(5000)
            nm.cancel(PROJECTION_LAUNCH_NOTIFICATION_ID)
        }
    }

    private fun setupMediaSession() {
        val mbr = ComponentName(this, MediaButtonReceiver::class.java)
        // Tag matches what the car media stack looks for when Allgo publishes AA.
        mediaSession = MediaSessionCompat(this, "Android Auto", mbr, null).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setSessionActivity(
                PendingIntent.getActivity(
                    this@AapService,
                    0,
                    AapProjectionActivity.intent(this@AapService).apply {
                        putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = mediaButtonEvent?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java) }

                    if (keyEvent != null) {
                        val actionStr = if (keyEvent.action == android.view.KeyEvent.ACTION_DOWN) "DOWN" else "UP"
                        if (AppLog.LOG_DEBUG) {
                            AppLog.d("MediaButtonEvent: Received key ${keyEvent.keyCode} ($actionStr)")
                        }

                        // Only handle ACTION_DOWN to prevent double triggers from standard Android behavior.
                        // Physical double triggers are handled by CommManager.sendKey deduplication.
                        if (keyEvent.action == android.view.KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                            AppLog.i("MediaButtonEvent: Processing key ${keyEvent.keyCode}")
                            if (keyEvent.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY ||
                                keyEvent.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                                resumeForUserPlay("media button ${keyEvent.keyCode}")
                            }
                            // Send a complete click sequence (press + release) immediately
                            commManager.sendKey(keyEvent.keyCode, true, keyEvent.downTime, "mediasession")
                            commManager.sendKey(keyEvent.keyCode, false, keyEvent.downTime, "mediasession")
                            if (keyEvent.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_NEXT ||
                                keyEvent.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                                flushSkippedTrackAudio()
                            }
                            return true
                        }

                        // Consume ACTION_UP to prevent fallback
                        if (keyEvent.action == android.view.KeyEvent.ACTION_UP) {
                            return true
                        }
                    }

                    return super.onMediaButtonEvent(mediaButtonEvent)
                }

                override fun onPause() {
                    AppLog.i("MediaSession: Processing transport control action = KEYCODE_MEDIA_PAUSE")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, true, null, "transport-control")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, false, null, "transport-control")
                }

                override fun onPlay() {
                    AppLog.i("MediaSession: Processing transport control action = KEYCODE_MEDIA_PLAY")
                    resumeForUserPlay("play")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, true, null, "transport-control")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, false, null, "transport-control")
                }

                override fun onSkipToNext() {
                    AppLog.i("MediaSession: Processing transport control action = KEYCODE_MEDIA_NEXT")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, true, null, "transport-control")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, false, null, "transport-control")
                    flushSkippedTrackAudio()
                }

                override fun onSkipToPrevious() {
                    AppLog.i("MediaSession: Processing transport control action = KEYCODE_MEDIA_PREVIOUS")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, true, null, "transport-control")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, false, null, "transport-control")
                    flushSkippedTrackAudio()
                }

                override fun onStop() {
                    AppLog.i("MediaSession: Processing transport control action = KEYCODE_MEDIA_STOP")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_STOP, true, null, "transport-control")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_STOP, false, null, "transport-control")
                }
            })
            setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)
        }
        applyPlaceholderMediaMetadata()
    }

    /**
     * Called by [CommManager.ConnectionState.Disconnected] observer:
     * 1. Refreshing the notification (unless we are already tearing down)
     * 2. Releasing the [MediaSessionCompat]
     * 3. Stopping audio/video decoders on the IO thread
     * 4. Scheduling a reconnect attempt if applicable (see [scheduleReconnectIfNeeded])
     */
    private fun onDisconnected(state: CommManager.ConnectionState.Disconnected) {
        AllgoRelayNotifier.sessionEnded(this)
        com.andrerinas.openheadunit.hud.SaicNavigationOutputs.stop()
        // Fresh scan results for the next automatic channel choice; never during a session, where a
        // scan takes the radio off the access point's channel.
        com.andrerinas.openheadunit.utils.WifiChannelPicker.scanSoon(this)
        // Give the car Wi-Fi back once wireless Android Auto has stayed down for a while.
        stationRestoreJob?.cancel()
        stationRestoreJob = serviceScope.launch(Dispatchers.IO) {
            delay(settings.stationRestoreDelaySeconds * 1000L)
            if (!commManager.isBusy) HotspotManager.restorePausedStation(this@AapService)
        }
        isSwitchingToAccessory.set(false)
        releaseWifiLock()

        // Stop GpsLocationService and NightModeManager sensor tracking
        AppLog.i("AapService: Stopping GpsLocationService and NightModeManager since connection is disconnected")
        stopService(GpsLocationService.intent(this))
        nightModeManager?.stop()

        // Release any permanent audio focus we may have requested when connected
        releasePermanentAudioFocus()
        App.provide(this).carKeysManager.unregisterReceivers()
        saicAaMediaClaimJob?.cancel()
        saicAaMediaClaimJob = null

        if (!isDestroying) updateNotification()
        mediaMetadataDecodeJob?.cancel()
        mediaMetadataDecodeJob = null
        lastAaMediaMetadata = null
        allgoMediaCard.sessionEnded()
        lastAaPlaybackPositionMs = 0L
        lastAaPlaybackIsPlaying = null
        cachedAaAlbumArtBitmap = null
        mediaNotification.cancel()
        applyPlaceholderMediaMetadata()
        // Keep MediaSession alive across disconnect/reconnect cycles.
        // Only deactivate it — do NOT release it. A released session can no longer
        // receive media button events, which means the keymap stops working until
        // the next connection. OpenHU keeps its session alive the entire service lifetime.
        safeMediaSessionCall { it.isActive = false }
        updateMediaSessionState(false)
        serviceScope.launch(Dispatchers.IO) {

            val settings = App.provide(this@AapService).settings

            if (preemptingWirelessForUsb) {
                AppLog.i("AapService: Native AA disconnect during USB preempt — not re-initializing wireless.")
                nativeAaHandshakeManager?.stop()
            } else if (state.isUserExit) {
                AppLog.i("AapService: Native AA user exit. Stopping handshake manager.")
                nativeAaHandshakeManager?.stop()
            } else {
                // Unexpected disconnect — reset and re-initialize for auto-reconnect.
                AppLog.i("AapService: Native AA disconnected. Resetting the handshake in 1.5s...")
                nativeAaHandshakeManager?.stop()
                serviceScope.launch {
                    delay(1500) // Give the hardware time to settle before re-initializing
                    if (commManager.isBusy) {
                        // The phone reconnects within ~300 ms; re-initialising now would tear
                        // down the Bluetooth handshake server under the new session.
                        AppLog.i("AapService: a new session is already up - skipping the delayed wireless re-init")
                    } else if (!preemptingWirelessForUsb && !isSwitchingToAccessory.get()) {
                        initWifiMode(force = true)
                    }
                }
            }

            if (state.isUserExit) {
                // The car hotspot route.
                // Closing the socket does not make the phone leave the network — it stays
                // associated and Android Auto retries its wireless setup until it throttles itself
                // — so the access point has to go, and for the same reason as above only once
                // CommManager has finished. The access point is usually the
                // user's own, and switching one back on is best effort, so it only comes down when
                // they have already handed the app that job.
                //
                // Restarted rather than left down. It has to disappear for the phone to be put off
                // it, but leaving it off charges the whole bring-up — measured at ~20s on a unit
                // that refuses setSoftApConfiguration() — to the next connection, with the phone
                // waiting through it. Paying it here spends the same seconds while the user is
                // already walking away.
                val action = UserExitHotspotPolicy.onUserExit(
                    settings.autoEnableHotspot, settings.hotspotTeardownProvenUnsafe
                )
                commManager.awaitDisconnectComplete()
                // Stop watching an access point nobody is connecting over, either way: this
                // holds a system broadcast receiver and can still re-enable the hotspot on its
                // own long after the user has finished with it.
                softApCredentialsProvider?.stop()
                when (action) {
                    HotspotExitAction.DISABLE -> {
                        AppLog.i("AapService: CommManager teardown complete. Restarting the hotspot so the phone leaves the network.")
                        if (!HotspotManager.restart(this@AapService)) {
                            // The one way to learn that this radio will not host an access point
                            // again once it has been taken down. Remembered so it costs the user
                            // one hotspot rather than one per session — from here on this device's
                            // access point is left alone and the phone is told, in the branch
                            // below, what that means.
                            settings.hotspotTeardownProvenUnsafe = true
                            AppLog.w(
                                "AapService: This device did not bring its access point back after " +
                                    "the app took it down, so it will not be taken down again. " +
                                    "Ending a session will leave the phone on the network from now " +
                                    "on — end it from the phone's own Android Auto notification if " +
                                    "that becomes a problem."
                            )
                        }
                    }
                    HotspotExitAction.WARN_LEFT_UP -> AppLog.w(
                        "AapService: Stopping the connection does not switch this device's hotspot " +
                            "off — either the app was not given charge of it, or this device has " +
                            "already shown it cannot switch one back on. So the phone stays " +
                            "joined to it and Android Auto may keep retrying until it throttles " +
                            "itself. Turn the hotspot off and on again to clear that, or end the " +
                            "session from the phone's own Android Auto notification instead, which " +
                            "makes it leave the network by itself."
                    )
                }
            }

            App.provide(this@AapService).audioDecoder.stop()
            App.provide(this@AapService).videoDecoder.stop("AapService::onDisconnect")
        }

        // [FIX] Set cooldown flag for ALL user exits (not just USB).
        // The WirelessServer checks this flag to reject instant reconnections.
        if (state.isUserExit) {
            userExitedAA = true
            userExitCooldownUntil = android.os.SystemClock.elapsedRealtime() + USER_EXIT_COOLDOWN_MS
            AppLog.i("AapService: User exit cooldown active for ${USER_EXIT_COOLDOWN_MS}ms")
        }

        scheduleReconnectIfNeeded(state)
    }

    /**
     * Schedules a USB reconnect check after an unexpected disconnect. Wireless sessions come back
     * through the handshake re-init in [onDisconnected] instead, so nothing is scheduled while the
     * wireless server is up.
     */
    private fun scheduleReconnectIfNeeded(state: CommManager.ConnectionState.Disconnected) {
        if (wirelessServer != null) return

        val settings = App.provide(this).settings
        val lastType = settings.lastConnectionType

        // USB auto-reconnect: try again after a delay to give dongles time to re-enumerate.
        // Skip if the user voluntarily exited AA — the dongle is likely still connected with
        // stale data, and reconnecting immediately just causes handshake failures. The next
        // USB attach event will re-trigger the flow cleanly.
        if (lastType == Settings.CONNECTION_TYPE_USB &&
            (settings.autoConnectLastSession || settings.autoConnectSingleUsbDevice)) {
            if (state.isUserExit && !(settings.autoStartOnUsb && settings.reopenOnReconnection)) {
                AppLog.i("AapService: USB disconnect after user Exit. Skipping auto-reconnect (waiting for dongle re-enumeration).")
                userExitedAA = true
                return
            }
            if (state.isUserExit && settings.autoStartOnUsb && settings.reopenOnReconnection) {
                AppLog.i("AapService: USB disconnect after user Exit with reopenOnReconnection enabled. Will reconnect on next USB attach.")
                return
            }
            if (!noteAutomaticReconnect("USB reconnect")) return
            AppLog.i(
                "AapService: USB disconnect. Scheduling reconnect check in ${USB_RECONNECT_DELAY_MS}ms " +
                    "(attempt $automaticReconnectAttempts/$MAX_AUTOMATIC_RECONNECT_ATTEMPTS)...",
            )
            serviceScope.launch {
                delay(USB_RECONNECT_DELAY_MS)
                if (!commManager.isConnected) checkAlreadyConnectedUsb(force = true)
            }
        }
    }

    /**
     * @return false when the automatic retry budget is exhausted (caller must not schedule more).
     */
    private fun noteAutomaticReconnect(reason: String): Boolean {
        if (automaticReconnectAttempts >= MAX_AUTOMATIC_RECONNECT_ATTEMPTS) {
            AppLog.w(
                "AapService: automatic reconnect limit reached ($MAX_AUTOMATIC_RECONNECT_ATTEMPTS) " +
                    "for $reason; stopping retries until the user connects again",
            )
            ToastUtils.showToast(
                this,
                getString(R.string.connection_retry_limit_reached),
                Toast.LENGTH_LONG,
            )
            return false
        }
        automaticReconnectAttempts++
        return true
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private fun registerReceivers() {
        usbReceiver = UsbReceiver(this)
        ContextCompat.registerReceiver(
            this, nightModeUpdateReceiver,
            IntentFilter(ACTION_REQUEST_NIGHT_MODE_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, sensorRefreshReceiver,
            IntentFilter(ACTION_REFRESH_SENSORS).apply { addAction(ACTION_RESTART_AUDIO) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, usbReceiver,
            UsbReceiver.createFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Runtime-registered MEDIA_BUTTON receiver.
        // Unlike manifest-registered receivers, runtime receivers bypass the
        // Android 8+ implicit broadcast restriction. This is the primary mechanism
        // that makes steering wheel media buttons work on China headunits.
        ContextCompat.registerReceiver(
            this, mediaButtonReceiver,
            IntentFilter(Intent.ACTION_MEDIA_BUTTON),
            ContextCompat.RECEIVER_EXPORTED
        )
        AppLog.i("Registered runtime MEDIA_BUTTON receiver")

        // Wake detection receiver: catches SCREEN_ON, SCREEN_OFF, POWER_CONNECTED,
        // and all known OEM boot/ACC intents. Enables hibernate wake detection on
        // Quick Boot head units where BOOT_COMPLETED never fires.
        val wakeFilter = IntentFilter().apply {
            // Screen events (only receivable by dynamic receivers on Android 8+)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            // Power events
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SHUTDOWN)
            // Standard boot (dynamic duplicate — BootCompleteReceiver handles manifest side)
            addAction(Intent.ACTION_BOOT_COMPLETED)
            addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED)
            // Quick boot variants
            addAction("android.intent.action.QUICKBOOT_POWERON")
            addAction("com.htc.intent.action.QUICKBOOT_POWERON")
            // MediaTek IPO (Instant Power On)
            addAction("com.mediatek.intent.action.QUICKBOOT_POWERON")
            addAction("com.mediatek.intent.action.BOOT_IPO")
            // FYT / GLSX head units (ACC ignition wake)
            addAction("com.fyt.boot.ACCON")
            addAction("com.glsx.boot.ACCON")
            addAction("android.intent.action.ACTION_MT_COMMAND_SLEEP_OUT")
            // Microntek / MTCD / PX3 head units (ACC wake)
            addAction("com.cayboy.action.ACC_ON")
            addAction("com.carboy.action.ACC_ON")
        }
        ContextCompat.registerReceiver(
            this, wakeDetectReceiver,
            wakeFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
        AppLog.i("Registered wake detection receiver (${wakeFilter.countActions()} actions)")
    }

    /** Brings up wireless Android Auto over the car's hotspot, unless it is off in the settings. */
    /** Bluetooth auto-start, once the Wi-Fi radio is free: poke the phone or arm the handshake. */
    private fun startNativeAaForPhone(mac: String?) {
        if (!mac.isNullOrEmpty() &&
            SystemClock.elapsedRealtime() - lastWatchdogPokeMs < WATCHDOG_POKE_SETTLE_MS) {
            // The phone is back because of the watchdog's own poke, which is still
            // holding its socket; a second poke on top of it kept the phone's Android
            // Auto channel out for two minutes (2026-10-26, 19:01-19:03).
            AppLog.i("AapService: Bluetooth auto-start — the phone answered the watchdog poke; not poking again")
            scheduleNativeAaArm(reason = "bt auto-start")
        } else if (!mac.isNullOrEmpty()) {
            AppLog.i("AapService: Bluetooth auto-start — poking $mac")
            scheduleNativeAaPoke(mac, reason = "bt auto-start")
        } else {
            scheduleNativeAaArm(reason = "bt auto-start")
        }
    }

    /** A phone DiAuto starts Android Auto with is connected over Bluetooth right now. */
    private fun rememberedPhoneLinked(): Boolean {
        val remembered = settings.autoStartBluetoothDeviceMacs
        return BluetoothHelper.connectedBondedDevices(this).any { remembered.contains(it.address) }
    }

    private fun initWifiMode(force: Boolean = false) {
        if (!force && wirelessInitialized && !wirelessSettingChanged()) {
            AppLog.d("AapService: wireless Android Auto is already initialized.")
            return
        }

        AppLog.i("AapService: Initializing wireless Android Auto")

        stopWirelessServer()
        nativeAaHandshakeManager?.stop()
        softApCredentialsProvider?.stop()

        wirelessInitialized = true
        activeWirelessEnabled = wirelessEnabled()
        if (activeWirelessEnabled != true) {
            AppLog.i("AapService: wireless Android Auto is off in the settings - USB only")
            return
        }

        // Native Android Auto over the car's own hotspot: the only wireless route left.
        startWirelessServer()
        // Skip the route, not just the handshake, when the Bluetooth this unit's phone is bonded
        // to isn't reachable from here: with no Bluetooth channel there is nobody to hand the
        // credentials to, so holding the hotspot open would only churn the WiFi stack for nothing.
        val externalBt = NativeAaHandshakeManager.externalBtDiagnostic()
        if (externalBt != null) AppLog.e(externalBt)
        val blockedByExternalBt =
            externalBt != null && !NativeAaHandshakeManager.externalBtOverridden(this)
        if (!blockedByExternalBt) {
            // Read this device's own access point. The AP itself is the car's; the provider only
            // resolves and watches it.
            AppLog.i("AapService: Native AA on the head unit hotspot — resolving access point credentials.")
            // The car's Wi-Fi client is only switched off once the phone is linked over Bluetooth
            // (here, or on the Bluetooth auto-start below), so with no phone it stays on.
            val phoneLinked = rememberedPhoneLinked()
            serviceScope.launch(Dispatchers.IO) {
                HotspotManager.ensure5GhzBeforeSession(this@AapService, pauseStation = phoneLinked)
            }
            softApCredentialsProvider?.start()
            // Start the official Bluetooth handshake servers
            nativeAaHandshakeManager?.start()
        }
    }

    private fun acquireWifiLock() {
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HeadunitRevived:Connection")
        }
        if (wifiLock?.isHeld == false) {
            wifiLock?.acquire()
            AppLog.i("WifiLock acquired (HIGH_PERF)")
        }
    }

    private fun releaseWifiLock() {
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
            AppLog.i("WifiLock released")
        }
    }

    /**
     * Acquires a partial wake lock to resist MediaTek/Reglink background power
     * saving that force-stops third-party apps when ACC is off.
     * The wake lock has a 10-minute timeout as a safety net.
     */
    private fun acquireBootWakeLock() {
        if (bootWakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        bootWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HeadunitRevived::BootAutoStart"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minute timeout
        }
        AppLog.i("Boot WakeLock acquired (10min timeout)")

        // Log battery optimization status for diagnostics
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val exempt = pm.isIgnoringBatteryOptimizations(packageName)
            AppLog.i("Battery optimization exempt: $exempt")
        }
    }

    private fun releaseBootWakeLock() {
        if (bootWakeLock?.isHeld == true) {
            bootWakeLock?.release()
            AppLog.i("Boot WakeLock released")
        }
        bootWakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        com.andrerinas.openheadunit.hud.SaicNavigationOutputs.stop()
        AppLog.i("AapService: onTaskRemoved — attempting restart")
        try {
            val restartIntent = Intent(this, AapService::class.java)
            ContextCompat.startForegroundService(this, restartIntent)
        } catch (e: Exception) {
            AppLog.e("AapService: failed to restart after task removal: ${e.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        com.andrerinas.openheadunit.hud.SaicNavigationOutputs.stop()
        AppLog.i("AapService destroying... (wakeLock held=${bootWakeLock?.isHeld == true})")
        isDestroying = true
        mediaMetadataDecodeJob?.cancel()
        cachedAaAlbumArtBitmap = null
        mediaNotification.cancel()
        commManager.onAaMediaMetadata = null
        commManager.onAaPlaybackStatus = null
        settingsPrefs?.unregisterOnSharedPreferenceChangeListener(settingsPreferenceListener)
        settingsPrefs = null
        nativeAaHandshakeManager?.stop()
        releaseBootWakeLock()

        // Before the hotspot goes, not after: SoftApCredentialsProvider watches
        // WIFI_AP_STATE_CHANGED and switches an access point it started back on when it sees one
        // drop. Left registered here it would treat this very teardown as the hotspot failing and
        // bring it back up as the service dies — leaving the access point running with nothing
        // left to serve it.
        softApCredentialsProvider?.stop()

        if (App.provide(this).settings.autoEnableHotspot) {
            AppLog.i("AapService: Auto-disabling hotspot...")
            HotspotManager.setHotspotEnabled(this, false)
        }

        releaseWifiLock()
        stopForeground(true)
        stopWirelessServer()
        try {
            mediaSession?.let {
                it.isActive = false
                it.release()
            }
        } catch (e: Exception) {
            AppLog.e("Error releasing MediaSession: ${e.message}")
        }
        mediaSession = null
        commManager.destroy()
        nightModeManager?.stop()
        stopService(GpsLocationService.intent(this))
        try {
            unregisterReceiver(nightModeUpdateReceiver)
            unregisterReceiver(sensorRefreshReceiver)
        } catch (_: Exception) {}
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(mediaButtonReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(wakeDetectReceiver) } catch (_: Exception) {}
        try { App.provide(this).carKeysManager.unregisterReceivers() } catch (e: Exception) { AppLog.w("AapService: Error unregistering carKeysManager: ${e.message}") }
        try {
            if (::uiModeManager.isInitialized) {
                // 0 = no flags (do not force GO_HOME). IntDef only lists DISABLE_CAR_MODE_GO_HOME.
                @Suppress("WrongConstant")
                uiModeManager.disableCarMode(0)
            }
        } catch (e: Exception) {
            AppLog.w("AapService: Error disabling car mode: ${e.message}")
        }
        try { serviceScope.cancel() } catch (_: Exception) {}
        try { LogExporter.stopCapture() } catch (_: Exception) {}
        super.onDestroy()
        if (killProcessOnDestroy) {
            AppLog.i("AapService: killProcessOnDestroy is true. Triggering System.exit(0).")
            System.exit(0)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1, createNotification())
            }
        } catch (e: Exception) {
            AppLog.e("ForegroundServiceStartNotAllowedException/Exception caught in onStartCommand: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // Handle stop before re-posting the notification to avoid a flash
        if (intent?.action == ACTION_STOP_SERVICE) {
            AppLog.i("Stop action received. Broadcasting finish request to activities.")
            sendBroadcast(Intent("com.andrerinas.openheadunit.ACTION_FINISH_ACTIVITIES").apply {
                setPackage(packageName)
            })
            isDestroying = true
            if (commManager.isConnected) commManager.disconnect(sendByeBye = true)
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        // Route MEDIA_BUTTON intents to the active MediaSession.
        safeMediaSessionCall { MediaButtonReceiver.handleIntent(it, intent) }
        // Launch the UI after boot.
        // Direct startActivity() is silently blocked on MIUI/HyperOS even from
        // a foreground service. We use an overlay window trampoline: creating a
        // zero-size overlay gives the app a "visible" context that bypasses OEM
        // background activity start restrictions. Falls back to full-screen
        // intent notification if overlay permission is not granted.
        // Acquire a partial wake lock on any boot/screen-on start to resist
        // aggressive power saving on MediaTek/Reglink head units that force-stop
        // third-party apps when ACC is off after a Quick Boot reboot.
        if (intent?.getBooleanExtra(BootCompleteReceiver.EXTRA_BOOT_START, false) == true ||
            intent?.action == ACTION_CHECK_USB) {
            acquireBootWakeLock()
        }

        if (intent?.getBooleanExtra(BootCompleteReceiver.EXTRA_BOOT_START, false) == true) {
            // Mark wake as handled so the dynamic wakeDetectReceiver doesn't double-trigger
            lastWakeHandledTimestamp = SystemClock.elapsedRealtime()
            launchUiOnCarStart("boot")
        }

        when (intent?.action) {
            ACTION_START_WIRELESS        -> initWifiMode()
            ACTION_START_WIRELESS_SCAN   -> {
                // [FIX] Reset exit flags on manual scan start
                userExitedAA = false
                userExitCooldownUntil = 0L
                initWifiMode(force = true)
            }
            ACTION_STOP_WIRELESS         -> stopWirelessServer()
            ACTION_NATIVE_AA_POKE        -> {
                val mac = intent?.getStringExtra(EXTRA_MAC)
                if (mac != null) {
                    AppLog.i("AapService: Received manual Native-AA poke request for $mac")
                    scheduleNativeAaPoke(mac, reason = "manual poke")
                }
            }
            ACTION_BT_AUTO_START          -> {
                // AutoStartReceiver fires this on ACL_CONNECTED from a trusted device. If the
                // service process was already alive (e.g. survived a prior disconnect/exit in
                // this same session), onCreate()'s initWifiMode() never re-runs, so a Native AA
                // mode that was stopped after a user exit (nativeAaHandshakeManager.stop() at
                // disconnect) would otherwise stay dead forever despite the phone reconnecting.
                // Only force a re-init when it's actually stopped — on a genuine cold start,
                // onCreate() already armed everything moments ago and re-running would tear
                // down and recreate the P2P group (new random SSID/passphrase) right as it's
                // being delivered to the phone.
                // A successful handoff closes the AA listeners, so isActive() is false for the
                // whole life of a working session — without the connection check below, any
                // later ACL_CONNECTED (the phone's own Bluetooth profiles reconnecting, or one
                // of our pokes) would tear down a session that is projecting fine.
                val mac = intent?.getStringExtra(EXTRA_MAC)
                val sessionUp = commManager.isConnected ||
                    commManager.connectionState.value is CommManager.ConnectionState.Connecting
                if (sessionUp) {
                    AppLog.d("AapService: Bluetooth auto-start ignored — session already up.")
                } else if (!wirelessEnabled()) {
                    AppLog.d("AapService: Bluetooth auto-start ignored — wireless Android Auto is off.")
                } else {
                    // Trusted Bluetooth phone just linked — go straight to Native AA; do not wait
                    // for the user to pick the phone on the home screen.
                    lastPhoneBtConnectMs = SystemClock.elapsedRealtime()
                    // The phone is here: free the single Wi-Fi radio from the car's own Wi-Fi
                    // client before it joins the access point (instant unless that client was on
                    // 2.4 GHz, where the access point has to restart on 5 GHz first).
                    serviceScope.launch {
                        withContext(Dispatchers.IO) {
                            HotspotManager.ensure5GhzBeforeSession(this@AapService, pauseStation = true)
                        }
                        startNativeAaForPhone(mac)
                    }
                }
            }
            ACTION_DISCONNECT            -> {
                AppLog.i("Disconnect action received.")
                // Cancel any pending post-disconnect wait/poke so pill cancel
                // does not keep reconnecting after the user backed out.
                nativeAaPokeJob?.cancel()
                nativeAaPokeJob = null
                // disconnect() has its own early-return when already Disconnected,
                // and unlike the previous isConnected guard it also covers the
                // Connecting state, so the UI cancel paths work before handshake
                // completes.
                commManager.disconnect()
            }
            ACTION_CONNECT_SOCKET        -> {
                // Caller already invoked commManager.connect(socket); the connectionState
                // observer in observeConnectionState() handles the rest — nothing to do here.
            }
            ACTION_CHECK_USB             -> {
                // Manual USB reconnect (list tap / AOA switch follow-up). Clear the user-exit
                // latch that otherwise waits for a physical unplug — soft reconnect never
                // produces USB_ATTACH while the cable stays in.
                userExitedAA = false
                userExitCooldownUntil = 0L
                checkAlreadyConnectedUsb(force = true)
            }
            else                         -> {
                if (intent?.action == null || intent.action == Intent.ACTION_MAIN) {
                    checkAlreadyConnectedUsb()
                }
            }
        }
        return START_STICKY
    }

    // -------------------------------------------------------------------------
    // USB
    // -------------------------------------------------------------------------

    override fun onUsbAttach(device: UsbDevice) {
        if (!UsbDeviceCompat.isAndroidDevice(device)) {
            AppLog.i("Ignoring non-Android USB device attached in service (VID: ${device.vendorId}): ${device.deviceName}")
            return
        }
        com.andrerinas.openheadunit.utils.OemAndroidAutoBlocker.blockIfEnabled(this, "service-usb-attach")
        userExitedAA = false
        if (UsbDeviceCompat.isInAccessoryMode(device)) {
            // Device already in AOA mode (re-enumerated after UsbAttachedActivity switched it).
            AppLog.i("USB accessory device attached, connecting.")
            launchMainActivityIfNeeded("USB accessory attach")
            checkAlreadyConnectedUsb(force = true)
        } else {
            // UsbAttachedActivity normally handles normal-mode devices via a manifest intent
            // filter. However, some headunits (especially Chinese MediaTek units) don't
            // deliver USB_DEVICE_ATTACHED to activities on cold start. As a fallback,
            // check after a delay to give UsbAttachedActivity a chance to handle it first.
            val deviceName = UsbDeviceCompat(device).uniqueName
            AppLog.i("Normal USB device attached: $deviceName. Will check auto-connect in ${USB_ATTACH_FALLBACK_DELAY_MS}ms...")
            launchMainActivityIfNeeded("USB normal attach ($deviceName)")
            serviceScope.launch {
                delay(USB_ATTACH_FALLBACK_DELAY_MS)
                // Wireless Connected must not skip this — USB preempts SoftAP/P2P after v1.6.
                if (!isSwitchingToAccessory.get() &&
                    (!commManager.isConnected || commManager.isWirelessSession)
                ) {
                    AppLog.i("UsbAttachedActivity didn't handle $deviceName. Trying from service...")
                    checkAlreadyConnectedUsb(force = true)
                }
            }
        }
    }

    override fun onUsbDetach(device: UsbDevice) {
        userExitedAA = false
        if (commManager.isConnectedToUsbDevice(device)) {
            // Cable physically removed — the USB connection is already dead, so skip the
            // ByeByeRequest send (which would block ~1 s trying to write to a gone device).
            commManager.disconnect(sendByeBye = false, isUserExit = false)
        }
    }

    override fun onUsbAccessoryDetach() {
        AppLog.i("USB Accessory detached. This might be a transient state (e.g., 100% battery). Attempting to re-sync...")
        userExitedAA = false
        if (commManager.isConnected) {
            commManager.disconnect(sendByeBye = false, isUserExit = false)
        }

        // Wait a bit and check if the device is still there in normal mode
        serviceScope.launch {
            delay(1500) // Give the phone/system time to settle its USB state
            AppLog.i("Accessory detach cooldown finished. Checking for re-connection...")
            checkAlreadyConnectedUsb(force = true)
        }
    }

    override fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice) {
        if (!UsbDeviceCompat.isAndroidDevice(device)) {
            AppLog.i("Ignoring USB permission callback for non-Android device (VID: ${device.vendorId}): ${device.deviceName}")
            return
        }
        val deviceName = UsbDeviceCompat(device).uniqueName
        if (granted) {
            AppLog.i("USB permission granted for $deviceName")
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                isSwitchingToAccessory.set(true)
                serviceScope.launch {
                    try {
                        connectUsbWithRetry(device)
                    } finally {
                        isSwitchingToAccessory.set(false)
                    }
                }
            } else {
                isSwitchingToAccessory.set(true)
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                val settings = App.provide(this).settings
                val usbMode = UsbAccessoryMode(usbManager)
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        if (usbMode.connectAndSwitch(device, settings.useLibusb)) {
                            AppLog.i("Successfully requested switch to accessory mode for $deviceName")
                        } else {
                            AppLog.w("USB permission granted but connectAndSwitch failed for $deviceName")
                        }
                    } finally {
                        isSwitchingToAccessory.set(false)
                    }
                }
            }
        } else {
            AppLog.w("USB permission denied for $deviceName")
            ToastUtils.showToast(this, getString(R.string.usb_permission_denied), Toast.LENGTH_LONG)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val permissionIntent = UsbReceiver.createPermissionPendingIntent(this)
        AppLog.i("Requesting USB permission for ${UsbDeviceCompat(device).uniqueName}")
        try {
            ToastUtils.showToast(this, getString(R.string.requesting_usb_permission), Toast.LENGTH_SHORT)
            usbManager.requestPermission(device, permissionIntent)
        } catch (e: Exception) {
            AppLog.e("Failed to request USB permission: ${e.message}. This device might not support USB permission dialogs.", e)
            ToastUtils.showToast(this, getString(R.string.error_usb_permission_failed), Toast.LENGTH_LONG)
        }
    }

    /**
     * Called when a handshake fails. If an accessory-mode device is still present,
     * it's likely a stale wireless AA dongle. Force re-enumeration by sending AOA
     * descriptors — this resets the dongle's USB state so the next connection
     * starts with clean buffers.
     */
    private fun onHandshakeFailed() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val accessoryDevice = usbManager.deviceList.values.firstOrNull {
            UsbDeviceCompat.isInAccessoryMode(it)
        } ?: return

        accessoryHandshakeFailures++
        val deviceName = UsbDeviceCompat(accessoryDevice).uniqueName
        AppLog.w("Handshake failed on accessory device $deviceName (failure #$accessoryHandshakeFailures)")

        if (accessoryHandshakeFailures > MAX_STALE_ACCESSORY_RETRIES) {
            AppLog.i("Stale accessory detected: forcing re-enumeration via AOA descriptors for $deviceName")
            accessoryHandshakeFailures = 0
            val settings = App.provide(this).settings
            val usbMode = UsbAccessoryMode(usbManager)
            isSwitchingToAccessory.set(true)
            serviceScope.launch(Dispatchers.IO) {
                try {
                    if (usbMode.connectAndSwitch(accessoryDevice, settings.useLibusb)) {
                        AppLog.i("AOA re-enumeration requested for stale device $deviceName")
                    } else {
                        AppLog.w("AOA re-enumeration failed for $deviceName")
                    }
                } catch (e: Exception) {
                    AppLog.e("AOA re-enumeration for $deviceName failed with exception", e)
                } finally {
                    isSwitchingToAccessory.set(false)
                }
            }
        }
    }

    /**
     * Tear down a live/in-progress wireless session so a cable attach can open AOA.
     * Native AA already aborts when USB is up; the reverse was missing after v1.6 SoftAP
     * made wireless stay Connected/Connecting and silently block [checkAlreadyConnectedUsb].
     */
    private suspend fun preemptWirelessForUsb() {
        AppLog.i(
            "AapService: Preempting wireless for USB attach " +
                "(state=${commManager.connectionState.value}, wireless=${commManager.isWirelessSession})"
        )
        nativeAaHandshakeManager?.stop()
        if (commManager.isBusy) {
            commManager.disconnect(sendByeBye = false, isUserExit = false)
            commManager.awaitDisconnectComplete()
        }
        delay(200)
    }

    /**
     * Scans currently connected USB devices and connects to any that are already in
     * Android Open Accessory (AOA) mode, or attempts to switch a known device into AOA mode.
     *
     * @param force When `true`, bypasses the [autoConnectLastSession] guard. Use `true` when
     *              called in response to an actual USB attach event or from [UsbAttachedActivity],
     *              because the user has explicitly plugged in a device. Use `false` (default)
     *              for the startup scan in [onCreate].
     * @param afterWirelessPreempt Internal: second pass after [preemptWirelessForUsb].
     */
    private fun checkAlreadyConnectedUsb(force: Boolean = false, afterWirelessPreempt: Boolean = false) {
        val settings = App.provide(this).settings
        val lastSession = settings.autoConnectLastSession
        val singleUsb = settings.autoConnectSingleUsbDevice
        val usbAutoStart = settings.autoStartOnUsb

        if (!force && !lastSession && !singleUsb && !usbAutoStart) return
        if (isSwitchingToAccessory.get()) return

        // USB projection already running — leave it alone.
        if (commManager.isConnected && !commManager.isWirelessSession) return

        if (commManager.isBusy) {
            if (!force) return
            if (afterWirelessPreempt) {
                AppLog.w("AapService: Still busy after wireless preempt — skipping USB this pass.")
                preemptingWirelessForUsb = false
                return
            }
            serviceScope.launch {
                preemptingWirelessForUsb = true
                try {
                    preemptWirelessForUsb()
                    checkAlreadyConnectedUsb(force = true, afterWirelessPreempt = true)
                } finally {
                    // If USB did not take over, put Native AA / SoftAP bring-up back.
                    if (!commManager.isBusy && !isSwitchingToAccessory.get()) {
                        preemptingWirelessForUsb = false
                        AppLog.i("AapService: USB did not connect after preempt — restoring wireless bring-up.")
                        initWifiMode(force = true)
                    } else {
                        preemptingWirelessForUsb = false
                    }
                }
            }
            return
        }

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList.values.filter { UsbDeviceCompat.isAndroidDevice(it) }

        // Check for devices already in accessory mode first.
        // After AOA switch the device re-enumerates and appears as a new USB device — we must
        // request permission for this new device before openDevice(), or SecurityException occurs.
        for (device in deviceList) {
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                val deviceName = UsbDeviceCompat(device).uniqueName
                AppLog.i("Found device already in accessory mode: $deviceName")
                if (!usbManager.hasPermission(device)) {
                    AppLog.i("Accessory-mode device has no permission (re-enumerated); requesting permission: $deviceName")
                    requestUsbPermission(device)
                    return
                }
                isSwitchingToAccessory.set(true)
                serviceScope.launch {
                    try {
                        connectUsbWithRetry(device)
                    } finally {
                        isSwitchingToAccessory.set(false)
                    }
                }
                return
            }
        }

        // Last-session mode: reconnect to a known/allowed device
        if (lastSession) {
            for (device in deviceList) {
                val deviceCompat = UsbDeviceCompat(device)
                if (settings.isConnectingDevice(deviceCompat)) {
                    if (usbManager.hasPermission(device)) {
                        AppLog.i("Found known USB device with permission: ${deviceCompat.uniqueName}. Switching to accessory mode.")
                        isSwitchingToAccessory.set(true)
                        val usbMode = UsbAccessoryMode(usbManager)
                        serviceScope.launch(Dispatchers.IO) {
                            try {
                                if (usbMode.connectAndSwitch(device, settings.useLibusb)) {
                                    AppLog.i("Successfully requested switch to accessory mode for ${deviceCompat.uniqueName}")
                                } else {
                                    AppLog.w("connectAndSwitch failed for ${deviceCompat.uniqueName}")
                                }
                            } finally {
                                isSwitchingToAccessory.set(false)
                            }
                        }
                        return
                    } else {
                        AppLog.i("Found known USB device but no permission: ${deviceCompat.uniqueName}, requesting...")
                        requestUsbPermission(device)
                        return
                    }
                }
            }
        }

        // USB auto-start mode: attempt AOA switch for any single non-accessory device
        if (usbAutoStart) {
            val nonAccessoryDevices = deviceList.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            if (nonAccessoryDevices.size == 1) {
                performSingleUsbConnect(nonAccessoryDevices[0])
                return
            }
        }

        // Single-USB mode: connect if there's exactly one candidate device.
        // If the user has marked specific devices as "Allowed" in the USB list,
        // only count those — so non-AA peripherals (dashcams, USB audio, etc.)
        // don't prevent auto-connect. Falls back to counting all devices when
        // no devices have been explicitly allowed (fresh install).
        if (singleUsb) {
            val nonAccessoryDevices = deviceList.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            val allowed = settings.allowedDevices
            val candidates = if (allowed.isNotEmpty()) {
                nonAccessoryDevices.filter { allowed.contains(UsbDeviceCompat(it).uniqueName) }
            } else {
                nonAccessoryDevices
            }
            if (allowed.isNotEmpty() && candidates.size != nonAccessoryDevices.size) {
                AppLog.i("Single USB auto-connect: ${nonAccessoryDevices.size} USB device(s) present, ${candidates.size} allowed")
            }
            if (candidates.size == 1) {
                performSingleUsbConnect(candidates[0])
                return
            }
        }

        // Fallback: if force=true and we have a single Google VID device in normal mode,
        // switch it to accessory mode. This handles cases where UsbAttachedActivity didn't fire.
        if (force) {
            val nonAccessoryDevices = deviceList.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            val googleDevices = nonAccessoryDevices.filter { it.vendorId == 0x18D1 }
            if (googleDevices.size == 1) {
                AppLog.i("Fallback: force=true and found single Google normal-mode device ${UsbDeviceCompat(googleDevices[0]).uniqueName}. Switching to accessory mode.")
                performSingleUsbConnect(googleDevices[0])
            }
        }
    }

    private fun performSingleUsbConnect(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            val deviceName = UsbDeviceCompat(device).uniqueName
            AppLog.i("Single USB auto-connect: connecting to $deviceName")
            isSwitchingToAccessory.set(true)
            val usbMode = UsbAccessoryMode(usbManager)
            serviceScope.launch(Dispatchers.IO) {
                try {
                    if (usbMode.connectAndSwitch(device, settings.useLibusb)) {
                        AppLog.i("Successfully requested switch to accessory mode for single USB device. Waiting for re-enumeration...")
                    } else {
                        AppLog.w("Single USB auto-connect: connectAndSwitch failed for $deviceName")
                    }
                } finally {
                    isSwitchingToAccessory.set(false)
                }
            }
        } else {
            AppLog.i("Single USB auto-connect: device found but no permission, requesting...")
            requestUsbPermission(device)
        }
    }

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    /**
     * Attempts a USB connection up to [maxRetries] times with a 1.5 s delay between attempts.
     *
     * USB accessories occasionally fail on the first attach (the device hasn't fully
     * enumerated yet), so retrying is necessary for reliability.
     */
    private suspend fun connectUsbWithRetry(device: UsbDevice, maxRetries: Int = 3) {
        var retryCount = 0
        var success = false
        while (retryCount <= maxRetries && !success) {
            if (retryCount > 0) {
                AppLog.i("Retrying USB connection (attempt ${retryCount + 1}/$maxRetries)...")
                delay(1500)
                // A USB reattach during the delay could have already started a new connection;
                // bail out to avoid two parallel retry loops competing on the same device.
                if (commManager.isConnected ||
                    commManager.connectionState.value is CommManager.ConnectionState.Connecting) return
            }
            commManager.connect(device)
            success = commManager.connectionState.value is CommManager.ConnectionState.Connected
            retryCount++
        }

        // Soft disconnect leaves the phone in AOA with a dead host session. openDevice can
        // succeed while the phone never speaks again, or fail until the bus is reset — the
        // physical unplug/replug users resort to. Force a USB reset so the phone re-enumerates
        // and onUsbAttach can complete a clean reconnect without touching the cable.
        if (!success && UsbDeviceCompat.isInAccessoryMode(device)) {
            AppLog.w(
                "USB connect failed on accessory ${UsbDeviceCompat(device).uniqueName} — " +
                    "bus-resetting for soft re-enumeration"
            )
            if (resetAccessoryUsbDevice(device)) {
                delay(2000)
                if (!commManager.isConnected) checkAlreadyConnectedUsb(force = true)
            }
        }
    }

    /**
     * Opens [device] briefly and issues a USB bus reset so a phone stuck in AOA after a soft
     * host disconnect re-enumerates (same effect as unplug/replug for reconnect purposes).
     */
    private fun resetAccessoryUsbDevice(device: UsbDevice): Boolean {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(device)) {
            AppLog.w("resetAccessoryUsbDevice: no permission for ${UsbDeviceCompat(device).uniqueName}")
            return false
        }
        var connection: android.hardware.usb.UsbDeviceConnection? = null
        var native: com.andrerinas.openheadunit.connection.UsbNative? = null
        return try {
            connection = usbManager.openDevice(device) ?: run {
                AppLog.w("resetAccessoryUsbDevice: openDevice returned null")
                return false
            }
            if (device.interfaceCount <= 0) return false
            val iface = device.getInterface(0)
            if (!connection.claimInterface(iface, true)) {
                AppLog.w("resetAccessoryUsbDevice: claimInterface failed")
                return false
            }
            var epIn = -1
            var epOut = -1
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                    if (epIn < 0) epIn = ep.address
                } else {
                    if (epOut < 0) epOut = ep.address
                }
            }
            if (epIn < 0 || epOut < 0) {
                AppLog.w("resetAccessoryUsbDevice: missing bulk endpoints")
                return false
            }
            native = com.andrerinas.openheadunit.connection.UsbNative()
            if (!native.wrap(connection, epIn, epOut)) {
                AppLog.w("resetAccessoryUsbDevice: UsbNative.wrap failed")
                return false
            }
            native.reset()
            AppLog.i("resetAccessoryUsbDevice: bus reset issued for ${UsbDeviceCompat(device).uniqueName}")
            true
        } catch (t: Throwable) {
            AppLog.e("resetAccessoryUsbDevice failed: ${t.message}", t)
            false
        } finally {
            try { native?.close() } catch (_: Throwable) {}
            try { connection?.close() } catch (_: Throwable) {}
        }
    }

    // -------------------------------------------------------------------------
    // Wireless
    // -------------------------------------------------------------------------

    /**
     * Starts the [WirelessServer] (TCP on port 5288) and kicks off the initial NSD scan.
     * No-op if the server is already running on the desired address.
     *
     * @param bindHost SoftAP / P2P IPv4 to bind (DiPlay), or null for `0.0.0.0`. When Native AA
     *   credentials publish a host IP, [onNativeCredentials] rebinds here before the BT poke.
     */
    private fun startWirelessServer(bindHost: String? = wirelessBindHost) {
        val desiredHost = WirelessServerBindPolicy.bindHostForAdvertisedIp(bindHost)
        wirelessBindHost = desiredHost
        val existing = wirelessServer

        // SoftAP IP known but listener still on wildcard / wrong address: force a rebuild even
        // though RestartPolicy would say NO_OP. That gap is what Skipperjonce2 saw as join-OK /
        // zero Incoming connection while DiPlay's SoftAP-bound AirPlay worked.
        val addressMismatch = existing?.isListening == true &&
            (WirelessServerBindPolicy.needsRebind(existing.boundHost, desiredHost) || existing.boundPort != wirelessPort)
        if (addressMismatch) {
            AppLog.i(
                "AapService: Wireless server is on ${existing?.boundHost ?: "0.0.0.0"}:${wirelessPort}; " +
                    "rebinding to SoftAP $desiredHost:${wirelessPort} (DiPlay-style)."
            )
            try { existing?.stopServer() } catch (e: Exception) {
                AppLog.d("AapService: Error stopping wireless server for SoftAP rebind: ${e.message}")
            }
            wirelessServer = null
        } else {
            val action = WirelessServerRestartPolicy.decide(
                assigned = existing != null,
                alive = existing?.isAlive == true,
                listening = existing?.isListening == true,
                nowMs = android.os.SystemClock.elapsedRealtime(),
                sessionBusy = commManager.isConnected,
                lastRebuildAtMs = lastWirelessRebuildAtMs,
                rebuildsInWindow = wirelessRebuildsInWindow,
                windowStartedAtMs = wirelessRebuildWindowStartedAtMs,
            )
            val why = WirelessServerRestartPolicy.describe(action, existing != null, existing?.isListening == true)
            when (action) {
                WirelessServerRestartPolicy.Action.NO_OP,
                WirelessServerRestartPolicy.Action.AWAIT -> {
                    AppLog.d("AapService: Wireless server not started - $why.")
                    return
                }
                WirelessServerRestartPolicy.Action.BACKOFF -> {
                    // INFO, not DEBUG. This is the state a stuck unit sits in, and the reporter logs
                    // that would have identified it are captured at INFO.
                    AppLog.i("AapService: Wireless server on ${wirelessPort} is not accepting connections - $why.")
                    return
                }
                WirelessServerRestartPolicy.Action.REBUILD -> {
                    val now = android.os.SystemClock.elapsedRealtime()
                    wirelessRebuildsInWindow = WirelessServerRestartPolicy.nextRebuildCount(
                        now, wirelessRebuildWindowStartedAtMs, wirelessRebuildsInWindow
                    )
                    wirelessRebuildWindowStartedAtMs =
                        WirelessServerRestartPolicy.nextWindowStart(now, wirelessRebuildWindowStartedAtMs)
                    lastWirelessRebuildAtMs = now
                    AppLog.w("AapService: Rebuilding the wireless server on ${wirelessPort} - $why (attempt $wirelessRebuildsInWindow).")
                    // Only this object, never stopWirelessServer(): that also resets the wireless
                    // initialization, and nothing has changed - we are repairing inside it.
                    try { existing?.stopServer() } catch (e: Exception) {
                        AppLog.d("AapService: Error stopping the previous wireless server: ${e.message}")
                    }
                    wirelessServer = null
                }
                WirelessServerRestartPolicy.Action.START -> {
                    AppLog.d("AapService: Starting the wireless server on ${wirelessPort} - $why.")
                }
            }
        }
        wirelessServer = WirelessServer().apply { start(bindHost = desiredHost) }
    }

    /** Credentials of the car hotspot the phone should join. */
    private fun onNativeCredentials(ssid: String, psk: String, ip: String, bssid: String) {
        val beforeHost = wirelessServer?.boundHost
        val listening = wirelessServer?.isListening == true
        AppLog.i(
            "AapService: Received WiFi credentials (SSID=$ssid, IP=$ip, BSSID=${bssid.ifEmpty { "<none>" }}). " +
                "listener=${if (listening) "up" else "down"} bound=${beforeHost ?: "0.0.0.0"}"
        )
        // DiPlay order: SoftAP IPv4 listener first, then publish credentials over BT. Binding
        // wildcard :5288 while advertising ap0's address left phones joined with no TCP accept.
        val softApHost = WirelessServerBindPolicy.bindHostForAdvertisedIp(ip)
        if (softApHost != null &&
            (WirelessServerBindPolicy.needsRebind(wirelessServer?.boundHost, softApHost) || (wirelessServer?.boundPort ?: wirelessPort) != wirelessPort)
        ) {
            AppLog.i(
                "AapService: SoftAP rebind needed — moving listener " +
                    "${beforeHost ?: "0.0.0.0"}:${wirelessPort} → $softApHost:${wirelessPort} (DiPlay-style)."
            )
            startWirelessServer(softApHost)
        } else if (softApHost != null) {
            wirelessBindHost = softApHost
            AppLog.i(
                "AapService: Wireless listener already on SoftAP $softApHost:${wirelessPort} — no rebind " +
                    "(listening=$listening)."
            )
        } else {
            AppLog.w("AapService: Credentials arrived with empty IP — leaving wildcard bind.")
        }
        nativeAaHandshakeManager?.updateWifiCredentials(ssid, psk, ip, bssid)
        if (commManager.isConnected ||
            commManager.connectionState.value is CommManager.ConnectionState.Connecting) {
            AppLog.i("AapService: USB/other session already active. Skipping auto-poke to avoid pulling phone into wireless flow.")
        } else if (!userExitedAA) {
            nativeAaHandshakeManager?.triggerPoke()
        } else {
            AppLog.i("AapService: userExitedAA is true. Skipping auto-poke.")
        }
    }

    /**
     * Whether the AAP TCP port the phone will be sent to is bound and accepting.
     *
     * The Bluetooth handshake checks this before handing over credentials, mirroring the ordering
     * the reference head unit software uses: access point up, address resolved, port bound, and
     * only then talk to the phone.
     */
    fun isWirelessServerListening(): Boolean = wirelessServer?.isListening == true

    /**
     * DiPlay-style readiness: listening **and** bound to [advertisedIp] when credentials name a
     * SoftAP host. A wildcard `:5288` must not count as ready once the phone is told to dial
     * `192.168.x.x:5288`.
     */
    fun isWirelessServerReadyFor(advertisedIp: String?): Boolean {
        val server = wirelessServer
        if (server?.isListening != true) return false
        val ready = !(WirelessServerBindPolicy.needsRebind(server.boundHost, advertisedIp) || server.boundPort != wirelessPort)
        if (!ready) {
            AppLog.d(
                "AapService: SoftAP not ready — listening on ${server.boundHost ?: "0.0.0.0"}:${wirelessPort} " +
                    "but phone will dial ${advertisedIp ?: "<none>"}:${wirelessPort}"
            )
        }
        return ready
    }

    /**
     * Tries to get the AAP port bound, and reports whether it is.
     *
     * Called by the Bluetooth handshake when it finds the port unbound with credentials already in
     * hand. Until this existed the handshake could only give up, so a server that died once stayed
     * dead for the life of the mode: the phone was woken, told to join a network, and left dialling
     * a port nothing was listening on, every few seconds, indefinitely.
     *
     * The start is marshalled onto Main because every other caller of [startWirelessServer] runs
     * there. Without that, this one arrives from `Dispatchers.IO` and can pass the "nothing is
     * assigned" check at the same moment [initWifiMode] does, and both bind. `SO_REUSEADDR` does not
     * help there - it covers a port in TIME_WAIT, not one with a live listener on it - so the loser
     * throws and spends its retry budget losing to its own sibling.
     *
     * @param reason what asked, for the log.
     * @param timeoutMs how long to wait for the bind after asking.
     */
    suspend fun ensureWirelessServerListening(reason: String, timeoutMs: Long): Boolean {
        val desired = wirelessBindHost
        val listeningOnDesired = isWirelessServerListening() &&
            !(WirelessServerBindPolicy.needsRebind(wirelessServer?.boundHost, desired) || (wirelessServer?.boundPort ?: wirelessPort) != wirelessPort)
        if (listeningOnDesired) return true
        AppLog.i(
            "AapService: $reason found port ${wirelessPort} unbound" +
                (desired?.let { " (want SoftAP $it)" } ?: "") +
                ". Trying to start the wireless server."
        )
        withContext(Dispatchers.Main.immediate) { startWirelessServer(desired) }

        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (isWirelessServerListening() &&
                !(WirelessServerBindPolicy.needsRebind(wirelessServer?.boundHost, desired) || (wirelessServer?.boundPort ?: wirelessPort) != wirelessPort)
            ) {
                AppLog.i(
                    "AapService: port ${wirelessPort} is bound now" +
                        (wirelessServer?.boundHost?.let { " on $it" } ?: "") +
                        "."
                )
                return true
            }
            delay(250)
        }
        AppLog.w("AapService: port ${wirelessPort} is still not bound ${timeoutMs}ms after trying to start it.")
        return false
    }
    /**
     * Closes an active session before the car shuts down, while the link still works.
     *
     * Android Auto's head unit server is wedged permanently by a peer that vanishes without
     * closing, and only a restart on the phone clears it. A session that closes properly does not
     * do that.
     *
     * [pendingResult] keeps the broadcast alive while the teardown runs, because it does socket
     * work and the caller is a receiver on the main thread. It is always finished.
     */
    private fun tearDownBeforeShutdown(pendingResult: () -> BroadcastReceiver.PendingResult) {
        if (!commManager.isConnected) return

        val pending = pendingResult()
        val startedAt = SystemClock.elapsedRealtime()
        AppLog.i("AapService: shutdown with a live session — closing it now, while the link still works.")
        Thread {
            try {
                commManager.disconnectForLinkLoss(LINK_LOSS_TEARDOWN_BUDGET_MS)
                AppLog.i(
                    "AapService: link-loss teardown finished in " +
                        "${SystemClock.elapsedRealtime() - startedAt}ms"
                )
            } catch (e: Exception) {
                AppLog.e("AapService: link-loss teardown failed", e)
            } finally {
                try { pending.finish() } catch (e: Exception) {}
            }
        }.apply { name = "AapService-LinkLossTeardown"; start() }
    }

    fun refreshHotspotCredentialsAfterJoinFailure() {
        // A delayed report from an old handshake must not alter a live session. This only
        // re-reads the car AP; it never restarts its radio.
        if (commManager.isBusy) return
        AppLog.i("AapService: Phone could not join the hotspot — discarding credentials and resolving the access point again.")
        softApCredentialsProvider?.refresh()
    }

    fun refreshAccessPoint() {
        AppLog.i("AapService: Access point refresh requested.")
        softApCredentialsProvider?.refresh()
    }

    private fun stopWirelessServer() {
        wirelessInitialized = false
        wirelessServer?.stopServer()
        wirelessServer = null
        wirelessBindHost = null
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotification(): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AapService::class.java).apply { action = ACTION_STOP_SERVICE },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Tap the notification to go back to the projection screen (if connected) or home
        val (notificationIntent, requestCode) = if (commManager.isConnected) {
            AapProjectionActivity.intent(this).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } to 100
        } else {
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } to 101
        }

        val contentText = if (commManager.isConnected)
            getString(R.string.notification_projection_active)
        else
            getString(R.string.notification_service_running)

        return NotificationCompat.Builder(this, App.defaultChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentTitle("Open Headunit")
            .setContentText(contentText)
            .setContentIntent(PendingIntent.getActivity(
                this, requestCode, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            ))
            .addAction(R.drawable.ic_exit_to_app_white_24dp, getString(R.string.exit), stopPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification())
    }

    /**
     * Launch MainActivity after boot using a cascading fallback chain designed
     * to work across stock AOSP head units, Xiaomi MIUI/HyperOS, Samsung One UI,
     * Huawei EMUI, OPPO ColorOS, and other OEM ROMs.
     *
     * Strategy order:
     * 1. Direct startActivity (Android < 10, or any device without background
     *    activity restrictions — works on most head units running AOSP)
     * 2. Overlay window trampoline (Android 10+): creates a zero-size invisible
     *    overlay giving the app a "visible" context. Bypasses MIUI, EMUI, ColorOS
     *    background start restrictions. Requires SYSTEM_ALERT_WINDOW.
     * 3. Full-screen intent notification (Android 10+): high-priority notification
     *    with fullScreenIntent. Works on stock Android 10-13 and Samsung. On
     *    Android 14+ needs USE_FULL_SCREEN_INTENT permission.
     * 4. Tap-to-open notification (last resort): user taps notification to open.
     */
    /**
     * Launches MainActivity when reopenOnReconnection is enabled and no activity is currently
     * visible. Uses the same overlay trampoline technique as boot auto-start to bypass OEM
     * background activity start restrictions.
     */
    private fun launchMainActivityIfNeeded(source: String) {
        val settings = App.provide(this).settings
        if (!settings.autoStartOnUsb || !settings.reopenOnReconnection) return

        AppLog.i("Reopen on reconnection: launching MainActivity ($source)")
        launchMainActivityOnBoot()
    }

    /**
     * At car start the app used to open its own screen (and its "connecting" overlay) on top of the
     * launcher. Like the stock Android Auto, it now stays in the background: the Bluetooth
     * auto-start connects the phone, and the projection comes forward only once the phone has
     * started Android Auto, which follows the phone's own "Start Android Auto automatically"
     * setting. [Settings.showAppOnCarStart] brings the old behaviour back.
     */
    private fun launchUiOnCarStart(reason: String) {
        if (!App.provide(this).settings.showAppOnCarStart) {
            AppLog.i("AapService: car start ($reason) — staying in the background, the projection opens once the phone connects")
            return
        }
        launchMainActivityOnBoot()
    }

    private fun launchMainActivityOnBoot() {
        // Android < 10: no background activity start restrictions
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            AppLog.i("Boot auto-start: launching directly (API ${Build.VERSION.SDK_INT} < 29)")
            launchDirectly()
            return
        }

        // Android 10+: try overlay trampoline (bypasses all known OEM restrictions)
        if (AppPermissions.isOverlayGranted(this)) {
            AppLog.i("Boot auto-start: launching via overlay window trampoline")
            if (launchViaOverlayTrampoline()) return
        }

        // Fallback: full-screen intent notification
        AppLog.i("Boot auto-start: falling back to full-screen intent notification")
        launchViaFullScreenIntent()
    }

    private fun launchDirectly() {
        try {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Boot auto-start")
            }
            startActivity(launchIntent)
            AppLog.i("Boot auto-start: direct startActivity succeeded")
        } catch (e: Exception) {
            AppLog.e("Boot auto-start: direct startActivity failed: ${e.message}")
            launchViaFullScreenIntent()
        }
    }

    private fun launchViaOverlayTrampoline(): Boolean {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Boot auto-start")
        }
        return launchViaOverlayTrampoline(launchIntent)
    }

    private fun launchViaOverlayTrampoline(launchIntent: Intent): Boolean {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            0, 0, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        val view = View(this)
        return try {
            wm.addView(view, params)
            startActivity(launchIntent)
            AppLog.i("Overlay trampoline: startActivity succeeded")
            true
        } catch (e: Exception) {
            AppLog.e("Overlay trampoline failed: ${e.message}")
            false
        } finally {
            try { wm.removeView(view) } catch (_: Exception) {}
        }
    }

    private fun launchViaFullScreenIntent() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Boot auto-start")
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val fullScreenPi = PendingIntent.getActivity(this, 200, launchIntent, piFlags)

        val notification = NotificationCompat.Builder(this, App.bootStartChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_service_running))
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(BOOT_START_NOTIFICATION_ID, notification)

        // Dismiss the boot notification after a short delay
        serviceScope.launch {
            delay(5000)
            nm.cancel(BOOT_START_NOTIFICATION_ID)
        }
    }

    // -------------------------------------------------------------------------
    // Native AA Bluetooth server
    // -------------------------------------------------------------------------

    /** The phone opened the Native AA Bluetooth channel: this head unit accepts it. */
    fun onNativeAaServerAccepted() {
        val settings = App.provide(this).settings
        if (settings.nativeAaServerFailures != 0) settings.nativeAaServerFailures = 0
    }

    /**
     * The Native AA Bluetooth server died without a connection, as on head units whose Bluetooth
     * library allows one app RFCOMM session (SWI68 R46): logged only. Neither the freed-hands-free
     * retry nor an automatic move to Headunit Server helped there, so both were removed; the fix
     * is a newer firmware (SWI68 R63 connects).
     */
    fun onNativeAaServerFailed() {
        val settings = App.provide(this).settings
        val failures = settings.nativeAaServerFailures + 1
        settings.nativeAaServerFailures = failures
        AppLog.w("AapService: Native AA Bluetooth server failed ($failures in a row, firmware " +
            "${com.evsuite.hardware.FirmwareInfo.getDetectedString()})")
    }

    // -------------------------------------------------------------------------
    // WirelessServer
    // -------------------------------------------------------------------------

    /**
     * Coroutine-based server that listens for incoming TCP connections on port 5288.
     *
     * Each accepted socket is handed
     * off to [CommManager.connect] on the service coroutine scope. Only one connection
     * is allowed at a time; subsequent sockets are closed immediately.
     *
     * Uses [isActive] for cooperative cancellation. [stopServer] cancels the job and
     * closes the server socket to unblock the blocking [ServerSocket.accept] call.
     */
    private inner class WirelessServer {
        private var serverSocket: ServerSocket? = null
        private var job: Job? = null

        /**
         * Whether the TCP port the phone is told to dial is actually bound right now.
         *
         * start() only launches a coroutine; the bind happens inside it and can fail (the port
         * still held by a previous session is the usual way). Handing the phone credentials for a
         * port nothing is listening on produces the worst possible log: a clean handshake, a
         * successful WiFi join, and then silence.
         */
        @Volatile var isListening = false
            private set

        /**
         * Host this listener is bound to, or null for the wildcard (`0.0.0.0`). Native AA SoftAP
         * must match the IP advertised over Bluetooth (DiPlay AirPlay bind behaviour).
         */
        @Volatile var boundHost: String? = null
            private set

        /** Port this listener is bound to (0 until bound); a different chosen port rebuilds the listener. */
        @Volatile var boundPort: Int = 0
            private set

        /**
         * Whether the coroutine that owns the bind is still running.
         *
         * [isListening] alone cannot separate "binding, give it a moment" from "died and will never
         * bind"; both are false. Replacing a server on the strength of that would tear down one that
         * was about to succeed, and the replacement would then race it for the same port.
         */
        val isAlive: Boolean get() = job?.isActive == true

        fun start(bindHost: String? = null) {
            val hostLabel = bindHost ?: "0.0.0.0"
            // Outside the coroutine on purpose. Everything below runs on a scope that can already
            // be cancelled, in which case the block never executes and prints nothing at all; this
            // line is what tells a reader the difference between "never asked" and "asked, and the
            // answer never came". Two complete reporter captures could not be told apart without it.
            AppLog.i("WirelessServer: binding $hostLabel:${wirelessPort}...")

            job = serviceScope.launch(Dispatchers.IO) {
                try {
                    // Unbound first, then the option, then bind. ServerSocket(int) binds inside the
                    // constructor, so setting reuseAddress after it is a no-op on a socket that is
                    // already bound - which is the whole failure this line was written to prevent.
                    // The previous peer's connection sits in TIME_WAIT for minutes after a session
                    // ends, so a re-init within that window threw BindException, isListening stayed
                    // false, and the next handshake woke the phone over Bluetooth and handed it
                    // nothing (NativeAaHandshakeManager aborts with "nothing is listening on 5288").
                    var bound: ServerSocket? = null
                    var attempt = 0
                    val listenPort = wirelessPort
                    while (isActive && bound == null) {
                        attempt++
                        try {
                            bound = ServerSocket().apply {
                                reuseAddress = true
                                // SoftAP IPv4 when known (DiPlay); wildcard only before that.
                                bind(
                                    if (bindHost != null) java.net.InetSocketAddress(bindHost, listenPort)
                                    else java.net.InetSocketAddress(listenPort)
                                )
                            }
                        } catch (e: Exception) {
                            // The last attempt rethrows, so a permanent failure still reaches the
                            // catch below and is reported as an error rather than disappearing.
                            if (attempt >= BIND_ATTEMPTS) throw e
                            AppLog.w("WirelessServer: $hostLabel:${wirelessPort} did not bind on attempt $attempt of $BIND_ATTEMPTS (${e.javaClass.simpleName}: ${e.message}). Retrying in ${BIND_RETRY_DELAY_MS}ms.")
                            delay(BIND_RETRY_DELAY_MS)
                        }
                    }
                    if (bound == null) {
                        AppLog.i("WirelessServer: stopped before $hostLabel:${wirelessPort} could be bound.")
                        return@launch
                    }
                    serverSocket = bound
                    boundHost = bindHost
                    boundPort = listenPort
                    isListening = true
                    // A bind that worked ends the rebuild budget: the next failure, whenever it
                    // comes, is a fresh one and gets its own attempts.
                    lastWirelessRebuildAtMs = 0L
                    wirelessRebuildsInWindow = 0
                    wirelessRebuildWindowStartedAtMs = 0L
                    AppLog.i("Wireless Server listening on $hostLabel:${wirelessPort}")
                    AppLog.i(
                        "WirelessServer: bound=${bound.isBound} " +
                            "local=${bound.localSocketAddress} — port is open in this app. " +
                            "Phone reachability is separate: join OK without Incoming usually means " +
                            "SoftAP blocks phone→car TCP (isolation/firewall)."
                    )
                    logLocalNetworkInterfaces()

                    while (isActive) {
                        AppLog.d("WirelessServer: Waiting for TCP connection on $hostLabel:${wirelessPort}...")
                        val clientSocket = serverSocket?.accept() ?: break
                        AppLog.i("WirelessServer: Incoming connection detected from ${clientSocket.inetAddress}")
                        com.andrerinas.openheadunit.connection.ArpNudger.stop()
                        if (!com.andrerinas.openheadunit.connection.WirelessPeerPolicy.allowed(clientSocket)) {
                            AppLog.w("WirelessServer: refusing a connection that did not come through the car's access point (interface " +
                                com.andrerinas.openheadunit.connection.WirelessPeerPolicy.interfaceOf(clientSocket) + ")")
                            try { clientSocket.close() } catch (e: Exception) {}
                            continue
                        }
                        serviceScope.launch {
                            if (commManager.isBusy || isSwitchingToAccessory.get() || preemptingWirelessForUsb) {
                                AppLog.w(
                                    "WirelessServer: Busy/USB-preempt, dropping client from " +
                                        "${clientSocket.inetAddress} (state=${commManager.connectionState.value})"
                                )
                                withContext(Dispatchers.IO) {
                                    try { clientSocket.close() } catch (e: Exception) {}
                                }
                            } else if (android.os.SystemClock.elapsedRealtime() < userExitCooldownUntil) {
                                // [FIX] User just exited AA — reject the instant reconnection.
                                AppLog.w("WirelessServer: Rejecting connection from ${clientSocket.inetAddress} — user exit cooldown active (${userExitCooldownUntil - android.os.SystemClock.elapsedRealtime()}ms remaining)")
                                withContext(Dispatchers.IO) {
                                    try { clientSocket.close() } catch (e: Exception) {}
                                }
                            } else {
                                AppLog.i("WirelessServer: Accepted client connection from ${clientSocket.inetAddress}. Passing to CommManager...")
                                userExitedAA = false // Clear flag on genuine new connection
                                commManager.connect(clientSocket)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // The cancelled branch used to be silent, which made a server that was torn
                    // down indistinguishable from one that was never started.
                    if (isActive) AppLog.e("Wireless server error", e)
                    else AppLog.i("WirelessServer: port ${wirelessPort} released (${e.javaClass.simpleName}).")
                } finally {
                    isListening = false
                    boundHost = null
                    boundPort = 0
                    try { serverSocket?.close() } catch (e: Exception) {}
                }
            }
        }

        /** Logs all non-loopback IPv4 addresses; useful for debugging connectivity issues. */
        private fun logLocalNetworkInterfaces() {
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            AppLog.i("Interface: ${iface.name}, IP: ${addr.hostAddress}")
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.e("Error logging interfaces", e)
            }
        }

        fun stopServer() {
            job?.cancel()
            job = null
            // Close the socket to unblock the accept() call in the coroutine.
            try { serverSocket?.close() } catch (e: Exception) {}
        }
    }

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------

    /**
     * Waits out a user-exit cooldown (if any), then arms Native AA and pokes [mac].
     * The home-screen pill shows a countdown so the user does not need to know about the gap.
     */
    /**
     * The car's Bluetooth reconnects the phone only at start, and the phone does not retry when it
     * comes back in range, so leaving the car on and walking away for a while left it without a
     * phone (and without Android Auto) until someone reconnected Bluetooth by hand. While the car
     * is on with no session and the remembered phone is not linked over Bluetooth, poke it every
     * 30 s; the poke opens the Bluetooth link, and Bluetooth auto-start does the rest. A phone
     * that is linked but has not started Android Auto is left alone: that is its own setting.
     */
    private fun startPhoneReconnectWatchdog() {
        phoneReconnectJob?.cancel()
        phoneReconnectJob = serviceScope.launch {
            while (isActive) {
                delay(PHONE_RECONNECT_INTERVAL_MS)
                val settings = App.provide(this@AapService).settings
                if (!wirelessEnabled() || userExitedAA) continue
                if (commManager.isBusy || nativeAaPokeJob?.isActive == true) continue
                val mac = settings.autoStartBluetoothDeviceMacs.firstOrNull() ?: continue
                val linked = try {
                    BluetoothHelper.connectedBondedDevices(this@AapService)
                        .any { it.address.equals(mac, ignoreCase = true) }
                } catch (_: SecurityException) {
                    true
                }
                if (linked) continue
                // connectedBondedDevices can miss a phone that has just come back; a Bluetooth
                // connect in the last few minutes means the handshake is under way, so let it run.
                if (SystemClock.elapsedRealtime() - lastPhoneBtConnectMs < RECENT_BT_CONNECT_MS) continue
                AppLog.i("AapService: remembered phone $mac is not linked and no session is up — poking it again")
                lastWatchdogPokeMs = SystemClock.elapsedRealtime()
                scheduleNativeAaPoke(mac, reason = "reconnect watchdog")
            }
        }
    }

    private fun scheduleNativeAaPoke(mac: String, reason: String) {
        nativeAaPokeJob?.cancel()
        nativeAaPokeJob = serviceScope.launch {
            awaitUserExitCooldown(reason)
            armNativeAaForPoke()
            AppLog.i("AapService: $reason — poking $mac")
            nativeAaHandshakeManager?.manualPoke(mac)
        }
    }

    private fun scheduleNativeAaArm(reason: String) {
        nativeAaPokeJob?.cancel()
        nativeAaPokeJob = serviceScope.launch {
            awaitUserExitCooldown(reason)
            armNativeAaForPoke()
        }
    }

    private suspend fun awaitUserExitCooldown(reason: String) {
        while (true) {
            val remaining = userExitCooldownUntil - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            val secs = ((remaining + 999L) / 1000L).toInt().coerceAtLeast(1)
            AppLog.i("AapService: $reason waiting ${remaining}ms after disconnect ($secs s left)")
            publishAutoConnectStatus(getString(R.string.da_waiting_after_disconnect, secs))
            delay(minOf(1_000L, remaining))
        }
        userExitedAA = false
        userExitCooldownUntil = 0L
        publishAutoConnectStatus(getString(R.string.da_connecting))
    }

    private fun armNativeAaForPoke() {
        if (!wirelessInitialized) {
            AppLog.i("AapService: Initializing Native AA mode before poke...")
            initWifiMode(force = true)
        } else if (nativeAaHandshakeManager?.isActive() != true) {
            // A completed handoff closes the AA listeners while leaving the manager
            // running, and start() returns immediately on isRunning - so calling it here
            // reopened nothing. The poke then woke the phone, the phone opened RFCOMM,
            // and nothing was listening: the button appeared to do nothing however many
            // times it was pressed. A full re-init is what reopens them, which is what
            // the Bluetooth auto-start path already does for the same reason.
            AppLog.i("AapService: Native AA listeners are closed — re-arming before the poke.")
            initWifiMode(force = true)
        } else {
            AppLog.d("AapService: Already in Native AA mode, skipping re-init.")
            startWirelessServer()
            nativeAaHandshakeManager?.start()
        }
    }

    private fun publishAutoConnectStatus(text: String) {
        com.andrerinas.openheadunit.main.MainActivity.autoConnectStatusText = text
        sendBroadcast(Intent(ACTION_AUTO_CONNECT_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_AUTO_CONNECT_STATUS, text)
        })
    }


    companion object {
        /** Bind attempts before the wireless server gives up and reports the port unusable. */
        private const val BIND_ATTEMPTS = 3

        /** Gap between them. A port released by a peer that just left frees within this. */
        private const val BIND_RETRY_DELAY_MS = 700L

        /**
         * If set to `true`, the service will call [System.exit] at the very end of [onDestroy].
         * This is used by `killOnDisconnect` to ensure all cleanup (like Car Mode) completes
         * before the process dies.
         */
        var killProcessOnDestroy: Boolean = false

        private const val BOOT_START_NOTIFICATION_ID = 42
        private const val PROJECTION_LAUNCH_NOTIFICATION_ID = 43

        // Service action strings used with startService() and sendBroadcast()
        const val ACTION_START_WIRELESS            = "com.andrerinas.openheadunit.ACTION_START_WIRELESS"
        const val ACTION_BT_AUTO_START              = "com.andrerinas.openheadunit.ACTION_BT_AUTO_START"
        const val ACTION_START_WIRELESS_SCAN       = "com.andrerinas.openheadunit.ACTION_START_WIRELESS_SCAN"
        const val ACTION_STOP_WIRELESS             = "com.andrerinas.openheadunit.ACTION_STOP_WIRELESS"
        const val ACTION_NATIVE_AA_POKE            = "com.andrerinas.openheadunit.ACTION_NATIVE_AA_POKE"
        /** Pill/overlay status text while waiting out the post-disconnect cooldown. */
        const val ACTION_AUTO_CONNECT_STATUS       = "com.andrerinas.openheadunit.ACTION_AUTO_CONNECT_STATUS"
        const val EXTRA_AUTO_CONNECT_STATUS        = "auto_connect_status"
        const val ACTION_CHECK_USB                 = "com.andrerinas.openheadunit.ACTION_CHECK_USB"
        const val ACTION_STOP_SERVICE              = "com.andrerinas.openheadunit.aap.action.STOP_SERVICE"
        const val ACTION_DISCONNECT                = "com.andrerinas.openheadunit.ACTION_DISCONNECT"
        const val ACTION_REQUEST_NIGHT_MODE_UPDATE = "com.andrerinas.openheadunit.aap.action.REQUEST_NIGHT_MODE_UPDATE"
        const val ACTION_NIGHT_MODE_CHANGED      = "com.andrerinas.openheadunit.ACTION_NIGHT_MODE_CHANGED"
        const val ACTION_ORIENTATION_CHANGED     = "com.andrerinas.openheadunit.ACTION_ORIENTATION_CHANGED"
        const val ACTION_REFRESH_SENSORS         = "com.andrerinas.openheadunit.aap.action.REFRESH_SENSORS"
        const val ACTION_RESTART_AUDIO           = "com.andrerinas.openheadunit.aap.action.RESTART_AUDIO"
        /**
         * Sent after the caller has already invoked [CommManager.connect(socket)].
         * The [observeConnectionState] flow observer handles the result — [onStartCommand]
         * does nothing for this action.
         */
        const val ACTION_CONNECT_SOCKET            = "com.andrerinas.openheadunit.ACTION_CONNECT_SOCKET"

        /** Max handshake failures on a stale accessory device before forcing AOA re-enumeration. */
        private const val MAX_STALE_ACCESSORY_RETRIES = 1

        /** Delay before retrying USB connection after an unexpected disconnect. */
        private const val USB_RECONNECT_DELAY_MS = 3000L

        /** Cap automatic reconnect / discovery restarts after unexpected disconnects. */
        private const val MAX_AUTOMATIC_RECONNECT_ATTEMPTS = 5
        /**
         * How long a link-loss teardown may take. The interface is already on its way down and the
         * broadcast is holding the system up, so this is a budget rather than a target: the
         * ByeBye and the socket close together take well under 200 ms when the link still works,
         * and when it does not there is nothing to wait for.
         */
        private const val LINK_LOSS_TEARDOWN_BUDGET_MS = 1500L

        /** Cooldown after user-initiated exit. SoftAP restart on MG4 often needs ~10–20s;
         *  poking while :5288 / hotspot are still down fails with a short wait. */
        private const val USER_EXIT_COOLDOWN_MS = 15_000L
        private const val PROMPT_SOURCE_CHECK_DELAY_MS = 1_500L
        private const val PHONE_RECONNECT_INTERVAL_MS = 30_000L
        private const val WATCHDOG_POKE_SETTLE_MS = 30_000L
        private const val RECENT_BT_CONNECT_MS = 180_000L
        private const val TRACK_SKIP_FLUSH_DELAY_MS = 250L
        private val CLAIM_CHECK_DELAYS_MS = longArrayOf(3_000L, 5_000L)

        /** Delay before AapService tries to handle a normal-mode USB attach as a fallback
         *  when UsbAttachedActivity doesn't fire (common on Chinese MediaTek headunits). */
        private const val USB_ATTACH_FALLBACK_DELAY_MS = 2000L

        /** Screen-off duration (ms) above which SCREEN_ON is treated as a hibernate wake.
         *  60 seconds filters out normal screen timeouts while catching any hibernate/quick boot. */
        private const val HIBERNATE_WAKE_THRESHOLD_MS = 60_000L

        const val EXTRA_MAC = "extra_mac"
    }
}
