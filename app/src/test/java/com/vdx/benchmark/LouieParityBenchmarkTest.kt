package com.vdx.benchmark

import com.vdx.sonic.IntentType
import com.vdx.sonic.flows.LouieFlowCatalog
import com.vdx.sonic.voice.IntentParser
import com.vdx.sonic.voice.LocalCleanupEngine
import org.junit.Assert.*
import org.junit.Test

/**
 * Louie parity gates — understanding + non-empty Louie-depth plans.
 */
class LouieParityBenchmarkTest {

    private val parser = IntentParser()

    data class Case(val spoken: String, val type: IntentType, val entityKey: String? = null, val entityHas: String? = null)

    private val cases = listOf(
        Case("uh message Ravi on WhatsApp that I'll be late", IntentType.WHATSAPP, "contact", "Ravi"),
        Case("WhatsApp voice call Mom", IntentType.WHATSAPP, "action", "call"),
        Case("book me an Uber home", IntentType.BOOK_RIDE, "destination", "home"),
        Case("cancel my Uber", IntentType.BOOK_RIDE, "action", "cancel"),
        Case("call Mom", IntentType.CALL, "contact", "Mom"),
        Case("open YouTube and play bhajans", IntentType.YOUTUBE_SEARCH, "query", "bhajans"),
        Case("like this video", IntentType.YOUTUBE_CONTROL, "action", "like"),
        Case("read my email", IntentType.EMAIL, "action", "read"),
        Case("email Ravi saying deck is ready", IntentType.EMAIL, "contact", "Ravi"),
        Case("sms Mom that I'm on my way", IntentType.SMS, "contact", "Mom"),
        Case("search play store for signal", IntentType.PLAY_STORE, "query", "signal"),
        Case("install WhatsApp", IntentType.PLAY_STORE, "action", "install"),
        Case("turn on wifi", IntentType.SYSTEM_TOGGLE, "target", "wifi"),
        Case("what's my battery", IntentType.SYSTEM_QUERY),
        Case("set an alarm for 7:30 am", IntentType.SET_ALARM, "hour", "7"),
        Case("search for nearby pharmacy", IntentType.SEARCH, "query", "pharmacy"),
        Case("open WiFi settings", IntentType.SETTINGS_NAVIGATION, "section", "wifi"),
        Case("save contact Anika", IntentType.CONTACT_MANAGE, "action", "create"),
        Case("read my screen", IntentType.READ_SCREEN),
        Case("go back", IntentType.GO_BACK),
    )

    @Test
    fun louieCorpus_understandingAtLeast90Percent() {
        var pass = 0
        for (c in cases) {
            val intent = parser.parse(LocalCleanupEngine.clean(c.spoken))
            val typeOk = intent.type == c.type
            val entOk = c.entityKey == null ||
                intent.entities[c.entityKey].orEmpty().contains(c.entityHas ?: "", ignoreCase = true)
            if (typeOk && entOk) pass++ else {
                println("FAIL '${c.spoken}' -> ${intent.type} ${intent.entities} expected ${c.type}")
            }
        }
        val pct = pass * 100.0 / cases.size
        println("Louie understanding: $pass/${cases.size} = $pct%")
        assertTrue("Need ≥90% Louie corpus understanding", pct >= 90.0)
    }

    @Test
    fun louieFlows_produceNonEmptyPlans() {
        for (c in cases) {
            val intent = parser.parse(LocalCleanupEngine.clean(c.spoken))
            if (intent.type == IntentType.UNKNOWN) continue
            val plan = LouieFlowCatalog.plan(intent)
            assertTrue("Empty plan for ${c.spoken}", plan.steps.isNotEmpty())
        }
    }

    @Test
    fun productSpecOneShots_stillPass() {
        val product = listOf(
            Case("uh message Ravi on WhatsApp that I'll be 10 minutes late", IntentType.WHATSAPP, "contact", "Ravi"),
            Case("book me an Uber home", IntentType.BOOK_RIDE, "destination", "home"),
            Case("call Mom", IntentType.CALL, "contact", "Mom"),
            Case("open YouTube and play bhajans", IntentType.YOUTUBE_SEARCH, "query", "bhajans"),
        )
        for (c in product) {
            val intent = parser.parse(LocalCleanupEngine.clean(c.spoken))
            assertEquals("type for '${c.spoken}' got ents=${intent.entities}", c.type, intent.type)
            assertTrue(
                "entity ${c.entityKey} for '${c.spoken}' in ${intent.entities}",
                intent.entities[c.entityKey].orEmpty().contains(c.entityHas!!, ignoreCase = true)
            )
        }
    }
}
