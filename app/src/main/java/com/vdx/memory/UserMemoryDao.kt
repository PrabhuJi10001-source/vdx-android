package com.vdx.memory

import androidx.room.*

@Dao
interface UserMemoryDao {

    @Query("SELECT * FROM user_memories WHERE `key` = :key ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getByKey(key: String): UserMemory?

    @Query("SELECT * FROM user_memories WHERE type = :type ORDER BY updatedAt DESC")
    suspend fun getByType(type: String): List<UserMemory>

    @Query("SELECT * FROM user_memories WHERE `key` LIKE '%' || :query || '%' OR value LIKE '%' || :query || '%' ORDER BY accessCount DESC")
    suspend fun search(query: String): List<UserMemory>

    @Query("SELECT * FROM user_memories ORDER BY accessCount DESC LIMIT 20")
    suspend fun getTopMemories(): List<UserMemory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: UserMemory): Long

    @Query("UPDATE user_memories SET accessCount = accessCount + 1, updatedAt = :now WHERE id = :id")
    suspend fun incrementAccess(id: Long, now: Long = System.currentTimeMillis())

    @Delete
    suspend fun delete(memory: UserMemory)

    @Query("DELETE FROM user_memories WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("SELECT COUNT(*) FROM user_memories")
    suspend fun count(): Int
}
