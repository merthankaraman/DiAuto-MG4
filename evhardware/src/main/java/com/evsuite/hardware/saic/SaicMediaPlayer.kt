package com.evsuite.hardware.saic

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import com.evsuite.hardware.AppLogger

/**
 * Next track, previous track, play/pause — addressed to the source that is actually playing.
 *
 * ## Why this is not one `dispatchMediaKeyEvent`
 *
 * The obvious implementation is the wrong one on this head unit, and quietly so. The car
 * publishes a single Android media session (`com.android.bluetooth`), so a media key either
 * reaches nothing — radio, USB and the projection stacks do not listen for one — or it
 * reaches Bluetooth, **wakes a phone that was not playing, and changes the audio source under
 * the driver's hands** while the radio was on. A key sent from a rule at 110 km/h is exactly
 * the wrong place to discover that.
 *
 * What the car's own launcher does instead is ask which source is current and talk to that
 * source's player. That is what this does.
 *
 * ## The rule that keeps it safe
 *
 * **No cascade between sources.** Trying each player in turn looks robust and is the bug: a
 * player that is not playing answers "yes" to `play` and starts, which is the source change
 * again. One source is chosen, one player is addressed, and a failure is reported as a
 * failure. Fallbacks exist only *within* the chosen source, or where nothing at all could be
 * identified.
 *
 * ## Firmware split
 *
 * SWI68/SWI165 answer through the SAIC media service below. The A9 generations (SWI69,
 * SWI131, SWI132) have no such service — their launcher drives the framework's media
 * sessions, so [sessionCommand] is the normal path there, not a consolation prize. Both ends
 * fall back to the media key, and only while something is audible.
 *
 * Binding contract: `MediaService` (bind target and the per-player actions),
 * `IMediaPlayerBinderInterface` and its siblings (transaction codes), `MediaConstants`
 * (source and player-status values), and `com.allgo.rui.IRemoteUIService` for the projection
 * stack, which is not part of the SAIC SDK at all.
 */
object SaicMediaPlayer {

    private const val TAG = "EV_MEDIA"

    private const val MEDIA_PACKAGE = "com.saicmotor.service.media"
    private const val MEDIA_CLASS = "com.saicmotor.service.media.MediaService"

    private const val ACTION_MEDIA = "com.saicmotor.service.media.MEDIA_PLAYER_ACTION"
    private const val ACTION_CPAA = "com.saicmotor.service.media.CPAA_PLAYER_ACTION"
    private const val ACTION_BT = "com.saicmotor.service.media.BT_MUSIC_ACTION"
    private const val ACTION_USB = "com.saicmotor.service.media.MUSIC_PLAYER_ACTION"
    private const val ACTION_ONLINE = "com.saicmotor.service.media.ONLINE_MUSIC_ACTION"
    private const val ACTION_STATUS = "com.saicmotor.service.media.PLAY_STATUS_ACTION"

    private const val DESC_MEDIA = "com.saicmotor.sdk.media.IMediaPlayerBinderInterface"
    private const val DESC_CPAA = "com.saicmotor.sdk.media.ICpAaBinderInterface"
    private const val DESC_BT = "com.saicmotor.sdk.media.IBtMusicBinderInterface"
    private const val DESC_USB = "com.saicmotor.sdk.media.IMusicPlayerBinderInterface"
    private const val DESC_ONLINE = "com.saicmotor.sdk.media.IOnlineMusicBinderInterface"
    private const val DESC_STATUS = "com.saicmotor.sdk.media.IPlayStatusBinderInterface"

    /**
     * `MediaConstants` source codes. 1..4 are the tuner's own bands — 1 radio, 2 FM, 3 AM,
     * **4 DAB** — which is why DAB needs no separate handling here: it is the radio.
     */
    private const val SRC_RADIO_MIN = 1
    private const val SRC_RADIO_MAX = 4
    private const val SRC_BT = 5
    private const val SRC_ONLINE = 6
    private const val SRC_USB = 7
    private const val SRC_USB_VIDEO = 0x12
    private const val SRC_CARPLAY = 0x32
    private const val SRC_AA = 0x46

    /** Of the player statuses, only START means "playing". */
    private const val PLAYER_STATUS_START = 3

    // Status interface — which source owns the audio right now.
    private const val TX_CURRENT_SOURCE = 0x9

