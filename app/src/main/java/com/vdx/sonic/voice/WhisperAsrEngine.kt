package com.vdx.sonic.voice

/**
 * WhisperAsrEngine — the HANDY-style on-device voice engine (v0.1.5, user-ordered).
 *
 * Uses the Handy project's engine choice, whisper.cpp (MIT license), compiled into
 * the APK as a native library, running the SAME multilingual GGML model Handy
 * distributes (English + Hindi + 90+ languages, automatic language detection).
 * 100% offline: audio → text never leaves the phone (privacy law preserved).
 *
 * The LANGUAGE PACKS the user installs from the in-app "Languages" tab are the
 * Handy model files (one-time download, from the official whisper.cpp / Handy
 * distribution). No model file present → engine reports unavailable and the
 * system recognizer stays in charge. No paid anything.
 */
object WhisperAsrEngine {

    init {
        try {
            System.loadLibrary("whisper_jni")
        } catch (_: UnsatisfiedLinkError) {
            // engine not compiled into this build — status reflects that honestly
        }
    }

    /** true when the native library is present in this APK build. */
    fun nativeAvailable(): Boolean {
        return try {
            System.loadLibrary("whisper_jni"); true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** Long-running native transcription. Call off the UI thread. */
    external fun nativeTranscribe(pcm16: ShortArray, sampleRate: Int, modelPath: String, threads: Int): String

    /**
     * Full convenience: PCM16 → WAV file → whisper → text.
     * @return transcript, or null when engine/model unavailable or audio empty.
     */
    fun transcribe(samples: ShortArray, sampleRate: Int, modelPath: String): String? {
        if (samples.isEmpty()) return null
        if (modelPath.isBlank() || !java.io.File(modelPath).isFile) return null
        if (!nativeAvailable()) return null
        return try {
            val text = nativeTranscribe(samples, sampleRate, modelPath, 4)
            text?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }
}
