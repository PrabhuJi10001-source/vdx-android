package com.vdx.sonic.flows

import com.vdx.sonic.*

/**
 * FlowCatalog — full-depth execution plans under tap-to-talk.
 *
 * Each plan is step-by-step accessibility (or SystemAction) coverage matching
 * V1 capability for that domain. User still controls when to
 * speak/click; we do not force continuous beep-menus.
 */
object FlowCatalog {

    private fun idSeq(): () -> String {
        var i = 0
        return { "s${i++}" }
    }

    fun plan(intent: SonicIntent): ExecutionPlan {
        val next = idSeq()
        val steps = when (intent.type) {
            IntentType.CALL -> callFlow(intent, next)
            IntentType.WHATSAPP -> whatsAppFlow(intent, next)
            IntentType.SMS -> smsFlow(intent, next)
            IntentType.EMAIL -> emailFlow(intent, next)
            IntentType.CONTACT_MANAGE -> contactFlow(intent, next)
            IntentType.BOOK_RIDE -> uberFlow(intent, next)
            IntentType.YOUTUBE_SEARCH, IntentType.YOUTUBE_CONTROL -> youtubeFlow(intent, next)
            IntentType.SEARCH -> webFlow(intent, next)
            IntentType.PLAY_STORE -> playStoreFlow(intent, next)
            IntentType.APP_LAUNCH, IntentType.APP_SWITCH -> appLaunchFlow(intent, next)
            IntentType.GO_BACK -> listOf(ActionStep(next(), ActionPrimitive.GoBack, "Go back"))
            IntentType.GESTURE -> gestureFlow(intent, next)
            IntentType.GO_HOME -> listOf(
                ActionStep(next(), ActionPrimitive.SystemAction("home"), "Go home")
            )
            IntentType.READ_SCREEN, IntentType.READ_FOCUSED -> listOf(
                ActionStep(next(), ActionPrimitive.ReadUiState(), "Read screen"),
                ActionStep(
                    next(),
                    ActionPrimitive.ReadVisibleResult(NodeSelector()),
                    "Speak visible text"
                )
            )
            IntentType.READ_NOTIFICATIONS -> listOf(
                ActionStep(next(), ActionPrimitive.SystemAction("notifications"), "Open notification shade"),
                ActionStep(next(), ActionPrimitive.ReadUiState(), "Read notifications"),
                ActionStep(next(), ActionPrimitive.ReadVisibleResult(NodeSelector()), "Speak notifications")
            )
            IntentType.READ_PDF -> pdfFlow(intent, next)
            IntentType.DESCRIBE_IMAGE -> listOf(
                ActionStep(
                    next(),
                    ActionPrimitive.AskUser("Point camera or open an image, then tap bubble and say describe."),
                    "Guide image describe"
                )
            )
            IntentType.SYSTEM_QUERY -> systemQueryFlow(intent, next)
            IntentType.SYSTEM_TOGGLE -> systemToggleFlow(intent, next)
            IntentType.SET_ALARM -> alarmFlow(intent, next)
            IntentType.SETTINGS_NAVIGATION -> settingsFlow(intent, next)
            IntentType.FORM_FILL, IntentType.TEXT_EDIT -> listOf(
                ActionStep(
                    next(),
                    ActionPrimitive.SetText(
                        NodeSelector(isEditable = true, isFocused = true),
                        intent.entities["text"] ?: intent.rawText
                    ),
                    "Insert dictation into focused field"
                )
            )
            IntentType.MEMORY_STORE, IntentType.MEMORY_RECALL -> listOf(
                ActionStep(
                    next(),
                    ActionPrimitive.AskUser("Memory: ${intent.entities["value"] ?: intent.entities["key"] ?: intent.rawText}"),
                    "Memory ack"
                )
            )
            IntentType.DRAFT_NOTE -> listOf(
                ActionStep(
                    next(),
                    ActionPrimitive.AskUser("Draft note: ${intent.entities["body"] ?: intent.rawText}"),
                    "Draft note ack"
                )
            )
            IntentType.CANCEL -> listOf(
                // Voice-flow abort — cancel the in-flight command. No action executes.
                ActionStep(next(), ActionPrimitive.GoBack, "Cancel command")
            )
            IntentType.UNKNOWN -> listOf(
                ActionStep(
                    next(),
                    ActionPrimitive.AskUser(
                        intent.clarificationQuestion
                            ?: "I didn't understand. Try WhatsApp, Uber, YouTube, Gmail, call, or settings."
                    ),
                    "Clarify"
                )
            )
        }

        return ExecutionPlan(
            intent = intent,
            steps = steps,
            requiresConfirmation = intent.requiresConfirmation,
            estimatedDurationMs = steps.size * 1800L
        )
    }

