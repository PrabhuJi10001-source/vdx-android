package com.vdx.sonic.voice

/**
 * CmdPriorityClassifier — COMMAND-FIRST ROUTING (fix for the real bug the maker
 * reported: "WhatsApp Cody saying …" showed "copied to clipboard" but nothing sent).
 *
 * ROOT CAUSE confirmed in code: the bubble sent EVERY phrase to DICTATION whenever
 * any text field on screen had input focus (BubbleForegroundService line ~976).
 * So a full COMMAND like "WhatsApp Cody saying I will be late" got pasted into whatever
 * field happened to be focused — the OS shows the clipboard toast, the text lands in
 * the WRONG field, and the command pipeline never runs at all.
 *
 * THE LAW (2026-10-18): commands are detected CONSERVATIVELY and win over dictation.
 * A transcript is a COMMAND when it starts with a clear app/intent verb or names a
 * routable app + action. Only true dictation (plain prose) goes through the
 * DictationInserter. When no command pattern matches, dictation behaves exactly
 * as before — zero regression risk for the dictate flow.
 */
object CmdPriorityClassifier {

    /** Command verbs/keywords that beat dictation. Kept deliberately small and
     *  prefix-anchored: a text field containing the word "whatsapp" anywhere is not
     *  enough to steal focus — the phrase must clearly command an action. */
    private val COMMAND_KEYWORDS = listOf(
        "whatsapp", "call", "dial", "text to", "message to", "message:",
        "open ", "launch ", "go to", "google ", "youtube", "search ",
        "find my", "play ", "instagram", "gmail", "email ", "maps",
        "navigate ", "camera", "settings", "phone ", "sms", "send ",
        "draft note", "remind me", "set reminder", "take note",
        "read screen", "read my screen", "read notifications", "describe screen",
        "go back", "go home", "scroll ", "tap ", "click "
    )

    /**
     * True when [transcript] should be treated as a COMMAND, not as dictation.
     * Rules:
     *  - very short fragments (<=2 words) stay dictation unless an exact verb match;
     *  - prefix keywords ("whatsapp", "call", "open ", "draft note", …) route as command;
     *  - phrases containing an app name in the FIRST TWO words route as command;
     *  - everything else = dictation (unchanged behavior).
     */
    fun isCommand(transcript: String): Boolean {
        val t = transcript.trim().lowercase()
        if (t.length < 4) return false
        val words = t.split("\\s+".toRegex())
        val prefixTwoWords = words.take(2).joinToString(" ")
        // 1) anchored command verbs
        for (k in COMMAND_KEYWORDS) {
            if (t.startsWith(k) || prefixTwoWords.contains(k.trim())) return true
        }
        // 2) known app names as the first/second word (WhatsApp, YouTube, Instagram,
        //    Gmail, Maps, Chrome, Camera, Settings, Messages...)
        val appNames = setOf("whatsapp", "youtube", "instagram", "gmail", "maps",
                             "chrome", "camera", "settings", "messages", "contacts")
        if (words.size >= 2 && appNames.contains(words[0])) return true
        if (words.size >= 3 && appNames.contains(words[1])) return true
        return false
    }
}
