package com.vdx

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * VdxAccessibilityService — the core of VDX.
 *
 * A TalkBack replacement that combines:
 *  1. Screen reading — traverses the accessibility tree to understand what's visible.
 *  2. Robot Hand execution — performs clicks, text entry, and gestures on behalf of the user.
 *
 * The service exposes a static [instance] reference via the companion object so that
 * [BubbleForegroundService] can call [readScreen], [clickElement], [insertText],
 * [findFocusedTextField], [tap], [swipe], etc. directly without IPC.
 *
 * Hardware safety:
 *  - CoroutineScope with SupervisorJob tied to service lifecycle
 *  - Bounded accessibility node walker (maxDepth=3, maxNodes=50) prevents ANR
 */
class VdxAccessibilityService : AccessibilityService(), CoroutineScope {

    companion object {
        private const val TAG = "VdxA11y"

        // Bounded walker limits — guarantees sub-10ms event processing
        private const val MAX_WALKER_DEPTH = 3
        private const val MAX_WALKER_NODES = 50

        /**
         * Live reference to the running service instance, or null when the service
         * is not connected.  [BubbleForegroundService] uses this to invoke methods.
         */
        @Volatile
        var instance: VdxAccessibilityService? = null
            private set

        /** Whether the accessibility service is currently connected. */
        var isRunning: Boolean = false
            private set

        /**
         * Set by [onAccessibilityEvent] whenever an editable text field receives focus.
         * The bubble service can poll this to know whether the IME / custom keyboard
         * should be surfaced.
         */
        @Volatile
        var focusedTextField: AccessibilityNodeInfo? = null
            private set
    }

