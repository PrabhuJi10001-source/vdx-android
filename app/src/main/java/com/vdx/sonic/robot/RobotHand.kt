package com.vdx.sonic.robot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.vdx.sonic.*
import com.vdx.sonic.harness.Harness
import java.util.Locale

/**
 * RobotHand — execution engine for VDX Sonic.
 *
 * Executes action plans step by step using the accessibility service.
 * Action priority:
 *   1. Semantic accessibility actions
 *   2. Text insertion
 *   3. Scroll / focus / global actions
 *   4. Gesture dispatch (only if semantic fails)
 *
 * Each step verifies postconditions and stops on failure.
 */
class RobotHand(
    private val context: Context,
    private val harness: Harness
) {
    companion object {
        private const val TAG = "Sonic-RobotHand"
        private const val STEP_DELAY_MS = 500L
        private const val DEFAULT_TIMEOUT_MS = 8000L
    }

    private var tts: TextToSpeech? = null
    private var currentStepIndex = 0

    /**
     * Execute a full execution plan step by step.
     */
    suspend fun execute(plan: ExecutionPlan): ExecutionResult {
        currentStepIndex = 0
        val a11y = getAccessibilityService()
        if (a11y == null) {
            return ExecutionResult.Failed("Accessibility service not running", recoverable = true)
        }

        initTts()

        for ((i, step) in plan.steps.withIndex()) {
            currentStepIndex = i
            Log.d(TAG, "Step ${i + 1}/${plan.steps.size}: ${step.description}")

            val result = executeStep(step, plan, a11y)
            if (result is ExecutionResult.Failed) {
                return result
            }
            if (result is ExecutionResult.ClarificationNeeded ||
                result is ExecutionResult.ConfirmationNeeded) {
                return result
            }

            // Postcondition delay
            if (step.expectedPostcondition != null) {
                Thread.sleep(STEP_DELAY_MS)
            }
        }

        return ExecutionResult.Success("Completed ${plan.steps.size} steps", plan.steps.size)
    }

    /**
     * Execute a single action step.
     */
    private suspend fun executeStep(step: ActionStep, plan: ExecutionPlan, a11y: AccessibilityService): ExecutionResult {
        return when (val action = step.action) {
            is ActionPrimitive.OpenApp -> {
                val ok = launchApp(action.packageName)
                if (!ok) ExecutionResult.Failed("Could not open ${action.packageName}", step.id, recoverable = true)
                else ExecutionResult.Success("Opened ${action.packageName}", 1)
            }

            is ActionPrimitive.WaitForPackage -> {
                val ok = waitForApp(action.packageName, action.timeoutMs, a11y)
                if (!ok) ExecutionResult.Failed("${action.packageName} did not load in time", step.id, recoverable = true)
                else ExecutionResult.Success("${action.packageName} loaded", 1)
            }

            is ActionPrimitive.ReadUiState -> {
                val screen = harness.readScreen(a11y)
                ExecutionResult.Success("Read screen: ${screen.packageName}, ${screen.elements.size} elements", 1)
            }

            is ActionPrimitive.FindNode -> {
                val screen = harness.readScreen(a11y)
                val node = findNode(screen, action.selector)
                if (node == null) ExecutionResult.Failed("Could not find element matching selector", step.id, recoverable = true)
                else ExecutionResult.Success("Found element: ${node.text ?: node.contentDescription ?: node.ref}", 1)
            }

            is ActionPrimitive.FocusNode -> {
                val screen = harness.readScreen(a11y)
                val node = findNode(screen, action.selector)
                if (node == null) return ExecutionResult.Failed("Could not find element to focus", step.id, recoverable = true)
                val a11yNode = findAccessibilityNode(a11y, node)
                if (a11yNode == null) return ExecutionResult.Failed("Node not found in tree", step.id, recoverable = true)
                a11yNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                ExecutionResult.Success("Focused element", 1)
            }

            is ActionPrimitive.SetText -> {
                val screen = harness.readScreen(a11y)
                val node = findNode(screen, action.selector)
                if (node == null) return ExecutionResult.Failed("Could not find text field", step.id, recoverable = true)
                val a11yNode = findAccessibilityNode(a11y, node)
                if (a11yNode == null) return ExecutionResult.Failed("Text field not found in tree", step.id, recoverable = true)
                val ok = insertText(a11yNode, action.text)
                if (!ok) ExecutionResult.Failed("Could not insert text", step.id, recoverable = true)
                else ExecutionResult.Success("Inserted text: ${action.text.take(50)}", 1)
            }

            is ActionPrimitive.ClickNode -> {
                val screen = harness.readScreen(a11y)
                val node = findNode(screen, action.selector)
                if (node == null) return ExecutionResult.Failed("Could not find element to click", step.id, recoverable = true)
                val a11yNode = findAccessibilityNode(a11y, node)
                if (a11yNode == null) return ExecutionResult.Failed("Clickable element not found in tree", step.id, recoverable = true)
                val ok = clickNode(a11yNode, a11y)
                if (!ok) ExecutionResult.Failed("Could not click element", step.id, recoverable = true)
                else ExecutionResult.Success("Clicked element", 1)
            }

            is ActionPrimitive.LongClickNode -> {
                val screen = harness.readScreen(a11y)
                val node = findNode(screen, action.selector)
                if (node == null) return ExecutionResult.Failed("Could not find element", step.id, recoverable = true)
                val a11yNode = findAccessibilityNode(a11y, node)
                if (a11yNode == null) return ExecutionResult.Failed("Element not found", step.id, recoverable = true)
                a11yNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                ExecutionResult.Success("Long-clicked element", 1)
            }

            is ActionPrimitive.ScrollContainer -> {
                val screen = harness.readScreen(a11y)
                val node = findNode(screen, action.selector)
                if (node == null) return ExecutionResult.Failed("Could not find scrollable container", step.id, recoverable = true)
                val a11yNode = findAccessibilityNode(a11y, node)
                if (a11yNode == null) return ExecutionResult.Failed("Container not found", step.id, recoverable = true)
                val actionId = when (action.direction) {
                    ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    ScrollDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    ScrollDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                a11yNode.performAction(actionId)
                ExecutionResult.Success("Scrolled ${action.direction}", 1)
            }

            is ActionPrimitive.SelectOption -> {
                val screen = harness.readScreen(a11y)
                val node = findNode(screen, action.selector)
                if (node == null) return ExecutionResult.Failed("Could not find option", step.id, recoverable = true)
                val a11yNode = findAccessibilityNode(a11y, node)
                if (a11yNode == null) return ExecutionResult.Failed("Option not found", step.id, recoverable = true)
                clickNode(a11yNode, a11y)
                ExecutionResult.Success("Selected option", 1)
            }

            is ActionPrimitive.ReadVisibleResult -> {
                val screen = harness.readScreen(a11y)
                val text = screen.elements.mapNotNull { it.text ?: it.contentDescription }
                    .joinToString(". ")
                speak(text)
                ExecutionResult.Success(text, 1)
            }

            is ActionPrimitive.AskUser -> {
                speak(action.question)
                ExecutionResult.ClarificationNeeded(action.question, plan.intent)
            }

            is ActionPrimitive.WaitForUserConfirmation -> {
                speak(action.prompt)
                ExecutionResult.ConfirmationNeeded(action.prompt, plan)
            }

            is ActionPrimitive.DispatchGesture -> {
                val ok = dispatchGesture(action, a11y)
                if (!ok) ExecutionResult.Failed("Gesture failed", step.id, recoverable = true)
                else ExecutionResult.Success("Gesture dispatched", 1)
            }

            is ActionPrimitive.GoBack -> {
                a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                ExecutionResult.Success("Went back", 1)
            }

            is ActionPrimitive.FailWithReason -> {
                ExecutionResult.Failed(action.reason, step.id, recoverable = false)
            }
        }
    }

    /**
     * Handle user confirmation response.
     */
    fun handleConfirmation(confirmed: Boolean, plan: ExecutionPlan): ExecutionResult {
        if (!confirmed) {
            return ExecutionResult.Cancelled("User cancelled")
        }
        // Continue execution from where we left off
        return ExecutionResult.Success("Confirmed", currentStepIndex)
    }

    // ──────────────────────────────────────────────────────────────
    // Action Helpers
    // ──────────────────────────────────────────────────────────────

    private fun launchApp(packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                // Try searching by label
                val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val apps = pm.queryIntentActivities(mainIntent, 0)
                val match = apps.firstOrNull {
                    it.loadLabel(pm).toString().equals(packageName, ignoreCase = true)
                } ?: apps.firstOrNull {
                    it.loadLabel(pm).toString().contains(packageName, ignoreCase = true)
                }
                if (match == null) return false
                val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "launchApp failed", e)
            false
        }
    }

    private fun waitForApp(packageName: String, timeoutMs: Long, a11y: AccessibilityService): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val root = a11y.rootInActiveWindow
            if (root != null) {
                val pkg = try { root.packageName?.toString() ?: "" } catch (e: Exception) { "" }
                if (pkg.equals(packageName, ignoreCase = true)) return true
            }
            Thread.sleep(200)
        }
        return false
    }

    private fun insertText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) return false
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun clickNode(node: AccessibilityNodeInfo, a11y: AccessibilityService): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // Walk up to find clickable parent
        var parent: AccessibilityNodeInfo? = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
        }
        // Fallback: gesture tap
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        return tap(rect.centerX().toFloat(), rect.centerY().toFloat(), a11y)
    }

    private fun tap(x: Float, y: Float, a11y: AccessibilityService): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return a11y.dispatchGesture(gesture, null, null)
    }

    private fun dispatchGesture(action: ActionPrimitive.DispatchGesture, a11y: AccessibilityService): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val w = context.resources.displayMetrics.widthPixels
        val h = context.resources.displayMetrics.heightPixels

        val gestureCoords = when (action.type) {
            GestureType.TAP -> floatArrayOf(action.x, action.y, action.x, action.y)
            GestureType.SWIPE_UP -> floatArrayOf(w / 2f, h * 0.7f, w / 2f, h * 0.3f)
            GestureType.SWIPE_DOWN -> floatArrayOf(w / 2f, h * 0.3f, w / 2f, h * 0.7f)
            GestureType.SWIPE_LEFT -> floatArrayOf(w * 0.8f, h / 2f, w * 0.2f, h / 2f)
            GestureType.SWIPE_RIGHT -> floatArrayOf(w * 0.2f, h / 2f, w * 0.8f, h / 2f)
        }
        val startX = gestureCoords[0]
        val startY = gestureCoords[1]
        val endX = gestureCoords[2]
        val endY = gestureCoords[3]

        val path = Path().apply {
            moveTo(startX, startY)
            if (action.type == GestureType.TAP) {
                // Tap: just a short stroke
                lineTo(endX, endY)
            } else {
                lineTo(endX, endY)
            }
        }
        val duration = if (action.type == GestureType.TAP) 50L else 300L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return a11y.dispatchGesture(gesture, null, null)
    }

    // ──────────────────────────────────────────────────────────────
    // Node Finding
    // ──────────────────────────────────────────────────────────────

    private fun findNode(screen: ScreenModel, selector: NodeSelector): UiElement? {
        return screen.elements.firstOrNull { el ->
            matches(el, selector)
        }
    }

    private fun matches(el: UiElement, sel: NodeSelector): Boolean {
        if (sel.text != null && !el.text.equals(sel.text, ignoreCase = true) &&
            !(el.text?.contains(sel.text, ignoreCase = true) == true)) return false
        if (sel.contentDescription != null && !el.contentDescription.equals(sel.contentDescription, ignoreCase = true) &&
            !(el.contentDescription?.contains(sel.contentDescription, ignoreCase = true) == true)) return false
        if (sel.hint != null && !el.hint.equals(sel.hint, ignoreCase = true) &&
            !(el.hint?.contains(sel.hint, ignoreCase = true) == true)) return false
        if (sel.className != null && !el.className.contains(sel.className, ignoreCase = true)) return false
        if (sel.isEditable != null && el.isEditable != sel.isEditable) return false
        if (sel.isClickable != null && el.isClickable != sel.isClickable) return false
        if (sel.isFocused != null && el.isFocused != sel.isFocused) return false
        return true
    }

    private fun findAccessibilityNode(a11y: AccessibilityService, target: UiElement): AccessibilityNodeInfo? {
        val root = a11y.rootInActiveWindow ?: return null
        return searchTree(root) { node ->
            val text = safeText(node)
            val cd = safeContentDescription(node)
            val hint = safeHint(node)
            val cls = safeClassName(node)

            (text == target.text || (text != null && text == target.text)) &&
            (cd == target.contentDescription || (cd != null && cd == target.contentDescription)) &&
            node.isClickable == target.isClickable &&
            node.isEditable == target.isEditable
        }
    }

    private fun searchTree(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = searchTree(child, predicate)
            if (result != null) return result
        }
        return null
    }

    // ──────────────────────────────────────────────────────────────
    // TTS
    // ──────────────────────────────────────────────────────────────

    private fun initTts() {
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                }
            }
        }
    }

    fun speak(text: String) {
        Log.i(TAG, "TTS: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sonic_robot")
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // ──────────────────────────────────────────────────────────────
    // Safe accessors
    // ──────────────────────────────────────────────────────────────

    private fun safeText(node: AccessibilityNodeInfo): String? = try { node.text?.toString() } catch (e: Exception) { null }
    private fun safeContentDescription(node: AccessibilityNodeInfo): String? = try { node.contentDescription?.toString() } catch (e: Exception) { null }
    private fun safeHint(node: AccessibilityNodeInfo): String? = try { node.hintText?.toString() } catch (e: Exception) { null }
    private fun safeClassName(node: AccessibilityNodeInfo): String = try { node.className?.toString() ?: "" } catch (e: Exception) { "" }

    private fun getAccessibilityService(): AccessibilityService? {
        return try {
            val cls = Class.forName("com.vdx.VdxAccessibilityService")
            val field = cls.getDeclaredField("instance")
            field.isAccessible = true
            field.get(null) as? AccessibilityService
        } catch (e: Exception) { null }
    }
}
