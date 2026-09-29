package com.vdx.sonic.executor

import com.vdx.sonic.IntentMode
import com.vdx.sonic.IntentType
import com.vdx.sonic.SonicIntent
import org.junit.Assert.*
import org.junit.Test

/**
 * VoiceSafeActionsTest — verifies the hard voice-action allowlist gate
 * (voice allowlist). Fail-closed: unknown types are blocked.
 */
class VoiceSafeActionsTest {

    private fun intent(type: IntentType) = SonicIntent(
        mode = IntentMode.COMMAND,
        type = type,
        rawText = type.name.lowercase(),
        confidence = 0.9f
    )

    // ── Allow (safe, non-destructive) ─────────────────────────────

    @Test
    fun goBack_isAllowed() {
        assertTrue(VoiceSafeActions.isVoiceExecutable(intent(IntentType.GO_BACK)))
        assertEquals(VoiceSafeActions.Decision.Allow, VoiceSafeActions.classify(intent(IntentType.GO_BACK)))
    }

    @Test
    fun readScreen_isAllowed() {
        assertTrue(VoiceSafeActions.isVoiceExecutable(intent(IntentType.READ_SCREEN)))
    }

    @Test
    fun cancel_isAllowed() {
        assertTrue(VoiceSafeActions.isVoiceExecutable(intent(IntentType.CANCEL)))
    }

    @Test
    fun systemQuery_isAllowed() {
        assertTrue(VoiceSafeActions.isVoiceExecutable(intent(IntentType.SYSTEM_QUERY)))
    }

    // ── Require confirmation (destructive / state-changing) ────────

    @Test
    fun call_requiresConfirmation() {
        val d = VoiceSafeActions.classify(intent(IntentType.CALL))
        assertTrue(d is VoiceSafeActions.Decision.RequireConfirmation)
    }

    @Test
    fun whatsapp_requiresConfirmation() {
        val d = VoiceSafeActions.classify(intent(IntentType.WHATSAPP))
        assertTrue(d is VoiceSafeActions.Decision.RequireConfirmation)
    }

    @Test
    fun bookRide_requiresConfirmation() {
        val d = VoiceSafeActions.classify(intent(IntentType.BOOK_RIDE))
        assertTrue(d is VoiceSafeActions.Decision.RequireConfirmation)
    }

    @Test
    fun enforce_forcesConfirmationOnDestructive() {
        val gated = VoiceSafeActions.enforce(intent(IntentType.CALL))
        assertNotNull(gated)
        assertTrue(gated!!.requiresConfirmation)
    }

    @Test
    fun enforce_doesNotForceConfirmationOnSafe() {
        val gated = VoiceSafeActions.enforce(intent(IntentType.GO_BACK))
        assertNotNull(gated)
        assertFalse(gated!!.requiresConfirmation)
    }

    // ── Block (never voice-executable) ─────────────────────────────

    @Test
    fun unknown_isBlocked() {
        val d = VoiceSafeActions.classify(intent(IntentType.UNKNOWN))
        assertTrue(d is VoiceSafeActions.Decision.Block)
        assertFalse(VoiceSafeActions.isVoiceExecutable(intent(IntentType.UNKNOWN)))
    }

    @Test
    fun enforce_returnsNullForBlocked() {
        assertNull(VoiceSafeActions.enforce(intent(IntentType.UNKNOWN)))
    }

    // ── Fail-closed: every IntentType is classified ────────────────

    @Test
    fun everyIntentType_isClassified() {
        // Fail-closed guarantee: no type may fall through to an unhandled state.
        for (type in IntentType.entries) {
            val d = VoiceSafeActions.classify(intent(type))
            assertTrue(
                "IntentType.${type.name} must be Allow, RequireConfirmation, or Block",
                d is VoiceSafeActions.Decision.Allow ||
                    d is VoiceSafeActions.Decision.RequireConfirmation ||
                    d is VoiceSafeActions.Decision.Block
            )
        }
    }
}
