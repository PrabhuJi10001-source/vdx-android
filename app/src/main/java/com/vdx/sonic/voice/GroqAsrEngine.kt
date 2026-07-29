package com.vdx.sonic.voice

import com.vdx.sonic.AsrResult
import com.vdx.sonic.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * GroqAsrEngine — cloud ASR via Groq Whisper API.
 *
 * Sends audio to Groq's Whisper Large v3 Turbo endpoint.
 * Fast (~500ms on 10s audio), high accuracy.
 */
class GroqAsrEngine(
    private val apiKey: String,
    private val model: String = "whisper-large-v3-turbo",
    private val baseUrl: String = "https://api.groq.com/openai/v1"
) {
    companion object {
        private const val TAG = "Sonic-GroqASR"
        private const val TIMEOUT_MS = 20_000
        private const val SAMPLE_RATE = 16000

        // Whisper hallucination phrases to filter
        private val HALLUCINATION_PHRASES = setOf(
            "thank you", "thank you for watching", "thank you very much",
            "thank you so much", "thanks for watching", "please subscribe",
            "like and subscribe", "subtitles by", "you"
        )
    }

    /**
     * Transcribe PCM16 audio data.
     * @param audioData Raw PCM16 mono 16kHz samples
     * @return ASR result with transcript and confidence
     */
    suspend fun transcribe(audioData: ShortArray): AsrResult = withContext(Dispatchers.IO) {
        val wavBytes = pcm16ToWav(audioData)
        val result = transcribeWav(wavBytes)
        result
    }

    /**
     * Transcribe a WAV file bytes.
     */
    private fun transcribeWav(wavBytes: ByteArray): AsrResult {
        val url = URL("$baseUrl/audio/transcriptions")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true

            val boundary = "Boundary_${java.util.UUID.randomUUID()}"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            val body = buildMultipartBody(wavBytes, boundary)
            conn.outputStream.use { it.write(body) }

            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) {
                readStream(conn.inputStream)
            } else {
                val error = readStream(conn.errorStream)
                return AsrResult(
                    text = "",
                    confidence = 0.0f,
                    provider = "groq",
                    language = null
                )
            }

            return parseResponse(responseBody)
        } catch (e: Exception) {
            return AsrResult(
                text = "",
                confidence = 0.0f,
                provider = "groq",
                language = null
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Build multipart form-data body for the Whisper API.
     */
    private fun buildMultipartBody(wavBytes: ByteArray, boundary: String): ByteArray {
        val bos = ByteArrayOutputStream()

        // Model parameter
        bos.write("--$boundary\r\n".toByteArray())
        bos.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n".toByteArray())
        bos.write("$model\r\n".toByteArray())

        // Response format
        bos.write("--$boundary\r\n".toByteArray())
        bos.write("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n".toByteArray())
        bos.write("verbose_json\r\n".toByteArray())

        // Audio file
        bos.write("--$boundary\r\n".toByteArray())
        bos.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n".toByteArray())
        bos.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
        bos.write(wavBytes)
        bos.write("\r\n".toByteArray())

        // End boundary
        bos.write("--$boundary--\r\n".toByteArray())

        return bos.toByteArray()
    }

    /**
     * Parse Groq Whisper response JSON.
     */
    private fun parseResponse(jsonStr: String): AsrResult {
        val json = JSONObject(jsonStr)
        val text = json.optString("text", "").trim()

        // Check for hallucination
        if (isHallucination(text, json)) {
            return AsrResult(text = "", confidence = 0.0f, provider = "groq")
        }

        // Extract segments for confidence
        val segments = json.optJSONArray("segments")
        var avgConfidence = 0.0f
        var segmentCount = 0
        val segmentList = mutableListOf<TranscriptSegment>()

        if (segments != null) {
            for (i in 0 until segments.length()) {
                val seg = segments.optJSONObject(i) ?: continue
                val segText = seg.optString("text", "").trim()
                val start = (seg.optDouble("start", 0.0) * 1000).toLong()
                val end = (seg.optDouble("end", 0.0) * 1000).toLong()
                val conf = seg.optDouble("confidence", 0.0).toFloat()

                if (segText.isNotBlank()) {
                    avgConfidence += conf
                    segmentCount++
                    segmentList.add(TranscriptSegment(segText, start, end, conf))
                }
            }
        }

        if (segmentCount > 0) {
            avgConfidence /= segmentCount
        }

        val language = json.optString("language", null)

        return AsrResult(
            text = text,
            segments = segmentList.ifEmpty { null },
            language = language,
            confidence = avgConfidence.coerceIn(0f, 1f),
            provider = "groq"
        )
    }

    /**
     * FreeFlow's hallucination filter — checks no_speech_prob from verbose JSON.
     * Whisper hallucinates common short phrases on silence/background noise.
     */
    private fun isHallucination(text: String, json: JSONObject): Boolean {
        val normalized = text.lowercase().trim()
            .trimEnd('.', '!', '?', ',')
        if (normalized !in HALLUCINATION_PHRASES) return false

        val segments = json.optJSONArray("segments")
        if (segments == null || segments.length() == 0) return false

        val firstSegment = segments.optJSONObject(0) ?: return false
        val noSpeechProb = firstSegment.optDouble("no_speech_prob", -1.0)
        if (noSpeechProb < 0) return false

        // Threshold: if no_speech_prob > 0.1, it's a hallucination
        return noSpeechProb >= 0.1
    }

    /**
     * Convert PCM16 short array to WAV format bytes.
     */
    private fun pcm16ToWav(samples: ShortArray): ByteArray {
        val byteRate = SAMPLE_RATE * 2 // 16-bit = 2 bytes per sample
        val dataSize = samples.size * 2
        val fileSize = 36 + dataSize

        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        // RIFF header
        dos.writeBytes("RIFF")
        dos.writeInt(Integer.reverseBytes(fileSize))
        dos.writeBytes("WAVE")

        // fmt chunk
        dos.writeBytes("fmt ")
        dos.writeInt(Integer.reverseBytes(16)) // chunk size
        dos.writeShort(Integer.reverseBytes(1)) // PCM format
        dos.writeShort(Integer.reverseBytes(1)) // mono
        dos.writeInt(Integer.reverseBytes(SAMPLE_RATE))
        dos.writeInt(Integer.reverseBytes(byteRate))
        dos.writeShort(Integer.reverseBytes(2)) // block align
        dos.writeShort(Integer.reverseBytes(16)) // bits per sample

        // data chunk
        dos.writeBytes("data")
        dos.writeInt(Integer.reverseBytes(dataSize))

        // Write PCM samples
        for (sample in samples) {
            dos.writeShort(Integer.reverseBytes(sample.toInt()))
        }

        return bos.toByteArray()
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
