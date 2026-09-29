package com.vdx

/**
 * Google-style command autocomplete. Prefix first, then word-start, then contains.
 * Catalog is the spoken commands VDX can actually run — not a search-the-web toy.
 */
object CommandSuggest {

    val CATALOG: List<String> = listOf(
        "call Mom",
        "WhatsApp Mom saying I'll be late",
        "book Uber to the airport",
        "open YouTube and play bhajans",
        "message Ravi on WhatsApp that I'll be late",
        "book me an Uber home",
        "cancel my Uber",
        "sms Mom that I'm on my way",
        "email Ravi saying deck is ready",
        "read my email",
        "turn on wifi",
        "open WiFi settings",
        "what's my battery",
        "set an alarm for 7:30 am",
        "search for nearby pharmacy",
        "read my screen",
        "go back",
    )

    fun suggest(query: String, limit: Int = 6): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return CATALOG.take(limit)

        val prefix = CATALOG.filter { it.lowercase().startsWith(q) }
        val word = CATALOG.filter { line ->
            line !in prefix && line.lowercase().split(Regex("\\s+")).any { it.startsWith(q) }
        }
        val contains = CATALOG.filter { line ->
            line !in prefix && line !in word && line.lowercase().contains(q)
        }
        return (prefix + word + contains).distinct().take(limit)
    }
}
