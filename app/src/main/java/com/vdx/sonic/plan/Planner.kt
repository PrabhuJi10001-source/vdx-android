package com.vdx.sonic.plan

import android.content.Context
import android.util.Log
import com.vdx.memory.VdxMemoryDatabase
import com.vdx.sonic.*
import com.vdx.sonic.flows.FlowCatalog
import com.vdx.sonic.harness.Harness
import com.vdx.sonic.knowledge.AppKnowledgeBase
import com.vdx.sonic.knowledge.NavStep
import com.vdx.sonic.voice.PromptTemplate

/**
 * Planner — delegates to FlowCatalog for full-depth plans (tap-to-talk UX).
 *
 * When [AppKnowledgeBase] has navigation steps for the target app+action,
 * the Planner uses those steps to build the ExecutionPlan instead of
 * guessing from the accessibility tree. Falls back to FlowCatalog
 * when no knowledge base entry exists.
 */
class Planner(private val context: Context? = null) {

    companion object {
        private const val TAG = "Planner"
    }

    private val knowledgeBase: AppKnowledgeBase? by lazy {
        context?.let { ctx ->
            try {
                val kb = AppKnowledgeBase(VdxMemoryDatabase.getInstance(ctx).appKnowledgeDao())
                // Seed baseline knowledge on first access (idempotent — only seeds if empty).
                kotlinx.coroutines.runBlocking { kb.seedIfEmpty() }
                kb
            } catch (e: Exception) {
                Log.w(TAG, "AppKnowledgeBase unavailable, falling back to FlowCatalog", e)
                null
            }
        }
    }

    suspend fun plan(
        intent: SonicIntent,
        screenModel: ScreenModel?,
        harness: Harness
    ): ExecutionPlan {
        if (com.vdx.sonic.executor.PaymentBlocklist.blocked(intent)) {
            return ExecutionPlan(
                intent = intent,
                steps = listOf(
                    ActionStep(
                        "block-pay",
                        ActionPrimitive.FailWithReason(com.vdx.sonic.executor.PaymentBlocklist.REASON),
                        "Blocked banking/payment"
                    )
                )
            )
        }
        // Call release is Play-clean: native DIAL, never a11y knowledge steps.
        if (intent.type == IntentType.CALL) {
            return FlowCatalog.plan(intent)
        }
        // Try the knowledge base first — if we have navigation steps for this
        // app+action, use them to build the plan.
        val kb = knowledgeBase
        if (kb != null && context != null) {
            val locale = PromptTemplate.getLocale()
            val pkg = resolvePackageName(intent)
            val action = resolveAction(intent)
            if (pkg != null && action != null) {
                val steps = kb.getSteps(pkg, action, locale)
                if (steps != null && steps.isNotEmpty()) {
                    Log.d(TAG, "Using knowledge base steps for $pkg/$action ($locale)")
                    return planFromKnowledge(intent, pkg, steps)
                }
            }
        }

        // Fallback: FlowCatalog
        return FlowCatalog.plan(intent)
    }

    /**
     * Build an [ExecutionPlan] from knowledge base [NavStep]s.
     * Each NavStep maps to an [ActionStep] with the appropriate [ActionPrimitive].
     */
    private fun planFromKnowledge(
        intent: SonicIntent,
        packageName: String,
        navSteps: List<NavStep>
    ): ExecutionPlan {
        var i = 0
        val next = { "kb${i++}" }
        val steps = mutableListOf<ActionStep>()

        // Ensure the app is open first
        steps += ActionStep(next(), ActionPrimitive.OpenApp(packageName), "Open $packageName")
        steps += ActionStep(next(), ActionPrimitive.WaitForPackage(packageName), "Wait for app")

        for (nav in navSteps) {
            val primitive = when (nav.action) {
                NavStep.ACTION_TAP -> {
                    val selector = makeSelector(nav.targetElement, intent)
                    ActionPrimitive.ClickNode(selector)
                }
                NavStep.ACTION_SET_TEXT -> {
                    val selector = makeSelector(nav.targetElement, intent)
                    val text = resolveTemplate(nav.text, intent)
                    ActionPrimitive.SetText(selector, text)
                }
                NavStep.ACTION_SCROLL -> {
                    val dir = when (nav.scrollDirection?.uppercase()) {
                        "UP" -> ScrollDirection.UP
                        "LEFT" -> ScrollDirection.LEFT
                        "RIGHT" -> ScrollDirection.RIGHT
                        else -> ScrollDirection.DOWN
                    }
                    ActionPrimitive.ScrollContainer(NodeSelector(), dir)
                }
                NavStep.ACTION_WAIT -> {
                    ActionPrimitive.WaitForPackage(packageName, nav.waitMs ?: 2000)
                }
                else -> {
                    val selector = makeSelector(nav.targetElement, intent)
                    ActionPrimitive.ClickNode(selector)
                }
            }
            steps += ActionStep(next(), primitive, nav.description)
        }

        return ExecutionPlan(
            intent = intent,
            steps = steps,
            requiresConfirmation = intent.requiresConfirmation,
            estimatedDurationMs = steps.size * 1800L
        )
    }