    // ── CALL / CONTACTS ──────────────────────────────────────────

    private fun callFlow(intent: SonicIntent, next: () -> String): List<ActionStep> {
        val contact = intent.entities["contact"] ?: intent.entities["number"] ?: ""
        val action = intent.entities["action"] ?: "call"
        return when (action) {
            "lookup" -> listOf(
                ActionStep(
                    next(),
                    ActionPrimitive.SystemAction("contact_search", mapOf("contact" to contact)),
                    "Lookup contact $contact"
                )
            )
            else -> listOf(
                ActionStep(
                    next(),
                    ActionPrimitive.WaitForUserConfirmation("Call $contact?"),
                    "Confirm call"
                ),
                ActionStep(
                    next(),
                    ActionPrimitive.SystemAction("dial", mapOf("contact" to contact, "number" to contact)),
                    "Place call"
                )
            )
        }
    }

    private fun contactFlow(intent: SonicIntent, next: () -> String): List<ActionStep> {
        val action = intent.entities["action"] ?: "search"
        val name = intent.entities["contact"] ?: intent.entities["name"] ?: ""
        val pkg = "com.android.contacts"
        return when (action) {
            "create", "save", "add" -> listOf(
                ActionStep(next(), ActionPrimitive.OpenApp(pkg), "Open Contacts"),
                ActionStep(next(), ActionPrimitive.WaitForPackage(pkg), "Wait Contacts"),
                ActionStep(
                    next(),
                    ActionPrimitive.ClickNode(NodeSelector(contentDescription = "Create contact", isClickable = true)),
                    "Create contact"
                ),
                ActionStep(
                    next(),
                    ActionPrimitive.SetText(NodeSelector(hint = "Name", isEditable = true), name),
                    "Type name"
                ),
                ActionStep(
                    next(),
                    ActionPrimitive.WaitForUserConfirmation("Save contact $name?"),
                    "Confirm save"
                )
            )
            "delete", "block", "edit" -> listOf(
                ActionStep(next(), ActionPrimitive.OpenApp(pkg), "Open Contacts"),
                ActionStep(next(), ActionPrimitive.WaitForPackage(pkg), "Wait"),
                ActionStep(
                    next(),
                    ActionPrimitive.SetText(NodeSelector(hint = "Search", isEditable = true), name),
                    "Search $name"
                ),
                ActionStep(
                    next(),
                    ActionPrimitive.ClickNode(NodeSelector(text = name, isClickable = true)),
                    "Open contact"
                ),
                ActionStep(
                    next(),
                    ActionPrimitive.AskUser("Contact open. Tap $action, or say what to change."),
                    "Await user click/voice"
                )
            )
            else -> listOf(
                ActionStep(
                    next(),
                    ActionPrimitive.SystemAction("contact_search", mapOf("contact" to name)),
                    "Search contact"
                )
            )
        }
    }

    // ── WHATSAPP (full depth) ───────────────────────────────────

