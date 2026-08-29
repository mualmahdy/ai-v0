package com.example.application.usecases

import com.example.application.orchestration.AgentOrchestrator
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * High-level Use Case: Executes a user or agent task via decision-driven orchestrated event stream.
 */
class ExecuteAgentTaskUseCase(
    private val orchestrator: AgentOrchestrator
) {

    operator fun invoke(
        agent: AgentDefinition,
        prompt: String,
        history: List<LlmMessage> = emptyList(),
        preferredProviderId: String? = null,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true,
        includeWebSearch: Boolean = false
    ): Flow<ExecutionEvent> {
        val task = TaskDefinition(
            id = TaskId(UUID.randomUUID().toString()),
            assignedAgentId = agent.identity.id,
            input = TaskInput(rawPrompt = prompt)
        )

        return orchestrator.executeTaskStream(
            agent = agent,
            task = task,
            conversationHistory = history,
            preferredProviderId = preferredProviderId,
            networkPolicy = networkPolicy,
            isNetworkAvailable = isNetworkAvailable,
            includeWebSearch = includeWebSearch
        )
    }
}
