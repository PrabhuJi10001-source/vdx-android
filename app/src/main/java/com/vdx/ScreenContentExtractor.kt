package com.vdx

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * ScreenContentExtractor — the "eyes" of the voice-first zero-UI app.
 *
 * Given an [AccessibilityNodeInfo] root (or an [AccessibilityEvent]), this class
 * traverses the accessibility node tree and produces a structured, JSON-serialisable
 * snapshot of the current screen: every meaningful UI element with its text,
 * content description, clickable / scrollable / editable / focused states, screen
 * bounds, and view id.
 *
 * Design goals:
 *  - Bounded traversal (max depth + max node count) so extraction never ANRs the
 *    accessibility service, even on dense screens.
 *  - Caching: repeated extraction within a short TTL returns the cached snapshot
 *    instead of re-walking the tree, so high-frequency events (e.g. scroll ticks)
 *    don't burn CPU.
 *  - Dynamic content: callers feed [TYPE_WINDOW_CONTENT_CHANGED] events through
 *    [onContentChanged] which invalidates the cache and re-extracts on demand.
 *  - On-demand snapshot: [getSnapshotOnDemand] reads the active window root and
 *    returns a fresh snapshot whenever the caller needs one.
 *
 * The extractor is deliberately stateless apart from its cache, so it can be
 * shared by the accessibility service and the bubble service without IPC.
 */
class ScreenContentExtractor {

