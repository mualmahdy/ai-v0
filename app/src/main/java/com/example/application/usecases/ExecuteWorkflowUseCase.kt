package com.example.application.usecases

import com.example.application.orchestration.WorkflowEngine
import com.example.domain.core.workflow.WorkflowExecutionReport
import com.example.domain.core.workflow.WorkflowPlan

/**
 * High-level Use Case: Executes structured DAG/Sequential workflow plans.
 */
class ExecuteWorkflowUseCase(
    private val workflowEngine: WorkflowEngine
) {

    suspend operator fun invoke(plan: WorkflowPlan): WorkflowExecutionReport {
        return workflowEngine.executePlan(plan)
    }
}
