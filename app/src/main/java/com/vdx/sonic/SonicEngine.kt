package com.vdx.sonic

import android.content.Context
import android.speech.tts.TextToSpeech
import com.vdx.sonic.voice.GroqAsrEngine
import com.vdx.sonic.voice.CleanupEngine
import com.vdx.sonic.clarify.ClarificationEngine
import com.vdx.sonic.diag.DiagnosticsEngine
import com.vdx.sonic.harness.Harness
import com.vdx.sonic.plan.Planner
import com.vdx.sonic.voice.EntityRepairEngine
import com.vdx.sonic.voice.IntentParser
import kotlinx.coroutines.*
import java.util.Locale

/**
 * SonicEngine — top-level orchestrator for VDX Sonic.
 *
 * Ties together all 10 layers:
 * 1. Bubble (external — BubbleForegroundService)
 * 2. Mic Capture (external — MicCaptureManager)
 * 3. Voice Understanding (ASR → Cleanup → Entity Repair → Intent)
 * 4. Entity Repair
 * 5. Intent Parser
 * 6. Planner
 * 7. Harness
 * 8. RobotHand (external — existing RobotHand)
 * 9. Clarification + Confirmation
 * 10. Diagnostics
 *
 * Usage:
 *   SonicEngine(context).process(audioData, metadata)
 */
class SonicEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        private const val TAG = "SonicEngine"
    }

    // Sub-engines (lazily initialized)
    val diagnostics: DiagnosticsEngine by lazy { DiagnosticsEngine(context) }
    val harness: Harness by lazy { Harness() }
    val entityRepair: EntityRepairEngine by lazy { EntityRepairEngine(context) }
    val intentParser: IntentParser by lazy { IntentParser() }
    val planner: Planner by lazy { Planner() }
    val clarification: ClarificationEngine by lazy { ClarificationEngine() }

    private var tts: TextToSpeech? = null
    private var currentJob: Job? = null

    // Callback interface for the bubble service
    var onStateChange: ((BubbleState) -> Unit)? = null
    var onPartialTranscript: ((String) -> Unit)? = null
    var onClarification: ((ClarificationRequest) -> Unit)? = null
    var onResult: ((ExecutionResult) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Process a captured audio session through the full pipeline.
     *
     * Pipeline:
     *   Capture → ASR → Cleanup → Entity Repair → Intent Parse
     *   → Clarify? → Plan → Execute
     */
    fun process(capture: CaptureSession) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                onStateChange?.invoke(BubbleState.PROCESSING)

                // Step 1: ASR (transcribe audio to text)
                val asrResult = transcribe(capture)
                if (asrResult.confidence < 0.3f) {
                    onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                    onClarification?.invoke(
                        ClarificationRequest(
                            id = java.util.UUID.randomUUID().toString(),
                            question = "I didn't catch that. Could you say it again?",
                            type = com.vdx.sonic.ClarificationType.AMBIGUOUS_INTENT
                        )
                    )
                    return@launch
                }

                onPartialTranscript?.invoke(asrResult.text)

                // Step 2: Cleanup (post-processing LLM)
                val cleanedText = cleanup(asrResult.text)

                // Step 3: Entity Repair
                val screenModel = harness.readScreen(getAccessibilityService())
                val repairResult = entityRepair.repair(
                    transcript = cleanedText,
                    screenModel = screenModel,
                    vocabulary = getVocabulary(),
                    contacts = getContacts()
                )

                // Step 4: Intent Parse
                val intent = intentParser.parse(repairResult.repairedText)

                // Step 5: Clarification check
                val clarificationRequest = clarification.evaluate(intent, repairResult)
                if (clarificationRequest != null) {
                    onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                    onClarification?.invoke(clarificationRequest)
                    return@launch
                }

                // Step 6: Plan
                val plan = planner.plan(intent, screenModel, harness)

                // Step 7: Execute
                onStateChange?.invoke(BubbleState.EXECUTING)
                val result = execute(plan)

                onStateChange?.invoke(if (result is ExecutionResult.Success) BubbleState.DONE else BubbleState.ERROR)
                onResult?.invoke(result)

            } catch (e: Exception) {
                onStateChange?.invoke(BubbleState.ERROR)
                onError?.invoke(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Handle user's response to a clarification request.
     */
    fun handleClarificationResponse(request: ClarificationRequest, response: String) {
        val updatedIntent = clarification.handleResponse(request, response, request.context ?: return)
        if (updatedIntent.type == IntentType.UNKNOWN) {
            onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
            onClarification?.invoke(
                ClarificationRequest(
                    id = java.util.UUID.randomUUID().toString(),
                    question = updatedIntent.clarificationQuestion ?: "What would you like me to do?",
                    type = com.vdx.sonic.ClarificationType.AMBIGUOUS_INTENT
                )
            )
            return
        }

        // Re-plan and execute with updated intent
        scope.launch {
            val screenModel = harness.readScreen(getAccessibilityService())
            val plan = planner.plan(updatedIntent, screenModel, harness)
            onStateChange?.invoke(BubbleState.EXECUTING)
            val result = execute(plan)
            onStateChange?.invoke(if (result is ExecutionResult.Success) BubbleState.DONE else BubbleState.ERROR)
            onResult?.invoke(result)
        }
    }

    /**
     * Speak a message via TTS.
     */
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

    /**
     * Clean up resources.
     */
    fun destroy() {
        currentJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // ──────────────────────────────────────────────────────────────
    // Pipeline steps (stubs — replace with real implementations)
    // ──────────────────────────────────────────────────────────────

    private var groqAsr: GroqAsrEngine? = null
    private var cleanupEngine: CleanupEngine? = null

    /**
     * Configure ASR provider.
     */
    fun configureAsr(apiKey: String, model: String = "whisper-large-v3-turbo") {
        groqAsr = GroqAsrEngine(apiKey, model)
    }

    /**
     * Configure cleanup LLM provider.
     */
    fun configureCleanup(
        apiKey: String = "",
        baseUrl: String = "http://localhost:11434",
        model: String = "qwen2.5:7b",
        provider: String = "ollama"
    ) {
        cleanupEngine = CleanupEngine(apiKey, baseUrl, model, provider)
    }

    private suspend fun transcribe(capture: CaptureSession): AsrResult {
        val asr = groqAsr
        if (asr != null) {
            return asr.transcribe(capture.audioData)
        }
        // No ASR configured — return empty
        return AsrResult(text = "", confidence = 0.0f, provider = "none")
    }

    private suspend fun cleanup(text: String): String {
        val engine = cleanupEngine
        if (engine != null && text.isNotBlank()) {
            val result = engine.cleanup(text)
            return result.cleanedText
        }
        return text
    }

    private suspend fun execute(plan: ExecutionPlan): ExecutionResult {
        // TODO: Replace with actual RobotHand execution
        return ExecutionResult.Success("Plan ready: ${plan.steps.size} steps", 0)
    }

    private fun getAccessibilityService(): android.accessibilityservice.AccessibilityService? {
        return try {
            val cls = Class.forName("com.vdx.VdxAccessibilityService")
            val field = cls.getDeclaredField("instance")
            field.isAccessible = true
            field.get(null) as? android.accessibilityservice.AccessibilityService
        } catch (e: Exception) { null }
    }

    private suspend fun getVocabulary(): List<String>? = null
    private suspend fun getContacts(): List<String>? = null
}
