package com.example.convergence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.AuditSeverity
import com.example.domain.core.observability.ExecutionTraceNode
import com.example.infrastructure.observability.RoomTelemetryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * GAP-04 (Design Closure 2026) — FeedWorkspaceIsolationTest
 * ============================================================================
 *
 * The Unified Activity Feed previously read `ExecutionTraceDao.recent` /
 * `AuditTrailDao.recent` with NO workspace predicate, so traces and audit
 * rows from OTHER workspaces (same user/device) leaked into the feed.
 * `ExecutionTraceNodeEntity.toDomain()` additionally DROPPED workspaceId,
 * so even a scoped query would have returned unattributed rows.
 *
 * This test pins, against a REAL in-memory Room database:
 *   1. `recentTraceNodes(workspaceId, n)` / `auditEvents(workspaceId, n)`
 *      return ONLY the requested workspace's rows (SQL-level scoping);
 *   2. switching the workspace re-targets the feed (both workspaces have
 *      live, complete views — no data loss, only isolation);
 *   3. `workspaceId == null` (no active workspace) → honest EMPTY feed;
 *   4. `toDomain()` carries workspaceId through (attribution survives).
 */
@RunWith(RobolectricTestRunner::class)
class FeedWorkspaceIsolationTest {

    private lateinit var db: com.example.infrastructure.persistence.AppDatabase
    private lateinit var repository: RoomTelemetryRepository

    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomTelemetryRepository(
            metricEventDao = db.metricEventDao(),
            auditTrailDao = db.auditTrailDao(),
            executionTraceDao = db.executionTraceDao(),
            executionLogDao = db.executionLogDao(),
            writeScope = writeScope
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed() {
        // Two workspaces, two executions each, interleaved writes.
        for (ws in listOf("ws-alpha", "ws-beta")) {
            for (i in 0 until 3) {
                repository.recordTraceNode(
                    ExecutionTraceNode(
                        executionId = "exec-$ws-$i",
                        stepIndex = 0,
                        actionType = "EXECUTE_TOOL",
                        targetResourceId = "res:tool:x",
                        agentId = "agent-1",
                        startedAtEpochMs = 100L + i,
                        completedAtEpochMs = 110L + i,
                        durationMs = 10L,
                        outcome = "SUCCESS",
                        summary = "step $i in $ws",
                        observationSummary = "obs",
                        workspaceId = ws
                    )
                )
                repository.recordAudit(
                    AuditEvent(
                        id = "ae-$ws-$i",
                        severity = AuditSeverity.INFO,
                        actor = "agent-1",
                        action = "TOOL_EXECUTED",
                        resourceType = "TOOL",
                        resourceId = "res:tool:x",
                        decision = "ALLOWED",
                        reason = "policy ok",
                        workspaceId = ws,
                        occurredAtEpochMs = 200L + i
                    )
                )
            }
        }
    }

    @Test
    fun `feed shows only the requested workspace traces`() = runBlocking {
        seed()
        val alpha = repository.recentTraceNodes("ws-alpha", 50).first()
        val beta = repository.recentTraceNodes("ws-beta", 50).first()
        assertEquals(3, alpha.size)
        assertEquals(3, beta.size)
        assertTrue("GAP-04: no ws-beta rows may appear for ws-alpha", alpha.all { it.workspaceId == "ws-alpha" })
        assertTrue("GAP-04: no ws-alpha rows may appear for ws-beta", beta.all { it.workspaceId == "ws-beta" })
        assertTrue(alpha.none { it.summary?.contains("ws-beta") == true })
        assertTrue(beta.none { it.summary?.contains("ws-alpha") == true })
    }

    @Test
    fun `feed shows only the requested workspace audit events`() = runBlocking {
        seed()
        val alpha = repository.auditEvents("ws-alpha", 100).first()
        val beta = repository.auditEvents("ws-beta", 100).first()
        assertEquals(3, alpha.size)
        assertEquals(3, beta.size)
        assertTrue("GAP-04: audit feed for ws-alpha must not contain ws-beta rows", alpha.all { it.workspaceId == "ws-alpha" })
        assertTrue("GAP-04: audit feed for ws-beta must not contain ws-alpha rows", beta.all { it.workspaceId == "ws-beta" })
    }

    @Test
    fun `no active workspace yields an honest empty feed`() = runBlocking {
        seed()
        assertEquals(0, repository.recentTraceNodes(null, 50).first().size)
        assertEquals(0, repository.auditEvents(null, 100).first().size)
    }

    @Test
    fun `toDomain carries workspaceId attribution`() = runBlocking {
        seed()
        val alpha = repository.recentTraceNodes("ws-alpha", 50).first()
        assertTrue(alpha.isNotEmpty())
        for (node in alpha) {
            assertNotNull("GAP-04: trace nodes must carry workspaceId through toDomain", node.workspaceId)
            assertEquals("ws-alpha", node.workspaceId)
        }
    }

    @Test
    fun `unscoped legacy reads still exist for non-feed consumers`() = runBlocking {
        // The unscoped variants remain available (e.g. diagnostics); the
        // feed simply no longer uses them. 6 rows total across both spaces.
        seed()
        assertEquals(6, repository.recentTraceNodes(50).first().size)
        assertEquals(6, repository.auditEvents(100).first().size)
    }
}
