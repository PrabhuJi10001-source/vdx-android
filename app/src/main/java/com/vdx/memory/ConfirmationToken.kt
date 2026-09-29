package com.vdx.memory

import java.security.MessageDigest
import java.util.UUID

/**
 * Confirmation capability — replaces any caller-controlled `confirmed: Boolean`.
 *
 * A [ConfirmationToken] is the ONLY thing that authorizes a consequential action.
 * It cannot be forged by passing `true`: the token must be minted by
 * [com.vdx.sonic.clarify.ClarificationEngine.confirmDraft] (the sole sanctioned
 * mint path, invoked from the live Bubble confirmation flow after the user says yes).
 *
 * The executor validates:
 *  - exact action id
 *  - exact draft hash (sha-256 of the note body)
 *  - session + user binding
 *  - expiration (TTL)
 *  - one-time use (consume marks it spent; replay is rejected)
 *  - source of confirmation
 */
class ConfirmationMinter(
    /** Token lifetime. After this, the confirmation is stale and rejected. */
    private val ttlMs: Long = 90_000L
) {
    private val issued = mutableMapOf<String, ConfirmationToken>()
    private val consumed = mutableSetOf<String>()

    fun mint(
        actionId: String,
        draftHash: String,
        sessionId: String,
        userId: String,
        source: ConfirmationSource
    ): ConfirmationToken {
        val now = System.currentTimeMillis()
        val token = ConfirmationToken(
            id = UUID.randomUUID().toString(),
            actionId = actionId,
            draftHash = draftHash,
            sessionId = sessionId,
            userId = userId,
            source = source,
            issuedAt = now,
            expiresAt = now + ttlMs
        )
        issued[token.id] = token
        return token
    }

    /**
     * Validate + consume a token in one atomic step. Returns the reason on failure.
     * A valid token is consumed (one-time use) and a replay is rejected.
     */
    fun consume(token: ConfirmationToken, actionId: String, draftHash: String, sessionId: String, userId: String): ConsumeResult {
        val stored = issued[token.id] ?: return ConsumeResult.Rejected("token not issued")
        if (consumed.contains(token.id)) return ConsumeResult.Rejected("token already used (replay)")
        val now = System.currentTimeMillis()
        if (now > stored.expiresAt) return ConsumeResult.Rejected("token expired")
        if (stored.source != ConfirmationSource.BUBBLE_CONFIRMATION) return ConsumeResult.Rejected("token from invalid source")
        if (stored.actionId != actionId) return ConsumeResult.Rejected("action id mismatch")
        if (stored.draftHash != draftHash) return ConsumeResult.Rejected("draft hash mismatch")
        if (stored.sessionId != sessionId) return ConsumeResult.Rejected("session mismatch")
        if (stored.userId != userId) return ConsumeResult.Rejected("user mismatch")
        consumed.add(token.id)
        return ConsumeResult.Valid(stored)
    }

    sealed class ConsumeResult {
        data class Valid(val token: ConfirmationToken) : ConsumeResult()
        data class Rejected(val reason: String) : ConsumeResult()
    }
}

data class ConfirmationToken(
    val id: String,
    val actionId: String,
    val draftHash: String,
    val sessionId: String,
    val userId: String,
    val source: ConfirmationSource,
    val issuedAt: Long,
    val expiresAt: Long
)

enum class ConfirmationSource { BUBBLE_CONFIRMATION }

object DraftHasher {
    fun of(body: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(body.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
