package com.andrerinas.openheadunit.decoder

import com.andrerinas.openheadunit.utils.AppLog
import kotlin.math.abs

/** 16 kHz mono voice in, 48 kHz out. Keeps its state across calls so chunk joins are seamless. */
internal interface VoiceUpsampler {
    /** Writes the 48 kHz samples into [out] (room for `count * 3 + MAX_EXTRA`) and returns their number. */
    fun process(input: ShortArray, count: Int, out: ShortArray): Int
    fun reset()

    companion object {
        /** A frame-based upsampler may hand back one frame more than `count * 3`. */
        const val MAX_EXTRA = OpusBwe.FRAME * 3

        /** Voice enhancement setting: 0 standard, 1 light exciter, 2 strong exciter, 3 Opus neural. */
        fun create(enhancement: Int): VoiceUpsampler = when (enhancement) {
            1 -> ExciterUpsampler(EXCITER_LIGHT_DB)
            2 -> ExciterUpsampler(EXCITER_STRONG_DB)
            3 -> OpusBwe.createOrNull() ?: Upsampler3x()
            else -> Upsampler3x()
        }

        private const val EXCITER_LIGHT_DB = -18.0
        private const val EXCITER_STRONG_DB = -12.0
    }
}

/**
 * The standard upsampler plus a classic "exciter": the 4-7.8 kHz band, full-wave rectified,
 * makes harmonics an octave up; those, kept between 8.2 and 16 kHz, are added under the voice to
 * give it back some of the treble the phone's 16 kHz stream cut off.
 */
internal class ExciterUpsampler(levelDb: Double) : VoiceUpsampler {
    private val base = Upsampler3x()
    private val band = Sos(BAND)
    private val harmonics = Sos(HARMONICS)
    // 2.376 = RMS of the band over RMS of its filtered harmonics, measured on recorded prompts.
    private val gain = 2.376 * Math.pow(10.0, levelDb / 20.0)

    override fun process(input: ShortArray, count: Int, out: ShortArray): Int {
        val n = base.process(input, count, out)
        for (i in 0 until n) {
            val x = out[i].toDouble()
            val h = harmonics.next(abs(band.next(x)))
            out[i] = (x + h * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        return n
    }

    override fun reset() {
        base.reset(); band.reset(); harmonics.reset()
    }

    /** Cascade of biquads, rows of b0 b1 b2 a0 a1 a2 (scipy's sos layout). */
    private class Sos(private val rows: Array<DoubleArray>) {
        private val z = Array(rows.size) { DoubleArray(2) }

        fun next(input: Double): Double {
            var x = input
            for (k in rows.indices) {
                val r = rows[k]
                val s = z[k]
                // Transposed direct form II.
                val y = r[0] * x + s[0]
                s[0] = r[1] * x - r[4] * y + s[1]
                s[1] = r[2] * x - r[5] * y
                x = y
            }
            return x
        }

        fun reset() = z.forEach { it.fill(0.0) }
    }

    private companion object {
        /** Butterworth order-6 band-pass 4-7.8 kHz at 48 kHz. */
        val BAND = arrayOf(
            doubleArrayOf(0.00010133959688949378, 0.00020267919377898755, 0.00010133959688949378, 1.0, -1.0893425712028666, 0.5836795171546735),
            doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.2778944010907844, 0.63222901488735),
            doubleArrayOf(1.0, 2.0, 1.0, 1.0, -0.9751615297949145, 0.6560383382106475),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.4741812723279968, 0.7551943659322127),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -0.9838763836730248, 0.8558036717173125),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.6503859930514353, 0.911604921294954),
        )

        /** Butterworth order-6 band-pass 8.2-16 kHz at 48 kHz. */
        val HARMONICS = arrayOf(
            doubleArrayOf(0.003814146562170369, -0.007628293124340738, 0.003814146562170369, 1.0, -0.20209339972893842, 0.30988743237778793),
            doubleArrayOf(1.0, 2.0, 1.0, 1.0, 0.24069542826992413, 0.3121761462683269),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -0.5661909883678461, 0.4955237843677895),
            doubleArrayOf(1.0, 2.0, 1.0, 1.0, 0.6059766166755969, 0.4999407355042769),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -0.8398887549454894, 0.7977215408744524),
            doubleArrayOf(1.0, 2.0, 1.0, 1.0, 0.8827369838794329, 0.8003189824812369),
        )
    }
}

/**
 * Opus 1.6's neural bandwidth extension (libopusbwe.so, BSD): recreates the 8-20 kHz band of a
 * 16 kHz voice, 20 ms frames at a time. Input is buffered to whole frames.
 */
internal class OpusBwe private constructor(private val handle: Long) : VoiceUpsampler {
    private val frameIn = ShortArray(FRAME)
    private val frameOut = ShortArray(FRAME * 3)
    private var pending = 0

    override fun process(input: ShortArray, count: Int, out: ShortArray): Int {
        var o = 0
        for (i in 0 until count) {
            frameIn[pending++] = input[i]
            if (pending == FRAME) {
                nativeProcess(handle, frameIn, frameOut)
                System.arraycopy(frameOut, 0, out, o, FRAME * 3)
                o += FRAME * 3
                pending = 0
            }
        }
        return o
    }

    override fun reset() {
        pending = 0
        nativeReset(handle)
    }

    protected fun finalize() = nativeDestroy(handle)

    companion object {
        const val FRAME = 320

        private val loaded: Boolean by lazy {
            try {
                System.loadLibrary("opusbwe")
                true
            } catch (e: Throwable) {
                AppLog.e("Opus BWE unavailable: ${e.message}")
                false
            }
        }

        fun createOrNull(): OpusBwe? {
            if (!loaded) return null
            val h = nativeCreate()
            if (h == 0L) {
                AppLog.e("Opus BWE: models failed to load")
                return null
            }
            return OpusBwe(h)
        }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeProcess(handle: Long, input: ShortArray, output: ShortArray)
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
