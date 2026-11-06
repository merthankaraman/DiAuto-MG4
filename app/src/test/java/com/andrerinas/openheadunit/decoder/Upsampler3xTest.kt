package com.andrerinas.openheadunit.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class Upsampler3xTest {
    @Test
    fun `a steady level comes out at the same level`() {
        val up = Upsampler3x()
        val input = ShortArray(64) { 10_000 }
        val out = ShortArray(192)
        assertEquals(192, up.process(input, 64, out))
        assertTrue(abs(out[150] - 10_000) < 200)
    }

    @Test
    fun `a 1 kHz tone keeps its amplitude across chunk joins`() {
        val up = Upsampler3x()
        val tone = ShortArray(1600) { (16_000 * sin(2 * PI * 1000 * it / 16_000.0)).toInt().toShort() }
        val out = ShortArray(4800)
        // Fed in 32 ms chunks, as the phone sends them.
        var o = 0
        for (start in 0 until 1600 step 512) {
            val n = minOf(512, 1600 - start)
            val chunk = tone.copyOfRange(start, start + n)
            val part = ShortArray(n * 3)
            up.process(chunk, n, part)
            System.arraycopy(part, 0, out, o, n * 3); o += n * 3
        }
        val peak = out.drop(300).maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak in 15_000..17_000)
    }
}
