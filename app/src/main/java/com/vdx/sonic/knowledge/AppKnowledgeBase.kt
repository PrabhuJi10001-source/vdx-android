package com.vdx.sonic.knowledge

import android.util.Log
import com.vdx.sonic.voice.PromptTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * AppKnowledgeBase — knowledge base for app navigation instructions.
 *
 * Wraps [AppKnowledgeDao] and provides high-level methods to store and
 * retrieve navigation steps for app actions. Seed data for core apps is
 * provided in [KnowledgeSeed] so the Planner has baseline knowledge without
 * needing to learn from scratch.
 *
 * Voice-first knowledge base pattern: instead of hardcoding every
 * app flow in adapters, navigation knowledge is stored as structured data
 * that the Planner can query at runtime.
 */
class AppKnowledgeBase(private val dao: AppKnowledgeDao) {

    companion object {
        private const val TAG = "AppKnowledgeBase"
    }

    /**
     * Get navigation steps for an app action in the given locale.
     * Falls back to English if not found in the requested locale.
     * Returns null if no knowledge exists for this app+action.
     */
    suspend fun getSteps(packageName: String, action: String, locale: String): List<NavStep>? {
        return withContext(Dispatchers.IO) {
            // Try requested locale first, fall back to English.
            val knowledge = dao.getForApp(packageName, action, locale)
                ?: dao.getForApp(packageName, action, PromptTemplate.LOCALE_EN)
                ?: return@withContext null
            deserializeSteps(knowledge.steps)
        }
    }

    /** Store or update navigation knowledge for an app action. */
    suspend fun putKnowledge(packageName: String, action: String, steps: List<NavStep>, locale: String) {
        withContext(Dispatchers.IO) {
            val json = serializeSteps(steps)
            dao.upsert(
                AppKnowledge(
                    packageName = packageName,
                    action = action,
                    steps = json,
                    locale = locale
                )
            )
        }
    }

    /** Check if knowledge exists for an app+action in the given locale (or English fallback). */
    suspend fun hasKnowledge(packageName: String, action: String, locale: String): Boolean {
        return withContext(Dispatchers.IO) {
            dao.getForApp(packageName, action, locale) != null
                || dao.getForApp(packageName, action, PromptTemplate.LOCALE_EN) != null
        }
    }

    /** Seed the knowledge base with baseline data if it's empty. */
    suspend fun seedIfEmpty() {
        withContext(Dispatchers.IO) {
            if (dao.count() > 0) return@withContext
            KnowledgeSeed.allSeeds.forEach { (pkg, actions) ->
                actions.forEach { (action, steps) ->
                    val json = serializeSteps(steps)
                    dao.upsert(
                        AppKnowledge(
                            packageName = pkg,
                            action = action,
                            steps = json,
                            locale = PromptTemplate.LOCALE_EN
                        )
                    )
                }
            }
            Log.i(TAG, "Seeded ${KnowledgeSeed.allSeeds.values.sumOf { it.size }} knowledge entries")
        }
    }

    // ── JSON serialization (org.json, part of Android SDK — no new deps) ──

    internal fun serializeSteps(steps: List<NavStep>): String {
        val arr = JSONArray()
        for (step in steps) {
            val obj = JSONObject()
            obj.put("description", step.description)
            step.targetElement?.let { obj.put("targetElement", it) }
            obj.put("action", step.action)
            step.text?.let { obj.put("text", it) }
            step.scrollDirection?.let { obj.put("scrollDirection", it) }
            step.waitMs?.let { obj.put("waitMs", it) }
            arr.put(obj)
        }
        return arr.toString()
    }

    internal fun deserializeSteps(json: String): List<NavStep> {
        val arr = JSONArray(json)
        val steps = mutableListOf<NavStep>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            steps.add(
                NavStep(
                    description = obj.optString("description"),
                    targetElement = obj.optString("targetElement").ifBlank { null },
                    action = obj.optString("action", NavStep.ACTION_TAP),
                    text = obj.optString("text").ifBlank { null },
                    scrollDirection = obj.optString("scrollDirection").ifBlank { null },
                    waitMs = obj.optLong("waitMs", 0).takeIf { it > 0 }
                )
            )
        }
        return steps
    }
}

/**
 * Seed knowledge for core apps — baseline navigation steps.
 *
 * Stored as a Kotlin object so it's testable without a database.
 * Each entry maps packageName → (action → list of NavSteps).
 */
