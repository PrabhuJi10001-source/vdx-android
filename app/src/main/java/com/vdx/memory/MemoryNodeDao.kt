package com.vdx.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MemoryNodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: MemoryNode): Long

    @Query("SELECT * FROM memory_nodes WHERE id = :id")
    suspend fun getById(id: Long): MemoryNode?

    @Query("SELECT * FROM memory_nodes WHERE name = :name ORDER BY confidence DESC LIMIT 1")
    suspend fun getByName(name: String): MemoryNode?

    @Query("DELETE FROM memory_nodes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM memory_nodes WHERE aliases LIKE '%' || :alias || '%' ORDER BY confidence DESC")
    suspend fun searchByAlias(alias: String): List<MemoryNode>

    @Query("SELECT * FROM memory_nodes WHERE type = :type ORDER BY vitalityScore DESC")
    suspend fun getByType(type: String): List<MemoryNode>

    @Query("SELECT * FROM memory_nodes WHERE vitalityState = :state ORDER BY vitalityScore DESC")
    suspend fun getByVitalityState(state: String): List<MemoryNode>

    @Query("SELECT * FROM memory_nodes ORDER BY vitalityScore DESC LIMIT :limit")
    suspend fun getTopByVitality(limit: Int = 20): List<MemoryNode>

    @Query("UPDATE memory_nodes SET reads7d = reads7d + 1, reads30d = reads30d + 1, lastReadAt = :now WHERE id = :id")
    suspend fun incrementReads(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE memory_nodes SET correctionEvents = correctionEvents + 1, confidence = CASE WHEN confidence - 0.15 < 0 THEN 0 ELSE confidence - 0.15 END WHERE id = :id")
    suspend fun recordCorrection(id: Long)

    @Query("UPDATE memory_nodes SET vitalityScore = :score, vitalityState = :state WHERE id = :id")
    suspend fun updateVitality(id: Long, score: Float, state: String)

    @Query("SELECT COUNT(*) FROM memory_nodes")
    suspend fun count(): Int

    @Query(
        """
        SELECT * FROM memory_nodes
        WHERE name LIKE '%' || :query || '%'
           OR value LIKE '%' || :query || '%'
           OR aliases LIKE '%' || :query || '%'
        ORDER BY vitalityScore DESC
        """
    )
    suspend fun search(query: String): List<MemoryNode>

    @Query("SELECT * FROM memory_nodes")
    suspend fun getAll(): List<MemoryNode>
}
