package com.vdx

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * RobotHand — the app navigation executor for VDX.
 *
 * Takes a parsed [VdxIntent] and executes it step by step inside target apps using
 * [VdxAccessibilityService].  This is NOT deep-link navigation — it launches the app,
 * waits for it to load, then walks the accessibility tree to find fields, type text,
 * and tap elements, just like Louie Voice Control does.
 *
 * Design principles for blind / low-vision users:
 *  - Speak a brief confirmation before starting ("Calling Mom", "Opening WhatsApp for Ravi").
 *  - Execute steps with a 500 ms delay between actions so the UI has time to settle.
 *  - Speak the result ("Message ready to send" or "Could not find contact field").
 *  - If any step fails, stop immediately and speak what went wrong.
 *  - For destructive / confirmable actions (send message, book ride, send SMS),
 *    DO NOT tap the final button — the user confirms manually.
 *
 * All execution happens on a background thread with a simple sleep-based delay.
 * The caller (typically [BubbleForegroundService]) is responsible for updating the
 * bubble state machine.
 */
class RobotHand(
    private val context: Context,
    private val tts: TextToSpeech? = null
) {

    companion object {
        private const val TAG = "RobotHand"
        private const val STEP_DELAY_MS = 500L
        private const val DEFAULT_APP_TIMEOUT_MS = 8000L

        // Known package names for apps we navigate inside.
        private const val PKG_WHATSAPP = "com.whatsapp"
        private const val PKG_UBER = "com.ubercab"
        private const val PKG_YOUTUBE = "com.google.android.youtube"
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Execute a parsed [VdxIntent] step by step.
     * Returns a human-readable result string suitable for TTS.
     */
    fun execute(intent: VdxIntent): String {
        return try {
            when (intent) {
                is VdxIntent.Call -> executeCall(intent)
                is VdxIntent.WhatsApp -> executeWhatsApp(intent)
                is VdxIntent.Uber -> executeUber(intent)
                is VdxIntent.YouTube -> executeYouTube(intent)
                is VdxIntent.Sms -> executeSms(intent)
                is VdxIntent.Email -> executeEmail(intent)
                is VdxIntent.AppLaunch -> executeAppLaunch(intent)
                is VdxIntent.ReadScreen -> executeReadScreen()
                is VdxIntent.ReadSms -> executeReadSms()
                is VdxIntent.Memory -> executeMemory()
                is VdxIntent.Clarification -> {
                    speak(intent.question)
                    "Clarification needed: ${intent.question}"
                }
                is VdxIntent.Unknown -> "I didn't understand. Try: call, whatsapp, uber, youtube, sms, email, open."
            }
        } catch (e: Exception) {
            Log.e(TAG, "execute failed", e)
            speak("Something went wrong: ${e.message ?: "unknown error"}")
            "Execution failed: ${e.message}"
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // CALL
    // ──────────────────────────────────────────────────────────────────────

    private fun executeCall(intent: VdxIntent.Call): String {
        speak("Calling ${intent.contact}")
        Log.i(TAG, "CALL: ${intent.contact}")

        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            // Only set the tel: URI if the contact looks like a phone number
            if (intent.contact.matches(Regex("^[\\d+\\-\\s()]+$"))) {
                data = Uri.parse("tel:${intent.contact}")
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(dialIntent)
        sleep(STEP_DELAY_MS)
        return "Dialing ${intent.contact}. Press call to confirm."
    }

    // ──────────────────────────────────────────────────────────────────────
    // WHATSAPP
    // ──────────────────────────────────────────────────────────────────────

    private fun executeWhatsApp(intent: VdxIntent.WhatsApp): String {
        val contact = intent.contact
        val message = intent.message
        speak("Opening WhatsApp for $contact")
        Log.i(TAG, "WHATSAPP: contact=$contact, message=$message")

        // 1. Launch WhatsApp
        if (!launchApp(PKG_WHATSAPP)) {
            speak("WhatsApp is not installed")
            return "WhatsApp not installed"
        }

        // 2. Wait for WhatsApp to load
        if (!waitForApp(PKG_WHATSAPP, DEFAULT_APP_TIMEOUT_MS)) {
            speak("WhatsApp did not load")
            return "WhatsApp did not load in time"
        }
        sleep(STEP_DELAY_MS)

        val a11y = accessibilityService()
        if (a11y == null) {
            speak("Accessibility service is not running")
            return "Accessibility service not running"
        }

        // 3. Find the search/contacts field
        //    WhatsApp's search field has contentDescription "Search" or hint text "Search"
        var root = a11y.rootInActiveWindow
        if (root == null) {
            speak("Could not read WhatsApp screen")
            return "Could not read WhatsApp screen"
        }

        val searchField = findEditableNode(root, "Search")
            ?: findNodeByContentDescription(root, "Search")
            ?: findNodeByText(root, "Search")
        if (searchField == null) {
            speak("Could not find contact search field")
            return "Could not find contact search field"
        }

        // 4. Tap the search field and type the contact name
        if (!a11y.clickNode(searchField)) {
            speak("Could not tap search field")
            return "Could not tap search field"
        }
        sleep(STEP_DELAY_MS)
        if (!a11y.insertText(searchField, contact)) {
            speak("Could not type contact name")
            return "Could not type contact name"
        }
        sleep(STEP_DELAY_MS * 2) // wait for search results

        // 5. Tap the contact in the results
        root = a11y.rootInActiveWindow
        if (root == null) {
            speak("Could not read search results")
            return "Could not read search results"
        }
        val contactNode = findNodeByText(root, contact)
            ?: findNodeByContentDescription(root, contact)
        if (contactNode == null) {
            speak("Could not find $contact in results")
            return "Could not find $contact in results"
        }
        if (!a11y.clickNode(contactNode)) {
            speak("Could not open contact chat")
            return "Could not open contact chat"
        }
        sleep(STEP_DELAY_MS * 2) // wait for chat to open

        // 6. Find the message input field and type the message
        //    DO NOT tap send — user confirms.
        if (message.isBlank()) {
            speak("Chat open. No message to type.")
            return "Chat open for $contact. No message specified."
        }

        root = a11y.rootInActiveWindow
        if (root == null) {
            speak("Could not read chat screen")
            return "Could not read chat screen"
        }
        val messageField = findEditableNode(root, "Message")
            ?: findNodeByContentDescription(root, "Type a message")
            ?: findNodeByText(root, "Type a message")
        if (messageField == null) {
            speak("Could not find message field")
            return "Could not find message input field"
        }
        if (!a11y.clickNode(messageField)) {
            speak("Could not tap message field")
            return "Could not tap message field"
        }
        sleep(STEP_DELAY_MS)
        if (!a11y.insertText(messageField, message)) {
            speak("Could not type message")
            return "Could not type message"
        }

        speak("Message ready to send")
        return "Message ready to send for $contact: $message"
    }

    // ──────────────────────────────────────────────────────────────────────
    // UBER
    // ──────────────────────────────────────────────────────────────────────

    private fun executeUber(intent: VdxIntent.Uber): String {
        val destination = intent.destination
        speak("Opening Uber for $destination")
        Log.i(TAG, "UBER: destination=$destination")

        // 1. Launch Uber
        if (!launchApp(PKG_UBER)) {
            speak("Uber is not installed")
            return "Uber not installed"
        }

        // 2. Wait for Uber to load
        if (!waitForApp(PKG_UBER, DEFAULT_APP_TIMEOUT_MS)) {
            speak("Uber did not load")
            return "Uber did not load in time"
        }
        sleep(STEP_DELAY_MS)

        val a11y = accessibilityService()
        if (a11y == null) {
            speak("Accessibility service is not running")
            return "Accessibility service not running"
        }

        // 3. Find the destination field ("Where to?")
        val root = a11y.rootInActiveWindow
        if (root == null) {
            speak("Could not read Uber screen")
            return "Could not read Uber screen"
        }
        val destField = findEditableNode(root, "Where to")
            ?: findNodeByText(root, "Where to")
            ?: findNodeByContentDescription(root, "Where to")
            ?: findNodeByText(root, "destination")
        if (destField == null) {
            speak("Could not find destination field")
            return "Could not find destination field"
        }

        // 4. Tap the destination field and type the destination
        if (!a11y.clickNode(destField)) {
            speak("Could not tap destination field")
            return "Could not tap destination field"
        }
        sleep(STEP_DELAY_MS)
        if (!a11y.insertText(destField, destination)) {
            speak("Could not type destination")
            return "Could not type destination"
        }
        sleep(STEP_DELAY_MS * 3) // wait for results to populate

        // 5. DO NOT tap book — user confirms.
        speak("Destination set. Confirm to book your ride.")
        return "Destination set to $destination. Confirm to book."
    }

    // ──────────────────────────────────────────────────────────────────────
    // YOUTUBE
    // ──────────────────────────────────────────────────────────────────────

    private fun executeYouTube(intent: VdxIntent.YouTube): String {
        val query = intent.searchQuery
        speak("Searching YouTube for $query")
        Log.i(TAG, "YOUTUBE: query=$query")

        // 1. Launch YouTube
        if (!launchApp(PKG_YOUTUBE)) {
            speak("YouTube is not installed")
            return "YouTube not installed"
        }

        // 2. Wait for YouTube to load
        if (!waitForApp(PKG_YOUTUBE, DEFAULT_APP_TIMEOUT_MS)) {
            speak("YouTube did not load")
            return "YouTube did not load in time"
        }
        sleep(STEP_DELAY_MS)

        val a11y = accessibilityService()
        if (a11y == null) {
            speak("Accessibility service is not running")
            return "Accessibility service not running"
        }

        // 3. Find the search field
        var root = a11y.rootInActiveWindow
        if (root == null) {
            speak("Could not read YouTube screen")
            return "Could not read YouTube screen"
        }
        val searchField = findNodeByContentDescription(root, "Search")
            ?: findNodeByContentDescription(root, "Search YouTube")
            ?: findNodeByText(root, "Search")
        if (searchField == null) {
            speak("Could not find search field")
            return "Could not find search field"
        }

        // 4. Tap search, type query, tap search button
        if (!a11y.clickNode(searchField)) {
            speak("Could not tap search field")
            return "Could not tap search field"
        }
        sleep(STEP_DELAY_MS)

        root = a11y.rootInActiveWindow
        if (root == null) {
            speak("Could not read search input")
            return "Could not read search input"
        }
        val inputField = findEditableNode(root, query)
            ?: findEditableNode(root, "Search")
            ?: findFocusedEditable(root)
        if (inputField == null) {
            speak("Could not find search input field")
            return "Could not find search input field"
        }
        if (!a11y.insertText(inputField, query)) {
            speak("Could not type search query")
            return "Could not type search query"
        }
        sleep(STEP_DELAY_MS)

        // Tap the search button (usually has contentDescription "Search" or is an ImageView)
        val searchButton = findClickableByContentDescription(root, "Search")
            ?: findClickableByText(root, "Search")
        if (searchButton != null) {
            a11y.clickNode(searchButton)
        } else {
            // Press Enter on the keyboard via IME action
            inputField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            // Fallback: simulate Enter key press
            val args = Bundle()
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, 0)
            inputField.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args)
        }
        sleep(STEP_DELAY_MS * 3) // wait for results

        // 5. Tap the first result
        root = a11y.rootInActiveWindow
        if (root == null) {
            speak("Could not read search results")
            return "Could not read search results"
        }
        val firstResult = findFirstClickable(root)
        if (firstResult == null) {
            speak("Could not find any results")
            return "Could not find any results"
        }
        if (!a11y.clickNode(firstResult)) {
            speak("Could not open first result")
            return "Could not open first result"
        }

        speak("Playing $query on YouTube")
        return "Playing $query on YouTube"
    }

    // ──────────────────────────────────────────────────────────────────────
    // SMS
    // ──────────────────────────────────────────────────────────────────────

    private fun executeSms(intent: VdxIntent.Sms): String {
        val contact = intent.contact
        val message = intent.message
        speak("Opening messages for $contact")
        Log.i(TAG, "SMS: contact=$contact, message=$message")

        // 1. Launch default messaging app
        val smsIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_MESSAGING)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(smsIntent)
        sleep(STEP_DELAY_MS * 2) // give it time to load

        val a11y = accessibilityService()
        if (a11y == null) {
            speak("Accessibility service is not running")
            return "Accessibility service not running"
        }

        // 2. Dismiss any interstitial (sign-in prompt, "New in Gmail", etc.)
        dismissInterstitials(a11y)

        // 3. Find the "new message" / compose button
        var root = a11y.rootInActiveWindow
        if (root == null) {
            speak("Could not read messaging screen")
            return "Could not read messaging screen"
        }
        val newMsgButton = findClickableByContentDescription(root, "New message")
            ?: findClickableByContentDescription(root, "Compose")
            ?: findClickableByContentDescription(root, "Start new conversation")
            ?: findClickableByText(root, "New message")
            ?: findClickableByText(root, "Compose")
            ?: findClickableByResourceId(root, "com.google.android.apps.messaging:id/start_new_conversation_button")
            ?: findClickableByContentDescription(root, "Start chat")
        if (newMsgButton == null) {
            speak("Could not find new message button")
            return "Could not find new message button"
        }
        if (!a11y.clickNode(newMsgButton)) {
            speak("Could not tap new message button")
            return "Could not tap new message button"
        }
        sleep(STEP_DELAY_MS * 2)

        // 3. Type the contact name in the recipient field
        root = a11y.rootInActiveWindow
        if (root == null) {
            speak("Could not read compose screen")
            return "Could not read compose screen"
        }
        val recipientField = findEditableNode(root, "To")
            ?: findEditableNode(root, "Recipients")
            ?: findEditableNode(root, "contact")
            ?: findNodeByContentDescription(root, "To")
        if (recipientField == null) {
            speak("Could not find recipient field")
            return "Could not find recipient field"
        }
        if (!a11y.clickNode(recipientField)) {
            speak("Could not tap recipient field")
            return "Could not tap recipient field"
        }
        sleep(STEP_DELAY_MS)
        if (!a11y.insertText(recipientField, contact)) {
            speak("Could not type contact name")
            return "Could not type contact name"
        }
        sleep(STEP_DELAY_MS * 2)

        // 4. Type the message in the body field
        if (message.isBlank()) {
            speak("Recipient set. No message to type.")
            return "Recipient set to $contact. No message specified."
        }
        root = a11y.rootInActiveWindow
        if (root == null) {
            speak("Could not read message screen")
            return "Could not read message screen"
        }
        val messageField = findEditableNode(root, "Message")
            ?: findEditableNode(root, "Text message")
            ?: findNodeByContentDescription(root, "Message")
        if (messageField == null) {
            speak("Could not find message field")
            return "Could not find message input field"
        }
        if (!a11y.clickNode(messageField)) {
            speak("Could not tap message field")
            return "Could not tap message field"
        }
        sleep(STEP_DELAY_MS)
        if (!a11y.insertText(messageField, message)) {
            speak("Could not type message")
            return "Could not type message"
        }

        // 5. DO NOT tap send — user confirms.
        speak("Message ready to send")
        return "Message ready to send to $contact: $message"
    }

    // ──────────────────────────────────────────────────────────────────────
    // EMAIL
    // ──────────────────────────────────────────────────────────────────────

    private fun executeEmail(intent: VdxIntent.Email): String {
        val contact = intent.contact
        val message = intent.message
        speak("Opening email for $contact")
        Log.i(TAG, "EMAIL: contact=$contact, message=$message")

        // 1. Launch default email app
        val emailIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_EMAIL)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(emailIntent)
        } catch (e: Exception) {
            speak("No email app found")
            return "No email app found"
        }
        sleep(STEP_DELAY_MS * 2)

        val a11y = accessibilityService()
        if (a11y == null) {
            speak("Accessibility service is not running")
            return "Accessibility service not running"
        }

        // 2. Dismiss any interstitial (sign-in prompt, "New in Gmail", etc.)
        dismissInterstitials(a11y)

        // 3. Find the compose / new email button
        var root = a11y.rootInActiveWindow
        if (root == null) {
            speak("Could not read email screen")
            return "Could not read email screen"
        }
        val composeButton = findClickableByContentDescription(root, "Compose")
            ?: findClickableByContentDescription(root, "New email")
            ?: findClickableByContentDescription(root, "Write")
            ?: findClickableByText(root, "Compose")
        if (composeButton == null) {
            speak("Could not find compose button")
            return "Could not find compose button"
        }
        if (!a11y.clickNode(composeButton)) {
            speak("Could not tap compose button")
            return "Could not tap compose button"
        }
        sleep(STEP_DELAY_MS * 2)

        // 3. Type the contact in the To field
        root = a11y.rootInActiveWindow
        if (root == null) {
            speak("Could not read compose screen")
            return "Could not read compose screen"
        }
        val toField = findEditableNode(root, "To")
            ?: findEditableNode(root, "Recipients")
            ?: findNodeByContentDescription(root, "To")
        if (toField == null) {
            speak("Could not find recipient field")
            return "Could not find recipient field"
        }
        if (!a11y.clickNode(toField)) {
            speak("Could not tap recipient field")
            return "Could not tap recipient field"
        }
        sleep(STEP_DELAY_MS)
        if (!a11y.insertText(toField, contact)) {
            speak("Could not type recipient")
            return "Could not type recipient"
        }
        sleep(STEP_DELAY_MS * 2)

        // 4. Type the message in the body field
        if (message.isBlank()) {
            speak("Recipient set. No message to type.")
            return "Recipient set to $contact. No message specified."
        }
        root = a11y.rootInActiveWindow
        if (root == null) {
            speak("Could not read email body")
            return "Could not read email body"
        }
        val bodyField = findEditableNode(root, "Message")
            ?: findEditableNode(root, "Body")
            ?: findEditableNode(root, "Compose email")
        if (bodyField == null) {
            speak("Could not find message body field")
            return "Could not find message body field"
        }
        if (!a11y.clickNode(bodyField)) {
            speak("Could not tap message body")
            return "Could not tap message body"
        }
        sleep(STEP_DELAY_MS)
        if (!a11y.insertText(bodyField, message)) {
            speak("Could not type message")
            return "Could not type message"
        }

        // 5. DO NOT tap send — user confirms.
        speak("Email ready to send")
        return "Email ready to send to $contact: $message"
    }

    // ──────────────────────────────────────────────────────────────────────
    // APP_LAUNCH
    // ──────────────────────────────────────────────────────────────────────

    private fun executeAppLaunch(intent: VdxIntent.AppLaunch): String {
        val appName = intent.appName
        speak("Opening $appName")
        Log.i(TAG, "APP_LAUNCH: $appName")

        val pm = context.packageManager

        // 1. Try known package name aliases for common apps
        val knownPackages = mapOf(
            "phone" to "com.google.android.dialer",
            "dialer" to "com.google.android.dialer",
            "settings" to "com.android.settings",
            "camera" to "com.google.android.GoogleCamera",
            "messages" to "com.google.android.apps.messaging",
            "messaging" to "com.google.android.apps.messaging",
            "sms" to "com.google.android.apps.messaging",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "calculator" to "com.google.android.calculator",
            "calendar" to "com.google.android.calendar",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "youtube" to "com.google.android.youtube",
            "play store" to "com.android.vending",
            "playstore" to "com.android.vending",
            "clock" to "com.google.android.deskclock",
            "alarm" to "com.google.android.deskclock",
            "contacts" to "com.google.android.contacts",
            "files" to "com.google.android.apps.nbu.files",
            "whatsapp" to "com.whatsapp",
            "uber" to "com.ubercab"
        )
        val knownPkg = knownPackages[appName.lowercase()]
        if (knownPkg != null) {
            val launchIntent = pm.getLaunchIntentForPackage(knownPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                sleep(STEP_DELAY_MS)
                speak("$appName open")
                return "$appName open"
            }
        }

        // 2. Fallback: search by app label
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().equals(appName, ignoreCase = true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(appName, ignoreCase = true) }

        if (match == null) {
            speak("Could not find app $appName")
            return "Could not find app $appName"
        }

        val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
        if (launchIntent == null) {
            speak("Could not launch $appName")
            return "Could not launch $appName"
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        sleep(STEP_DELAY_MS)

        speak("$appName open")
        return "$appName open"
    }

    // ──────────────────────────────────────────────────────────────────────
    // READ_SCREEN
    // ──────────────────────────────────────────────────────────────────────

    private fun executeReadScreen(): String {
        speak("Reading screen")
        Log.i(TAG, "READ_SCREEN")

        val a11y = accessibilityService()
        if (a11y == null) {
            speak("Accessibility service is not running")
            return "Accessibility service not running"
        }

        val summary = a11y.readScreen()
        if (summary.elementCount == 0) {
            speak("Screen is empty")
            return "Screen is empty"
        }

        // Build a concise spoken summary
        val sb = StringBuilder()
        sb.append("On ${summary.packageName}. ")
        if (summary.textFields.isNotEmpty()) {
            sb.append("Text field available. ")
        }
        // Speak up to 5 key elements
        val keyItems = summary.keyElements.take(5)
        if (keyItems.isNotEmpty()) {
            sb.append("I see: ")
            keyItems.forEachIndexed { i, el ->
                if (el.text.isNotBlank()) {
                    sb.append(el.text)
                    if (i < keyItems.size - 1) sb.append(", ")
                }
            }
        }
        val spoken = sb.toString().trim().ifBlank { "Screen has ${summary.elementCount} elements" }
        speak(spoken)
        return spoken
    }

    // ──────────────────────────────────────────────────────────────────────
    // READ_SMS (opens messaging app — full reading is future work)
    // ──────────────────────────────────────────────────────────────────────

    private fun executeReadSms(): String {
        speak("Opening messages")
        Log.i(TAG, "READ_SMS")

        val smsIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_MESSAGING)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(smsIntent)
        sleep(STEP_DELAY_MS * 2)

        // Try to read the screen for recent messages
        val a11y = accessibilityService()
        if (a11y == null) {
            speak("Messages open. Accessibility not running for reading.")
            return "Messages open. Accessibility not running."
        }

        val summary = a11y.readScreen()
        if (summary.elementCount == 0) {
            speak("Could not read messages")
            return "Could not read messages"
        }

        val sb = StringBuilder()
        sb.append("Messages. ")
        val items = summary.keyElements.take(5).filter { it.text.isNotBlank() }
        if (items.isNotEmpty()) {
            sb.append("Recent: ")
            items.forEachIndexed { i, el ->
                sb.append(el.text)
                if (i < items.size - 1) sb.append(", ")
            }
        }
        val spoken = sb.toString().trim()
        speak(spoken)
        return spoken
    }

    // ──────────────────────────────────────────────────────────────────────
    // MEMORY
    // ──────────────────────────────────────────────────────────────────────

    private fun executeMemory(): String {
        // RobotHand doesn't own session memory — return a hint
        val msg = "Memory is handled by the session service."
        speak(msg)
        return msg
    }

    // ──────────────────────────────────────────────────────────────────────
    // App Launch & Wait Helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Launch an app by package name.  Returns true if the launch intent was
     * successfully dispatched.
     */
    private fun launchApp(packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                Log.w(TAG, "launchApp: no launch intent for $packageName")
                return false
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "launchApp failed for $packageName", e)
            false
        }
    }

    /**
     * Poll until the target app's window is active, or timeout.
     * Checks the root accessibility window's package name.
     *
     * @return true if the app's window became active before the timeout.
     */
    fun waitForApp(packageName: String, timeoutMs: Long = DEFAULT_APP_TIMEOUT_MS): Boolean {
        val a11y = accessibilityService()
        if (a11y == null) {
            Log.w(TAG, "waitForApp: accessibility service not running")
            // Can't check window — assume app loaded after a fixed delay
            sleep(timeoutMs.coerceAtMost(2000))
            return true
        }

        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val root = a11y.rootInActiveWindow
            if (root != null) {
                val pkg = root.packageName?.toString() ?: ""
                if (pkg.equals(packageName, ignoreCase = true)) {
                    Log.i(TAG, "waitForApp: $packageName is active")
                    return true
                }
            }
            sleep(200) // poll interval
        }
        Log.w(TAG, "waitForApp: timed out waiting for $packageName")
        return false
    }

    // ──────────────────────────────────────────────────────────────────────
    // Accessibility Tree Search Helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Find a node whose [contentDescription] contains [text] (case-insensitive).
     * Searches the entire subtree rooted at [root].
     */
    fun findNodeByContentDescription(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val needle = text.lowercase().trim()
        return searchTree(root) { node ->
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            cd.contains(needle)
        }
    }

    /**
     * Find a node whose visible text or content description contains [text]
     * (case-insensitive).
     */
    fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val needle = text.lowercase().trim()
        return searchTree(root) { node ->
            val t = node.text?.toString()?.lowercase() ?: ""
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            val hint = node.hintText?.toString()?.lowercase() ?: ""
            t.contains(needle) || cd.contains(needle) || hint.contains(needle)
        }
    }

    /**
     * Find an editable node whose text, hint, or content description contains [query].
     */
    private fun findEditableNode(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val needle = query.lowercase().trim()
        return searchTree(root) { node ->
            if (!isEditable(node)) return@searchTree false
            val t = node.text?.toString()?.lowercase() ?: ""
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            val hint = node.hintText?.toString()?.lowercase() ?: ""
            t.contains(needle) || cd.contains(needle) || hint.contains(needle)
        }
    }

    /**
     * Find the first editable node in the tree (typically the focused one).
     */
    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return searchTree(root) { it.isEditable }
    }

    /**
     * Find a clickable node whose content description contains [text].
     */
    private fun findClickableByContentDescription(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val needle = text.lowercase().trim()
        return searchTree(root) { node ->
            node.isClickable && (node.contentDescription?.toString()?.lowercase()?.contains(needle) == true)
        }
    }

    /**
     * Find a clickable node whose text or content description contains [text].
     */
    private fun findClickableByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val needle = text.lowercase().trim()
        return searchTree(root) { node ->
            if (!node.isClickable) return@searchTree false
            val t = node.text?.toString()?.lowercase() ?: ""
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            t.contains(needle) || cd.contains(needle)
        }
    }

    /**
     * Find a clickable node whose resource-id contains [id].
     */
    private fun findClickableByResourceId(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val needle = id.lowercase().trim()
        return searchTree(root) { node ->
            node.isClickable && (node.viewIdResourceName?.lowercase()?.contains(needle) == true)
        }
    }

    /**
     * Find the first clickable node in the tree (for tapping the first search result, etc.).
     */
    private fun findFirstClickable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return searchTree(root) { it.isClickable }
    }

    /**
     * Dismiss common interstitial dialogs that block the UI:
     * - "Got it" / "OK" / "Dismiss" buttons
     * - Sign-in prompts
     * - Feature announcements
     *
     * Taps the dismiss button if found, then waits for the screen to settle.
     */
    private fun dismissInterstitials(a11y: VdxAccessibilityService) {
        val dismissTexts = listOf("Got it", "OK", "Dismiss", "Skip", "Not now", "Later", "Cancel")
        for (attempt in 1..3) {
            val root = a11y.rootInActiveWindow ?: break
            val dismissButton = dismissTexts.firstNotNullOfOrNull { text ->
                findClickableByText(root, text)
                    ?: findClickableByContentDescription(root, text)
            } ?: break
            Log.i(TAG, "dismissInterstitials: tapping dismiss button")
            a11y.clickNode(dismissButton)
            sleep(STEP_DELAY_MS)
        }
    }

    /**
     * Recursively search the accessibility tree with a predicate.
     * Returns the first matching node, or null.
     */
    private fun searchTree(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = searchTree(child, predicate)
            if (result != null) return result
        }
        return null
    }

    // ──────────────────────────────────────────────────────────────────────
    // Utilities
    // ──────────────────────────────────────────────────────────────────────

    /** True if the node is editable (EditText or similar). */
    private fun isEditable(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cls = node.className?.toString() ?: return false
        return cls.contains("EditText") || cls.contains("AutoComplete") || cls.contains("TextInput")
    }

    /** Get the live VdxAccessibilityService instance, or null. */
    private fun accessibilityService(): VdxAccessibilityService? {
        return VdxAccessibilityService.instance
    }

    /** Speak via TTS if available. */
    private fun speak(text: String) {
        Log.i(TAG, "TTS: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "robot_hand_utterance")
    }

    /** Sleep the current thread for [ms] milliseconds. */
    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}