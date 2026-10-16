package com.vdx.research

import org.json.JSONObject

/**
 * Research Desk — the L2 wire contract from Vedaxi's WebMCP two-origin desk, as Kotlin data.
 * Mirrors @vedaxi/contracts tool schemas from vedaxi-elastic-web.
 * LAW: a proposal alone never blocks a citation. Only the human's confirm/reject does.
 */
data class ResearchRecord(
    val id: String,
    val origin: String,
    val title: String,
    val source: String,
    val locator: String,
    val passage: String
)

data class DiscrepancyProposal(
    val id: String,
    val claimA: ResearchRecord,
    val claimB: ResearchRecord,
    val humanText: String
)

sealed class DeskDecision {
    object Pending : DeskDecision()
    data class Confirmed(val by: String, val at: String) : DeskDecision()
    data class Rejected(val by: String, val at: String) : DeskDecision()
}

data class DeskDecisionView(val decision: String, val by: String?, val at: String?)

fun decisionView(d: DeskDecision): DeskDecisionView = when (d) {
    is DeskDecision.Pending -> DeskDecisionView("PENDING_HUMAN", null, null)
    is DeskDecision.Confirmed -> DeskDecisionView("CONFIRMED", d.by, d.at)
    is DeskDecision.Rejected -> DeskDecisionView("REJECTED", d.by, d.at)
}

object ResearchDeskContract {
    val TOOLS = listOf(
        mapOf("origin" to "paper", "tool" to "search_paper_evidence", "role" to "read-only publisher evidence"),
        mapOf("origin" to "paper", "tool" to "request_discrepancy_focus", "role" to "proposal only"),
        mapOf("origin" to "video", "tool" to "search_video_evidence", "role" to "read-only transcript evidence"),
        mapOf("origin" to "video", "tool" to "read_video_transcript", "role" to "read-only cue dump")
    )

    fun proposalView(p: DiscrepancyProposal, d: DeskDecision): Map<String, String> {
        val v = decisionView(d)
        return mapOf(
            "type" to "discrepancy_proposal",
            "id" to p.id,
            "claimA" to p.claimA.source,
            "claimB" to p.claimB.source,
            "humanText" to p.humanText,
            "decision" to v.decision
        ).plus(v.by?.let { mapOf("by" to it) } ?: emptyMap())
         .plus(v.at?.let { mapOf("at" to it) } ?: emptyMap())
    }

    fun proposalJson(p: DiscrepancyProposal, d: DeskDecision): String {
        val o = JSONObject()
        val v = proposalView(p, d)
        for ((k, value) in v) o.put(k, value)
        return o.toString()
    }
}
