package com.vdx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vdx.sonic.SonicEngine

/**
 * External automation intent interface (RUN_TASK / RUN_CHAT style, from the
 * external-agent interface). Lets external agents trigger on-device VDX
 * actions by firing an Android intent with a task/chat payload.
 *
 * Contract:
 *   Action:  com.vdx.action.RUN_TASK   — execute a task string through SonicEngine
 *            com.vdx.action.RUN_CHAT   — alias for RUN_TASK (chat-style payload)
 *   Extras:
 *            "task"  (String) — the task/command text to execute (required)
 *            "chat"  (String) — alternative payload key, used when "task" is absent
 *            "source"(String) — optional caller label, logged for traceability
 *
 * Example (adb):
 *   adb shell am broadcast -a com.vdx.action.RUN_TASK \
 *       --es task "open youtube and play cat videos" \
 *       --es source "agent"
 *
 * The receiver is self-contained. Not an Agent OS control plane — the
 * manifest keeps it exported=false so other apps cannot drive the phone.
 */
class ExternalAutomationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ExternalAutomation"

        /** Intent action for executing a task string. */
        const val ACTION_RUN_TASK = "com.vdx.action.RUN_TASK"

        /** Alias action for chat-style payloads. */
        const val ACTION_RUN_CHAT = "com.vdx.action.RUN_CHAT"

        /** Extra key for the task/command text. */
        const val EXTRA_TASK = "task"

        /** Alternative extra key for chat-style payloads. */
        const val EXTRA_CHAT = "chat"

        /** Optional extra key identifying the calling agent. */
        const val EXTRA_SOURCE = "source"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_RUN_TASK && action != ACTION_RUN_CHAT) {
            Log.w(TAG, "Ignoring unknown action: $action")
            return
        }

        // Resolve the payload: prefer "task", fall back to "chat".
        val task = intent.getStringExtra(EXTRA_TASK)
            ?: intent.getStringExtra(EXTRA_CHAT)
            ?: ""

        if (task.isBlank()) {
            Log.w(TAG, "No task/chat payload provided for action $action")
            return
        }

        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "unknown"
        Log.i(TAG, "External automation ($action) from '$source': $task")

        // Route into SonicEngine. The engine owns its own coroutine scope, so
        // this is safe to call from the broadcast's main-thread onReceive.
        try {
            SonicEngine(context).submitTask(task)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit external task", e)
        }
    }
}