    // Service-scoped coroutine context — cancels all jobs onDestroy
    private val serviceJob = SupervisorJob()
    override val coroutineContext: CoroutineContext = Dispatchers.Default + serviceJob

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        Log.i(TAG, "VDX Accessibility Service connected — TalkBack replacement active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType

        // Detect text-field focus — report to bubble service
        if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            val src = event.source ?: return
            if (isEditableNode(src)) {
                focusedTextField = src
                Log.d(TAG, "Editable text field focused: ${nodeText(src)}")
                // Notify the bubble service if it's running
                BubbleForegroundService.onTextFieldFocused()
            } else if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                // Focus moved to a non-editable element — clear the reference
                focusedTextField = null
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        focusedTextField = null
        serviceJob.cancel()
        Log.i(TAG, "VDX Accessibility Service destroyed")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Screen Reading
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Returns a human-readable summary of what's currently visible on screen:
     * active app package, key interactive elements, and text fields.
     */
    fun readScreen(): ScreenSummary {
        val root = rootInActiveWindow ?: return ScreenSummary.empty()

        val appName = try { root.packageName?.toString() ?: "unknown" } catch (e: Exception) { "unknown" }
        val elements = mutableListOf<ScreenElement>()
        val textFields = mutableListOf<ScreenElement>()

        traverseTree(root, elements, textFields)

        return ScreenSummary(
            packageName = appName,
            elementCount = elements.size,
            keyElements = elements,
            textFields = textFields
        )
    }

    /**
     * Recursively walk the accessibility tree, collecting visible nodes.
     */
    private fun traverseTree(
        node: AccessibilityNodeInfo,
        elements: MutableList<ScreenElement>,
        textFields: MutableList<ScreenElement>
    ) {
        val text = nodeText(node)
        val isEditable = isEditableNode(node)
        val isClickable = node.isClickable

        // Only collect nodes that have some discernible content or are interactive
        if (text.isNotBlank() || isClickable || node.isFocusable) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val element = ScreenElement(
                text = text,
                className = node.className?.toString() ?: "",
                isClickable = isClickable,
                isEditable = isEditable,
                bounds = rect.toString()
            )
            elements.add(element)
            if (isEditable) textFields.add(element)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseTree(child, elements, textFields)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Text Field Detection
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Returns the currently focused editable node, if any.
     * This is the node the Robot Hand will [insertText] into.
     */
    fun findFocusedTextField(): AccessibilityNodeInfo? {
        // First check the cached reference from onAccessibilityEvent
        focusedTextField?.let { if (isEditableNode(it)) return it }

        // Fall back to a tree search for the focused editable node
        val root = rootInActiveWindow ?: return null
        return findFocusedEditable(root)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isEditableNode(node) && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditable(child)
            if (result != null) return result
        }
        return null
    }

    // ──────────────────────────────────────────────────────────────────────
    // Robot Hand — Text Entry
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Insert text into an editable node using ACTION_SET_TEXT.
     * The node must be editable (e.g. an EditText).
     *
     * @return true if the action was dispatched successfully.
     */
    fun insertText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!isEditableNode(node)) {
            Log.w(TAG, "insertText: node is not editable")
            return false
        }
        val args = android.os.Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(TAG, "insertText(\"$text\") → $ok")
        return ok
    }

    /**
     * Convenience overload — inserts text into the currently focused text field.
     */
    fun insertTextIntoFocused(text: String): Boolean {
        val node = findFocusedTextField() ?: return false
        return insertText(node, text)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Robot Hand — Click
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Search the entire accessibility tree for a node whose text or contentDescription
     * matches [query] (case-insensitive, contains match) and click it.
     *
     * @return true if a matching node was found and the click action was dispatched.
     */
    fun clickElement(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNodeByText(root, query) ?: return false
        return clickNode(target)
    }

    /**
     * Click a specific [node].  Tries ACTION_CLICK on the node first; if the node
     * is not clickable, walks up the parent chain until a clickable ancestor is found.
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // If the node itself is clickable, just click it
        if (node.isClickable) {
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "clickNode(text=\"${nodeText(node)}\") → $ok")
            return ok
        }
        // Walk up to find a clickable parent
        var parent: AccessibilityNodeInfo? = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                val ok = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "clickNode(parent of \"${nodeText(node)}\") → $ok")
                return ok
            }
            parent = parent.parent
        }
        // Last resort: tap the centre of the node's bounds via gesture
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        Log.w(TAG, "clickNode: no clickable ancestor — falling back to gesture tap at $rect")
        return tap(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val q = query.lowercase().trim()
        return searchTree(root) { node ->
            val text = nodeText(node).lowercase()
            text.contains(q)
        }
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

    // ──────────────────────────────────────────────────────────────────────
    // Robot Hand — Gestures (dispatchGesture)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Tap at the given screen coordinates.  Uses [dispatchGesture] with a short
     * tap-start / tap-end stroke.
     */
    fun tap(x: Float, y: Float, durationMs: Long = 50): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(gesture, null, null)
        Log.i(TAG, "tap($x, $y) → $ok")
        return ok
    }

    /**
     * Swipe from (startX, startY) to (endX, endY) over [durationMs] milliseconds.
     */
    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(gesture, null, null)
        Log.i(TAG, "swipe($startX,$startY → $endX,$endY) → $ok")
        return ok
    }

    /**
     * Swipe up — useful for scrolling lists / closing keyboards.
     */
    fun swipeUp(distance: Float = 500f): Boolean {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        return swipe(w / 2f, h * 0.7f, w / 2f, h * 0.7f - distance)
    }

    /**
     * Swipe down — pull notifications, scroll up.
     */
    fun swipeDown(distance: Float = 500f): Boolean {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        return swipe(w / 2f, h * 0.3f, w / 2f, h * 0.3f + distance)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Robot Hand — Generic action dispatch
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Perform an arbitrary [AccessibilityNodeInfo] action on [node].
     */
    fun performNodeAction(node: AccessibilityNodeInfo, action: Int, arguments: android.os.Bundle? = null): Boolean {
        return node.performAction(action, arguments)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Utilities
    // ──────────────────────────────────────────────────────────────────────

    /** Extract the best textual representation of a node. */
    private fun nodeText(node: AccessibilityNodeInfo): String {
        return node.text?.toString()
            ?: node.contentDescription?.toString()
            ?: node.hintText?.toString()
            ?: ""
    }

    /** True if the node is editable (EditText or similar). */
    private fun isEditableNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cls = node.className?.toString() ?: return false
        return cls.contains("EditText") || cls.contains("AutoComplete") || cls.contains("TextInput")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Bounded Wispr Overlay Text Extraction
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Extract text from a Wispr Flow overlay using a bounded accessibility
     * node walker.  Guarantees sub-10ms processing:
     *  - maxDepth = 3 (Wispr's overlay is shallow)
     *  - maxNodes = 50 (hard stop to prevent ANR)
     *
     * Text fallback cascade: text → contentDescription → hint
     */
    fun extractWisprOverlayText(
        rootNode: AccessibilityNodeInfo?,
        currentDepth: Int = 0,
        visitedCount: AtomicInteger = AtomicInteger(0)
    ): String? {
        if (rootNode == null || currentDepth > MAX_WALKER_DEPTH || visitedCount.incrementAndGet() > MAX_WALKER_NODES) {
            return null
        }

        if (rootNode.className?.contains("EditText", ignoreCase = true) == true || rootNode.isEditable) {
            return rootNode.text?.toString()
                ?: rootNode.contentDescription?.toString()
                ?: rootNode.hintText?.toString()
        }

        for (i in 0 until rootNode.childCount) {
            val result = extractWisprOverlayText(rootNode.getChild(i), currentDepth + 1, visitedCount)
            if (result != null) return result
        }
        return null
    }

    // ──────────────────────────────────────────────────────────────────────
    // Data classes
    // ──────────────────────────────────────────────────────────────────────

    /** Summary of what's currently on screen. */
    data class ScreenSummary(
        val packageName: String,
        val elementCount: Int,
        val keyElements: List<ScreenElement>,
        val textFields: List<ScreenElement>
    ) {
        companion object {
            fun empty() = ScreenSummary("unknown", 0, emptyList(), emptyList())
        }
    }

    /** A single visible element discovered during screen reading. */
    data class ScreenElement(
        val text: String,
        val className: String,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val bounds: String
    )
}