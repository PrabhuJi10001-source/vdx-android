package com.vdx.sonic.voice.vad

/**
 * Pure Kotlin endpointing state machine for mic sessions.
 *
 * This class contains ONLY the decision logic (speech/silence frame → verdict) and is
 * deliberately independent of any native VAD implementation so it can be unit-tested
 * in a plain JVM (no `.so` loading). The actual per-frame voice classification is
 * supplied via [classifier]; production wires this to WebRTC VAD ([VadWebRtc]),
 * tests use a stub.
 *
 * Behavior:
 *  - Idle → speech onset requires [minSpeechMs] of continuous speech before the
 *    utterance is "live" (filters one-off blips / background noise).
 *  - Once live, [trailingSilenceMs] of continuous silence auto-finalizes the
 *    utterance ([VadResult.ENDED_UTTERANCE]).
 *  - [reset] makes the detector reusable for a fresh utterance.
 *
 * A detector instance is NOT thread-safe; callers serialize access.
 */
class VadEndpointDetector(
    /** Trailing silence (ms) after speech onset that finalizes the utterance. */
    val trailingSilenceMs: Int = 700,

    /** Minimum continuous speech (ms) before an utterance is considered live. */
    val minSpeechMs: Int = 200,

    /** Frame length in ms (10ms for WebRTC VAD @16kHz/160 samples). */
    private val frameMs: Int = 10,

    /** Per-frame classifier: true = speech present in this frame. */
    private val classifier: (ShortArray) -> Boolean
) {

    /** Outcome of a frame feed. */
    enum class VadResult {
        /** Frame consumed; no speech onset and no utterance in progress. */
        IDLE,

        /** Frame consumed; utterance is live but not yet finalized. */
        IN_UTTERANCE,

        /** Frame consumed; utterance auto-finalized after trailing silence. */
        ENDED_UTTERANCE
    }

    private var inUtterance = false
    private var speechFrames = 0
    private var silenceFrames = 0
    private val minSpeechFrames = (minSpeechMs / frameMs).coerceAtLeast(1)
    private val trailingSilenceFrames = (trailingSilenceMs / frameMs).coerceAtLeast(1)

    /** Feed one frame; returns the resulting [VadResult]. */
    fun feed(frame: ShortArray): VadResult {
        val speech = classifier(frame)
        if (speech) {
            silenceFrames = 0
            if (inUtterance || ++speechFrames >= minSpeechFrames) {
                inUtterance = true
                return VadResult.IN_UTTERANCE
            }
            return VadResult.IDLE
        }
        // Non-speech frame.
        if (!inUtterance) {
            speechFrames = 0
            return VadResult.IDLE
        }
        if (++silenceFrames >= trailingSilenceFrames) {
            reset()
            return VadResult.ENDED_UTTERANCE
        }
        return VadResult.IN_UTTERANCE
    }

    /**
     * Analyze a complete buffer and decide whether it holds a usable utterance.
     * Splits [buffer] into [frameMs]-long frames and runs the state machine over them.
     *
     * @return [VadResult.IN_UTTERANCE] if speech onset was reached, else
     *         [VadResult.IDLE] (silent or too short). Used to gate the existing
     *         full-buffer ASR path so Whisper never hallucinates on silence.
     */
    fun analyze(buffer: ShortArray): VadResult {
        reset()
        var live = false
        val frameSamples = frameMs * 16 // 16kHz mono → 160 samples per 10ms frame
        var idx = 0
        val frame = ShortArray(frameSamples)
        while (idx + frame.size <= buffer.size) {
            System.arraycopy(buffer, idx, frame, 0, frame.size)
            if (feed(frame) == VadResult.IN_UTTERANCE) live = true
            idx += frame.size
        }
        reset()
        return if (live) VadResult.IN_UTTERANCE else VadResult.IDLE
    }

    /** Reset the state machine (usable for a fresh utterance). */
    fun reset() {
        inUtterance = false
        speechFrames = 0
        silenceFrames = 0
    }
}
