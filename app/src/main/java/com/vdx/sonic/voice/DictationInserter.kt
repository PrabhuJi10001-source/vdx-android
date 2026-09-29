package com.vdx.sonic.voice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.vdx.VdxAccessibilityService

/**
 * DictationInserter — reliably lands dictated text into whatever editable field
 * currently has input focus, using the soniqo/speech-android pattern:
 *
 *  1. The bubble overlay window is non-focusable (see BubbleForegroundService's
 *     FLAG_NOT_FOCUSABLE) so the target text field keeps input focus while the
 *     user dictates.
 *  2. Text is inserted at the cursor with ACTION_SET_TEXT on the focused node.
 *  3. When a field's real content cannot be read — some apps report their
 *     placeholder as the field's own text — fall back to pasting from the
 *     clipboard, then clear the clipboard immediately so the dictation isn't
 *     left behind for the next paste.
 *
 * This is the authoritative insertion path for pure dictation (FORM_FILL /
 * TEXT_EDIT intents). Command intents continue to flow through the full Sonic
 * pipeline; this class only handles the "type what I said into the focused
 * field" case, where reliability of landing the text is the whole point.
 */
class DictationInserter(private val context: Context) {

    companion object {
        private const val TAG = "VDXDictation"
    }

    /**
     * Insert [text] into the currently focused editable field.
     *
     * @return true if the text was inserted (via ACTION_SET_TEXT or clipboard paste).
     */
    fun insert(text: String): Boolean {
        if (text.isBlank()) {
            Log.w(TAG, "insert: blank text, nothing to insert")
            return false
        }
        val a11y = VdxAccessibilityService.instance ?: run {
            Log.w(TAG, "insert: accessibility service not running")
            return false
        }
        val node = a11y.findFocusedTextField() ?: run {
            Log.w(TAG, "insert: no focused editable field")
            return false
        }
        return insertInto(node, text)
    }

    /**
     * Insert [text] into a specific editable [node].
     */
    fun insertInto(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) {
            Log.w(TAG, "insertInto: node is not editable")
            return false
        }
        // If the field's real content cannot be read (some apps report their
        // placeholder as the field's own text), ACTION_SET_TEXT would clobber
        // the placeholder as if it were real content. Fall back to paste.
        if (cannotReadRealContent(node)) {
            return pasteAndClear(node, text)
        }
        // Normal path: ACTION_SET_TEXT at the cursor.
        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val ok = try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            Log.w(TAG, "insertInto: ACTION_SET_TEXT threw", e)
            false
        }
        Log.i(TAG, "insertInto(ACTION_SET_TEXT \"$text\") → $ok")
        if (!ok) {
            // ACTION_SET_TEXT failed — fall back to clipboard paste.
            return pasteAndClear(node, text)
        }
        return true
    }

    /**
     * True when the field's reported text is actually its placeholder, meaning
     * the app is not exposing the real content and ACTION_SET_TEXT would be
     * unsafe (it would replace the placeholder as if it were real text).
     */
    private fun cannotReadRealContent(node: AccessibilityNodeInfo): Boolean {
        val hint = try { node.hintText?.toString() } catch (e: Exception) { null }
        if (hint.isNullOrBlank()) return false
        val text = try { node.text?.toString() } catch (e: Exception) { null }
        if (text == hint) return true
        // Some apps expose the placeholder via contentDescription instead.
        val contentDesc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
        return contentDesc == hint
    }

    /**
     * Paste [text] into [node] via the clipboard, then clear the clipboard so
     * the dictation isn't left behind for the next paste.
     */
    private fun pasteAndClear(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: run {
                Log.w(TAG, "pasteAndClear: clipboard not available")
                return false
            }
        // The dictation replaces whatever was on the clipboard.
        clipboard.setPrimaryClip(ClipData.newPlainText("vdx_dictation", text))
        // Focus the field so the paste lands at the cursor.
        try { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (e: Exception) {}
        val ok = try {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            Log.w(TAG, "pasteAndClear: ACTION_PASTE threw", e)
            false
        }
        Log.i(TAG, "pasteAndClear(ACTION_PASTE \"$text\") → $ok")
        // Clear the clipboard right after so the dictation isn't left behind.
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        return ok
    }
}
