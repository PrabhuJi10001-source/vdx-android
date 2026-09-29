package com.vdx.sonic.voice

import android.content.Context
import android.content.pm.PackageManager
import com.vdx.sonic.EntityRepairResult
import com.vdx.sonic.EntitySource
import com.vdx.sonic.RepairedEntity
import com.vdx.sonic.ScreenModel

/**
 * EntityRepairEngine — context-aware entity repair for VDX Sonic.
 *
 * Fixes ASR-mangled entities (names, apps, places) using local context:
 * - Installed apps + known aliases
 * - Contact names (from phonebook)
 * - VDX vocabulary (Mem0)
 * - Current UI labels from Harness
 * - Phonetic matching as fallback
 */
class EntityRepairEngine(private val context: Context) {

    companion object {
        private const val TAG = "Sonic-EntityRepair"
        private const val PHONETIC_THRESHOLD = 0.7f
        private const val EXACT_CONFIDENCE = 0.99f
        private const val PHONETIC_CONFIDENCE = 0.75f
        private const val SUBSTRING_CONFIDENCE = 0.6f

        // Known app aliases — maps common mispronunciations to correct names
        private val APP_ALIASES = mapOf(
            "over" to "Uber",
            "uber" to "Uber",
            "youber" to "Uber",
            "what's app" to "WhatsApp",
            "whats app" to "WhatsApp",
            "what sapp" to "WhatsApp",
            "watsapp" to "WhatsApp",
            "insta" to "Instagram",
            "insta gram" to "Instagram",
            "face book" to "Facebook",
            "you tube" to "YouTube",
            "youtube" to "YouTube",
            "g mail" to "Gmail",
            "gee mail" to "Gmail",
            "chrome" to "Chrome",
            "google maps" to "Maps",
            "maps" to "Maps",
            "settings" to "Settings",
            "camera" to "Camera",
            "phone" to "Phone",
            "dialer" to "Phone",
            "messages" to "Messages",
            "messaging" to "Messages",
            "clock" to "Clock",
            "alarm" to "Clock",
            "calculator" to "Calculator",
            "calendar" to "Calendar",
            "contacts" to "Contacts",
            "play store" to "Play Store",
            "playstore" to "Play Store",
            "spotify" to "Spotify",
            "netflix" to "Netflix",
            "twitter" to "X",
            "x" to "X"
        )
    }

    private val packageManager = context.packageManager

    // Cache of installed app names (refreshed on demand)
    private var installedApps: List<String>? = null
    private var installedAppsTime: Long = 0
    private val APP_CACHE_TTL_MS = 30_000L

