package com.vdx.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A log entry for every action the user executes through VDX.
 * Used to detect usage patterns over time (Veda's pattern detection, but from app usage).
 *
 * Examples:
 * - User calls Mom every evening → multiple Call actions with contact="Mom" at ~same time
 * - User orders Uber Eats every Friday → multiple Uber actions with destination containing food keywords
 * - User searches YouTube for "ice cream recipes" repeatedly → multiple YouTube actions with similar queries
 */
@Entity(tableName = "action_log")
data class ActionLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val action: String,          // call | whatsapp | sms | uber | youtube | email | app_launch | read_screen
    val target: String,          // the contact, destination, search query, or app name
    val detail: String = "",     // additional context (message body, etc.)
    val timestamp: Long = System.currentTimeMillis(),
)
