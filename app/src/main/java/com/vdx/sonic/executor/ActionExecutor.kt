package com.vdx.sonic.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.vdx.ScreenContentExtractor
import com.vdx.settings.Verbosity
import com.vdx.settings.VerbosityFilter
import com.vdx.sonic.ScrollDirection
import com.vdx.sonic.voice.PromptTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * ActionExecutor — the bridge between the voice engine and the accessibility service.
 *
 * Takes a [ResolvedAction] (produced by [ActionResolver] from a parsed command) and
 * performs the corresponding accessibility action on the live node tree:
 *   - ACTION_CLICK on the located node (with clickable-ancestor walk-up + gesture fallback)
 *   - ACTION_SCROLL_FORWARD / ACTION_SCROLL_BACKWARD on the scrollable container
 *   - ACTION_SET_TEXT on the editable field
 *   - GLOBAL_ACTION_BACK / GLOBAL_ACTION_HOME
 *   - gesture tap at screen coordinates
 *
 * Safety:
 *   - Destructive actions (e.g. tapping a "Delete" button) are gated behind a TTS
 *     confirmation prompt. The caller must invoke [confirm] with the user's spoken
 *     "yes"/"no" before the action is performed.
 *   - All execution is asynchronous (suspend) so it never blocks the accessibility
 *     service's main thread.
 *   - Errors (element not found, action not supported) are returned as [ActionResult.Failed]
 *     with a spoken message, never thrown.
 */
