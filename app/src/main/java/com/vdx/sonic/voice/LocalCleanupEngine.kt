package com.vdx.sonic.voice

/**
 * LocalCleanupEngine — always-on Wispr-style cleanup with zero network.
 *
 * Beats chatty assistants on rambling speech by stripping fillers and
 * applying course-corrections before intent parse. Cloud CleanupEngine
 * can refine further when available; this is the reliable V1 baseline.
 */
object LocalCleanupEngine {

    private val FILLERS = Regex(
        """\b(uh+|um+|erm+|ah+|like|you know|basically|actually|so yeah|i mean|kind of|kinda|sort of|sorta)\b""",
        RegexOption.IGNORE_CASE
    )

    private val HESITATION = Regex("""\b(wait|hold on|hang on)\b[,.]?\s*""", RegexOption.IGNORE_CASE)

    /** "X, no actually Y" / "X no Y" course corrections → keep final. */
    private val COURSE_CORRECTION = Regex(
        """(.+?)(?:,?\s*(?:no(?:t)?(?:\s+actually)?|sorry|wait|i mean|scratch that|rather)\s+)(.+)$""",
        RegexOption.IGNORE_CASE
    )

    private val MULTI_SPACE = Regex("""\s{2,}""")
    private val LEADING_JUNK = Regex("""^[,.\-\s]+""")

    fun clean(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw.trim()
            .replace('\u2019', '\'')
            .replace('\u2018', '\'')
            .replace('\u201C', '"')
            .replace('\u201D', '"')

        // Course correction first (keep final clause)
        val correction = COURSE_CORRECTION.find(text)
        if (correction != null) {
            val abandoned = correction.groupValues[1].trim()
            val kept = correction.groupValues[2].trim()
            // Prefer kept if it looks like a replacement, not a continuation
            if (kept.isNotBlank() && kept.split(" ").size <= abandoned.split(" ").size + 6) {
                text = kept
            }
        }

        text = FILLERS.replace(text, " ")
        text = HESITATION.replace(text, "")
        text = MULTI_SPACE.replace(text, " ").trim()
        text = LEADING_JUNK.replace(text, "")

        // Capitalize first letter for cleaner intent parse
        if (text.isNotEmpty()) {
            text = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

        // Spoken punctuation
        text = text
            .replace(Regex("""\bcomma\b""", RegexOption.IGNORE_CASE), ",")
            .replace(Regex("""\bperiod\b""", RegexOption.IGNORE_CASE), ".")
            .replace(Regex("""\bquestion mark\b""", RegexOption.IGNORE_CASE), "?")
            .replace(Regex("""\s+,"""), ",")
            .replace(Regex("""\s+\."""), ".")

        return MULTI_SPACE.replace(text, " ").trim()
    }
}
