package com.vdx.sonic.clarify

import com.vdx.sonic.*

/**
 * ClarificationEngine — ask/confirm logic for VDX Sonic.
 *
 * Hard policy: If uncertain, ask to clarify.
 * Wrong action is worse than delayed action.
 */
class ClarificationEngine {

    companion object {
        private const val TAG = "Sonic-Clarify"
        private const val INTENT_CONFIDENCE_THRESHOLD = 0.7f
        private const val ENTITY_CONFIDENCE_THRESHOLD = 0.7f
    }

    /**
     * Evaluate whether clarification or confirmation is needed.
     */
    fun evaluate(intent: SonicIntent, repairResult: EntityRepairResult?): ClarificationRequest? {
        // 1. Intent explicitly needs clarification
        if (intent.clarificationNeeded) {
            return ClarificationRequest(
                id = java.util.UUID.randomUUID().toString(),
                question = intent.clarificationQuestion ?: "I didn't understand. What would you like me to do?",
                type = ClarificationType.AMBIGUOUS_INTENT,
                context = intent
            )
        }

        // 2. Low intent confidence
        if (intent.confidence < INTENT_CONFIDENCE_THRESHOLD) {
            return ClarificationRequest(
                id = java.util.UUID.randomUUID().toString(),
                question = "I'm not sure I understood correctly. Did you mean to ${describeIntent(intent)}?",
                type = ClarificationType.AMBIGUOUS_INTENT,
                options = listOf("Yes", "No, try again"),
                context = intent
            )
        }

        // 3. Low entity confidence
        if (repairResult != null && repairResult.overallConfidence < ENTITY_CONFIDENCE_THRESHOLD) {
            val lowEntities = repairResult.entities.filter { it.confidence < ENTITY_CONFIDENCE_THRESHOLD }
            if (lowEntities.isNotEmpty()) {
                val entity = lowEntities.first()
                return ClarificationRequest(
                    id = java.util.UUID.randomUUID().toString(),
                    question = "Did you say \"${entity.original}\" or \"${entity.repaired}\"?",
                    type = ClarificationType.SPELLING_CONFIRMATION,
                    options = listOf(entity.repaired, entity.original),
                    context = intent
                )
            }
        }

        // 4. Destructive action confirmation
        if (intent.requiresConfirmation) {
            return ClarificationRequest(
                id = java.util.UUID.randomUUID().toString(),
                question = buildConfirmationQuestion(intent),
                type = ClarificationType.ACTION_CONFIRMATION,
                options = listOf("Yes, do it", "No, cancel"),
                context = intent
            )
        }

        return null
    }

    /**
     * Evaluate if confirmation is needed for an irreversible action.
     */
    fun needsConfirmation(intent: SonicIntent): Boolean {
        return when (intent.type) {
            IntentType.CALL -> true
            IntentType.WHATSAPP -> intent.entities.containsKey("message")
            IntentType.SMS -> intent.entities.containsKey("message")
            IntentType.BOOK_RIDE -> true
            IntentType.EMAIL -> true
            else -> false
        }
    }

    /**
     * Handle user's response to a clarification request.
     */
    fun handleResponse(
        request: ClarificationRequest,
        response: String,
        originalIntent: SonicIntent
    ): SonicIntent {
        val lower = response.lowercase().trim()

        when (request.type) {
            ClarificationType.AMBIGUOUS_INTENT -> {
                if (lower.startsWith("yes") || lower.startsWith("y")) {
                    return originalIntent.copy(confidence = 0.9f, clarificationNeeded = false)
                }
                return originalIntent.copy(
                    confidence = 0.0f,
                    type = IntentType.UNKNOWN,
                    clarificationQuestion = "What would you like me to do?"
                )
            }

            ClarificationType.SPELLING_CONFIRMATION -> {
                // User confirmed one of the options
                val matchedOption = request.options?.firstOrNull { it.lowercase() == lower }
                if (matchedOption != null) {
                    return originalIntent.copy(confidence = 0.9f, clarificationNeeded = false)
                }
                return originalIntent.copy(
                    confidence = 0.0f,
                    type = IntentType.UNKNOWN,
                    clarificationQuestion = "I still didn't catch that. Could you spell it?"
                )
            }

            ClarificationType.ACTION_CONFIRMATION -> {
                if (lower.startsWith("yes") || lower.startsWith("y")) {
                    return originalIntent.copy(confidence = 0.95f, requiresConfirmation = false)
                }
                return originalIntent.copy(
                    type = IntentType.UNKNOWN,
                    clarificationQuestion = "Cancelled. What would you like to do instead?"
                )
            }

            else -> return originalIntent
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Private
    // ──────────────────────────────────────────────────────────────

    private fun describeIntent(intent: SonicIntent): String {
        return when (intent.type) {
            IntentType.CALL -> "call ${intent.entities["contact"] ?: "someone"}"
            IntentType.WHATSAPP -> "send a WhatsApp to ${intent.entities["contact"] ?: "someone"}"
            IntentType.SMS -> "send an SMS to ${intent.entities["contact"] ?: "someone"}"
            IntentType.BOOK_RIDE -> "book a ride to ${intent.entities["destination"] ?: "somewhere"}"
            IntentType.APP_LAUNCH -> "open ${intent.entities["app_name"] ?: "an app"}"
            IntentType.SEARCH -> "search for ${intent.entities["query"] ?: "something"}"
            IntentType.YOUTUBE_SEARCH -> "search YouTube for ${intent.entities["query"] ?: "something"}"
            IntentType.READ_SCREEN -> "read the screen"
            IntentType.GO_BACK -> "go back"
            IntentType.GO_HOME -> "go home"
            IntentType.SETTINGS_NAVIGATION -> "open ${intent.entities["section"] ?: ""} settings"
            else -> "do that"
        }
    }

    private fun buildConfirmationQuestion(intent: SonicIntent): String {
        return when (intent.type) {
            IntentType.CALL -> "Call ${intent.entities["contact"] ?: "that contact"}?"
            IntentType.WHATSAPP -> {
                val contact = intent.entities["contact"] ?: "someone"
                val message = intent.entities["message"] ?: ""
                if (message.isNotBlank()) "Send \"$message\" to $contact on WhatsApp?"
                else "Open WhatsApp chat for $contact?"
            }
            IntentType.SMS -> {
                val contact = intent.entities["contact"] ?: "someone"
                val message = intent.entities["message"] ?: ""
                if (message.isNotBlank()) "Send \"$message\" to $contact?"
                else "Open messages for $contact?"
            }
            IntentType.BOOK_RIDE -> "Book a ride to ${intent.entities["destination"] ?: "that destination"}?"
            IntentType.EMAIL -> "Send email to ${intent.entities["contact"] ?: "that contact"}?"
            else -> "Proceed?"
        }
    }
}
