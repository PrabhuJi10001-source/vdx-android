package com.vdx.sonic.adapters

import android.content.Context
import com.vdx.sonic.AppAdapter
import com.vdx.sonic.IntentType

/** Resolves app adapters for full-depth planning. */
object AdapterRegistry {
    private val whatsApp = WhatsAppAdapter()
    private val uber = UberAdapter()
    private val phone = PhoneAdapter()
    private val settings = SettingsAdapter()
    private val gmail = GmailAdapter()
    private val youtube = YouTubeAdapter()
    private val playStore = PlayStoreAdapter()
    private val chrome = ChromeAdapter()
    private val generic = GenericAdapter()

    fun forIntent(type: IntentType): AppAdapter = when (type) {
        IntentType.WHATSAPP, IntentType.SMS -> whatsApp
        IntentType.BOOK_RIDE -> uber
        IntentType.CALL, IntentType.CONTACT_MANAGE -> phone
        IntentType.SETTINGS_NAVIGATION, IntentType.SYSTEM_TOGGLE -> settings
        IntentType.EMAIL -> gmail
        IntentType.YOUTUBE_SEARCH, IntentType.YOUTUBE_CONTROL -> youtube
        IntentType.PLAY_STORE -> playStore
        IntentType.SEARCH -> chrome
        else -> generic
    }

    fun forPackage(packageName: String): AppAdapter = when {
        packageName.contains("whatsapp", ignoreCase = true) -> whatsApp
        packageName.contains("ubercab", ignoreCase = true) -> uber
        packageName.contains("dialer", ignoreCase = true) ||
            packageName.contains("contacts", ignoreCase = true) -> phone
        packageName.contains("settings", ignoreCase = true) -> settings
        packageName.contains("gm", ignoreCase = true) ||
            packageName.contains("gmail", ignoreCase = true) -> gmail
        packageName.contains("youtube", ignoreCase = true) -> youtube
        packageName.contains("vending", ignoreCase = true) -> playStore
        packageName.contains("chrome", ignoreCase = true) -> chrome
        else -> generic
    }

    @Suppress("UNUSED_PARAMETER")
    fun warm(context: Context) { }
}
