package com.example.convergence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.audit.AuditTrailService
import com.example.domain.core.audit.AuditActorType
import com.example.domain.core.audit.AuditActions
import com.example.domain.core.audit.AuditResult
import com.example.domain.core.context.ResourceScope
import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.AuditSeverity
import com.example.infrastructure.observability.RoomTelemetryRepository
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.AuditEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * GAP-24 (Design Closure 2026, ADR-8 option ج) — AuditTruthUnificationTest
 * ============================================================================
 *
 * Two audit subsystems previously coexisted with NO shared truth:
 *   - `audit_trail` (runtime governance decisions) fed the live activity
 *     feed;
 *   - `audit_events` (REPAIR ORDER §30 lifecycle/portability actions, the
 *     RICHER who/what/when/scope/policy/result model) had SIX production
 *     writers and ZERO readers.
 *
 * The unified reader (RoomTelemetryRepository.auditEvents, both overloads)
 * now MERGES both tables into the feed. This test pins, against a REAL
 * in-memory Room database:
 *   1. the merged, workspace-scoped feed returns rows from BOTH sources,
 *      time-sorted descending;
 *   2. §30 rows map into the feed's severity/decision shape with
 *      provenance (attributes["source"] = "audit_events");
 *   3. workspace isolation holds on the MERGED reader (GAP-04 invariant
 *      preserved — merging never re-opens the leak);
 *   4. `workspaceId == null` stays the honest EMPTY feed;
 *   5. the unscoped reader merges too;
 *   6. recordAudit write failures are COUNTED and surfaced through
 *      measurementHealth() (previously a swallowed -1L — the "mute
 *      counter" pattern);
 *   7. a clean repository reports a healthy measurement layer.
 */
@RunWith(RobolectricTestRunner::class)
class AuditTruthUnificationTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RoomTelemetryRepository
    private lateinit var auditTrailService: AuditTrailService

    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomTelemetryRepository(
            metricEventDao = db.metricEventDao(),
            auditTrailDao = db.auditTrailDao(),
            executionTraceDao = db.executionTraceDao(),
            executionLogDao = db.executionLogDao(),
            auditEventDao = db.auditEventDao(),
            writeScope = writeScope
        )
        auditTrailService = AuditTrailService(database = db, scope = writeScope)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Direct §30 row seeding with DETERMINISTIC timestamps (ordering proof). */
    private suspend fun seedAuditEventRow(
        id: Long,
        workspaceId: String,
        result: AuditResult,
        occurredAtEpochMs: Long,
        action: String = AuditActions.PROJECT_TRASHED
    ) {
        db.auditEventDao().insert(
            AuditEventEntity(
                id = id,
                actorType = AuditActorType.USER.name,
                actorId = "user",
                action = action,
                resourceType = "PROJECT",
                resourceId = "42",
                sourceScopeType = ResourceScope.Workspace(workspaceId).type.name,
                sourceScopeId = ResourceScope.Workspace(workspaceId).describe(),
                targetScopeType = null,
                targetScopeId = null,
                policy = "SUPERVISED",
                result = result.name,
                reason = "test reason",
                workspaceId = workspaceId,
                projectId = 42L,
                occurredAtEpochMs = occurredAtEpochMs,
                metadataJson = "{}"
            )
        )
    }

    @Test
    fun `unified feed merges both audit tables scoped by workspace, time-sorted`() = runBlocking {
        // audit_trail row (older) + audit_events row (newer) for ws-alpha.
        repository.recordAudit(
            AuditEvent(
                id = "trail-1",
                severity = AuditSeverity.INFO,
                actor = "agent-1",
                action = "TOOL_EXECUTED",
                resourceType = "TOOL",
                resourceId = "res:tool:x",
                decision = "ALLOWED",
                reason = "policy ok",
                workspaceId = "ws-alpha",
                occurredAtEpochMs = 100L
            )
        )
        seedAuditEventRow(id = 1L, workspaceId = "ws-alpha", result = AuditResult.DENIED, occurredAtEpochMs = 200L)

        val feed = repository.auditEvents("ws-alpha", 100).first()

        assertEquals("both audit sources must appear in the merged feed", 2, feed.size)
        assertEquals("newest first (time-sorted desc)", 200L, feed.first().occurredAtEpochMs)
        assertEquals("oldest last", 100L, feed.last().occurredAtEpochMs)
        assertTrue(
            "one row must come from audit_events (id prefix) and one from audit_trail",
            feed.any { it.id.startsWith("audit_events:") } && feed.any { it.id == "trail-1" }
        )
    }

    @Test
    fun `audit_events rows map into the feed shape with provenance`() = runBlocking {
        seedAuditEventRow(id = 7L, workspaceId = "ws-alpha", result = AuditResult.DENIED, occurredAtEpochMs = 500L)

        val feed = repository.auditEvents("ws-alpha", 100).first()
        assertEquals(1, feed.size)
        val row = feed.first()

        assertEquals("audit_events:7", row.id)
        assertEquals("DENIED must map to WARN severity", AuditSeverity.WARN, row.severity)
        assertEquals("decision mirrors the §30 result", "DENIED", row.decision)
        assertEquals("actor carries the typed attribution", "USER:user", row.actor)
        assertEquals(AuditActions.PROJECT_TRASHED, row.action)
        assertEquals("PROJECT", row.resourceType)
        assertEquals("provenance must survive the mapping", "audit_events", row.attributes["source"])
        assertEquals("policy rides along as an attribute", "SUPERVISED", row.attributes["policy"])
        assertEquals("ws-alpha", row.workspaceId)
    }

    @Test
    fun `unified feed keeps workspace isolation`() = runBlocking {
        seedAuditEventRow(id = 1L, workspaceId = "ws-alpha", result = AuditResult.SUCCESS, occurredAtEpochMs = 10L)
        seedAuditEventRow(id = 2L, workspaceId = "ws-beta", result = AuditResult.SUCCESS, occurredAtEpochMs = 20L)
        repository.recordAudit(
            AuditEvent(
                id = "trail-beta",
                severity = AuditSeverity.INFO,
                actor = "agent-1",
                action = "TOOL_EXECUTED",
                resourceType = "TOOL",
                resourceId = "res:tool:x",
                decision = "ALLOWED",
                reason = "policy ok",
                workspaceId = "ws-beta",
                occurredAtEpochMs = 30L
            )
        )

        val alpha = repository.auditEvents("ws-alpha", 100).first()
        val beta = repository.auditEvents("ws-beta", 100).first()

        assertEquals("ws-alpha sees ONLY its own merged rows", 1, alpha.size)
        assertEquals("ws-beta sees BOTH sources' rows", 2, beta.size)
        assertTrue(alpha.all { it.workspaceId == "ws-alpha" })
        assertTrue(beta.all { it.workspaceId == "ws-beta" })
    }

    @Test
    fun `null workspace stays the honest EMPTY merged feed`() = runBlocking {
        seedAuditEventRow(id = 1L, workspaceId = "ws-alpha", result = AuditResult.SUCCESS, occurredAtEpochMs = 10L)
        repository.recordAudit(
            AuditEvent(
                id = "trail-1",
                severity = AuditSeverity.INFO,
                actor = "agent-1",
                action = "TOOL_EXECUTED",
                resourceType = "TOOL",
                resourceId = "res:tool:x",
                decision = "ALLOWED",
                reason = "policy ok",
                workspaceId = "ws-alpha",
                occurredAtEpochMs = 20L
            )
        )
        assertTrue(repository.auditEvents(null as String?, 100).first().isEmpty())
    }

    @Test
    fun `unscoped unified reader merges both tables too`() = runBlocking {
        repository.recordAudit(
            AuditEvent(
                id = "trail-1",
                severity = AuditSeverity.WARN,
                actor = "economic_governance",
                action = "budget_gate_denied",
                resourceType = "BUDGET",
                resourceId = "ws-alpha",
                decision = "DENIED",
                reason = "over hard limit",
                workspaceId = "ws-alpha",
                occurredAtEpochMs = 100L
            )
        )
        seedAuditEventRow(id = 1L, workspaceId = "ws-alpha", result = AuditResult.DEGRADED, occurredAtEpochMs = 200L)

        val feed = repository.auditEvents(100).first()
        assertEquals(2, feed.size)
        assertTrue(feed.any { it.id == "trail-1" })
        assertTrue(feed.any { it.id == "audit_events:1" })
    }

    @Test
    fun `AuditTrailService writer reaches the unified reader (write path round-trip)`() = runBlocking {
        // The §30 typed writer (the six production call sites' path) must land
        // rows the unified reader actually returns.
        auditTrailService.record(
            actorType = AuditActorType.USER,
            actorId = "user",
            action = AuditActions.PROJECT_ARCHIVED,
            resourceType = "PROJECT",
            resourceId = "42",
            sourceScope = ResourceScope.Workspace("ws-alpha"),
            targetScope = null,
            policy = "SUPERVISED",
            result = AuditResult.SUCCESS,
            reason = "user action"
        )
        val feed = repository.auditEvents("ws-alpha", 100).first()
        assertEquals(1, feed.size)
        assertEquals(AuditActions.PROJECT_ARCHIVED, feed.first().action)
        assertEquals(AuditSeverity.INFO, feed.first().severity)
    }

    @Test
    fun `recordAudit write failure is COUNTED and surfaced - no more mute counter`() = runBlocking {
        // Healthy before the outage.
        assertTrue("clean repository must report healthy", repository.measurementHealth().isHealthy)

        // Force the outage: closed DB → insert throws → -1L return + counters.
        db.close()
        val returned = repository.recordAudit(
            AuditEvent(
                id = "trail-fail",
                severity = AuditSeverity.INFO,
                actor = "agent-1",
                action = "TOOL_EXECUTED",
                resourceType = "TOOL",
                resourceId = "res:tool:x",
                decision = "ALLOWED",
                reason = "policy ok",
                workspaceId = "ws-alpha",
                occurredAtEpochMs = 1L
            )
        )
        assertEquals("the -1L honest-failure return contract is preserved", -1L, returned)

        val health = repository.measurementHealth()
        assertFalse("an audit write outage must NOT be reported as healthy", health.isHealthy)
        assertTrue(
            "failure count must be at least 1, was ${health.auditPersistenceFailures}",
            health.auditPersistenceFailures >= 1
        )
        assertNotNull("last audit persistence error must be recorded", health.lastAuditPersistenceError)
        assertTrue(
            "error must carry the machine-readable prefix",
            health.lastAuditPersistenceError!!.startsWith("AUDIT_PERSIST_FAILED")
        )
    }
}
