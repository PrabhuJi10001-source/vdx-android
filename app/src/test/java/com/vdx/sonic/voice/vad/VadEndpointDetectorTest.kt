package com.vdx.sonic.voice.vad

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure [VadEndpointDetector] endpointing state machine.
 *
 * These tests use a stub classifier (no native WebRTC .so), so they run in a plain
 * JVM. They verify the core endpointing contract: speech onset detection, trailing
 * silence auto-finalization, silence/too-short discard, and reset reusability.
 */
class VadEndpointDetectorTest {

    /** Builds a detector whose frame classifier is driven by a simple predicate. */
    private fun detector(
        trailingSilenceMs: Int = 800,
        minSpeechMs: Int = 200,
        frameIsSpeech: (ShortArray) -> Boolean = { true }
    ): VadEndpointDetector =
        VadEndpointDetector(
            trailingSilenceMs = trailingSilenceMs,
            minSpeechMs = minSpeechMs,
            frameMs = 10,
            classifier = frameIsSpeech
        )

    private fun silenceFrame(): ShortArray = ShortArray(160) { 0 }
    private fun speechFrame(): ShortArray = ShortArray(160) { 1 }

    /** A whole buffer built from a repeated frame pattern. */
    private fun bufferOf(frames: Int, frame: ShortArray): ShortArray {
        val out = ShortArray(frames * frame.size)
        for (i in 0 until frames) System.arraycopy(frame, 0, out, i * frame.size, frame.size)
        return out
    }

    @Test
    fun silenceOnly_neverEntersUtterance() {
        val vad = detector { false } // classifier always says "no speech"
        repeat(50) {
            assertEquals(VadEndpointDetector.VadResult.IDLE, vad.feed(silenceFrame()))
        }
        assertEquals(
            VadEndpointDetector.VadResult.IDLE,
            vad.analyze(bufferOf(40, silenceFrame()))
        )
    }

    @Test
    fun speechOnset_thenTrailingSilence_autoEndsUtterance() {
        // minSpeechMs=200 → 20 frames. Frames 1-19 are below the onset threshold
        // (IDLE); the 20th crosses it and makes the utterance live. Then 800ms
        // (80 frames) of silence → auto-finalize.
        val d = detector(trailingSilenceMs = 800, minSpeechMs = 200) { it.any { s -> s > 0 } }
        repeat(19) {
            assertEquals(VadEndpointDetector.VadResult.IDLE, d.feed(speechFrame()))
        }
        assertEquals(VadEndpointDetector.VadResult.IN_UTTERANCE, d.feed(speechFrame())) // 20th
        repeat(79) {
            assertEquals(VadEndpointDetector.VadResult.IN_UTTERANCE, d.feed(silenceFrame()))
        }
        assertEquals(VadEndpointDetector.VadResult.ENDED_UTTERANCE, d.feed(silenceFrame()))
    }

    @Test
    fun tooShortSpeechBlip_isTreatedAsNoise_notUtterance() {
        // 100ms of speech (< minSpeechMs=200) must NOT start an utterance.
        val d = detector(trailingSilenceMs = 800, minSpeechMs = 200) { it.any { s -> s > 0 } }
        repeat(10) { d.feed(speechFrame()) }
        repeat(20) { d.feed(silenceFrame()) }

        // Same short blip through full-buffer analyze → IDLE (discard).
        assertEquals(
            VadEndpointDetector.VadResult.IDLE,
            d.analyze(bufferOf(10, speechFrame()))
        )
    }

    @Test
    fun reset_makesDetectorReusableForNewUtterance() {
        val d = detector(trailingSilenceMs = 200, minSpeechMs = 100) { it.any { s -> s > 0 } }
        // Complete utterance 1. minSpeechMs=100 → 10 frames; frames 1-9 IDLE,
        // 10th crosses onset → IN_UTTERANCE. trailingSilenceMs=200 → 20 frames.
        repeat(9) { d.feed(speechFrame()) }
        assertEquals(VadEndpointDetector.VadResult.IN_UTTERANCE, d.feed(speechFrame())) // 10th
        repeat(19) { d.feed(silenceFrame()) }
        assertEquals(VadEndpointDetector.VadResult.ENDED_UTTERANCE, d.feed(silenceFrame())) // 20th silence
        // Detector auto-reset; a fresh utterance can start again.
        repeat(9) { d.feed(speechFrame()) }
        assertEquals(VadEndpointDetector.VadResult.IN_UTTERANCE, d.feed(speechFrame())) // 10th of utterance 2
    }

    @Test
    fun analyze_withRealSpeech_returnsInUtterance() {
        val d = detector(trailingSilenceMs = 800, minSpeechMs = 200) { it.any { s -> s > 0 } }
        // 40 frames of speech (~400ms) → valid utterance.
        assertEquals(
            VadEndpointDetector.VadResult.IN_UTTERANCE,
            d.analyze(bufferOf(40, speechFrame()))
        )
    }
}
