package com.vdx.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Migration 3→4 gate.
 *
 * Verifies, against a REAL v3 on-disk database seeded with real memory graph data,
 * that the production MIGRATION_3_4 object preserves nodes, edges, FKs, indexes,
 * and other tables — and that the builder is FAIL-CLOSED (missing migration ⇒ throw,
 * never a silent wipe). The migration runs through the same code path a production
 * open() uses (registered via addMigrations).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class V3MigrationTest {

    private lateinit var context: Context
    private val dbName = "migration_test_v3.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        VdxMemoryDatabase.resetForTests()
        context.deleteDatabase(dbName)
    }

    /** Exact v3 DDL — mirrors the schema that existed at version 3. */
    private fun createV3Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS memory_nodes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, " +
                "value TEXT NOT NULL, context TEXT NOT NULL, aliases TEXT NOT NULL, properties TEXT NOT NULL, " +
                "confidence REAL NOT NULL, confidenceSource TEXT NOT NULL, vitalityScore REAL NOT NULL, " +
                "vitalityState TEXT NOT NULL, reads7d INTEGER NOT NULL, reads30d INTEGER NOT NULL, " +
                "correctionEvents INTEGER NOT NULL, lastReadAt INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS memory_edges (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceId INTEGER NOT NULL, targetId INTEGER NOT NULL, " +
                "relationType TEXT NOT NULL, properties TEXT NOT NULL, confidence REAL NOT NULL, createdAt INTEGER NOT NULL, " +
                "FOREIGN KEY(sourceId) REFERENCES memory_nodes(id) ON DELETE CASCADE, " +
                "FOREIGN KEY(targetId) REFERENCES memory_nodes(id) ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_edges_sourceId ON memory_edges(sourceId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_edges_targetId ON memory_edges(targetId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_edges_relationType ON memory_edges(relationType)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS user_memories (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, key TEXT NOT NULL, " +
                "value TEXT NOT NULL, context TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                "accessCount INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS action_episodes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, goal TEXT NOT NULL, action TEXT NOT NULL, " +
                "target TEXT NOT NULL, outcome TEXT NOT NULL, memoriesReferenced TEXT NOT NULL, " +
                "errorDetail TEXT NOT NULL, durationMs INTEGER NOT NULL, timestamp INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS action_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, action TEXT NOT NULL, target TEXT NOT NULL, " +
                "detail TEXT NOT NULL, timestamp INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS memory_contradictions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, nodeIdA INTEGER NOT NULL, nodeIdB INTEGER NOT NULL, " +
                "fieldA TEXT NOT NULL, valueA TEXT NOT NULL, valueB TEXT NOT NULL, detectedAt INTEGER NOT NULL, " +
                "resolved INTEGER NOT NULL, resolution TEXT NOT NULL)"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT NOT NULL)")
    }

    private fun seedV3(db: SupportSQLiteDatabase) {
        // Columns: type,name,value,context,aliases,properties,confidence,confidenceSource,
        //          vitalityScore,vitalityState,reads7d,reads30d,correctionEvents,lastReadAt,createdAt,updatedAt
        db.execSQL(
            "INSERT INTO memory_nodes (type,name,value,context,aliases,properties,confidence,confidenceSource," +
                "vitalityScore,vitalityState,reads7d,reads30d,correctionEvents,lastReadAt,createdAt,updatedAt) " +
                "VALUES ('contact','mom','+9715','','momma,mother','{}',0.99,'user-confirmed',80,'thriving',5,20,0,0,0,0)"
        )
        db.execSQL(
            "INSERT INTO memory_nodes (type,name,value,context,aliases,properties,confidence,confidenceSource," +
                "vitalityScore,vitalityState,reads7d,reads30d,correctionEvents,lastReadAt,createdAt,updatedAt) " +
                "VALUES ('location','home','Dubai Marina','','home addr','{}',0.95,'user-confirmed',60,'active',2,10,0,0,0,0)"
        )
        // Graph relationship: mom related_to home
        db.execSQL(
            "INSERT INTO memory_edges (sourceId,targetId,relationType,properties,confidence,createdAt) " +
                "VALUES (1,2,'related_to','{}',1.0,0)"
        )
        db.execSQL(
            "INSERT INTO user_memories (type,key,value,context,createdAt,updatedAt,accessCount) " +
                "VALUES ('preference','default_app','whatsapp','',0,0,0)"
        )
        db.execSQL("INSERT INTO room_master_table (id,identity_hash) VALUES (42,'legacy_v3_hash')")
    }

    /** Build a v3 on-disk DB seeded with real data; returns closed helper. */
    private fun buildV3(): SupportSQLiteOpenHelper {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createV3Schema(db)
                        seedV3(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.close()
        return helper
    }

    @Test
    fun migration_preservesNodesEdgesAndDefaults() = runBlocking {
        buildV3()
        val file = File(context.getDatabasePath(dbName).absolutePath)
        assertTrue("v3 seed db should exist", file.exists())

        // Open through the production Room path WITH the registered migrations.
        val roomDb = Room.databaseBuilder(context, VdxMemoryDatabase::class.java, dbName)
            .addMigrations(VdxMemoryDatabase.MIGRATION_3_4, VdxMemoryDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        try {
            val nodeDao = roomDb.memoryNodeDao()
            val edgeDao = roomDb.memoryEdgeDao()
            val userMemDao = roomDb.userMemoryDao()

            // 1. Existing v3 rows survive
            assertEquals("both nodes survive", 2, nodeDao.count())
            val mom = nodeDao.getByName("mom")
            assertNotNull("Mom survives", mom)
            assertEquals("+9715", mom!!.value)

            // 2. New columns default correctly for legacy rows
            assertEquals("source defaults to legacy", "legacy", mom.source)
            assertEquals("scope defaults to global", "global", mom.scope)

            // 3. Graph relationships survive
            val edges = edgeDao.getEdgesForNode(mom.id)
            assertEquals("edge survives", 1, edges.size)
            assertEquals("relationType preserved", "related_to", edges.first().relationType)
            assertEquals("edge target preserved", "home", nodeDao.getById(edges.first().targetId)?.name)

            // 4. Other tables survive
            val userMem = userMemDao.getByKey("default_app")
            assertNotNull("user memory survives", userMem)
            assertEquals("whatsapp", userMem!!.value)
        } finally {
            roomDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun builder_failsClosed_withoutMigration() {
        buildV3()
        val file = File(context.getDatabasePath(dbName).absolutePath)
        assertTrue(file.exists())

        // No registered migration → Room must THROW on open, never wipe silently.
        val thrown = try {
            val db = Room.databaseBuilder(context, VdxMemoryDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            db.openHelper.writableDatabase
            db.close()
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull("open without migration must throw (fail closed)", thrown)

        // The old db file must still be intact.
        assertTrue("old db file must survive failed open", file.exists())

        context.deleteDatabase(dbName)
    }
}
