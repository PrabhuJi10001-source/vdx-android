package com.vdx.sonic.voice

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for AudioResampler — 8kHz↔16kHz PCM16 bidirectional conversion.
 *
 * Verifies length invariants, round-trip identity, and correctness of the
 * linear interpolation on known inputs.
 */
class AudioResamplerTest {

    @Test
    fun resample8to16_emptyInput_returnsEmpty() {
        assertEquals(0, AudioResampler.resample8to16(ShortArray(0)).size)
    }

    @Test
    fun resample16to8_emptyInput_returnsEmpty() {
        assertEquals(0, AudioResampler.resample16to8(ShortArray(0)).size)
    }

    @Test
    fun resample8to16_doublesLength() {
        val input = ShortArray(10) { it.toShort() }
        val out = AudioResampler.resample8to16(input)
        assertEquals(20, out.size)
    }

    @Test
    fun resample16to8_halvesLength() {
        val input = ShortArray(20) { it.toShort() }
        val out = AudioResampler.resample16to8(input)
        assertEquals(10, out.size)
    }

    @Test
    fun resample8to16_preservesEvenSamples() {
        // Upsampling inserts interpolated midpoints between original samples;
        // the even-indexed output samples must equal the original 8kHz samples.
        val input = shortArrayOf(100, 200, 300)
        val out = AudioResampler.resample8to16(input)
        assertEquals(100, out[0].toInt())
        assertEquals(200, out[2].toInt())
        assertEquals(300, out[4].toInt())
    }

    @Test
    fun resample8to16_interpolatesMidpoints() {
        // Linear interpolation: midpoint = average of adjacent samples.
        val input = shortArrayOf(100, 200)
        val out = AudioResampler.resample8to16(input)
        assertEquals(100, out[0].toInt())  // s0
        assertEquals(150, out[1].toInt())  // (100+200)/2
        assertEquals(200, out[2].toInt())  // s1
        // Last midpoint repeats the last sample (no next sample).
        assertEquals(200, out[3].toInt())
    }

    @Test
    fun resample16to8_averagesPairs() {
        // Downsampling averages each consecutive pair.
        val input = shortArrayOf(100, 200, 300, 400)
        val out = AudioResampler.resample16to8(input)
        assertEquals(2, out.size)
        assertEquals(150, out[0].toInt())  // (100+200)/2
        assertEquals(350, out[1].toInt())  // (300+400)/2
    }

    @Test
    fun resample16to8_truncatesOddTrailingSample() {
        val input = shortArrayOf(100, 200, 300)  // 3 samples → 1 output
        val out = AudioResampler.resample16to8(input)
        assertEquals(1, out.size)
        assertEquals(150, out[0].toInt())
    }

    @Test
    fun roundTrip_8to16to8_approximatesOriginal() {
        // 8→16→8 round trip: downsample averages the upsampled pairs.
        // For the even-sample-preserving upsample, pair-average of (s0, midpoint)
        // = (s0 + (s0+s1)/2)/2 which is close to s0 but not exactly s0.
        // Verify the round trip preserves length and stays within a reasonable
        // amplitude bound (no clipping / sign flip).
        val input = ShortArray(50) { (it * 100).toShort() }
        val up = AudioResampler.resample8to16(input)
        val back = AudioResampler.resample16to8(up)
        assertEquals(input.size, back.size)
        for (i in input.indices) {
            assertTrue("round-trip sample $i within bound", kotlin.math.abs(back[i].toInt() - input[i].toInt()) <= 100)
        }
    }

    @Test
    fun resample8to16_constantSignal_preservesValue() {
        // A constant signal (DC) must be preserved exactly through both directions.
        val input = ShortArray(20) { 42 }
        val up = AudioResampler.resample8to16(input)
        for (s in up) assertEquals(42, s.toInt())
        val back = AudioResampler.resample16to8(up)
        for (s in back) assertEquals(42, s.toInt())
    }

    @Test
    fun resample_handlesNegativeSamples() {
        val input = shortArrayOf(-100, 100, -100)
        val out = AudioResampler.resample8to16(input)
        assertEquals(-100, out[0].toInt())
        assertEquals(0, out[1].toInt())     // (-100+100)/2
        assertEquals(100, out[2].toInt())
        assertEquals(0, out[3].toInt())     // (100 + -100)/2
        assertEquals(-100, out[4].toInt())
        assertEquals(-100, out[5].toInt())  // last repeats
    }
}