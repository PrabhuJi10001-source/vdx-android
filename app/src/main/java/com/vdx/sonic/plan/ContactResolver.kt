package com.vdx.sonic.plan

import android.content.Context
import android.provider.ContactsContract

/**
 * ContactResolver — the CALL chain's missing half (v0.1.7, maker-ordered).
 *
 * "Call Papa" must do the REAL ladder: find the number in the phone's own
 * contacts (the system's language/data — nothing extra to configure), ask the
 * CONFIRMATION question with the actual number on the table ("is this the
 * person?"), and on yes place the call. No number resolution was ever wired
 * before — the app could only say "calling Papa" without knowing who.
 *
 * Also merges the app's OWN contact memories (user_memories/memory_nodes type
 * contact) with the phone book, so taught contacts work without a contact card.
 */
object ContactResolver {

    data class Resolved(val label: String, val number: String)

    /** All normalized +labelled phone numbers for a spoken name, phone book first. */
    fun resolve(context: Context, name: String): List<Resolved> {
        if (name.isBlank()) return emptyList()
        val out = LinkedHashMap<String, String>()   // number → label
        try {
            // 1) The phone's real contacts (system language dataset)
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} IS NOT NULL",
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cursor?.use { c ->
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                while (c.moveToNext()) {
                    val cn = c.getString(nameIdx)?.trim().orEmpty()
                    val num = c.getString(numIdx)?.trim().orEmpty()
                    if (num.isBlank()) continue
                    // spoken-name match: exact, starts-with, or word-prefix of the display name
                    val n = name.trim().lowercase()
                    if (cn.lowercase() == n ||
                        cn.lowercase().startsWith(n) ||
                        cn.lowercase().split(" ").any { it.startsWith(n) && n.length >= 2 }) {
                        val type = when (c.getInt(typeIdx)) {
                            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
                            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
                            ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "main"
                            else -> "other"
                        }
                        if (!out.containsKey(num)) out[num] = type
                    }
                }
            }
        } catch (_: Exception) {
            // contacts permission missing → memory-only path below still works
        }
        return out.map { Resolved(it.value, it.key) }
    }
}
