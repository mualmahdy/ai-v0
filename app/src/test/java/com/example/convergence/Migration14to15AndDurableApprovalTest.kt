package com.example.convergence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.security.governance.ApprovalResolution
import com.example.domain.ports.governed.HumanApprovalRequest
import com.example.infrastructure.governed.RoomHumanApprovalStore
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.TaskEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * P1-7 / P1-10 / §17 (audit 2026) — v15: workspace identity + DURABLE
 * human approvals.
 * ============================================================================
 *
 * Proves, against a REAL SQLite database:
 *
 *  1. MIGRATION_14_TO_15 adds the workspace identity columns to
 *     `tasks` / `execution_logs` / `execution_trace_nodes` (data preserved),
 *     the delegation-lineage index, and the `human_approval_requests`
 *     table — the §17 durability foundation.
 *  2. Workspace-scoped TaskDao queries see ONLY the owning workspace's
 *     tasks (P1-7: another workspace's rows are indistinguishable from
 *     nonexistent).
 *  3. The Room-backed approval store SURVIVES "process death" (a brand-new
 *     store instance over the same database) — §17: approvals are durable.
 *  4. One-shot token semantics hold across that restart.
 */
@RunWith(RobolectricTestRunner::class)
class Migration14to15AndDurableApprovalTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    private fun createRawDb(): androidx.sqlite.db.SupportSQLiteDatabase {
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null) // in-memory
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        return helper.writableDatabase
    }

    /** The COMPLETE v14-era schema for the three tables this migration touches. */
    private fun seedV14Schema(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tasks (
                id TEXT NOT NULL PRIMARY KEY,
                assignedAgentId TEXT NOT NULL,
                rawPrompt TEXT NOT NULL,
                lifecycleState TEXT NOT NULL,
                autonomyPolicy TEXT NOT NULL,
                resultSummary TEXT,
                totalTokensConsumed INTEGER NOT NULL,
                durationMs INTEGER NOT NULL,
                isDegraded INTEGER NOT NULL,
                degradedReason TEXT,
                errorMessage TEXT,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                goal TEXT NOT NULL,
                currentStepIndex INTEGER NOT NULL,
                tokenLimit INTEGER NOT NULL,
                maxRetries INTEGER NOT NULL,
                allowDegradedExecution INTEGER NOT NULL,
                requireHumanConsentForSensitiveTools INTEGER NOT NULL,
                timeoutMs INTEGER NOT NULL,
                minOutputLengthChars INTEGER NOT NULL,
                verificationStrategy TEXT NOT NULL,
                assignedModelId TEXT,
                activeToolsJson TEXT,
                requiredCapabilitiesJson TEXT,
                requiredEvidenceKeysJson TEXT,
                requiredOutputKeysJson TEXT,
                executionLogJson TEXT,
                parentTaskId TEXT,
                delegationDepth INTEGER NOT NULL,
                checkpointJson TEXT,
                executionContextJson TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS execution_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                executionId TEXT NOT NULL,
                sessionId TEXT NOT NULL,
                eventType TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                timestampEpochMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS execution_trace_nodes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                executionId TEXT NOT NULL,
                stepIndex INTEGER NOT NULL,
                actionType TEXT NOT NULL,
                targetResourceId TEXT,
                agentId TEXT,
                startedAtEpochMs INTEGER NOT NULL,
                completedAtEpochMs INTEGER,
                durationMs INTEGER,
                outcome TEXT NOT NULL,
                summary TEXT NOT NULL,
                observationSummary TEXT
            )
            """.trimIndent()
        )
        // Seed one row per table with user data that must survive.
        db.execSQL(
            "INSERT INTO tasks (id, assignedAgentId, rawPrompt, lifecycleState, autonomyPolicy, " +
                "totalTokensConsumed, durationMs, isDegraded, createdAtEpochMs, updatedAtEpochMs, goal, " +
                "currentStepIndex, tokenLimit, maxRetries, allowDegradedExecution, " +
                "requireHumanConsentForSensitiveTools, timeoutMs, minOutputLengthChars, " +
                "verificationStrategy, delegationDepth) VALUES " +
                "('t_legacy', 'agent_x', 'prompt', 'RUNNING', 'SUPERVISED', 0, 0, 0, 1, 1, 'prompt', 0, 30000, 3, 1, 1, 60000, 1, 'STRICT', 0)"
        )
        db.execSQL(
            "INSERT INTO execution_logs (executionId, sessionId, eventType, payloadJson, timestampEpochMs) " +
                "VALUES ('exec_1', 'agent', 'TRACE_NODE', '{}', 1)"
        )
        db.execSQL(
            "INSERT INTO execution_trace_nodes (executionId, stepIndex, actionType, startedAtEpochMs, outcome, summary) " +
                "VALUES ('exec_1', 0, 'STARTED', 1, 'STARTED', 's')"
        )
    }

    private fun columnsOf(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info($table)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }

    @Test
    fun `migration 14 to 15 adds workspace identity, lineage index and the approvals table`() {
        createRawDb().use { db ->
            seedV14Schema(db)

            val migrationField = AppDatabase::class.java.getDeclaredField("MIGRATION_14_TO_15")
            migrationField.isAccessible = true
            val migration = migrationField.get(null) as androidx.room.migration.Migration
            migration.migrate(db)

            // 1. workspace identity columns exist on all three tables.
            assertTrue("tasks.workspaceId missing", columnsOf(db, "tasks").contains("workspaceId"))
            assertTrue("execution_logs.workspaceId missing", columnsOf(db, "execution_logs").contains("workspaceId"))
            assertTrue("execution_trace_nodes.workspaceId missing", columnsOf(db, "execution_trace_nodes").contains("workspaceId"))

            // 2. existing data SURVIVES (legacy rows stay unattributed-null).
            db.query("SELECT id, lifecycleState FROM tasks WHERE id = 't_legacy'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("t_legacy", cursor.getString(0))
                assertEquals("RUNNING", cursor.getString(1))
            }

            // 3. delegation lineage index + workspace indices exist.
            val taskIndices = db.query("PRAGMA index_list(tasks)").use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }
            assertTrue("index_tasks_parentTaskId missing", taskIndices.contains("index_tasks_parentTaskId"))
            assertTrue("index_tasks_workspaceId missing", taskIndices.contains("index_tasks_workspaceId"))

            // 4. the durable approvals table exists with its columns.
            val approvalCols = columnsOf(db, "human_approval_requests")
            listOf(
                "approvalId", "executionId", "toolName", "riskLevel", "prompt", "justification",
                "requestedAtEpochMs", "expiresAtEpochMs", "resolution", "resolvedBy",
                "resolvedAtEpochMs", "isTokenConsumed"
            ).forEach { col ->
                assertTrue("human_approval_requests.$col missing", approvalCols.contains(col))
            }
        }
    }

    @Test
    fun `workspace-scoped task queries see ONLY the owning workspace's tasks`() = runBlocking {
        val db = AppDatabase.getInstance(context)
        val dao = db.taskDao()
        try {
            fun task(id: String, workspaceId: String?) = TaskEntity(
                id = id, assignedAgentId = "a", rawPrompt = "p", lifecycleState = "COMPLETED",
                autonomyPolicy = "SUPERVISED", resultSummary = null, totalTokensConsumed = 0,
                durationMs = 0, isDegraded = false, degradedReason = null, errorMessage = null,
                createdAtEpochMs = 0, updatedAtEpochMs = 0, goal = "g", currentStepIndex = 0,
                tokenLimit = 1000, maxRetries = 0, allowDegradedExecution = true,
                requireHumanConsentForSensitiveTools = true, timeoutMs = 1000,
                minOutputLengthChars = 1, verificationStrategy = "STRICT", workspaceId = workspaceId
            )
            dao.insertOrUpdateTask(task("t_a1", "ws_a"))
            dao.insertOrUpdateTask(task("t_a2", "ws_a"))
            dao.insertOrUpdateTask(task("t_b1", "ws_b"))

            val wsA = dao.getTasksForWorkspace("ws_a")
            assertEquals(listOf("t_a1", "t_a2"), wsA.map { it.id }.sorted())
            val wsB = dao.getTasksForWorkspace("ws_b")
            assertEquals(listOf("t_b1"), wsB.map { it.id })
            // A workspace with no tasks sees NOTHING from the others.
            assertTrue(dao.getTasksForWorkspace("ws_c").isEmpty())
        } finally {
            context.deleteDatabase("agent_orchestrator_platform.db")
        }
    }

    @Test
    fun `durable approval store survives process death and keeps one-shot token semantics`() = runBlocking {
        val db = AppDatabase.getInstance(context)
        try {
            // "First process": create a pending approval, resolve it APPROVED.
            val store1 = RoomHumanApprovalStore(db.humanApprovalRequestDao())
            val request = store1.create(
                HumanApprovalRequest(
                    approvalId = "appr_1",
                    executionId = "exec_1",
                    toolName = "delete_file",
                    riskLevel = "HIGH",
                    prompt = "حذف ملف حساس؟",
                    justification = "اختبار"
                )
            )
            val resolved = store1.resolve("appr_1", ApprovalResolution.APPROVED, "user:test")
            assertNotNull(resolved)
            assertEquals(ApprovalResolution.APPROVED, resolved!!.resolution)

            // "Process death": a BRAND-NEW store instance over the same file.
            val store2 = RoomHumanApprovalStore(db.humanApprovalRequestDao())
            val afterRestart = store2.find("appr_1")
            assertNotNull("§17: the approval must survive the process death", afterRestart)
            assertEquals(ApprovalResolution.APPROVED, afterRestart!!.resolution)
            assertEquals("user:test", afterRestart.resolvedBy)

            // One-shot token: first consumption succeeds, second FAILS.
            assertTrue(store2.markTokenConsumed("appr_1"))
            assertTrue(store2.isTokenConsumed("appr_1"))
            assertFalse("One-shot token must never authorize twice", store2.markTokenConsumed("appr_1"))
            assertTrue(store2.isTokenConsumed("appr_1"))

            // TTL expiry sweep is durable too.
            val expired = store2.create(
                HumanApprovalRequest(
                    approvalId = "appr_stale",
                    executionId = "exec_1",
                    toolName = "delete_file",
                    riskLevel = "HIGH",
                    prompt = "قديم",
                    justification = "اختبار",
                    expiresAtEpochMs = System.currentTimeMillis() - 10_000
                )
            )
            assertEquals(1, store2.expireStale(System.currentTimeMillis()))
            assertEquals(ApprovalResolution.EXPIRED, store2.find(expired.approvalId)!!.resolution)

            // Pending-for lookup finds only PENDING requests.
            store2.create(
                HumanApprovalRequest(
                    approvalId = "appr_2",
                    executionId = "exec_1",
                    toolName = "delete_file",
                    riskLevel = "HIGH",
                    prompt = "جديد",
                    justification = "اختبار"
                )
            )
            val pending = store2.findPendingFor("exec_1", "delete_file")
            assertNotNull(pending)
            assertEquals("appr_2", pending!!.approvalId)
            assertNull(store2.findPendingFor("exec_999", "delete_file"))
        } finally {
            context.deleteDatabase("agent_orchestrator_platform.db")
        }
    }
}
