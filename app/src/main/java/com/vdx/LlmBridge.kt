package com.vdx

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.vdx.memory.ContextHydrator
import com.vdx.memory.LlmCache
import com.vdx.memory.UserMemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * LlmBridge — AI intent extraction layer for VDX.
 *
 * Takes a raw speech transcript and returns a structured [VdxIntent] by calling a cloud LLM.
 * Handles filler words, course corrections ("uh message Ravi wait no call Ravi" → call Ravi),
 * and extracts action, contact, message body, destination, app name, and search query.
 *
 * Also auto-extracts memory-worthy facts from user speech (Veda pattern: knowledge as byproduct).
 * When the user says "Mom's number is +971****4567", Gemini returns it as a memory entry
 * and LlmBridge stores it in [UserMemoryStore] for future recall.
 *
 * Primary provider: **Gemini** via the official Google Gen AI Java SDK (com.google.genai).
 * Fallback providers: OpenRouter, OpenAI, Anthropic (raw HTTP).
 * Ultimate fallback: [IntentEngine.parse] (regex-based).
 *
 * Usage (from a background thread — this class does NOT auto-dispatch):
 * ```
 * val bridge = LlmBridge(context)
 * val intent = bridge.extract(transcript)  // synchronous, call off main thread
 * ```
 * Or use the async variant:
 * ```
 * bridge.extractAsync(transcript) { intent -> /* run on main thread */ }
 * ```
 */
class LlmBridge(private val context: Context) {

    companion object {
        private const val TAG = "LlmBridge"
        private const val PREFS_NAME = "vdx_prefs"
        private const val KEY_API_KEY = "vdx_llm_api_key"
        private const val KEY_PROVIDER = "vdx_llm_provider"
        private const val KEY_MODEL = "vdx_llm_model"
        private const val TIMEOUT_MS = 15_000

        /**
         * The system prompt sent to the LLM. Encodes VDX's persona, the JSON schema,
         * and few-shot examples covering clean intent, course correction, and ambiguous input.
         * Also extracts memory-worthy facts from user speech (Veda pattern: knowledge as byproduct).
         */
        val SYSTEM_PROMPT: String = """
You are VDX, a voice action assistant for blind users. Extract the user's intent from their speech.
Return JSON with: action (one of: call, whatsapp, sms, uber, youtube, email, app_launch, read_screen, unknown), contact (name if mentioned), message (text to send if applicable), destination (for uber), app_name (for app_launch), search_query (for youtube), confidence (0-1).
If confidence < 0.7, include a clarification_question field.

Also extract any memory-worthy facts the user states. These are facts the user tells you that should be remembered for future use. Include a "memories" array in the JSON, each with: type (contact|preference|location|fact|habit|relationship|default), key (normalized lookup key like "mom", "home", "default_app"), value (the stored value), context (optional context like "WhatsApp contact").
Examples of memory-worthy facts:
- "Mom's number is +971****4567" → {type: contact, key: mom, value: +971****4567, context: phone number}
- "I live in Dubai Marina" → {type: location, key: home, value: Dubai Marina}
- "Ravi is my colleague" → {type: relationship, key: ravi, value: colleague}
- "Always use WhatsApp for Ravi" → {type: default, key: ravi, value: whatsapp, context: preferred app}
- "I call Mom every evening" → {type: habit, key: mom, value: call every evening}

Examples: "uh message Ravi on WhatsApp that I'll be 10 minutes late" → {action: whatsapp, contact: Ravi, message: I'll be 10 minutes late, confidence: 0.95, memories: []}. "call Mom" → {action: call, contact: Mom, confidence: 0.99, memories: []}. "book me an Uber home" → {action: uber, destination: home, confidence: 0.9, memories: []}. "Mom's number is +971****4567" → {action: unknown, confidence: 0.3, clarification_question: "What would you like me to do?", memories: [{type: contact, key: mom, value: +971****4567, context: phone number}]}. If unclear: "did something" → {action: unknown, confidence: 0.3, clarification_question: "What would you like me to do?", memories: []}.
        """.trimIndent()
    }

    enum class LlmProvider(val displayName: String, val defaultModel: String) {
        GEMINI("Gemini", "gemini-3.6-flash"),
        OPENROUTER("OpenRouter", "anthropic/claude-3.5-sonnet"),
        OPENAI("OpenAI", "gpt-4o-mini"),
        ANTHROPIC("Anthropic", "claude-3-5-sonnet-20241022"),
    }

