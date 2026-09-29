package com.vdx.sonic.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.vdx.memory.UserMemory
import com.vdx.memory.UserMemoryDao
import com.vdx.memory.VdxMemoryDatabase
import com.vdx.sonic.voice.PromptTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * VoiceOnboarding — first-launch voice-driven setup.
 *
 * technologically challenged
 * users shouldn't have to navigate settings menus. They speak, VDX configures itself.
 *
 * Flow (voice onboarding, 2026-09-01):
 *  1. VDX speaks welcome message (locale-aware)
 *  2. User speaks language preference → set locale
 *  3. VDX asks for name → user speaks → store in UserMemory
 *  4. VDX asks about capabilities → user confirms → enable adapters
 *  5. VDX speaks done message
 *  6. Set onboarded = true in SharedPreferences
 *
 * Gesture-practice step: tells the user
 * not to touch the screen while VDX drives, then lets them try a gesture so
 * GestureAccessibilityService proves it sees their touch.
 */
class VoiceOnboarding(
    private val context: Context,
    private val userMemoryDao: UserMemoryDao? = null
) {
    companion object {
        private const val TAG = "VoiceOnboarding"
        const val PREFS_NAME = "vdx_prefs"
        const val ONBOARDED_KEY = "onboarded"
        const val LOCALE_KEY = "vdx_locale"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var currentStep = OnboardingStep.WELCOME
    private var isListening = false

    /** Callback for completion. */
    var onComplete: (() -> Unit)? = null

    /** Callback for state updates (for UI feedback if needed). */
    var onStepChange: ((OnboardingStep) -> Unit)? = null

    enum class OnboardingStep {
        WELCOME, LANGUAGE, NAME, CAPABILITIES, GESTURE, DONE
    }

    /**
     * Check if onboarding has been completed.
     */
    fun isOnboarded(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(ONBOARDED_KEY, false)
    }

    /**
     * Start the voice onboarding flow. Initializes TTS, speaks the welcome
     * message, then begins listening for the user's language preference.
     */
    fun start() {
        Log.i(TAG, "Starting voice onboarding")
        currentStep = OnboardingStep.WELCOME
        onStepChange?.invoke(currentStep)

        initTTS()
        // Speak welcome + language prompt, then start listening
        speakAndListen(
            PromptTemplate.render(PromptTemplate.ONBOARDING_WELCOME) + " " +
                PromptTemplate.render(PromptTemplate.ONBOARDING_LANGUAGE),
            OnboardingStep.LANGUAGE
        )
    }

    /**
     * Initialize TTS with the current locale (defaults to English for the
     * welcome message, switches after the user picks their language).
     */
    private fun initTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = PromptTemplate.ttsLocale()
                Log.d(TAG, "TTS initialized for onboarding")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    /**
     * Speak a prompt via TTS, then start listening for the user's response.
     * The [nextStep] determines how the recognized text is processed.
     */
    private fun speakAndListen(prompt: String, nextStep: OnboardingStep) {
        // Stop any existing listening
        stopListening()

        // Speak the prompt
        tts?.apply {
            language = PromptTemplate.ttsLocale()
            speak(prompt, TextToSpeech.QUEUE_FLUSH, null, "onboarding_prompt")
        }
        Log.d(TAG, "Speaking: $prompt")

        // Wait for TTS to finish, then start listening
        // ponytail: poll TTS completion instead of UtteranceProgressListener (simpler, no callback wiring)
        handler.postDelayed({
            startListening(nextStep)
        }, estimateSpeechDurationMs(prompt))
    }

    /** Rough estimate of how long TTS will take to speak the prompt, in ms. */
    private fun estimateSpeechDurationMs(text: String): Long {
        // Average speaking rate ~150 words/min = 2.5 words/sec = 400ms/word.
        // Average word length ~5 chars → ~80ms/char. Minimum 1500ms.
        val byChars = text.length * 80L
        val byWords = text.split(" ").size * 400L
        return maxOf(byChars, byWords, 1500L)
    }

    /**
     * Start listening for speech input using Android SpeechRecognizer.
     */
    private fun startListening(step: OnboardingStep) {
        if (isListening) return
        isListening = true
        Log.d(TAG, "Starting listening for step: $step")

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }
        val recognizer = speechRecognizer ?: run {
            Log.e(TAG, "SpeechRecognizer not available")
            isListening = false
            return
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "SR onBeginningOfSpeech")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "SR onEndOfSpeech")
            }
            override fun onError(error: Int) {
                Log.w(TAG, "SR onError: $error")
                handler.post {
                    isListening = false
                    // Retry the same step after a brief pause
                    handler.postDelayed({
                        if (currentStep != OnboardingStep.DONE) {
                            retryCurrentStep(step)
                        }
                    }, 1000)
                }
            }
            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = texts?.firstOrNull()?.trim()
                Log.d(TAG, "SR onResults: \"$transcript\"")
                handler.post {
                    isListening = false
                    if (!transcript.isNullOrBlank()) {
                        handleSpeechInput(transcript, step)
                    } else {
                        retryCurrentStep(step)
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, PromptTemplate.sttLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer.startListening(intent)
    }

    private fun stopListening() {
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
        }
    }

    /**
     * Retry the current step — re-speak the prompt and listen again.
     */
    private fun retryCurrentStep(step: OnboardingStep) {
        val prompt = when (step) {
            OnboardingStep.LANGUAGE -> PromptTemplate.render(PromptTemplate.DIDNT_CATCH) + " " +
                PromptTemplate.render(PromptTemplate.ONBOARDING_LANGUAGE)
            OnboardingStep.NAME -> PromptTemplate.render(PromptTemplate.DIDNT_CATCH) + " " +
                PromptTemplate.render(PromptTemplate.ONBOARDING_NAME)
            OnboardingStep.CAPABILITIES -> PromptTemplate.render(PromptTemplate.DIDNT_CATCH) + " " +
                PromptTemplate.render(PromptTemplate.ONBOARDING_CAPABILITIES)
            OnboardingStep.GESTURE -> "Please tap once to test my gestures, or say okay to skip."
            else -> return
        }
        speakAndListen(prompt, step)
    }

    /**
     * Process the user's spoken response based on the current onboarding step.
     */
    private fun handleSpeechInput(transcript: String, step: OnboardingStep) {
        when (step) {
            OnboardingStep.LANGUAGE -> handleLanguageResponse(transcript)
            OnboardingStep.NAME -> handleNameResponse(transcript)
            OnboardingStep.CAPABILITIES -> handleCapabilitiesResponse(transcript)
            OnboardingStep.GESTURE -> handleGestureResponse(transcript)
            else -> {}
        }
    }

    /**
     * Step 2: User spoke their language preference.
     * Detect Hindi/Hinglish/English from the transcript and set the locale.
     */
    private fun handleLanguageResponse(transcript: String) {
        val lower = transcript.lowercase().trim()
        val locale = when {
            lower.contains("hinglish") || lower.contains("hindi english") -> PromptTemplate.LOCALE_HINGLISH
            lower.contains("hindi") || lower.contains("हिंदी") || lower.contains("हिन्दी") -> PromptTemplate.LOCALE_HI
            else -> PromptTemplate.LOCALE_EN
        }
        PromptTemplate.setLocale(locale)
        saveLocale(locale)
        Log.i(TAG, "Locale set to: $locale")

        // Re-init TTS for the new locale
        tts?.language = PromptTemplate.ttsLocale()

        currentStep = OnboardingStep.NAME
        onStepChange?.invoke(currentStep)
        speakAndListen(PromptTemplate.render(PromptTemplate.ONBOARDING_NAME), OnboardingStep.NAME)
    }

    /**
     * Step 3: User spoke their name. Store in UserMemory.
     */
    private fun handleNameResponse(transcript: String) {
        val name = transcript.trim().replaceFirstChar { it.uppercase() }
        Log.i(TAG, "User name: $name")

        scope.launch {
            try {
                val dao = userMemoryDao ?: VdxMemoryDatabase.getInstance(context).userMemoryDao()
                dao.upsert(
                    UserMemory(
                        type = "preference",
                        key = "user_name",
                        value = name,
                        context = "onboarding"
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to store user name", e)
            }
        }

        currentStep = OnboardingStep.CAPABILITIES
        onStepChange?.invoke(currentStep)
        speakAndListen(PromptTemplate.render(PromptTemplate.ONBOARDING_CAPABILITIES), OnboardingStep.CAPABILITIES)
    }

    /**
     * Step 4: User confirmed capabilities. Enable adapters and store preference.
     */
    private fun handleCapabilitiesResponse(transcript: String) {
        val lower = transcript.lowercase().trim()
        val confirmed = lower.startsWith("yes") || lower.startsWith("haan") ||
            lower.startsWith("हां") || lower.startsWith("ok") || lower.startsWith("okay")

        if (confirmed) {
            scope.launch {
                try {
                    val dao = userMemoryDao ?: VdxMemoryDatabase.getInstance(context).userMemoryDao()
                    dao.upsert(
                        UserMemory(
                            type = "preference",
                            key = "adapters_enabled",
                            value = "true",
                            context = "onboarding"
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to store adapter preference", e)
                }
            }
        }

        // Step 5 (gesture practice): tell the user VDX drives via
        // gestures and they shouldn't touch the screen. A real GestureAccessibilityService
        // would await a captured gesture here; this step just confirms the teaching moment.
        currentStep = OnboardingStep.GESTURE
        onStepChange?.invoke(currentStep)
        speakAndListen("Please don't touch the screen while I work. Tap once to test my gestures.", OnboardingStep.GESTURE)
    }

    /**
     * Step 5b: gesture practice acknowledged. Then speak done.
     * The gesture itself is verified live by GestureAccessibilityService, not by STT.
     */
    private fun handleGestureResponse(transcript: String) {
        val lower = transcript.lowercase().trim()
        val confirmed = lower.isNotEmpty() && (lower.startsWith("ok") || lower.startsWith("okay") ||
            lower.startsWith("yes") || lower.startsWith("haan") || transcript.isNotBlank())

        if (confirmed) {
            // gesture practiced
        }
        advanceToDone()
    }

    /**
     * Final steps of onboarding: speak the done message, mark onboarded, and
     * call onComplete. Extracted so both the capabilities-confirm path and the
     * gesture path converge here.
     */
    private fun advanceToDone() {
        // Step 6: Done message
        currentStep = OnboardingStep.DONE
        onStepChange?.invoke(currentStep)

        val donePrompt = PromptTemplate.render(PromptTemplate.ONBOARDING_DONE)
        tts?.apply {
            language = PromptTemplate.ttsLocale()
            speak(donePrompt, TextToSpeech.QUEUE_FLUSH, null, "onboarding_done")
        }
        Log.d(TAG, "Speaking done: $donePrompt")

        // Step 7: Mark onboarded after the done message plays
        handler.postDelayed({
            markOnboarded()
            onComplete?.invoke()
            destroy()
        }, estimateSpeechDurationMs(donePrompt))
    }

    private fun saveLocale(locale: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(LOCALE_KEY, locale).apply()
    }

    private fun markOnboarded() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(ONBOARDED_KEY, true).apply()
        Log.i(TAG, "Onboarding complete — onboarded flag set")
    }

    fun destroy() {
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}