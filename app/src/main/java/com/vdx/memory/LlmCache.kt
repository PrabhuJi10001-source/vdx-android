package com.vdx.memory

import android.util.LruCache

/**
 * LlmCache — simple in-memory LRU cache for LLM responses.
 *
 * Caches the most recent N transcript→intent mappings so that if the user
 * repeats the same command, we return instantly without a network call.
 *
 * This is especially useful for:
 * - Repeated commands ("call Mom" every evening)
 * - Accidental double-taps on the bubble
 * - Commands the user uses daily
 *
 * Cache is in-memory only (volatile). It resets when the app process dies,
 * which is fine — the Room DB is the durable store, this is just a speed layer.
 */
class LlmCache(maxSize: Int = 50) {

    /**
     * Cache entry with the raw LLM response JSON and a timestamp.
     */
    data class CacheEntry(
        val rawResponse: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val cache = object : LruCache<String, CacheEntry>(maxSize) {
        override fun sizeOf(key: String, value: CacheEntry): Int = 1
    }

    /**
     * Get a cached response for a transcript.
     * Returns null if not cached or if the entry is older than [maxAgeMs].
     */
    fun get(transcript: String, maxAgeMs: Long = 60_000L): String? {
        val normalized = normalize(transcript)
        val entry = cache.get(normalized) ?: return null
        val age = System.currentTimeMillis() - entry.timestamp
        if (age > maxAgeMs) {
            cache.remove(normalized)
            return null
        }
        return entry.rawResponse
    }

    /**
     * Store a response in the cache.
     */
    fun put(transcript: String, rawResponse: String) {
        val normalized = normalize(transcript)
        cache.put(normalized, CacheEntry(rawResponse))
    }

    /**
     * Clear the entire cache.
     */
    fun clear() {
        cache.evictAll()
    }

    /**
     * Remove a specific transcript from the cache.
     */
    fun remove(transcript: String) {
        cache.remove(normalize(transcript))
    }

    /**
     * Normalize a transcript for consistent cache keys.
     * Strips punctuation, lowercases, trims whitespace.
     */
    private fun normalize(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()
    }
}
