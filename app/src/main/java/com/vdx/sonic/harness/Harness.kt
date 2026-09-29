package com.vdx.sonic.harness

import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import com.vdx.sonic.Rect
import com.vdx.sonic.ScreenModel
import com.vdx.sonic.UiElement
import com.vdx.sonic.NodeSelector
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * Harness — semantic UI reader for VDX Sonic.
 *
 * Reads the current Android UI via the accessibility tree and produces
 * a structured [ScreenModel] for the Planner and RobotHand.
 *
 * Event-driven first, brute-force rescanning second.
 * Bounded traversal (max depth 5, max nodes 100) prevents ANR.
 */
class Harness {

    companion object {
        private const val TAG = "Sonic-Harness"
        private const val MAX_DEPTH = 5
        private const val MAX_NODES = 100
        private const val CACHE_TTL_MS = 500L
    }

    // Simple cache: last screen model + timestamp
    @Volatile
    private var cachedScreen: ScreenModel? = null
    @Volatile
    private var cacheTime: Long = 0

    /**
     * Read the current screen from the accessibility service.
     * Returns cached result if called within [CACHE_TTL_MS].
     */
    fun readScreen(service: android.accessibilityservice.AccessibilityService?): ScreenModel {
        val now = System.currentTimeMillis()
        cachedScreen?.let { if (now - cacheTime < CACHE_TTL_MS) return it }

        val root = service?.rootInActiveWindow
        if (root == null) {
            val empty = ScreenModel(packageName = "unknown")
            cachedScreen = empty
            cacheTime = now
            return empty
        }

        val model = buildScreenModel(root)
        cachedScreen = model
        cacheTime = now
        return model
    }

    /**
     * Force a fresh read, bypassing cache.
     */
    fun refresh(service: android.accessibilityservice.AccessibilityService?): ScreenModel {
        cacheTime = 0
        return readScreen(service)
    }

    /**
     * Invalidate cache (call when an accessibility event fires).
     */
    fun invalidateCache() {
        cacheTime = 0
    }

    /**
     * Multi-condition node search (ported from common
     * SmartFinder). Matches a node only if ALL non-null selector fields match
     * (AND semantics). Unlike RobotHand's single-field findNode, this supports
     * combining text + hint + className + isEditable + isClickable + isFocused
     * in one query, and can target a specific occurrence via [index].
     *
     * Returns the first matching [UiElement] in tree order, or null.
     */
    fun findNode(screen: ScreenModel, selector: NodeSelector): UiElement? {
        val matches = screen.elements.filter { matchesAll(it, selector) }
        if (matches.isEmpty()) return null
        val idx = selector.index ?: 0
        return matches.getOrNull(idx)
    }

