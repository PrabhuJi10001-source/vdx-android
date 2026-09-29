package com.vdx.sonic.knowledge

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for app navigation knowledge.
 *
 * Stores structured navigation steps for a given app (package) + action pair,
 * keyed by locale so the Planner can retrieve locale-appropriate instructions.
 *
 * Voice-first knowledge base pattern: agents trained on FAQs/files/URLs
 * produce structured navigation knowledge. Here we store it in Room so it persists
 * and can be updated without app redeployment.
 */
@Entity(
    tableName = "app_knowledge",
    indices = [Index(value = ["packageName", "action", "locale"], unique = true)]
)
data class AppKnowledge(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val action: String,       // e.g. "send_message", "book_ride", "make_call"
    val steps: String,        // JSON array of NavStep objects
    val locale: String,       // language for this knowledge ("en", "hi", "hinglish")
    val lastUpdated: Long = System.currentTimeMillis()
)