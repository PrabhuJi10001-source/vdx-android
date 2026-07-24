package com.vdx.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single user memory — a fact the user has told VDX that should persist
 * across sessions. Examples: "Mom's number is +971****4567", "I live in Dubai",
 * "My favorite app is WhatsApp", "Ravi is my colleague".
 *
 * Each memory has a type for semantic grouping:
 * - contact: phone numbers, names, relationships
 * - preference: app preferences, voice settings, defaults
 * - location: home, work, frequent destinations
 * - fact: any other durable information
 * - habit: recurring patterns ("I call Mom every evening")
 * - relationship: how people relate to the user ("Ravi is my colleague")
 * - default: default app/behavior for a person ("always use WhatsApp for Ravi")
 */
@Entity(tableName = "user_memories")
data class UserMemory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String,          // contact | preference | location | fact | habit | relationship | default
    val key: String,           // normalized lookup key (e.g. "mom", "home", "default_app")
    val value: String,         // the stored value
    val context: String = "",  // optional context (e.g. "WhatsApp contact", "Uber destination")
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,  // how often this memory has been retrieved
)
