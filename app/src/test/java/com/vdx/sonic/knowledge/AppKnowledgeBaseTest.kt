package com.vdx.sonic.knowledge

import androidx.test.core.app.ApplicationProvider
import com.vdx.memory.VdxMemoryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for AppKnowledgeBase — serialization, seed data, and CRUD operations.
 */
@RunWith(RobolectricTestRunner::class)
class AppKnowledgeBaseTest {

    private lateinit var db: VdxMemoryDatabase
    private lateinit var knowledgeBase: AppKnowledgeBase

    @Before
    fun setup() {
        VdxMemoryDatabase.resetForTests()
        db = VdxMemoryDatabase.getInstance(ApplicationProvider.getApplicationContext())
        knowledgeBase = AppKnowledgeBase(db.appKnowledgeDao())
    }

    @After
    fun cleanup() {
        db.close()
        VdxMemoryDatabase.resetForTests()
    }

    @Test
    fun seedIfEmpty_populatesKnowledgeForCoreApps() = runBlocking {
        knowledgeBase.seedIfEmpty()

        assertTrue(knowledgeBase.hasKnowledge("com.whatsapp", "send_message", "en"))
        assertTrue(knowledgeBase.hasKnowledge("com.whatsapp", "open_chat", "en"))
        assertTrue(knowledgeBase.hasKnowledge("com.android.dialer", "make_call", "en"))
        assertTrue(knowledgeBase.hasKnowledge("com.ubercab", "book_ride", "en"))
        assertTrue(knowledgeBase.hasKnowledge("com.android.chrome", "search", "en"))
        assertTrue(knowledgeBase.hasKnowledge("com.android.settings", "open_wifi", "en"))
        assertTrue(knowledgeBase.hasKnowledge("com.android.settings", "open_bluetooth", "en"))
    }

    @Test
    fun seedIfEmpty_isIdempotent() = runBlocking {
        knowledgeBase.seedIfEmpty()
        val countAfterFirstSeed = db.appKnowledgeDao().count()

        // Second call should NOT duplicate entries.
        knowledgeBase.seedIfEmpty()
        val countAfterSecondSeed = db.appKnowledgeDao().count()

        assertEquals(countAfterFirstSeed, countAfterSecondSeed)
    }

    @Test
    fun getSteps_returnsStepsForKnownAppAction() = runBlocking {
        knowledgeBase.seedIfEmpty()

        val steps = knowledgeBase.getSteps("com.whatsapp", "send_message", "en")
        assertNotNull(steps)
        assertTrue(steps!!.isNotEmpty())

        // First step should be about opening WhatsApp
        assertTrue(steps[0].description.contains("WhatsApp"))
    }

    @Test
    fun getSteps_returnsNullForUnknownAppAction() = runBlocking {
        knowledgeBase.seedIfEmpty()

        val steps = knowledgeBase.getSteps("com.unknown.app", "unknown_action", "en")
        assertNull(steps)
    }

    @Test
    fun putKnowledge_storesAndRetrievesSteps() = runBlocking {
        val customSteps = listOf(
            NavStep("Step 1", "Button1", NavStep.ACTION_TAP),
            NavStep("Step 2", "Field1", NavStep.ACTION_SET_TEXT, text = "hello"),
            NavStep("Step 3", null, NavStep.ACTION_WAIT, waitMs = 3000)
        )

        knowledgeBase.putKnowledge("com.test.app", "test_action", customSteps, "en")

        val retrieved = knowledgeBase.getSteps("com.test.app", "test_action", "en")
        assertNotNull(retrieved)
        assertEquals(3, retrieved!!.size)
        assertEquals("Step 1", retrieved[0].description)
        assertEquals("Button1", retrieved[0].targetElement)
        assertEquals(NavStep.ACTION_TAP, retrieved[0].action)
        assertEquals("hello", retrieved[1].text)
        assertEquals(3000L, retrieved[2].waitMs)
    }

    @Test
    fun hasKnowledge_returnsFalseForUnknownEntry() = runBlocking {
        assertFalse(knowledgeBase.hasKnowledge("com.unknown.app", "unknown", "en"))
    }

    @Test
    fun serializeDeserialize_isRoundTripSafe() {
        val original = listOf(
            NavStep("Test", "Target", NavStep.ACTION_TAP),
            NavStep("Wait", null, NavStep.ACTION_WAIT, waitMs = 5000),
            NavStep("Type", "Field", NavStep.ACTION_SET_TEXT, text = "hello world"),
            NavStep("Scroll", null, NavStep.ACTION_SCROLL, scrollDirection = "DOWN")
        )

        val json = knowledgeBase.serializeSteps(original)
        val deserialized = knowledgeBase.deserializeSteps(json)

        assertEquals(original.size, deserialized.size)
        assertEquals(original[0].description, deserialized[0].description)
        assertEquals(original[0].targetElement, deserialized[0].targetElement)
        assertEquals(original[0].action, deserialized[0].action)
        assertEquals(original[1].waitMs, deserialized[1].waitMs)
        assertEquals(original[2].text, deserialized[2].text)
        assertEquals(original[3].scrollDirection, deserialized[3].scrollDirection)
    }

    @Test
    fun knowledgeSeed_hasAllExpectedApps() {
        assertTrue(KnowledgeSeed.allSeeds.containsKey("com.whatsapp"))
        assertTrue(KnowledgeSeed.allSeeds.containsKey("com.android.dialer"))
        assertTrue(KnowledgeSeed.allSeeds.containsKey("com.ubercab"))
        assertTrue(KnowledgeSeed.allSeeds.containsKey("com.android.chrome"))
        assertTrue(KnowledgeSeed.allSeeds.containsKey("com.android.settings"))

        // WhatsApp should have send_message and open_chat
        val waActions = KnowledgeSeed.allSeeds["com.whatsapp"]!!
        assertTrue(waActions.containsKey("send_message"))
        assertTrue(waActions.containsKey("open_chat"))

        // Settings should have open_wifi and open_bluetooth
        val settingsActions = KnowledgeSeed.allSeeds["com.android.settings"]!!
        assertTrue(settingsActions.containsKey("open_wifi"))
        assertTrue(settingsActions.containsKey("open_bluetooth"))
    }
}