    private fun whatsAppFlow(intent: SonicIntent, next: () -> String): List<ActionStep> {
        val contact = intent.entities["contact"] ?: ""
        val message = intent.entities["message"] ?: ""
        val action = intent.entities["action"] ?: if (message.isNotBlank()) "send" else "open"
        val pkg = "com.whatsapp"
        val steps = mutableListOf<ActionStep>()
        steps += ActionStep(next(), ActionPrimitive.OpenApp(pkg), "Open WhatsApp")
        steps += ActionStep(next(), ActionPrimitive.WaitForPackage(pkg), "Wait WhatsApp")
        steps += ActionStep(next(), ActionPrimitive.ReadUiState(), "Read WhatsApp")

        when (action) {
            "call", "voice_call" -> {
                steps += searchAndOpenChat(next, contact)
                steps += ActionStep(
                    next(),
                    ActionPrimitive.ClickNode(NodeSelector(contentDescription = "Voice call", isClickable = true)),
                    "Voice call"
                )
                steps += ActionStep(next(), ActionPrimitive.WaitForUserConfirmation("Place WhatsApp call to $contact?"), "Confirm")
            }
            "video_call" -> {
                steps += searchAndOpenChat(next, contact)
                steps += ActionStep(
                    next(),
                    ActionPrimitive.ClickNode(NodeSelector(contentDescription = "Video call", isClickable = true)),
                    "Video call"
                )
            }
            "reply", "forward", "delete", "block", "location", "share_location" -> {
                steps += searchAndOpenChat(next, contact)
                steps += ActionStep(
                    next(),
                    ActionPrimitive.AskUser("Chat open. Tap $action on screen, or speak the next detail."),
                    "full-depth mid-chat action"
                )
            }
            "send", "message", "open" -> {
                steps += searchAndOpenChat(next, contact)
                if (message.isNotBlank()) {
                    steps += ActionStep(
                        next(),
                        ActionPrimitive.SetText(NodeSelector(hint = "Message", isEditable = true), message),
                        "Type message"
                    )
                    steps += ActionStep(
                        next(),
                        ActionPrimitive.WaitForUserConfirmation("Send to $contact?"),
                        "Confirm send (no auto-send)"
                    )
                    steps += ActionStep(
                        next(),
                        ActionPrimitive.ClickNode(
                            NodeSelector(contentDescription = "Send", isClickable = true)
                        ),
                        "Send"
                    )
                }
            }
            else -> steps += searchAndOpenChat(next, contact)
        }
        return steps
    }

    private fun searchAndOpenChat(next: () -> String, contact: String): List<ActionStep> = listOf(
        ActionStep(
            next(),
            ActionPrimitive.ClickNode(NodeSelector(contentDescription = "Search", isClickable = true)),
            "Open search"
        ),
        ActionStep(
            next(),
            ActionPrimitive.SetText(NodeSelector(hint = "Search", isEditable = true), contact),
            "Search $contact"
        ),
        ActionStep(next(), ActionPrimitive.WaitForPackage("com.whatsapp", 2500), "Wait results"),
        ActionStep(
            next(),
            ActionPrimitive.ClickNode(NodeSelector(text = contact, isClickable = true)),
            "Open chat"
        ),
        ActionStep(next(), ActionPrimitive.WaitForPackage("com.whatsapp", 2000), "Wait chat")
    )

    // ── SMS ──────────────────────────────────────────────────────

    private fun smsFlow(intent: SonicIntent, next: () -> String): List<ActionStep> {
        val contact = intent.entities["contact"] ?: ""
        val message = intent.entities["message"] ?: ""
        val action = intent.entities["action"] ?: if (message.isNotBlank()) "send" else "read"
        return when (action) {
            "read", "browse" -> listOf(
                ActionStep(next(), ActionPrimitive.OpenApp("com.google.android.apps.messaging"), "Open Messages"),
                ActionStep(next(), ActionPrimitive.WaitForPackage("com.google.android.apps.messaging"), "Wait"),
                ActionStep(next(), ActionPrimitive.ReadVisibleResult(NodeSelector()), "Read messages")
            )
            else -> listOf(
                ActionStep(
                    next(),
                    ActionPrimitive.SystemAction(
                        "sms_compose",
                        mapOf("number" to contact, "message" to message)
                    ),
                    "Compose SMS"
                ),
                ActionStep(
                    next(),
                    ActionPrimitive.WaitForUserConfirmation("Send SMS to $contact?"),
                    "Confirm SMS"
                )
            )
        }
    }

    // ── EMAIL / GMAIL ────────────────────────────────────────────

