package com.vdx.sonic.adapters

import com.vdx.sonic.ScreenModel
import com.vdx.sonic.UiElement

/**
 * UberAdapter — app-specific selectors for Uber.
 */
class UberAdapter : GenericAdapter() {

    override val packageName: String = "com.ubercab"
    override val displayName: String = "Uber"

    override suspend fun findDestinationField(screen: ScreenModel): UiElement? {
        return screen.editableElements.firstOrNull { el ->
            el.hint?.contains("where", ignoreCase = true) == true ||
            el.hint?.contains("destination", ignoreCase = true) == true ||
            el.hint?.contains("go", ignoreCase = true) == true ||
            el.contentDescription?.contains("where to", ignoreCase = true) == true ||
            el.contentDescription?.contains("destination", ignoreCase = true) == true
        }
    }

    override suspend fun findPriceInfo(screen: ScreenModel): String? {
        // Uber shows prices like "$12.50" or "₹312"
        return screen.elements.firstOrNull { el ->
            el.text?.matches(Regex("""[\$₹€£]\s*\d+\.?\d*""")) == true ||
            el.text?.contains("min", ignoreCase = true) == true ||
            el.text?.contains("fare", ignoreCase = true) == true
        }?.text
    }

    override suspend fun findConfirmButton(screen: ScreenModel): UiElement? {
        return screen.clickableElements.firstOrNull { el ->
            el.text?.contains("confirm", ignoreCase = true) == true ||
            el.text?.contains("book", ignoreCase = true) == true ||
            el.text?.contains("request", ignoreCase = true) == true ||
            el.contentDescription?.contains("confirm", ignoreCase = true) == true ||
            el.contentDescription?.contains("request", ignoreCase = true) == true
        }
    }
}
