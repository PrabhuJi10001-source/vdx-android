package com.vdx.sonic.voice.vad

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate

/**
 * VDX VAD (Voice Activity Detection) — WebRTC GMM DSP endpoint for mic-session
 * endpointing.
 *
 * WebRTC VAD is a lightweight (~140KB native AAR), BSD/MIT-licensed signal-processing
 * filter (NOT a model), so it complies with the "no heavy native libs / no on-device
 * model download" rule. It classifies 16 kHz PCM16 frames as speech or silence.
 *
 * This wraps the native [VadWebRTC] classifier behind the pure endpointing state
 * machine [VadEndpointDetector], exposing two use modes:
 *
 * 1. **Streaming endpointing** — for a live AudioRecord capture loop. Feed 10ms
 *    frames via [feed]; once speech onset is observed and then a trailing silent
 *    window ([trailingSilenceMs]) elapses, [VadEndpointDetector.VadResult.ENDED_UTTERANCE]
 *    is returned so the caller finalizes the capture and hands it to ASR.
 *
 * 2. **Full-buffer gating** — for the existing PCM path where [CaptureSession] already
 *    contains a complete utterance. [analyze] runs the same detector over the buffer
 *    and returns [VadEndpointDetector.VadResult.IDLE] for silent / too-short captures,
 *    so the ASR layer (Groq Whisper) never hallucinates short phrases on silence.
 *
 * A [VadWebRtc] instance is NOT thread-safe; callers must serialize access.
 * All time-based parameters are in milliseconds.
 */
class VadWebRtc(
    /** Trailing silence (ms) that auto-finalizes an utterance after speech onset. */
    val trailingSilenceMs: Int = 700,

    /** Minimum continuous speech (ms) before an utterance is considered live. */
    val minSpeechMs: Int = 200,

    /** WebRTC VAD aggressiveness — VERY_AGGRESSIVE has the lowest miss rate. */
    mode: Mode = Mode.VERY_AGGRESSIVE
) : AutoCloseable {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 10
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000 // 160
    }

    private val vad: VadWebRTC =
        VadWebRTC(
            sampleRate = SampleRate.SAMPLE_RATE_16K,
            frameSize = FrameSize.FRAME_SIZE_160,
            mode = mode
        )

    private val detector = VadEndpointDetector(
        trailingSilenceMs = trailingSilenceMs,
        minSpeechMs = minSpeechMs,
        frameMs = FRAME_MS,
        classifier = { frame -> vad.isSpeech(frame) }
    )

    /**
     * Outcome of a frame feed / full-buffer analysis.
     */

    /** Feed one 10ms PCM16 mono 16 kHz frame (160 shorts). */
    fun feed(frame: ShortArray): VadEndpointDetector.VadResult = detector.feed(frame)

    /**
     * Analyze a complete 16 kHz PCM buffer and decide whether it holds a usable
     * utterance. Gates the existing [CaptureSession] ASR path.
     */
    fun analyze(buffer: ShortArray): VadEndpointDetector.VadResult = detector.analyze(buffer)

    /** Reset the streaming state machine (keeps the native detector alive). */
    fun reset() = detector.reset()

    override fun close() {
        vad.close()
    }
}