    private val memoryStore = UserMemoryStore(context)
    private val memoryScope = CoroutineScope(Dispatchers.IO)
    private val contextHydrator = ContextHydrator(context)
    private val llmCache = LlmCache(maxSize = 50)

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Synchronously extract intent from [transcript].
     * MUST be called from a background thread.
     * Falls back to [IntentEngine.parse] on any error.
     *
     * Pipeline:
     * 1. Check LlmCache — if same transcript was processed recently, return cached result
     * 2. Hydrate context — resolve known entities from Room DB (contacts, locations, defaults)
     * 3. Call LLM with hydrated prompt
     * 4. Cache the result
     * 5. Extract and store any memory-worthy facts
     */
    fun extract(transcript: String): VdxIntent {
        if (!isLlmAvailable()) {
            return IntentEngine.parse(transcript)
        }

        // Step 1: Check cache
        val cached = llmCache.get(transcript)
        if (cached != null) {
            return parseLlmResponse(cached, transcript)
        }

        return try {
            // Step 2: Hydrate context from stored memories
            val hydration = runBlocking { contextHydrator.hydrate(transcript) }
            val prompt = hydration.hydratedPrompt

            // Step 3: Call LLM with hydrated prompt
            val rawJson = callLlm(prompt)

            // Step 4: Cache the result
            llmCache.put(transcript, rawJson)

            // Step 5: Parse and extract memories
            parseLlmResponse(rawJson, transcript)
        } catch (e: Exception) {
            // LLM failed — fall back to regex
            IntentEngine.parse(transcript)
        }
    }

    /**
     * Asynchronously extract intent, invoking [callback] on the main thread.
     * Also auto-extracts and stores any memory-worthy facts from the response.
     */
    fun extractAsync(transcript: String, callback: (VdxIntent) -> Unit) {
        thread {
            val result = extract(transcript)
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(result) }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Configuration (SharedPreferences)
    // ──────────────────────────────────────────────────────────────────────

    fun setApiKey(key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_API_KEY, key).apply()
    }