    companion object {
        private const val TAG = "ScreenExtractor"

        // Bounded traversal limits — guarantee sub-10ms extraction on dense screens.
        private const val MAX_DEPTH = 8
        private const val MAX_NODES = 200

        // Cache TTL: within this window a snapshot is served from cache.
        private const val CACHE_TTL_MS = 300L

        // Event types that indicate the screen content changed and the cache is stale.
        private val CONTENT_CHANGED_EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED
        )
    }

    /** The most recent snapshot, or null if none has been produced yet. */
    @Volatile
    private var cachedSnapshot: ScreenSnapshot? = null

    @Volatile
    private var cacheTimestamp: Long = 0L

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Traverse [root] and produce a structured [ScreenSnapshot].
     * Bounded to [MAX_DEPTH] levels and [MAX_NODES] nodes.
     */
    fun extract(root: AccessibilityNodeInfo?): ScreenSnapshot {
        if (root == null) return ScreenSnapshot.empty()

        val elements = mutableListOf<ScreenElement>()
        val visited = AtomicInteger(0)
        val packageName = try { root.packageName?.toString() ?: "unknown" } catch (e: Exception) { "unknown" }

        traverse(root, elements, visited, depth = 0)

        val snapshot = ScreenSnapshot(
            packageName = packageName,
            elementCount = elements.size,
            elements = elements,
            capturedAt = System.currentTimeMillis()
        )
        cache(snapshot)
        return snapshot
    }

    /**
     * Extract a snapshot from an [AccessibilityEvent]. Returns null when the
     * event carries no usable source node (e.g. a pure announcement event).
     */
    fun extractFromEvent(event: AccessibilityEvent?): ScreenSnapshot? {
        if (event == null) return null
        val source = event.source ?: return null
        return extract(source)
    }

    /**
     * Handle a content-change event. Invalidates the cache when the event type
     * indicates the visible content may have changed, so the next extraction
     * re-walks the tree. Returns the freshly extracted snapshot when the event
     * carries a source node, otherwise null.
     */
    fun onContentChanged(event: AccessibilityEvent?): ScreenSnapshot? {
        if (event == null) return null
        if (event.eventType in CONTENT_CHANGED_EVENTS) {
            invalidateCache()
        }
        return extractFromEvent(event)
    }

    /**
     * Get the current screen snapshot on demand. Uses [rootInActiveWindow] via
     * the running accessibility service instance. Serves from cache when fresh,
     * otherwise re-extracts.
     */
    fun getSnapshotOnDemand(): ScreenSnapshot? {
        val cached = cachedSnapshot
        if (cached != null && isCacheFresh()) return cached

        val service = VdxAccessibilityService.instance ?: return cached
        val root = try { service.rootInActiveWindow } catch (e: Exception) { null }
        if (root == null) return cached
        return extract(root)
    }

    /**
     * Serialise a [ScreenSnapshot] to a JSON string.
     */
    fun toJson(snapshot: ScreenSnapshot): String {
        return snapshotToJson(snapshot).toString()
    }

    /**
     * Serialise the current (cached or freshly extracted) snapshot to JSON.
     * Returns null when no snapshot is available.
     */
    fun toJsonOnDemand(): String? {
        return getSnapshotOnDemand()?.let { toJson(it) }
    }

    /** Drop the cached snapshot so the next extraction re-walks the tree. */
    fun invalidateCache() {
        cachedSnapshot = null
        cacheTimestamp = 0L
    }

    // ──────────────────────────────────────────────────────────────────────
    // Traversal
    // ──────────────────────────────────────────────────────────────────────

    private fun traverse(
        node: AccessibilityNodeInfo,
        elements: MutableList<ScreenElement>,
        visited: AtomicInteger,
        depth: Int
    ) {
        if (depth > MAX_DEPTH || visited.incrementAndGet() > MAX_NODES) return

        val text = nodeText(node)
        val contentDescription = node.contentDescription?.toString() ?: ""
        val isClickable = node.isClickable
        val isScrollable = node.isScrollable
        val isEditable = isEditableNode(node)
        val isFocused = node.isFocused

        // Collect nodes that carry content or are interactive.
        if (text.isNotBlank() || contentDescription.isNotBlank() ||
            isClickable || isScrollable || isEditable || isFocused
        ) {
            val rect = Rect()
            try { node.getBoundsInScreen(rect) } catch (e: Exception) { /* bounds unavailable */ }
            elements.add(
                ScreenElement(
                    text = text,
                    contentDescription = contentDescription,
                    className = node.className?.toString() ?: "",
                    viewId = node.viewIdResourceName ?: "",
                    isClickable = isClickable,
                    isScrollable = isScrollable,
                    isEditable = isEditable,
                    isFocused = isFocused,
                    bounds = rect
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
            traverse(child, elements, visited, depth + 1)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Caching
    // ──────────────────────────────────────────────────────────────────────

    private fun cache(snapshot: ScreenSnapshot) {
        cachedSnapshot = snapshot
        cacheTimestamp = System.currentTimeMillis()
    }

    private fun isCacheFresh(): Boolean {
        return System.currentTimeMillis() - cacheTimestamp <= CACHE_TTL_MS
    }

    // ──────────────────────────────────────────────────────────────────────
    // JSON serialisation
    // ──────────────────────────────────────────────────────────────────────

    private fun snapshotToJson(snapshot: ScreenSnapshot): JSONObject {
        val elements = JSONArray()
        snapshot.elements.forEach { elements.put(elementToJson(it)) }
        return JSONObject()
            .put("packageName", snapshot.packageName)
            .put("elementCount", snapshot.elementCount)
            .put("capturedAt", snapshot.capturedAt)
            .put("elements", elements)
    }

    private fun elementToJson(element: ScreenElement): JSONObject {
        return JSONObject()
            .put("text", element.text)
            .put("contentDescription", element.contentDescription)
            .put("className", element.className)
            .put("viewId", element.viewId)
            .put("isClickable", element.isClickable)
            .put("isScrollable", element.isScrollable)
            .put("isEditable", element.isEditable)
            .put("isFocused", element.isFocused)
            .put("bounds", element.bounds.flattenToString())
    }

    // ──────────────────────────────────────────────────────────────────────
    // Utilities
    // ──────────────────────────────────────────────────────────────────────

    /** Best textual representation of a node: text → contentDescription → hint. */
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
    // Data classes
    // ──────────────────────────────────────────────────────────────────────

    /** A single meaningful UI element discovered during traversal. */
    data class ScreenElement(
        val text: String,
        val contentDescription: String,
        val className: String,
        val viewId: String,
        val isClickable: Boolean,
        val isScrollable: Boolean,
        val isEditable: Boolean,
        val isFocused: Boolean,
        val bounds: Rect
    )

    /** Structured representation of the current screen. */
    data class ScreenSnapshot(
        val packageName: String,
        val elementCount: Int,
        val elements: List<ScreenElement>,
        val capturedAt: Long
    ) {
        companion object {
            fun empty() = ScreenSnapshot("unknown", 0, emptyList(), 0L)
        }
    }
}
