package com.vdx.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vdx.sonic.IntentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * End-to-end Memory-to-Action vertical slice.
 *
 *   Speak → context → memory retrieval → intent classification → proposed action
 *        → confirmation → execution → verification → learning
 *
 * Charter checks:
 *  - confirmation gate (no confirmation ⇒ nothing happens)
 *  - results are inspectable, correctable, undoable
 *  - memory is typed, sourced, confidence-scored, scoped, deletable
 *  - retrieval is ranked + deduped
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class MemoryToActionPipelineTest {

    private lateinit var context: Context
    private lateinit var pipeline: MemoryToActionPipeline
    private lateinit var memoryStore: MemoryStore
    private lateinit var draftStore: DraftNoteStore

    @Before
    fun setUp() {
        VdxMemoryDatabase.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        pipeline = MemoryToActionPipeline(context)
        memoryStore = MemoryStore(context)
        draftStore = DraftNoteStore(context)
    }

    // ── Full happy path: propose → confirm → execute → verify ──
    @Test
    fun fullSlice_savesDraftOnlyAfterConfirmation() = runBlocking {
        memoryStore.remember(
            "preference", "client meeting", "confirm the agenda, use the boardroom",
            source = "user", scope = "global"
        )

        val proposal = pipeline.propose("draft a project note about the client meeting")
        assertEquals(IntentType.DRAFT_NOTE, proposal.intent.type)
        // Retrieval found the relevant "client meeting" context (ranked, not noise)
        val clientMeetingHit = proposal.contextMemories.firstOrNull { it.node.name == "client meeting" }
        assertNotNull("relevant memory should be retrieved", clientMeetingHit)

        // No action before confirmation
        val before = draftStore.getAll().size

        // No token → nothing saved
        val noToken = pipeline.execute(proposal, null)
        assertTrue(noToken is MemoryToActionPipeline.SliceResult.Rejected)
        assertEquals("nothing should be saved without confirmation", before, draftStore.getAll().size)

        // Confirmed path — mint the live-flow token, then save + verify
        val token = pipeline.confirmProposal(proposal)
        val saved = pipeline.execute(proposal, token)
        assertTrue("expected Saved, got $saved", saved is MemoryToActionPipeline.SliceResult.Saved)
        saved as MemoryToActionPipeline.SliceResult.Saved
        assertTrue("draft id should be positive", saved.draftId > 0)
        assertTrue("body should be captured", saved.body.isNotBlank())
        assertTrue("verification should pass", saved.verification.contains("matched"))

        // Inspectable
        val readBack = draftStore.get(saved.draftId)
        assertNotNull(readBack)
        assertEquals(saved.body, readBack!!.body)

        // Learning: note surfaced back into memory
        pipeline.learnNote(readBack)
        val relevant = memoryStore.retrieveRelevant("client meeting")
        assertTrue("learned note should be retrievable", relevant.any { it.node.type == "note" })
    }

    @Test
    fun fullSlice_correctThenUndo() = runBlocking {
        val proposal = pipeline.propose("draft a note about Q3 budget")
        val token = pipeline.confirmProposal(proposal)
        val saved = pipeline.execute(proposal, token) as MemoryToActionPipeline.SliceResult.Saved

        // Correct
        val corrected = draftStore.update(saved.draftId, "Q3 budget — revised")
        assertEquals("Q3 budget — revised", corrected!!.body)

        // Undo / delete
        draftStore.delete(saved.draftId)
        assertNull(draftStore.get(saved.draftId))
    }

    @Test
    fun confirmationGate_rejectsRawForgedReplayMismatch() = runBlocking {
        val proposal = pipeline.propose("draft a note about launch", sessionId = "s1", userId = "u1")

        // 1. Raw `confirmed` boolean is impossible — there is no boolean parameter.
        //    A null token is rejected.
        assertTrue(pipeline.execute(proposal, null) is MemoryToActionPipeline.SliceResult.Rejected)

        // 2. Forged token (correct fields but never issued) is rejected.
        val forged = ConfirmationToken(
            id = "forged-id",
            actionId = "draft_note",
            draftHash = proposal.draftHash(),
            sessionId = "s1",
            userId = "u1",
            source = ConfirmationSource.BUBBLE_CONFIRMATION,
            issuedAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60_000
        )
        assertTrue(
            "forged token must be rejected",
            pipeline.execute(proposal, forged) is MemoryToActionPipeline.SliceResult.Failed
        )

        // 3. Replay: a used token cannot be reused.
        val token = pipeline.confirmProposal(proposal)
        val first = pipeline.execute(proposal, token)
        assertTrue("first use succeeds", first is MemoryToActionPipeline.SliceResult.Saved)
        val replay = pipeline.execute(proposal, token)
        assertTrue("replay must be rejected", replay is MemoryToActionPipeline.SliceResult.Failed)

        // 4. Mismatched draft: token confirms one note, executor runs a different one.
        val otherProposal = pipeline.propose("draft a note about something else", sessionId = "s1", userId = "u1")
        val tokenForOther = pipeline.confirmProposal(otherProposal)
        val mismatch = pipeline.execute(proposal, tokenForOther)
        assertTrue("draft hash mismatch must be rejected", mismatch is MemoryToActionPipeline.SliceResult.Failed)

        // 5. Session mismatch.
        val wrongSession = pipeline.propose("draft a note about launch", sessionId = "s2", userId = "u1")
        val tokenWrongSession = pipeline.confirmProposal(wrongSession)
        val sessionMismatch = pipeline.execute(proposal, tokenWrongSession)
        assertTrue("session mismatch must be rejected", sessionMismatch is MemoryToActionPipeline.SliceResult.Failed)
    }

    @Test
    fun confirmationToken_expiresAfterTtl() = runBlocking {
        val proposal = pipeline.propose("draft a note about expiry")
        val shortMinter = ConfirmationMinter(ttlMs = 10)
        val token = shortMinter.mint(
            actionId = "draft_note",
            draftHash = proposal.draftHash(),
            sessionId = proposal.sessionId,
            userId = proposal.userId,
            source = ConfirmationSource.BUBBLE_CONFIRMATION
        )
        Thread.sleep(30)
        val result = pipeline.confirmer.consume(
            token,
            actionId = "draft_note",
            draftHash = proposal.draftHash(),
            sessionId = proposal.sessionId,
            userId = proposal.userId
        )
        assertTrue(
            "expired token must be rejected",
            result is ConfirmationMinter.ConsumeResult.Rejected
        )
    }

    @Test
    fun retrieval_ranksAndDedupes() = runBlocking {
        memoryStore.remember("contact", "ravi", "colleague at Google", scope = "global")
        memoryStore.remember("location", "home", "Dubai Marina", scope = "project")

        val hits = memoryStore.retrieveRelevant("message ravi about home", maxResults = 10)
        // Both relevant nodes returned, no duplicates; exact-name matches score 1.0, tie→recency
        val ids = hits.map { it.node.id }.distinct()
        assertEquals("no duplicate nodes", hits.size, ids.size)
        assertTrue("ravi should be retrieved", hits.any { it.node.name == "ravi" })
        assertTrue("home should be retrieved", hits.any { it.node.name == "home" })
        assertEquals("exact-name hits score 1.0", 1.0f, hits.maxOf { it.score })

        // scope filter excludes non-global/project-scoped
        val scoped = memoryStore.retrieveRelevant("message ravi", maxResults = 10, scopeFilter = "global")
        assertTrue(scoped.all { it.node.scope == "global" })
    }

    @Test
    fun sourceAndScopeRecorded() = runBlocking {
        memoryStore.remember("fact", "team", "12 people", source = "transcript", scope = "project")
        val hit = memoryStore.retrieveRelevant("team").firstOrNull()
        assertNotNull(hit)
        assertEquals("transcript", hit!!.node.source)
        assertEquals("project", hit.node.scope)
    }

    @Test
    fun intention_isDeletable() = runBlocking {
        memoryStore.remember("fact", "temp", "ephemeral", source = "observation", scope = "session")
        assertNotNull(memoryStore.recall("temp"))
        memoryStore.forget("temp")
        assertNull(memoryStore.recall("temp"))
    }
}
