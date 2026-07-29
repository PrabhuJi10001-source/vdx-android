package com.vdx.sonic.adapters

import com.vdx.sonic.ScreenModel
import com.vdx.sonic.UiElement
import com.vdx.sonic.AppAdapter

/**
 * GenericAdapter — default app adapter that works for any app using standard UI patterns.
 *
 * Uses heuristic selectors (hint text, content description, class names) to find
 * common UI elements. App-specific adapters override these for known apps.
 */
open class GenericAdapter : AppAdapter {

    override val packageName: String = "*"
    override val displayName: String = "Generic"

    override suspend fun findContactField(screen: ScreenModel): UiElement? {
        return screen.editableElements.firstOrNull { el ->
            el.hint?.contains("contact", ignoreCase = true) == true ||
            el.hint?.contains("to", ignoreCase = true) == true ||
            el.hint?.contains("recipient", ignoreCase = true) == true ||
            el.contentDescription?.contains("contact", ignoreCase = true) == true ||
            el.contentDescription?.contains("to", ignoreCase = true) == true
        }
    }

    override suspend fun findMessageField(screen: ScreenModel): UiElement? {
        return screen.editableElements.firstOrNull { el ->
            el.hint?.contains("message", ignoreCase = true) == true ||
            el.hint?.contains("type", ignoreCase = true) == true ||
            el.hint?.contains("text", ignoreCase = true) == true ||
            el.hint?.contains("write", ignoreCase = true) == true ||
            el.contentDescription?.contains("message", ignoreCase = true) == true ||
            el.contentDescription?.contains("input", ignoreCase = true) == true
        }
    }

    override suspend fun findSendButton(screen: ScreenModel): UiElement? {
        return screen.clickableElements.firstOrNull { el ->
            el.text?.contains("send", ignoreCase = true) == true ||
            el.contentDescription?.contains("send", ignoreCase = true) == true ||
            el.text?.contains("submit", ignoreCase = true) == true ||
            el.contentDescription?.contains("submit", ignoreCase = true) == true
        }
    }

    override suspend fun findSearchField(screen: ScreenModel): UiElement? {
        return screen.editableElements.firstOrNull { el ->
            el.hint?.contains("search", ignoreCase = true) == true ||
            el.contentDescription?.contains("search", ignoreCase = true) == true ||
            el.hint?.contains("find", ignoreCase = true) == true
        }
    }

    override suspend fun findDestinationField(screen: ScreenModel): UiElement? {
        return screen.editableElements.firstOrNull { el ->
            el.hint?.contains("destination", ignoreCase = true) == true ||
            el.hint?.contains("where", ignoreCase = true) == true ||
            el.hint?.contains("location", ignoreCase = true) == true ||
            el.hint?.contains("go", ignoreCase = true) == true ||
            el.contentDescription?.contains("destination", ignoreCase = true) == true
        }
    }

    override suspend fun findPriceInfo(screen: ScreenModel): String? {
        return screen.elements.firstOrNull { el ->
            el.text?.contains("$", ignoreCase = false) == true ||
            el.text?.contains("₹", ignoreCase = false) == true ||
            el.text?.contains("€", ignoreCase = false) == true ||
            el.text?.contains("£", ignoreCase = false) == true ||
            el.text?.contains("price", ignoreCase = true) == true ||
            el.text?.contains("fare", ignoreCase = true) == true ||
            el.text?.contains("total", ignoreCase = true) == true
        }?.text
    }

    override suspend fun findConfirmButton(screen: ScreenModel): UiElement? {
        return screen.clickableElements.firstOrNull { el ->
            el.text?.contains("confirm", ignoreCase = true) == true ||
            el.text?.contains("book", ignoreCase = true) == true ||
            el.text?.contains("order", ignoreCase = true) == true ||
            el.text?.contains("request", ignoreCase = true) == true ||
            el.contentDescription?.contains("confirm", ignoreCase = true) == true ||
            el.contentDescription?.contains("book", ignoreCase = true) == true
        }
    }

    override suspend fun findCallButton(screen: ScreenModel): UiElement? {
        return screen.clickableElements.firstOrNull { el ->
            el.text?.contains("call", ignoreCase = true) == true ||
            el.contentDescription?.contains("call", ignoreCase = true) == true ||
            el.contentDescription?.contains("dial", ignoreCase = true) == true ||
            el.className.contains("Button", ignoreCase = true) &&
            (el.text?.contains("phone", ignoreCase = true) == true ||
             el.contentDescription?.contains("phone", ignoreCase = true) == true)
        }
    }

    override suspend fun findDialPad(screen: ScreenModel): UiElement? {
        return screen.clickableElements.firstOrNull { el ->
            el.className.contains("Button", ignoreCase = true) &&
            el.text?.matches(Regex("\\d+")) == true
        }
    }
}
