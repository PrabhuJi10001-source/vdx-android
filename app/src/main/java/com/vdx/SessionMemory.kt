package com.vdx

class SessionMemory {
    private val items = mutableListOf<String>()

    fun add(item: String) {
        items.add(item)
        // ponytail: unbounded list — V1 session-only, dies with process. Upgrade: Room DB.
        if (items.size > 100) items.removeAt(0)
    }

    fun getAll(): List<String> = items.toList()

    fun clear() = items.clear()
}