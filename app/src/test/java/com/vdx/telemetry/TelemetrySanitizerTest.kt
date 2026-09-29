package com.vdx.telemetry

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * TelemetrySanitizerTest — verifies the hard allowlist gate that every event
 * must pass before it is queued or leaves the device.
 *
 * Two guarantees:
 *  1. Forbidden fields (transcript, contact, message, key, raw text, search
 *     query, screen content, package names) are DROPPED — defense in depth.
 *  2. Allowlisted event shapes serialize intact (type + allowlisted fields).
 */
class TelemetrySanitizerTest {

    @Before
    fun resetState() {
        // The sanitizer is stateless; nothing to reset. Kept for clarity.
    }

    @Test
    fun forbiddenFields_areStrippedFromIntentParsed() {
        val result = TelemetrySanitizer.sanitize(
            TelemetryEventTypes.INTENT_PARSED,
            mapOf(
                "type" to "CALL",
                "transcript" to "call my mother right now please",
                "contact" to "Mom",
                "raw_text" to "call mom",
                "phone" to "+91-555-1234",
                "message_body" to "hey can you come over",
                "api_key" to "sk-secret-123",
                "screen_content" to "dialer keypad 1 2 3"
            )
        )

        // Only the allowlisted "type" survives.
        assertEquals(1, result.fields.size)
        assertEquals("CALL", result.fields["type"])
        // Every forbidden key was dropped.
        assertFalse(result.fields.containsKey("transcript"))
        assertFalse(result.fields.containsKey("contact"))
        assertFalse(result.fields.containsKey("raw_text"))
        assertFalse(result.fields.containsKey("phone"))
        assertFalse(result.fields.containsKey("message_body"))
        assertFalse(result.fields.containsKey("api_key"))
        assertFalse(result.fields.containsKey("screen_content"))
    }

    @Test
    fun forbiddenFields_areStrippedFromExecutionResult() {
        val result = TelemetrySanitizer.sanitize(
            TelemetryEventTypes.EXECUTION_RESULT,
            mapOf(
                "intent_type" to "WHATSAPP",
                "status" to "success",
                "duration_ms" to 1234,
                "transcript" to "send message to mom",
                "contact" to "Mom",
                "package" to "com.whatsapp",
                "message_body" to "hello"
            )
        )

        assertEquals(3, result.fields.size)
        assertEquals("WHATSAPP", result.fields["intent_type"])
        assertEquals("success", result.fields["status"])
        assertEquals(1234, result.fields["duration_ms"])
        assertFalse(result.fields.containsKey("transcript"))
        assertFalse(result.fields.containsKey("contact"))
        assertFalse(result.fields.containsKey("package"))
        assertFalse(result.fields.containsKey("message_body"))
    }

    @Test
    fun forbiddenFields_areStrippedFromKeyAdded() {
        val result = TelemetrySanitizer.sanitize(
            TelemetryEventTypes.KEY_ADDED,
            mapOf(
                "provider" to "gemini",
                "valid" to true,
                "api_key" to "sk-secret-456",
                "key_preview" to "sk-s..."
            )
        )
        assertEquals(2, result.fields.size)
        assertEquals("gemini", result.fields["provider"])
        assertEquals(true, result.fields["valid"])
        assertFalse(result.fields.containsKey("api_key"))
        assertFalse(result.fields.containsKey("key_preview"))
    }

    @Test
    fun unknownEventType_isEntirelyDropped() {
        val result = TelemetrySanitizer.sanitize("totally_unknown_event", mapOf("foo" to "bar"))
        assertTrue(result.fields.isEmpty())
    }

    @Test
    fun installAndConsent_allowNoFields() {
        for (type in listOf(
            TelemetryEventTypes.INSTALL,
            TelemetryEventTypes.CONSENT_GRANTED,
            TelemetryEventTypes.CONSENT_DENIED
        )) {
            val result = TelemetrySanitizer.sanitize(type, mapOf("extra" to "x", "user" to "Bob"))
            assertTrue("$type must carry no fields", result.fields.isEmpty())
        }
    }

    @Test
    fun status_isEnumRestricted() {
        val ok = TelemetrySanitizer.sanitize(
            TelemetryEventTypes.EXECUTION_RESULT,
            mapOf("status" to "success")
        )
        assertEquals("success", ok.fields["status"])

        val bad = TelemetrySanitizer.sanitize(
            TelemetryEventTypes.EXECUTION_RESULT,
            mapOf("status" to "SUCCESS_WITH_SIDE_EFFECT_TRANSCRIPT")
        )
        assertFalse(bad.fields.containsKey("status"))
    }

    @Test
    fun engineSelected_fields_areEnumRestricted() {
        val ok = TelemetrySanitizer.sanitize(
            TelemetryEventTypes.ENGINE_SELECTED,
            mapOf("asr" to "sarvam", "llm" to "gemini", "key" to "byok")
        )
        assertEquals("sarvam", ok.fields["asr"])
        assertEquals("gemini", ok.fields["llm"])
        assertEquals("byok", ok.fields["key"])

        val bad = TelemetrySanitizer.sanitize(
            TelemetryEventTypes.ENGINE_SELECTED,
            mapOf("asr" to "google-cloud-whisper-v3", "llm" to "my-custom-llm", "key" to "sk-123")
        )
        assertFalse(bad.fields.containsKey("asr"))
        assertFalse(bad.fields.containsKey("llm"))
        assertFalse(bad.fields.containsKey("key"))
    }

    @Test
    fun safeIntentLabel_blocksFreeText() {
        // Coarse enums pass.
        assertEquals("CALL", TelemetrySanitizer.safeIntentLabel("CALL"))
        assertEquals("WHATSAPP", TelemetrySanitizer.safeIntentLabel("WHATSAPP"))
        // Free text / content / transcripts are rejected.
        assertNull(TelemetrySanitizer.safeIntentLabel("call my mom right now"))
        assertNull(TelemetrySanitizer.safeIntentLabel("send message body"))
        assertNull(TelemetrySanitizer.safeIntentLabel(""))
        assertNull(TelemetrySanitizer.safeIntentLabel(null))
    }

    @Test
    fun crashFields_areRestricted() {
        val ok = TelemetrySanitizer.sanitize(
            TelemetryEventTypes.CRASH,
            mapOf(
                "exception_class" to "java.lang.NullPointerException",
                "stack_top3_frames" to "com.vdx.MainActivity.method:10 | com.vdx.SonicEngine:20"
            )
        )
        assertEquals("java.lang.NullPointerException", ok.fields["exception_class"])
        assertNotNull(ok.fields["stack_top3_frames"])

        // Message body or transcript must not ride along under a crash.
        val bad = TelemetrySanitizer.sanitize(
            TelemetryEventTypes.CRASH,
            mapOf("exception_class" to "java.lang.Error", "last_message" to "call mom about rent")
        )
        assertFalse(bad.fields.containsKey("last_message"))
    }
}