    /**
     * Repair entities in the given transcript using available context.
     */
    suspend fun repair(
        transcript: String,
        screenModel: ScreenModel?,
        vocabulary: List<String>?,
        contacts: List<String>?
    ): EntityRepairResult {
        val words = transcript.split(" ").toMutableList()
        val entities = mutableListOf<RepairedEntity>()
        val apps = getInstalledApps()
        val uiLabels = extractUiLabels(screenModel)

        // Try multi-word phrases first (longest match wins)
        val phrases = extractPhrases(transcript)
        val repairedPhrases = mutableListOf<Pair<String, String>>() // original → repaired

        for (phrase in phrases) {
            val repaired = repairPhrase(phrase, apps, contacts ?: emptyList(), vocabulary ?: emptyList(), uiLabels)
            if (repaired != phrase) {
                repairedPhrases.add(phrase to repaired)
            }
        }

        // Apply repairs to transcript
        var repairedText = transcript
        for ((original, repaired) in repairedPhrases.sortedByDescending { it.first.length }) {
            repairedText = repairedText.replace(original, repaired, ignoreCase = true)
            entities.add(
                RepairedEntity(
                    original = original,
                    repaired = repaired,
                    source = determineSource(repaired, apps, contacts ?: emptyList(), vocabulary ?: emptyList()),
                    confidence = EXACT_CONFIDENCE
                )
            )
        }

        // Single-word entity repair for remaining words
        val remainingWords = repairedText.split(" ").toMutableList()
        for (i in remainingWords.indices) {
            val word = remainingWords[i]
            if (word.length < 2) continue

            // Check if already repaired
            if (entities.any { it.repaired.equals(word, ignoreCase = true) }) continue

            val repaired = repairWord(word, apps, contacts ?: emptyList(), vocabulary ?: emptyList(), uiLabels)
            if (repaired != word) {
                remainingWords[i] = repaired
                entities.add(
                    RepairedEntity(
                        original = word,
                        repaired = repaired,
                        source = determineSource(repaired, apps, contacts ?: emptyList(), vocabulary ?: emptyList()),
                        confidence = PHONETIC_CONFIDENCE
                    )
                )
            }
        }

        repairedText = remainingWords.joinToString(" ")

        val overallConfidence = if (entities.isEmpty()) {
            1.0f // no entities to repair
        } else {
            entities.map { it.confidence }.average().toFloat()
        }

        return EntityRepairResult(
            repairedText = repairedText,
            originalText = transcript,
            entities = entities,
            overallConfidence = overallConfidence
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Phrase-level repair
    // ──────────────────────────────────────────────────────────────

    private fun extractPhrases(text: String): List<String> {
        val words = text.split(" ")
        val phrases = mutableListOf<String>()

        // 2-word and 3-word phrases
        for (len in 3 downTo 2) {
            for (i in 0..words.size - len) {
                phrases.add(words.subList(i, i + len).joinToString(" "))
            }
        }
        // Single words
        phrases.addAll(words)

        return phrases.distinct()
    }

    private fun repairPhrase(
        phrase: String,
        apps: List<String>,
        contacts: List<String>,
        vocabulary: List<String>,
        uiLabels: List<String>
    ): String {
        val lower = phrase.lowercase()

        // 1. Check app aliases
        APP_ALIASES.entries.firstOrNull { (key, _) ->
            lower == key || lower.contains(key)
        }?.let { return it.value }

        // 2. Check installed apps (exact match)
        apps.firstOrNull { it.lowercase() == lower }?.let { return it }

        // 3. Check contacts (exact match)
        contacts.firstOrNull { it.lowercase() == lower }?.let { return it }

        // 4. Check vocabulary
        vocabulary.firstOrNull { it.lowercase() == lower }?.let { return it }

        // 5. Check UI labels
        uiLabels.firstOrNull { it.lowercase() == lower }?.let { return it }

        // 6. Phonetic match against apps
        apps.firstOrNull { phoneticSimilarity(lower, it.lowercase()) > PHONETIC_THRESHOLD }
            ?.let { return it }

        // 7. Phonetic match against contacts
        contacts.firstOrNull { phoneticSimilarity(lower, it.lowercase()) > PHONETIC_THRESHOLD }
            ?.let { return it }

        return phrase
    }

    // ──────────────────────────────────────────────────────────────
    // Word-level repair
    // ──────────────────────────────────────────────────────────────

    private fun repairWord(
        word: String,
        apps: List<String>,
        contacts: List<String>,
        vocabulary: List<String>,
        uiLabels: List<String>
    ): String {
        val lower = word.lowercase()

        // 1. App aliases
        APP_ALIASES.entries.firstOrNull { (key, _) -> lower == key }?.let { return it.value }

        // 2. Exact match against apps
        apps.firstOrNull { it.lowercase() == lower }?.let { return it }

        // 3. Exact match against contacts
        contacts.firstOrNull { it.lowercase() == lower }?.let { return it }

        // 4. Vocabulary
        vocabulary.firstOrNull { it.lowercase() == lower }?.let { return it }

        // 5. UI labels
        uiLabels.firstOrNull { it.lowercase() == lower }?.let { return it }

        // 6. Phonetic
        apps.firstOrNull { phoneticSimilarity(lower, it.lowercase()) > PHONETIC_THRESHOLD }
            ?.let { return it }
        contacts.firstOrNull { phoneticSimilarity(lower, it.lowercase()) > PHONETIC_THRESHOLD }
            ?.let { return it }

        return word
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private fun determineSource(
        repaired: String,
        apps: List<String>,
        contacts: List<String>,
        vocabulary: List<String>
    ): EntitySource {
        if (APP_ALIASES.values.contains(repaired)) return EntitySource.INSTALLED_APP
        if (apps.any { it.equals(repaired, ignoreCase = true) }) return EntitySource.INSTALLED_APP
        if (contacts.any { it.equals(repaired, ignoreCase = true) }) return EntitySource.CONTACT
        if (vocabulary.any { it.equals(repaired, ignoreCase = true) }) return EntitySource.VOCABULARY
        return EntitySource.PHONETIC_MATCH
    }

    private fun extractUiLabels(screen: ScreenModel?): List<String> {
        if (screen == null) return emptyList()
        return screen.elements.mapNotNull { it.text ?: it.contentDescription ?: it.hint }
    }

    private fun getInstalledApps(): List<String> {
        val now = System.currentTimeMillis()
        if (installedApps != null && now - installedAppsTime < APP_CACHE_TTL_MS) {
            return installedApps!!
        }

        val apps = mutableListOf<String>()
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfo = packageManager.queryIntentActivities(intent, 0)
        for (info in resolveInfo) {
            val label = info.loadLabel(packageManager).toString()
            apps.add(label)
            // Also add the package name as a fallback
            apps.add(info.activityInfo.packageName)
        }

        installedApps = apps.distinct()
        installedAppsTime = now
        return installedApps!!
    }

    /**
     * Simple phonetic similarity using Dice coefficient on character bigrams.
     * Fast, no external dependencies.
     */
    private fun phoneticSimilarity(a: String, b: String): Float {
        if (a == b) return 1.0f
        if (a.isEmpty() || b.isEmpty()) return 0.0f

        val bigramsA = a.windowed(2).toSet()
        val bigramsB = b.windowed(2).toSet()
        val intersection = bigramsA.intersect(bigramsB).size
        val union = bigramsA.size + bigramsB.size
        return (2.0f * intersection) / union
    }
}
