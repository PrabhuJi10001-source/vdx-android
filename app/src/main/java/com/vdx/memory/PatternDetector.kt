package com.vdx.memory

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * PatternDetector — scans action logs for recurring usage patterns
 * and stores them as memories (Veda's pattern detection, but from app usage).
 *
 * Runs after every action execution. Detects:
 * - Repeated actions to the same target (calls Mom every evening)
 * - Repeated action types at similar times (Uber Eats on Fridays)
 * - Repeated search queries (YouTube for "ice cream" repeatedly)
 *
 * When a pattern crosses the threshold (3+ times in 7 days), it's stored
 * as a `habit` memory so VDX can proactively suggest it.
 *
 * Junk filter: skips noise actions that don't represent meaningful user behavior.
 * Only logs actions that reveal genuine preferences or habits.
 */
class PatternDetector(private val context: Context) {

    private val dao = VdxMemoryDatabase.getInstance(context).actionLogDao()
    private val memoryStore = MemoryStore(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        // Threshold: 3+ same action+target in 7 days = a pattern
        private const val PATTERN_THRESHOLD = 3
        private const val PATTERN_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
        private const val MAX_LOG_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 30 days retention

        // Junk targets — actions against these are noise, not patterns
        private val JUNK_TARGETS = setOf(
            "unknown", "home", "work", "airport", "default",
            "settings", "phone", "messages", "clock", "calculator",
            "camera", "gallery", "files", "my files", "downloads",
        )

        // Junk actions — these are system-level, not user preference signals
        private val JUNK_ACTIONS = setOf(
            "read_screen", "app_launch",
        )

        // Junk search queries — generic terms that don't reveal preferences
        private val JUNK_QUERIES = setOf(
            "cat videos", "music", "news", "weather", "today",
            "trending", "popular", "new", "latest",
        )
    }

    /**
     * Log an action and check for patterns. Call this after every RobotHand execution.
     * Skips junk actions that don't represent meaningful user behavior.
     */
    fun recordAction(action: String, target: String, detail: String = "") {
        if (isJunk(action, target)) return

        scope.launch {
            dao.insert(ActionLog(action = action, target = target, detail = detail))
            detectPatterns()
            // Auto-purge logs older than 30 days
            dao.deleteOlderThan(System.currentTimeMillis() - MAX_LOG_AGE_MS)
        }
    }

    /**
     * Returns true if this action is noise and should not be logged.
     * Prevents the system from gathering stupid info.
     */
    private fun isJunk(action: String, target: String): Boolean {
        val t = target.lowercase().trim()
        val a = action.lowercase().trim()

        // Skip junk actions entirely
        if (a in JUNK_ACTIONS) return true

        // Skip junk targets
        if (t in JUNK_TARGETS) return true

        // Skip junk search queries
        if (a == "youtube" && t in JUNK_QUERIES) return true
        if (a == "uber" && t in JUNK_TARGETS) return true

        // Skip very short targets (likely typos or partial input)
        if (t.length < 3) return true

        return false
    }

    /**
     * Scan recent action logs for frequency patterns.
     * If a pattern crosses the threshold, store it as a habit memory.
     */
    private suspend fun detectPatterns() {
        val since = System.currentTimeMillis() - PATTERN_WINDOW_MS
        val recent = dao.getSince(since)

        // Group by action+target and count
        val frequencyMap = mutableMapOf<Pair<String, String>, MutableList<ActionLog>>()
        for (log in recent) {
            val key = log.action to log.target.lowercase().trim()
            frequencyMap.getOrPut(key) { mutableListOf() }.add(log)
        }

        for ((key, logs) in frequencyMap) {
            if (logs.size >= PATTERN_THRESHOLD) {
                val (action, target) = key
                val memoryKey = "pattern_${action}_${target.replace(" ", "_")}"

                // Check if we already stored this pattern
                val existing = memoryStore.recallSync(memoryKey)
                if (existing == null) {
                    // New pattern detected — store it
                    val times = logs.joinToString(", ") {
                        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                            .format(java.util.Date(it.timestamp))
                    }
                    val value = "$action $target — done ${logs.size} times recently. Times: $times"
                    memoryStore.remember("habit", memoryKey, value,
                        "auto-detected: $action → $target × ${logs.size}")
                }
            }
        }
    }
}
