package com.vdx.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "action_episodes")
data class ActionEpisode(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val goal: String,
    val action: String,
    val target: String,
    val outcome: String,
    val memoriesReferenced: String = "[]",
    val errorDetail: String = "",
    val durationMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
)
