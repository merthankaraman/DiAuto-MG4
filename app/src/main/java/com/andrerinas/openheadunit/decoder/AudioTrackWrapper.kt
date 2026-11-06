package com.andrerinas.openheadunit.decoder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.evsuite.hardware.saic.SaicCarAudioSource
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AudioTrackWrapper(
    stream: Int,
    sampleRateInHz: Int,
    bitDepth: Int,
    channelCount: Int,
    private val isAac: Boolean = false,
    gain: Float,
    private val audioLatencyMultiplier: Int = 8,
    private val audioQueueCapacity: Int = 0,
    private val mixer: AudioMixer? = null,
    private val channelId: Int = -1,
    private val attachHwDspEqualizer: Boolean = false,
    /** >0 enables Wi‑Fi media jitter: larger track + delay play() until this many ms are queued. */
    private val mediaBufferMillis: Int = 0,
    /**
     * Called once for every chunk handed to [write], when it leaves this wrapper: played, decoded,
     * flushed or refused. The phone's media ack is sent from here, so its unacked window really
     * bounds how far ahead of the speakers it can be, instead of every burst being acked on arrival
     * and then dropped here when the queue overflowed (heard as sped-up, choppy music).
     */
    private val onChunkDone: (() -> Unit)? = null,
) : Thread() {

    private data class AudioChunk(
        val data: ByteArray,
        val size: Int
    )

    companion object {
        /** Where the voice limiter starts rounding peaks off (about -6 dBFS). */
        private const val LIMIT_KNEE = 16_384
        private const val RECEIVING_WINDOW_MS = 300L
        private const val PLAYING_WINDOW_MS = 3_000L
        private const val AUDIO_BUFFER_POOL_LIMIT = 16
        private const val MIN_POOLED_AUDIO_BUFFER_SIZE = 4096
        private const val INPUT_BUFFER_POLL_MS = 100L
        private const val INPUT_BUFFER_WAIT_MAX_MS = 1_500L
    }

    private val audioTrack: AudioTrack?
    private var decoder: MediaCodec? = null
    private var codecHandlerThread: HandlerThread? = null
    private val freeInputBuffers = LinkedBlockingQueue<Int>()
    private val writeExecutor = Executors.newSingleThreadExecutor()
    private val writeSemaphore = java.util.concurrent.Semaphore(3)
    private var equalizer: Equalizer? = null

    // Limit queue capacity to provide backpressure to the network thread if audio playback is slow
    private val dataQueue = if (audioQueueCapacity > 0)
        LinkedBlockingQueue<AudioChunk>(audioQueueCapacity)
    else
        LinkedBlockingQueue<AudioChunk>()
    private val audioBufferPool = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var isRunning = true

    @Volatile
    private var currentGain: Float = gain

    fun setVolume(gain: Float) {
        currentGain = gain
        val track = audioTrack
        if (track != null) {
            try {
                val hwGain = gain.coerceAtMost(1.0f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    track.setVolume(hwGain)
                } else {
                    @Suppress("DEPRECATION")
                    track.setStereoVolume(hwGain, hwGain)
                }
            } catch (e: Exception) {
                AppLog.e("Failed to set volume on AudioTrack", e)
            }
        } else {
            mixer?.setChannelGain(channelId, gain)
        }
    }

    /**
     * Soft limiter for boosted voice: above the knee, peaks are rounded off instead of cut flat.
     * Prompts arrive close to full scale, so a +100 to +200 % boost clipped hard on every syllable.
     */
    private fun limit(sample: Int): Int {
        val magnitude = kotlin.math.abs(sample)
        if (magnitude <= LIMIT_KNEE) return sample
        val room = (32767 - LIMIT_KNEE).toDouble()
        val shaped = LIMIT_KNEE + room * kotlin.math.tanh((magnitude - LIMIT_KNEE) / room)
        return if (sample < 0) -shaped.toInt() else shaped.toInt()
    }

    private fun applyGain(buffer: ByteArray, size: Int) {
        if (currentGain <= 1.0f) return
        for (i in 0 until size - 1 step 2) {
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt() // High byte handles sign
            val sample = (high shl 8) or low
            val modifiedSample = limit((sample * currentGain).toInt())
            buffer[i] = (modifiedSample and 0xFF).toByte()
            buffer[i + 1] = (modifiedSample shr 8).toByte()
        }
    }

    // Track frames written for better draining
    private var framesWritten: Long = 0
    private val bytesPerFrame: Int = channelCount * (if (bitDepth == 16) 2 else 1)
    private val mediaJitterStartBytes: Int
    private var bytesWrittenBeforePlay: Int = 0
    @Volatile private var mediaPlaybackStarted: Boolean = true
    @Volatile private var lastWriteMs = 0L

    /** 16 kHz mono voice played straight to a track is upsampled here rather than by the car. */
    private val voiceSettings = if (mixer == null && !isAac && sampleRateInHz == 16_000 && channelCount == 1 && bitDepth == 16)
        com.andrerinas.openheadunit.App.instance?.let { app -> com.andrerinas.openheadunit.App.provide(app).settings } else null
    private val upsampler: VoiceUpsampler? =
        if (mixer == null && !isAac && sampleRateInHz == 16_000 && channelCount == 1 && bitDepth == 16)
            VoiceUpsampler.create(voiceSettings?.voiceEnhancement ?: 0) else null
    /** Optional clarity filter and headroom for that voice, read when the prompt starts. */
    private val voiceClarity: VoiceClarity? = voiceSettings?.let {
        VoiceClarity(16_000, it.voiceClarity, it.voiceHeadroomDb)
    }?.takeIf { upsampler != null && it.active }
    private var upsampleIn = ShortArray(0)
    private var upsampleOut = ShortArray(0)
    private var upsampleBytes = ByteArray(0)

    /** The chunk at 48 kHz, in a reused buffer; returns its size in bytes. */
    private fun upsample(buffer: ByteArray, size: Int, upsampler: VoiceUpsampler): Int {
        val samples = size / 2
        if (upsampleIn.size < samples) upsampleIn = ShortArray(samples)
        val room = samples * 3 + VoiceUpsampler.MAX_EXTRA
        if (upsampleOut.size < room) upsampleOut = ShortArray(room)
        if (upsampleBytes.size < room * 2) upsampleBytes = ByteArray(room * 2)
        java.nio.ByteBuffer.wrap(buffer, 0, samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().get(upsampleIn, 0, samples)
        val produced = upsampler.process(upsampleIn, samples, upsampleOut)
        java.nio.ByteBuffer.wrap(upsampleBytes, 0, produced * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().put(upsampleOut, 0, produced)
        return produced * 2
    }

    /** The voice as the phone sent it, kept to compare with what the speakers play. */
    private val voiceCapture: VoiceCapture? =
        // Kept only in test mode, to compare the voice settings being tested.
        if (voiceSettings?.testMode == true && !isAac && sampleRateInHz == 16_000 && (channelId == com.andrerinas.openheadunit.aap.protocol.Channel.ID_AU1 ||
                channelId == com.andrerinas.openheadunit.aap.protocol.Channel.ID_AU2))
            VoiceCapture.open(com.andrerinas.openheadunit.aap.protocol.Channel.name(channelId), sampleRateInHz, channelCount)
        else null

    init {
        this.name = "AudioPlaybackThread"
        val plan = if (mixer == null && mediaBufferMillis > 0) {
            val channelConfig =
                if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val dataFormat =
                if (bitDepth == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT
            val minBuffer = AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, dataFormat)
            MediaAudioBuffer.plan(
                isMedia = true,
                sampleRate = sampleRateInHz,
                channels = channelCount,
                minBufferBytes = minBuffer.coerceAtLeast(0),
                mediaMillis = mediaBufferMillis,
            )
        } else {
            null
        }

        audioTrack = if (mixer == null) {
            createAudioTrack(
                stream,
                if (upsampler != null) 48_000 else sampleRateInHz,
                bitDepth,
                channelCount,
                audioLatencyMultiplier,
                plan?.trackBufferBytes ?: 0,
            )
        } else {
            null
        }

        mediaJitterStartBytes = run {
            val track = audioTrack
            val mediaPlan = plan
            if (mediaPlan != null && track != null) {
                val capacity = runCatching { track.bufferSizeInFrames * bytesPerFrame }
                    .getOrDefault(mediaPlan.trackBufferBytes)
                MediaAudioBuffer.startBytesFor(mediaPlan.startBytes, capacity, writeChunkBytes = 2048)
            } else {
                0
            }
        }
        mediaPlaybackStarted = mediaJitterStartBytes <= 0

        if (mixer != null) {
            mixer.registerChannel(channelId, sampleRateInHz, channelCount)
            mixer.setChannelGain(channelId, gain)
        } else {
            setVolume(gain)
            audioTrack?.let { track ->
                attachHwDspEqualizerQuietly(track.audioSessionId)
                if (mediaPlaybackStarted) {
                    track.play()
                } else {
                    AppLog.i(
                        "Media audio jitter: delaying play until ${mediaJitterStartBytes}B " +
                            "(~${mediaBufferMillis}ms) buffered",
                    )
                }
            }
        }

        if (isAac) {
            initDecoder(sampleRateInHz, channelCount)
        }

        this.start()
    }

    private fun initDecoder(sampleRate: Int, channels: Int) {
        try {
            val mime = "audio/mp4a-latm"
            val format = MediaFormat.createAudioFormat(mime, sampleRate, channels)
            format.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            // CSD for RAW AAC-LC (AudioSpecificConfig)
            val csd = makeAacCsd(sampleRate, channels)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))

            decoder = MediaCodec.createDecoderByType(mime)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Use a HandlerThread for the codec callback but set its priority to AUDIO
                // to prevent it from being starved by the video decoder.
                codecHandlerThread = object : HandlerThread("AacCodecThread") {
                    override fun onLooperPrepared() {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    }
                }
                codecHandlerThread!!.start()

                val callback = object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        freeInputBuffers.offer(index)
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                        info: MediaCodec.BufferInfo
                    ) {
                        try {
                            if (!isRunning) return
                            val outputBuffer = codec.getOutputBuffer(index)
                            if (outputBuffer != null && info.size > 0) {
                                val chunk = ByteArray(info.size)
                                outputBuffer.position(info.offset)
                                outputBuffer.get(chunk)
                                outputBuffer.clear()

                                writeSemaphore.acquire()
                                // Write to AudioTrack or Mixer using executor
                                writeExecutor.submit {
                                    try {
                                        writeToTrack(chunk)
                                    } catch (e: Exception) {
                                        AppLog.e("Error writing decoded AAC to AudioTrack", e)
                                    } finally {
                                        writeSemaphore.release()
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(index, false)
                        } catch (e: Exception) {
                            AppLog.e("Error processing AAC output", e)
                            if (e is InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        AppLog.e("AAC Codec Error", e)
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        AppLog.i("AAC Output Format Changed: $format")
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val handler = Handler(codecHandlerThread!!.looper)
                    decoder!!.setCallback(callback, handler)
                } else {
                    decoder!!.setCallback(callback)
                }
            }

            decoder?.configure(format, null, null, 0)
            decoder?.start()
            AppLog.i("AAC Decoder started for $sampleRate Hz, $channels channels (Async)")
        } catch (e: Exception) {
            AppLog.e("Failed to init AAC decoder", e)
        }
    }

    private fun writeToTrack(buffer: ByteArray) {
        writeToTrack(buffer, buffer.size)
    }

    private fun writeToTrack(buffer: ByteArray, size: Int) {
        if (mixer != null) {
            mixer.feed(channelId, buffer, 0, size)
            framesWritten += size / bytesPerFrame
        } else {
            applyGain(buffer, size)
            val track = audioTrack ?: return
            if (android.os.SystemClock.elapsedRealtime() < silencedUntilMs) {
                // Paused by the driver: what the phone still sends is not played.
                lastWriteMs = android.os.SystemClock.elapsedRealtime()
                return
            }
            val up = upsampler
            voiceClarity?.process(buffer, size)
            val result = if (up != null) {
                val bytes = upsample(buffer, size, up)
                track.write(upsampleBytes, 0, bytes)
            } else {
                track.write(buffer, 0, size)
            }
            if (result > 0) {
                lastWriteMs = android.os.SystemClock.elapsedRealtime()
                framesWritten += result / bytesPerFrame
                if (!mediaPlaybackStarted) {
                    bytesWrittenBeforePlay += result
                    if (bytesWrittenBeforePlay >= mediaJitterStartBytes) {
                        track.play()
                        mediaPlaybackStarted = true
                        AppLog.i(
                            "Media audio jitter: play() after ${bytesWrittenBeforePlay}B buffered",
                        )
                    }
                }
            }
        }
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        // Drain the queue even after isRunning is set to false
        while (isRunning || dataQueue.isNotEmpty()) {
            try {
                // Use poll to avoid blocking indefinitely if isRunning becomes false
                val chunk = dataQueue.poll(200, TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    try {
                        if (isAac && decoder != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                queueInput(chunk.data, chunk.size)
                            } else {
                                decodeSync(chunk.data, chunk.size)
                            }
                        } else {
                            // PCM path - direct write in this high-priority thread
                            writeToTrack(chunk.data, chunk.size)
                        }
                    } finally {
                        recycleAudioBuffer(chunk.data)
                        onChunkDone?.invoke()
                    }
                }
            } catch (e: InterruptedException) {
                drainQueuedAudio()
                break
            } catch (e: Exception) {
                AppLog.e("Error in AudioTrackWrapper run loop", e)
                isRunning = false
            }
        }
        cleanup()
        AppLog.i("AudioTrackWrapper thread finished.")
    }

    @Suppress("DEPRECATION")
    private fun decodeSync(inputData: ByteArray, size: Int) {
        try {
            val dec = this.decoder ?: return
            val inputIndex = dec.dequeueInputBuffer(200000)
            if (inputIndex >= 0) {
                val inputBuffer = dec.inputBuffers[inputIndex]
                inputBuffer.clear()
                inputBuffer.put(inputData, 0, size)
                dec.queueInputBuffer(inputIndex, 0, size, 0, 0)
            }

            val info = MediaCodec.BufferInfo()
            var outputIndex = dec.dequeueOutputBuffer(info, 0)
            while (outputIndex >= 0) {
                val outputBuffer = dec.outputBuffers[outputIndex]
                val chunk = ByteArray(info.size)
                outputBuffer.position(info.offset)
                outputBuffer.get(chunk)
                writeToTrack(chunk)
                dec.releaseOutputBuffer(outputIndex, false)
                outputIndex = dec.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            AppLog.e("Error in decodeSync", e)
        }
    }

    @Throws(InterruptedException::class)
    private fun queueInput(inputData: ByteArray, size: Int) {
        try {
            // Wait for input buffer (with timeout to avoid deadlock if codec dies)
            // Restore to 200ms to prevent dropping frames under load
            // The phone sends music in bursts at stream start; while the AudioTrack is full the
            // codec holds its buffers back. Waiting it out (the queue in front absorbs the burst)
            // is better than dropping frames, which was the stutter heard at every start.
            var inputIndex: Int? = null
            var waitedMs = 0L
            while (inputIndex == null && isRunning && waitedMs < INPUT_BUFFER_WAIT_MAX_MS) {
                inputIndex = freeInputBuffers.poll(INPUT_BUFFER_POLL_MS, TimeUnit.MILLISECONDS)
                waitedMs += INPUT_BUFFER_POLL_MS
            }

            if (inputIndex != null && inputIndex >= 0) {
                val inputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    decoder?.getInputBuffer(inputIndex)
                } else {
                    @Suppress("DEPRECATION")
                    decoder?.inputBuffers?.get(inputIndex)
                }

                inputBuffer?.clear()
                inputBuffer?.put(inputData, 0, size)
                decoder?.queueInputBuffer(inputIndex, 0, size, 0, 0)
            } else {
                AppLog.w("AAC Input Buffer timeout (${INPUT_BUFFER_WAIT_MAX_MS}ms) - dropping frame")
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Error queuing AAC input", e)
        }
    }

    private fun makeAacCsd(sampleRate: Int, channelCount: Int): ByteArray {
        val sampleRateIndex = getFrequencyIndex(sampleRate)
        val audioObjectType = 2 // AAC-LC

        // Correct packing: [AOT:5][FreqIdx:4][ChanCfg:4][...padding:3]
        val config = ((audioObjectType and 0x1F) shl 11) or
                     ((sampleRateIndex and 0x0F) shl 7) or
                     ((channelCount and 0x0F) shl 3)

        return byteArrayOf(
            ((config shr 8) and 0xFF).toByte(),
            (config and 0xFF).toByte()
        )
    }

    private fun getFrequencyIndex(sampleRate: Int): Int {
        return when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000  -> 11
            7350  -> 12
            else  -> 4 // Default 44100
        }
    }

    private fun createAudioTrack(
        stream: Int,
        sampleRateInHz: Int,
        bitDepth: Int,
        channelCount: Int,
        multiplier: Int,
        mediaTrackBytes: Int = 0,
    ): AudioTrack {
        val channelConfig =
            if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val dataFormat =
            if (bitDepth == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT

        val minBufferSize = AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, dataFormat)
        val multiplied = if (minBufferSize > 0) minBufferSize * multiplier else minBufferSize
        val bufferSize = if (mediaTrackBytes > 0) maxOf(multiplied, mediaTrackBytes) else multiplied

        AppLog.i("Audio stream: $stream buffer size: $bufferSize (min: $minBufferSize) sampleRateInHz: $sampleRateInHz channelCount: $channelCount")

        // With the navigation channel chosen (SpeechRoute), prompts and the assistant (AUDIO1/AUDIO2)
        // play as navigation guidance, untagged: the MG4
        // sends that usage to its NAVI bus, mixed over any source. As media tagged Android Auto
        // they went to the media bus, which is silent while the radio is the car's source.
        val speech = channelId == Channel.ID_AU1 || channelId == Channel.ID_AU2
        if (speech && SpeechRoute.navigationChannel && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRateInHz)
                        .setChannelMask(channelConfig)
                        .setEncoding(dataFormat)
                        .build()
                )
                // As the car's own Android Auto plays prompts: 48 kHz (converted by this wrapper)
                // on a normal mixer track with a ~210 ms buffer. A small buffer became a fast
                // track, which the car resampled from 16 kHz with its basic converter (measured
                // 2026-10-30: AllGo 48 kHz, flags 0, 10240 frames; DiAuto 16 kHz, fast, 3104).
                .setBufferSizeInBytes(maxOf(bufferSize, sampleRateInHz * channelCount * 2 * 210 / 1000))
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
                    }
                }
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val (usage, contentType) = when (stream) {
                AudioManager.STREAM_NOTIFICATION -> Pair(
                    AudioAttributes.USAGE_NOTIFICATION,
                    AudioAttributes.CONTENT_TYPE_SONIFICATION
                )
                AudioManager.STREAM_VOICE_CALL -> Pair(
                    AudioAttributes.USAGE_VOICE_COMMUNICATION,
                    AudioAttributes.CONTENT_TYPE_SPEECH
                )
                else -> Pair(
                    AudioAttributes.USAGE_MEDIA,
                    AudioAttributes.CONTENT_TYPE_MUSIC
                )
            }

            val attrBuilder = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(contentType)
                .setLegacyStreamType(stream)
            // Tag every AA sink (media, Gemini/AU1, nav/notification/AU2) as Android Auto
            // (70) so the SAIC amp routes hybrid speech PCM as well as Wi‑Fi media.
            SaicCarAudioSource.attachSourceType(
                attrBuilder,
                SaicCarAudioSource.SRC_ANDROID_AUTO,
            )
            val audioAttributes = attrBuilder.build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRateInHz)
                .setChannelMask(channelConfig)
                .setEncoding(dataFormat)
                .build()

            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                stream,
                sampleRateInHz,
                channelConfig,
                dataFormat,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        }
    }

    private fun attachHwDspEqualizerQuietly(sessionId: Int) {
        if (!attachHwDspEqualizer) return
        if (sessionId != AudioManager.AUDIO_SESSION_ID_GENERATE && sessionId > 0) {
            try {
                equalizer?.release()
                val eq = Equalizer(0, sessionId)
                eq.enabled = true
                equalizer = eq
                AppLog.i("Attached dummy Equalizer to audioSessionId $sessionId to trigger HW DSP")
            } catch (t: Throwable) {
                // Ignore if Equalizer or AudioEffect is unsupported on device
            }
        }
    }

    fun write(buffer: ByteArray, offset: Int, size: Int) {
        if (!isRunning) {
            onChunkDone?.invoke()
            return
        }

        voiceCapture?.add(buffer, offset, size)
        var data: ByteArray? = null
        try {
            data = obtainAudioBuffer(size)
            System.arraycopy(buffer, offset, data, 0, size)
            val success = dataQueue.offer(AudioChunk(data, size), 5, TimeUnit.MILLISECONDS)
            if (!success) {
                recycleAudioBuffer(data)
                onChunkDone?.invoke()
                AppLog.w("Audio queue is full, dropping audio frame to prevent stalling")
            }
        } catch (e: InterruptedException) {
            data?.let { recycleAudioBuffer(it) }
            onChunkDone?.invoke()
            Thread.currentThread().interrupt()
            AppLog.w("Interrupted while putting audio data to queue")
        }
    }

    fun setGain(gain: Float) {
        if (AppLog.LOG_DEBUG) AppLog.d("AudioTrackWrapper: updating gain to $gain")
        setVolume(gain)
    }

    /**
     * Drops the audio of the previous track still queued here and in the AudioTrack (about 1–2 s
     * with the Wi-Fi jitter buffer), so a skipped-to track is heard as soon as the phone sends it.
     */
    fun flushForTrackChange() {
        if (!isRunning || mixer != null) return
        drainQueuedAudio()
        val track = audioTrack ?: return
        try {
            if (mediaPlaybackStarted && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
                track.flush()
                track.play()
                AppLog.i("AudioTrackWrapper: flushed the previous track's buffered audio")
            }
        } catch (e: Exception) {
            AppLog.w("AudioTrackWrapper: flush for track change failed: ${e.message}")
        }
    }

    @Volatile private var silencedUntilMs = 0L

    /**
     * Pause heard at once: some phones and players keep sending the music for several seconds after
     * a pause (a fade, or a slow stop), heard as a ~4 s delay. That audio is dropped until [ms] have
     * passed, the stream stops, or [unsilence] is called for play.
     */
    fun silenceFor(ms: Long) {
        silencedUntilMs = android.os.SystemClock.elapsedRealtime() + ms
    }

    fun unsilence() {
        silencedUntilMs = 0L
    }

    fun isSilenced(): Boolean = android.os.SystemClock.elapsedRealtime() < silencedUntilMs

    /** The phone is still sending this stream (audio written in the last few hundred ms). */
    fun isReceivingAudio(): Boolean =
        android.os.SystemClock.elapsedRealtime() - lastWriteMs < RECEIVING_WINDOW_MS

    /**
     * Music is playing on this stream, for the pause key: audio written in the last few seconds.
     * Over Wi-Fi the phone can send the music in bursts, so the last write may be well over
     * [RECEIVING_WINDOW_MS] old while the music plays on from the buffers; that read as "not
     * playing" and the pause cut was skipped (issue #3, SWI173).
     */
    fun isPlayingMusic(): Boolean =
        android.os.SystemClock.elapsedRealtime() - lastWriteMs < PLAYING_WINDOW_MS

    fun stopPlayback() {
        isRunning = false
        this.interrupt()
    }

    private fun cleanup() {
        voiceCapture?.close()
        drainQueuedAudio()
        audioBufferPool.clear()

        // 1. Stop the decoder to stop producing new output buffers
        try {
            decoder?.stop()
            decoder?.release()
            decoder = null
        } catch (e: Exception) {
            AppLog.e("Error releasing audio decoder", e)
        }

        // 2. Wait for AAC writes that were already submitted to the executor
        writeExecutor.shutdown()
        try {
            if (!writeExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                AppLog.w("Audio write executor did not terminate in time")
                writeExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            AppLog.w("Audio write executor interrupted during shutdown")
            writeExecutor.shutdownNow()
        }

        if (mixer != null) {
            mixer.unregisterChannel(channelId)
        }

        // 3. Gracefully stop the AudioTrack – stop() plays remaining buffer data
        val track = audioTrack
        if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            try {
                track.stop()

                // Wait for the AudioTrack hardware buffer to drain.
                // Especially important on older devices (KitKat etc.).
                var lastPos = -1
                var stagnantCount = 0
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 2500) {
                    val pos = track.playbackHeadPosition
                    if (framesWritten > 0 && pos >= framesWritten) break
                    if (pos == lastPos && pos > 0) {
                        stagnantCount++
                        if (stagnantCount >= 3) break
                    } else {
                        lastPos = pos
                        stagnantCount = 0
                    }
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                AppLog.e("Error during audio track cleanup", e)
            }
        }

        // 4. Release the AudioTrack
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            AppLog.e("Error releasing audio track", e)
        }

        // 5. Clean up the codec handler thread
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                codecHandlerThread?.quitSafely()
            } else {
                codecHandlerThread?.quit()
            }
            codecHandlerThread = null
        } catch (e: Exception) {
            AppLog.e("Error quitting codec thread", e)
        }
    }

    private fun obtainAudioBuffer(size: Int): ByteArray {
        while (true) {
            val pooled = audioBufferPool.poll() ?: return ByteArray(maxOf(size, MIN_POOLED_AUDIO_BUFFER_SIZE))
            if (pooled.size >= size) return pooled
        }
    }

    private fun recycleAudioBuffer(buffer: ByteArray) {
        if (audioBufferPool.size < AUDIO_BUFFER_POOL_LIMIT) {
            audioBufferPool.offer(buffer)
        }
    }

    private fun drainQueuedAudio() {
        while (true) {
            val chunk = dataQueue.poll() ?: break
            recycleAudioBuffer(chunk.data)
            onChunkDone?.invoke()
        }
    }
}
