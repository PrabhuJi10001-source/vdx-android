package com.vdx.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MemoryEdgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(edge: MemoryEdge): Long

    @Query("SELECT * FROM memory_edges WHERE sourceId = :nodeId OR targetId = :nodeId")
    suspend fun getEdgesForNode(nodeId: Long): List<MemoryEdge>

    @Query("SELECT * FROM memory_edges WHERE relationType = :type")
    suspend fun getByRelationType(type: String): List<MemoryEdge>

    @Query("SELECT targetId FROM memory_edges WHERE sourceId = :startId")
    suspend fun getTargetsFrom(startId: Long): List<Long>

    @Query("DELETE FROM memory_edges WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memory_edges WHERE sourceId = :nodeId OR targetId = :nodeId")
    suspend fun deleteEdgesForNode(nodeId: Long)
}
