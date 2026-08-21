package com.vdx.sonic.executor

import com.vdx.ScreenContentExtractor
import com.vdx.sonic.IntentType
import com.vdx.sonic.ScrollDirection
import com.vdx.sonic.SonicIntent

/**
 * ActionResolver — pure command → action mapping for the voice-to-accessibility bridge.
 *
 * Takes a parsed [SonicIntent] (from the voice engine) and the current screen snapshot
 * (from [ScreenContentExtractor]) and resolves it to a concrete [ResolvedAction] that
 * [ActionExecutor] can perform against the live accessibility tree.
 *
 * This class is deliberately free of Android service dependencies so the
 * command-to-action mapping can be unit-tested in a plain JVM.
 */
object ActionResolver {

    /**
     * Resolve a parsed command against the current screen snapshot.
     *
     * @param intent  the parsed voice command
     * @param snapshot the current screen snapshot (may be null if extraction failed)
     * @return a [ResolvedAction] describing what to do, or [ResolvedAction.Unresolved]
     *         when the command cannot be mapped to a direct accessibility action.
     */
    fun resolve(intent: SonicIntent, snapshot: ScreenContentExtractor.ScreenSnapshot?): ResolvedAction {
        return when (intent.type) {
            IntentType.GESTURE -> resolveGesture(intent, snapshot)
            IntentType.GO_BACK -> ResolvedAction.GoBack
            IntentType.GO_HOME -> ResolvedAction.GoHome
            IntentType.FORM_FILL, IntentType.TEXT_EDIT -> resolveTextEntry(intent, snapshot)
            else -> ResolvedAction.Unresolved(
                "Command type ${intent.type} is not a direct accessibility action"
            )
        }
    }

    private fun resolveGesture(
        intent: SonicIntent,
        snapshot: ScreenContentExtractor.ScreenSnapshot?
    ): ResolvedAction {
        val action = intent.entities["action"] ?: "tap"

        if (action == "scroll") {
            val dir = intent.entities["direction"] ?: "down"
            val scrollDir = when (dir) {
                "up" -> ScrollDirection.UP
                "left" -> ScrollDirection.LEFT
                "right" -> ScrollDirection.RIGHT
                else -> ScrollDirection.DOWN
            }
            return ResolvedAction.Scroll(scrollDir)
        }

        // tap / click / press
        val target = intent.entities["target"]?.trim()
        if (target.isNullOrBlank()) {
            // No target — tap the centre of the screen.
            return ResolvedAction.TapCenter(0.5f, 0.5f)
        }

        val element = findElement(snapshot, target)
        if (element == null) {
            return ResolvedAction.Unresolved("Could not find element \"$target\" on screen")
        }
        return ResolvedAction.Click(
            target = TargetRef(
                text = element.text.ifBlank { null },
                contentDescription = element.contentDescription.ifBlank { null },
                index = snapshot?.elements?.indexOf(element)?.takeIf { it >= 0 }
            ),
            element = element,
            destructive = isDestructiveTarget(target)
        )
    }

    /**
     * Heuristic for destructive / irreversible targets that must be confirmed via TTS
     * before the action is performed (e.g. tapping a "Delete" button).
     */
    private fun isDestructiveTarget(target: String): Boolean {
        val t = target.lowercase()
        return DESTRUCTIVE_KEYWORDS.any { t.contains(it) }
    }

    private val DESTRUCTIVE_KEYWORDS = listOf(
        "delete", "remove", "block", "uninstall", "clear", "erase",
        "discard", "cancel", "send", "book", "confirm", "submit", "logout", "sign out"
    )

    private fun resolveTextEntry(
        intent: SonicIntent,
        snapshot: ScreenContentExtractor.ScreenSnapshot?
    ): ResolvedAction {
        val text = intent.entities["text"] ?: intent.rawText
        if (text.isBlank()) {
            return ResolvedAction.Unresolved("No text to enter")
        }
        // Prefer the focused editable field; fall back to the first editable element.
        val focused = snapshot?.elements?.firstOrNull { it.isEditable && it.isFocused }
        val editable = focused ?: snapshot?.elements?.firstOrNull { it.isEditable }
        if (editable == null) {
            return ResolvedAction.Unresolved("No editable text field on screen")
        }
        return ResolvedAction.SetText(
            target = TargetRef(
                text = editable.text.ifBlank { null },
                contentDescription = editable.contentDescription.ifBlank { null },
                index = snapshot?.elements?.indexOf(editable)?.takeIf { it >= 0 }
            ),
            text = text,
            element = editable
        )
    }

    /**
     * Locate a [ScreenContentExtractor.ScreenElement] by text or content description
     * (case-insensitive, contains match). Prefers an exact match, then a contains match.
     */
    private fun findElement(
        snapshot: ScreenContentExtractor.ScreenSnapshot?,
        target: String
    ): ScreenContentExtractor.ScreenElement? {
        if (snapshot == null) return null
        val q = target.lowercase().trim()

        // Exact match on text or content description first.
        snapshot.elements.firstOrNull {
            it.text.equals(target, ignoreCase = true) ||
                it.contentDescription.equals(target, ignoreCase = true)
        }?.let { return it }

        // Then contains match.
        return snapshot.elements.firstOrNull {
            it.text.lowercase().contains(q) || it.contentDescription.lowercase().contains(q)
        }
    }
}

/**
 * A reference to a UI element that [ActionExecutor] can resolve against the live
 * accessibility tree. At least one field should be non-null.
 */
data class TargetRef(
    val text: String? = null,
    val contentDescription: String? = null,
    val index: Int? = null
)

/**
 * A concrete, executable action resolved from a parsed command.
 */
sealed class ResolvedAction {
    /** Click the element referenced by [target]. [element] is the snapshot match (for bounds fallback). */
    data class Click(
        val target: TargetRef,
        val element: ScreenContentExtractor.ScreenElement? = null,
        val destructive: Boolean = false
    ) : ResolvedAction()

    /** Scroll the current scrollable container in [direction]. */
    data class Scroll(val direction: ScrollDirection) : ResolvedAction()

    /** Insert [text] into the editable field referenced by [target]. */
    data class SetText(
        val target: TargetRef,
        val text: String,
        val element: ScreenContentExtractor.ScreenElement? = null
    ) : ResolvedAction()

    /** Tap the centre of the screen (normalised 0..1 coordinates). */
    data class TapCenter(val x: Float, val y: Float) : ResolvedAction()

    /** Perform the system back action. */
    object GoBack : ResolvedAction()

    /** Perform the system home action. */
    object GoHome : ResolvedAction()

    /** The command could not be mapped to a direct accessibility action. */
    data class Unresolved(val reason: String) : ResolvedAction()
}
