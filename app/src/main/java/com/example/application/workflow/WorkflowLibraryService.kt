package com.example.application.workflow

import com.example.domain.core.workflow.ExecutionMode
import com.example.domain.core.workflow.StepNode
import com.example.domain.core.workflow.WorkflowId
import com.example.domain.core.workflow.WorkflowPlan
import com.example.infrastructure.persistence.dao.WorkflowDefinitionDao
import com.example.infrastructure.persistence.entities.WorkflowDefinitionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * ============================================================================
 * WorkflowLibraryService — the USER-AUTHORED workflow asset authority
 * ============================================================================
 *
 * REPORT GAP CLOSED ("workflow library/history NOT FIXED"):
 * WorkflowPersistenceService made workflow EXECUTION durable (the run
 * survives process death), but the workflow DEFINITION the user authored in
 * the builder lived only in Compose state — closing the screen lost it.
 *
 * This service stores definitions as first-class workspace assets:
 *
 *   Create Workflow → Save Definition → List → Load → Edit → Re-save
 *   (versioned) → Clone → Run (recorded) → Delete
 *
 * The steps JSON reuses the SAME lossless serialization the execution
 * persistence uses (`serializePlan` / `deserializePlan`), so a saved
 * definition and a running execution share one plan format (schema-tolerant
 * reads keep older definitions loadable).
 */
class WorkflowLibraryService(
    private val workflowDefinitionDao: WorkflowDefinitionDao,
    /** Serialization authority — the SAME lossless format execution persistence uses. */
    private val planSerializer: WorkflowPersistenceService,
    private val workspaceIdProvider: suspend () -> String
) {

    /** Library summary row (no plan payload — cheap for lists). */
    data class WorkflowDefinitionSummary(
        val workflowId: WorkflowId,
        val workspaceId: String,
        val name: String,
        val goal: String,
        val executionMode: ExecutionMode,
        val stepCount: Int,
        val version: Int,
        val runCount: Int,
        val lastRunAtEpochMs: Long?,
        val updatedAtEpochMs: Long
    )

    /** Live, most-recently-updated-first library for a workspace. */
    fun observeLibrary(workspaceId: String): Flow<List<WorkflowDefinitionSummary>> =
        workflowDefinitionDao.forWorkspace(workspaceId).map { rows ->
            rows.map { it.toSummary() }
        }

    /** Saves (or re-saves, version-bumped) a plan as a named library asset. */
    suspend fun saveDefinition(
        existingId: WorkflowId?,
        name: String,
        plan: WorkflowPlan
    ): WorkflowId {
        val now = System.currentTimeMillis()
        val trimmedName = name.trim().ifBlank { "خطة عمل بلا اسم" }
        val id = existingId ?: WorkflowId("wfl_${UUID.randomUUID().toString().take(12)}")
        val existing = workflowDefinitionDao.byId(id.value)
        val stepsJson = planSerializer.serializePlan(plan).let { json ->
            // Store ONLY the steps array (goal/mode live in dedicated columns).
            extractStepsArray(json)
        }
        val entity = WorkflowDefinitionEntity(
            workflowId = id.value,
            workspaceId = workspaceIdProvider(),
            name = trimmedName,
            goal = plan.goal,
            executionMode = plan.executionMode.name,
            stepsJson = stepsJson,
            version = (existing?.version ?: 0) + 1,
            runCount = existing?.runCount ?: 0,
            lastRunAtEpochMs = existing?.lastRunAtEpochMs,
            createdAtEpochMs = existing?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now
        )
        workflowDefinitionDao.upsert(entity)
        return id
    }

    /** Loads a definition back into a FULL editable plan (resume editing). */
    suspend fun loadDefinition(id: WorkflowId): WorkflowPlan? {
        val entity = workflowDefinitionDao.byId(id.value) ?: return null
        val planJson = rebuildPlanJson(entity)
        return planSerializer.deserializePlan(planJson)
    }

    /** Clones a definition (id + fresh timestamps, version 1, runCount 0). */
    suspend fun cloneDefinition(id: WorkflowId): WorkflowId? {
        val entity = workflowDefinitionDao.byId(id.value) ?: return null
        val now = System.currentTimeMillis()
        val cloneId = WorkflowId("wfl_${UUID.randomUUID().toString().take(12)}")
        workflowDefinitionDao.upsert(
            entity.copy(
                workflowId = cloneId.value,
                name = "${entity.name} (نسخة)",
                version = 1,
                runCount = 0,
                lastRunAtEpochMs = null,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        )
        return cloneId
    }

    /** Records that a definition was executed (run history). */
    suspend fun recordRun(id: WorkflowId) {
        workflowDefinitionDao.recordRun(id.value, System.currentTimeMillis())
    }

    suspend fun deleteDefinition(id: WorkflowId) {
        workflowDefinitionDao.delete(id.value)
    }

    // ------------------------------------------------------------------
    // JSON plumbing — reuses the execution persistence format exactly
    // ------------------------------------------------------------------

    private fun extractStepsArray(planJson: String): String {
        return try {
            val obj = org.json.JSONObject(planJson)
            obj.getJSONArray("steps").toString()
        } catch (_: Exception) {
            "[]"
        }
    }

    private fun rebuildPlanJson(entity: WorkflowDefinitionEntity): String {
        // deserializePlan expects {workflowId, goal, executionMode, steps[]}.
        return org.json.JSONObject().apply {
            put("workflowId", entity.workflowId)
            put("goal", entity.goal)
            put("executionMode", entity.executionMode)
            put("schemaVersion", 2)
            put("steps", org.json.JSONArray(entity.stepsJson))
        }.toString()
    }

    private fun WorkflowDefinitionEntity.toSummary(): WorkflowDefinitionSummary {
        val stepCount = try {
            org.json.JSONArray(stepsJson).length()
        } catch (_: Exception) {
            0
        }
        val mode = try {
            ExecutionMode.valueOf(executionMode)
        } catch (_: Exception) {
            ExecutionMode.SEQUENTIAL
        }
        return WorkflowDefinitionSummary(
            workflowId = WorkflowId(workflowId),
            workspaceId = workspaceId,
            name = name,
            goal = goal,
            executionMode = mode,
            stepCount = stepCount,
            version = version,
            runCount = runCount,
            lastRunAtEpochMs = lastRunAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs
        )
    }
}
