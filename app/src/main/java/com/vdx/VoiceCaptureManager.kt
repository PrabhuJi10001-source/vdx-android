package com.vdx

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.sqrt
import java.util.concurrent.Executors

/**
 * VoiceCaptureManager — AudioRecord-based voice capture with Gemini multimodal processing.
 *
 * Captures 16kHz PCM16 mono audio from the mic, runs simple energy-based VAD,
 * and sends the audio directly to Gemini 3.6 Flash via the Google Gen AI Java SDK.
 *
 * **Key improvement over the old approach:** Instead of two separate calls
 * (Google Cloud STT → transcript → LlmBridge → intent), this sends the raw audio
 * directly to Gemini which returns both the transcript AND the structured intent
 * in a single API call. This reduces latency, cost, and failure points.
 *
 * When no API key is configured, delivers a fallback message and triggers
 * the keyboard input path.
 *
 * Callback interface:
 *   onSpeechStart()                    — first frame above threshold detected
 *   onSpeechEnd(audioData: ShortArray)  — user stopped speaking; captured audio
 *   onTranscript(text: String)          — Gemini returned a transcript
 *   onIntent(intent: VdxIntent)         — Gemini returned a structured intent
 *   onError(message: String)            — something went wrong
 *   onAudioLevel(rmsdB: Float)          — continuous RMS dB for waveform animation
 *
 * Hardware safety features:
 *  - Dedicated high-priority audio thread (never blocks Dispatchers.Default)
 *  - Hard timeout via withTimeoutOrNull (prevents infinite capture hang)
 *  - OEM audio source fallback (VOICE_RECOGNITION → MIC)
 *  - Pre-allocated 4-slot buffer pool (eliminates GC pressure during PCM ingestion)
 */
