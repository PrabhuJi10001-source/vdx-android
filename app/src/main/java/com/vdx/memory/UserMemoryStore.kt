package com.vdx.memory

import android.content.Context

/**
 * Compatibility facade over [MemoryStore] for existing callers/tests.
 * Maps flat key/value API onto V2 graph nodes.
 */
class UserMemoryStore(context: Context) {

    private val store = MemoryStore(context)

    fun remember(type: String, key: String, value: String, context: String = "") {
        store.remember(type, key, value, context, aliases = key.lowercase())
    }

    suspend fun recall(key: String): String? = store.recall(key)

    fun recallSync(key: String): String? = store.recallSync(key)

    suspend fun getMemoryByKey(key: String): UserMemory? {
        val nodes = store.search(key)
        val node = nodes.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: return null
        return UserMemory(
            id = node.id,
            type = node.type,
            key = node.name,
            value = node.value,
            context = node.context,
            createdAt = node.createdAt,
            updatedAt = node.updatedAt,
            accessCount = node.reads30d
        )
    }

    suspend fun search(query: String): List<UserMemory> =
        store.search(query).map { it.toUserMemory() }

    suspend fun getByType(type: String): List<UserMemory> =
        store.getByType(type).map { it.toUserMemory() }

    suspend fun getTopMemories(): List<UserMemory> =
        store.getTopMemories().map { it.toUserMemory() }

    fun forget(key: String) {
        store.forget(key)
    }

    suspend fun count(): Int = store.count()

    private fun MemoryNode.toUserMemory() = UserMemory(
        id = id,
        type = type,
        key = name,
        value = value,
        context = context,
        createdAt = createdAt,
        updatedAt = updatedAt,
        accessCount = reads30d
    )
}
