package com.vdx.sonic.plan

import com.vdx.sonic.*
import com.vdx.sonic.harness.Harness

/**
 * Planner — turns a structured [SonicIntent] into an executable [ExecutionPlan].
 *
 * Generates a step-by-step action graph using action primitives.
 * Each step inspects UI state, executes, and verifies postconditions.
 */
class Planner {

    companion object {
        private const val TAG = "Sonic-Planner"
    }

    /**
     * Create an execution plan from a parsed intent.
     */
    suspend fun plan(
        intent: SonicIntent,
        screenModel: ScreenModel?,
        harness: Harness
    ): ExecutionPlan {
        val steps = mutableListOf<ActionStep>()
        var stepId = 0

        fun nextId(): String = "step_${stepId++}"

        when (intent.type) {
            IntentType.CALL -> {
                val contact = intent.entities["contact"] ?: "unknown"
                steps.add(ActionStep(nextId(), ActionPrimitive.OpenApp("com.android.dialer"), "Open dialer"))
                steps.add(ActionStep(nextId(), ActionPrimitive.WaitForPackage("com.android.dialer"), "Wait for dialer"))
                steps.add(ActionStep(nextId(), ActionPrimitive.ReadUiState(), "Read dialer UI"))
                steps.add(ActionStep(
                    nextId(),
                    ActionPrimitive.SetText(
                        NodeSelector(hint = "Search", isEditable = true),
                        contact
                    ),
                    "Type contact name"
                ))
                steps.add(ActionStep(nextId(), ActionPrimitive.WaitForPackage("com.android.dialer", 3000), "Wait for search results"))
                steps.add(ActionStep(
                    nextId(),
                    ActionPrimitive.ClickNode(NodeSelector(text = contact, isClickable = true)),
                    "Select contact"
                ))
                steps.add(ActionStep(
                    nextId(),
                    ActionPrimitive.ClickNode(NodeSelector(contentDescription = "Call", isClickable = true)),
                    "Tap call button"
                ))
            }

            IntentType.WHATSAPP -> {
                val contact = intent.entities["contact"] ?: "unknown"
                val message = intent.entities["message"] ?: ""

                steps.add(ActionStep(nextId(), ActionPrimitive.OpenApp("com.whatsapp"), "Open WhatsApp"))
                steps.add(ActionStep(nextId(), ActionPrimitive.WaitForPackage("com.whatsapp"), "Wait for WhatsApp"))
                steps.add(ActionStep(nextId(), ActionPrimitive.ReadUiState(), "Read WhatsApp UI"))
                steps.add(ActionStep(
                    nextId(),
                    ActionPrimitive.SetText(
                        NodeSelector(hint = "Search", isEditable = true),
                        contact
                    ),
                    "Search for contact"
                ))
                steps.add(ActionStep(nextId(), ActionPrimitive.WaitForPackage("com.whatsapp", 3000), "Wait for search results"))
                steps.add(ActionStep(
                    nextId(),
                    ActionPrimitive.ClickNode(NodeSelector(text = contact, isClickable = true)),
                    "Open contact chat"
                ))
                steps.add(ActionStep(nextId(), ActionPrimitive.WaitForPackage("com.whatsapp", 2000), "Wait for chat to open"))

                if (message.isNotBlank()) {
                    steps.add(ActionStep(
                        nextId(),
                        ActionPrimitive.SetText(
                            NodeSelector(hint = "Message", isEditable = true),
                            message
                        ),
                        "Type message"
                    ))
                }

                // DO NOT tap send — user confirms
                steps.add(ActionStep(
                    nextId(),
                    ActionPrimitive.WaitForUserConfirmation("Message ready for $contact. Tap send to confirm?"),
                    "Wait for user confirmation"
                ))
            }

            IntentType.BOOK_RIDE -> {
                val destination = intent.entities["destination"] ?: "unknown"

                steps.add(ActionStep(nextId(), ActionPrimitive.OpenApp("com.ubercab"), "Open Uber"))
                steps.add(ActionStep(nextId(), ActionPrimitive.WaitForPackage("com.ubercab"), "Wait for Uber"))
                steps.add(ActionStep(nextId(), ActionPrimitive.ReadUiState(), "Read Uber UI"))
                steps.add(ActionStep(
                    nextId(),
                    ActionPrimitive.SetText(
                        NodeSelector(hint = "Where to", isEditable = true),
                        destination
                    ),
                    "Type destination"
                ))
                steps.add(ActionStep(nextId(), ActionPrimitive.WaitForPackage("com.ubercab", 4000), "Wait for results"))
                steps.add(ActionStep(
                    nextId(),
                    ActionPrimitive.ReadVisibleResult(NodeSelector(className = "android.widget.TextView")),
                    "Read ride options and price"
                ))
                steps.add(ActionStep(
                    nextId(),
                    ActionPrimitive.WaitForUserConfirmation("Destination set to $destination. Confirm to book?"),
                    "Wait for user confirmation"
                ))
            }

            IntentType.APP_LAUNCH -> {
                val appName = intent.entities["app_name"] ?: "unknown"
                steps.add(ActionStep(
                    nextId(),
                    ActionPrimitive.OpenApp(appName),
                    "Open $appName"
                ))
                steps.add(ActionStep(nextId(), ActionPrimitive.WaitForPackage(appName, 8000), "Wait for app"))
            }

            IntentType.GO_BACK -> {
                steps.add(ActionStep(nextId(), ActionPrimitive.GoBack, "Go back"))
            }

            IntentType.GO_HOME -> {
                steps.add(ActionStep(
                    nextId(),
                    ActionPrimitive.DispatchGesture(0f, 0f, GestureType.SWIPE_UP),
                    "Go home"
                ))
            }

            IntentType.READ_SCREEN -> {
                steps.add(ActionStep(nextId(), ActionPrimitive.ReadUiState(), "Read current screen"))
            }

            IntentType.SEARCH -> {
                val query = intent.entities["query"] ?: "unknown"
                steps.add(ActionStep(nextId(), ActionPrimitive.OpenApp("com.android.chrome"), "Open browser"))
                steps.add(ActionStep(nextId(), ActionPrimitive.WaitForPackage("com.android.chrome"), "Wait for browser"))
                steps.add(ActionStep(
                    nextId(),
                    ActionPrimitive.SetText(
                        NodeSelector(hint = "Search", isEditable = true),
                        query
                    ),
                    "Type search query"
                ))
            }

            IntentType.YOUTUBE_SEARCH -> {
                val query = intent.entities["query"] ?: "unknown"
                steps.add(ActionStep(nextId(), ActionPrimitive.OpenApp("com.google.android.youtube"), "Open YouTube"))
                steps.add(ActionStep(nextId(), ActionPrimitive.WaitForPackage("com.google.android.youtube"), "Wait for YouTube"))
                steps.add(ActionStep(
                    nextId(),
                    ActionPrimitive.SetText(
                        NodeSelector(contentDescription = "Search", isEditable = true),
                        query
                    ),
                    "Search YouTube"
                ))
            }

            IntentType.SETTINGS_NAVIGATION -> {
                val section = intent.entities["section"] ?: ""
                steps.add(ActionStep(nextId(), ActionPrimitive.OpenApp("com.android.settings"), "Open Settings"))
                steps.add(ActionStep(nextId(), ActionPrimitive.WaitForPackage("com.android.settings"), "Wait for Settings"))
                if (section.isNotBlank()) {
                    steps.add(ActionStep(
                        nextId(),
                        ActionPrimitive.SetText(
                            NodeSelector(hint = "Search", isEditable = true),
                            section
                        ),
                        "Search for $section settings"
                    ))
                }
            }

            IntentType.FORM_FILL -> {
                val text = intent.entities["text"] ?: ""
                if (text.isNotBlank()) {
                    steps.add(ActionStep(
                        nextId(),
                        ActionPrimitive.SetText(
                            NodeSelector(isEditable = true, isFocused = true),
                            text
                        ),
                        "Insert text into focused field"
                    ))
                }
            }

            IntentType.UNKNOWN -> {
                steps.add(ActionStep(
                    nextId(),
                    ActionPrimitive.AskUser(intent.clarificationQuestion ?: "I didn't understand. What would you like me to do?"),
                    "Ask clarification"
                ))
            }

            else -> {
                steps.add(ActionStep(
                    nextId(),
                    ActionPrimitive.FailWithReason("Unsupported intent type: ${intent.type}"),
                    "Unsupported"
                ))
            }
        }

        return ExecutionPlan(
            intent = intent,
            steps = steps,
            requiresConfirmation = intent.requiresConfirmation,
            estimatedDurationMs = steps.size * 2000L
        )
    }
}
