package com.example.testing

import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.DimensionSummary
import com.example.domain.core.observability.ExecutionTraceNode
import com.example.domain.core.observability.MetricSample
import com.example.domain.core.observability.MetricSnapshot
import com.example.domain.core.observability.MetricType
import com.example.domain.ports.observability.TelemetryPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stage-4 test fake: captures audit events (the budget-forecast early-warning
 * channel) and answers empty for every stream. Test-only — production uses
 * RoomTelemetryRepository.
 */
class FakeTelemetryPort : TelemetryPort {
    val auditEvents: MutableList<AuditEvent> = mutableListOf()
    val samples: MutableList<MetricSample> = mutableListOf()

    override suspend fun record(sample: MetricSample) {
        samples.add(sample)
    }

    override suspend fun recordBatch(samples: List<MetricSample>) {
        this.samples.addAll(samples)
    }

    override suspend fun recordAudit(event: AuditEvent): Long {
        auditEvents.add(event)
        return auditEvents.size.toLong()
    }

    override suspend fun recordTraceNode(node: ExecutionTraceNode) = Unit

    override fun snapshots(): Flow<List<MetricSnapshot>> = emptyFlow()

    override fun dimensionSummaries(): Flow<List<DimensionSummary>> = emptyFlow()

    override fun auditEvents(limit: Int): Flow<List<AuditEvent>> = emptyFlow()

    override fun traceForExecution(executionId: String): Flow<List<ExecutionTraceNode>> = emptyFlow()

    override fun recentTraceNodes(limit: Int): Flow<List<ExecutionTraceNode>> = emptyFlow()

    override suspend fun snapshotByType(type: MetricType): List<MetricSnapshot> = emptyList()
}
