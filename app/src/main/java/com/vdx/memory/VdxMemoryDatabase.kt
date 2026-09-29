package com.vdx.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.vdx.sonic.knowledge.AppKnowledge
import com.vdx.sonic.knowledge.AppKnowledgeDao

@Database(
    entities = [
        UserMemory::class,
        ActionLog::class,
        MemoryNode::class,
        MemoryEdge::class,
        ActionEpisode::class,
        MemoryContradiction::class,
        DraftNote::class,
        AppKnowledge::class
    ],
    version = 5,
    exportSchema = true
)
abstract class VdxMemoryDatabase : RoomDatabase() {

    abstract fun userMemoryDao(): UserMemoryDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun memoryNodeDao(): MemoryNodeDao
    abstract fun memoryEdgeDao(): MemoryEdgeDao
    abstract fun actionEpisodeDao(): ActionEpisodeDao
    abstract fun memoryContradictionDao(): MemoryContradictionDao
    abstract fun draftNoteDao(): DraftNoteDao
    abstract fun appKnowledgeDao(): AppKnowledgeDao

    companion object {
        @Volatile
        private var INSTANCE: VdxMemoryDatabase? = null
        private const val DB_NAME = "vdx_memory.db"

        /**
         * v3 → v4: add provenance (source) and visibility (scope) to memory_nodes.
         * Existing v3 rows default to source='legacy' (provenance unknown) and scope='global'
         * (fully visible), which is the safest superset for pre-existing memories.
         * FKs and indexes are unaffected — ALTER TABLE ADD COLUMN preserves them.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE memory_nodes " +
                        "ADD COLUMN source TEXT NOT NULL DEFAULT 'legacy'"
                )
                db.execSQL(
                    "ALTER TABLE memory_nodes " +
                        "ADD COLUMN scope TEXT NOT NULL DEFAULT 'global'"
                )
                // v4 adds the draft_notes entity; Room does NOT auto-create tables
                // during a migration — the migration must create it.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS draft_notes (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "body TEXT NOT NULL, scope TEXT NOT NULL, sourceTranscript TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v4 → v5: add app_knowledge table for the app navigation knowledge base.
         * Non-destructive — only creates a new table, no existing data is touched.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS app_knowledge (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "packageName TEXT NOT NULL, " +
                        "action TEXT NOT NULL, " +
                        "steps TEXT NOT NULL, " +
                        "locale TEXT NOT NULL, " +
                        "lastUpdated INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_app_knowledge_packageName_action_locale " +
                        "ON app_knowledge(packageName, action, locale)"
                )
            }
        }

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
            // Fail-closed: NO destructive fallback. If the migration path is unavailable,
            // Room throws on open rather than silently wiping the user's memory graph.
            // In-memory DBs build the current schema directly, so no migration/fallback needed.
            return builder
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
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