package com.vdx.sonic.knowledge

import androidx.room.*

@Dao
interface AppKnowledgeDao {

    @Query("SELECT * FROM app_knowledge WHERE packageName = :packageName AND action = :action AND locale = :locale LIMIT 1")
    suspend fun getForApp(packageName: String, action: String, locale: String): AppKnowledge?

    @Query("SELECT * FROM app_knowledge WHERE packageName = :packageName")
    suspend fun getAllForApp(packageName: String): List<AppKnowledge>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(knowledge: AppKnowledge): Long

    @Query("DELETE FROM app_knowledge WHERE packageName = :packageName AND action = :action")
    suspend fun delete(packageName: String, action: String)

    @Query("SELECT COUNT(*) FROM app_knowledge")
    suspend fun count(): Int
}