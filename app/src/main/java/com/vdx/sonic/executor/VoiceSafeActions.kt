package com.vdx.sonic.executor

import com.vdx.sonic.IntentType
import com.vdx.sonic.SonicIntent

/**
 * VoiceSafeActions — hard voice-action allowlist gate (voice allowlist).
 *
 * Voice is a high-risk input channel: a misheard or accidental utterance can fire
 * an irreversible action. This gate is the single source of truth for what voice
 * may execute, and it is fail-closed — any intent type not explicitly classified
 * is BLOCKED. It is deliberately independent of [ClarificationEngine], which is
 * advisory (it asks the user); this gate is enforcement (it refuses).
 *
 * Three tiers:
 *  - ALLOW: safe, non-destructive, voice may execute directly.
 *  - REQUIRE_CONFIRMATION: destructive / irreversible / state-changing; voice may
 *    execute only after explicit user confirmation. The gate forces
 *    [SonicIntent.requiresConfirmation] so a caller cannot bypass it.
 *  - BLOCK: never voice-executable, even with confirmation. The user must act
 *    directly (or the command is not a real command).
 *
 * Pure and Android-free so it is unit-testable in a plain JVM.
 */
object VoiceSafeActions {

    /** Intent types voice may execute directly, with no confirmation. */
    private val ALLOWED: Set<IntentType> = setOf(
        // Navigation / system
        IntentType.GO_BACK,
        IntentType.GO_HOME,
        IntentType.SETTINGS_NAVIGATION,
        IntentType.APP_LAUNCH,
        IntentType.APP_SWITCH,
        // Read-only
        IntentType.READ_SCREEN,
        IntentType.READ_FOCUSED,
        IntentType.READ_NOTIFICATIONS,
        IntentType.READ_PDF,
        IntentType.DESCRIBE_IMAGE,
        IntentType.SYSTEM_QUERY,
        IntentType.SEARCH,
        IntentType.YOUTUBE_SEARCH,
        IntentType.MEMORY_RECALL,
        // Gestures (destructive targets are caught by ActionResolver.isDestructiveTarget)
        IntentType.GESTURE,
        // Voice-flow abort
        IntentType.CANCEL
    )

    /** Intent types that change state / are destructive / irreversible — need confirmation. */
    private val CONFIRMATION_REQUIRED: Set<IntentType> = setOf(
        IntentType.CALL,
        IntentType.WHATSAPP,
        IntentType.SMS,
        IntentType.EMAIL,
        IntentType.BOOK_RIDE,
        IntentType.CONTACT_MANAGE,
        IntentType.PLAY_STORE,
        IntentType.YOUTUBE_CONTROL,
        IntentType.SET_ALARM,
        IntentType.SYSTEM_TOGGLE,
        IntentType.MEMORY_STORE,
        IntentType.DRAFT_NOTE,
        IntentType.FORM_FILL,
        IntentType.TEXT_EDIT
    )

    sealed class Decision {
        /** Safe — voice may execute directly. */
        object Allow : Decision()
        /** Destructive / state-changing — voice may execute only after confirmation. */
        data class RequireConfirmation(val reason: String) : Decision()
        /** Never voice-executable. The user must act directly. */
        data class Block(val reason: String) : Decision()
    }

    /**
     * Classify an intent against the voice allowlist. Fail-closed: any type not
     * explicitly listed is BLOCKED.
     */
    fun classify(intent: SonicIntent): Decision {
        if (PaymentBlocklist.blocked(intent)) {
            return Decision.Block(PaymentBlocklist.REASON)
        }
        val type = intent.type
        if (type in ALLOWED) return Decision.Allow
        if (type in CONFIRMATION_REQUIRED) {
            return Decision.RequireConfirmation(
                "Voice action '${type.name}' is destructive or state-changing and requires confirmation."
            )
        }
        return Decision.Block(
            "Voice action '${type.name}' is not on the safe allowlist and cannot be executed by voice."
        )
    }

    /**
     * True if voice may execute this intent (directly or after confirmation).
     * False means the intent is hard-blocked and must not reach the executor.
     */
    fun isVoiceExecutable(intent: SonicIntent): Boolean =
        classify(intent) !is Decision.Block

    /**
     * Enforce the gate on an intent, returning the intent with confirmation forced
     * when the gate requires it. Returns null when the intent is hard-blocked.
     *
     * This is the non-bypassable entry point: callers must use the returned intent
     * (or null) rather than the original, so a caller cannot skip confirmation.
     */
    fun enforce(intent: SonicIntent): SonicIntent? = when (val d = classify(intent)) {
        is Decision.Allow -> intent
        is Decision.RequireConfirmation -> intent.copy(requiresConfirmation = true)
        is Decision.Block -> null
    }
}
