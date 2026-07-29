package com.vdx.sonic.voice

import com.vdx.sonic.IntentMode
import com.vdx.sonic.IntentType
import com.vdx.sonic.SonicIntent

/**
 * IntentParser — structured intent extraction for VDX Sonic.
 *
 * Takes cleaned + entity-repaired text and produces a structured [SonicIntent].
 * Uses regex patterns for fast path, LLM for complex/ambiguous cases.
 */
class IntentParser {

    companion object {
        private const val TAG = "Sonic-IntentParser"
        private const val HIGH_CONFIDENCE = 0.95f
        private const val MEDIUM_CONFIDENCE = 0.8f
        private const val LOW_CONFIDENCE = 0.6f
    }

    /**
     * Parse a cleaned transcript into a structured intent.
     * Fast path uses regex patterns. Falls back to LLM for complex cases.
     */
    fun parse(cleanedText: String, useLlm: Boolean = false): SonicIntent {
        val text = cleanedText.trim()
        if (text.isBlank()) {
            return SonicIntent(
                mode = IntentMode.COMMAND,
                type = IntentType.UNKNOWN,
                rawText = text,
                confidence = 0.0f
            )
        }

        // Fast regex-based parsing
        val result = parseRegex(text)
        if (result != null && result.confidence >= HIGH_CONFIDENCE) {
            return result
        }

        // If regex gives medium confidence, return it with clarification needed
        if (result != null && result.confidence >= MEDIUM_CONFIDENCE) {
            return result
        }

        // Low confidence or no match — return unknown with clarification
        return SonicIntent(
            mode = IntentMode.COMMAND,
            type = IntentType.UNKNOWN,
            rawText = text,
            confidence = LOW_CONFIDENCE,
            clarificationNeeded = true,
            clarificationQuestion = "I didn't understand. Try: call, message, open, book, search, or read."
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Regex Patterns
    // ──────────────────────────────────────────────────────────────

    private fun parseRegex(text: String): SonicIntent? {
        val t = text.lowercase().trim()

        // ── CALL ──
        // "call Mom", "phone John", "ring Dad", "dial 555-1234"
        val callMatch = Regex("""(?:call|phone|ring|dial)\s+(.+)$""").find(t)
        if (callMatch != null) {
            val contact = callMatch.groupValues[1].trim()
            return SonicIntent(
                mode = IntentMode.COMMAND,
                type = IntentType.CALL,
                entities = mapOf("contact" to contact),
                rawText = text,
                confidence = HIGH_CONFIDENCE,
                requiresConfirmation = true
            )
        }

        // ── WHATSAPP ──
        // "WhatsApp Mom saying hello", "message John on WhatsApp"
        val waMatch = Regex("""(?:whatsapp|whats app|wa)\s+(?:to\s+)?(\w[\w\s]*?)(?:\s+(?:saying|that|message)\s+(.+))?$""").find(t)
        if (waMatch != null) {
            val contact = waMatch.groupValues[1].trim()
            val message = waMatch.groupValues.getOrNull(2)?.trim() ?: ""
            val entities = mutableMapOf("contact" to contact)
            if (message.isNotBlank()) entities["message"] = message
            return SonicIntent(
                mode = IntentMode.COMMAND,
                type = IntentType.WHATSAPP,
                targetApp = "com.whatsapp",
                entities = entities,
                rawText = text,
                confidence = HIGH_CONFIDENCE,
                requiresConfirmation = message.isNotBlank()
            )
        }

        // ── SMS ──
        // "SMS Mom saying hello", "text John", "send message to Dad"
        val smsMatch = Regex("""(?:sms|text|send\s+message\s+to)\s+(\w[\w\s]*?)(?:\s+(?:saying|that|message)\s+(.+))?$""").find(t)
        if (smsMatch != null) {
            val contact = smsMatch.groupValues[1].trim()
            val message = smsMatch.groupValues.getOrNull(2)?.trim() ?: ""
            val entities = mutableMapOf("contact" to contact)
            if (message.isNotBlank()) entities["message"] = message
            return SonicIntent(
                mode = IntentMode.COMMAND,
                type = IntentType.SMS,
                entities = entities,
                rawText = text,
                confidence = HIGH_CONFIDENCE,
                requiresConfirmation = message.isNotBlank()
            )
        }

        // ── BOOK RIDE ──
        // "Book Uber to airport", "Uber to hospital", "get me an Uber"
        val uberMatch = Regex("""(?:book|get|order)?\s*(?:an?\s+)?(?:uber|ola)\s+(?:to\s+)?(.+)$""").find(t)
        if (uberMatch != null) {
            val dest = uberMatch.groupValues[1].trim()
            return SonicIntent(
                mode = IntentMode.COMMAND,
                type = IntentType.BOOK_RIDE,
                targetApp = "com.ubercab",
                entities = mapOf("destination" to dest),
                rawText = text,
                confidence = HIGH_CONFIDENCE,
                requiresConfirmation = true
            )
        }

        // ── APP LAUNCH ──
        // "Open WhatsApp", "launch Maps", "start Spotify"
        val appMatch = Regex("""(?:open|launch|start)\s+(.+)$""").find(t)
        if (appMatch != null) {
            val appName = appMatch.groupValues[1].trim()
            return SonicIntent(
                mode = IntentMode.COMMAND,
                type = IntentType.APP_LAUNCH,
                entities = mapOf("app_name" to appName),
                rawText = text,
                confidence = HIGH_CONFIDENCE
            )
        }

        // ── GO BACK / HOME ──
        if (t == "go back" || t == "back") {
            return SonicIntent(
                mode = IntentMode.COMMAND,
                type = IntentType.GO_BACK,
                rawText = text,
                confidence = HIGH_CONFIDENCE
            )
        }
        if (t == "go home" || t == "home") {
            return SonicIntent(
                mode = IntentMode.COMMAND,
                type = IntentType.GO_HOME,
                rawText = text,
                confidence = HIGH_CONFIDENCE
            )
        }

        // ── READ SCREEN ──
        if (t.contains("read") && (t.contains("screen") || t.contains("this") || t.contains("page"))) {
            return SonicIntent(
                mode = IntentMode.READ,
                type = IntentType.READ_SCREEN,
                rawText = text,
                confidence = HIGH_CONFIDENCE
            )
        }

        // ── SEARCH ──
        // "search for cat videos", "search YouTube for cats"
        val searchMatch = Regex("""search\s+(?:for\s+)?(.+)$""").find(t)
        if (searchMatch != null) {
            val query = searchMatch.groupValues[1].trim()
            return SonicIntent(
                mode = IntentMode.COMMAND,
                type = IntentType.SEARCH,
                entities = mapOf("query" to query),
                rawText = text,
                confidence = HIGH_CONFIDENCE
            )
        }

        // ── YOUTUBE ──
        val ytMatch = Regex("""(?:search\s+)?youtube\s+(?:for\s+)?(.+)$""").find(t)
        if (ytMatch != null) {
            val query = ytMatch.groupValues[1].trim()
            return SonicIntent(
                mode = IntentMode.COMMAND,
                type = IntentType.YOUTUBE_SEARCH,
                targetApp = "com.google.android.youtube",
                entities = mapOf("query" to query),
                rawText = text,
                confidence = HIGH_CONFIDENCE
            )
        }

        // ── SETTINGS ──
        // "open WiFi settings", "open Bluetooth settings"
        val settingsMatch = Regex("""(?:open|go to)\s+(.+)\s+settings$""").find(t)
        if (settingsMatch != null) {
            val section = settingsMatch.groupValues[1].trim()
            return SonicIntent(
                mode = IntentMode.NAVIGATION,
                type = IntentType.SETTINGS_NAVIGATION,
                entities = mapOf("section" to section),
                rawText = text,
                confidence = MEDIUM_CONFIDENCE
            )
        }

        // ── SYSTEM QUERY ──
        if (t.contains("battery") || t.contains("time") || t.contains("date") || t.contains("weather")) {
            return SonicIntent(
                mode = IntentMode.SYSTEM_QUERY,
                type = IntentType.SYSTEM_QUERY,
                rawText = text,
                confidence = MEDIUM_CONFIDENCE
            )
        }

        // ── DICTATION (user in text field, natural text) ──
        // Heuristic: if text is long (>5 words) and doesn't match command patterns
        val wordCount = t.split(" ").size
        if (wordCount > 5 && !containsCommandKeywords(t)) {
            return SonicIntent(
                mode = IntentMode.DICTATION,
                type = IntentType.FORM_FILL,
                entities = mapOf("text" to text),
                rawText = text,
                confidence = MEDIUM_CONFIDENCE
            )
        }

        return null
    }

    private fun containsCommandKeywords(text: String): Boolean {
        val keywords = listOf(
            "call", "phone", "ring", "dial",
            "whatsapp", "whats app", "wa",
            "sms", "text", "message",
            "uber", "ola", "book",
            "open", "launch", "start",
            "search", "find",
            "read", "go back", "go home",
            "email", "mail"
        )
        return keywords.any { text.startsWith(it) }
    }
}
