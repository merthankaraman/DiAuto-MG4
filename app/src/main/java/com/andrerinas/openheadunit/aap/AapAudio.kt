package com.andrerinas.openheadunit.aap

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.evsuite.hardware.saic.SaicCarAudioSource
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.aap.protocol.AudioConfigs
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.decoder.AudioDecoder
import com.andrerinas.openheadunit.decoder.MediaAudioBuffer
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.Settings

internal class AapAudio(
        private val audioDecoder: AudioDecoder,
        private val audioManager: AudioManager,
        private val settings: Settings,
        private val context: Context) {

    /**
     * MEDIA focus must be attributed to AllGo AA on eh32 so SystemUI's media card
     * leaves radio; notification/voice keep the process [audioManager].
     */
    private val mediaAudioManager: AudioManager =
        SaicCarAudioSource.audioManager(context) ?: audioManager

    private val staticAudioFocus = settings.staticAudioFocus

    // Hybrid Bluetooth music: keep assistant/nav off STREAM_MUSIC so Gemini is not lost under A2DP.
    private val separateAudioStreams =
        settings.separateAudioStreams || settings.musicViaBluetooth

    init {
        // Separate streams: prompts and the assistant get their own channel, the car's navigation
        // one (mixed over the radio). Otherwise they play in the multimedia stream with the music.
        com.andrerinas.openheadunit.decoder.SpeechRoute.navigationChannel = separateAudioStreams
    }

    private val mediaVolumeOffset = settings.mediaVolumeOffset
    // On their own channel the voice is never boosted: the phone sends it near full scale, and the
    // car's navigation volume sets its level cleanly. Only mixed with the music can it be raised.
    private val assistantVolumeOffset = if (separateAudioStreams) 0 else settings.assistantVolumeOffset
    private val navigationVolumeOffset = if (separateAudioStreams) 0 else settings.navigationVolumeOffset
    private val audioLatencyMultiplier = settings.audioLatencyMultiplier
    private val useAacAudio = settings.useAacAudio
    private val audioQueueCapacity = settings.audioQueueCapacity
    private val enableAudioSink = settings.enableAudioSink
    private val attachHwDspEqualizer = settings.attachHwDspEqualizer
    private val mediaBufferMillis = settings.mediaAudioBufferMillis
    private fun playbackFocusMode() = settings.effectivePlaybackFocusMode(context)

    /**
     * Hybrid sets focus mode NEVER so A2DP music is not AVRCP-paused. Assistant (AU1) and
     * system/nav (AU2) still need focus or Gemini/TTS stays on the phone speaker.
     */
    private fun playbackFocusModeForChannel(channel: Int) =
        if (settings.musicViaBluetooth &&
            (channel == Channel.ID_AU1 || channel == Channel.ID_AU2)
        ) {
            PlaybackFocusPolicy.Mode.ALWAYS
        } else {
            playbackFocusMode()
        }

    private var audioFocusRequest: AudioFocusRequest? = null
    /** Kind of [audioFocusRequest]: a held media request does not cover a prompt. */
    private var audioFocusKind: ProtocolFocusRoute.Kind? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    /** Which focus changes come from the car and have to reach the phone (see [CarFocusTracker]). */
    private val carFocus = CarFocusTracker()
    private var audioFocusToken: Any? = null
    @Volatile
    private var playbackFocusToken: Any? = null

    /** Set by [AapTransport]: tells the phone the car took (or gave back) audio focus. */
    var onPhoneFocusState: ((Control.AudioFocusNotification.AudioFocusStateType) -> Unit)? = null

    /** Set by [AapTransport]: the phone's music started playing over Wi-Fi. */
    var onMediaPlaybackStarted: (() -> Unit)? = null

    /** Set by [AapTransport]: a guidance or voice prompt ended while the music keeps playing. */
    var onSpeechEndedDuringMedia: (() -> Unit)? = null

    val isMediaPlaying: Boolean
        get() = audioDecoder.getTrack(Channel.ID_AUD) != null

    private fun trackedFocusListener(
        token: Any,
        inner: AudioManager.OnAudioFocusChangeListener
    ) = AudioManager.OnAudioFocusChangeListener { focusChange ->
        inner.onAudioFocusChange(focusChange)
        if (isOwnFocusChurn(focusChange)) return@OnAudioFocusChangeListener
        carFocus.onFocusChange(token, focusChange)?.let { state ->
            AppLog.i("AapAudio: the car changed audio focus ($focusChange) - telling the phone $state")
            onPhoneFocusState?.invoke(state)
        }
    }

    @Volatile
    private var lastOwnFocusRequestMs = 0L

    /** Called before the service claims the car source, so the losses that claim causes stay here. */
    fun markOwnFocusRequest() {
        lastOwnFocusRequestMs = SystemClock.elapsedRealtime()
    }

    /** When the car or the user last took the audio focus away and the phone was told. */
    @Volatile
    var lastCarFocusLossMs = 0L
        private set

    /**
     * A loss right after one of this app's own focus requests (the phone's, the playback one, or
     * the car-source claim) is that request taking over, not the car: passing it on paused the
     * phone 0.1 s after its music started. Losses that do reach the phone are timestamped.
     */
    private fun isOwnFocusChurn(focusChange: Int): Boolean {
        if (focusChange >= 0) return false
        val own = SystemClock.elapsedRealtime() - lastOwnFocusRequestMs < OWN_REQUEST_GRACE_MS ||
            com.evsuite.hardware.saic.SaicCarAudioSource.claimedWithin(OWN_REQUEST_GRACE_MS)
        if (own) {
            AppLog.i("AapAudio: focus change $focusChange comes from this app's own request - not telling the phone")
        } else {
            lastCarFocusLossMs = SystemClock.elapsedRealtime()
        }
        return own
    }

    /**
     * A change on the car-source request ([com.evsuite.hardware.saic.SaicCarAudioSource]). It is
     * the only focus held once the phone's own requests are released, so when the car or a
     * launcher switches to the radio the phone was never told and kept "playing" in silence.
     * A loss right after one of our own requests is that request taking over, not the car.
     */
    fun onCarSourceFocusChange(focusChange: Int) {
        if (isOwnFocusChurn(focusChange)) return
        if (focusChange < 0) {
            // The stock launcher sends the steering-wheel arrows to the radio's session, which grabs
            // focus for a moment until a launcher hands the source back with PLAY. Passing that
            // loss on at once stopped the phone, and a PLAY alone never restarted it. Wait.
            pendingCarLoss?.let { handler.removeCallbacks(it) }
            val forward = Runnable {
                pendingCarLoss = null
                forwardCarSourceFocusChange(focusChange)
            }
            pendingCarLoss = forward
            handler.postDelayed(forward, CAR_LOSS_DEBOUNCE_MS)
            return
        }
        pendingCarLoss?.let {
            handler.removeCallbacks(it)
            pendingCarLoss = null
            AppLog.i("AapAudio: the car gave the Android Auto source focus back before the phone was told")
        }
        forwardCarSourceFocusChange(focusChange)
    }

    private fun forwardCarSourceFocusChange(focusChange: Int) {
        carFocus.onUntrackedFocusChange(focusChange)?.let { state ->
            AppLog.i("AapAudio: the car changed the Android Auto source focus ($focusChange) - telling the phone $state")
            onPhoneFocusState?.invoke(state)
        }
    }

    @Volatile
    private var pendingCarLoss: Runnable? = null

    /**
     * The user (a steering-wheel key, a launcher's PLAY) wants this app's music again. A car loss
     * still waiting is dropped; one the phone was already told is undone with a GAIN, since after
     * STATE_LOSS the phone ignores a PLAY key on its own. Returns whether anything was taken back.
     */
    fun resumeFromUser(): Boolean {
        var resumed = false
        pendingCarLoss?.let {
            handler.removeCallbacks(it)
            pendingCarLoss = null
            resumed = true
        }
        if (carFocus.takeBackAfterLoss()) {
            AppLog.i("AapAudio: play asked after a car focus loss - telling the phone STATE_GAIN")
            onPhoneFocusState?.invoke(Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN)
            resumed = true
        }
        return resumed
    }

    /** A change on the static path's permanent request, the only focus this app holds there. */
    fun onPermanentFocusChange(focusChange: Int) {
        carFocus.onUntrackedFocusChange(focusChange)?.let { state ->
            AppLog.i("AapAudio: the car changed permanent audio focus ($focusChange) - telling the phone $state")
            onPhoneFocusState?.invoke(state)
        }
    }

    @Volatile
    private var playbackFocusRequest: AudioFocusRequest? = null
    @Volatile
    private var legacyPlaybackFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    // Dynamic-mode playback focus: some phones never send an AudioFocusRequestNotification(GAIN)
    // when media starts (the always-grant handshake makes them assume the head unit always holds
    // focus), so relying on the protocol notification alone leaves the car radio playing alongside
    // AA audio. Instead we hold system audio focus for as long as any AA audio channel is actively
    // playing and release it when the last one stops. Static mode manages focus permanently and is
    // left untouched.
    //
    // Whether we take it at all is PlaybackFocusPolicy's call: on a head unit that is also the
    // phone's Bluetooth A2DP sink, the focus grab makes the sink service AVRCP-pause that same
    // phone, silencing the stream we are playing.
    private val activeAudioChannels = mutableSetOf<Int>()
    private val playbackFocusListener = AudioManager.OnAudioFocusChangeListener {
        AppLog.i("AapAudio: playback audio focus changed: $it")
    }

    @Volatile
    private var holdingPlaybackFocus = false
    @Volatile
    private var focusAcquiredAtMs = 0L
    @Volatile
    private var selfDefeatingStops = 0
    @Volatile
    private var selfDefeatingLatched = false

    @Volatile
    private var isDucked = false
    private val handler = Handler(Looper.getMainLooper())
    private val unduckRunnable = Runnable {
        unduckMedia()
    }

    private fun duckMedia() {
        if (!isDucked) {
            val mediaTrack = audioDecoder.getTrack(Channel.ID_AUD)
            if (mediaTrack != null) {
                val duckedVolume = getMediaGain() * DUCK_VOLUME_FACTOR
                AppLog.i("Static Audio Focus: Ducking media volume to $duckedVolume")
                mediaTrack.setVolume(duckedVolume)
                isDucked = true
            }
        }
    }

    private fun unduckMedia() {
        if (isDucked) {
            val mediaTrack = audioDecoder.getTrack(Channel.ID_AUD)
            if (mediaTrack != null) {
                val originalVolume = getMediaGain()
                AppLog.i("Static Audio Focus: Restoring media volume to $originalVolume")
                mediaTrack.setVolume(originalVolume)
            }
            isDucked = false
        }
    }

    private fun getMediaGain(): Float {
        return (1.0f + (mediaVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)
    }

    /**
     * Whether a focus request the phone asked for over the protocol should reach the system.
     *
     * [AapControl] answers every AudioFocusRequestNotification with an always-grant reply, which is
     * what keeps AA routing audio to us; separately it asks the system for the focus the phone
     * wanted, so other apps on the head unit duck. That second half is the same act that makes an
     * A2DP sink AVRCP-pause the phone we project, and it honours GAIN as a *permanent* grab, so it
     * needs the same rule as the playback path.
     *
     * RELEASE is never gated: abandoning focus must always work, or a grab from before the link
     * came up would be stranded for the rest of the session.
     *
     * The latch does not extend here — it is armed in [onAudioPlaybackStopped], which only runs
     * while the playback path holds focus. On a unit whose Bluetooth probe reads nothing, this path
     * has no automatic backstop and needs the mode set to NEVER by hand.
     */
    fun shouldHonourProtocolFocusRequest(isRelease: Boolean, isMedia: Boolean = true): Boolean {
        if (isRelease) return true

        val btMediaLinkActive = BluetoothHelper.isA2dpMediaLinkActive(context)
        // Hybrid NEVER is for media/A2DP only. Protocol focus (Gemini, nav prompts) must still
        // duck the car so TTS is not left on the phone speaker.
        // Prompts and the assistant only duck or pause briefly: the latch, which guards the
        // phone's music, must not leave guidance silent under the radio (seen 2026-10-27).
        val mode = if (settings.musicViaBluetooth) {
            PlaybackFocusPolicy.Mode.ALWAYS
        } else {
            PlaybackFocusPolicy.speechMode(playbackFocusMode(), isSpeech = !isMedia)
        }
        val honour = PlaybackFocusPolicy.shouldAcquire(
                mode = mode,
                staticAudioFocus = staticAudioFocus,
                audioSinkEnabled = enableAudioSink,
                isAudioChannel = true,
                btMediaLinkActive = btMediaLinkActive,
                selfDefeatingLatched = selfDefeatingLatched)

        if (!honour) {
            AppLog.i("AapAudio: phone asked for audio focus - leaving system audio focus alone " +
                    "(mode=$mode, bluetoothMedia=$btMediaLinkActive, latched=$selfDefeatingLatched)")
        }
        return honour
    }

    fun requestFocusChange(stream: Int, focusRequest: Int, callback: AudioManager.OnAudioFocusChangeListener): Int {
        lastOwnFocusRequestMs = SystemClock.elapsedRealtime()
        // Notification/voice GAIN_TRANSIENT pauses A2DP (AVRCP) on this HU; duck instead.
        val effectiveFocus =
            if (focusRequest == Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_VALUE &&
                (stream == AudioManager.STREAM_NOTIFICATION || stream == AudioManager.STREAM_VOICE_CALL)
            ) {
                AppLog.i("Audio Focus Request: coercing GAIN_TRANSIENT → MAY_DUCK for stream=$stream")
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_MAY_DUCK_VALUE
            } else {
                focusRequest
            }
        AppLog.i("Audio Focus Request: stream=$stream, type=$effectiveFocus")

        var result = AudioManager.AUDIOFOCUS_REQUEST_FAILED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // API 26+
            if (effectiveFocus == Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE_VALUE) {
                AppLog.i("Releasing audio focus")
                audioFocusRequest?.let {
                    runCatching { mediaAudioManager.abandonAudioFocusRequest(it) }
                    runCatching { audioManager.abandonAudioFocusRequest(it) }
                }
                audioFocusRequest = null
                audioFocusKind = null
                audioFocusToken?.let { carFocus.abandoned(it) }
                audioFocusToken = null
                result = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                // One kind per request type, as MG's own Android Auto asks (see ProtocolFocusRoute).
                val kind = ProtocolFocusRoute.kind(stream, effectiveFocus)
                if (!ProtocolFocusRoute.asksSystem(kind, settings.musicViaBluetooth)) {
                    AppLog.i("Audio Focus Request: music is on the car's Bluetooth - leaving system focus and source alone")
                    return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }
                val tagsSource = ProtocolFocusRoute.tagsCarSource(kind, settings.musicViaBluetooth)
                val audioAttributes = if (tagsSource) {
                    // Tag MEDIA focus as AA (70) so MG4 eh32 amp leaves radio/BT for Wi‑Fi PCM.
                    SaicCarAudioSource.mediaAttributes()
                } else {
                    AudioAttributes.Builder()
                        .setUsage(kind.usage)
                        .setContentType(
                            if (kind == ProtocolFocusRoute.Kind.MEDIA) AudioAttributes.CONTENT_TYPE_MUSIC
                            else AudioAttributes.CONTENT_TYPE_SPEECH
                        )
                        .build()
                }
                AppLog.i("Audio Focus Request: ${kind.name} (usage=${kind.usage}, carSource=$tagsSource)")
                val usage = kind.usage

                val focusAm = if (tagsSource) mediaAudioManager else audioManager
                val token = Any()
                audioFocusRequest = AudioFocusRequest.Builder(effectiveFocus)
                        .setAudioAttributes(audioAttributes)
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(trackedFocusListener(token, callback))
                        .build()
                audioFocusToken = token
                audioFocusKind = kind
                carFocus.requested(token, media = usage == AudioAttributes.USAGE_MEDIA)
                if (kind == ProtocolFocusRoute.Kind.GUIDANCE || kind == ProtocolFocusRoute.Kind.NOTIFICATION) armSpeechIdleCheck()

                result = focusAm.requestAudioFocus(audioFocusRequest!!)
                AppLog.i("Audio focus request result: ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED ($result)"}")
            }
        } else { // API < 26
            @Suppress("DEPRECATION")
            result = when (effectiveFocus) {
                Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE_VALUE -> {
                    audioManager.abandonAudioFocus(callback)
                    legacyFocusListener?.let { audioManager.abandonAudioFocus(it) }
                    legacyFocusListener = null
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_VALUE -> {
                    legacyFocusListener = callback
                    mediaAudioManager.requestAudioFocus(callback, stream, AudioManager.AUDIOFOCUS_GAIN)
                }
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_VALUE -> {
                    legacyFocusListener = callback
                    mediaAudioManager.requestAudioFocus(callback, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_MAY_DUCK_VALUE -> {
                    legacyFocusListener = callback
                    mediaAudioManager.requestAudioFocus(callback, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                }
                else -> AudioManager.AUDIOFOCUS_REQUEST_FAILED
            }
            AppLog.i("Audio focus request result (legacy): ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED"}")
        }
        return result
    }

    private fun requestPlaybackFocus(channel: Int) {
        lastOwnFocusRequestMs = SystemClock.elapsedRealtime()
        val speech = channel == Channel.ID_AU1 || channel == Channel.ID_AU2
        // Speech/notification must MAY_DUCK so hybrid A2DP music is not AVRCP-paused.
        val focusGain = if (speech) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = if (speech) {
                // Guidance, not voice call: the MG4 mixes it over the radio, which a voice-call
                // request silenced until the next key press (2026-10-27).
                val usage = if (channel == Channel.ID_AU1) {
                    AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                } else {
                    AudioAttributes.USAGE_NOTIFICATION
                }
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            } else {
                SaicCarAudioSource.mediaAttributes()
            }
            val focusAm = if (speech) audioManager else mediaAudioManager
            val token = Any()
            val request = AudioFocusRequest.Builder(focusGain)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(trackedFocusListener(token, playbackFocusListener))
                    .build()
            playbackFocusRequest = request
            playbackFocusToken = token
            carFocus.requested(token, media = !speech)

            val result = focusAm.requestAudioFocus(request)
            AppLog.i(
                "AapAudio: Playback focus (${Channel.name(channel)}, " +
                    "${if (speech) "MAY_DUCK" else "TRANSIENT"}) result: " +
                    "${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED ($result)"}",
            )
        } else {
            @Suppress("DEPRECATION")
            legacyPlaybackFocusListener = playbackFocusListener
            val stream = AudioConfigs.stream(channel, separateAudioStreams)
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(playbackFocusListener, stream, focusGain)
            AppLog.i(
                "AapAudio: Playback focus legacy (${Channel.name(channel)}) result: " +
                    "${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED"}",
            )
        }
    }

    private fun releasePlaybackFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            playbackFocusRequest?.let {
                AppLog.i("AapAudio: Releasing playback transient audio focus")
                // Speech may have been requested on process AM; media on SAIC AM.
                runCatching { mediaAudioManager.abandonAudioFocusRequest(it) }
                runCatching { audioManager.abandonAudioFocusRequest(it) }
            }
            playbackFocusRequest = null
            playbackFocusToken?.let { carFocus.abandoned(it) }
            playbackFocusToken = null
        } else {
            @Suppress("DEPRECATION")
            legacyPlaybackFocusListener?.let {
                AppLog.i("AapAudio: Releasing playback transient audio focus (legacy)")
                runCatching { mediaAudioManager.abandonAudioFocus(it) }
                runCatching { audioManager.abandonAudioFocus(it) }
            }
            legacyPlaybackFocusListener = null
        }
    }

    /**
     * Native AA keeps A2DP up through bootstrap, so the first media channel often starts while
     * [PlaybackFocusPolicy.Mode.AUTO] correctly skips focus (to avoid AVRCP-pausing the phone).
     * After the Wi‑Fi media path drops A2DP, call this so the car radio does not keep the amp
     * for the rest of the session.
     */
    fun reclaimPlaybackFocusIfNeeded() {
        if (staticAudioFocus || !enableAudioSink) return
        if (holdingPlaybackFocus) return
        val channel = synchronized(activeAudioChannels) {
            when {
                activeAudioChannels.isEmpty() -> return
                Channel.ID_AUD in activeAudioChannels -> Channel.ID_AUD
                else -> activeAudioChannels.first()
            }
        }
        val btMediaLinkActive = BluetoothHelper.isA2dpMediaLinkActive(context)
        val mode = playbackFocusModeForChannel(channel)
        val acquire = PlaybackFocusPolicy.shouldAcquire(
            mode = mode,
            staticAudioFocus = staticAudioFocus,
            audioSinkEnabled = enableAudioSink,
            isAudioChannel = true,
            btMediaLinkActive = btMediaLinkActive,
            selfDefeatingLatched = selfDefeatingLatched,
        )
        if (!acquire) {
            AppLog.i(
                "AapAudio: reclaim skipped after BT media release " +
                    "(mode=$mode, bluetoothMedia=$btMediaLinkActive, latched=$selfDefeatingLatched)",
            )
            return
        }
        AppLog.i(
            "AapAudio: reclaiming transient system audio focus after BT media release " +
                "(${Channel.name(channel)}, mode=$mode)",
        )
        focusAcquiredAtMs = SystemClock.elapsedRealtime()
        holdingPlaybackFocus = true
        requestPlaybackFocus(channel)
    }

    fun releaseAllFocus() {
        AppLog.i("AapAudio: Releasing all audio focus.")
        pendingCarLoss?.let { handler.removeCallbacks(it) }
        pendingCarLoss = null
        synchronized(activeAudioChannels) { activeAudioChannels.clear() }
        // The latch is a property of one connection, not of the head unit: re-arm it so a session
        // that reconnects with Bluetooth off gets the car-radio behaviour back.
        holdingPlaybackFocus = false
        focusAcquiredAtMs = 0L
        selfDefeatingStops = 0
        selfDefeatingLatched = false
        carFocus.clear()
        audioFocusToken = null
        playbackFocusToken = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { mediaAudioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
            speechFocusRequests.values.forEach { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            speechFocusRequests.clear()
            audioFocusKind = null
            playbackFocusRequest?.let { mediaAudioManager.abandonAudioFocusRequest(it) }
            playbackFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            legacyFocusListener?.let { mediaAudioManager.abandonAudioFocus(it) }
            legacyFocusListener = null
            @Suppress("DEPRECATION")
            legacyPlaybackFocusListener?.let { mediaAudioManager.abandonAudioFocus(it) }
            legacyPlaybackFocusListener = null
        }
    }

    /**
     * Processes a message as an audio stream packet.
     * Returns true if the packet was identified and processed as audio data, false otherwise.
     */
    /** Set by [process]: the last media chunk will be acked by its track once played (see AudioDecoder.mediaAck). */
    var lastChunkAckDeferred = false
        private set

    fun process(message: AapMessage): Boolean {
        // Media stream packets have msgType 0 or 1.
        // Control packets on audio channels (Setup, Start, Stop) have types > 32767.
        lastChunkAckDeferred = false
        if (message.type == 0 || message.type == 1) {
            if (message.size >= 10) {
                lastChunkAckDeferred = decode(message.channel, 10, message.data, message.size - 10)
            }
            return true
        }
        return false
    }

    private fun startAudioTrack(channel: Int) {
        if (audioDecoder.getTrack(channel) != null) return

        val config = AudioConfigs.get(channel)
        val stream = AudioConfigs.stream(channel, separateAudioStreams)
        // AAC only on MEDIA — matches Service Discovery (SPEECH/SYSTEM are PCM).
        val channelUsesAac = useAacAudio && channel == Channel.ID_AUD

        val offset = when (channel) {
            Channel.ID_AUD -> mediaVolumeOffset
            Channel.ID_AU1 -> assistantVolumeOffset
            Channel.ID_AU2 -> navigationVolumeOffset
            else -> 0
        }
        val gain = (1.0f + (offset / 100.0f)).coerceIn(0.0f, 3.0f)

        // Voice and Navigation benefit from lower latency. Cap the multiplier for those channels.
        // MEDIA over Wi‑Fi also gets a DiPlay-style jitter start buffer (nav/speech stay immediate).
        val effectiveMultiplier = if (channel == Channel.ID_AUD) {
            audioLatencyMultiplier
        } else {
            audioLatencyMultiplier.coerceAtMost(4)
        }
        val jitterMs = if (channel == Channel.ID_AUD) {
            MediaAudioBuffer.sanitize(mediaBufferMillis)
        } else {
            0
        }

        AppLog.i(
            "AudioDecoder.start: channel=$channel, stream=$stream, gain=$gain, " +
                "sampleRate=${config.sampleRate}, numberOfBits=${config.numberOfBits}, " +
                "numberOfChannels=${config.numberOfChannels}, isAac=$channelUsesAac, " +
                "latencyMultiplier=$effectiveMultiplier, queueCapacity=$audioQueueCapacity, " +
                "mediaJitterMs=$jitterMs, attachHwDspEqualizer=$attachHwDspEqualizer",
        )
        audioDecoder.start(
            channel,
            stream,
            config.sampleRate,
            config.numberOfBits,
            config.numberOfChannels,
            channelUsesAac,
            gain,
            effectiveMultiplier,
            audioQueueCapacity,
            staticAudioFocus,
            attachHwDspEqualizer,
            mediaBufferMillis = jitterMs,
        )
        onAudioPlaybackStarted(channel)
        // The phone is playing music over Wi-Fi: the moment to take the car's media source.
        if (channel == Channel.ID_AUD) onMediaPlaybackStarted?.invoke()
    }

    private val speechFocusRequests = java.util.concurrent.ConcurrentHashMap<Int, AudioFocusRequest>()

    /**
     * A prompt's own guidance request, kept apart from the music's: the MG4 opens its navigation
     * channel (and lowers the radio or the music) only while a guidance request is held, and the
     * prompt audio plays on that channel. Without it the prompt is silent on every source.
     */
    private fun requestSpeechFocus(channel: Int) {
        // Only the navigation channel needs it; on the media path the prompt plays with the music.
        if (!com.andrerinas.openheadunit.decoder.SpeechRoute.navigationChannel) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || speechFocusRequests.containsKey(channel)) return
        lastOwnFocusRequestMs = SystemClock.elapsedRealtime()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        speechFocusRequests[channel] = request
        val result = audioManager.requestAudioFocus(request)
        AppLog.i("AapAudio: AA audio started (${Channel.name(channel)}) - guidance focus result=$result")
        armSpeechIdleCheck()
    }

    private var speechLastActiveMs = 0L
    private val speechIdleCheck = Runnable { checkSpeechIdle() }

    /**
     * A prompt's focus is given back when the phone stops the stream or releases its request; when
     * that message does not come, the radio or the music stayed lowered until the next prompt
     * (2026-10-28). Once no prompt audio has arrived for a few seconds, the guidance focus (never
     * the assistant's, which listens in silence) is given back here.
     */
    private fun armSpeechIdleCheck() {
        speechLastActiveMs = SystemClock.elapsedRealtime()
        handler.removeCallbacks(speechIdleCheck)
        handler.postDelayed(speechIdleCheck, SPEECH_IDLE_POLL_MS)
    }

    private fun checkSpeechIdle() {
        val now = SystemClock.elapsedRealtime()
        if (listOf(Channel.ID_AU1, Channel.ID_AU2).any { audioDecoder.getTrack(it)?.isReceivingAudio() == true }) {
            speechLastActiveMs = now
        }
        val guidanceHeld = audioFocusRequest != null &&
            (audioFocusKind == ProtocolFocusRoute.Kind.GUIDANCE || audioFocusKind == ProtocolFocusRoute.Kind.NOTIFICATION)
        if (speechFocusRequests.isEmpty() && !guidanceHeld) return
        if (now - speechLastActiveMs < SPEECH_IDLE_RELEASE_MS) {
            handler.postDelayed(speechIdleCheck, SPEECH_IDLE_POLL_MS)
            return
        }
        AppLog.i("AapAudio: no prompt audio for ${SPEECH_IDLE_RELEASE_MS / 1000}s - giving the guidance focus back")
        speechFocusRequests.keys.toList().forEach { releaseSpeechFocus(it) }
        if (guidanceHeld && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                runCatching { mediaAudioManager.abandonAudioFocusRequest(it) }
                runCatching { audioManager.abandonAudioFocusRequest(it) }
            }
            audioFocusRequest = null
            audioFocusKind = null
            audioFocusToken?.let { carFocus.abandoned(it) }
            audioFocusToken = null
        }
    }

    private fun releaseSpeechFocus(channel: Int) {
        val request = speechFocusRequests.remove(channel) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { audioManager.abandonAudioFocusRequest(request) }
        }
        AppLog.i("AapAudio: ${Channel.name(channel)} stopped - guidance focus released")
    }

    private fun onAudioPlaybackStarted(channel: Int) {
        if (staticAudioFocus || !enableAudioSink || !Channel.isAudio(channel)) return
        // A prompt the phone already asked focus for on the control channel needs nothing more:
        // a second request made the SAIC radio drop focus and stay silent after it. The phone does
        // not always ask, though, and then the prompt asks here, with the same guidance usage.
        if (channel != Channel.ID_AUD) {
            if (audioFocusRequest != null && audioFocusKind != null && audioFocusKind != ProtocolFocusRoute.Kind.MEDIA) {
                AppLog.i("AapAudio: AA audio started (${Channel.name(channel)}) - the phone's focus request already covers it")
            } else {
                requestSpeechFocus(channel)
            }
            return
        }

        // Only the set mutation belongs under the lock. requestPlaybackFocus() is a binder
        // round-trip to AudioService and the Bluetooth probe below is another, and this runs on
        // the transport thread for every track change.
        val wasEmpty = synchronized(activeAudioChannels) {
            val empty = activeAudioChannels.isEmpty()
            activeAudioChannels.add(channel)
            empty
        }
        if (!wasEmpty) return

        val btMediaLinkActive = BluetoothHelper.isA2dpMediaLinkActive(context)
        val mode = PlaybackFocusPolicy.speechMode(
            playbackFocusModeForChannel(channel), isSpeech = channel != Channel.ID_AUD)
        val acquire = PlaybackFocusPolicy.shouldAcquire(
                mode = mode,
                staticAudioFocus = staticAudioFocus,
                audioSinkEnabled = enableAudioSink,
                isAudioChannel = true,
                btMediaLinkActive = btMediaLinkActive,
                selfDefeatingLatched = selfDefeatingLatched)

        if (!acquire) {
            AppLog.i("AapAudio: AA audio started (${Channel.name(channel)}) - leaving system audio focus alone " +
                    "(mode=$mode, bluetoothMedia=$btMediaLinkActive, latched=$selfDefeatingLatched)")
            return
        }

        // Use GAIN_TRANSIENT, not GAIN: a permanent GAIN sends other players (e.g. the car
        // radio) a permanent AUDIOFOCUS_LOSS, so they stop and do NOT resume when we later
        // abandon focus. TRANSIENT sends AUDIOFOCUS_LOSS_TRANSIENT so they pause and resume
        // once AA audio stops and we release focus.
        AppLog.i("AapAudio: AA audio started (${Channel.name(channel)}) - acquiring transient system audio focus " +
                "(mode=$mode, bluetoothMedia=$btMediaLinkActive)")
        focusAcquiredAtMs = SystemClock.elapsedRealtime()
        holdingPlaybackFocus = true
        requestPlaybackFocus(channel)
    }

    /**
     * Releases system audio focus once the last active AA audio channel stops (dynamic mode),
     * letting other local sources (e.g. the car radio) resume.
     */
    private fun onAudioPlaybackStopped(channel: Int) {
        // The car may switch its source for a prompt and not come back to Android Auto; the
        // service checks. Before the gates below: static focus mode has the same problem.
        if ((channel == Channel.ID_AU1 || channel == Channel.ID_AU2) && isMediaPlaying) {
            onSpeechEndedDuringMedia?.invoke()
        }
        if (staticAudioFocus || !enableAudioSink || !Channel.isAudio(channel)) return
        if (channel != Channel.ID_AUD) {
            releaseSpeechFocus(channel)
            return
        }

        val nowEmpty = synchronized(activeAudioChannels) {
            activeAudioChannels.remove(channel)
            activeAudioChannels.isEmpty()
        }
        if (!nowEmpty || !holdingPlaybackFocus) return

        noteStopWhileHoldingFocus(channel)

        AppLog.i("AapAudio: last AA audio channel stopped - releasing transient system audio focus")
        holdingPlaybackFocus = false
        releasePlaybackFocus()
    }

    /**
     * Watches for the pathology the Bluetooth probe is meant to pre-empt, for the units where it
     * cannot see the link: the media channel closing again almost as soon as we took focus, because
     * the phone stopped its own playback in response. Two of those in a row and we stop asking for
     * focus, so the session settles instead of cycling every few seconds.
     */
    private fun noteStopWhileHoldingFocus(channel: Int) {
        if (selfDefeatingLatched || focusAcquiredAtMs == 0L) return

        val elapsedMs = SystemClock.elapsedRealtime() - focusAcquiredAtMs
        if (!PlaybackFocusPolicy.countsAsSelfDefeating(channel == Channel.ID_AUD, elapsedMs)) {
            selfDefeatingStops = 0
            return
        }

        selfDefeatingStops++
        if (AppLog.LOG_DEBUG) {
            AppLog.d("AapAudio: media stopped ${elapsedMs}ms after taking audio focus " +
                    "($selfDefeatingStops/${PlaybackFocusPolicy.SELF_DEFEATING_LIMIT})")
        }
        if (selfDefeatingStops >= PlaybackFocusPolicy.SELF_DEFEATING_LIMIT) {
            selfDefeatingLatched = true
            AppLog.w("AapAudio: taking system audio focus is stopping the phone's own playback " +
                    "(the head unit is most likely its Bluetooth audio sink) - not acquiring it again this session")
        }
    }

    fun precreateAudioTrack(channel: Int) {
        if (!staticAudioFocus) return
        if (channel != Channel.ID_AU2) return
        startAudioTrack(channel)
    }

    private fun decode(channel: Int, start: Int, buf: ByteArray, len: Int): Boolean {
        var length = len
        if (length > AUDIO_BUFS_SIZE) {
            AppLog.e("Error audio len: %d  aud_buf_BUFS_SIZE: %d", length, AUDIO_BUFS_SIZE)
            length = AUDIO_BUFS_SIZE
        }

        if (audioDecoder.getTrack(channel) == null) {
            startAudioTrack(channel)
        }

        val deferred = audioDecoder.decode(channel, buf, start, length)

        if ((channel == Channel.ID_AU1 || channel == Channel.ID_AU2) && staticAudioFocus) {
            duckMedia()
            handler.removeCallbacks(unduckRunnable)
            handler.postDelayed(unduckRunnable, UNDUCK_DELAY_MS)
        }
        return deferred
    }

    fun updateGains() {
        val mediaGain = (1.0f + (settings.mediaVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)
        val assistantGain = if (separateAudioStreams) 1.0f
            else (1.0f + (settings.assistantVolumeOffset / 100.0f)).coerceIn(0.0f, 3.0f)
        val navGain = if (separateAudioStreams) 1.0f
            else (1.0f + (settings.navigationVolumeOffset / 100.0f)).coerceIn(0.0f, 3.0f)

        audioDecoder.setGain(Channel.ID_AUD, mediaGain)
        audioDecoder.setGain(Channel.ID_AU1, assistantGain)
        audioDecoder.setGain(Channel.ID_AU2, navGain)
    }

    fun restartAudio() {
        AppLog.i("AapAudio: Restarting all audio tracks")
        audioDecoder.stop()
    }

    fun stopAudio(channel: Int) {
        AppLog.i("Audio Stop: " + Channel.name(channel))
        if ((channel == Channel.ID_AU1 || channel == Channel.ID_AU2) && staticAudioFocus) {
            // Keep the speech wrappers alive to prevent recreate overhead and keep state consistent.
            // Just restore media volume.
            handler.removeCallbacks(unduckRunnable)
            unduckMedia()
        } else {
            audioDecoder.stop(channel)
            onAudioPlaybackStopped(channel)
        }
    }

    companion object {
        private const val AUDIO_BUFS_SIZE = 65536 * 4  // Up to 256 Kbytes
        private const val DUCK_VOLUME_FACTOR = 0.4f
        private const val UNDUCK_DELAY_MS = 1500L
        private const val OWN_REQUEST_GRACE_MS = 1_000L
        private const val CAR_LOSS_DEBOUNCE_MS = 1_500L
        private const val SPEECH_IDLE_POLL_MS = 1_000L
        private const val SPEECH_IDLE_RELEASE_MS = 3_000L
    }
}
