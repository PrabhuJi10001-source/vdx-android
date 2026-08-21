package com.vdx.sonic.plan

import android.content.Context
import com.vdx.sonic.*
import com.vdx.sonic.flows.LouieFlowCatalog
import com.vdx.sonic.harness.Harness

/**
 * Planner — delegates to LouieFlowCatalog for Louie-depth plans (Wispr UX).
 */
class Planner(private val context: Context? = null) {

    suspend fun plan(
        intent: SonicIntent,
        screenModel: ScreenModel?,
        harness: Harness
    ): ExecutionPlan {
        // Mid-app resume: if already in target package, catalog still opens/ensures state
        return LouieFlowCatalog.plan(intent)
    }
}
