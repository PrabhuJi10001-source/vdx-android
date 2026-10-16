package com.vdx.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchDeskTest {

    private fun rec(id: String, origin: String, source: String) =
        ResearchRecord(id, origin, "claim $id", source, if (origin=="paper") "10.1000/x$id" else "00:03:12", "passage $id")

    private fun p(id: String) = DiscrepancyProposal(id, rec("a","paper","SSRN"), rec("b","video","transcript"), "40 - 6 = 34")

    @Test
    fun D1_proposal_isPending_until_human_speaks() {
        val v = ResearchDeskContract.proposalView(p("dp1"), DeskDecision.Pending)
        assertEquals("PENDING_HUMAN", v["decision"])
        assertEquals(null, v["by"])
    }

    @Test
    fun D2_confirm_isRecorded_withWhoAndWhen() {
        val v = ResearchDeskContract.proposalView(p("dp2"), DeskDecision.Confirmed("prithu","2026-10-16T09:00:00Z"))
        assertEquals("CONFIRMED", v["decision"])
        assertEquals("prithu", v["by"])
        assertEquals("2026-10-16T09:00:00Z", v["at"])
    }

    @Test
    fun D3_reject_isAlsoFirstClass() {
        val v = ResearchDeskContract.proposalView(p("dp3"), DeskDecision.Rejected("prithu","2026-10-16T09:01:00Z"))
        assertEquals("REJECTED", v["decision"])
    }

    @Test
    fun D4_contract_declares_fourBoundedTools() {
        assertEquals(4, ResearchDeskContract.TOOLS.size)
        assertTrue(ResearchDeskContract.TOOLS.any { it["tool"] == "search_paper_evidence" })
        assertTrue(ResearchDeskContract.TOOLS.any { it["tool"] == "read_video_transcript" })
    }

    @Test
    fun D5_origins_areIndependent() {
        val a = rec("a","paper","SSRN"); val b = rec("b","video","transcript")
        assertTrue(a.origin != b.origin)
    }
}
