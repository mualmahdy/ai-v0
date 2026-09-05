package com.example.application.observability

import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.AuditSeverity
import com.example.domain.core.observability.HealthProbe
import com.example.domain.core.observability.MetricDimensions
import com.example.domain.core.observability.MetricSample
import com.example.domain.core.observability.MetricType
import com.example.domain.ports.observability.TelemetryPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 — TelemetryService unit tests.
 *
 * Closes the test-coverage aspect of P5-P0-01 (Observability): proves the
 * service correctly translates domain operations into MetricSamples and
 * persists AuditEvents via the port.
 */
class TelemetryServiceTest {

    private val fakePort = InMemoryTelemetryPort()
    private val service = TelemetryService(fakePort, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined))

    @Test
    fun `incrementCounter records a COUNTER sample with the action in dimensions`() = kotlinx.coroutines.runBlocking {
        service.incrementCounter(
            action = "TOOL_CALLED",
            dimensions = MetricDimensions(toolName = "file_system_tool")
        )
        assertEquals(1, fakePort.counterSamples.size)
        val sample = fakePort.counterSamples.first()
        assertEquals(MetricType.COUNTER, sample.type)
        assertEquals("TOOL_CALLED", sample.dimensions.actionType)
        assertEquals("file_system_tool", sample.dimensions.toolName)
    }

    @Test
    fun `recordLatency stores a LATENCY_HISTOGRAM sample`() = kotlinx.coroutines.runBlocking {
        service.recordLatency(MetricDimensions(providerId = "gemini"), 234L)
        assertEquals(1, fakePort.latencySamples.size)
        assertEquals(234L, fakePort.latencySamples.first().value)
    }

    @Test
    fun `recordTokenUsage writes both PROMPT and COMPLETION samples`() = kotlinx.coroutines.runBlocking {
        service.recordTokenUsage(
            dimensions = MetricDimensions(executionId = "exec_1", providerId = "gemini"),
            promptTokens = 100,
            completionTokens = 50,
            providerId = "gemini"
        )
        assertEquals(2, fakePort.tokenSamples.size)
        val prompt = fakePort.tokenSamples.first { it.dimensions.actionType == "PROMPT" }
        val completion = fakePort.tokenSamples.first { it.dimensions.actionType == "COMPLETION" }
        assertEquals(100L, prompt.value)
        assertEquals(50L, completion.value)
    }

    @Test
    fun `recordAudit delegates to port and returns row id`() = kotlinx.coroutines.runBlocking {
        val rowId = service.recordAudit(
            severity = AuditSeverity.WARN,
            actor = "agent_general",
            action = "TOOL_DENIED",
            resourceType = "TOOL",
            resourceId = "file_system_tool",
            decision = "DENY",
            reason = "permission missing"
        )
        assertTrue(rowId > 0L)
        assertEquals(1, fakePort.auditEvents.size)
        assertEquals("TOOL_DENIED", fakePort.auditEvents.first().action)
    }

    @Test
    fun `recordHealthProbe delegates to port`() = kotlinx.coroutines.runBlocking {
        service.recordHealthProbe(
            HealthProbe(
                resourceId = "res_gemini",
                resourceType = "LLM",
                isHealthy = true,
                latencyMs = 120L
            )
        )
        assertEquals(1, fakePort.healthProbes.size)
        assertTrue(fakePort.healthProbes.first().isHealthy)
    }

    @Test
    fun `recordTraceNode stores node with computed durationMs`() = kotlinx.coroutines.runBlocking {
        service.recordTraceNode(
            executionId = "exec_1",
            stepIndex = 0,
            actionType = "TOOL_EXECUTION",
            agentId = "agent_general",
            targetResourceId = "file_system_tool",
            startedAtEpochMs = 1000L,
            completedAtEpochMs = 1500L,
            outcome = "SUCCESS",
            summary = "file read"
        )
        assertEquals(1, fakePort.traceNodes.size)
        val node = fakePort.traceNodes.first()
        assertEquals(500L, node.durationMs)
        assertEquals("SUCCESS", node.outcome)
    }

    /**
     * In-memory TelemetryPort for testing — captures all writes without
     * requiring Room. Matches the project convention of hand-rolled fakes
     * (no MockK in this codebase).
     */
    private class InMemoryTelemetryPort : TelemetryPort {
        val counterSamples = mutableListOf<MetricSample>()
        val latencySamples = mutableListOf<MetricSample>()
        val tokenSamples = mutableListOf<MetricSample>()
        val auditEvents = mutableListOf<AuditEvent>()
        val healthProbes = mutableListOf<HealthProbe>()
        val traceNodes = mutableListOf<com.example.domain.core.observability.ExecutionTraceNode>()
        private var rowIdCounter = 1L

        override suspend fun record(sample: MetricSample) {
            when (sample.type) {
                MetricType.COUNTER -> counterSamples.add(sample)
                MetricType.LATENCY_HISTOGRAM -> latencySamples.add(sample)
                MetricType.TOKEN_USAGE -> tokenSamples.add(sample)
                else -> { /* other types: ignore for test simplicity */ }
            }
        }

        override suspend fun recordBatch(samples: List<MetricSample>) {
            samples.forEach { record(it) }
        }

        override suspend fun recordAudit(event: AuditEvent): Long {
            auditEvents.add(event)
            return rowIdCounter++
        }

        override suspend fun recordHealthProbe(probe: HealthProbe) {
            healthProbes.add(probe)
        }

        override suspend fun recordTraceNode(node: com.example.domain.core.observability.ExecutionTraceNode) {
            traceNodes.add(node)
        }

        override fun snapshots(): Flow<List<com.example.domain.core.observability.MetricSnapshot>> = flowOf(emptyList())
        override fun dimensionSummaries(): Flow<List<com.example.domain.core.observability.DimensionSummary>> = flowOf(emptyList())
        override fun auditEvents(limit: Int): Flow<List<AuditEvent>> = flowOf(auditEvents.toList())
        override fun traceForExecution(executionId: String): Flow<List<com.example.domain.core.observability.ExecutionTraceNode>> = flowOf(traceNodes.toList())
        override suspend fun snapshotByType(type: MetricType): List<com.example.domain.core.observability.MetricSnapshot> = emptyList()
    }
}
