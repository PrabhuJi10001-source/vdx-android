package com.vdx.sonic.adapters

import com.vdx.sonic.ScreenModel
import com.vdx.sonic.UiElement

class YouTubeAdapter : GenericAdapter() {
    override val packageName = "com.google.android.youtube"
    override val displayName = "YouTube"

    override suspend fun findSearchField(screen: ScreenModel): UiElement? =
        screen.editableElements.firstOrNull {
            it.contentDescription?.contains("search", true) == true ||
                it.hint?.contains("search", true) == true
        } ?: screen.clickableElements.firstOrNull {
            it.contentDescription?.contains("Search", true) == true
        } ?: super.findSearchField(screen)
}
