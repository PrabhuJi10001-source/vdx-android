package com.vdx.sonic.mcp

import com.vdx.sonic.ActionPrimitive
import com.vdx.sonic.ActionStep
import com.vdx.sonic.ExecutionPlan
import com.vdx.sonic.ExecutionResult
import com.vdx.sonic.GestureType
import com.vdx.sonic.NodeSelector
import com.vdx.sonic.ScrollDirection
import com.vdx.sonic.SonicIntent
import com.vdx.sonic.IntentType
import com.vdx.sonic.IntentMode
import com.vdx.sonic.robot.RobotHand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * McpToolRegistry — in-process tool catalog for RobotHand primitives.
 *
 * This is NOT a server. It is the open surface other transports can call later
 * (HTTP client, stdio, vendor SDK) via [McpTransport]. Voice still owns execution.
 * Each tool builds a single-step [ExecutionPlan] and hands it to [RobotHand.execute].
 */
class McpToolRegistry(
    private val robotHand: RobotHand,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    /**
     * A single tool invocation: a name, a JSON body, and a callback that receives
     * the [ExecutionResult] (or an error string) on the caller's thread.
     */
    class Invocation(
        val name: String,
        val body: JSONObject
    )

    /**
     * Dispatch a tool call. Non-blocking: returns immediately; the result is
     * delivered to [onResult] (or [onError]) from a background coroutine.
     */
    fun invoke(invocation: Invocation, onResult: (ExecutionResult) -> Unit, onError: (String) -> Unit) {
        scope.launch {
            try {
                val result = runTool(invocation)
                onResult(result)
            } catch (e: Exception) {
                onError(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Build and execute the plan for a tool. Runs on the caller's coroutine
     * (the registry scope is Dispatchers.IO).
     */
    private suspend fun runTool(invocation: Invocation): ExecutionResult {
        val plan = buildPlan(invocation) ?: return ExecutionResult.Failed(
            "Unknown tool: ${invocation.name}. Call one of: tap, swipe, type, click, long_click, scroll, open_app, go_back, home, notifications, recents, read_screen, screenshot."
        )
        return robotHand.execute(plan)
    }

    /**
     * Translate a tool name + JSON body into a single-step [ExecutionPlan].
     * Returns null for unknown tool names.
     */
    private fun buildPlan(invocation: Invocation): ExecutionPlan? {
        val body = invocation.body
        val intent = SonicIntent(
            mode = IntentMode.COMMAND,
            type = IntentType.GESTURE,
            rawText = invocation.name
        )

        val step: ActionStep = when (invocation.name) {
            "tap" -> {
                val x = body.optDouble("x", -1.0).toFloat()
                val y = body.optDouble("y", -1.0).toFloat()
                if (x < 0f || y < 0f) return null
                ActionStep("tap", ActionPrimitive.DispatchGesture(x, y, GestureType.TAP), "Tap at ($x, $y)")
            }

            "swipe" -> {
                val direction = body.optString("direction", "up").uppercase()
                val type = when (direction) {
                    "up" -> GestureType.SWIPE_UP
                    "down" -> GestureType.SWIPE_DOWN
                    "left" -> GestureType.SWIPE_LEFT
                    "right" -> GestureType.SWIPE_RIGHT
                    else -> return null
                }
                ActionStep("swipe", ActionPrimitive.DispatchGesture(0f, 0f, type), "Swipe $direction")
            }

            "type" -> {
                val text = body.optString("text", "")
                if (text.isEmpty()) return null
                val selector = selectorFrom(body)
                ActionStep("type", ActionPrimitive.SetText(selector, text), "Type text")
            }

            "click" -> {
                val selector = selectorFrom(body)
                ActionStep("click", ActionPrimitive.ClickNode(selector), "Click element")
            }

            "long_click" -> {
                val selector = selectorFrom(body)
                ActionStep("long_click", ActionPrimitive.LongClickNode(selector), "Long-click element")
            }

            "scroll" -> {
                val direction = body.optString("direction", "down").uppercase()
                val scrollDir = when (direction) {
                    "up" -> ScrollDirection.UP
                    "down" -> ScrollDirection.DOWN
                    "left" -> ScrollDirection.LEFT
                    "right" -> ScrollDirection.RIGHT
                    else -> return null
                }
                val selector = selectorFrom(body)
                ActionStep("scroll", ActionPrimitive.ScrollContainer(selector, scrollDir), "Scroll $direction")
            }

            "open_app" -> {
                val pkg = body.optString("package", "")
                if (pkg.isEmpty()) return null
                ActionStep("open_app", ActionPrimitive.OpenApp(pkg), "Open $pkg")
            }

            "go_back" -> ActionStep("go_back", ActionPrimitive.GoBack, "Go back")

            "home" -> ActionStep("home", ActionPrimitive.SystemAction("home"), "Go home")

            "notifications" -> ActionStep(
                "notifications",
                ActionPrimitive.SystemAction("notifications"),
                "Open notifications"
            )

            "recents" -> ActionStep("recents", ActionPrimitive.SystemAction("recents"), "Open recents")

            "read_screen" -> ActionStep("read_screen", ActionPrimitive.ReadUiState(), "Read screen")

            // Semantic screenshot: returns the current UI state summary. A true
            // pixel screenshot would require AccessibilityService.takeScreenshot,
            // which lives in VdxAccessibilityService (owned by another agent).
            "screenshot" -> ActionStep("screenshot", ActionPrimitive.ReadUiState(), "Capture screen state")

            else -> return null
        }

        return ExecutionPlan(intent = intent, steps = listOf(step))
    }

    /**
     * Build a [NodeSelector] from the common selector fields in a JSON body.
     * All fields are optional; at least one should be present for node-targeted tools.
     */
    private fun selectorFrom(body: JSONObject): NodeSelector {
        return NodeSelector(
            text = body.optString("text").ifBlank { null },
            contentDescription = body.optString("contentDescription").ifBlank { null },
            hint = body.optString("hint").ifBlank { null },
            className = body.optString("className").ifBlank { null },
            resourceId = body.optString("resourceId").ifBlank { null },
            isEditable = if (body.has("isEditable")) body.optBoolean("isEditable") else null,
            isClickable = if (body.has("isClickable")) body.optBoolean("isClickable") else null,
            isFocused = if (body.has("isFocused")) body.optBoolean("isFocused") else null,
            index = if (body.has("index")) body.optInt("index") else null
        )
    }
}
