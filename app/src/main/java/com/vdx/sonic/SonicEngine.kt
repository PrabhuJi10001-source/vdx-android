package com.vdx.sonic

import android.content.Context
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.util.Log
import com.vdx.memory.MemoryStore
import com.vdx.sonic.clarify.ClarificationEngine
import com.vdx.sonic.diag.DiagnosticsEngine
import com.vdx.sonic.harness.Harness
import com.vdx.sonic.plan.Planner
import com.vdx.sonic.robot.RobotHand as SonicRobotHand
import com.vdx.sonic.voice.CleanupEngine
import com.vdx.sonic.voice.EntityRepairEngine
import com.vdx.sonic.voice.GeminiAsrEngine
import com.vdx.sonic.voice.GroqAsrEngine
import com.vdx.sonic.voice.IntentParser
import com.vdx.sonic.voice.LocalCleanupEngine
import com.vdx.sonic.voice.OpenAiAsrEngine
import kotlinx.coroutines.*
import java.util.Locale

/**
 * SonicEngine — live orchestrator for the 10-layer VDX Sonic pipeline.
 *
 * Bubble → MicCapture → ASR → Cleanup → EntityRepair → IntentParse
 * → Clarify? → Plan (adapters) → Sonic RobotHand → Result
 */
class SonicEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        private const val TAG = "SonicEngine"
        private const val PREFS = "vdx_prefs"
    }

    val diagnostics: DiagnosticsEngine by lazy { DiagnosticsEngine(context) }
    val harness: Harness by lazy { Harness() }
    val entityRepair: EntityRepairEngine by lazy { EntityRepairEngine(context) }
    val intentParser: IntentParser by lazy { IntentParser() }
    val planner: Planner by lazy { Planner(context) }
    val clarification: ClarificationEngine by lazy { ClarificationEngine() }
    private val sonicRobot: SonicRobotHand by lazy { SonicRobotHand(context, harness) }
    private val memoryStore: MemoryStore by lazy { MemoryStore(context) }

    private var tts: TextToSpeech? = null
    private var currentJob: Job? = null

    var onStateChange: ((BubbleState) -> Unit)? = null
    var onPartialTranscript: ((String) -> Unit)? = null
    var onClarification: ((ClarificationRequest) -> Unit)? = null
    var onResult: ((ExecutionResult) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var groqAsr: GroqAsrEngine? = null
    private var openAiAsr: OpenAiAsrEngine? = null
    private var geminiAsr: GeminiAsrEngine? = null
    private var cleanupEngine: CleanupEngine? = null

    init {
        autoConfigureFromPrefs()
    }

    fun autoConfigureFromPrefs() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val groqKey = prefs.getString("vdx_groq_api_key", null)
            ?: prefs.getString("groq_api_key", null)
        val openAiKey = prefs.getString("vdx_openai_api_key", null)
            ?: prefs.getString("openai_api_key", null)
        val geminiKey = prefs.getString("vdx_llm_api_key", null)
        val geminiModel = prefs.getString("vdx_llm_model", null) ?: "gemini-2.0-flash"

        if (!groqKey.isNullOrBlank()) groqAsr = GroqAsrEngine(groqKey)
        if (!openAiKey.isNullOrBlank()) openAiAsr = OpenAiAsrEngine(openAiKey)
        if (!geminiKey.isNullOrBlank()) geminiAsr = GeminiAsrEngine(context, geminiKey, geminiModel)

        cleanupEngine = CleanupEngine(
            apiKey = geminiKey.orEmpty().ifBlank { groqKey.orEmpty() },
            baseUrl = prefs.getString("vdx_cleanup_base_url", "http://localhost:11434") ?: "http://localhost:11434",
            model = prefs.getString("vdx_cleanup_model", "qwen2.5:7b") ?: "qwen2.5:7b",
            provider = prefs.getString("vdx_cleanup_provider", "local") ?: "local"
        )
    }

    fun configureAsr(apiKey: String, model: String = "whisper-large-v3-turbo") {
        groqAsr = GroqAsrEngine(apiKey, model)
    }

    fun configureCleanup(
        apiKey: String = "",
        baseUrl: String = "http://localhost:11434",
        model: String = "qwen2.5:7b",
        provider: String = "ollama"
    ) {
        cleanupEngine = CleanupEngine(apiKey, baseUrl, model, provider)
    }

    fun process(capture: CaptureSession) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                if (!diagnostics.isReady()) {
                    val blockers = diagnostics.getBlockers()
                    onStateChange?.invoke(BubbleState.BLOCKED_PERMISSION)
                    onError?.invoke(blockers.firstOrNull() ?: "System not ready")
                    return@launch
                }

                onStateChange?.invoke(BubbleState.PROCESSING)

                if (capture.audioData.isEmpty()) {
                    onStateChange?.invoke(BubbleState.ERROR)
                    onError?.invoke("No audio captured. Tap the bubble, speak, then tap again.")
                    return@launch
                }

                val asrResult = transcribe(capture)
                if (asrResult.text.isBlank() || asrResult.confidence < 0.3f) {
                    onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                    onClarification?.invoke(
                        ClarificationRequest(
                            id = java.util.UUID.randomUUID().toString(),
                            question = "I didn't catch that. Could you say it again?",
                            type = ClarificationType.AMBIGUOUS_INTENT
                        )
                    )
                    return@launch
                }

                onPartialTranscript?.invoke(asrResult.text)

                val cleanedText = cleanup(asrResult.text)
                val screenModel = harness.readScreen(getAccessibilityService())
                val repairResult = entityRepair.repair(
                    transcript = cleanedText,
                    screenModel = screenModel,
                    vocabulary = getVocabulary(),
                    contacts = getContacts()
                )

                val intent = intentParser.parse(repairResult.repairedText)
                val clarificationRequest = clarification.evaluate(intent, repairResult)
                if (clarificationRequest != null) {
                    onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                    onClarification?.invoke(clarificationRequest)
                    return@launch
                }

                val plan = planner.plan(intent, screenModel, harness)
                onStateChange?.invoke(BubbleState.EXECUTING)
                val result = sonicRobot.execute(plan)

                // Record episode for V2 memory
                memoryStore.recordEpisode(
                    goal = intent.rawText,
                    action = intent.type.name.lowercase(),
                    target = intent.entities.values.firstOrNull().orEmpty(),
                    outcome = if (result is ExecutionResult.Success) "success" else "failure",
                    errorDetail = (result as? ExecutionResult.Failed)?.reason.orEmpty()
                )

                when (result) {
                    is ExecutionResult.ClarificationNeeded -> {
                        onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                        onClarification?.invoke(
                            ClarificationRequest(
                                id = java.util.UUID.randomUUID().toString(),
                                question = result.question,
                                type = ClarificationType.AMBIGUOUS_INTENT,
                                context = result.context
                            )
                        )
                    }
                    is ExecutionResult.ConfirmationNeeded -> {
                        onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                        onClarification?.invoke(
                            ClarificationRequest(
                                id = java.util.UUID.randomUUID().toString(),
                                question = result.prompt,
                                type = ClarificationType.ACTION_CONFIRMATION,
                                context = plan.intent
                            )
                        )
                    }
                    else -> {
                        onStateChange?.invoke(
                            if (result is ExecutionResult.Success) BubbleState.DONE else BubbleState.ERROR
                        )
                        onResult?.invoke(result)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "process failed", e)
                onStateChange?.invoke(BubbleState.ERROR)
                onError?.invoke(e.message ?: "Unknown error")
            }
        }
    }

    /** Process typed/transcript text through cleanup → repair → plan → execute (no ASR). */
    fun processText(text: String) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                onStateChange?.invoke(BubbleState.PROCESSING)
                val cleaned = cleanup(text)
                val screenModel = harness.readScreen(getAccessibilityService())
                val repairResult = entityRepair.repair(
                    transcript = cleaned,
                    screenModel = screenModel,
                    vocabulary = getVocabulary(),
                    contacts = getContacts()
                )
                val intent = intentParser.parse(repairResult.repairedText)
                val clarificationRequest = clarification.evaluate(intent, repairResult)
                if (clarificationRequest != null) {
                    onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                    onClarification?.invoke(clarificationRequest)
                    return@launch
                }
                val plan = planner.plan(intent, screenModel, harness)
                onStateChange?.invoke(BubbleState.EXECUTING)
                val result = sonicRobot.execute(plan)
                onStateChange?.invoke(
                    if (result is ExecutionResult.Success) BubbleState.DONE else BubbleState.ERROR
                )
                onResult?.invoke(result)
            } catch (e: Exception) {
                onStateChange?.invoke(BubbleState.ERROR)
                onError?.invoke(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Process text from Google SpeechRecognizer through the full Sonic pipeline
     * with ASR confidence gating, diagnostics, cleanup, entity repair, intent parse,
     * plan, execute, and memory logging — same path as raw-audio process().
     */
    fun processFromText(text: String, asrResult: AsrResult) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                if (!diagnostics.isReady()) {
                    val blockers = diagnostics.getBlockers()
                    onStateChange?.invoke(BubbleState.BLOCKED_PERMISSION)
                    onError?.invoke(blockers.firstOrNull() ?: "System not ready")
                    return@launch
                }

                onStateChange?.invoke(BubbleState.PROCESSING)

                if (asrResult.text.isBlank() || asrResult.confidence < 0.3f) {
                    onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                    onClarification?.invoke(
                        ClarificationRequest(
                            id = java.util.UUID.randomUUID().toString(),
                            question = "I didn't catch that. Could you say it again?",
                            type = ClarificationType.AMBIGUOUS_INTENT
                        )
                    )
                    return@launch
                }

                onPartialTranscript?.invoke(asrResult.text)

                val cleanedText = cleanup(text)
                val screenModel = harness.readScreen(getAccessibilityService())
                val repairResult = entityRepair.repair(
                    transcript = cleanedText,
                    screenModel = screenModel,
                    vocabulary = getVocabulary(),
                    contacts = getContacts()
                )

                val intent = intentParser.parse(repairResult.repairedText)
                val clarificationRequest = clarification.evaluate(intent, repairResult)
                if (clarificationRequest != null) {
                    onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                    onClarification?.invoke(clarificationRequest)
                    return@launch
                }

                val plan = planner.plan(intent, screenModel, harness)
                onStateChange?.invoke(BubbleState.EXECUTING)
                val result = sonicRobot.execute(plan)

                // Record episode for V2 memory
                memoryStore.recordEpisode(
                    goal = intent.rawText,
                    action = intent.type.name.lowercase(),
                    target = intent.entities.values.firstOrNull().orEmpty(),
                    outcome = if (result is ExecutionResult.Success) "success" else "failure",
                    errorDetail = (result as? ExecutionResult.Failed)?.reason.orEmpty()
                )

                when (result) {
                    is ExecutionResult.ClarificationNeeded -> {
                        onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                        onClarification?.invoke(
                            ClarificationRequest(
                                id = java.util.UUID.randomUUID().toString(),
                                question = result.question,
                                type = ClarificationType.AMBIGUOUS_INTENT,
                                context = result.context
                            )
                        )
                    }
                    is ExecutionResult.ConfirmationNeeded -> {
                        onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                        onClarification?.invoke(
                            ClarificationRequest(
                                id = java.util.UUID.randomUUID().toString(),
                                question = result.prompt,
                                type = ClarificationType.ACTION_CONFIRMATION,
                                context = plan.intent
                            )
                        )
                    }
                    else -> {
                        onStateChange?.invoke(
                            if (result is ExecutionResult.Success) BubbleState.DONE else BubbleState.ERROR
                        )
                        onResult?.invoke(result)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "processFromText failed", e)
                onStateChange?.invoke(BubbleState.ERROR)
                onError?.invoke(e.message ?: "Unknown error")
            }
        }
    }

    fun handleClarificationResponse(request: ClarificationRequest, response: String) {
        val base = request.context ?: return
        val updatedIntent = clarification.handleResponse(request, response, base)
        if (updatedIntent.type == IntentType.UNKNOWN) {
            onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
            onClarification?.invoke(
                ClarificationRequest(
                    id = java.util.UUID.randomUUID().toString(),
                    question = updatedIntent.clarificationQuestion ?: "What would you like me to do?",
                    type = ClarificationType.AMBIGUOUS_INTENT
                )
            )
            return
        }
        scope.launch {
            val screenModel = harness.readScreen(getAccessibilityService())
            val plan = planner.plan(updatedIntent, screenModel, harness)
            onStateChange?.invoke(BubbleState.EXECUTING)
            val result = sonicRobot.execute(plan)
            onStateChange?.invoke(
                if (result is ExecutionResult.Success) BubbleState.DONE else BubbleState.ERROR
            )
            onResult?.invoke(result)
        }
    }

    fun speak(text: String) {
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sonic_utterance")
                }
            }
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sonic_utterance")
        }
    }

    fun destroy() {
        currentJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private suspend fun transcribe(capture: CaptureSession): AsrResult {
        groqAsr?.transcribe(capture.audioData)?.takeIf { it.text.isNotBlank() }?.let { return it }
        openAiAsr?.transcribe(capture.audioData)?.takeIf { it.text.isNotBlank() }?.let { return it }
        geminiAsr?.transcribe(capture.audioData)?.takeIf { it.text.isNotBlank() }?.let { return it }
        Log.w(TAG, "No ASR provider configured or all failed")
        return AsrResult(text = "", confidence = 0.0f, provider = "none")
    }

    private suspend fun cleanup(text: String): String {
        // Always apply local cleanup (Louie-beating Wispr path, offline)
        val local = LocalCleanupEngine.clean(text)
        val engine = cleanupEngine
        if (engine != null && local.isNotBlank()) {
            return try {
                engine.cleanup(local).cleanedText.ifBlank { local }
            } catch (_: Exception) {
                local
            }
        }
        return local.ifBlank { text }
    }

    private fun getAccessibilityService(): android.accessibilityservice.AccessibilityService? {
        return com.vdx.VdxAccessibilityService.instance
    }

    private suspend fun getVocabulary(): List<String>? = withContext(Dispatchers.IO) {
        try {
            memoryStore.getVocabularyAliases()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getContacts(): List<String>? = withContext(Dispatchers.IO) {
        try {
            val names = mutableListOf<String>()
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                "${ContactsContract.Contacts.DISPLAY_NAME} IS NOT NULL",
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
            )
            cursor?.use {
                val idx = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                var n = 0
                while (it.moveToNext() && n < 500) {
                    if (idx >= 0) {
                        val name = it.getString(idx)?.trim().orEmpty()
                        if (name.length >= 2) {
                            names.add(name)
                            n++
                        }
                    }
                }
            }
            // Merge remembered contact names from memory graph
            memoryStore.getByType("contact").forEach { names.add(it.name) }
            names.distinct().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}