    private fun emailFlow(intent: SonicIntent, next: () -> String): List<ActionStep> {
        val contact = intent.entities["contact"] ?: ""
        val message = intent.entities["message"] ?: ""
        val subject = intent.entities["subject"] ?: ""
        val action = intent.entities["action"] ?: if (message.isNotBlank()) "compose" else "read"
        val pkg = "com.google.android.gm"
        val steps = mutableListOf<ActionStep>()
        steps += ActionStep(next(), ActionPrimitive.OpenApp(pkg), "Open Gmail")
        steps += ActionStep(next(), ActionPrimitive.WaitForPackage(pkg), "Wait Gmail")
        steps += ActionStep(next(), ActionPrimitive.ReadUiState(), "Read inbox")

        when (action) {
            "compose", "send" -> {
                steps += ActionStep(
                    next(),
                    ActionPrimitive.ClickNode(NodeSelector(contentDescription = "Compose", isClickable = true)),
                    "Compose"
                )
                steps += ActionStep(
                    next(),
                    ActionPrimitive.SystemAction(
                        "email_compose",
                        mapOf("contact" to contact, "message" to message, "subject" to subject)
                    ),
                    "Fill compose"
                )
                steps += ActionStep(next(), ActionPrimitive.WaitForUserConfirmation("Send email to $contact?"), "Confirm")
            }
            "reply", "reply_all", "forward", "delete", "star", "block", "unread" -> {
                steps += ActionStep(
                    next(),
                    ActionPrimitive.ClickNode(NodeSelector(isClickable = true, index = 0)),
                    "Open latest / selected mail"
                )
                steps += ActionStep(
                    next(),
                    ActionPrimitive.AskUser("Mail open. Tap $action, or speak next instruction."),
                    "Gmail action"
                )
            }
            "search" -> {
                val q = intent.entities["query"] ?: contact
                steps += ActionStep(
                    next(),
                    ActionPrimitive.ClickNode(NodeSelector(contentDescription = "Search", isClickable = true)),
                    "Search"
                )
                steps += ActionStep(
                    next(),
                    ActionPrimitive.SetText(NodeSelector(isEditable = true), q),
                    "Type query"
                )
            }
            else -> {
                steps += ActionStep(next(), ActionPrimitive.ReadVisibleResult(NodeSelector()), "Read subjects")
            }
        }
        return steps
    }

    // ── UBER ─────────────────────────────────────────────────────

    private fun uberFlow(intent: SonicIntent, next: () -> String): List<ActionStep> {
        val dest = intent.entities["destination"] ?: ""
        val action = intent.entities["action"] ?: "book"
        val pkg = "com.ubercab"
        val steps = mutableListOf<ActionStep>()
        steps += ActionStep(next(), ActionPrimitive.OpenApp(pkg), "Open Uber")
        steps += ActionStep(next(), ActionPrimitive.WaitForPackage(pkg, 10000), "Wait Uber")
        steps += ActionStep(next(), ActionPrimitive.ReadUiState(), "Read Uber")

        when (action) {
            "cancel", "edit", "share", "message_driver", "call_driver" -> {
                steps += ActionStep(
                    next(),
                    ActionPrimitive.AskUser("Trip screen: tap $action, or say details after bubble tap."),
                    "In-trip action"
                )
            }
            else -> {
                steps += ActionStep(
                    next(),
                    ActionPrimitive.ClickNode(NodeSelector(hint = "Where to", isEditable = true)),
                    "Focus destination"
                )
                steps += ActionStep(
                    next(),
                    ActionPrimitive.SetText(NodeSelector(hint = "Where to", isEditable = true), dest),
                    "Type destination"
                )
                steps += ActionStep(next(), ActionPrimitive.WaitForPackage(pkg, 4000), "Wait suggestions")
                steps += ActionStep(
                    next(),
                    ActionPrimitive.ClickNode(NodeSelector(text = dest, isClickable = true)),
                    "Pick suggestion"
                )
                steps += ActionStep(next(), ActionPrimitive.WaitForPackage(pkg, 4000), "Wait fares")
                steps += ActionStep(
                    next(),
                    ActionPrimitive.ReadVisibleResult(NodeSelector()),
                    "Read fares low to high"
                )
                steps += ActionStep(
                    next(),
                    ActionPrimitive.WaitForUserConfirmation("Book Uber to $dest?"),
                    "Confirm book"
                )
            }
        }
        return steps
    }

    // ── YOUTUBE ──────────────────────────────────────────────────

