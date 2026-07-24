package com.vdx.memory

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * UserMemoryStore — high-level API for reading/writing user memories.
 *
 * Replaces the old in-memory [com.vdx.SessionMemory] with persistent Room storage.
 * Memories survive app restarts and grow over time.
 *
 * Usage:
 * ```
 * val store = UserMemoryStore(context)
 * store.remember("contact", "mom", "+971501234567", "Mom's phone number")
 * val momNumber = store.recall("mom")  // returns "+971501234567"
 * ```
 */
class UserMemoryStore(private val context: Context) {

    private val dao = VdxMemoryDatabase.getInstance(context).userMemoryDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Store a memory. Async — returns immediately. */
    fun remember(type: String, key: String, value: String, context: String = "") {
        scope.launch {
            val existing = dao.getByKey(key)
            if (existing != null) {
                dao.upsert(existing.copy(
                    type = type,
                    value = value,
                    context = context,
                    updatedAt = System.currentTimeMillis()
                ))
            } else {
                dao.upsert(UserMemory(
                    type = type,
                    key = key,
                    value = value,
                    context = context
                ))
            }
        }
    }

    /** Retrieve a memory by key. Returns null if not found. */
    suspend fun recall(key: String): String? {
        val memory = dao.getByKey(key) ?: return null
        dao.incrementAccess(memory.id)
        return memory.value
    }

    /** Synchronous recall for use in non-coroutine contexts (e.g. PatternDetector). */
    fun recallSync(key: String): String? {
        return try {
            kotlinx.coroutines.runBlocking { recall(key) }
        } catch (e: Exception) {
            null
        }
    }

    /** Get the full UserMemory object by key (not just the value). */
    suspend fun getMemoryByKey(key: String): UserMemory? = dao.getByKey(key)

    /** Search memories by keyword. */
    suspend fun search(query: String): List<UserMemory> = dao.search(query)

    /** Get all memories of a type. */
    suspend fun getByType(type: String): List<UserMemory> = dao.getByType(type)

    /** Get the most frequently accessed memories. */
    suspend fun getTopMemories(): List<UserMemory> = dao.getTopMemories()

    /** Delete a memory by key. */
    fun forget(key: String) {
        scope.launch { dao.deleteByKey(key) }
    }

    /** Total memory count. */
    suspend fun count(): Int = dao.count()
}
