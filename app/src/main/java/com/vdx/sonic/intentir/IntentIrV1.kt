package com.vdx.sonic.intentir

import com.vdx.sonic.IntentType
import com.vdx.sonic.SonicIntent
import java.util.UUID

/**
 * Maps [SonicIntent] onto conversational Intent IR V1.
 *
 * Does not parse speech. Does not invent domain, authority, or values.
 * Unresolved deixis stays unresolved. Schema: VDX/intent-ir/intent_ir.schema.json
 */
object IntentIrV1 {

    const val SCHEMA_VERSION = "1.0"

    private val DEICTIC = setOf(
        "this", "that", "it", "them", "her", "him", "one",
        "the second one", "that one", "this one"
    )

    fun fromSonic(
        intent: SonicIntent,
        intentId: String = UUID.randomUUID().toString(),
        revision: Int = 1,
    ): Map<String, Any?> {
        val doc = linkedMapOf<String, Any?>(
            "schema_version" to SCHEMA_VERSION,
            "intent_id" to intentId,
            "revision" to revision,
        )
        if (intent.rawText.isNotBlank()) {
            doc["source_text"] = intent.rawText
        }
        actionSlot(intent)?.let { doc["action"] = it }
        objectSlot(intent)?.let { doc["object"] = it }
        targetSlot(intent)?.let { doc["target"] = it }
        val extra = extraSlots(intent)
        if (extra.isNotEmpty()) {
            doc["slots"] = extra
        }
        return doc
    }

    private fun actionSlot(intent: SonicIntent): Map<String, Any?>? {
        if (intent.type == IntentType.UNKNOWN) return null
        val verb = verbFor(intent)
        return resolved(verb, surface = intent.entities["action"] ?: verb)
    }

    private fun objectSlot(intent: SonicIntent): Map<String, Any?>? {
        val contact = intent.entities["contact"]
        if (!contact.isNullOrBlank()) {
            return if (isDeictic(contact)) unresolved(contact) else resolved(contact, contact)
        }
        val target = intent.entities["target"]
        if (!target.isNullOrBlank() && intent.type in setOf(
                IntentType.SYSTEM_TOGGLE, IntentType.GESTURE, IntentType.SYSTEM_QUERY
            )
        ) {
            return resolved(target, target)
        }
        return null
    }

    private fun targetSlot(intent: SonicIntent): Map<String, Any?>? {
        intent.targetApp?.takeIf { it.isNotBlank() }?.let { return resolved(it, it) }
        return when (intent.type) {
            IntentType.WHATSAPP -> resolved("WhatsApp", "WhatsApp")
            IntentType.YOUTUBE_SEARCH, IntentType.YOUTUBE_CONTROL -> resolved("YouTube", "YouTube")
            IntentType.BOOK_RIDE -> resolved("Uber", "Uber")
            IntentType.EMAIL -> resolved("Gmail", "Gmail")
            IntentType.PLAY_STORE -> resolved("Play Store", "Play Store")
            IntentType.APP_LAUNCH -> {
                val app = intent.entities["app"] ?: intent.entities["app_name"]
                if (app.isNullOrBlank()) null else resolved(app, app)
            }
            else -> null
        }
    }

    private fun extraSlots(intent: SonicIntent): List<Map<String, Any?>> {
        val consumed = consumedEntityKeys(intent)
        return intent.entities.mapNotNull { (key, value) ->
            if (key in consumed || value.isBlank() || key == "action") null
            else {
                val slot = linkedMapOf<String, Any?>("name" to key)
                if (isDeictic(value)) {
                    slot["status"] = "unresolved"
                    slot["value"] = null
                    slot["surface"] = value
                } else {
                    slot["status"] = "resolved"
                    slot["value"] = value
                    slot["surface"] = value
                }
                slot
            }
        }
    }

    private fun consumedEntityKeys(intent: SonicIntent): Set<String> {
        val keys = mutableSetOf("action")
        if (!intent.entities["contact"].isNullOrBlank()) keys += "contact"
        if (intent.type in setOf(IntentType.SYSTEM_TOGGLE, IntentType.GESTURE, IntentType.SYSTEM_QUERY)) {
            keys += "target"
        }
        if (intent.type == IntentType.APP_LAUNCH) {
            keys += "app"
            keys += "app_name"
        }
        return keys
    }

    private fun verbFor(intent: SonicIntent): String {
        intent.entities["action"]?.takeIf { it.isNotBlank() }?.let { return it }
        return when (intent.type) {
            IntentType.CALL -> "call"
            IntentType.WHATSAPP, IntentType.SMS, IntentType.EMAIL -> "send"
            IntentType.BOOK_RIDE -> "book"
            IntentType.APP_LAUNCH -> "open"
            IntentType.APP_SWITCH -> "switch"
            IntentType.GO_BACK -> "back"
            IntentType.GO_HOME -> "home"
            IntentType.SEARCH, IntentType.YOUTUBE_SEARCH, IntentType.PLAY_STORE -> "search"
            IntentType.YOUTUBE_CONTROL -> "control"
            IntentType.READ_SCREEN, IntentType.READ_FOCUSED, IntentType.READ_NOTIFICATIONS,
            IntentType.READ_PDF -> "read"
            IntentType.DESCRIBE_IMAGE -> "describe"
            IntentType.GESTURE -> "gesture"
            IntentType.SYSTEM_QUERY -> "query"
            IntentType.SYSTEM_TOGGLE -> "toggle"
            IntentType.SET_ALARM -> "set"
            IntentType.SETTINGS_NAVIGATION -> "open"
            IntentType.FORM_FILL -> "fill"
            IntentType.TEXT_EDIT -> "edit"
            IntentType.MEMORY_STORE -> "store"
            IntentType.MEMORY_RECALL -> "recall"
            IntentType.DRAFT_NOTE -> "draft"
            IntentType.CANCEL -> "cancel"
            IntentType.CONTACT_MANAGE -> "contact"
            IntentType.UNKNOWN -> "unknown"
        }
    }

    private fun isDeictic(value: String): Boolean =
        value.trim().lowercase() in DEICTIC

    private fun resolved(value: String, surface: String): Map<String, Any?> =
        linkedMapOf("status" to "resolved", "value" to value, "surface" to surface)

    private fun unresolved(surface: String): Map<String, Any?> =
        linkedMapOf("status" to "unresolved", "value" to null, "surface" to surface)

    /** First deictic/unresolved surface, or null if the intent is fully resolved. */
    fun firstUnresolvedSurface(intent: SonicIntent): String? {
        val doc = fromSonic(intent)
        fun slot(key: String): String? {
            val m = doc[key] as? Map<*, *> ?: return null
            return if (m["status"] == "unresolved") m["surface"] as? String else null
        }
        slot("object")?.let { return it }
        slot("target")?.let { return it }
        val extras = doc["slots"] as? List<*> ?: return null
        for (item in extras) {
            val m = item as? Map<*, *> ?: continue
            if (m["status"] == "unresolved") return m["surface"] as? String
        }
        return null
    }
}