    /**
     * Wait until a node matching [selector] appears, re-reading the screen each
     * poll. Returns the matched element, or null on timeout. Ported from
     * common waitFor()/require() primitives.
     */
    suspend fun waitForNode(
        service: android.accessibilityservice.AccessibilityService?,
        selector: NodeSelector,
        timeoutMs: Long = 8000L
    ): UiElement? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val screen = refresh(service)
            findNode(screen, selector)?.let { return it }
            delay(200)
        }
        return null
    }

    /**
     * Validate [screen] for accessibility compliance (touch-target size,
     * content-description presence, and focus order). Pure delegation to
     * [AccessibilityCompliance.check]; returns an empty list when compliant.
     */
    fun checkCompliance(
        screen: ScreenModel,
        minTouchTargetSize: Int = AccessibilityCompliance.DEFAULT_MIN_TOUCH_TARGET_SIZE
    ): List<ComplianceViolation> =
        AccessibilityCompliance.check(screen, minTouchTargetSize)

    /**
     * AND-match a node against all non-null selector fields.
     */
    private fun matchesAll(el: UiElement, sel: NodeSelector): Boolean {
        if (sel.text != null && !el.text.equals(sel.text, ignoreCase = true) &&
            !(el.text?.contains(sel.text, ignoreCase = true) == true)) return false
        if (sel.contentDescription != null && !el.contentDescription.equals(sel.contentDescription, ignoreCase = true) &&
            !(el.contentDescription?.contains(sel.contentDescription, ignoreCase = true) == true)) return false
        if (sel.hint != null && !el.hint.equals(sel.hint, ignoreCase = true) &&
            !(el.hint?.contains(sel.hint, ignoreCase = true) == true)) return false
        if (sel.className != null && !el.className.contains(sel.className, ignoreCase = true)) return false
        if (sel.resourceId != null && !el.packageName.contains(sel.resourceId, ignoreCase = true)) return false
        if (sel.isEditable != null && el.isEditable != sel.isEditable) return false
        if (sel.isClickable != null && el.isClickable != sel.isClickable) return false
        if (sel.isFocused != null && el.isFocused != sel.isFocused) return false
        return true
    }

    // ──────────────────────────────────────────────────────────────
    // Internal
    // ──────────────────────────────────────────────────────────────

    private fun buildScreenModel(root: AccessibilityNodeInfo): ScreenModel {
        val elements = mutableListOf<UiElement>()
        val clickable = mutableListOf<UiElement>()
        val editable = mutableListOf<UiElement>()
        val scrollable = mutableListOf<UiElement>()
        val counter = AtomicInteger(0)

        traverse(root, elements, clickable, editable, scrollable, counter, 0)

        val packageName = try { root.packageName?.toString() ?: "unknown" } catch (e: Exception) { "unknown" }

        // Find focused editable field
        val focusedEditable = editable.firstOrNull { it.isFocused }

        return ScreenModel(
            packageName = packageName,
            isEditableFieldFocused = focusedEditable != null,
            focusedFieldText = focusedEditable?.text,
            focusedFieldHint = focusedEditable?.hint,
            elements = elements,
            clickableElements = clickable,
            editableElements = editable,
            scrollableContainers = scrollable,
            windowCount = 1,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        elements: MutableList<UiElement>,
        clickable: MutableList<UiElement>,
        editable: MutableList<UiElement>,
        scrollable: MutableList<UiElement>,
        counter: AtomicInteger,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        if (counter.incrementAndGet() > MAX_NODES) return

        val text = safeText(node)
        val contentDesc = safeContentDescription(node)
        val hint = safeHint(node)
        val isClickable = node.isClickable
        val isEditable = node.isEditable || isEditTextClass(node)
        val isFocused = node.isFocused
        val isScrollable = node.isScrollable
        val className = safeClassName(node)
        val pkg = safePackage(node)
        val bounds = safeBounds(node)
        val childCount = node.childCount

        // Build supported actions list
        val actions = mutableListOf<String>()
        if (isClickable) actions.add("click")
        if (isEditable) actions.add("set_text")
        if (isFocused) actions.add("focused")
        if (isScrollable) actions.add("scroll")
        if (node.isLongClickable) actions.add("long_click")
        if (node.isCheckable) actions.add("check")
        if (node.isAccessibilityFocused) actions.add("accessibility_focused")

        val ref = "e${counter.get()}"

        val element = UiElement(
            ref = ref,
            text = text,
            contentDescription = contentDesc,
            hint = hint,
            className = className,
            packageName = pkg,
            isClickable = isClickable,
            isEditable = isEditable,
            isFocused = isFocused,
            isScrollable = isScrollable,
            isChecked = if (node.isCheckable) node.isChecked else null,
            bounds = bounds,
            childCount = childCount,
            supportedActions = actions
        )

        // Only collect nodes with content or interactivity
        if (text != null || contentDesc != null || isClickable || isEditable) {
            elements.add(element)
            if (isClickable) clickable.add(element)
            if (isEditable) editable.add(element)
            if (isScrollable) scrollable.add(element)
        }

        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            traverse(child, elements, clickable, editable, scrollable, counter, depth + 1)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Safe accessors (handle exceptions from stale nodes)
    // ──────────────────────────────────────────────────────────────

    private fun safeText(node: AccessibilityNodeInfo): String? = try {
        node.text?.toString()
    } catch (e: Exception) { null }

    private fun safeContentDescription(node: AccessibilityNodeInfo): String? = try {
        node.contentDescription?.toString()
    } catch (e: Exception) { null }

    private fun safeHint(node: AccessibilityNodeInfo): String? = try {
        node.hintText?.toString()
    } catch (e: Exception) { null }

    private fun safeClassName(node: AccessibilityNodeInfo): String = try {
        node.className?.toString() ?: ""
    } catch (e: Exception) { "" }

    private fun safePackage(node: AccessibilityNodeInfo): String = try {
        node.packageName?.toString() ?: ""
    } catch (e: Exception) { "" }

    private fun safeBounds(node: AccessibilityNodeInfo): Rect? = try {
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        Rect(r.left, r.top, r.right, r.bottom)
    } catch (e: Exception) { null }

    private fun isEditTextClass(node: AccessibilityNodeInfo): Boolean {
        val cls = safeClassName(node)
        return cls.contains("EditText", ignoreCase = true) ||
               cls.contains("AutoComplete", ignoreCase = true) ||
               cls.contains("TextInput", ignoreCase = true) ||
               cls.contains("SearchView", ignoreCase = true)
    }
}
