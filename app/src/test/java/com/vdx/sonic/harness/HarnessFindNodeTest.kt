package com.vdx.sonic.harness

import com.vdx.sonic.NodeSelector
import com.vdx.sonic.Rect
import com.vdx.sonic.ScreenModel
import com.vdx.sonic.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HarnessFindNodeTest — unit tests for the multi-condition node search ported
 * from common SmartFinder (AND semantics + index targeting).
 *
 * Acceptance: a selector combining text + hint + className + isEditable +
 * isClickable matches only when ALL non-null fields match, and [index] targets
 * a specific occurrence.
 */
class HarnessFindNodeTest {

    private val harness = Harness()

    private fun el(
        ref: String,
        text: String? = null,
        hint: String? = null,
        className: String = "",
        isEditable: Boolean = false,
        isClickable: Boolean = false,
        isFocused: Boolean = false
    ) = UiElement(
        ref = ref,
        text = text,
        contentDescription = null,
        hint = hint,
        className = className,
        isClickable = isClickable,
        isEditable = isEditable,
        isFocused = isFocused
    )

    private fun screen(vararg elements: UiElement) = ScreenModel(
        packageName = "com.example.app",
        elements = elements.toList()
    )

    @Test
    fun `single field text match`() {
        val s = screen(el("e1", text = "OK", isClickable = true))
        val found = harness.findNode(s, NodeSelector(text = "OK"))
        assertEquals("e1", found?.ref)
    }

    @Test
    fun `multi-condition AND match requires all fields`() {
        val s = screen(
            el("e1", text = "Search", hint = "Search", className = "android.widget.EditText", isEditable = true),
            el("e2", text = "Search", hint = "Search", className = "android.widget.Button", isClickable = true)
        )
        // Only e1 matches editable + EditText
        val found = harness.findNode(
            s,
            NodeSelector(text = "Search", hint = "Search", className = "EditText", isEditable = true)
        )
        assertEquals("e1", found?.ref)
    }

    @Test
    fun `multi-condition fails when one field mismatches`() {
        val s = screen(el("e1", text = "Search", hint = "Search", isEditable = true))
        // isClickable=true mismatches → no match
        val found = harness.findNode(s, NodeSelector(text = "Search", isClickable = true))
        assertNull(found)
    }

    @Test
    fun `index targets specific occurrence`() {
        val s = screen(
            el("e1", text = "Result", isClickable = true),
            el("e2", text = "Result", isClickable = true),
            el("e3", text = "Result", isClickable = true)
        )
        assertEquals("e2", harness.findNode(s, NodeSelector(text = "Result", index = 1))?.ref)
        assertEquals("e3", harness.findNode(s, NodeSelector(text = "Result", index = 2))?.ref)
    }

    @Test
    fun `no match returns null`() {
        val s = screen(el("e1", text = "OK"))
        assertNull(harness.findNode(s, NodeSelector(text = "Cancel")))
    }

    @Test
    fun `bounds are preserved on matched element`() {
        val s = screen(el("e1", text = "OK", isClickable = true).copy(bounds = Rect(0, 0, 100, 50)))
        val found = harness.findNode(s, NodeSelector(text = "OK"))
        assertEquals(Rect(0, 0, 100, 50), found?.bounds)
    }
}
