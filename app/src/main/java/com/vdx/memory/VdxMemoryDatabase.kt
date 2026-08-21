package com.vdx.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserMemory::class,
        ActionLog::class,
        MemoryNode::class,
        MemoryEdge::class,
        ActionEpisode::class,
        MemoryContradiction::class
    ],
    version = 3,
    exportSchema = false
)
abstract class VdxMemoryDatabase : RoomDatabase() {

    abstract fun userMemoryDao(): UserMemoryDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun memoryNodeDao(): MemoryNodeDao
    abstract fun memoryEdgeDao(): MemoryEdgeDao
    abstract fun actionEpisodeDao(): ActionEpisodeDao
    abstract fun memoryContradictionDao(): MemoryContradictionDao

    companion object {
        @Volatile
        private var INSTANCE: VdxMemoryDatabase? = null
        private const val DB_NAME = "vdx_memory.db"

        fun getInstance(context: Context): VdxMemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): VdxMemoryDatabase {
            val isRobolectric = android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)
                || android.os.Build.PRODUCT.contains("robolectric", ignoreCase = true)
            val builder = if (isRobolectric) {
                Room.inMemoryDatabaseBuilder(
                    context.applicationContext,
                    VdxMemoryDatabase::class.java
                )
            } else {
                Room.databaseBuilder(
                    context.applicationContext,
                    VdxMemoryDatabase::class.java,
                    DB_NAME
                )
            }
            return builder
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        }

        /** Test helper — clears singleton so each Robolectric test gets a fresh DB. */
        fun resetForTests() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
