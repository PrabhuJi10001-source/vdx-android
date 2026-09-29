package com.vdx.sonic.voice

import com.vdx.sonic.IntentType
import com.vdx.sonic.SonicIntent
import org.junit.Assert.*
import org.junit.Test

/**
 * VoiceInteractionHarnessTest — acceptance harness for the voice interaction engine.
 *
 * Acceptance criteria: "A test harness can send a simulated voice command (or actual
 * speech) and receive a parsed command object with the action type and target (if any)."
 *
 * This harness simulates the full voice path:
 *   simulated speech → (wake-word detection) → cleanup → IntentParser → SonicIntent
 *
 * It exercises the exact pipeline the live service runs, minus the Android
 * SpeechRecognizer/TextToSpeech hardware (which cannot run in a JVM unit test).
 */
class VoiceInteractionHarnessTest {

    private val parser = IntentParser()

    /** Simulate a spoken utterance through the full voice pipeline. */
    private fun simulateVoiceCommand(spoken: String): ParsedCommand {
        // 1. Wake-word detection (strips "Hey Vision" if present)
        val wake = WakeWordDetector.detect(spoken)
        val commandText = if (wake.activated) wake.command else spoken

        // 2. Local cleanup (fillers, course corrections)
        val cleaned = LocalCleanupEngine.clean(commandText)

        // 3. Intent parse → command object
        val intent = parser.parse(cleaned)

        return ParsedCommand(
            intent = intent,
            wakeWordDetected = wake.activated,
            rawSpoken = spoken,
            cleanedText = cleaned
        )
    }

    /** The parsed command object the harness returns. */
    data class ParsedCommand(
        val intent: SonicIntent,
        val wakeWordDetected: Boolean,
        val rawSpoken: String,
        val cleanedText: String
    ) {
        val actionType: IntentType get() = intent.type
        val target: String? get() = intent.entities.values.firstOrNull()
    }

    // ──────────────────────────────────────────────────────────────
    // Acceptance: simulated voice command → parsed command object
    // ──────────────────────────────────────────────────────────────

    @Test
    fun simulatedVoiceCommand_returnsParsedCommandWithActionType() {
        val cmd = simulateVoiceCommand("call Mom")
        assertEquals(IntentType.CALL, cmd.actionType)
        assertEquals("Mom", cmd.target)
        assertFalse(cmd.wakeWordDetected)
    }

    @Test
    fun simulatedVoiceCommand_withWakeWord_stripsPhraseAndParses() {
        val cmd = simulateVoiceCommand("Hey Vision call Mom")
        assertTrue(cmd.wakeWordDetected)
        assertEquals(IntentType.CALL, cmd.actionType)
        assertEquals("Mom", cmd.target)
        // The wake word must not leak into the command text.
        assertFalse(cmd.cleanedText.contains("hey", ignoreCase = true))
    }

    @Test
    fun simulatedVoiceCommand_withFillers_cleansBeforeParse() {
        val cmd = simulateVoiceCommand("uh call Mom please")
        assertEquals(IntentType.CALL, cmd.actionType)
        assertEquals("Mom", cmd.target)
    }

    @Test
    fun simulatedVoiceCommand_scrollDown_mapsToIntent() {
        // "scroll down" is a navigation gesture — verify it parses to a known intent
        // (falls through to dictation/form-fill or a gesture intent; must not be UNKNOWN).
        val cmd = simulateVoiceCommand("scroll down")
        assertNotEquals(IntentType.UNKNOWN, cmd.actionType)
    }

    @Test
    fun simulatedVoiceCommand_goBack_mapsToGoBack() {
        val cmd = simulateVoiceCommand("go back")
        assertEquals(IntentType.GO_BACK, cmd.actionType)
    }

    @Test
    fun simulatedVoiceCommand_readScreen_mapsToReadScreen() {
        val cmd = simulateVoiceCommand("read screen")
        assertEquals(IntentType.READ_SCREEN, cmd.actionType)
    }

    @Test
    fun simulatedVoiceCommand_unknown_returnsUnknownWithClarification() {
        val cmd = simulateVoiceCommand("xyzzy plugh")
        assertEquals(IntentType.UNKNOWN, cmd.actionType)
        assertTrue(cmd.intent.clarificationNeeded)
    }

    // ──────────────────────────────────────────────────────────────
    // Wake-word detector unit tests
    // ──────────────────────────────────────────────────────────────

    @Test
    fun wakeWord_pureActivation_detected() {
        val d = WakeWordDetector.detect("Hey Vision")
        assertTrue(d.activated)
        assertTrue(d.pureActivation)
        assertEquals("", d.command)
    }

    @Test
    fun wakeWord_withCommand_returnsRemainder() {
        val d = WakeWordDetector.detect("Hey Vision open WhatsApp")
        assertTrue(d.activated)
        assertFalse(d.pureActivation)
        assertEquals("open WhatsApp", d.command)
    }

    @Test
    fun wakeWord_variant_vdxMatches() {
        val d = WakeWordDetector.detect("hey vdx call mom")
        assertTrue(d.activated)
        assertEquals("call mom", d.command)
    }

    @Test
    fun wakeWord_absent_notActivated() {
        val d = WakeWordDetector.detect("call mom")
        assertFalse(d.activated)
        assertEquals("call mom", d.command)
    }

    @Test
    fun wakeWord_blank_notActivated() {
        val d = WakeWordDetector.detect("   ")
        assertFalse(d.activated)
    }

    @Test
    fun wakeWord_containsActivation_anywhere() {
        assertTrue(WakeWordDetector.containsActivation("please hey vision call mom"))
        assertFalse(WakeWordDetector.containsActivation("call mom"))
    }

    // ──────────────────────────────────────────────────────────────
    // Cancel / abort routing (voice-flow abort)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun cancelUtterance_routesToCancelIntent() {
        val cmd = simulateVoiceCommand("cancel")
        assertEquals(IntentType.CANCEL, cmd.actionType)
    }

    @Test
    fun neverMindUtterance_routesToCancelIntent() {
        val cmd = simulateVoiceCommand("never mind")
        assertEquals(IntentType.CANCEL, cmd.actionType)
    }

    @Test
    fun stopUtterance_routesToCancelIntent() {
        val cmd = simulateVoiceCommand("stop")
        assertEquals(IntentType.CANCEL, cmd.actionType)
    }

    @Test
    fun dismissUtterance_routesToCancelIntent() {
        val cmd = simulateVoiceCommand("dismiss")
        assertEquals(IntentType.CANCEL, cmd.actionType)
    }

    @Test
    fun cancelRide_doesNotRouteToAbort_staysOnBookRide() {
        // A longer command with "cancel" as a substring must NOT abort the flow;
        // it is a ride-management command, not a voice-flow abort.
        val cmd = simulateVoiceCommand("cancel my uber ride")
        assertNotEquals(IntentType.CANCEL, cmd.actionType)
        assertEquals(IntentType.BOOK_RIDE, cmd.actionType)
    }

    @Test
    fun cancelUtterance_doesNotTriggerConfirmation() {
        val cmd = simulateVoiceCommand("cancel")
        assertFalse(cmd.intent.requiresConfirmation)
        assertFalse(cmd.intent.clarificationNeeded)
    }
}
