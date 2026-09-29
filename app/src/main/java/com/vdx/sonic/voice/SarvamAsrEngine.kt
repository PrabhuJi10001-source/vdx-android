package com.vdx.sonic.voice

import com.vdx.sonic.AsrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * SarvamAsrEngine — cloud ASR via Sarvam AI Speech-to-Text.
 *
 * Sends PCM audio converted to WAV to Sarvam's `/speech-to-text` endpoint (the
 * Saarika model family for Indic-language transcription). Mirrors the shape of
 * [GroqAsrEngine]/[OpenAiAsrEngine]: multipart POST, Authorization: Bearer.
 *
 * Auth: Sarvam accepts the API key as `Authorization: Bearer <key>` (it also
 * accepts `api-subscription-key`, but Bearer is used here to match the other ASR
 * engines).
 *
 * Model handling: the model name is read from prefs under `vdx_sarvam_model`
 * with a default of `saarika:v2.5`. If the API rejects that model name (any
 * HTTP error response that names the model), the error body is logged ONCE and
 * the engine falls back to `saarika:v1` for the duration of this instance.
 */
class SarvamAsrEngine(
    private val apiKey: String,
    private val model: String = "saarika:v2.5",
    private val fallbackModel: String = "saarika:v1",
    private val baseUrl: String = "https://api.sarvam.ai/speech-to-text"
) {
    companion object {
        private const val TAG = "Sonic-SarvamASR"
        private const val TIMEOUT_MS = 30_000
        private const val SAMPLE_RATE = 16000
    }

    /** Current effective model; may flip to [fallbackModel] once on rejection. */
    private var effectiveModel: String = model

    /** Whether we already logged the model-rejection error body this run. */
    private var fallbackLogged = false

    /**
     * Transcribe PCM16 audio data.
     * @param audioData Raw PCM16 mono 16kHz samples
     * @return ASR result with transcript and confidence
     */
    suspend fun transcribe(audioData: ShortArray): AsrResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || audioData.isEmpty()) {
            return@withContext AsrResult(text = "", confidence = 0f, provider = "sarvam")
        }
        try {
            val wav = WavUtil.pcm16ToWav(audioData, SAMPLE_RATE)
            transcribeWav(wav)
        } catch (_: Exception) {
            AsrResult(text = "", confidence = 0f, provider = "sarvam")
        }
    }

    private fun transcribeWav(wavBytes: ByteArray): AsrResult {
        val conn = URL(baseUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true

            val boundary = "Boundary_${java.util.UUID.randomUUID()}"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { it.write(buildMultipartBody(wavBytes, boundary)) }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val errorBody = readStream(conn.errorStream)
                handleError(responseCode, errorBody)
                return AsrResult(text = "", confidence = 0f, provider = "sarvam")
            }

            val responseBody = readStream(conn.inputStream)
            return parseResponse(responseBody)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Handle a non-2xx response. If the request was rejected as malformed
     * (anything on the 4xx client-error range except auth failures and rate
     * limits — i.e. the model name or request shape was at fault), log the error
     * body once and fall back to [fallbackModel] for subsequent attempts.
     * The body is redacted so it never leaks a credential-like token.
     */
    private fun handleError(code: Int, body: String) {
        val isRequestRejection = code in 400..499 &&
            code != 401 && code != 403 && code != 429
        if (isRequestRejection && !fallbackLogged) {
            // Redact any accidental credential-looking token before logging.
            val redacted = body.replace(Regex("[A-Za-z0-9_-]{24,}"), "[REDACTED]")
            Log.w(TAG, "Sarvam rejected request (HTTP $code): $redacted")
            fallbackLogged = true
        }
        if (isRequestRejection && effectiveModel != fallbackModel) {
            effectiveModel = fallbackModel
            Log.i(TAG, "Sarvam ASR falling back to model '$fallbackModel'")
        } else if (!isRequestRejection) {
            // Auth / rate-limit / server errors — not model-related; just note it.
            Log.w(TAG, "Sarvam ASR non-2xx (HTTP $code), no model fallback")
        }
    }

    /**
     * Build multipart form-data body. Sarvam's `/speech-to-text` expects the
     * audio as `file` and the model as `model`. The Saarika family responds with
     * JSON { transcript, language_code }.
     */
    private fun buildMultipartBody(wavBytes: ByteArray, boundary: String): ByteArray {
        val bos = ByteArrayOutputStream()
        val crlf = "\r\n"

        bos.write("--$boundary$crlf".toByteArray())
        bos.write("Content-Disposition: form-data; name=\"model\"$crlf$crlf".toByteArray())
        bos.write("$effectiveModel$crlf".toByteArray())

        // Language: let Sarvam auto-detect (unknown) — no forced locale.
        bos.write("--$boundary$crlf".toByteArray())
        bos.write("Content-Disposition: form-data; name=\"language_code\"$crlf$crlf".toByteArray())
        bos.write("unknown$crlf".toByteArray())

        // Audio file (16kHz mono WAV)
        bos.write("--$boundary$crlf".toByteArray())
        bos.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"$crlf".toByteArray())
        bos.write("Content-Type: audio/wav$crlf$crlf".toByteArray())
        bos.write(wavBytes)
        bos.write("$crlf".toByteArray())

        bos.write("--$boundary--$crlf".toByteArray())
        return bos.toByteArray()
    }

    /**
     * Parse Sarvam response JSON: { "transcript": "...", "language_code": "..." }.
     */
    private fun parseResponse(jsonStr: String): AsrResult {
        val json = JSONObject(jsonStr)
        val text = json.optString("transcript", "").trim()
        val language = if (json.has("language_code") && !json.isNull("language_code"))
            json.optString("language_code") else null
        return AsrResult(
            text = text,
            confidence = if (text.isNotBlank()) 0.8f else 0f,
            provider = "sarvam",
            language = language
        )
    }

    private fun readStream(stream: InputStream): String {
        val reader = BufferedReader(InputStreamReader(stream))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            sb.append(line)
        }
        return sb.toString()
    }
}
