package com.vdx.sonic.harness

import com.vdx.sonic.ScreenModel
import com.vdx.sonic.UiElement

/**
 * Accessibility compliance validation for VDX Sonic.
 *
 * A pure, unit-testable checker modeled on Google's
 * accessibility-test-framework-for-android. It inspects a [ScreenModel] and
 * returns a list of [ComplianceViolation]s for common WCAG / Android
 * accessibility failures:
 *
 *  1. **Touch target size** — clickable elements whose bounds are smaller than
 *     the recommended minimum (default 48x48). Bounds are compared in the same
 *     units as [com.vdx.sonic.Rect] (screen pixels); callers that need dp can
 *     pass a density-scaled threshold.
 *  2. **Missing content description** — clickable elements that expose neither
 *     text nor a contentDescription, so a screen reader has nothing to announce.
 *  3. **Focus order** — interactive elements that are focusable but not
 *     accessibility-focused, so they are unreachable via accessibility focus.
 *
 * The checker makes no Android framework calls — it operates entirely on the
 * data already present in [ScreenModel] / [UiElement], so it is trivially
 * testable with plain JVM unit tests.
 */
object AccessibilityCompliance {

    /** Default minimum touch-target edge length, in the same units as bounds. */
    const val DEFAULT_MIN_TOUCH_TARGET_SIZE = 48

    /** Marker string added to [UiElement.supportedActions] when the node is accessibility-focused. */
    private const val ACTION_ACCESSIBILITY_FOCUSED = "accessibility_focused"

    /**
     * Run all compliance checks against [screen].
     *
     * @param minTouchTargetSize minimum edge length (width and height) a
     *   clickable element's bounds must meet to pass the touch-target check.
     * @return the list of violations found, in tree order. Empty when compliant.
     */
    fun check(
        screen: ScreenModel,
        minTouchTargetSize: Int = DEFAULT_MIN_TOUCH_TARGET_SIZE
    ): List<ComplianceViolation> {
        val violations = mutableListOf<ComplianceViolation>()
        for (element in screen.elements) {
            checkTouchTargetSize(element, minTouchTargetSize)?.let { violations += it }
            checkMissingContentDescription(element)?.let { violations += it }
            checkFocusOrder(element)?.let { violations += it }
        }
        return violations
    }

    /**
     * A clickable element whose bounds are smaller than [minTouchTargetSize] in
     * either dimension is too small to be a reliable touch target.
     */
    private fun checkTouchTargetSize(
        element: UiElement,
        minTouchTargetSize: Int
    ): ComplianceViolation? {
        if (!element.isClickable) return null
        val bounds = element.bounds ?: return null
        if (bounds.width >= minTouchTargetSize && bounds.height >= minTouchTargetSize) return null
        return ComplianceViolation(
            type = ViolationType.TOUCH_TARGET_TOO_SMALL,
            elementRef = element.ref,
            message = "Clickable element '${element.ref}' is ${bounds.width}x${bounds.height}, " +
                "smaller than the recommended ${minTouchTargetSize}x${minTouchTargetSize} touch target.",
            severity = Severity.WARNING
        )
    }

    /**
     * A clickable element with neither text nor a contentDescription is
     * invisible to screen readers.
     */
    private fun checkMissingContentDescription(element: UiElement): ComplianceViolation? {
        if (!element.isClickable) return null
        if (!element.text.isNullOrBlank() || !element.contentDescription.isNullOrBlank()) return null
        return ComplianceViolation(
            type = ViolationType.MISSING_CONTENT_DESCRIPTION,
            elementRef = element.ref,
            message = "Clickable element '${element.ref}' has no text and no contentDescription.",
            severity = Severity.ERROR
        )
    }

    /**
     * An interactive (focusable) element that is not accessibility-focused is
     * unreachable by a screen reader's focus navigation.
     */
    private fun checkFocusOrder(element: UiElement): ComplianceViolation? {
        if (!isFocusable(element)) return null
        if (element.supportedActions.contains(ACTION_ACCESSIBILITY_FOCUSED)) return null
        return ComplianceViolation(
            type = ViolationType.FOCUS_ORDER,
            elementRef = element.ref,
            message = "Focusable element '${element.ref}' is not accessibility-focused.",
            severity = Severity.WARNING
        )
    }

    /**
     * An element is considered focusable when it is interactive — clickable,
     * editable, or scrollable. These are the nodes a user must be able to reach
     * via accessibility focus.
     */
    private fun isFocusable(element: UiElement): Boolean =
        element.isClickable || element.isEditable || element.isScrollable
}

/** The category of an accessibility [ComplianceViolation]. */
enum class ViolationType {
    TOUCH_TARGET_TOO_SMALL,
    MISSING_CONTENT_DESCRIPTION,
    FOCUS_ORDER
}

/** How severe a [ComplianceViolation] is. */
enum class Severity {
    ERROR,
    WARNING
}

/**
 * A single accessibility compliance failure found in a [ScreenModel].
 *
 * @param type the category of failure.
 * @param elementRef the [UiElement.ref] of the offending node.
 * @param message a human-readable description of the failure.
 * @param severity how severe the failure is.
 */
data class ComplianceViolation(
    val type: ViolationType,
    val elementRef: String,
    val message: String,
    val severity: Severity = Severity.WARNING
)
