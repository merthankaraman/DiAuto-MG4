package com.andrerinas.openheadunit.decoder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MicRecorder(private val micSampleRate: Int, private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private val settings = Settings(context)

    private val micBufferSize: Int
    private var micAudioBuf: ByteArray

    // Indicates whether mic recording is available on this device
    val isAvailable: Boolean

    init {
        val minSize = AudioRecord.getMinBufferSize(micSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minSize <= 0) {
            // Device doesn't support the requested audio config (common on API 16)
            AppLog.w("MicRecorder: getMinBufferSize returned $minSize, mic recording unavailable")
            micBufferSize = 0
            micAudioBuf = ByteArray(0)
            isAvailable = false
        } else {
            micBufferSize = minSize
            micAudioBuf = ByteArray(minSize)
            isAvailable = true
        }
    }

    private var threadMicAudioActive = false
    private var threadMicAudio: Thread? = null
    var listener: Listener? = null

    // Tracks whether this instance started Bluetooth SCO so we can clean it up
    private var bluetoothScoStarted = false
    private var scoReceiver: BroadcastReceiver? = null

    companion object {
        // Sentinel value stored in settings to indicate Bluetooth SCO mode
        const val SOURCE_BLUETOOTH_SCO = 100
        private const val SCO_CONNECT_TIMEOUT_MS = 2_000L
    }

    interface Listener {
        fun onMicDataAvailable(mic_buf: ByteArray, mic_audio_len: Int)
    }

    fun stop() {
        AppLog.i("MicRecorder: Stopping. Active: $threadMicAudioActive")
        CarVoicePath.disable(context)
        
        threadMicAudioActive = false
        threadMicAudio?.interrupt()
        threadMicAudio = null

        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                AppLog.e("MicRecorder: Error releasing AudioRecord", e)
            }
        }
        audioRecord = null
        
        try {
            aec?.release()
            ns?.release()
            agc?.release()
        } catch (e: Exception) {
            AppLog.e("MicRecorder: Error releasing AudioFX", e)
        }
        aec = null
        ns = null
        agc = null

        if (bluetoothScoStarted) {
            cleanupSco()
        }
    }

    private fun cleanupSco() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            scoReceiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {}
        scoReceiver = null
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.communicationDevice
            if (device != null) {
                AppLog.i("MicRecorder: Clearing communication device: ${device.productName}")
            }
            audioManager.clearCommunicationDevice()
        } else {
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
        }

        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            AppLog.e("MicRecorder: Failed to restore audio mode to MODE_NORMAL", e)
        }

        bluetoothScoStarted = false
        AppLog.i("MicRecorder: Bluetooth SCO stopped and audio settings restored")
    }

    private fun micAudioRead(aud_buf: ByteArray, max_len: Int): Int {
        val currentAudioRecord = audioRecord ?: return 0
        val currentListener = listener ?: return 0
        
        val len = currentAudioRecord.read(aud_buf, 0, max_len)
        if (len <= 0) {
            if (len == AudioRecord.ERROR_INVALID_OPERATION && threadMicAudioActive) {
                AppLog.e("MicRecorder: Unexpected interruption error: $len")
            }
            return len
        }

        currentListener.onMicDataAvailable(aud_buf, len)
        return len
    }

    private fun getAudioSource(index: Int): Int {
        return when (index) {
            0 -> MediaRecorder.AudioSource.DEFAULT
            1 -> MediaRecorder.AudioSource.MIC
            2 -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            3 -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            4, SOURCE_BLUETOOTH_SCO -> SOURCE_BLUETOOTH_SCO
            else -> MediaRecorder.AudioSource.DEFAULT
        }
    }

    /**
     * @return 0 on success (AudioRecord initialized), -3 permission, -4 unavailable,
     * -7 internal / init failure.
     */
    fun start(): Int {
        if (!isAvailable) {
            AppLog.w("MicRecorder: Cannot start, mic not available on this device")
            return -4
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.e("MicRecorder: No RECORD_AUDIO permission")
            return -3
        }

        if (audioRecord?.state == AudioRecord.STATE_INITIALIZED && threadMicAudioActive) {
            AppLog.i("MicRecorder: already recording")
            return 0
        }

        CarVoicePath.enable(context, com.andrerinas.openheadunit.App.provide(context).commManager.isWirelessSession)
        val configuredSource = getAudioSource(settings.micInputSource)
        val ok = if (configuredSource == SOURCE_BLUETOOTH_SCO) {
            startScoAndRecord()
        } else {
            startRecording(configuredSource)
        }

        return if (ok && audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
            AppLog.i("MicRecorder: start OK (source=${getAudioSourceName(configuredSource)})")
            0
        } else {
            AppLog.e("MicRecorder: start failed — AudioRecord not initialized")
            CarVoicePath.disable(context)
            -7
        }
    }

    /** @return true when an AudioRecord was started successfully. */
    private fun startScoAndRecord(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val hasBluetoothPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasBluetoothPermission) {
            AppLog.i("MicRecorder: API 31+. Using setCommunicationDevice for Bluetooth routing.")
            val devices = audioManager.availableCommunicationDevices
            val bluetoothDevice = devices.find {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (bluetoothDevice != null) {
                val success = audioManager.setCommunicationDevice(bluetoothDevice)
                AppLog.i(
                    "MicRecorder: setCommunicationDevice result: $success for device: " +
                        "${bluetoothDevice.productName} (${bluetoothDevice.type})",
                )
                if (success) {
                    try {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    } catch (e: Exception) {
                        AppLog.e("MicRecorder: Failed to set MODE_IN_COMMUNICATION", e)
                    }
                    bluetoothScoStarted = true
                    if (startRecording(MediaRecorder.AudioSource.VOICE_COMMUNICATION)) return true
                    cleanupSco()
                }
            } else {
                AppLog.w("MicRecorder: No Bluetooth SCO/BLE headset — falling back to local mic.")
            }
            return startRecording(MediaRecorder.AudioSource.VOICE_RECOGNITION) ||
                startRecording(MediaRecorder.AudioSource.MIC)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission) {
            AppLog.w("MicRecorder: Missing BLUETOOTH_CONNECT — falling back to local mic.")
            return startRecording(MediaRecorder.AudioSource.VOICE_RECOGNITION) ||
                startRecording(MediaRecorder.AudioSource.MIC)
        }

        // Legacy SCO: wait briefly for CONNECTED, then fall back to cabin mic.
        val connected = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                if (AppLog.LOG_DEBUG) AppLog.d("MicRecorder: SCO State change: $state")
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    connected.set(true)
                    latch.countDown()
                } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED && bluetoothScoStarted) {
                    AppLog.w("MicRecorder: SCO Disconnected unexpectedly.")
                    stop()
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            scoReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
            ContextCompat.RECEIVER_EXPORTED,
        )

        AppLog.i("MicRecorder: Starting Bluetooth SCO...")
        bluetoothScoStarted = true
        audioManager.startBluetoothSco()
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = true

        val scoOk = try {
            latch.await(SCO_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) && connected.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (scoOk) {
            AppLog.i("MicRecorder: SCO Connected. Starting AudioRecord.")
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            } catch (e: Exception) {
                AppLog.e("MicRecorder: Failed to set MODE_IN_COMMUNICATION", e)
            }
            if (startRecording(MediaRecorder.AudioSource.MIC)) return true
            cleanupSco()
        } else {
            AppLog.w("MicRecorder: SCO did not connect in ${SCO_CONNECT_TIMEOUT_MS}ms — local mic fallback.")
            cleanupSco()
        }
        return startRecording(MediaRecorder.AudioSource.VOICE_RECOGNITION) ||
            startRecording(MediaRecorder.AudioSource.MIC)
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO checked immediately above / in start()
    private fun startRecording(source: Int): Boolean {
        try {
            if (audioRecord != null) return audioRecord?.state == AudioRecord.STATE_INITIALIZED
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                AppLog.e("MicRecorder: No RECORD_AUDIO permission when starting AudioRecord")
                return false
            }

            AppLog.i(
                "MicRecorder: Initializing AudioRecord with source: ${getAudioSourceName(source)} ($source), " +
                    "SampleRate: $micSampleRate, BufferSize: $micBufferSize",
            )
            audioRecord = AudioRecord(
                source,
                micSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                micBufferSize,
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                AppLog.e("MicRecorder: Failed to initialize AudioRecord")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            val audioSessionId = audioRecord?.audioSessionId ?: 0
            if (audioSessionId != 0) {
                try {
                    if (settings.micNoiseSuppressor && NoiseSuppressor.isAvailable()) {
                        ns = NoiseSuppressor.create(audioSessionId)
                        ns?.enabled = true
                        AppLog.i("MicRecorder: NoiseSuppressor: ${if (ns?.enabled == true) "ON" else "failed"}")
                    } else if (settings.micNoiseSuppressor) {
                        AppLog.i("MicRecorder: NoiseSuppressor: Unsupported on this device")
                    }

                    if (settings.micAutoGainControl && AutomaticGainControl.isAvailable()) {
                        agc = AutomaticGainControl.create(audioSessionId)
                        agc?.enabled = true
                        AppLog.i("MicRecorder: AutomaticGainControl: ${if (agc?.enabled == true) "ON" else "failed"}")
                    } else if (settings.micAutoGainControl) {
                        AppLog.i("MicRecorder: AutomaticGainControl: Unsupported on this device")
                    }

                    if (settings.micEchoCanceler && AcousticEchoCanceler.isAvailable()) {
                        aec = AcousticEchoCanceler.create(audioSessionId)
                        aec?.enabled = true
                        AppLog.i("MicRecorder: AcousticEchoCanceler: ${if (aec?.enabled == true) "ON" else "failed"}")
                    } else if (settings.micEchoCanceler) {
                        AppLog.i("MicRecorder: AcousticEchoCanceler: Unsupported on this device")
                    }
                } catch (e: Exception) {
                    AppLog.e("MicRecorder: Error initializing AudioFX", e)
                }
            }

            audioRecord?.startRecording()

            threadMicAudioActive = true
            threadMicAudio = Thread({
                while (threadMicAudioActive) {
                    micAudioRead(micAudioBuf, micBufferSize)
                }
            }, "mic_audio").apply { start() }

            return true
        } catch (e: Exception) {
            AppLog.e("MicRecorder: Error during startRecording", e)
            audioRecord?.release()
            audioRecord = null
            return false
        }
    }

    private fun getAudioSourceName(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
            MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.REMOTE_SUBMIX -> "REMOTE_SUBMIX"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            SOURCE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            else -> "UNKNOWN ($source)"
        }
    }
}
