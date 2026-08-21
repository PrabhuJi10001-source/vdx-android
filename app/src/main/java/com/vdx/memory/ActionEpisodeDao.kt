package com.vdx.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ActionEpisodeDao {
    @Insert
    suspend fun insert(episode: ActionEpisode): Long

    @Query("SELECT * FROM action_episodes WHERE action = :action AND outcome = 'success' ORDER BY timestamp DESC LIMIT 20")
    suspend fun getRecentSuccesses(action: String): List<ActionEpisode>

    @Query("SELECT * FROM action_episodes WHERE outcome = 'failure' ORDER BY timestamp DESC LIMIT 20")
    suspend fun getRecentFailures(): List<ActionEpisode>

    @Query("DELETE FROM action_episodes WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT * FROM action_episodes WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getSince(since: Long): List<ActionEpisode>
}
