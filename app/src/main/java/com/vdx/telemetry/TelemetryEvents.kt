package com.vdx.telemetry

/**
 * TelemetryEvents — pure-Kotlin event contract for the VDX telemetry pipe.
 *
 * This file contains NO Android imports so the sanitizer and event shape can be
 * unit-tested in plain JVM. Only allowlisted event types and fields survive —
 * anything else is dropped (defense in depth), so transcripts, contact names,
 * message bodies, search queries, screen contents, typed text, and API keys can
 * never leak into an event.
 *
 * Event shape (serialized by Telemetry):
 *   { v: appVersion, did: installUUID, ts: ISO8601,
 *     e: <type>, fields: { allowlisted only } }
 */
object TelemetryEventTypes {
    const val INSTALL = "install"
    const val CONSENT_GRANTED = "consent_granted"
    const val CONSENT_DENIED = "consent_denied"
    const val INTENT_PARSED = "intent_parsed"
    const val EXECUTION_RESULT = "execution_result"
    const val ENGINE_SELECTED = "engine_selected"
    const val KEY_ADDED = "key_added"
    const val VERBOSITY_CHANGED = "verbosity_changed"
    const val CRASH = "crash"

    /** All known event types. */
    val ALL: Set<String> = setOf(
        INSTALL, CONSENT_GRANTED, CONSENT_DENIED, INTENT_PARSED, EXECUTION_RESULT,
        ENGINE_SELECTED, KEY_ADDED, VERBOSITY_CHANGED, CRASH
    )
}

/** A raw (pre-sanitization) event as submitted to [TelemetrySanitizer]. */
data class TelemetryEvent(
    val type: String,
    val fields: Map<String, Any?>
)

/**
 * Strict allowlist sanitizer — the single gate every event passes through before
 * it is queued or leaves the device. Drops unknown event types entirely and drops
 * any field key not allowlisted for that type. Enum-restricted values are also
 * validated so a stray non-allowlisted value can't ride in under an allowed key.
 */
object TelemetrySanitizer {

    // Per-event-type allowlist of field keys.
    private val ALLOWLIST: Map<String, Set<String>> = mapOf(
        TelemetryEventTypes.INSTALL to emptySet(),
        TelemetryEventTypes.CONSENT_GRANTED to emptySet(),
        TelemetryEventTypes.CONSENT_DENIED to emptySet(),
        TelemetryEventTypes.INTENT_PARSED to setOf("type"),
        TelemetryEventTypes.EXECUTION_RESULT to setOf("intent_type", "status", "duration_ms"),
        TelemetryEventTypes.ENGINE_SELECTED to setOf("asr", "llm", "key"),
        TelemetryEventTypes.KEY_ADDED to setOf("provider", "valid"),
        TelemetryEventTypes.VERBOSITY_CHANGED to setOf("level"),
        TelemetryEventTypes.CRASH to setOf("exception_class", "stack_top3_frames")
    )

    // Allowed enum values (coarse intents / engine names only — no free text).
    private val STATUS_VALUES = setOf("success", "fail", "unverified")
    private val ASR_VALUES = setOf("gemini", "groq", "openai", "sarvam", "ondevice")
    private val LLM_VALUES = setOf("gemini", "openrouter", "openai", "anthropic", "regex")
    private val KEY_VALUES = setOf("byok", "none")

    data class Result(val fields: Map<String, Any?>, val dropped: Int)

    /** True when [type] is a known, sendable event type. */
    fun isKnownType(type: String): Boolean = type in ALLOWLIST

    /**
     * Sanitize [fields] for [type]. Unknown event → empty. Any key not in the
     * allowlist, or a value that fails validation, is dropped (counted in
     * [Result.dropped]). The returned map never contains PII-capable content.
     */
    fun sanitize(type: String, fields: Map<String, Any?>): Result {
        val allowed = ALLOWLIST[type] ?: return Result(emptyMap(), fields.size)
        val out = LinkedHashMap<String, Any?>()
        var dropped = 0
        for ((k, v) in fields) {
            if (k !in allowed) { dropped++; continue }
            if (!valueValid(type, k, v)) { dropped++; continue }
            out[k] = v
        }
        return Result(out, dropped)
    }

    /**
     * Coarse intent label for an [IntentType]-like enum name, or null when it
     * can't be expressed safely (unknown → dropped, no free text).
     */
    fun safeIntentLabel(enumName: String?): String? {
        val n = enumName?.trim().orEmpty().uppercase()
        if (n.isEmpty()) return null
        // Only allow coarse enum identifiers (A-Z, 0-9, underscore) — this blocks
        // any transcript/contact/content from sneaking in via this slot.
        if (!n.all { it.isLetterOrDigit() || it == '_' }) return null
        return n
    }

    /** Engine label for an ASR provider enum; null if not allowlisted. */
    fun safeAsr(value: String?): String? = value?.trim()?.lowercase()?.takeIf { it in ASR_VALUES }

    /** Engine label for an LLM provider; null if not allowlisted. */
    fun safeLlm(value: String?): String? = value?.trim()?.lowercase()?.takeIf { it in LLM_VALUES }

    /** Key mode label (byok|none); null if not allowlisted. */
    fun safeKeyMode(value: String?): String? = value?.trim()?.lowercase()?.takeIf { it in KEY_VALUES }

    /** Status label (success|fail|unverified); null if not allowlisted. */
    fun safeStatus(value: String?): String? = value?.trim()?.lowercase()?.takeIf { it in STATUS_VALUES }

    private fun valueValid(type: String, key: String, v: Any?): Boolean {
        if (v == null) return false
        return when (key) {
            "type", "intent_type" -> v is String && v.isNotBlank() &&
                v.all { it.isLetterOrDigit() || it == '_' }
            "status" -> v is String && v.lowercase() in STATUS_VALUES
            "asr" -> v is String && v.lowercase() in ASR_VALUES
            "llm" -> v is String && v.lowercase() in LLM_VALUES
            "key" -> v is String && v.lowercase() in KEY_VALUES
            "provider" -> v is String && v.isNotBlank() &&
                v.all { it.isLetterOrDigit() || it == '_' || it.isWhitespace() }
            "valid" -> v is Boolean
            "duration_ms" -> v is Number && v.toDouble() >= 0
            "level" -> v is Number && v.toInt() in 0..10
            "exception_class" -> v is String && v.isNotBlank() &&
                v.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '$' }
            "stack_top3_frames" -> v is String && v.length <= 400
            else -> false
        }
    }
}
