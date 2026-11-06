package com.andrerinas.openheadunit.decoder

import kotlin.math.PI
import kotlin.math.sin

/**
 * 16 kHz → 48 kHz mono upsampler for Android Auto's voice channels: a Kaiser-windowed sinc low-pass
 * in polyphase form (192 taps, β = 9). Flat to 7 kHz, -27 dB at 8 kHz and below -90 dB from
 * 8.5 kHz: the images of the voice band are gone. The first 48-tap Blackman version was already
 * 3.6 dB down at 7 kHz and let the images through at -18 to -27 dB between 8.5 and 9 kHz.
 *
 * Both earlier paths left the images of the voice band in the output: the mixer interpolated
 * linearly, and a 16 kHz AudioTrack left the job to the car's resampler. Either way the prompts
 * sounded harsh and metallic. Keeps its history across calls, so chunk joins are seamless.
 */
internal class Upsampler3x : VoiceUpsampler {

    private val history = FloatArray(TAPS_PER_PHASE)

    /** Writes `count * 3` samples into [out] and returns that number. */
    override fun process(input: ShortArray, count: Int, out: ShortArray): Int {
        var o = 0
        for (i in 0 until count) {
            // Shift the newest sample in; history[0] is x[k].
            System.arraycopy(history, 0, history, 1, TAPS_PER_PHASE - 1)
            history[0] = input[i].toFloat()
            for (phase in 0 until 3) {
                var acc = 0f
                val taps = PHASES[phase]
                for (m in 0 until TAPS_PER_PHASE) acc += taps[m] * history[m]
                out[o++] = acc.toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return o
    }

    override fun reset() = history.fill(0f)

    companion object {
        private const val TAPS_PER_PHASE = 64
        private const val KAISER_BETA = 9.0
        private const val TAPS = TAPS_PER_PHASE * 3

        /** Filter taps split by output phase: PHASES[p][m] = h[p + 3m]. */
        private val PHASES: Array<FloatArray> = run {
            val cutoff = 7_600.0 / 48_000.0 // normalized to the output rate
            val center = (TAPS - 1) / 2.0
            val h = DoubleArray(TAPS) { n ->
                val x = n - center
                val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val r = 2.0 * n / (TAPS - 1) - 1.0
                val window = besselI0(KAISER_BETA * kotlin.math.sqrt(1 - r * r)) / besselI0(KAISER_BETA)
                sinc * window
            }
            // Unity gain per phase after zero-stuffing by 3.
            val sum = h.sum()
            Array(3) { p -> FloatArray(TAPS_PER_PHASE) { m -> (h[p + 3 * m] * 3.0 / sum).toFloat() } }
        }

        /** Modified Bessel function of the first kind, order 0, for the Kaiser window. */
        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            for (k in 1..50) {
                term *= (x / (2 * k)) * (x / (2 * k))
                sum += term
            }
            return sum
        }
    }
}
