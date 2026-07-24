package com.vdx.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [UserMemory::class, ActionLog::class], version = 2, exportSchema = false)
abstract class VdxMemoryDatabase : RoomDatabase() {

    abstract fun userMemoryDao(): UserMemoryDao
    abstract fun actionLogDao(): ActionLogDao

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
            return Room.databaseBuilder(
                context.applicationContext,
                VdxMemoryDatabase::class.java,
                DB_NAME
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
