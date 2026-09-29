package com.vdx.sonic.knowledge

/**
 * NavStep — a single navigation step in an app knowledge entry.
 *
 * Each step describes what to do, which accessibility element to find, and
 * what action to perform on it. The Planner converts these into [com.vdx.sonic.ActionStep]s
 * when building an [com.vdx.sonic.ExecutionPlan].
 *
 * Voice-first knowledge base pattern: instead of hardcoding every
 * app flow in adapters, navigation knowledge is stored as structured data
 * that the Planner can query at runtime.
 */
data class NavStep(
    /** Human-readable description of what this step does (also used as the ActionStep description). */
    val description: String,
    /** Accessibility text or resource ID to find on screen. Null = no specific target. */
    val targetElement: String? = null,
    /** Action to perform: TAP, SCROLL, SET_TEXT, or WAIT. */
    val action: String = "TAP",
    /** Text to enter when action is SET_TEXT. Null for other actions. */
    val text: String? = null,
    /** Scroll direction when action is SCROLL. One of UP/DOWN/LEFT/RIGHT. */
    val scrollDirection: String? = null,
    /** Wait timeout in ms when action is WAIT. */
    val waitMs: Long? = null
) {
    companion object {
        const val ACTION_TAP = "TAP"
        const val ACTION_SCROLL = "SCROLL"
        const val ACTION_SET_TEXT = "SET_TEXT"
        const val ACTION_WAIT = "WAIT"
    }
}