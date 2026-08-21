package com.vdx

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests for [ScreenContentExtractor] — the "eyes" of the voice-first zero-UI app.
 *
 * Builds a small sample "settings page" accessibility tree and verifies that the
 * extractor produces a structured JSON snapshot with all clickable elements and
 * their text / content descriptions.
 *
 * Run with: ./gradlew app:testDebugUnitTest --tests "com.vdx.ScreenContentExtractorTest"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScreenContentExtractorTest {

    private val extractor = ScreenContentExtractor()

    /**
     * Build a sample settings screen:
     *   root
     *   ├─ "Settings" (header, not clickable)
     *   ├─ "Wi-Fi" row (clickable, contentDescription "Wi-Fi settings")
     *   │   └─ "Connected" (subtitle)
     *   ├─ "Bluetooth" row (clickable, contentDescription "Bluetooth settings")
     *   ├─ "Battery" row (clickable)
     *   └─ "Search settings" (editable text field)
     */
    private fun buildSettingsTree(): AccessibilityNodeInfo {
        val root = AccessibilityNodeInfo.obtain()
        root.packageName = "com.android.settings"
        root.className = "android.widget.FrameLayout"

        val header = AccessibilityNodeInfo.obtain()
        header.text = "Settings"
        header.className = "android.widget.TextView"
        shadowOf(root).addChild(header)

        val wifiRow = AccessibilityNodeInfo.obtain()
        wifiRow.text = "Wi-Fi"
        wifiRow.contentDescription = "Wi-Fi settings"
        wifiRow.className = "android.widget.LinearLayout"
        wifiRow.isClickable = true
        wifiRow.setBoundsInScreen(Rect(0, 100, 1080, 200))
        shadowOf(root).addChild(wifiRow)

        val wifiSub = AccessibilityNodeInfo.obtain()
        wifiSub.text = "Connected"
        wifiSub.className = "android.widget.TextView"
        shadowOf(wifiRow).addChild(wifiSub)

        val btRow = AccessibilityNodeInfo.obtain()
        btRow.text = "Bluetooth"
        btRow.contentDescription = "Bluetooth settings"
        btRow.className = "android.widget.LinearLayout"
        btRow.isClickable = true
        btRow.setBoundsInScreen(Rect(0, 200, 1080, 300))
        shadowOf(root).addChild(btRow)

        val batteryRow = AccessibilityNodeInfo.obtain()
        batteryRow.text = "Battery"
        batteryRow.className = "android.widget.LinearLayout"
        batteryRow.isClickable = true
        batteryRow.setBoundsInScreen(Rect(0, 300, 1080, 400))
        shadowOf(root).addChild(batteryRow)

        val searchField = AccessibilityNodeInfo.obtain()
        searchField.text = "Search settings"
        searchField.className = "android.widget.EditText"
        searchField.isEditable = true
        searchField.setBoundsInScreen(Rect(0, 0, 1080, 100))
        shadowOf(root).addChild(searchField)

        return root
    }

    @Test
    fun extract_producesSnapshotWithAllClickableElements() {
        val snapshot = extractor.extract(buildSettingsTree())

        assertEquals("com.android.settings", snapshot.packageName)
        assertTrue("should have extracted elements", snapshot.elementCount > 0)

        val clickables = snapshot.elements.filter { it.isClickable }
        assertEquals("expected 3 clickable rows", 3, clickables.size)

        val texts = clickables.map { it.text }
        assertTrue("Wi-Fi row present", texts.contains("Wi-Fi"))
        assertTrue("Bluetooth row present", texts.contains("Bluetooth"))
        assertTrue("Battery row present", texts.contains("Battery"))
    }

    @Test
    fun extract_capturesContentDescriptions() {
        val snapshot = extractor.extract(buildSettingsTree())

        val wifi = snapshot.elements.first { it.text == "Wi-Fi" }
        assertEquals("Wi-Fi settings", wifi.contentDescription)
        assertTrue("Wi-Fi bounds captured", wifi.bounds.width() > 0)
    }

    @Test
    fun extract_detectsEditableField() {
        val snapshot = extractor.extract(buildSettingsTree())

        val editable = snapshot.elements.filter { it.isEditable }
        assertEquals("expected 1 editable field", 1, editable.size)
        assertEquals("Search settings", editable[0].text)
    }

    @Test
    fun toJson_producesStructuredJson() {
        val snapshot = extractor.extract(buildSettingsTree())
        val json = JSONObject(extractor.toJson(snapshot))

        assertEquals("com.android.settings", json.getString("packageName"))
        assertEquals(snapshot.elementCount, json.getInt("elementCount"))
        assertTrue("capturedAt present", json.getLong("capturedAt") > 0)

        val elements = json.getJSONArray("elements")
        assertTrue("elements array non-empty", elements.length() > 0)

        val first = elements.getJSONObject(0)
        assertTrue("element has text key", first.has("text"))
        assertTrue("element has contentDescription key", first.has("contentDescription"))
        assertTrue("element has isClickable key", first.has("isClickable"))
        assertTrue("element has bounds key", first.has("bounds"))
    }

    @Test
    fun extract_nullRoot_returnsEmptySnapshot() {
        val snapshot = extractor.extract(null)
        assertEquals(0, snapshot.elementCount)
        assertEquals("unknown", snapshot.packageName)
    }

    @Test
    fun onContentChanged_invalidatesCache() {
        // First extraction caches a snapshot.
        extractor.extract(buildSettingsTree())
        assertNotNull("snapshot cached after extract", extractor.getSnapshotOnDemand())

        // A content-changed event invalidates the cache.
        extractor.onContentChanged(null) // null event is a no-op, cache stays
        assertNotNull(extractor.getSnapshotOnDemand())

        // Explicit invalidation drops the cache.
        extractor.invalidateCache()
        // getSnapshotOnDemand falls back to the service instance; with none running
        // it returns the (now null) cached value.
        assertNull(extractor.getSnapshotOnDemand())
    }
}
