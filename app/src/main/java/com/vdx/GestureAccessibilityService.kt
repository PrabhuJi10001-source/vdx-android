package com.vdx

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * GestureAccessibilityService — a dedicated, gesture-only accessibility service.
 *
 * VDX runs TWO accessibility services (two-service design):
 *  1. [VdxAccessibilityService] — the base service: layout retrieval + view operations.
 *     It does NOT declare canPerformGestures, so it avoids the system lag / frame
 *     drops that a gesture-capable accessibility service can cause on some devices.
 *  2. This service — gesture dispatch only (tap / swipe / scroll). It declares
 *     canPerformGestures=true and does no tree work, keeping its event handling a
 *     no-op so it stays lightweight and never contributes to jank.
 *
 * Callers that need to perform gestures should obtain this service via [instance]
 * and call [tap], [swipe], [swipeUp], or [swipeDown]. [VdxAccessibilityService]
 * delegates its own gesture methods here for backward compatibility.
 */
class GestureAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VdxGestureA11y"

        /**
         * Live reference to the running gesture service instance, or null when the
         * service is not connected. Callers use this to dispatch gestures.
         */
        @Volatile
        var instance: GestureAccessibilityService? = null
            private set

        /** Whether the gesture accessibility service is currently connected. */
        var isRunning: Boolean = false
            private set
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        Log.i(TAG, "VDX Gesture Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately a no-op: this service only dispatches gestures and must not
        // do any tree work, so it never contributes to system lag.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Gesture accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        Log.i(TAG, "VDX Gesture Accessibility Service destroyed")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Gesture dispatch
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
}
