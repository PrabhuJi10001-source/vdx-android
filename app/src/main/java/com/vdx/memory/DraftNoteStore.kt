package com.vdx.memory

import android.content.Context

/**
 * Store for saved project notes / drafts.
 * Every result is inspectable, correctable, and undoable (per charter).
 */
class DraftNoteStore(private val context: Context) {

    private val db = VdxMemoryDatabase.getInstance(context)
    private val dao = db.draftNoteDao()

    /** Save a draft. Returns the created note (with id) so the caller can point to it for undo. */
    suspend fun save(body: String, scope: String = "project", sourceTranscript: String = ""): DraftNote {
        val id = dao.insert(DraftNote(body = body, scope = scope, sourceTranscript = sourceTranscript))
        return requireNotNull(dao.getById(id))
    }

    suspend fun get(id: Long): DraftNote? = dao.getById(id)

    suspend fun getAll(): List<DraftNote> = dao.getAll()

    /** Correct a saved note. */
    suspend fun update(id: Long, body: String): DraftNote? {
        dao.updateBody(id, body)
        return dao.getById(id)
    }

    /** Undo / delete a saved note. */
    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }
}
