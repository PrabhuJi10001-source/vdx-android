package com.vdx.sonic.adapters

import com.vdx.sonic.ScreenModel
import com.vdx.sonic.UiElement

class GmailAdapter : GenericAdapter() {
    override val packageName = "com.google.android.gm"
    override val displayName = "Gmail"

    override suspend fun findSearchField(screen: ScreenModel): UiElement? =
        screen.editableElements.firstOrNull {
            it.contentDescription?.contains("search", true) == true ||
                it.hint?.contains("search", true) == true
        } ?: super.findSearchField(screen)
}
