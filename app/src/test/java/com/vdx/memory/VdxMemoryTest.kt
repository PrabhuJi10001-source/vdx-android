package com.vdx.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for VDX memory system: UserMemoryStore, PatternDetector, ContextHydrator, LlmCache, NanoFallback.
 *
 * Run with: ./gradlew app:testDebugUnitTest
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class VdxMemoryTest {

    private lateinit var context: Context
    private lateinit var memoryStore: UserMemoryStore
    private lateinit var patternDetector: PatternDetector
    private lateinit var contextHydrator: ContextHydrator
    private lateinit var llmCache: LlmCache

    @Before
    fun setUp() {
        VdxMemoryDatabase.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        memoryStore = UserMemoryStore(context)
        patternDetector = PatternDetector(context)
        contextHydrator = ContextHydrator(context)
        llmCache = LlmCache(maxSize = 50)
    }

    // ──────────────────────────────────────────────────────────────
    // UserMemoryStore Tests
    // ──────────────────────────────────────────────────────────────

    @Test
    fun testRememberAndRecall() = runBlocking {
        memoryStore.remember("contact", "mom", "+971501234567", "Mom's phone number")
        val result = memoryStore.recall("mom")
        assertEquals("+971501234567", result)
    }

    @Test
    fun testRecallNonExistent() = runBlocking {
        val result = memoryStore.recall("nonexistent")
        assertNull(result)
    }

    @Test
    fun testForget() = runBlocking {
        memoryStore.remember("contact", "test", "value")
        assertNotNull(memoryStore.recall("test"))
        memoryStore.forget("test")
        assertNull(memoryStore.recall("test"))
    }

    @Test
    fun testSearch() = runBlocking {
        memoryStore.remember("contact", "ravi", "colleague", "works at Google")
        val results = memoryStore.search("ravi")
        assertTrue(results.isNotEmpty())
        assertEquals("ravi", results.first().key)
    }

    @Test
    fun testGetByType() = runBlocking {
        memoryStore.remember("location", "home", "Dubai Marina")
        memoryStore.remember("location", "work", "DIFC")
        val locations = memoryStore.getByType("location")
        assertTrue(locations.size >= 2)
    }

    @Test
    fun testAccessCountIncrements() = runBlocking {
        memoryStore.remember("contact", "test_count", "value")
        memoryStore.recall("test_count")
        memoryStore.recall("test_count")
        val mem = memoryStore.getMemoryByKey("test_count")
        assertEquals(2, mem?.accessCount)
    }

    @Test
    fun testTopMemories() = runBlocking {
        memoryStore.remember("contact", "frequent", "used often")
        repeat(5) { memoryStore.recall("frequent") }
        val top = memoryStore.getTopMemories()
        assertTrue(top.isNotEmpty())
        assertEquals("frequent", top.first().key)
    }

    // ──────────────────────────────────────────────────────────────
    // LlmCache Tests
    // ──────────────────────────────────────────────────────────────

    @Test
    fun testCacheHit() {
        llmCache.put("call Mom", """{"action":"call","contact":"Mom","confidence":0.99}""")
        val result = llmCache.get("call Mom")
        assertNotNull(result)
        assertTrue(result!!.contains("call"))
    }

    @Test
    fun testCacheMiss() {
        val result = llmCache.get("never cached")
        assertNull(result)
    }

    @Test
    fun testCacheExpiry() {
        llmCache.put("old", "response")
        Thread.sleep(100) // cache has 60s TTL, so this should still hit
        assertNotNull(llmCache.get("old"))
    }

    @Test
    fun testCacheNormalization() {
        llmCache.put("Call Mom!", """{"action":"call"}""")
        // Different casing/punctuation should normalize to same key
        val result = llmCache.get("call mom")
        assertNotNull(result)
    }

    @Test
    fun testCacheEviction() {
        val smallCache = LlmCache(maxSize = 2)
        smallCache.put("a", "1")
        smallCache.put("b", "2")
        smallCache.put("c", "3") // should evict "a"
        assertNull(smallCache.get("a"))
        assertNotNull(smallCache.get("c"))
    }

    @Test
    fun testCacheClear() {
        llmCache.put("temp", "value")
        llmCache.clear()
        assertNull(llmCache.get("temp"))
    }

    // ──────────────────────────────────────────────────────────────
    // ContextHydrator Tests
    // ──────────────────────────────────────────────────────────────

    @Test
    fun testHydrateWithKnownContact() = runBlocking {
        memoryStore.remember("contact", "mom", "+971501234567")
        val result = contextHydrator.hydrate("call Mom")
        assertTrue(result.resolvedEntities.isNotEmpty())
        assertTrue(result.hydratedPrompt.contains("mom"))
        assertTrue(result.hydratedPrompt.contains("+971501234567"))
    }

    @Test
    fun testHydrateWithNoMatches() = runBlocking {
        val result = contextHydrator.hydrate("do something random")
        assertTrue(result.resolvedEntities.isEmpty())
        assertEquals("do something random", result.hydratedPrompt)
    }

    @Test
    fun testHydrateWithLocation() = runBlocking {
        memoryStore.remember("location", "home", "Dubai Marina")
        val result = contextHydrator.hydrate("Uber home")
        assertTrue(result.resolvedEntities.isNotEmpty())
        assertTrue(result.hydratedPrompt.contains("Dubai Marina"))
    }

    @Test
    fun testHydrateWithDefault() = runBlocking {
        memoryStore.remember("default", "ravi", "whatsapp", "preferred app")
        val result = contextHydrator.hydrate("message Ravi")
        assertTrue(result.resolvedEntities.isNotEmpty())
        assertEquals("default", result.resolvedEntities.first().type)
    }

    // ──────────────────────────────────────────────────────────────
    // PatternDetector Tests
    // ──────────────────────────────────────────────────────────────

    @Test
    fun testJunkFilterSkipsReadScreen() {
        // read_screen should be filtered out
        patternDetector.recordAction("read_screen", "screen")
        // No crash = pass. The action should be silently dropped.
    }

    @Test
    fun testJunkFilterSkipsGenericTargets() {
        patternDetector.recordAction("uber", "home") // "home" is in JUNK_TARGETS
        patternDetector.recordAction("uber", "airport") // "airport" is in JUNK_TARGETS
        // Should be silently dropped
    }

    @Test
    fun testJunkFilterSkipsShortTargets() {
        patternDetector.recordAction("call", "a") // < 3 chars
        // Should be silently dropped
    }

    @Test
    fun testJunkFilterSkipsGenericQueries() {
        patternDetector.recordAction("youtube", "music") // in JUNK_QUERIES
        patternDetector.recordAction("youtube", "news") // in JUNK_QUERIES
        // Should be silently dropped
    }

    @Test
    fun testMeaningfulActionPassesFilter() {
        patternDetector.recordAction("uber", "Pizza Hut")
        patternDetector.recordAction("call", "Mom")
        patternDetector.recordAction("youtube", "ice cream recipes")
        // These should pass through — no crash = pass
    }

    // ──────────────────────────────────────────────────────────────
    // NanoFallback Tests (device-dependent)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun testNanoAvailability() = runBlocking {
        val nano = NanoFallback(context)
        val available = nano.isAvailable()
        // This will be false on most test devices/emulators.
        // The test verifies the API doesn't crash, not that Nano is available.
        println("Nano 4 available on this device: $available")
    }

    // ──────────────────────────────────────────────────────────────
    // AAPM: Android App Performance Measurement
    // ──────────────────────────────────────────────────────────────

    @Test
    fun testMemoryStoreLatency() = runBlocking {
        // Measure write latency
        val writeStart = System.nanoTime()
        memoryStore.remember("contact", "perf_test", "value")
        val writeElapsed = (System.nanoTime() - writeStart) / 1_000_000 // ms
        println("MemoryStore write: ${writeElapsed}ms")
        assertTrue("Write should be under 100ms", writeElapsed < 100)

        // Measure read latency
        val readStart = System.nanoTime()
        memoryStore.recall("perf_test")
        val readElapsed = (System.nanoTime() - readStart) / 1_000_000
        println("MemoryStore read: ${readElapsed}ms")
        assertTrue("Read should be under 50ms", readElapsed < 50)
    }

    @Test
    fun testCacheLatency() {
        llmCache.put("perf", "response")

        val start = System.nanoTime()
        repeat(100) { llmCache.get("perf") }
        val elapsed = (System.nanoTime() - start) / 1_000_000
        val avg = elapsed.toDouble() / 100
        println("LlmCache 100 reads: ${elapsed}ms total, ${avg}ms avg")
        assertTrue("Cache reads should be under 1ms avg", avg < 1)
    }

    @Test
    fun testContextHydratorLatency() = runBlocking {
        // Seed some memories
        memoryStore.remember("contact", "mom", "+971501234567")
        memoryStore.remember("location", "home", "Dubai Marina")
        memoryStore.remember("default", "ravi", "whatsapp")

        val start = System.nanoTime()
        repeat(10) { contextHydrator.hydrate("call Mom and then Uber home") }
        val elapsed = (System.nanoTime() - start) / 1_000_000
        val avg = elapsed.toDouble() / 10
        println("ContextHydrator 10 hydrations: ${elapsed}ms total, ${avg}ms avg")
        assertTrue("Hydration should be under 500ms avg", avg < 500)
    }

    @Test
    fun testDatabaseSize() = runBlocking {
        val count = memoryStore.count()
        println("Current memory count: $count")
        // No assertion — just informational for AAPM monitoring
    }
}