    fun getApiKey(): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, null)

    fun setProvider(provider: LlmProvider) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROVIDER, provider.name).apply()
    }

    fun getProvider(): LlmProvider {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROVIDER, null)
        return LlmProvider.entries.firstOrNull { it.name == name } ?: LlmProvider.GEMINI
    }

    fun setModel(model: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODEL, model).apply()
    }

    fun getModel(): String {
        val provider = getProvider()
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, null) ?: provider.defaultModel
    }

    // ──────────────────────────────────────────────────────────────────────
    // Availability checks
    // ──────────────────────────────────────────────────────────────────────

    fun isLlmAvailable(): Boolean {
        val key = getApiKey()
        if (key.isNullOrBlank()) return false
        return isNetworkAvailable()
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ──────────────────────────────────────────────────────────────────────
    // LLM calls
    // ──────────────────────────────────────────────────────────────────────

    @Throws(IOException::class)
    private fun callLlm(transcript: String): String {
        val provider = getProvider()
        val apiKey = getApiKey() ?: throw IOException("No API key")
        val model = getModel()

        return when (provider) {
            LlmProvider.GEMINI -> callGeminiSdk(apiKey, model, transcript)
            LlmProvider.OPENROUTER -> callOpenRouter(apiKey, model, transcript)
            LlmProvider.OPENAI -> callOpenAI(apiKey, model, transcript)
            LlmProvider.ANTHROPIC -> callAnthropic(apiKey, model, transcript)
        }
    }

    /**
     * Call Gemini via the official Google Gen AI Java SDK.
     * Supports both Gemini Developer API (apiKey) and Vertex AI (future).
     */
    @Throws(IOException::class)
    private fun callGeminiSdk(apiKey: String, model: String, transcript: String): String {
        val client = Client.builder()
            .apiKey(apiKey)
            .build()

        val config = GenerateContentConfig.builder()
            .systemInstruction(Content.builder()
                .role("system")
                .parts(listOf(Part.builder().text(SYSTEM_PROMPT).build()))
                .build())
            .temperature(0.1f)
            .maxOutputTokens(300)
            .build()

        val response = client.models.generateContent(model, transcript, config)

        val text = response.text()
            ?: throw IOException("Gemini returned empty response")

        return text
    }

    @Throws(IOException::class)
    private fun callOpenRouter(apiKey: String, model: String, transcript: String): String {
        val url = URL("https://openrouter.ai/api/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("HTTP-Referer", "https://vdx.app")
            conn.setRequestProperty("X-Title", "VDX")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true

            val body: String = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    put(JSONObject().put("role", "user").put("content", transcript))
                })
                put("temperature", 0.1)
                put("max_tokens", 300)
            }.toString()

            conn.outputStream.use { os -> OutputStreamWriter(os).use { w -> w.write(body) } }

            val response = readResponse(conn)
            val json = JSONObject(response)
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } finally {
            conn.disconnect()
        }
    }

    @Throws(IOException::class)
    private fun callOpenAI(apiKey: String, model: String, transcript: String): String {
        val url = URL("https://api.openai.com/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true

            val body: String = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    put(JSONObject().put("role", "user").put("content", transcript))
                })
                put("temperature", 0.1)
                put("max_tokens", 300)
            }.toString()

            conn.outputStream.use { os -> OutputStreamWriter(os).use { w -> w.write(body) } }

            val response = readResponse(conn)
            val json = JSONObject(response)
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } finally {
            conn.disconnect()
        }
    }

    @Throws(IOException::class)
    private fun callAnthropic(apiKey: String, model: String, transcript: String): String {
        val url = URL("https://api.anthropic.com/v1/messages")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true

            val body: String = JSONObject().apply {
                put("model", model)
                put("max_tokens", 300)
                put("system", SYSTEM_PROMPT)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "user").put("content", transcript))
                })
            }.toString()

            conn.outputStream.use { os -> OutputStreamWriter(os).use { w -> w.write(body) } }

            val response = readResponse(conn)
            val json = JSONObject(response)
            return json.getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
        } finally {
            conn.disconnect()
        }
    }

    @Throws(IOException::class)
    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(stream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
        }
        if (code !in 200..299) {
            throw IOException("HTTP $code: ${sb.toString().take(200)}")
        }
        return sb.toString()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Response parsing — LLM JSON → VdxIntent + Memory extraction
    // ──────────────────────────────────────────────────────────────────────

    private fun parseLlmResponse(rawContent: String, originalTranscript: String): VdxIntent {
        // Extract JSON object from the response (LLMs sometimes wrap in markdown ```json ... ```)
        val jsonStr = extractJson(rawContent)
        val json = JSONObject(jsonStr)

        val action = json.optString("action", "unknown").lowercase().trim()
        val confidence = json.optDouble("confidence", 0.5)
        val contact = json.optString("contact", "").trim()
        val message = json.optString("message", "").trim()
        val destination = json.optString("destination", "").trim()
        val appName = json.optString("app_name", "").trim()
        val searchQuery = json.optString("search_query", "").trim()
        val clarificationQuestion = json.optString("clarification_question", "").trim()

        // Extract and store memory-worthy facts (Veda pattern: knowledge as byproduct)
        extractAndStoreMemories(json)

        // If confidence is low and there's a clarification question, return Clarification
        if (confidence < 0.7 && clarificationQuestion.isNotBlank()) {
            return VdxIntent.Clarification(action, clarificationQuestion)
        }

        return when (action) {
            "call" -> VdxIntent.Call(contact.ifBlank { "unknown" })
            "whatsapp" -> VdxIntent.WhatsApp(contact.ifBlank { "unknown" }, message)
            "sms" -> VdxIntent.Sms(contact.ifBlank { "unknown" }, message)
            "uber" -> VdxIntent.Uber(destination.ifBlank { "unknown" })
            "youtube" -> VdxIntent.YouTube(searchQuery.ifBlank { originalTranscript })
            "email" -> VdxIntent.Email(contact.ifBlank { "unknown" }, message)
            "app_launch" -> VdxIntent.AppLaunch(appName.ifBlank { originalTranscript })
            "read_screen" -> VdxIntent.ReadScreen
            "unknown" -> VdxIntent.Unknown(originalTranscript)
            else -> VdxIntent.Unknown(originalTranscript)
        }
    }

    /**
     * Extract memory-worthy facts from the LLM response and store them persistently.
     * Veda pattern: knowledge flows in as a byproduct of doing work.
     */
    private fun extractAndStoreMemories(json: JSONObject) {
        val memories = json.optJSONArray("memories") ?: return
        for (i in 0 until memories.length()) {
            val mem = memories.optJSONObject(i) ?: continue
            val type = mem.optString("type", "").trim()
            val key = mem.optString("key", "").trim()
            val value = mem.optString("value", "").trim()
            val context = mem.optString("context", "").trim()
            if (type.isNotBlank() && key.isNotBlank() && value.isNotBlank()) {
                memoryStore.remember(type, key, value, context)
            }
        }
    }

    /**
     * Extracts the first JSON object from [raw], handling markdown code fences
     * and surrounding prose.
     */
    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()

        // Strip markdown code fences ```json ... ``` or ``` ... ```
        val fenceRegex = Regex("(?s)```(?:json)?\\s*(\\{.*?})\\s*```")
        val fenceMatch = fenceRegex.find(trimmed)
        if (fenceMatch != null) return fenceMatch.groupValues[1]

        // Fallback: find first { ... } pair
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1)
        }

        // Last resort: assume the whole thing is JSON
        return trimmed
    }
}
