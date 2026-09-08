package com.example.application.workflow

import com.example.domain.core.workflow.WorkflowExecutionReport
import com.example.domain.core.workflow.WorkflowFailure
import com.example.domain.core.workflow.WorkflowId
import com.example.domain.core.workflow.WorkflowPlan
import com.example.domain.core.workflow.StepNode
import com.example.domain.core.workflow.StepStatus
import com.example.domain.core.workflow.ExecutionMode
import com.example.domain.core.task.AcceptanceCriterion
import com.example.domain.core.task.TaskCapabilityRequirements
import com.example.domain.core.task.TaskId
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.capability.Locality
import com.example.domain.core.capability.SideEffectClassification
import com.example.infrastructure.persistence.dao.WorkflowExecutionDao
import com.example.infrastructure.persistence.dao.WorkflowStepStateDao
import com.example.infrastructure.persistence.entities.WorkflowExecutionEntity
import com.example.infrastructure.persistence.entities.WorkflowStepStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * ============================================================================
 * WorkflowPersistenceService — Phase 5 Workflow Intelligence (P1)
 * ============================================================================
 *
 * Durable substrate for the workflow engine:
 *
 *   1. Persisting `WorkflowPlan` + per-step execution state to Room
 *      (`workflow_executions` + `workflow_step_states` tables).
 *
 *   2. Providing `resumable()` so the orchestrator can resume workflows
 *      after process death — previously impossible because state was
 *      in-memory only.
 *
 *   3. Providing durable checkpoints — `checkpoint()` writes the current
 *      step index so a resumed workflow skips already-completed steps.
 *
 *   4. Supporting compensation actions — `markStepCompensated()` records
 *      that a step's effects were rolled back.
 *
 *   5. Tracking cancellation reasons — `cancel()` records why the
 *      workflow was cancelled (user, timeout, security guard, etc).
 *
 * ============================================================================
 * P0 CONVERGENCE FIX (audit step 12 §5): FULL PLAN DURABILITY
 * ============================================================================
 *
 * DEFECT: `serializePlan()` previously persisted only
 * (step.id, step.description, step.agentRole) and dropped goal, taskId,
 * requirements, expectedOutputs, evidenceRequirements, acceptanceCriteria,
 * dependencies and executionMode; `deserializePlan()` then returned a plan
 * with `steps = emptyList()`. The persisted workflow was therefore NOT a
 * durable workflow artifact: after process death the plan's structure was
 * gone, and a resumed workflow could not rebuild the DAG it was executing.
 *
 * FIX: the plan is now round-tripped LOSSLESSLY — every field of
 * [WorkflowPlan] and [StepNode] (including the full
 * [TaskCapabilityRequirements] tree and [AcceptanceCriterion] entries) is
 * serialized and reconstructed, so a killed workflow can be restored and
 * resumed from the SAME plan without rebuilding it from task definitions.
 * Round-trip fidelity is pinned by `WorkflowPlanDurabilityTest`.
 */
