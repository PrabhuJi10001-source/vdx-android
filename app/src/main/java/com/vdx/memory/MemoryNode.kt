package com.vdx.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_nodes")
data class MemoryNode(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String,
    val name: String,
    val value: String,
    val context: String = "",
    val aliases: String = "",
    val properties: String = "{}",
    val confidence: Float = 0.90f,
    val confidenceSource: String = "user-confirmed",
    val vitalityScore: Float = 50f,
    val vitalityState: String = "active",
    val reads7d: Int = 0,
    val reads30d: Int = 0,
    val correctionEvents: Int = 0,
    val lastReadAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
