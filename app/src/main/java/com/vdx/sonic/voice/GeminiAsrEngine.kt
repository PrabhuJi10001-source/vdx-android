package com.vdx.sonic.voice

import android.content.Context
import com.google.genai.Client
import com.google.genai.types.Blob
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.vdx.sonic.AsrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gemini multimodal ASR fallback (spec Layer 3 legacy path).
 * Returns transcript only — intent parsing stays in IntentParser.
 */
class GeminiAsrEngine(
    private val context: Context,
    private val apiKey: String,
    private val model: String = "gemini-2.0-flash"
) {
    suspend fun transcribe(audioData: ShortArray): AsrResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || audioData.isEmpty()) {
            return@withContext AsrResult(text = "", confidence = 0f, provider = "gemini")
        }
        try {
            val wavBytes = WavUtil.pcm16ToWav(audioData)
            val client = Client.builder().apiKey(apiKey).build()
            val audioPart = Part.builder()
                .inlineData(Blob.builder().mimeType("audio/wav").data(wavBytes).build())
                .build()
            val textPart = Part.builder()
                .text("Transcribe this audio exactly. Return only the spoken words, nothing else.")
                .build()
            val response = client.models.generateContent(
                model,
                listOf(
                    Content.builder()
                        .role("user")
                        .parts(listOf(audioPart, textPart))
                        .build()
                ),
                GenerateContentConfig.builder().temperature(0.1f).maxOutputTokens(256).build()
            )
            val text = response.text()?.trim().orEmpty()
            AsrResult(
                text = text,
                confidence = if (text.isNotBlank()) 0.8f else 0f,
                provider = "gemini"
            )
        } catch (e: Exception) {
            AsrResult(text = "", confidence = 0f, provider = "gemini")
        }
    }
}