class VoiceCaptureManager(
    private val context: Context,
    private val callback: Callback
) {

    interface Callback {
        fun onSpeechStart()
        fun onSpeechEnd(audioData: ShortArray)
        fun onTranscript(text: String)
        fun onIntent(intent: VdxIntent)
        fun onError(message: String)
        fun onAudioLevel(rmsdB: Float)
    }

    companion object {
        private const val TAG = "VDX-VCM"

        // Audio config
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // VAD parameters
        private const val SPEECH_THRESHOLD_DB = -30.0f   // RMS > -30 dB = speech
        private const val SILENCE_DURATION_MS = 1500L    // 1.5 s of silence → speech end
        private const val MAX_RECORDING_MS = 30000L       // hard cap 30 s
        private const val FRAME_MS = 50L                  // process every 50 ms

        // Buffer sizes
        private const val FRAME_SAMPLES = (SAMPLE_RATE * FRAME_MS / 1000).toInt() // 800 samples

        // SharedPreferences keys
        private const val PREFS_NAME = "vdx_prefs"
        private const val KEY_API_KEY = "vdx_llm_api_key"
        private const val KEY_MODEL = "vdx_llm_model"

        // Gemini multimodal prompt — asks for both transcript and structured intent
        private val GEMINI_PROMPT: String = """
You are VDX, a voice action assistant for blind users.
Transcribe the audio and extract the user's intent.
Return JSON with: transcript (the exact words spoken), action (one of: call, whatsapp, sms, uber, youtube, email, app_launch, read_screen, unknown), contact (name if mentioned), message (text to send if applicable), destination (for uber), app_name (for app_launch), search_query (for youtube), confidence (0-1).
If confidence < 0.7, include a clarification_question field.
Examples: "uh message Ravi on WhatsApp that I'll be 10 minutes late" → {transcript: "uh message Ravi on WhatsApp that I'll be 10 minutes late", action: whatsapp, contact: Ravi, message: I'll be 10 minutes late, confidence: 0.95}. "call Mom" → {transcript: "call Mom", action: call, contact: Mom, confidence: 0.99}.
        """.trimIndent()
    }

    // ──────────────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────────────

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    @Volatile private var isCapturing = false

    private val collectedAudio = ArrayList<Short>()
    private var speechStarted = false
    private var silenceStartMs: Long = 0L
    private var captureStartMs: Long = 0L

    // Dedicated high-priority audio thread — never blocks Dispatchers.Default
    private val audioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "audio-capture").apply { priority = Thread.MAX_PRIORITY }
    }
    private val audioDispatcher = audioExecutor.asCoroutineDispatcher()

    // Pre-allocated 4-slot buffer pool (eliminates GC pressure during PCM ingestion)
    private val bufferPool = Array(4) { ShortArray(FRAME_SAMPLES) }

    // ──────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────

    /**
     * Start capturing audio from the mic.  Must be called from a thread that
     * is allowed to spawn a background thread (any thread).
     */
    fun startCapture() {
        Log.d(TAG, "startCapture: called")
        if (isCapturing) {
            Log.w(TAG, "startCapture: already capturing, ignoring")
            return
        }

        // Check RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "startCapture: RECORD_AUDIO permission not granted")
            callback.onError("Microphone permission needed.")
            return
        }

        // Compute buffer size
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuf * 2, FRAME_SAMPLES * 2 * 4)
        Log.d(TAG, "startCapture: minBuf=$minBuf, bufferSize=$bufferSize")

        // Try VOICE_RECOGNITION first, fall back to MIC (OEM compatibility)
        audioRecord = createAudioRecord(bufferSize)
        if (audioRecord == null) {
            Log.e(TAG, "startCapture: all audio sources failed")
            callback.onError("Microphone hardware unavailable.")
            return
        }

        // Reset state
        collectedAudio.clear()
        speechStarted = false
        silenceStartMs = 0L
        captureStartMs = System.currentTimeMillis()

        isCapturing = true
        audioRecord?.startRecording()
        Log.d(TAG, "startCapture: AudioRecord started recording")

        // Launch capture on dedicated audio thread with hard timeout
        captureJob = CoroutineScope(audioDispatcher + SupervisorJob()).launch {
            val success = withTimeoutOrNull(MAX_RECORDING_MS + 5000L) {
                captureLoop()
            }
            if (success == null) {
                Log.w(TAG, "startCapture: capture timed out (${MAX_RECORDING_MS}ms)")
                handleCaptureTimeout()
            }
        }
    }

    /**
     * Try audio sources in priority order. VOICE_RECOGNITION preferred,
     * MIC fallback for OEMs that restrict the former.
     */
    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            try {
                val recorder = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    val sourceName = when (source) {
                        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
                        MediaRecorder.AudioSource.MIC -> "MIC"
                        else -> "UNKNOWN"
                    }
                    Log.i(TAG, "AudioRecord initialized (Source: $sourceName)")
                    return recorder
                } else {
                    recorder.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioSource $source failed: ${e.message}")
            }
        }
        return null
    }

    private fun handleCaptureTimeout() {
        Log.w(TAG, "handleCaptureTimeout: force-stopping capture")
        isCapturing = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        callback.onError("Recording timed out. Please try again.")
    }

    /**
     * Stop capturing immediately and release resources.
     */
    fun stopCapture() {
        Log.d(TAG, "stopCapture: called")
        isCapturing = false
        captureJob?.cancel()
        captureJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "stopCapture: AudioRecord released")
    }

    // ──────────────────────────────────────────────────────────────────
    // Capture Loop (background thread)
    // ──────────────────────────────────────────────────────────────────

    private suspend fun captureLoop() {
        Log.d(TAG, "captureLoop: started")
        var poolIndex = 0
        var lastLevelUpdateMs = 0L

        while (isCapturing && kotlin.coroutines.coroutineContext.isActive) {

            val readBuffer = bufferPool[poolIndex]
            val readCount = audioRecord?.read(readBuffer, 0, FRAME_SAMPLES) ?: -1
            if (readCount <= 0) {
                Log.w(TAG, "captureLoop: read returned $readCount")
                if (readCount == AudioRecord.ERROR_INVALID_OPERATION ||
                    readCount == AudioRecord.ERROR_BAD_VALUE
                ) {
                    callback.onError("AudioRecord read error: $readCount")
                    break
                }
                continue
            }

            // Compute RMS in dB
            val rms = computeRms(readBuffer, readCount)
            val rmsDb = if (rms > 0) (20.0 * log10(rms / 32767.0)).toFloat() else -100f

            // Notify UI about audio level (throttle to ~20 fps)
            val now = System.currentTimeMillis()
            if (now - lastLevelUpdateMs >= 50) {
                lastLevelUpdateMs = now
                callback.onAudioLevel(rmsDb)
            }

            // Time since capture start
            val elapsedMs = now - captureStartMs

            // VAD state machine
            if (!speechStarted) {
                if (rmsDb > SPEECH_THRESHOLD_DB) {
                    Log.d(TAG, "captureLoop: SPEECH START detected (rmsDb=$rmsDb)")
                    speechStarted = true
                    for (i in 0 until readCount) collectedAudio.add(readBuffer[i])
                    callback.onSpeechStart()
                }
            } else {
                // Already speaking — append audio
                for (i in 0 until readCount) collectedAudio.add(readBuffer[i])

                if (rmsDb < SPEECH_THRESHOLD_DB) {
                    // Below threshold — count silence
                    if (silenceStartMs == 0L) {
                        silenceStartMs = now
                    }
                    val silenceMs = now - silenceStartMs

                    if (silenceMs >= SILENCE_DURATION_MS) {
                        Log.d(TAG, "captureLoop: SPEECH END (silence ${silenceMs}ms)")
                        finishCapture()
                        return
                    }
                } else {
                    // Reset silence counter
                    silenceStartMs = 0L
                }

                // Hard cap: stop after MAX_RECORDING_MS
                if (elapsedMs >= MAX_RECORDING_MS) {
                    Log.d(TAG, "captureLoop: max recording time reached ($elapsedMs ms)")
                    finishCapture()
                    return
                }
            }

            poolIndex = (poolIndex + 1) % bufferPool.size
        }

        // If we exit the loop while still capturing (e.g. interrupted), deliver what we have
        if (isCapturing && speechStarted && collectedAudio.isNotEmpty()) {
            Log.d(TAG, "captureLoop: loop ended, delivering partial capture")
            finishCapture()
        }
        Log.d(TAG, "captureLoop: ended")
    }

    // ──────────────────────────────────────────────────────────────────
    // Finish & Process via Gemini Multimodal
    // ──────────────────────────────────────────────────────────────────

    private fun finishCapture() {
        Log.d(TAG, "finishCapture: stopping, ${collectedAudio.size} samples collected")
        isCapturing = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val audioData = collectedAudio.toShortArray()
        collectedAudio.clear()
        callback.onSpeechEnd(audioData)

        // Check for API key
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.d(TAG, "finishCapture: no API key, delivering fallback")
            callback.onTranscript("")
            return
        }

        Log.d(TAG, "finishCapture: sending audio to Gemini multimodal")
        processWithGemini(audioData, apiKey)
    }

    /**
     * Send raw PCM audio to Gemini 3.6 Flash via the Google Gen AI Java SDK.
     * Gemini handles both transcription and intent extraction in one call.
     */
    private fun processWithGemini(audioData: ShortArray, apiKey: String) {
        Thread {
            try {
                Log.d(TAG, "processWithGemini: ${audioData.size} samples")

                // Convert ShortArray → WAV bytes (Gemini expects WAV, not raw PCM)
                val wavBytes = pcmToWav(audioData, SAMPLE_RATE)

                val model = getModel()

                // Build the Gemini client
                val client = Client.builder()
                    .apiKey(apiKey)
                    .build()

                // Create audio part + text prompt part
                val audioPart = Part.builder()
                    .inlineData(com.google.genai.types.Blob.builder()
                        .mimeType("audio/wav")
                        .data(wavBytes)
                        .build())
                    .build()

                val textPart = Part.builder()
                    .text(GEMINI_PROMPT)
                    .build()

                val config = GenerateContentConfig.builder()
                    .temperature(0.1f)
                    .maxOutputTokens(500)
                    .build()

                val response = client.models.generateContent(
                    model,
                    listOf(Content.builder()
                        .role("user")
                        .parts(listOf(audioPart, textPart))
                        .build()),
                    config
                )

                val text = response.text()
                if (text.isNullOrBlank()) {
                    Log.w(TAG, "processWithGemini: empty response")
                    callback.onError("Gemini returned empty response")
                    return@Thread
                }

                Log.d(TAG, "processWithGemini: response=$text")

                // Parse the JSON response
                val json = org.json.JSONObject(extractJson(text))
                val transcript = json.optString("transcript", "").trim()
                val action = json.optString("action", "unknown").lowercase().trim()
                val confidence = json.optDouble("confidence", 0.5)
                val contact = json.optString("contact", "").trim()
                val message = json.optString("message", "").trim()
                val destination = json.optString("destination", "").trim()
                val appName = json.optString("app_name", "").trim()
                val searchQuery = json.optString("search_query", "").trim()
                val clarificationQuestion = json.optString("clarification_question", "").trim()

                // Deliver transcript
                if (transcript.isNotBlank()) {
                    callback.onTranscript(transcript)
                }

                // Build and deliver VdxIntent
                val intent = if (confidence < 0.7 && clarificationQuestion.isNotBlank()) {
                    VdxIntent.Clarification(action, clarificationQuestion)
                } else {
                    when (action) {
                        "call" -> VdxIntent.Call(contact.ifBlank { "unknown" })
                        "whatsapp" -> VdxIntent.WhatsApp(contact.ifBlank { "unknown" }, message)
                        "sms" -> VdxIntent.Sms(contact.ifBlank { "unknown" }, message)
                        "uber" -> VdxIntent.Uber(destination.ifBlank { "unknown" })
                        "youtube" -> VdxIntent.YouTube(searchQuery.ifBlank { transcript })
                        "email" -> VdxIntent.Email(contact.ifBlank { "unknown" }, message)
                        "app_launch" -> VdxIntent.AppLaunch(appName.ifBlank { transcript })
                        "read_screen" -> VdxIntent.ReadScreen
                        else -> VdxIntent.Unknown(transcript.ifBlank { "audio captured" })
                    }
                }
                callback.onIntent(intent)

            } catch (e: Exception) {
                Log.e(TAG, "processWithGemini: exception", e)
                callback.onError("Gemini error: ${e.message}")
            }
        }.also { it.name = "VCM-Gemini" }.start()
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private fun computeRms(buffer: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val v = buffer[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / count)
    }

    /**
     * Convert raw PCM16 mono samples to a WAV file bytes.
     * Gemini expects WAV format, not raw PCM.
     */
    private fun pcmToWav(samples: ShortArray, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2 // 16-bit = 2 bytes per sample
        val dataSize = samples.size * 2
        val fileSize = 44 + dataSize

        val wav = java.io.ByteArrayOutputStream()
        val data = java.nio.ByteBuffer.allocate(fileSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        data.put("RIFF".toByteArray())
        data.putInt(36 + dataSize)  // file size - 8
        data.put("WAVE".toByteArray())

        // fmt chunk
        data.put("fmt ".toByteArray())
        data.putInt(16)             // chunk size
        data.putShort(1)            // PCM format
        data.putShort(1)            // mono
        data.putInt(sampleRate)
        data.putInt(byteRate)       // byte rate
        data.putShort(2)            // block align
        data.putShort(16)           // bits per sample

        // data chunk
        data.put("data".toByteArray())
        data.putInt(dataSize)
        for (s in samples) data.putShort(s)

        wav.write(data.array())
        return wav.toByteArray()
    }

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        val fenceRegex = Regex("(?s)```(?:json)?\\s*(\\{.*?})\\s*```")
        val fenceMatch = fenceRegex.find(trimmed)
        if (fenceMatch != null) return fenceMatch.groupValues[1]
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1)
        }
        return trimmed
    }

    private fun getApiKey(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_KEY, null)
    }

    private fun getModel(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MODEL, null) ?: "gemini-3.6-flash"
    }
}