    /** Build a NodeSelector from a target element string, resolving {{var}} placeholders. */
    private fun makeSelector(targetElement: String?, intent: SonicIntent): NodeSelector {
        if (targetElement.isNullOrBlank()) return NodeSelector()
        val resolved = resolveTemplate(targetElement, intent)
        // Try text match first, then contentDescription
        return NodeSelector(text = resolved)
    }

    /** Resolve {{var}} placeholders in a template string using intent entities. */
    private fun resolveTemplate(template: String?, intent: SonicIntent): String {
        if (template.isNullOrBlank()) return ""
        val vars = intent.entities.toMap()
        return PromptTemplate.render(template, vars)
    }

    /** Map intent type + target app to a package name for knowledge base lookup. */
    private fun resolvePackageName(intent: SonicIntent): String? {
        // Check if the intent already has a target app package
        intent.targetApp?.let { return normalizePackage(it) }
        // Map intent type to default package
        return when (intent.type) {
            IntentType.CALL -> "com.android.dialer"
            IntentType.WHATSAPP -> "com.whatsapp"
            IntentType.SMS -> "com.google.android.apps.messaging"
            IntentType.EMAIL -> "com.google.android.gm"
            IntentType.BOOK_RIDE -> "com.ubercab"
            IntentType.YOUTUBE_SEARCH, IntentType.YOUTUBE_CONTROL -> "com.google.android.youtube"
            IntentType.SEARCH -> "com.android.chrome"
            IntentType.SETTINGS_NAVIGATION, IntentType.SYSTEM_TOGGLE -> "com.android.settings"
            else -> null
        }
    }

    /** Map intent type + entities to a knowledge base action string. */
    private fun resolveAction(intent: SonicIntent): String? {
        val action = intent.entities["action"]
        return when (intent.type) {
            IntentType.CALL -> "make_call"
            IntentType.WHATSAPP -> {
                when (action) {
                    "open", null -> "open_chat"
                    "send", "message" -> "send_message"
                    else -> "send_message"
                }
            }
            IntentType.SMS -> "send_message"
            IntentType.EMAIL -> "send_message"
            IntentType.BOOK_RIDE -> "book_ride"
            IntentType.SEARCH -> "search"
            IntentType.YOUTUBE_SEARCH -> "search"
            IntentType.SETTINGS_NAVIGATION -> {
                val section = intent.entities["section"] ?: ""
                when {
                    section.contains("wifi", ignoreCase = true) -> "open_wifi"
                    section.contains("bluetooth", ignoreCase = true) -> "open_bluetooth"
                    else -> null
                }
            }
            IntentType.SYSTEM_TOGGLE -> {
                val target = intent.entities["target"] ?: ""
                when {
                    target.contains("wifi", ignoreCase = true) -> "open_wifi"
                    target.contains("bluetooth", ignoreCase = true) -> "open_bluetooth"
                    else -> null
                }
            }
            else -> null
        }
    }

    /** Normalize common app names to package names. */
    private fun normalizePackage(name: String): String {
        return when (name.lowercase().trim()) {
            "whatsapp" -> "com.whatsapp"
            "phone", "dialer" -> "com.android.dialer"
            "uber" -> "com.ubercab"
            "chrome", "browser" -> "com.android.chrome"
            "settings" -> "com.android.settings"
            "youtube" -> "com.google.android.youtube"
            "gmail", "email" -> "com.google.android.gm"
            "messages", "sms" -> "com.google.android.apps.messaging"
            else -> name
        }
    }
}