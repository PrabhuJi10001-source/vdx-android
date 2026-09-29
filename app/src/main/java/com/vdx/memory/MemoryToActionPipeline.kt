package com.vdx.memory

import android.content.Context
import com.vdx.sonic.IntentType
import com.vdx.sonic.SonicIntent
import com.vdx.sonic.clarify.ClarificationEngine
import com.vdx.sonic.voice.IntentParser

/**
 * MemoryToActionPipeline — the complete vertical slice:
 *
 *   Speak → context → memory retrieval → intent classification → proposed action
 *        → confirmation → execution → verification → learning
 *
 * Uses the on-device memory graph. The ONLY executed action is the safe
 * "draft a project note" — no messages, no deletes, no purchases, no account
 * changes. Every consequential action is gated behind explicit confirmation.
 * Every result is inspectable, correctable, and undoable.
 */
class MemoryToActionPipeline(private val context: Context) {

    private val memoryStore = MemoryStore(context)
    private val draftStore = DraftNoteStore(context)
    private val hydrator = ContextHydrator(context)
    private val intentParser = IntentParser()
    private val clarifier = ClarificationEngine()

    /** The sole mint of confirmation capabilities for the safe slice. */
    val confirmer = ConfirmationMinter()

    /**
     * Stage 1-3: understand context, retrieve relevant memory, classify intent.
     * Returns a proposal awaiting confirmation. NO action is taken here.
     */
    suspend fun propose(
        transcript: String,
        sessionId: String = "session",
        userId: String = "user",
        scopeFilter: String? = null
    ): Proposal {
        val hydration = hydrator.hydrate(transcript)

        val retrieved = memoryStore.retrieveRelevant(
            transcript,
            maxResults = 5,
            scopeFilter = scopeFilter
        )

        val intent = intentParser.parse(transcript)
        val clarification = clarifier.evaluate(intent, null)

        return Proposal(
            transcript = transcript,
            sessionId = sessionId,
            userId = userId,
            hydratedPrompt = hydration.hydratedPrompt,
            contextMemories = retrieved,
            intent = intent,
            clarification = clarification
        )
    }

    /**
     * Stage 4-8 (post-confirmation): execute the proposed action, verify, learn.
     *
     * Requires a [ConfirmationToken] minted by ClarificationEngine's live Bubble
     * confirmation flow — NOT a caller-controlled boolean. A missing, expired,
     * replayed, or mismatched token aborts before anything is written.
     */
    suspend fun execute(proposal: Proposal, token: ConfirmationToken?): SliceResult {
        if (token == null) {
            return SliceResult.rejected("No confirmation token. Consequential action aborted.")
        }

        val intent = proposal.intent
        when (intent.type) {
            IntentType.DRAFT_NOTE -> {
                val body = intent.entities["body"]?.takeIf { it.isNotBlank() }
                    ?: proposal.transcript.removePrefix("draft note").trim()
                if (body.isBlank()) {
                    return SliceResult.failed("No note body captured.")
                }
                // Validate the exact user-confirmed draft through the token capability.
                val validation = confirmer.consume(
                    token,
                    actionId = "draft_note",
                    draftHash = DraftHasher.of(body),
                    sessionId = proposal.sessionId,
                    userId = proposal.userId
                )
                if (validation !is ConfirmationMinter.ConsumeResult.Valid) {
                    val reason = (validation as? ConfirmationMinter.ConsumeResult.Rejected)?.reason ?: "invalid token"
                    return SliceResult.failed("Confirmation rejected: $reason. Nothing saved.")
                }

                val saved = draftStore.save(
                    body = body,
                    scope = "project",
                    sourceTranscript = proposal.transcript
                )
                // Verification — read back what was written.
                val verified = draftStore.get(saved.id)
                if (verified == null || verified.body != body) {
                    return SliceResult.failed("Verification failed: draft not found after save.")
                }
                // Learning — record the episode (for future plan reuse) + a memory of the note
                // so the note becomes retrievable context on later turns.
                memoryStore.recordEpisode(
                    goal = "draft note",
                    action = "draft_note",
                    target = "project",
                    outcome = "success",
                    memoriesReferenced = proposal.contextMemoriesIds(),
                    durationMs = 0
                )
                learnNote(verified)
                return SliceResult.saved(
                    draftId = saved.id,
                    body = verified.body,
                    verification = "Draft #${saved.id} read back and matched."
                )
            }
            else -> return SliceResult.failed("Intent '${intent.type}' not supported by the safe slice.")
        }
    }

    /**
     * Learning fallback: create a memory from the note so future retrieval
     * of the same topic surfaces it. Source + scope are always recorded.
     */
    suspend fun learnNote(note: DraftNote, scope: String = "project") {
        memoryStore.remember(
            type = "note",
            name = "note-${note.id}",
            value = note.body,
            contextNote = "draft note saved from transcript",
            source = "user",
            scope = scope
        )
    }

    data class Proposal(
        val transcript: String,
        val sessionId: String,
        val userId: String,
        val hydratedPrompt: String,
        val contextMemories: List<MemoryStore.ScoredMemory>,
        val intent: SonicIntent,
        val clarification: Any?,
    ) {
        fun contextMemoriesIds(): List<Long> = contextMemories.map { it.node.id }
        fun draftHash(): String = DraftHasher.of(
            intent.entities["body"]?.takeIf { it.isNotBlank() }
                ?: transcript.removePrefix("draft note").trim()
        )
    }

    /**
     * Mint a confirmation token for the user-confirmed draft.
     * This is the LIVE path: the Bubble confirmation flow calls this ONLY after
     * the user explicitly says yes to the proposed note. No other call site can
     * mint an authorization.
     */
    fun confirmProposal(proposal: Proposal): ConfirmationToken =
        confirmer.mint(
            actionId = "draft_note",
            draftHash = proposal.draftHash(),
            sessionId = proposal.sessionId,
            userId = proposal.userId,
            source = ConfirmationSource.BUBBLE_CONFIRMATION
        )

    sealed class SliceResult {
        data class Saved(val draftId: Long, val body: String, val verification: String) : SliceResult()
        data class Cancelled(val reason: String) : SliceResult()
        data class Rejected(val reason: String) : SliceResult()
        data class Failed(val reason: String) : SliceResult()
        companion object {
            fun saved(draftId: Long, body: String, verification: String) = Saved(draftId, body, verification)
            fun cancelled(reason: String) = Cancelled(reason)
            fun rejected(reason: String) = Rejected(reason)
            fun failed(reason: String) = Failed(reason)
        }
    }
}
