package com.vdx.memory

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_edges",
    foreignKeys = [
        ForeignKey(
            entity = MemoryNode::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MemoryNode::class,
            parentColumns = ["id"],
            childColumns = ["targetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sourceId"), Index("targetId"), Index("relationType")]
)
data class MemoryEdge(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourceId: Long,
    val targetId: Long,
    val relationType: String,
    val properties: String = "{}",
    val confidence: Float = 1.0f,
    val createdAt: Long = System.currentTimeMillis(),
)
