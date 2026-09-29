package com.vdx.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved project note / draft — the VDX Memory-to-Action "safe action".
 *
 * Fully inspectable, correctable, and undoable:
 *  - inspect via [DraftNoteDao.getById] / [DraftNoteDao.getAll]
 *  - correct via [DraftNoteStore.update]
 *  - undo via [DraftNoteStore.delete]
 */
@Entity(tableName = "draft_notes")
data class DraftNote(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val body: String,
    val scope: String = "project",
    val sourceTranscript: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
