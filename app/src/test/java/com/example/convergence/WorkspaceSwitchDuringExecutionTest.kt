package com.example.convergence

import com.example.application.observability.TelemetryService
import com.example.domain.core.agent.AgentId
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.EnvironmentObservation
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.execution.ExecutionScope
import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.HealthProbe
import com.example.domain.core.observability.MetricSample
import com.example.domain.core.observability.MetricType
import com.example.domain.ports.observability.TelemetryPort
import com.example.domain.core.Outcome
import com.example.domain.core.storage.StorageFailure
import com.example.domain.core.storage.WorkspaceFileEntry
import com.example.domain.core.tools.ToolInput
import com.example.domain.ports.storage.WorkspaceStoragePort
import com.example.infrastructure.tools.FileSystemTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * P0 CONVERGENCE — WorkspaceSwitchDuringExecutionTest
 * ============================================================================
 *
 * Audit P0-هـ: "اختبار Workspace Switch أثناء Execution — بحيث تثبت أن كل
 * Telemetry/Evidence/I/O تبقى مرتبطة بالـ workspace المثبّت للعملية."
 *
 * Proves TWO pinned-scope invariants:
 *   1. Telemetry attribution: events of an execution whose workspace was
 *      PINNED at start keep attributing to THAT workspace, even when the
 *      user switches the active workspace mid-run (the provider returns a
 *      different workspace for later emissions).
 *   2. File I/O targeting: agent file operations inside an execution resolve
 *      the sandbox project from the PINNED ExecutionScope — a mid-run
 *      workspace switch cannot re-target file writes to another workspace's
 *      sandbox (previously the tool re-asked the ACTIVE workspace per call).
 *
 * Determinism: the telemetry service is constructed with an UNCONFINED
 * scope, so event handling happens synchronously during emission — no
 * sleeps, no races.
 */
class WorkspaceSwitchDuringExecutionTest {

    // ------------------------------------------------------------------
    // Fake telemetry port capturing every recorded sample's dimensions.
    // ------------------------------------------------------------------
    private class CapturingTelemetryPort : TelemetryPort {
        val samples = mutableListOf<MetricSample>()
        override suspend fun record(sample: MetricSample) { samples.add(sample) }
        override suspend fun recordBatch(list: List<MetricSample>) { samples.addAll(list) }
        override suspend fun recordAudit(event: AuditEvent): Long = 1L
        override suspend fun recordHealthProbe(probe: HealthProbe) {}
        override suspend fun recordTraceNode(
            node: com.example.domain.core.observability.ExecutionTraceNode
        ) {}
        override fun snapshots(): Flow<List<com.example.domain.core.observability.MetricSnapshot>> = flowOf(emptyList())
        override fun dimensionSummaries(): Flow<List<com.example.domain.core.observability.DimensionSummary>> = flowOf(emptyList())
        override fun auditEvents(limit: Int): Flow<List<AuditEvent>> = flowOf(emptyList())
        override fun traceForExecution(executionId: String): Flow<List<com.example.domain.core.observability.ExecutionTraceNode>> = flowOf(emptyList())
        override fun recentTraceNodes(limit: Int): Flow<List<com.example.domain.core.observability.ExecutionTraceNode>> = flowOf(emptyList())
        override suspend fun snapshotByType(type: MetricType): List<com.example.domain.core.observability.MetricSnapshot> = emptyList()
    }

    // ------------------------------------------------------------------
    // Fake storage capturing which project id each operation targeted.
    // ------------------------------------------------------------------
    private class CapturingStorage : WorkspaceStoragePort {
        val writtenProjects = mutableListOf<Long>()
        val readProjects = mutableListOf<Long>()
        override suspend fun readFile(projectId: Long, relativePath: String): Outcome<String, StorageFailure> {
            readProjects.add(projectId)
            return Outcome.Success("content")
        }
        override suspend fun writeFile(projectId: Long, relativePath: String, content: String): Outcome<Unit, StorageFailure> {
            writtenProjects.add(projectId)
            return Outcome.Success(Unit)
        }
        override suspend fun listFiles(projectId: Long, subDirectory: String?): Outcome<List<WorkspaceFileEntry>, StorageFailure> =
            Outcome.Success(emptyList())
        override suspend fun deleteFile(projectId: Long, relativePath: String): Outcome<Unit, StorageFailure> =
            Outcome.Success(Unit)
        override suspend fun fileExists(projectId: Long, relativePath: String): Boolean = true
    }

