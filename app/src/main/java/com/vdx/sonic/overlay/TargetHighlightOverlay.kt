package com.vdx.sonic.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.vdx.VdxAccessibilityService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * TargetHighlightOverlay — GAP 1.
 *
 * A lightweight, on-demand visual overlay that draws subtle rectangles over the
 * interactive nodes of the current screen (clickable / scrollable / editable) so
 * a low-vision user can see exactly where to tap.
 *
 * Design:
 *  - Reuses the existing screen "eyes" ([VdxAccessibilityService.screenExtractor]
 *    via [com.vdx.ScreenContentExtractor.getSnapshotOnDemand]) to obtain element
 *    bounds in screen coordinates. No new tree traversal is added.
 *  - Paints via a custom [View] whose [View.onDraw] draws each actionable node's
 *    [Rect] as a semi-transparent outline. No image assets, no heavy libraries.
 *  - Touch-invisible: the overlay WindowManager.LayoutParams use FLAG_NOT_TOUCHABLE
 *    and FLAG_NOT_FOCUSABLE, so taps pass straight through to the real app below.
 *  - Degrades gracefully: if the overlay permission is missing or the tree exposes
 *    no actionable elements, [show] is a no-op (log only) and never throws.
 *  - Default OFF. Callers flip it with [toggle] / [setVisible].
 */
class TargetHighlightOverlay(
    private val context: Context,
    private val windowManager: WindowManager
) {

    companion object {
        private const val TAG = "TargetHighlight"

        /** Subtle blue outline — semi-transparent so app content stays readable. */
        private val OUTLINE_COLOR = Color.argb(0xE0, 0x3B, 0x82, 0xF6)

        /** Very faint fill so the target area is easy to spot without hiding it. */
        private val FILL_COLOR = Color.argb(0x14, 0x3B, 0x82, 0xF6)

        /** Max interactive rects drawn, to stay cheap on dense screens. */
        private const val MAX_RECTS = 80

        private fun dpToPx(context: Context, dp: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
    }

    private val visible = AtomicBoolean(false)
    private var view: HighlightView? = null

    /** True when the overlay is currently painted on screen. */
    fun isVisible(): Boolean = visible.get()

    /** Flip the overlay: if visible, hide; otherwise re-read the screen and show. */
    fun toggle() {
        if (visible.get()) hide() else show()
    }

    /** Force a specific visibility state. */
    fun setVisible(showOverlay: Boolean) {
        if (showOverlay) show() else hide()
    }

    /** Hide and remove the overlay (idempotent; safe to call from any lifecycle path). */
    fun hide() {
        val v = view ?: return
        view = null
        visible.set(false)
        try {
            windowManager.removeView(v)
        } catch (e: Exception) {
            // View may already have been removed (e.g. onDestroy ordering). Non-fatal.
            Log.w(TAG, "hide: failed to remove overlay view", e)
        }
    }

    /**
     * Re-read the accessibility tree and paint outlines over actionable nodes.
     * No-op (no crash) when overlay permission is missing or no actionable nodes
     * are present — the overlay simply stays hidden.
     */
    fun show() {
        if (visible.get()) return
        if (!canDrawOverlays()) {
            Log.w(TAG, "show: overlay permission not granted — showing nothing")
            return
        }
        val rects = collectActionableRects()
        if (rects.isEmpty()) {
            Log.d(TAG, "show: no actionable elements on screen — showing nothing")
            return
        }

        val params = buildLayoutParams()
        val v = HighlightView(context).apply { setRects(rects) }
        try {
            windowManager.addView(v, params)
        } catch (e: Exception) {
            Log.w(TAG, "show: could not add overlay view", e)
            return
        }
        view = v
        visible.set(true)
        Log.i(TAG, "show: highlighted ${rects.size} actionable elements")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internals
    // ──────────────────────────────────────────────────────────────────────

    /** Collect bounds of clickable / scrollable / editable nodes that are on-screen. */
    private fun collectActionableRects(): List<Rect> {
        val extractor = VdxAccessibilityService.screenExtractor ?: return emptyList()
        val snapshot = extractor.getSnapshotOnDemand() ?: return emptyList()

        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        return snapshot.elements.asSequence()
            .filter { it.isClickable || it.isScrollable || it.isEditable }
            .map { it.bounds }
            .filter { r ->
                !r.isEmpty && r.right > 0 && r.left < screenW && r.bottom > 0 && r.top < screenH
            }
            .take(MAX_RECTS)
            .toList()
    }

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
        else true

    /** Full-screen, touch-invisible, top-layer overlay. */
    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    /**
     * Custom View that paints the actionable rectangles on demand.
     * Touch-invisible by window flags, so it never intercepts user taps.
     */
    private class HighlightView(context: Context) : View(context) {

        private val rects = AtomicReference<List<Rect>>(emptyList())

        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = OUTLINE_COLOR
            strokeWidth = dpToPx(context, 2f)
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = FILL_COLOR
        }

        fun setRects(newRects: List<Rect>) {
            rects.set(newRects)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (r in rects.get()) {
                canvas.drawRect(r, fillPaint)
                canvas.drawRect(r, outlinePaint)
            }
        }
    }
}
