package com.vdx.sonic.voice

/**
 * AudioResampler — bidirectional 8kHz↔16kHz PCM16 resampling.
 *
 * Bidirectional 8kHz↔16kHz PCM16 resampling for telephony vs mic capture.
 *
 * Implementation: linear interpolation. No external dependencies, no
 * MediaCodec — a stateless pure-Kotlin function is smaller, deterministic,
 * and avoids the codec-initialisation overhead on every chunk.
 *
 * ponytail: linear interpolation is a first-order hold. It preserves
 * amplitude but not phase for content above ~4kHz (Nyquist of the 8kHz
 * leg). Fine for speech telephony; upgrade to a polyphase FIR if we ever
 * stream music through this path.
 */
object AudioResampler {

    private const val RATIO_16_OVER_8 = 2

    /**
     * Upsample 8kHz PCM16 → 16kHz PCM16 by linear interpolation.
     * Output length = input.length * 2.
     */
    fun resample8to16(input: ShortArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)
        val out = ShortArray(input.size * RATIO_16_OVER_8)
        for (i in input.indices) {
            val s0 = input[i].toInt()
            val s1 = if (i + 1 < input.size) input[i + 1].toInt() else s0
            out[i * 2] = s0.toShort()
            // Linear midpoint between adjacent 8kHz samples.
            out[i * 2 + 1] = ((s0 + s1) / 2).toShort()
        }
        return out
    }

    /**
     * Downsample 16kHz PCM16 → 8kHz PCM16 by decimation with simple averaging.
     * Output length = input.length / 2 (truncates a trailing odd sample).
     */
    fun resample16to8(input: ShortArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)
        val out = ShortArray(input.size / RATIO_16_OVER_8)
        for (i in out.indices) {
            val s0 = input[i * 2].toInt()
            val s1 = if (i * 2 + 1 < input.size) input[i * 2 + 1].toInt() else s0
            out[i] = ((s0 + s1) / 2).toShort()
        }
        return out
    }
}