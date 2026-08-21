package com.vdx.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_contradictions")
data class MemoryContradiction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val nodeIdA: Long,
    val nodeIdB: Long,
    val fieldA: String,
    val valueA: String,
    val valueB: String,
    val detectedAt: Long = System.currentTimeMillis(),
    val resolved: Boolean = false,
    val resolution: String = "",
)