    @Test
    fun `telemetry stays bound to the execution PINNED workspace after a mid-run switch`() = runBlocking {
        val port = CapturingTelemetryPort()
        var activeWorkspace = "ws_alpha"
        val service = TelemetryService(
            port,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        ).also { it.workspaceIdProvider = { activeWorkspace } }

        // The execution's events, with the MID-RUN SWITCH between emissions.
        val events = flow {
            // Execution starts while ws_alpha is active — its workspace is PINNED.
            emit(ExecutionEvent.Started(
                executionId = "exec_pinned_1",
                agentId = AgentId("agent_a"),
                modelId = "model_m",
                workspaceId = "ws_alpha"
            ))
            // MID-RUN: the user switches the active workspace.
            activeWorkspace = "ws_beta"
            // Late events of the SAME execution must still attribute to ws_alpha.
            emit(ExecutionEvent.ActionCompleted(
                executionId = "exec_pinned_1",
                action = DecisionAction(DecisionActionType.EXECUTE_TOOL, targetId = "res_file_tool"),
                outputSummary = "done",
                observation = EnvironmentObservation(
                    action = DecisionAction(DecisionActionType.EXECUTE_TOOL, targetId = "res_file_tool"),
                    isSuccess = true,
                    actualLatencyMs = 120L,
                    stepIndex = 0
                )
            ))
            emit(ExecutionEvent.Completed(
                executionId = "exec_pinned_1",
                finalText = "تم",
                totalDurationMs = 500L
            ))
        }
        service.subscribeToExecutionEvents(events)

        val pinnedAttribution = port.samples.filter { it.dimensions.executionId == "exec_pinned_1" }
        assertTrue("Events must have been recorded for the execution", pinnedAttribution.isNotEmpty())
        assertEquals(
            "Every metric of the execution must stay attributed to the PINNED workspace (ws_alpha) — not the newly-active ws_beta",
            setOf("ws_alpha"),
            pinnedAttribution.map { it.dimensions.workspaceId }.toSet()
        )
    }

    @Test
    fun `file operations inside an execution target the PINNED project after a mid-run switch`() = runBlocking {
        val storage = CapturingStorage()
        var activeProject = 7L // ws_alpha's project
        val tool = FileSystemTool(
            storagePort = storage,
            projectIdProvider = { activeProject }
        )

        // The execution pins ws_alpha + its project 7 at launch.
        withContext(ExecutionScope(executionId = "exec_pin_tool", workspaceId = "ws_alpha", projectId = 7L)) {
            // FIRST write — inside the execution, before any switch.
            tool.execute(ToolInput(toolName = "workspace_file_tool", arguments = mapOf(
                "action" to "write", "path" to "a.txt", "content" to "1"
            )))

            // MID-RUN: the user switches to ws_beta whose project is 9.
            activeProject = 9L

            // SECOND write — same execution: must STILL target the pinned
            // project 7, NOT the newly-active project 9.
            tool.execute(ToolInput(toolName = "workspace_file_tool", arguments = mapOf(
                "action" to "write", "path" to "b.txt", "content" to "2"
            )))
        }

        assertEquals(
            "Both writes must target the PINNED project 7 (a mid-run workspace switch must NOT re-target execution I/O)",
            listOf(7L, 7L),
            storage.writtenProjects
        )
        assertTrue(
            "The other workspace's project must never be touched",
            storage.writtenProjects.none { it == 9L }
        )
    }

    @Test
    fun `user-driven paths outside executions still resolve the ACTIVE workspace project`() = runBlocking {
        val storage = CapturingStorage()
        var activeProject = 11L
        val tool = FileSystemTool(storagePort = storage, projectIdProvider = { activeProject })

        // No ExecutionScope in the context → provider path (user-driven).
        withContext(Dispatchers.Default) {
            tool.execute(ToolInput(toolName = "workspace_file_tool", arguments = mapOf(
                "action" to "write", "path" to "user.txt", "content" to "x"
            )))
        }
        assertEquals(listOf(11L), storage.writtenProjects)
    }
}
