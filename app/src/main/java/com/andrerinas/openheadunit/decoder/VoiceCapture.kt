package com.andrerinas.openheadunit.decoder

import android.os.Environment
import com.andrerinas.openheadunit.utils.AppLog
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the voice audio exactly as the phone sent it (prompts, assistant) as WAV files, to compare
 * its quality with what the speakers play: the last [KEEP] captures of up to [MAX_SECONDS] each, in
 * Download/OpenHeadunitLogs/voice.
 */
class VoiceCapture private constructor(private val file: File, private val sampleRate: Int, private val channels: Int) {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    private var bytes = 0L
    private val maxBytes = MAX_SECONDS.toLong() * sampleRate * channels * 2

    fun add(data: ByteArray, offset: Int, size: Int) {
        if (bytes >= maxBytes) return
        val n = minOf(size.toLong(), maxBytes - bytes).toInt()
        runCatching { out.write(data, offset, n) }.onSuccess { bytes += n }
    }

    fun close() {
        runCatching {
            out.seek(0)
            out.write(header(bytes, sampleRate, channels))
            out.close()
            AppLog.i("VoiceCapture: kept ${bytes / (sampleRate * channels * 2.0)} s in ${file.name}")
        }
    }

    companion object {
        private const val KEEP = 6
        private const val MAX_SECONDS = 30

        /** Null when the folder cannot be written; never stops the audio. */
        fun open(channelName: String, sampleRate: Int, channels: Int): VoiceCapture? = runCatching {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OpenHeadunitLogs/voice")
            if (!dir.exists() && !dir.mkdirs()) return null
            dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.lastModified() }
                ?.dropLast(KEEP - 1)?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            VoiceCapture(File(dir, "voice_${stamp}_$channelName.wav"), sampleRate, channels)
        }.getOrNull()

        private fun header(dataBytes: Long, rate: Int, channels: Int): ByteArray {
            val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            b.putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
            b.put("data".toByteArray()).putInt(dataBytes.toInt())
            return b.array()
        }
    }
}
