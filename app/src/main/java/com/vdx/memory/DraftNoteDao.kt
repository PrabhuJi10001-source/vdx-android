package com.vdx.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DraftNoteDao {
    @Insert
    suspend fun insert(note: DraftNote): Long

    @Query("SELECT * FROM draft_notes WHERE id = :id")
    suspend fun getById(id: Long): DraftNote?

    @Query("SELECT * FROM draft_notes ORDER BY updatedAt DESC")
    suspend fun getAll(): List<DraftNote>

    @Query("UPDATE draft_notes SET body = :body, updatedAt = :now WHERE id = :id")
    suspend fun updateBody(id: Long, body: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM draft_notes WHERE id = :id")
    suspend fun deleteById(id: Long)
}
