package com.vdx.sonic.adapters

import com.vdx.sonic.ScreenModel
import com.vdx.sonic.UiElement

/**
 * WhatsAppAdapter — app-specific selectors for WhatsApp.
 */
class WhatsAppAdapter : GenericAdapter() {

    override val packageName: String = "com.whatsapp"
    override val displayName: String = "WhatsApp"

    override suspend fun findContactField(screen: ScreenModel): UiElement? {
        // WhatsApp's contact search field
        return screen.editableElements.firstOrNull { el ->
            el.hint?.contains("search", ignoreCase = true) == true ||
            el.contentDescription?.contains("search", ignoreCase = true) == true ||
            el.hint?.contains("type", ignoreCase = true) == true
        }
    }

    override suspend fun findMessageField(screen: ScreenModel): UiElement? {
        // WhatsApp's message input
        return screen.editableElements.firstOrNull { el ->
            el.hint?.contains("message", ignoreCase = true) == true ||
            el.hint?.contains("type a", ignoreCase = true) == true ||
            el.contentDescription?.contains("type a message", ignoreCase = true) == true ||
            el.hint?.contains("text", ignoreCase = true) == true
        }
    }

    override suspend fun findSendButton(screen: ScreenModel): UiElement? {
        // WhatsApp's send button (paper plane icon)
        return screen.clickableElements.firstOrNull { el ->
            el.contentDescription?.contains("send", ignoreCase = true) == true ||
            el.className.contains("ImageButton", ignoreCase = true)
        }
    }

    override suspend fun findSearchField(screen: ScreenModel): UiElement? {
        return screen.editableElements.firstOrNull { el ->
            el.hint?.contains("search", ignoreCase = true) == true
        }
    }
}
