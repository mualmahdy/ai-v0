package com.example.convergence

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.infrastructure.persistence.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * GAP-08 (Design Closure 2026, ADR-7) — Migration16to17Test
 * ============================================================================
 *
 * Validates MIGRATION_16_TO_17 directly against a raw SQLite database seeded
 * with the v16-era doomed tables plus surviving tables holding real data:
 *
 *   1. The three dead tables are DROPPED: `provider_configs` (legacy
 *      provider island — entity graph removed long ago),
 *      `policy_versions` (PolicyVersionService had zero production
 *      consumers) and `health_probes` (write-only; recordHealthProbe was
 *      never called in production).
 *   2. ALL data in surviving tables is untouched (drop-only migration).
 *   3. The drop is idempotent-safe (IF EXISTS) — converging every
 *      historical upgrade path v1..v16 onto the same v17 end state.
 *
 * Full-chain identity validation (Room's own onValidateSchema for v1→17 and
 * v15→17) lives in MigrationChainValidationTest.
 */
@RunWith(RobolectricTestRunner::class)
class Migration16to17Test {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    private fun createRawDb(): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null) // in-memory
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) { /* no-op */ }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) { /* no-op */ }
                })
                .build()
        )
        return helper.writableDatabase
    }

    /** A representative v16-era schema: the three doomed tables + survivors. */
    private fun seedV16Schema(db: SupportSQLiteDatabase) {
        // --- doomed: provider_configs (created historically by MIGRATION_2_TO_3) ---
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS provider_configs (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                category TEXT NOT NULL,
                flavor TEXT NOT NULL,
                endpointUrl TEXT NOT NULL,
                defaultModelId TEXT NOT NULL,
                isEnabled INTEGER NOT NULL,
                isDefault INTEGER NOT NULL,
                healthStatus TEXT NOT NULL,
                lastValidatedEpochMs INTEGER NOT NULL,
                lastLatencyMs INTEGER NOT NULL,
                lastErrorMessage TEXT,
                extraHeadersJson TEXT,
                timeoutSeconds INTEGER NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // --- doomed: policy_versions (created historically by MIGRATION_7_TO_8) ---
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS policy_versions (
                versionId TEXT NOT NULL PRIMARY KEY,
                policyKind TEXT NOT NULL,
                versionLabel TEXT NOT NULL,
                snapshotJson TEXT NOT NULL,
                evaluationReportJson TEXT,
                isPromoted INTEGER NOT NULL,
                promotedBy TEXT NOT NULL,
                promotedAtEpochMs INTEGER,
                createdAtEpochMs INTEGER NOT NULL,
                parentVersionId TEXT
            )
            """.trimIndent()
        )
        // --- doomed: health_probes (created historically by MIGRATION_7_TO_8) ---
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS health_probes (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                resourceId TEXT NOT NULL,
                resourceType TEXT NOT NULL,
                isHealthy INTEGER NOT NULL,
                latencyMs INTEGER NOT NULL,
                errorMessage TEXT,
                probedAtEpochMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // --- survivors (shape mirrors the v16 entity graph) ---
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                description TEXT,
                rootPath TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                isArchived INTEGER NOT NULL DEFAULT 0,
                workspaceId TEXT,
                lifecycleState TEXT NOT NULL DEFAULT 'ACTIVE',
                archivedAtEpochMs INTEGER,
                trashedAtEpochMs INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS audit_trail (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                action TEXT NOT NULL,
                actorType TEXT,
                actorId TEXT,
                workspaceId TEXT,
                occurredAtEpochMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS human_approval_requests (
                approvalId TEXT NOT NULL PRIMARY KEY,
                executionId TEXT NOT NULL,
                toolName TEXT NOT NULL,
                resolution TEXT NOT NULL DEFAULT 'PENDING',
                expiresAtEpochMs INTEGER NOT NULL,
                isTokenConsumed INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        // Seed rows: doomed tables hold orphaned data; survivors hold live data.
        db.execSQL("INSERT INTO provider_configs (id, name, category, flavor, endpointUrl, defaultModelId, isEnabled, isDefault, healthStatus, lastValidatedEpochMs, lastLatencyMs, timeoutSeconds, createdAtEpochMs, updatedAtEpochMs) VALUES ('pc1', 'Legacy', 'LLM', 'GEMINI', 'https://x', 'm', 1, 0, 'UNKNOWN', 0, 0, 30, 0, 0)")
        db.execSQL("INSERT INTO policy_versions (versionId, policyKind, versionLabel, snapshotJson, isPromoted, promotedBy, createdAtEpochMs) VALUES ('pv1', 'ROUTING', 'v1', '{}', 1, 'sys', 0)")
        db.execSQL("INSERT INTO health_probes (resourceId, resourceType, isHealthy, latencyMs, probedAtEpochMs) VALUES ('res1', 'LLM', 1, 10, 0)")
        db.execSQL("INSERT INTO projects (name, rootPath, createdAtEpochMs, updatedAtEpochMs, workspaceId) VALUES ('Survivor', '/tmp', 1, 1, 'ws-1')")
        db.execSQL("INSERT INTO audit_trail (action, actorType, actorId, workspaceId, occurredAtEpochMs) VALUES ('APPROVE', 'USER', 'user-1', 'ws-1', 5)")
        db.execSQL("INSERT INTO human_approval_requests (approvalId, executionId, toolName, resolution, expiresAtEpochMs) VALUES ('apr-1', 'exec-1', 'delete_file', 'PENDING', 99)")
    }

    private fun runMigration16To17(db: SupportSQLiteDatabase) {
        val field = AppDatabase::class.java.getDeclaredField("MIGRATION_16_TO_17")
        field.isAccessible = true
        val migration = field.get(null) as androidx.room.migration.Migration
        migration.migrate(db)
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { c ->
            return c.moveToFirst()
        }
    }

    @Test
    fun `the three dead tables are dropped`() {
        createRawDb().use { db ->
            seedV16Schema(db)
            assertTrue(tableExists(db, "provider_configs"))
            assertTrue(tableExists(db, "policy_versions"))
            assertTrue(tableExists(db, "health_probes"))
            runMigration16To17(db)
            assertFalse("provider_configs must be dropped by v16→v17", tableExists(db, "provider_configs"))
            assertFalse("policy_versions must be dropped by v16→v17", tableExists(db, "policy_versions"))
            assertFalse("health_probes must be dropped by v16→v17", tableExists(db, "health_probes"))
        }
    }

    @Test
    fun `surviving tables and their data are untouched`() {
        createRawDb().use { db ->
            seedV16Schema(db)
            runMigration16To17(db)
            db.query("SELECT COUNT(*) FROM projects").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM audit_trail").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM human_approval_requests").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        }
    }

    @Test
    fun `drop is idempotent-safe on a database that already lost the tables`() {
        createRawDb().use { db ->
            // A v16 database where the doomed tables were already absent
            // (e.g. exotic partial states): IF EXISTS keeps the migration
            // from crashing — every path converges on the same v17 state.
            db.execSQL("CREATE TABLE IF NOT EXISTS projects (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, rootPath TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL)")
            runMigration16To17(db)
            assertTrue(tableExists(db, "projects"))
            assertFalse(tableExists(db, "provider_configs"))
        }
    }
}