    private fun youtubeFlow(intent: SonicIntent, next: () -> String): List<ActionStep> {
        val query = intent.entities["query"] ?: intent.entities["search"] ?: ""
        val action = intent.entities["action"] ?: if (query.isNotBlank()) "play" else "open"
        val seconds = intent.entities["seconds"] ?: "10"
        val pkg = "com.google.android.youtube"
        val steps = mutableListOf<ActionStep>()
        steps += ActionStep(next(), ActionPrimitive.OpenApp(pkg), "Open YouTube")
        steps += ActionStep(next(), ActionPrimitive.WaitForPackage(pkg), "Wait YouTube")

        when (action) {
            "like" -> steps += ActionStep(next(), ActionPrimitive.ClickNode(NodeSelector(contentDescription = "like", isClickable = true)), "Like")
            "subscribe" -> steps += ActionStep(next(), ActionPrimitive.ClickNode(NodeSelector(text = "Subscribe", isClickable = true)), "Subscribe")
            "share" -> steps += ActionStep(next(), ActionPrimitive.ClickNode(NodeSelector(contentDescription = "Share", isClickable = true)), "Share")
            "comment" -> {
                steps += ActionStep(next(), ActionPrimitive.ClickNode(NodeSelector(contentDescription = "Comment", isClickable = true)), "Comment")
                val text = intent.entities["text"] ?: intent.entities["message"] ?: ""
                if (text.isNotBlank()) {
                    steps += ActionStep(next(), ActionPrimitive.SetText(NodeSelector(isEditable = true), text), "Type comment")
                }
            }
            "forward", "seek_forward" -> steps += ActionStep(
                next(),
                ActionPrimitive.AskUser("Seeking +$seconds seconds — tap player or say again after seek."),
                "Seek forward"
            )
            "rewind", "seek_back" -> steps += ActionStep(
                next(),
                ActionPrimitive.AskUser("Seeking -$seconds seconds — tap player."),
                "Seek back"
            )
            else -> {
                if (query.isNotBlank()) {
                    steps += ActionStep(
                        next(),
                        ActionPrimitive.ClickNode(NodeSelector(contentDescription = "Search", isClickable = true)),
                        "Search"
                    )
                    steps += ActionStep(
                        next(),
                        ActionPrimitive.SetText(NodeSelector(isEditable = true), query),
                        "Type query"
                    )
                    steps += ActionStep(next(), ActionPrimitive.WaitForPackage(pkg, 3000), "Wait results")
                    steps += ActionStep(
                        next(),
                        ActionPrimitive.ClickNode(NodeSelector(isClickable = true, index = 0)),
                        "Play first result"
                    )
                }
            }
        }
        return steps
    }

    // ── WEB / PLAY STORE / SETTINGS / SYSTEM ─────────────────────

    private fun webFlow(intent: SonicIntent, next: () -> String): List<ActionStep> {
        val query = intent.entities["query"] ?: intent.rawText
        return listOf(
            ActionStep(next(), ActionPrimitive.SystemAction("web_search", mapOf("query" to query)), "Web search"),
            ActionStep(next(), ActionPrimitive.WaitForPackage("com.android.chrome", 5000), "Wait browser"),
            ActionStep(next(), ActionPrimitive.ReadVisibleResult(NodeSelector()), "Read results")
        )
    }

    private fun playStoreFlow(intent: SonicIntent, next: () -> String): List<ActionStep> {
        val query = intent.entities["query"] ?: intent.entities["app"] ?: ""
        val action = intent.entities["action"] ?: "search"
        val pkg = "com.android.vending"
        val steps = mutableListOf(
            ActionStep(next(), ActionPrimitive.SystemAction("play_store_search", mapOf("query" to query)), "Play Store"),
            ActionStep(next(), ActionPrimitive.WaitForPackage(pkg, 8000), "Wait Store")
        )
        when (action) {
            "install", "update", "uninstall" -> steps += ActionStep(
                next(),
                ActionPrimitive.AskUser("Tap $action on the store page."),
                "Store action"
            )
            "reviews" -> steps += ActionStep(next(), ActionPrimitive.ReadVisibleResult(NodeSelector()), "Read reviews")
            else -> steps += ActionStep(next(), ActionPrimitive.ReadUiState(), "Show search results")
        }
        return steps
    }

