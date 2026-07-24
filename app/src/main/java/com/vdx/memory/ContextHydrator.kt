package com.vdx.memory

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * ContextHydrator — hydrates user speech with relevant memories before LLM processing.
 *
 * Before calling Gemini, this resolves known entities from the transcript against
 * stored memories. This means:
 * - "call Mom" → resolves "Mom" to stored contact number, passes as context
 * - "Uber home" → resolves "home" to stored location
 * - "message Ravi" → resolves "Ravi" to preferred app (WhatsApp vs SMS)
 *
 * The hydrated context is injected into the LLM prompt so Gemini has full
 * information without needing to ask clarifying questions.
 *
 * Veda pattern: retrieval-augmented generation, but from on-device Room DB.
 */
class ContextHydrator(private val context: Context) {

    private val memoryStore = UserMemoryStore(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Hydrate a transcript with relevant context from stored memories.
     * Returns a pair: (hydratedPrompt, resolvedEntities) where resolvedEntities
     * is a JSON string of known facts about entities mentioned in the transcript.
     *
     * Example:
     * Input: "call Mom"
     * Output: "call Mom\n\nKnown context: Mom = +971****4567 (contact, phone number)"
     */
    suspend fun hydrate(transcript: String): HydrationResult {
        val t = transcript.lowercase().trim()
        val words = t.split("\\s+".toRegex()).filter { it.length > 2 }

        val resolved = mutableListOf<ResolvedEntity>()

        // Check each word against stored memories
        for (word in words) {
            val memory = memoryStore.recall(word) ?: continue
            val memObj = memoryStore.getMemoryByKey(word)
            if (memObj != null) {
                resolved.add(ResolvedEntity(
                    key = word,
                    value = memory,
                    type = memObj.type,
                    context = memObj.context
                ))
            }
        }

        // Also check for multi-word matches (e.g. "ice cream" as a key)
        // by checking the full transcript as a key
        val fullMatch = memoryStore.recall(t)
        if (fullMatch != null) {
            val memObj = memoryStore.getMemoryByKey(t)
            if (memObj != null) {
                resolved.add(ResolvedEntity(
                    key = t,
                    value = fullMatch,
                    type = memObj.type,
                    context = memObj.context
                ))
            }
        }

        // Check for default app preferences
        // If user says "message Ravi" and we have a default for Ravi, resolve it
        for (entity in resolved) {
            if (entity.type == "default") {
                // The value is the preferred action (e.g. "whatsapp")
                // This tells the LLM to route to that app
            }
        }

        val hydratedPrompt = if (resolved.isEmpty()) {
            transcript
        } else {
            val contextBlock = resolved.joinToString("\n") {
                "- ${it.key} = ${it.value} (${it.type}${if (it.context.isNotBlank()) ", ${it.context}" else ""})"
            }
            "$transcript\n\nKnown context:\n$contextBlock"
        }

        return HydrationResult(
            hydratedPrompt = hydratedPrompt,
            resolvedEntities = resolved
        )
    }

    data class HydrationResult(
        val hydratedPrompt: String,
        val resolvedEntities: List<ResolvedEntity>
    )

    data class ResolvedEntity(
        val key: String,
        val value: String,
        val type: String,
        val context: String
    )
}
