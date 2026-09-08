package com.example.application.usecases

import com.example.application.orchestration.WorkflowEngine
import com.example.domain.core.workflow.WorkflowExecutionReport
import com.example.domain.core.workflow.WorkflowPlan

/**
 * High-level Use Case: Executes structured DAG/Sequential workflow plans.
 *
 * DURABLE RESUME (report gap: "Resume later"): [completedStepIds] carries the
 * steps a PREVIOUS run already finished (from WorkflowPersistenceService);
 * the engine seeds them COMPLETED and never re-executes them.
 */
class ExecuteWorkflowUseCase(
    private val workflowEngine: WorkflowEngine
) {

    suspend operator fun invoke(
        plan: WorkflowPlan,
        completedStepIds: Set<String> = emptySet()
    ): WorkflowExecutionReport {
        return workflowEngine.executePlan(plan, completedStepIds)
    }
}
