package com.example.application.execution

import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.execution.CanonicalExecutionContext
import com.example.domain.core.task.TaskId
import org.json.JSONObject

/**
 * CanonicalExecutionContext serialization (application layer; the domain model
 * stays pure Kotlin). Round-trips through `tasks.executionContextJson`.
 */
object ExecutionContextCodec {

    fun encode(context: CanonicalExecutionContext): String {
        val obj = JSONObject()
        obj.put("executionId", context.executionId)
        obj.put("taskId", context.taskId.value)
        obj.put("workspaceId", context.workspaceId)
        context.projectId?.let { obj.put("projectId", it) }
        obj.put("agentId", context.agentId.value)
        obj.put("agentRole", context.agentRole.name)
        context.modelId?.let { obj.put("modelId", it) }
        context.parentTaskId?.let { obj.put("parentTaskId", it) }
        obj.put("delegationDepth", context.delegationDepth)
        obj.put("attempt", context.attempt)
        obj.put("startedAtEpochMs", context.startedAtEpochMs)
        return obj.toString()
    }

    fun decode(json: String?): CanonicalExecutionContext? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(json)
            CanonicalExecutionContext(
                executionId = obj.getString("executionId"),
                taskId = TaskId(obj.optString("taskId")),
                workspaceId = obj.getString("workspaceId"),
                projectId = if (obj.has("projectId") && !obj.isNull("projectId")) obj.getLong("projectId") else null,
                agentId = AgentId(obj.getString("agentId")),
                agentRole = runCatching { AgentRole.valueOf(obj.getString("agentRole")) }
                    .getOrDefault(AgentRole.GENERAL_ASSISTANT),
                modelId = obj.optString("modelId").takeIf { it.isNotBlank() },
                parentTaskId = obj.optString("parentTaskId").takeIf { it.isNotBlank() },
                delegationDepth = obj.optInt("delegationDepth", 0),
                attempt = obj.optInt("attempt", 1),
                startedAtEpochMs = obj.optLong("startedAtEpochMs", System.currentTimeMillis())
            )
        }.getOrNull()
    }
}