    // Per-player transaction codes, each in its own interface.
    private const val TX_BT_PLAY_STATE = 0x9
    private const val TX_BT_PAUSE = 1
    private const val TX_BT_PLAY = 2
    private const val TX_BT_PREVIOUS = 4
    private const val TX_BT_NEXT = 5
    /** `IBtMusicBinderInterface.requestBtAudioFocus` — the launcher's source switch. */
    private const val TX_BT_REQUEST_AUDIO_FOCUS = 0xf

    private const val TX_USB_PLAY_STATE = 0x1e
    private const val TX_USB_PLAY_PAUSE = 0xc
    private const val TX_USB_PREVIOUS = 0x19
    private const val TX_USB_NEXT = 0x1a

    private const val TX_ONLINE_STATUS = 0x6
    private const val TX_ONLINE_PAUSE = 1
    private const val TX_ONLINE_PLAY = 2
    private const val TX_ONLINE_PREVIOUS = 3
    private const val TX_ONLINE_NEXT = 4

    private const val TX_CPAA_INFO = 0x7
    private const val TX_CPAA_PLAY = 1
    private const val TX_CPAA_PAUSE = 2
    private const val TX_CPAA_PREVIOUS = 3
    private const val TX_CPAA_NEXT = 4

    private const val TX_GENERIC_PLAY = 2
    private const val TX_GENERIC_PAUSE = 3
    private const val TX_GENERIC_PREVIOUS = 4
    private const val TX_GENERIC_NEXT = 5

    // The projection stack: allgo's service, outside the SAIC SDK entirely.
    private const val RUI_PACKAGE = "com.allgo.rui"
    private const val RUI_CLASS = "com.allgo.rui.RemoteUIService"
    private const val DESC_RUI = "com.allgo.rui.IRemoteUIService"
    private const val TX_RUI_MEDIA_KEY = 0x12

    /**
     * `sendMediaPlayControlKey` values. Skip carries a press and a release: leaving a key
     * held reads as fast-forward, which is what the two codes are for.
     */
    private const val RUI_PLAY = 1
    private const val RUI_PAUSE = 2
    private const val RUI_NEXT_DOWN = 7
    private const val RUI_PREVIOUS_DOWN = 8
    private const val RUI_NEXT_UP = 11
    private const val RUI_PREVIOUS_UP = 12

    enum class Command { PLAY_PAUSE, NEXT, PREVIOUS }

    private val status = mediaService(ACTION_STATUS, "media-status")
    private val generic = mediaService(ACTION_MEDIA, "media-generic")
    private val bluetooth = mediaService(ACTION_BT, "media-bt")
    private val usb = mediaService(ACTION_USB, "media-usb")
    private val online = mediaService(ACTION_ONLINE, "media-online")
    private val projection = mediaService(ACTION_CPAA, "media-cpaa")
    private val remoteUi = SaicService.byComponent(RUI_PACKAGE, RUI_CLASS, "media-rui")

    private fun mediaService(action: String, tag: String) =
        SaicService.byActionAndComponent(MEDIA_PACKAGE, action, MEDIA_CLASS, tag)

    @Volatile
    private var appContext: Context? = null

    /**
     * Binds every player interface, and the projection service beside them.
     *
     * All of them, up front, rather than on demand: a bind is asynchronous, and a shortcut
     * pressed on the steering wheel cannot wait for one. On a firmware where a given service
     * does not exist the bind is refused once, logged, and that interface simply never
     * answers — which is the same thing the dispatch below already handles.
     */
    fun connect(context: Context) {
        appContext = context.applicationContext
        listOf(status, generic, bluetooth, usb, online, projection, remoteUi)
            .forEach { it.connect(context) }
    }

    /**
     * Binds only the Bluetooth music interface — enough for [switchToBluetooth] without
     * pulling every other player up on an AA connect path that does not need them.
     */
    fun connectBluetooth(context: Context) {
        appContext = context.applicationContext
        bluetooth.connect(context)
    }

    val isAvailable: Boolean get() = status.isReady

    val isBluetoothReady: Boolean get() = bluetooth.isReady

    /**
     * Makes the car's media source Bluetooth Music, the same way the SAIC launcher does when
     * the home-screen music card picks BT (resource id 4).
     *
     * Order matters: [TX_BT_REQUEST_AUDIO_FOCUS] is the source handoff inside `MediaService`;
     * [TX_BT_PLAY] only resumes the A2DP/AVRCP transport. Calling play alone is not a
     * reliable switch.
     *
     * @return true when the focus call reached the service (play may still return false if
     * nothing is queued on the phone).
     */
    fun switchToBluetooth(): Boolean {
        val binder = bluetooth.binder()
        if (binder == null) {
            AppLogger.i(TAG, "switchToBluetooth: BT music service not bound")
            return false
        }
        val focusOk = SaicAidl.callVoid(binder, DESC_BT, TX_BT_REQUEST_AUDIO_FOCUS)
        val playOk = SaicAidl.callBoolean(binder, DESC_BT, TX_BT_PLAY) ?: false
        AppLogger.i(TAG, "switchToBluetooth: requestBtAudioFocus=$focusOk play=$playOk")
        return focusOk
    }

