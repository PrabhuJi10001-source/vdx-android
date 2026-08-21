package com.vdx.sonic.executor

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.vdx.ScreenContentExtractor
import com.vdx.sonic.IntentMode
import com.vdx.sonic.IntentType
import com.vdx.sonic.ScrollDirection
import com.vdx.sonic.SonicIntent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ActionResolverTest — unit tests for the command-to-action mapping.
 *
 * Acceptance criteria: "A user can say 'tap OK' and the app performs a click on the
 * element with text 'OK' on the current screen, with spoken confirmation."
 *
 * These tests verify the pure mapping layer: a parsed [SonicIntent] (as produced by
 * the voice engine's IntentParser) + a screen snapshot (as produced by
 * ScreenContentExtractor) → a concrete [ResolvedAction].
 *
 * Run with: ./gradlew app:testDebugUnitTest --tests "com.vdx.sonic.executor.ActionResolverTest"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActionResolverTest {

    private val resolver = ActionResolver

    /** Build a snapshot with a clickable "OK" button and a "Delete" button. */
    private fun buildOkScreen(): ScreenContentExtractor.ScreenSnapshot {
        val root = AccessibilityNodeInfo.obtain()
        root.packageName = "com.example.app"
        root.className = "android.widget.FrameLayout"

        val okButton = AccessibilityNodeInfo.obtain()
        okButton.text = "OK"
        okButton.className = "android.widget.Button"
        okButton.isClickable = true
        okButton.setBoundsInScreen(Rect(0, 100, 200, 200))
        shadowOf(root).addChild(okButton)

        val deleteButton = AccessibilityNodeInfo.obtain()
        deleteButton.text = "Delete"
        deleteButton.className = "android.widget.Button"
        deleteButton.isClickable = true
        deleteButton.setBoundsInScreen(Rect(0, 200, 200, 300))
        shadowOf(root).addChild(deleteButton)

        val extractor = ScreenContentExtractor()
        return extractor.extract(root)
    }

    private fun gestureIntent(action: String, target: String? = null, direction: String? = null): SonicIntent {
        val entities = buildMap {
            put("action", action)
            if (target != null) put("target", target)
            if (direction != null) put("direction", direction)
        }
        return SonicIntent(
            mode = IntentMode.COMMAND,
            type = IntentType.GESTURE,
            rawText = "tap $target",
            confidence = 0.95f,
            entities = entities
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Acceptance: "tap OK" → click on element with text "OK"
    // ──────────────────────────────────────────────────────────────

    @Test
    fun tapOk_resolvesToClickOnOkElement() {
        val intent = gestureIntent("tap", "OK")
        val snapshot = buildOkScreen()

        val action = resolver.resolve(intent, snapshot)

        assertTrue("expected a Click action", action is ResolvedAction.Click)
        val click = action as ResolvedAction.Click
        assertEquals("OK", click.target.text)
        assertEquals("OK", click.element?.text)
        assertFalse("OK is not destructive", click.destructive)
    }

    @Test
    fun tapOk_matchesByContentDescription() {
        val intent = gestureIntent("tap", "Confirm")
        val root = AccessibilityNodeInfo.obtain()
        root.packageName = "com.example.app"
        val btn = AccessibilityNodeInfo.obtain()
        btn.contentDescription = "Confirm"
        btn.isClickable = true
        shadowOf(root).addChild(btn)
        val snapshot = ScreenContentExtractor().extract(root)

        val action = resolver.resolve(intent, snapshot)

        assertTrue(action is ResolvedAction.Click)
        assertEquals("Confirm", (action as ResolvedAction.Click).target.contentDescription)
    }

    @Test
    fun tapDelete_marksDestructive() {
        val intent = gestureIntent("tap", "Delete")
        val snapshot = buildOkScreen()

        val action = resolver.resolve(intent, snapshot)

        assertTrue(action is ResolvedAction.Click)
        assertTrue("Delete is destructive", (action as ResolvedAction.Click).destructive)
    }

    @Test
    fun tapMissingElement_returnsUnresolved() {
        val intent = gestureIntent("tap", "Nonexistent")
        val snapshot = buildOkScreen()

        val action = resolver.resolve(intent, snapshot)

        assertTrue(action is ResolvedAction.Unresolved)
        assertTrue((action as ResolvedAction.Unresolved).reason.contains("Nonexistent"))
    }

    @Test
    fun tapWithNoTarget_resolvesToTapCenter() {
        val intent = gestureIntent("tap")
        val action = resolver.resolve(intent, null)

        assertTrue(action is ResolvedAction.TapCenter)
    }

    // ──────────────────────────────────────────────────────────────
    // Scroll
    // ──────────────────────────────────────────────────────────────

    @Test
    fun scrollDown_resolvesToScrollDown() {
        val intent = gestureIntent("scroll", direction = "down")
        val action = resolver.resolve(intent, null)

        assertTrue(action is ResolvedAction.Scroll)
        assertEquals(ScrollDirection.DOWN, (action as ResolvedAction.Scroll).direction)
    }

    @Test
    fun scrollUp_resolvesToScrollUp() {
        val intent = gestureIntent("scroll", direction = "up")
        val action = resolver.resolve(intent, null)

        assertTrue(action is ResolvedAction.Scroll)
        assertEquals(ScrollDirection.UP, (action as ResolvedAction.Scroll).direction)
    }

    // ──────────────────────────────────────────────────────────────
    // Navigation
    // ──────────────────────────────────────────────────────────────

    @Test
    fun goBack_resolvesToGoBack() {
        val intent = SonicIntent(IntentMode.COMMAND, IntentType.GO_BACK, rawText = "go back", confidence = 0.95f)
        val action = resolver.resolve(intent, null)

        assertTrue(action is ResolvedAction.GoBack)
    }

    @Test
    fun goHome_resolvesToGoHome() {
        val intent = SonicIntent(IntentMode.COMMAND, IntentType.GO_HOME, rawText = "go home", confidence = 0.95f)
        val action = resolver.resolve(intent, null)

        assertTrue(action is ResolvedAction.GoHome)
    }

    // ──────────────────────────────────────────────────────────────
    // Text entry
    // ──────────────────────────────────────────────────────────────

    @Test
    fun formFill_resolvesToSetTextOnEditableField() {
        val root = AccessibilityNodeInfo.obtain()
        root.packageName = "com.example.app"
        val field = AccessibilityNodeInfo.obtain()
        field.text = "Search"
        field.className = "android.widget.EditText"
        field.isEditable = true
        field.isFocused = true
        shadowOf(root).addChild(field)
        val snapshot = ScreenContentExtractor().extract(root)

        val intent = SonicIntent(
            IntentMode.DICTATION, IntentType.FORM_FILL, rawText = "hello world",
            confidence = 0.8f, entities = mapOf("text" to "hello world")
        )
        val action = resolver.resolve(intent, snapshot)

        assertTrue(action is ResolvedAction.SetText)
        assertEquals("hello world", (action as ResolvedAction.SetText).text)
        assertEquals("Search", action.target.text)
    }

    @Test
    fun formFill_noEditableField_returnsUnresolved() {
        val intent = SonicIntent(
            IntentMode.DICTATION, IntentType.FORM_FILL, rawText = "hello",
            confidence = 0.8f, entities = mapOf("text" to "hello")
        )
        val action = resolver.resolve(intent, buildOkScreen())

        assertTrue(action is ResolvedAction.Unresolved)
    }

    // ──────────────────────────────────────────────────────────────
    // Unsupported types
    // ──────────────────────────────────────────────────────────────

    @Test
    fun unsupportedIntentType_returnsUnresolved() {
        val intent = SonicIntent(IntentMode.COMMAND, IntentType.CALL, rawText = "call Mom", confidence = 0.95f)
        val action = resolver.resolve(intent, null)

        assertTrue(action is ResolvedAction.Unresolved)
    }
}