object KnowledgeSeed {

    val allSeeds: Map<String, Map<String, List<NavStep>>> = mapOf(
        // WhatsApp
        "com.whatsapp" to mapOf(
            "send_message" to listOf(
                NavStep("Open WhatsApp", action = NavStep.ACTION_WAIT, waitMs = 2000),
                NavStep("Tap search", targetElement = "Search", action = NavStep.ACTION_TAP),
                NavStep("Type contact name", targetElement = "Search", action = NavStep.ACTION_SET_TEXT, text = "{{contact}}"),
                NavStep("Wait for results", action = NavStep.ACTION_WAIT, waitMs = 2000),
                NavStep("Tap contact in results", targetElement = "{{contact}}", action = NavStep.ACTION_TAP),
                NavStep("Wait for chat to open", action = NavStep.ACTION_WAIT, waitMs = 2000),
                NavStep("Tap message field", targetElement = "Message", action = NavStep.ACTION_TAP),
                NavStep("Type message", targetElement = "Message", action = NavStep.ACTION_SET_TEXT, text = "{{message}}"),
                NavStep("Tap send", targetElement = "Send", action = NavStep.ACTION_TAP)
            ),
            "open_chat" to listOf(
                NavStep("Open WhatsApp", action = NavStep.ACTION_WAIT, waitMs = 2000),
                NavStep("Tap search", targetElement = "Search", action = NavStep.ACTION_TAP),
                NavStep("Type contact name", targetElement = "Search", action = NavStep.ACTION_SET_TEXT, text = "{{contact}}"),
                NavStep("Wait for results", action = NavStep.ACTION_WAIT, waitMs = 2000),
                NavStep("Tap contact in results", targetElement = "{{contact}}", action = NavStep.ACTION_TAP)
            )
        ),
        // Phone / Dialer
        "com.android.dialer" to mapOf(
            "make_call" to listOf(
                NavStep("Open Phone app", action = NavStep.ACTION_WAIT, waitMs = 2000),
                NavStep("Tap dial pad or search", targetElement = "Dialpad", action = NavStep.ACTION_TAP),
                NavStep("Enter number or contact", targetElement = "Dialpad", action = NavStep.ACTION_SET_TEXT, text = "{{contact}}"),
                NavStep("Tap call button", targetElement = "Call", action = NavStep.ACTION_TAP)
            )
        ),
        // Uber
        "com.ubercab" to mapOf(
            "book_ride" to listOf(
                NavStep("Open Uber", action = NavStep.ACTION_WAIT, waitMs = 5000),
                NavStep("Tap destination field", targetElement = "Where to", action = NavStep.ACTION_TAP),
                NavStep("Type destination", targetElement = "Where to", action = NavStep.ACTION_SET_TEXT, text = "{{destination}}"),
                NavStep("Wait for suggestions", action = NavStep.ACTION_WAIT, waitMs = 3000),
                NavStep("Tap destination suggestion", targetElement = "{{destination}}", action = NavStep.ACTION_TAP),
                NavStep("Wait for fare options", action = NavStep.ACTION_WAIT, waitMs = 4000),
                NavStep("Tap confirm / book", targetElement = "Confirm", action = NavStep.ACTION_TAP)
            )
        ),
        // Chrome
        "com.android.chrome" to mapOf(
            "search" to listOf(
                NavStep("Open Chrome", action = NavStep.ACTION_WAIT, waitMs = 2000),
                NavStep("Tap address bar", targetElement = "Search or type URL", action = NavStep.ACTION_TAP),
                NavStep("Type search query", targetElement = "Search or type URL", action = NavStep.ACTION_SET_TEXT, text = "{{query}}"),
                NavStep("Press enter to search", action = NavStep.ACTION_TAP)
            )
        ),
        // Settings
        "com.android.settings" to mapOf(
            "open_wifi" to listOf(
                NavStep("Open Settings", action = NavStep.ACTION_WAIT, waitMs = 2000),
                NavStep("Tap Wi-Fi", targetElement = "Wi-Fi", action = NavStep.ACTION_TAP)
            ),
            "open_bluetooth" to listOf(
                NavStep("Open Settings", action = NavStep.ACTION_WAIT, waitMs = 2000),
                NavStep("Tap Bluetooth", targetElement = "Bluetooth", action = NavStep.ACTION_TAP)
            )
        )
    )
}