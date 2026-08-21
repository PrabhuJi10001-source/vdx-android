package com.vdx.sonic.adapters

import com.vdx.sonic.ScreenModel
import com.vdx.sonic.UiElement

class SettingsAdapter : GenericAdapter() {
    override val packageName: String = "com.android.settings"
    override val displayName: String = "Settings"

    override suspend fun findSearchField(screen: ScreenModel): UiElement? {
        return screen.editableElements.firstOrNull { el ->
            el.hint?.contains("search", ignoreCase = true) == true ||
                el.contentDescription?.contains("search", ignoreCase = true) == true
        } ?: super.findSearchField(screen)
    }
}
