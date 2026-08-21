package com.vdx.sonic.adapters

import com.vdx.sonic.ScreenModel
import com.vdx.sonic.UiElement

class PhoneAdapter : GenericAdapter() {
    override val packageName: String = "com.android.dialer"
    override val displayName: String = "Phone"

    override suspend fun findSearchField(screen: ScreenModel): UiElement? {
        return screen.editableElements.firstOrNull { el ->
            el.hint?.contains("search", ignoreCase = true) == true ||
                el.hint?.contains("name", ignoreCase = true) == true ||
                el.contentDescription?.contains("search", ignoreCase = true) == true
        } ?: super.findSearchField(screen)
    }

    override suspend fun findCallButton(screen: ScreenModel): UiElement? {
        return screen.clickableElements.firstOrNull { el ->
            el.contentDescription?.contains("call", ignoreCase = true) == true ||
                el.text?.equals("call", ignoreCase = true) == true
        } ?: super.findCallButton(screen)
    }
}
