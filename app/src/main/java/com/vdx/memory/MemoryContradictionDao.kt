package com.vdx.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MemoryContradictionDao {
    @Insert
    suspend fun insert(contradiction: MemoryContradiction): Long

    @Query("SELECT * FROM memory_contradictions WHERE resolved = 0")
    suspend fun getUnresolved(): List<MemoryContradiction>

    @Query("UPDATE memory_contradictions SET resolved = 1, resolution = :resolution WHERE id = :id")
    suspend fun resolve(id: Long, resolution: String)
}
