package com.vdx.sonic.intentir

import com.vdx.sonic.IntentMode
import com.vdx.sonic.IntentType
import com.vdx.sonic.SonicIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentIrV1Test {

    @Test
    fun callMom_staysSimple() {
        val sonic = SonicIntent(
            mode = IntentMode.COMMAND,
            type = IntentType.CALL,
            entities = mapOf("contact" to "Mom"),
            rawText = "Call Mom.",
            confidence = 0.95f,
        )
        val doc = IntentIrV1.fromSonic(sonic, intentId = "call-mom", revision = 1)
        assertEquals("1.0", doc["schema_version"])
        assertEquals("call-mom", doc["intent_id"])
        assertEquals(1, doc["revision"])
        val action = doc["action"] as Map<*, *>
        val obj = doc["object"] as Map<*, *>
        assertEquals("call", action["value"])
        assertEquals("Mom", obj["value"])
        assertFalse(doc.containsKey("domain"))
        assertFalse(doc.containsKey("slots"))
        assertFalse(doc.containsKey("preferences"))
        assertFalse(doc.containsKey("exclusions"))
        assertFalse(doc.containsKey("target"))
    }

    @Test
    fun sendThis_keepsDeixisUnresolved() {
        val sonic = SonicIntent(
            mode = IntentMode.COMMAND,
            type = IntentType.WHATSAPP,
            entities = mapOf("contact" to "Mom", "message" to "this"),
            rawText = "Send this to Mom.",
            confidence = 0.9f,
        )
        val doc = IntentIrV1.fromSonic(sonic, intentId = "send-mom", revision = 1)
        val action = doc["action"] as Map<*, *>
        val obj = doc["object"] as Map<*, *>
        val target = doc["target"] as Map<*, *>
        assertEquals("send", action["value"])
        assertEquals("Mom", obj["value"])
        assertEquals("WhatsApp", target["value"])
        @Suppress("UNCHECKED_CAST")
        val slots = doc["slots"] as List<Map<String, Any?>>
        val message = slots.first { it["name"] == "message" }
        assertEquals("unresolved", message["status"])
        assertNull(message["value"])
        assertEquals("this", message["surface"])
        assertFalse(doc.containsKey("domain"))
    }

    @Test
    fun unknownIntent_doesNotInventAction() {
        val sonic = SonicIntent(
            mode = IntentMode.COMMAND,
            type = IntentType.UNKNOWN,
            rawText = "Hmm.",
            confidence = 0.1f,
        )
        val doc = IntentIrV1.fromSonic(sonic, intentId = "hmm", revision = 1)
        assertEquals("1.0", doc["schema_version"])
        assertFalse(doc.containsKey("action"))
        assertFalse(doc.containsKey("object"))
        assertFalse(doc.containsKey("domain"))
        assertEquals("Hmm.", doc["source_text"])
    }

    @Test
    fun roundTrip_doesNotInventFields() {
        val sonic = SonicIntent(
            mode = IntentMode.COMMAND,
            type = IntentType.CALL,
            entities = mapOf("contact" to "Mom"),
            rawText = "Call Mom.",
        )
        val doc = IntentIrV1.fromSonic(sonic, intentId = "call-mom")
        assertFalse(doc.containsKey("goal"))
        assertFalse(doc.containsKey("desired_output"))
        assertFalse(doc.containsKey("authority"))
        assertFalse(doc.containsKey("success_conditions"))
        assertFalse(doc.containsKey("confidence"))
        assertFalse(doc.containsKey("capability_id"))
        val action = doc["action"] as Map<*, *>
        assertEquals("call", action["value"])
        assertTrue(doc.keys.all { it in ALLOWED })
    }

    @Test
    fun firstUnresolvedSurface_readsDeixis() {
        val deictic = SonicIntent(
            mode = IntentMode.COMMAND,
            type = IntentType.CALL,
            entities = mapOf("contact" to "her"),
            rawText = "Call her.",
        )
        assertEquals("her", IntentIrV1.firstUnresolvedSurface(deictic))
        val resolved = SonicIntent(
            mode = IntentMode.COMMAND,
            type = IntentType.CALL,
            entities = mapOf("contact" to "Mom"),
            rawText = "Call Mom.",
        )
        assertNull(IntentIrV1.firstUnresolvedSurface(resolved))
    }

    companion object {
        private val ALLOWED = setOf(
            "schema_version", "intent_id", "revision", "source_text",
            "action", "object", "target", "domain", "slots",
            "preferences", "exclusions",
        )
    }
}
