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
 *
 * Gap-closure P1-13 (model pinning): [assignedModelId] pins the task to ONE
 * specific model — the decision layer targets exactly that resource and the
 * binding survives the whole execution (reproducibility) instead of degrading
 * into "whatever model is currently preferred".
 */
class ExecuteAgentTaskUseCase(
    private val orchestrator: AgentOrchestrator
) {

    operator fun invoke(
        agent: AgentDefinition,
        prompt: String,
        taskId: String = UUID.randomUUID().toString(),
        history: List<LlmMessage> = emptyList(),
        preferredProviderId: String? = null,
        assignedModelId: String? = null,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true,
        includeWebSearch: Boolean = false
    ): Flow<ExecutionEvent> {
        // REPAIR (defect family 3 — canonical identity & reproducibility):
        // `assignedModelId` previously fell back to `preferredProviderId`,
        // storing a PROVIDER id in a MODEL-RESOURCE field. Provider identity
        // and model-resource identity are now STRICTLY separated:
        //   - [assignedModelId] accepts ONLY model-resource ids
        //     (res:provider:service:type:offering).
        //   - [preferredProviderId] remains a routing PREFERENCE hint, never
        //     an exact pin.
        // A pin that is not a model-resource id matches no candidate and
        // surfaces as an explicit PINNED_MODEL_UNAVAILABLE decision — no
        // silent degradation.
        val task = TaskDefinition(
            id = TaskId(taskId),
            assignedAgentId = agent.identity.id,
            input = TaskInput(rawPrompt = prompt),
            assignedModelId = assignedModelId
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