    fun next(): Boolean = command(Command.NEXT)

    fun previous(): Boolean = command(Command.PREVIOUS)

    fun playPause(): Boolean = command(Command.PLAY_PAUSE)

    /**
     * Sends [cmd] to whatever is playing.
     *
     * A source code below zero means the SAIC service did not answer — either it does not
     * exist (A9) or its bind has not landed yet. Both take the session path, which is safe
     * whichever it was: it commands only a session that declares itself playing.
     */
    fun command(cmd: Command): Boolean {
        val source = currentSource()

        if (source < 0) {
            AppLogger.i(TAG, "no SAIC media source — driving media sessions instead")
            if (sessionCommand(cmd)) return true

            // Sessions cover radio, Bluetooth and USB here. What they do not cover is the
            // projection stack, which declares neither skip action — hence allgo, the path
            // the A9 launcher takes for exactly this.
            if (projectionCommand(cmd, musicActive())) return true

            // Last resort, when enumerating sessions is refused outright. Only while
            // something is audible: sent while the radio plays (`isMusicActive` false, as
            // measured) it would wake the Bluetooth session and change the source — the very
            // defect this class exists to avoid, let back in through the side door. The
            // accepted cost is that it cannot resume a playback that had stopped.
            if (!musicActive()) {
                AppLogger.i(TAG, "no usable session and nothing playing — no key sent")
                return false
            }
            return sendMediaKey(
                when (cmd) {
                    Command.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                    Command.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    Command.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
            )
        }

        val target = if (isKnown(source)) source else playingSource()?.also {
            AppLogger.i(TAG, "source $source not in the table — player answering: ${sourceName(it)}")
        } ?: source

        return when {
            target in SRC_RADIO_MIN..SRC_RADIO_MAX -> radioCommand(cmd)

            target == SRC_BT -> transact(bluetooth, DESC_BT, when (cmd) {
                Command.NEXT -> TX_BT_NEXT
                Command.PREVIOUS -> TX_BT_PREVIOUS
                Command.PLAY_PAUSE -> if (isPlaying(target)) TX_BT_PAUSE else TX_BT_PLAY
            })

            target == SRC_USB -> transact(usb, DESC_USB, when (cmd) {
                Command.NEXT -> TX_USB_NEXT
                Command.PREVIOUS -> TX_USB_PREVIOUS
                // A real toggle, so the state never has to be read for this one.
                Command.PLAY_PAUSE -> TX_USB_PLAY_PAUSE
            })

            target == SRC_ONLINE -> transact(online, DESC_ONLINE, when (cmd) {
                Command.NEXT -> TX_ONLINE_NEXT
                Command.PREVIOUS -> TX_ONLINE_PREVIOUS
                Command.PLAY_PAUSE -> if (isPlaying(target)) TX_ONLINE_PAUSE else TX_ONLINE_PLAY
            })

            target == SRC_CARPLAY || target == SRC_AA -> {
                // Read the state only where the command depends on it: a skip has no use for
                // a binder round trip.
                val playing = cmd == Command.PLAY_PAUSE && isPlaying(target)
                // SWI133 answers through the SAIC SDK, every other firmware through allgo.
                // The two are exclusive by generation; the absent one fails without effect.
                transact(projection, DESC_CPAA, when (cmd) {
                    Command.NEXT -> TX_CPAA_NEXT
                    Command.PREVIOUS -> TX_CPAA_PREVIOUS
                    Command.PLAY_PAUSE -> if (playing) TX_CPAA_PAUSE else TX_CPAA_PLAY
                }) || projectionCommand(cmd, playing)
            }

            else -> {
                AppLogger.i(TAG, "source ${sourceName(target)} has no known player — generic facade")
                genericCommand(cmd, musicActive())
            }
        }
    }

    private fun isKnown(source: Int) =
        source in SRC_RADIO_MIN..SRC_RADIO_MAX ||
            source == SRC_BT || source == SRC_ONLINE || source == SRC_USB ||
            source == SRC_CARPLAY || source == SRC_AA

    /**
     * The tuner has its own service: skip changes station, and `srcPlayRadio` /
     * `srcPauseRadio` are what the launcher calls.
     *
     * An unreadable state counts as playing — the normal state of a tuner that owns the
     * audio. One pause too many is undone by a second press; the opposite reading would leave
     * the shortcut doing nothing at all.
     */
    private fun radioCommand(cmd: Command): Boolean {
        val playing = SaicRadio.isPlaying() != false
        val ok = when (cmd) {
            Command.NEXT -> SaicRadio.nextStation()
            Command.PREVIOUS -> SaicRadio.previousStation()
            Command.PLAY_PAUSE -> if (playing) SaicRadio.pause() else SaicRadio.play()
        }
        // Fallback *within* the source: the generic facade addresses whichever source is
        // current, so it cannot wake another one.
        return ok || genericCommand(cmd, playing)
    }

    private fun genericCommand(cmd: Command, playing: Boolean): Boolean =
        transact(generic, DESC_MEDIA, when (cmd) {
            Command.NEXT -> TX_GENERIC_NEXT
            Command.PREVIOUS -> TX_GENERIC_PREVIOUS
            Command.PLAY_PAUSE -> if (playing) TX_GENERIC_PAUSE else TX_GENERIC_PLAY
        })

    /** Which source owns the audio, or -1 when the service did not answer. */
    private fun currentSource(): Int {
        val v = SaicAidl.callInt(status.binder(), DESC_STATUS, TX_CURRENT_SOURCE) ?: return -1
        AppLogger.i(TAG, "current source = $v (${sourceName(v)})")
        return v
    }

    /**
     * Is [source] playing?
     *
     * The **source** is asked wherever it can answer, and `isMusicActive` only when none can:
     * that one stays true for a second or two after a stop, which is what made a second press
     * send a second pause instead of a play.
     */
    private fun isPlaying(source: Int): Boolean {
        val reported = when (source) {
            SRC_BT -> readInt(bluetooth, DESC_BT, TX_BT_PLAY_STATE)?.let { it != 0 }
            SRC_ONLINE -> readInt(online, DESC_ONLINE, TX_ONLINE_STATUS)?.let { it == PLAYER_STATUS_START }
            SRC_CARPLAY, SRC_AA -> projectionPlaying()
            else -> null
        }
        if (reported != null) {
            AppLogger.i(TAG, "${sourceName(source)} reports playing = $reported")
            return reported
        }
        val fallback = musicActive()
        AppLogger.i(TAG, "${sourceName(source)} does not report state — isMusicActive = $fallback")
        return fallback
    }

    /**
     * Which player declares itself playing, when the source code is one this does not know.
     *
     * Asking the players is what keeps the dispatch honest despite an incomplete table: only
     * a player that says it is playing gets commanded, so a sleeping source is never woken.
     */
    private fun playingSource(): Int? {
        if (readInt(bluetooth, DESC_BT, TX_BT_PLAY_STATE)?.let { it != 0 } == true) return SRC_BT
        if (readInt(usb, DESC_USB, TX_USB_PLAY_STATE)?.let { it != 0 } == true) return SRC_USB
        if (readInt(online, DESC_ONLINE, TX_ONLINE_STATUS) == PLAYER_STATUS_START) return SRC_ONLINE
        return null
    }

    /**
     * Is CarPlay / Android Auto playing? Null when nothing can be concluded.
     *
     * `getLastCpAaAudioInfoBean` returns an `AudioInfoBean` whose play state is
     * [PLAYER_STATUS_START] while playing — the launcher's own media card compares it to that
     * exact value. The bean is unwound in its `writeToParcel` order: id(long), duration(long),
     * seven strings, two longs, one string, then **state(int)**.
     *
     * Without this read the projection fell back to `isMusicActive`, which stays true for two
     * or three seconds after a pause: the shortcut answered a pause with another pause, and
     * the driver had to wait before the toggle worked again.
     */
    private fun projectionPlaying(): Boolean? =
        SaicAidl.callParcel(projection.binder(), DESC_CPAA, TX_CPAA_INFO) { reply ->
            if (reply.readInt() == 0) return@callParcel null   // null bean: nothing to conclude
            reply.readLong(); reply.readLong()                 // id, duration
            repeat(7) { reply.readString() }                   // name, art, path, artist, user, avatar, album
            reply.readLong(); reply.readLong()                 // added, last played
            reply.readString()                                 // readable position
            reply.readInt() == PLAYER_STATUS_START
        }

    /**
     * Drives the framework's media sessions — the Android path, and the A9 launcher's own.
     *
     * Safe by construction where a media key is not: only a session whose state *declares* it
     * is playing gets commanded, so this cannot wake a sleeping source. The single exception
     * is resuming, which acts only when no session is playing at all.
     *
     * A session also declares what it supports, and ignoring that declaration is talking into
     * the void: `skipToNext()` on a session without `ACTION_SKIP_TO_NEXT` throws nothing and
     * does nothing. Reporting success there would stop the caller trying the path that works.
     */
    private fun sessionCommand(cmd: Command): Boolean {
        val ctx = appContext ?: return false
        val manager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return false
        val sessions: List<MediaController> = try {
            manager.getActiveSessions(null)
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "sessions unreadable — MEDIA_CONTENT_CONTROL not held: ${e.message}")
            return false
        } catch (e: Exception) {
            AppLogger.w(TAG, "sessions unreadable: ${(e.cause ?: e).message}")
            return false
        }

        val playing = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val target = playing
            ?: if (cmd == Command.PLAY_PAUSE) {
                sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
            } else null

        if (target == null) {
            AppLogger.i(TAG, "no usable session among ${sessions.size}")
            return false
        }

        val declared = target.playbackState?.actions ?: 0L
        val required = when (cmd) {
            Command.NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
            Command.PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
            Command.PLAY_PAUSE -> PlaybackState.ACTION_PLAY_PAUSE or
                (if (playing != null) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY)
        }
        if (declared and required == 0L) {
            AppLogger.i(TAG, "${cmd.name} not declared by ${target.packageName} — session path given up")
            return false
        }

        return try {
            when (cmd) {
                Command.NEXT -> target.transportControls.skipToNext()
                Command.PREVIOUS -> target.transportControls.skipToPrevious()
                Command.PLAY_PAUSE ->
                    if (playing != null) target.transportControls.pause()
                    else target.transportControls.play()
            }
            AppLogger.i(TAG, "${cmd.name} sent to session ${target.packageName}")
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "session refused ${cmd.name}: ${(e.cause ?: e).message}")
            false
        }
    }

    private fun projectionCommand(cmd: Command, playing: Boolean): Boolean = when (cmd) {
        Command.NEXT -> remoteUiKey(RUI_NEXT_DOWN) && remoteUiKey(RUI_NEXT_UP)
        Command.PREVIOUS -> remoteUiKey(RUI_PREVIOUS_DOWN) && remoteUiKey(RUI_PREVIOUS_UP)
        Command.PLAY_PAUSE -> remoteUiKey(if (playing) RUI_PAUSE else RUI_PLAY)
    }

    private fun remoteUiKey(value: Int): Boolean =
        SaicAidl.callVoid(remoteUi.binder(), DESC_RUI, TX_RUI_MEDIA_KEY, value).also {
            AppLogger.i(TAG, "projection: sendMediaPlayControlKey($value) → $it")
        }

    private fun sendMediaKey(keyCode: Int): Boolean {
        val audio = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            AppLogger.w(TAG, "no AudioManager — media key not sent")
            return false
        }
        // Both halves or nothing: an app given a key-down and never the matching key-up can
        // sit on a held key.
        return try {
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            AppLogger.i(TAG, "sent ${KeyEvent.keyCodeToString(keyCode)}")
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "media key refused: ${(e.cause ?: e).message}")
            false
        }
    }

    private fun musicActive(): Boolean =
        (appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMusicActive == true

    /**
     * Calls a no-argument player method.
     *
     * Deliberately outside the vehicle write gate: changing track is not a driving setting,
     * and blocking it above a given speed would make no sense. A true here means the call was
     * *received*, not that it had an effect — which is why every step is logged.
     */
    private fun transact(service: SaicService, descriptor: String, code: Int): Boolean =
        SaicAidl.callVoid(service.binder(), descriptor, code).also {
            AppLogger.i(TAG, "${descriptor.substringAfterLast('.')} tx=0x${Integer.toHexString(code)} → $it")
        }

    /** A null here is not a zero: it means the question could not be put. */
    private fun readInt(service: SaicService, descriptor: String, code: Int): Int? =
        SaicAidl.callInt(service.binder(), descriptor, code)

    private fun sourceName(v: Int): String = when (v) {
        in SRC_RADIO_MIN..SRC_RADIO_MAX -> "radio/DAB"
        SRC_BT -> "bluetooth"
        SRC_ONLINE -> "online"
        SRC_USB -> "usb"
        SRC_USB_VIDEO -> "usb-video"
        SRC_CARPLAY -> "carplay"
        SRC_AA -> "android-auto"
        else -> "unknown($v)"
    }
}