class WorkflowPersistenceService(
    private val workflowExecutionDao: WorkflowExecutionDao,
    private val workflowStepStateDao: WorkflowStepStateDao
) {

    /**
     * Persist the initial state of a workflow execution. Called by
     * `WorkflowEngine.executePlan` at the very start.
     */
    suspend fun start(workflowId: WorkflowId, workspaceId: String, plan: WorkflowPlan): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        workflowExecutionDao.upsert(
            WorkflowExecutionEntity(
                workflowId = workflowId.value,
                workspaceId = workspaceId,
                planJson = serializePlan(plan),
                lifecycleState = "RUNNING",
                currentStepIndex = 0,
                totalSteps = plan.steps.size,
                startedAtEpochMs = now,
                lastCheckpointAtEpochMs = now,
                completedAtEpochMs = null,
                failureReason = null,
                cancellationReason = null
            )
        )
        // Persist initial step states.
        val stepStates = plan.steps.mapIndexed { idx, step ->
            WorkflowStepStateEntity(
                id = 0L,
                workflowId = workflowId.value,
                stepId = step.id,
                stepIndex = idx,
                status = StepStatus.PENDING.name,
                outputSummary = null,
                durationMs = null,
                startedAtEpochMs = null,
                completedAtEpochMs = null,
                attemptCount = 0,
                lastErrorMessage = null
            )
        }
        workflowStepStateDao.upsertAll(stepStates)
    }

    /**
     * Write a durable checkpoint. Called by `WorkflowEngine` after each
     * step completes.
     */
    suspend fun checkpoint(workflowId: WorkflowId, currentStepIndex: Int): Unit = withContext(Dispatchers.IO) {
        workflowExecutionDao.checkpoint(workflowId.value, "RUNNING", currentStepIndex, System.currentTimeMillis())
    }

    /**
     * Mark a step as completed/failed/skipped/compensated.
     */
    suspend fun markStepStatus(
        workflowId: WorkflowId,
        stepId: String,
        status: StepStatus,
        outputSummary: String?,
        durationMs: Long?,
        errorMessage: String? = null
    ): Unit = withContext(Dispatchers.IO) {
        workflowStepStateDao.updateStepStatus(
            workflowId = workflowId.value,
            stepId = stepId,
            status = status.name,
            summary = outputSummary,
            duration = durationMs,
            now = System.currentTimeMillis()
        )
    }

    /**
     * Mark a step as compensated — its effects were rolled back by a
     * compensation action.
     */
    suspend fun markStepCompensated(workflowId: WorkflowId, stepId: String, reason: String): Unit = withContext(Dispatchers.IO) {
        workflowStepStateDao.updateStepStatus(
            workflowId = workflowId.value,
            stepId = stepId,
            status = "COMPENSATED",
            summary = "تعويض: $reason",
            duration = null,
            now = System.currentTimeMillis()
        )
    }

    /**
     * Mark the workflow as completed (success or degraded).
     *
     * Gap-closure P1-06: previously both branches wrote "COMPLETED" (the
     * isDegraded flag was computed then discarded) — the durable row could
     * not distinguish a clean run from a degraded one. The state strings are
     * free-form TEXT in the entity; DEGRADED is a terminal state like
     * COMPLETED and is excluded from `resumable()` by design.
     */
    suspend fun complete(workflowId: WorkflowId, isDegraded: Boolean): Unit = withContext(Dispatchers.IO) {
        val state = if (isDegraded) "DEGRADED" else "COMPLETED"
        workflowExecutionDao.terminate(workflowId.value, state, System.currentTimeMillis(), null)
    }

    /**
     * Mark the workflow as failed.
     */
    suspend fun fail(workflowId: WorkflowId, reason: String): Unit = withContext(Dispatchers.IO) {
        workflowExecutionDao.terminate(workflowId.value, "FAILED", System.currentTimeMillis(), reason)
    }

    /**
     * Mark the workflow as cancelled.
     */
    suspend fun cancel(workflowId: WorkflowId, reason: String): Unit = withContext(Dispatchers.IO) {
        workflowExecutionDao.terminate(workflowId.value, "CANCELLED", System.currentTimeMillis(), reason)
    }

    /**
     * List all workflows that can be resumed after process death.
     * Returns workflows in state RUNNING, PAUSED, or COMPENSATING.
     */
    suspend fun resumable(): List<ResumableWorkflow> = withContext(Dispatchers.IO) {
        workflowExecutionDao.resumable().map { entity ->
            val plan = deserializePlan(entity.planJson)
            val steps = workflowStepStateDao.forWorkflow(entity.workflowId)
            ResumableWorkflow(
                workflowId = WorkflowId(entity.workflowId),
                workspaceId = entity.workspaceId,
                plan = plan,
                currentStepIndex = entity.currentStepIndex,
                completedStepIds = steps.filter { it.status == "COMPLETED" }.map { it.stepId }.toSet(),
                failedStepIds = steps.filter { it.status == "FAILED" }.map { it.stepId }.toSet(),
                startedAtEpochMs = entity.startedAtEpochMs
            )
        }
    }

    /**
     * Get a single workflow execution state by id.
     */
    suspend fun byId(workflowId: WorkflowId): WorkflowExecutionState? = withContext(Dispatchers.IO) {
        val entity = workflowExecutionDao.byId(workflowId.value) ?: return@withContext null
        val steps = workflowStepStateDao.forWorkflow(workflowId.value)
        WorkflowExecutionState(
            workflowId = WorkflowId(entity.workflowId),
            workspaceId = entity.workspaceId,
            lifecycleState = entity.lifecycleState,
            currentStepIndex = entity.currentStepIndex,
            totalSteps = entity.totalSteps,
            startedAtEpochMs = entity.startedAtEpochMs,
            completedAtEpochMs = entity.completedAtEpochMs,
            failureReason = entity.failureReason,
            cancellationReason = entity.cancellationReason,
            stepStates = steps.map {
                WorkflowStepState(
                    stepId = it.stepId,
                    stepIndex = it.stepIndex,
                    status = it.status,
                    outputSummary = it.outputSummary,
                    durationMs = it.durationMs,
                    attemptCount = it.attemptCount,
                    lastErrorMessage = it.lastErrorMessage
                )
            }
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // Plan serialization (JSON) — P0 CONVERGENCE: LOSSLESS round-trip.
    // Every field of WorkflowPlan / StepNode survives persistence so the
    // plan is a DURABLE workflow artifact (kill → restore → resume from the
    // same DAG), not a metadata stub.
    // ────────────────────────────────────────────────────────────────────

    internal fun serializePlan(plan: WorkflowPlan): String {
        val obj = JSONObject()
        obj.put("workflowId", plan.id.value)
        obj.put("goal", plan.goal)
        obj.put("executionMode", plan.executionMode.name)
        val arr = JSONArray()
        for (step in plan.steps) {
            arr.put(serializeStep(step))
        }
        obj.put("steps", arr)
        obj.put("schemaVersion", PLAN_SCHEMA_VERSION)
        return obj.toString()
    }

    private fun serializeStep(step: StepNode): JSONObject {
        val s = JSONObject()
        s.put("id", step.id)
        s.put("taskId", step.taskId.value)
        s.put("agentRole", step.agentRole.name)
        s.put("description", step.description)
        s.put("requirements", serializeRequirements(step.requirements))
        s.put("expectedOutputs", toStringArray(step.expectedOutputs))
        s.put("evidenceRequirements", toStringArray(step.evidenceRequirements))
        s.put("acceptanceCriteria", serializeAcceptanceCriteria(step.acceptanceCriteria))
        s.put("dependencies", toStringArray(step.dependencies.toList()))
        // Schema v3 — canonical agent binding + per-step model pin (report:
        // workflow steps must execute through DURABLE registry agents).
        step.assignedAgentId?.let { s.put("assignedAgentId", it) } ?: s.put("assignedAgentId", JSONObject.NULL)
        step.assignedModelId?.let { s.put("assignedModelId", it) } ?: s.put("assignedModelId", JSONObject.NULL)
        s.put("status", step.status.name)
        step.outputSummary?.let { s.put("outputSummary", it) } ?: s.put("outputSummary", JSONObject.NULL)
        s.put("durationMs", step.durationMs)
        return s
    }

    private fun serializeRequirements(req: TaskCapabilityRequirements): JSONObject {
        val r = JSONObject()
        r.put("requiredCapabilities", toStringArray(req.requiredCapabilities.map { it.name }))
        r.put("optionalCapabilities", toStringArray(req.optionalCapabilities.map { it.name }))
        r.put("prohibitedCapabilities", toStringArray(req.prohibitedCapabilities.map { it.name }))
        r.put("requiredResourceTypes", toStringArray(req.requiredResourceTypes))
        r.put("requiredModelCapabilities", toStringArray(req.requiredModelCapabilities))
        r.put("requiredAgentCapabilities", toStringArray(req.requiredAgentCapabilities.map { it.name }))
        r.put("networkRequirement", req.networkRequirement.name)
        r.put("requiresLocalInference", req.requiresLocalInference)
        r.put("securityRequirements", toStringArray(req.securityRequirements))
        r.put("requiredEvidenceKeys", toStringArray(req.requiredEvidenceKeys))
        r.put("expectedOutputType", req.expectedOutputType)
        r.put("acceptanceCriteria", serializeAcceptanceCriteria(req.acceptanceCriteria))
        req.localityConstraint?.let { r.put("localityConstraint", it.name) } ?: r.put("localityConstraint", JSONObject.NULL)
        req.maxAllowedSideEffect?.let { r.put("maxAllowedSideEffect", it.name) } ?: r.put("maxAllowedSideEffect", JSONObject.NULL)
        return r
    }

    private fun serializeAcceptanceCriteria(criteria: List<AcceptanceCriterion>): JSONArray {
        val arr = JSONArray()
        for (c in criteria) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("description", c.description)
            c.requiredKey?.let { o.put("requiredKey", it) } ?: o.put("requiredKey", JSONObject.NULL)
            o.put("validatorType", c.validatorType)
            c.minValue?.let { o.put("minValue", it) } ?: o.put("minValue", JSONObject.NULL)
            c.maxValue?.let { o.put("maxValue", it) } ?: o.put("maxValue", JSONObject.NULL)
            c.regexPattern?.let { o.put("regexPattern", it) } ?: o.put("regexPattern", JSONObject.NULL)
            arr.put(o)
        }
        return arr
    }

    /**
     * Reconstructs the FULL plan from its persisted JSON. Legacy rows
     * written by the pre-convergence serializer (goal/workflowId absent,
     * steps carrying only id/description/agentRole) are restored with
     * everything that WAS persisted — degraded but never silently empty:
     * legacy step records still produce real [StepNode]s (with
     * `requirements` defaults) instead of an empty plan.
     */
    internal fun deserializePlan(json: String): WorkflowPlan {
        val obj = JSONObject(json)
        val steps = mutableListOf<StepNode>()
        val arr = obj.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            steps.add(deserializeStep(s))
        }
        return WorkflowPlan(
            id = WorkflowId(
                obj.optString("workflowId", obj.optString("id", "unknown"))
                    .ifBlank { "unknown" }
            ),
            goal = obj.optString("goal", ""),
            executionMode = runCatching { ExecutionMode.valueOf(obj.optString("executionMode", ExecutionMode.SEQUENTIAL.name)) }
                .getOrDefault(ExecutionMode.SEQUENTIAL),
            steps = steps
        )
    }

    private fun deserializeStep(s: JSONObject): StepNode {
        val requirements = s.optJSONObject("requirements")?.let { deserializeRequirements(it) }
            ?: TaskCapabilityRequirements()
        return StepNode(
            id = s.optString("id", UUID.randomUUID().toString()),
            taskId = TaskId(s.optString("taskId", s.optString("id", ""))),
            agentRole = runCatching { AgentRole.valueOf(s.optString("agentRole", AgentRole.PLANNER.name)) }
                .getOrDefault(AgentRole.PLANNER),
            description = s.optString("description", ""),
            requirements = requirements,
            expectedOutputs = fromStringArray(s.optJSONArray("expectedOutputs")),
            evidenceRequirements = fromStringArray(s.optJSONArray("evidenceRequirements")),
            acceptanceCriteria = s.optJSONArray("acceptanceCriteria")?.let { deserializeAcceptanceCriteria(it) } ?: emptyList(),
            dependencies = fromStringArray(s.optJSONArray("dependencies")).toSet(),
            assignedAgentId = if (s.isNull("assignedAgentId")) null else s.optString("assignedAgentId", null),
            assignedModelId = if (s.isNull("assignedModelId")) null else s.optString("assignedModelId", null),
            status = runCatching { StepStatus.valueOf(s.optString("status", StepStatus.PENDING.name)) }
                .getOrDefault(StepStatus.PENDING),
            outputSummary = if (s.isNull("outputSummary")) null else s.optString("outputSummary", null),
            durationMs = s.optLong("durationMs", 0L)
        )
    }

    private fun deserializeRequirements(r: JSONObject): TaskCapabilityRequirements {
        return TaskCapabilityRequirements(
            requiredCapabilities = fromStringArray(r.optJSONArray("requiredCapabilities")).mapNotNull { runCatching { CapabilityType.valueOf(it) }.getOrNull() }.toSet(),
            optionalCapabilities = fromStringArray(r.optJSONArray("optionalCapabilities")).mapNotNull { runCatching { CapabilityType.valueOf(it) }.getOrNull() }.toSet(),
            prohibitedCapabilities = fromStringArray(r.optJSONArray("prohibitedCapabilities")).mapNotNull { runCatching { CapabilityType.valueOf(it) }.getOrNull() }.toSet(),
            requiredResourceTypes = fromStringArray(r.optJSONArray("requiredResourceTypes")),
            requiredModelCapabilities = fromStringArray(r.optJSONArray("requiredModelCapabilities")),
            requiredAgentCapabilities = fromStringArray(r.optJSONArray("requiredAgentCapabilities")).mapNotNull { runCatching { CapabilityType.valueOf(it) }.getOrNull() }.toSet(),
            networkRequirement = runCatching { NetworkPolicy.valueOf(r.optString("networkRequirement", NetworkPolicy.HYBRID.name)) }
                .getOrDefault(NetworkPolicy.HYBRID),
            requiresLocalInference = r.optBoolean("requiresLocalInference", false),
            securityRequirements = fromStringArray(r.optJSONArray("securityRequirements")),
            requiredEvidenceKeys = fromStringArray(r.optJSONArray("requiredEvidenceKeys")),
            expectedOutputType = r.optString("expectedOutputType", "TEXT"),
            acceptanceCriteria = r.optJSONArray("acceptanceCriteria")?.let { deserializeAcceptanceCriteria(it) } ?: emptyList(),
            localityConstraint = if (r.isNull("localityConstraint")) null
                else runCatching { Locality.valueOf(r.optString("localityConstraint")) }.getOrNull(),
            maxAllowedSideEffect = if (r.isNull("maxAllowedSideEffect")) null
                else runCatching { SideEffectClassification.valueOf(r.optString("maxAllowedSideEffect")) }.getOrNull()
        )
    }

    private fun deserializeAcceptanceCriteria(arr: JSONArray): List<AcceptanceCriterion> {
        val out = mutableListOf<AcceptanceCriterion>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                AcceptanceCriterion(
                    id = o.optString("id", "criterion_$i"),
                    description = o.optString("description", ""),
                    requiredKey = if (o.isNull("requiredKey")) null else o.optString("requiredKey", null),
                    validatorType = o.optString("validatorType", "EXISTS"),
                    minValue = if (o.isNull("minValue")) null else if (o.has("minValue") && !o.isNull("minValue")) o.optDouble("minValue") else null,
                    maxValue = if (o.isNull("maxValue")) null else if (o.has("maxValue") && !o.isNull("maxValue")) o.optDouble("maxValue") else null,
                    regexPattern = if (o.isNull("regexPattern")) null else o.optString("regexPattern", null)
                )
            )
        }
        return out
    }

    private fun toStringArray(list: List<String>): JSONArray {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr
    }

    private fun fromStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            if (!arr.isNull(i)) out.add(arr.optString(i))
        }
        return out
    }

    private companion object {
        /**
         * Bumped when the plan JSON schema changes; future readers can branch on it.
         * v3 adds assignedAgentId / assignedModelId per step (canonical agent
         * binding + per-step model pin).
         */
        const val PLAN_SCHEMA_VERSION = 3
    }
}

data class ResumableWorkflow(
    val workflowId: WorkflowId,
    val workspaceId: String,
    val plan: WorkflowPlan,
    val currentStepIndex: Int,
    val completedStepIds: Set<String>,
    val failedStepIds: Set<String>,
    val startedAtEpochMs: Long
)

data class WorkflowExecutionState(
    val workflowId: WorkflowId,
    val workspaceId: String,
    val lifecycleState: String,
    val currentStepIndex: Int,
    val totalSteps: Int,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val failureReason: String?,
    val cancellationReason: String?,
    val stepStates: List<WorkflowStepState>
)

data class WorkflowStepState(
    val stepId: String,
    val stepIndex: Int,
    val status: String,
    val outputSummary: String?,
    val durationMs: Long?,
    val attemptCount: Int,
    val lastErrorMessage: String?
)