class ActionExecutor(
    private val context: Context,
    private val service: AccessibilityService
) {
    companion object {
        private const val TAG = "ActionExecutor"
        private const val STEP_DELAY_MS = 300L
    }

    private var tts: TextToSpeech? = null

    /**
     * Perform a resolved action asynchronously.
     *
     * @param action the resolved action to perform
     * @param confirm a suspend callback invoked when a destructive action needs user
     *        confirmation. Returns true if the user confirmed, false if they declined.
     * @return the outcome of the action
     */
    suspend fun execute(
        action: ResolvedAction,
        confirm: suspend (String) -> Boolean = { true }
    ): ActionResult {
        return withContext(Dispatchers.Default) {
            try {
                when (action) {
                    is ResolvedAction.Click -> executeClick(action, confirm)
                    is ResolvedAction.Scroll -> executeScroll(action)
                    is ResolvedAction.SetText -> executeSetText(action)
                    is ResolvedAction.TapCenter -> executeTapCenter(action)
                    ResolvedAction.GoBack -> executeGlobal(AccessibilityService.GLOBAL_ACTION_BACK, PromptTemplate.render(PromptTemplate.SUCCESS_BACK))
                    ResolvedAction.GoHome -> executeGlobal(AccessibilityService.GLOBAL_ACTION_HOME, PromptTemplate.render(PromptTemplate.SUCCESS_HOME))
                    is ResolvedAction.Unresolved -> {
                        speak(action.reason, Verbosity.MIN_ERROR)
                        ActionResult.Failed(action.reason)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "execute failed", e)
                val msg = PromptTemplate.render(
                    PromptTemplate.ERROR_GENERIC,
                    mapOf("reason" to (e.message ?: "unknown error"))
                )
                speak(msg, Verbosity.MIN_ERROR)
                ActionResult.Failed(msg)
            }
        }
    }

    /**
     * Verify a pending [ActionResult.Unverified] result. Only after [verifier] confirms
     * the action's effect is the result upgraded to [ActionResult.Success]. Non-Unverified
     * results are returned unchanged.
     */
    suspend fun verify(
        result: ActionResult,
        successMessage: String,
        verifier: suspend () -> Boolean
    ): ActionResult {
        if (result !is ActionResult.Unverified) return result
        return if (verifier()) ActionResult.Success(successMessage)
               else ActionResult.Failed("Verification failed")
    }

    /**
     * Confirm a pending destructive action. Call this with the user's spoken response
     * ("yes" / "no") after [execute] returned [ActionResult.ConfirmationRequired].
     */
    suspend fun confirm(confirmed: Boolean, pending: ResolvedAction.Click): ActionResult {
        if (!confirmed) {
            speak(PromptTemplate.render(PromptTemplate.CANCELLED), Verbosity.MIN_CONFIRM)
            return ActionResult.Cancelled
        }
        return withContext(Dispatchers.Default) {
            performClick(pending)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Click
    // ──────────────────────────────────────────────────────────────

    private suspend fun executeClick(
        action: ResolvedAction.Click,
        confirm: suspend (String) -> Boolean
    ): ActionResult {
        if (action.destructive) {
            val target = action.target.text ?: action.target.contentDescription ?: "this"
            val prompt = PromptTemplate.render(
                PromptTemplate.CONFIRMATION_TAP,
                mapOf("target" to target)
            )
            speak(prompt, Verbosity.MIN_CONFIRM)
            val ok = confirm(prompt)
            if (!ok) {
                speak(PromptTemplate.render(PromptTemplate.CANCELLED), Verbosity.MIN_CONFIRM)
                return ActionResult.Cancelled
            }
        }
        return performClick(action)
    }

    private suspend fun performClick(action: ResolvedAction.Click): ActionResult {
        val node = findNode(action.target) ?: run {
            val msg = PromptTemplate.render(PromptTemplate.ERROR_NOT_FOUND)
            speak(msg, Verbosity.MIN_ERROR)
            return ActionResult.Failed(msg)
        }
        val ok = clickNode(node)
        if (!ok) {
            val msg = PromptTemplate.render(PromptTemplate.ERROR_CLICK)
            speak(msg, Verbosity.MIN_ERROR)
            return ActionResult.Failed(msg)
        }
        val label = action.target.text ?: action.target.contentDescription ?: "element"
        val spoken = PromptTemplate.render(PromptTemplate.SUCCESS_TAP, mapOf("target" to label))
        speak(spoken, Verbosity.MIN_SUCCESS)
        return ActionResult.Unverified
    }

    // ──────────────────────────────────────────────────────────────
    // Scroll
    // ──────────────────────────────────────────────────────────────

    private suspend fun executeScroll(action: ResolvedAction.Scroll): ActionResult {
        val node = findScrollable() ?: run {
            val msg = PromptTemplate.render(PromptTemplate.ERROR_NO_SCROLL)
            speak(msg, Verbosity.MIN_ERROR)
            return ActionResult.Failed(msg)
        }
        val actionId = when (action.direction) {
            ScrollDirection.UP, ScrollDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ScrollDirection.DOWN, ScrollDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        val ok = node.performAction(actionId)
        val dirName = action.direction.name.lowercase()
        if (!ok) {
            val msg = PromptTemplate.render(PromptTemplate.ERROR_SCROLL, mapOf("direction" to dirName))
            speak(msg, Verbosity.MIN_ERROR)
            return ActionResult.Failed(msg)
        }
        val spoken = PromptTemplate.render(PromptTemplate.SUCCESS_SCROLL, mapOf("direction" to dirName))
        speak(spoken, Verbosity.MIN_SUCCESS)
        return ActionResult.Unverified
    }

    // ──────────────────────────────────────────────────────────────
    // SetText
    // ──────────────────────────────────────────────────────────────

    private suspend fun executeSetText(action: ResolvedAction.SetText): ActionResult {
        val node = findNode(action.target) ?: run {
            val msg = PromptTemplate.render(PromptTemplate.ERROR_NO_FIELD)
            speak(msg, Verbosity.MIN_ERROR)
            return ActionResult.Failed(msg)
        }
        if (!node.isEditable) {
            val msg = PromptTemplate.render(PromptTemplate.ERROR_NOT_EDITABLE)
            speak(msg, Verbosity.MIN_ERROR)
            return ActionResult.Failed(msg)
        }
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) {
            val msg = PromptTemplate.render(PromptTemplate.ERROR_SET_TEXT)
            speak(msg, Verbosity.MIN_ERROR)
            return ActionResult.Failed(msg)
        }
        speak(PromptTemplate.render(PromptTemplate.SUCCESS_TEXT_ENTERED), Verbosity.MIN_SUCCESS)
        return ActionResult.Unverified
    }

    // ──────────────────────────────────────────────────────────────
    // Tap center / global
    // ──────────────────────────────────────────────────────────────

    private suspend fun executeTapCenter(action: ResolvedAction.TapCenter): ActionResult {
        val w = context.resources.displayMetrics.widthPixels
        val h = context.resources.displayMetrics.heightPixels
        val ok = tap(action.x * w, action.y * h)
        if (!ok) {
            val msg = PromptTemplate.render(PromptTemplate.ERROR_TAP)
            speak(msg, Verbosity.MIN_ERROR)
            return ActionResult.Failed(msg)
        }
        speak(PromptTemplate.render(PromptTemplate.SUCCESS_TAPPED), Verbosity.MIN_SUCCESS)
        return ActionResult.Unverified
    }

    private suspend fun executeGlobal(action: Int, successMsg: String): ActionResult {
        val ok = service.performGlobalAction(action)
        if (!ok) {
            val msg = PromptTemplate.render(PromptTemplate.ERROR_GLOBAL)
            speak(msg, Verbosity.MIN_ERROR)
            return ActionResult.Failed(msg)
        }
        speak(successMsg, Verbosity.MIN_SUCCESS)
        return ActionResult.Unverified
    }

    // ──────────────────────────────────────────────────────────────
    // Node finding
    // ──────────────────────────────────────────────────────────────

    private fun findNode(target: TargetRef): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return searchTree(root) { node ->
            var match = true
            if (target.text != null) {
                match = match && nodeText(node).equals(target.text, ignoreCase = true)
            }
            if (target.contentDescription != null) {
                match = match && (node.contentDescription?.toString()?.equals(target.contentDescription, ignoreCase = true) == true)
            }
            match
        }
    }

    private fun findScrollable(): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return searchTree(root) { it.isScrollable }
    }

    private fun searchTree(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = searchTree(child, predicate)
            if (result != null) return result
        }
        return null
    }

    private fun nodeText(node: AccessibilityNodeInfo): String =
        node.text?.toString() ?: node.contentDescription?.toString() ?: node.hintText?.toString() ?: ""

    // ──────────────────────────────────────────────────────────────
    // Action helpers
    // ──────────────────────────────────────────────────────────────

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // Walk up to find a clickable ancestor.
        var parent: AccessibilityNodeInfo? = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
        }
        // Fallback: gesture tap at the node's bounds centre.
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        return tap(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    private fun tap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    // ──────────────────────────────────────────────────────────────
    // TTS
    // ──────────────────────────────────────────────────────────────

    private fun speak(text: String, minLevel: Int = Verbosity.MIN_STANDARD) {
        // SINGLE gate: at SILENT (0) short-circuit before any TTS engine init.
        val decision = VerbosityFilter.decide(minLevel, Verbosity.level(context))
        if (!decision.spoken) return
        Log.i(TAG, "TTS: $text")
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = PromptTemplate.ttsLocale()
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "executor_utterance")
                }
            }
        } else {
            tts?.language = PromptTemplate.ttsLocale()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "executor_utterance")
        }
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

/**
 * The outcome of an executed action.
 */
sealed class ActionResult {
    data class Success(val message: String) : ActionResult()
    data class Failed(val reason: String) : ActionResult()
    object Unverified : ActionResult()
    object Cancelled : ActionResult()

    // Test: when-branch pattern — all states must be handled exhaustively.
    // Unverified is a distinct subtype; it is NOT == Success and cannot be
    // treated as Success in a when branch:
    //   fun handle(r: ActionResult) = when (r) {
    //       is Success     -> println("verified: ${r.message}")
    //       is Failed      -> println("failed: ${r.reason}")
    //       Unverified     -> println("pending verifier confirmation")
    //       Cancelled      -> println("cancelled")
    //   }
}
