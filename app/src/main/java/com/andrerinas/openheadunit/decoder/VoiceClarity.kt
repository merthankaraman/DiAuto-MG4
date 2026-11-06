package com.andrerinas.openheadunit.decoder

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Voice-only processing for the 16 kHz prompts, before they are upsampled: an optional clarity
 * equaliser and an optional headroom cut. Both are linear (biquads and a gain), so they add no
 * distortion of their own.
 *
 * Clarity: a 150 Hz high-pass takes out the low rumble that carries most of a synthetic voice's
 * energy (90 % below 500 Hz in prompts recorded on the car) without carrying words, and a +4 dB
 * presence lift around 3 kHz, where consonants are. Headroom: the phone sends prompts with peaks
 * at 0 dBFS, so a few dB less leaves room for the car's own processing on its navigation output.
 */
internal class VoiceClarity(sampleRate: Int, clarity: Boolean, headroomDb: Int) {

    private val stages: List<Biquad> =
        if (clarity) listOf(Biquad.highPass(sampleRate, 150.0, 0.707), Biquad.peaking(sampleRate, 3_000.0, 1.0, 4.0))
        else emptyList()
    private val gain = 10.0.pow(-headroomDb / 20.0)
    // The presence lift raises the peaks a little: take it back so it never clips.
    private val makeup = if (clarity) 10.0.pow(-2.0 / 20.0) else 1.0

    val active: Boolean = clarity || headroomDb > 0

    /** Processes little-endian 16-bit mono PCM in place. */
    fun process(buffer: ByteArray, size: Int) {
        var i = 0
        while (i < size - 1) {
            var x = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toDouble()
            for (s in stages) x = s.next(x)
            val y = (x * gain * makeup).toInt().coerceIn(-32768, 32767)
            buffer[i] = (y and 0xFF).toByte()
            buffer[i + 1] = (y shr 8).toByte()
            i += 2
        }
    }

    /** RBJ cookbook biquad, direct form I. */
    private class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun next(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }

        companion object {
            fun highPass(fs: Int, f: Double, q: Double): Biquad {
                val w = 2 * PI * f / fs
                val alpha = sin(w) / (2 * q)
                val c = cos(w)
                val a0 = 1 + alpha
                return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }

            fun peaking(fs: Int, f: Double, q: Double, dbGain: Double): Biquad {
                val a = sqrt(10.0.pow(dbGain / 20.0))
                val w = 2 * PI * f / fs
                val alpha = sin(w) / (2 * q)
                val c = cos(w)
                val a0 = 1 + alpha / a
                return Biquad((1 + alpha * a) / a0, -2 * c / a0, (1 - alpha * a) / a0, -2 * c / a0, (1 - alpha / a) / a0)
            }
        }
    }
}
