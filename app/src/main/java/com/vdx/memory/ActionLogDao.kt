package com.vdx.memory

import androidx.room.*

@Dao
interface ActionLogDao {

    @Query("SELECT * FROM action_log ORDER BY timestamp DESC LIMIT 200")
    suspend fun getRecent(): List<ActionLog>

    @Query("SELECT * FROM action_log WHERE action = :action ORDER BY timestamp DESC LIMIT 100")
    suspend fun getByAction(action: String): List<ActionLog>

    @Query("SELECT * FROM action_log WHERE target LIKE '%' || :target || '%' ORDER BY timestamp DESC LIMIT 100")
    suspend fun getByTarget(target: String): List<ActionLog>

    @Query("SELECT * FROM action_log WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getSince(since: Long): List<ActionLog>

    @Insert
    suspend fun insert(log: ActionLog): Long

    @Query("DELETE FROM action_log WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM action_log")
    suspend fun count(): Int
}