    private fun appLaunchFlow(intent: SonicIntent, next: () -> String): List<ActionStep> {
        val app = intent.entities["app"] ?: intent.entities["app_name"] ?: intent.targetApp ?: ""
        return listOf(
            ActionStep(next(), ActionPrimitive.OpenApp(app), "Open $app"),
            ActionStep(next(), ActionPrimitive.WaitForPackage(app, 8000), "Wait $app"),
            ActionStep(next(), ActionPrimitive.ReadUiState(), "Confirm screen")
        )
    }

    private fun settingsFlow(intent: SonicIntent, next: () -> String): List<ActionStep> {
        val section = intent.entities["section"] ?: ""
        val pkg = "com.android.settings"
        return listOf(
            ActionStep(next(), ActionPrimitive.OpenApp(pkg), "Open Settings"),
            ActionStep(next(), ActionPrimitive.WaitForPackage(pkg), "Wait Settings"),
            ActionStep(
                next(),
                ActionPrimitive.SetText(NodeSelector(hint = "Search", isEditable = true), section),
                "Search $section"
            )
        )
    }

    private fun systemQueryFlow(intent: SonicIntent, next: () -> String): List<ActionStep> {
        val q = intent.rawText.lowercase()
        val name = when {
            q.contains("battery") -> "battery"
            q.contains("time") || q.contains("date") -> "datetime"
            else -> "datetime"
        }
        return listOf(
            ActionStep(next(), ActionPrimitive.SystemAction(name), "System query")
        )
    }

    private fun systemToggleFlow(intent: SonicIntent, next: () -> String): List<ActionStep> {
        val target = intent.entities["target"] ?: ""
        val state = intent.entities["state"] ?: "toggle"
        val name = when {
            target.contains("wifi") || target.contains("wi-fi") ->
                if (state == "on") "wifi_on" else if (state == "off") "wifi_off" else "wifi_toggle"
            target.contains("bluetooth") || target.contains("bt") ->
                if (state == "on") "bt_on" else if (state == "off") "bt_off" else "bt_settings"
            target.contains("data") -> "data_settings"
            target.contains("flash") -> "flashlight_toggle"
            target.contains("silent") -> "ringer_silent"
            target.contains("vibrate") -> "ringer_vibrate"
            target.contains("sound") || target.contains("ringer") -> "ringer_normal"
            else -> "wifi_settings"
        }
        return listOf(ActionStep(next(), ActionPrimitive.SystemAction(name), "Toggle $target"))
    }

    private fun alarmFlow(intent: SonicIntent, next: () -> String): List<ActionStep> {
        return listOf(
            ActionStep(
                next(),
                ActionPrimitive.SystemAction(
                    "alarm",
                    mapOf(
                        "hour" to (intent.entities["hour"] ?: ""),
                        "minute" to (intent.entities["minute"] ?: "0"),
                        "label" to (intent.entities["label"] ?: "VDX")
                    )
                ),
                "Set alarm"
            )
        )
    }

    private fun pdfFlow(intent: SonicIntent, next: () -> String): List<ActionStep> {
        return listOf(
            ActionStep(next(), ActionPrimitive.OpenApp("com.google.android.apps.docs.editors.docs"), "Open docs/PDF viewer"),
            ActionStep(next(), ActionPrimitive.ReadVisibleResult(NodeSelector()), "Read PDF text via a11y")
        )
    }

    private fun gestureFlow(intent: SonicIntent, next: () -> String): List<ActionStep> {
        val action = intent.entities["action"] ?: "tap"
        return when (action) {
            "scroll" -> {
                val dir = intent.entities["direction"] ?: "down"
                val scrollDir = when (dir) {
                    "up" -> ScrollDirection.UP
                    "left" -> ScrollDirection.LEFT
                    "right" -> ScrollDirection.RIGHT
                    else -> ScrollDirection.DOWN
                }
                listOf(
                    ActionStep(
                        next(),
                        ActionPrimitive.ScrollContainer(NodeSelector(), scrollDir),
                        "Scroll $dir"
                    )
                )
            }
            else -> {
                val target = intent.entities["target"]
                listOf(
                    ActionStep(
                        next(),
                        if (target.isNullOrBlank()) {
                            ActionPrimitive.DispatchGesture(0.5f, 0.5f, GestureType.TAP)
                        } else {
                            ActionPrimitive.ClickNode(NodeSelector(text = target))
                        },
                        "Tap ${target ?: "center"}"
                    )
                )
            }
        }
    }
}
