package com.vdx.sonic.voice

import com.vdx.sonic.AsrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI Whisper ASR fallback (spec Layer 3).
 */
class OpenAiAsrEngine(
    private val apiKey: String,
    private val model: String = "whisper-1",
    private val baseUrl: String = "https://api.openai.com/v1"
) {
    companion object {
        private const val TIMEOUT_MS = 30_000
        private const val SAMPLE_RATE = 16000
    }

    suspend fun transcribe(audioData: ShortArray): AsrResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || audioData.isEmpty()) {
            return@withContext AsrResult(text = "", confidence = 0f, provider = "openai")
        }
        try {
            val wav = WavUtil.pcm16ToWav(audioData, SAMPLE_RATE)
            val url = URL("$baseUrl/audio/transcriptions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            val boundary = "Boundary_${System.currentTimeMillis()}"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val body = buildBody(wav, boundary)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream)).readText()
            if (code !in 200..299) {
                return@withContext AsrResult(text = "", confidence = 0f, provider = "openai")
            }
            val json = JSONObject(text)
            val transcript = json.optString("text", "").trim()
            AsrResult(
                text = transcript,
                confidence = if (transcript.isNotBlank()) 0.85f else 0f,
                provider = "openai"
            )
        } catch (e: Exception) {
            AsrResult(text = "", confidence = 0f, provider = "openai")
        }
    }

    private fun buildBody(wav: ByteArray, boundary: String): ByteArray {
        val crlf = "\r\n"
        val sb = StringBuilder()
        sb.append("--$boundary$crlf")
        sb.append("Content-Disposition: form-data; name=\"model\"$crlf$crlf")
        sb.append(model).append(crlf)
        sb.append("--$boundary$crlf")
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"$crlf")
        sb.append("Content-Type: audio/wav$crlf$crlf")
        val header = sb.toString().toByteArray()
        val footer = "$crlf--$boundary--$crlf".toByteArray()
        return header + wav + footer
    }
}
