package com.vdx.sonic.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.PrebuiltVoiceConfig
import com.google.genai.types.SpeechConfig
import com.google.genai.types.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemini TTS (priority 6 spoken response).
 *
 * Uses the Gemini generateContent API with responseModalities=[AUDIO] and a
 * speechConfig voice — the same Client and call shape GeminiAsrEngine already
 * uses, so there is no second SDK/runtime. Returns the synthesized audio as an
 * inline part and streams it straight to an AudioTrack (no file, no re-encode).
 *
 * Offline note: this is a cloud path. The caller must fall back to Android's
 * system TextToSpeech when no API key is configured (see SonicEngine.speak()).
 */
class GeminiTtsEngine(
    private val context: Context,
    private val apiKey: String,
    private val model: String = "gemini-3.1-flash-tts-preview",
    private val voiceName: String = "Kore"
) {
    companion object {
        private const val TAG = "GeminiTts"
    }

    /** True while an utterance is playing. Used to stop/cancel on a new turn. */
    val isSpeaking = AtomicBoolean(false)

    /**
     * Synthesize `text` and play it. Returns true if audio was produced and
     * playback started. Throws on any API failure so the caller can fall back.
     */
    suspend fun speak(text: String): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || text.isBlank()) return@withContext false
        stop() // never overlap the previous utterance
        try {
            val client = Client.builder().apiKey(apiKey).build()

            val textPart = Part.builder().text(text).build()
            val config = GenerateContentConfig.builder()
                .responseModalities(listOf("AUDIO"))
                .speechConfig(
                    SpeechConfig.builder()
                        .voiceConfig(
                            VoiceConfig.builder()
                                .prebuiltVoiceConfig(
                                    PrebuiltVoiceConfig.builder().voiceName(voiceName).build()
                                )
                                .build()
                        )
                        .build()
                )
                .build()

            val response = client.models.generateContent(
                model,
                listOf(Content.builder().role("user").parts(listOf(textPart)).build()),
                config
            )

            val part: Part? = response.candidates()?.orElse(null)
                ?.firstOrNull()?.content()?.orElse(null)?.parts()?.orElse(null)
                ?.firstOrNull()
            val blob = part?.inlineData()?.orElse(null)
                ?: run {
                    Log.w(TAG, "No inline audio in response")
                    return@withContext false
                }
            val audioBytes = blob.data()?.orElse(null) ?: return@withContext false
            playPcm24k(audioBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini TTS failed", e)
            throw e
        }
    }

    /** Stop any in-flight playback. Safe to call repeatedly. */
    fun stop() {
        isSpeaking.set(false)
        try {
            _activeTrack?.stop()
        } catch (_: Exception) {
        }
        _activeTrack?.release()
        _activeTrack = null
    }

    @Volatile
    private var _activeTrack: AudioTrack? = null

    private fun playPcm24k(pcm: ByteArray): Boolean {
        if (pcm.isEmpty()) return false
        val sampleRate = 24000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, pcm.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            isSpeaking.set(true)
            _activeTrack = track
            track.play()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "playback failed", e)
            track.release()
            _activeTrack = null
            return false
        }
    }
}
