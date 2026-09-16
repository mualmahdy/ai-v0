package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.usecases.ExecuteWorkflowUseCase
import com.example.application.workflow.WorkflowLibraryService
import com.example.application.workflow.WorkflowPersistenceService
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.workflow.StepNode
import com.example.domain.core.workflow.WorkflowExecutionReport
import com.example.domain.core.workflow.WorkflowId
import com.example.domain.core.workflow.WorkflowPlan
import com.example.presentation.state.WorkflowBuilderState
import com.example.presentation.state.WorkflowBuilderStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * WorkflowsViewModel — ADR-6 slice 6 (Design Closure 2026 UI-redesign track)
 * ============================================================================
 *
 * The workflow feature — the plan BUILDER, the durable LIBRARY and the
 * RESUME surface (the "Tasks المتبقي" of the track table) — left the
 * MainViewModel per ADR-6 option-ج, with its whole dependency set
 * (executeWorkflowUseCase, workflowLibraryService,
 * workflowPersistenceService). The TASK BOARD stays in TasksViewModel (its
 * owner since GAP-11); TasksScreen composes on both.
 *
 * STATE: the 5 workflow UiState fields (workflowReport /
 * isExecutingWorkflow / workflowBuilder / workflowLibrary /
 * resumableWorkflows) moved to this feature-owned [WorkflowsUiState] with
 * its own error + banner channels. The builder types
 * ([WorkflowBuilderStep] / [WorkflowBuilderState]) remain in the state
 * package (the StudioTurn precedent, ADR-6 slice 2).
 *
 * BEHAVIOR (moved verbatim unless noted): execution with durable-resume
 * seeding (the already-completed steps are seeded COMPLETED and never
 * re-executed), the FULL builder (add / edit / reorder / dependency wiring /
 * canonical durable-agent binding), the library lifecycle (save → list →
 * load → edit → re-save / clone / delete / run-with-history), the
 * workspace-scoped resumable list, and the durable resume itself
 * (WORKSPACE-SCOPED, defect family 1: another workspace's run cannot be
 * resumed from this surface).
 *
 * RE-WIRING (the one deliberate repair, slice-4 precedent): the
 * workspace-scoped assets observer used the stacked-collector pattern (a new
 * inner library collector per active-workspace emission, the previous never
 * cancelled — the same last-writer race family the slice-2 sessions registry
 * and the slice-4 radar observers had). Rewritten with flatMapLatest: the
 * library reflects ONLY the active workspace, and a null workspace is the
 * honest empty list (the GAP-04 feed-isolation precedent) instead of a stale
 * workspace's rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowsViewModel(
    private val executeWorkflowUseCase: ExecuteWorkflowUseCase,
    private val workflowLibraryService: WorkflowLibraryService? = null,
    private val workflowPersistenceService: WorkflowPersistenceService? = null,
    workspaceRuntimeService: WorkspaceRuntimeService
) : ViewModel() {

    data class WorkflowsUiState(
        /** The last execution report (rendered by the console card). */
        val workflowReport: WorkflowExecutionReport? = null,
        val isExecutingWorkflow: Boolean = false,
        /**
         * WORKFLOW BUILDER STATE (report gap: "workflow definition must be a
         * re-editable durable asset"): the authoring state lives in the
         * ViewModel — not in Compose `remember` — so saving, loading,
         * editing and re-saving library definitions is possible without
         * losing the builder on navigation.
         */
        val workflowBuilder: WorkflowBuilderState = WorkflowBuilderState(),
        /** The user-authored library definitions (durable assets). */
        val workflowLibrary: List<com.example.domain.core.workflow.WorkflowDefinitionSummary> = emptyList(),
        /** The RUNNING/PAUSED/COMPENSATING executions resumable in THIS workspace. */
        val resumableWorkflows: List<com.example.domain.core.workflow.ResumableWorkflow> = emptyList(),
        /** The feature's own honest error channel (global snackbar). */
        val errorMessage: String? = null,
        /** The feature's own transient diagnostic channel (local banner). */
        val diagnosticBanner: String? = null
    )

    private val _state = MutableStateFlow(WorkflowsUiState())
    val state: StateFlow<WorkflowsUiState> = _state.asStateFlow()

    private val workspaceRuntimeService: WorkspaceRuntimeService = workspaceRuntimeService

    init {
        // DURABLE SESSIONS + WORKFLOW LIBRARY + RESUMABLE (report
        // gap-closure): workspace-scoped durable assets follow the active
        // workspace (continuity across switches).
        observeWorkspaceScopedAssets()
    }

    /**
     * (Moved from MainViewModel — with the ONE deliberate re-wiring of this
     * slice:) the workspace-scoped library observer is flatMapLatest-bound
     * to the active workspace (the stacked-collector last-writer race is
     * gone — slice-4 precedent), and the resumable list refreshes on every
     * workspace emission (it stays a PULL surface).
     */
    private fun observeWorkspaceScopedAssets() {
        val library = workflowLibraryService
        if (library != null) {
            viewModelScope.launch {
                // A null active workspace is the HONEST empty library (the
                // GAP-04 feed-isolation precedent) — never a stale
                // workspace's rows.
                workspaceRuntimeService.activeWorkspace
                    .flatMapLatest { workspace ->
                        val wsId = workspace?.id
                        if (wsId == null) flowOf(emptyList())
                        else library.observeLibrary(wsId)
                    }
                    .collect { defs ->
                        _state.update { it.copy(workflowLibrary = defs) }
                    }
            }
        }
        viewModelScope.launch {
            workspaceRuntimeService.activeWorkspace.collect { workspace ->
                if (workspace != null) {
                    // Resumable workflow executions (durable resume surface).
                    loadResumableWorkflows()
                }
            }
        }
    }

    /** Refreshes the resumable (RUNNING/PAUSED/COMPENSATING) workflows list —
     *  WORKSPACE-SCOPED (defect family 1): only the ACTIVE workspace's runs. */
    fun loadResumableWorkflows() {
        val persistence = workflowPersistenceService ?: return
        viewModelScope.launch {
            runCatching {
                val workspaceId = workspaceRuntimeService.activeWorkspaceIdOrNull()
                _state.update { it.copy(resumableWorkflows = persistence.resumable(workspaceId)) }
            }
        }
    }

    // --- Workflow & Task ---

    fun executeWorkflow(plan: WorkflowPlan) {
        executeWorkflow(plan, completedStepIds = emptySet())
    }

    /**
     * DURABLE RESUME (report gap: "Resume later"): carries the steps a
     * previous run already finished — the engine seeds them COMPLETED and
     * never re-executes them.
     */
    private fun executeWorkflow(plan: WorkflowPlan, completedStepIds: Set<String>) {
        if (_state.value.isExecutingWorkflow) return
        _state.update { it.copy(isExecutingWorkflow = true, workflowReport = null, errorMessage = null) }
        viewModelScope.launch {
            try {
                val report = executeWorkflowUseCase(plan, completedStepIds)
                _state.update { it.copy(isExecutingWorkflow = false, workflowReport = report) }
                // Refresh the resumable surface (a failed run is resumable).
                loadResumableWorkflows()
            } catch (e: Exception) {
                _state.update { it.copy(isExecutingWorkflow = false, errorMessage = "فشل تنفيذ خطة العمل: ${e.localizedMessage}") }
                loadResumableWorkflows()
            }
        }
    }

    // ==================================================================
    // WORKFLOW BUILDER (report gap: authoring state lives in the ViewModel,
    // not Compose memory — the definition is a durable, re-editable asset)
    // ==================================================================

    fun updateWorkflowName(name: String) {
        _state.update { it.copy(workflowBuilder = it.workflowBuilder.copy(name = name)) }
    }

    fun updateWorkflowGoal(goal: String) {
        _state.update { it.copy(workflowBuilder = it.workflowBuilder.copy(goal = goal)) }
    }

    fun updateWorkflowMode(mode: com.example.domain.core.workflow.ExecutionMode) {
        _state.update { it.copy(workflowBuilder = it.workflowBuilder.copy(executionMode = mode)) }
    }

    fun addWorkflowStep() {
        _state.update { state ->
            val steps = state.workflowBuilder.steps
            val newStep = WorkflowBuilderStep(
                id = "step_${steps.size + 1}_${System.currentTimeMillis() % 1000}",
                description = "",
                role = com.example.domain.core.agent.AgentRole.GENERAL_ASSISTANT,
                dependencies = emptySet()
            )
            state.copy(workflowBuilder = state.workflowBuilder.copy(steps = steps + newStep))
        }
    }

    fun updateWorkflowStep(index: Int, transform: (WorkflowBuilderStep) -> WorkflowBuilderStep) {
        _state.update { state ->
            val steps = state.workflowBuilder.steps.toMutableList()
            if (index in steps.indices) {
                steps[index] = transform(steps[index])
                state.copy(workflowBuilder = state.workflowBuilder.copy(steps = steps))
            } else state
        }
    }

    fun removeWorkflowStep(index: Int) {
        _state.update { state ->
            val steps = state.workflowBuilder.steps.toMutableList()
            if (index in steps.indices) steps.removeAt(index)
            state.copy(workflowBuilder = state.workflowBuilder.copy(steps = steps))
        }
    }

    fun moveWorkflowStep(index: Int, delta: Int) {
        _state.update { state ->
            val steps = state.workflowBuilder.steps.toMutableList()
            val target = index + delta
            if (index in steps.indices && target in steps.indices) {
                val moved = steps.removeAt(index)
                steps.add(target, moved)
                state.copy(workflowBuilder = state.workflowBuilder.copy(steps = steps))
            } else state
        }
    }

    fun toggleWorkflowStepDependency(index: Int, depId: String) {
        updateWorkflowStep(index) { step ->
            step.copy(
                dependencies = if (depId in step.dependencies) step.dependencies - depId
                else step.dependencies + depId
            )
        }
    }

    /**
     * CANONICAL AGENT BINDING (report gap): assigns a DURABLE registry agent
     * to a builder step — the step executes through the real agent (system
     * prompt, capabilities, budget, version, lifecycle), not a synthetic one.
     */
    fun assignWorkflowStepAgent(index: Int, agentId: String?) {
        updateWorkflowStep(index) { it.copy(assignedAgentId = agentId) }
    }

    /** Applies a built-in template to the builder. */
    fun applyWorkflowTemplate(template: WorkflowBuilderState) {
        _state.update { it.copy(workflowBuilder = template) }
    }

    // ==================================================================
    // WORKFLOW LIBRARY (report gap: save → list → load → edit → clone →
    // run — the USER-AUTHORED definition as a durable workspace asset)
    // ==================================================================

    /** Saves the current builder as a library definition (or re-saves it). */
    fun saveWorkflowDefinition() {
        val library = workflowLibraryService ?: return
        viewModelScope.launch {
            runCatching {
                val builder = _state.value.workflowBuilder
                val plan = WorkflowPlan(
                    id = WorkflowId(
                        builder.editingDefinitionId ?: "wf_builder_${System.currentTimeMillis()}"
                    ),
                    goal = builder.goal.trim(),
                    executionMode = builder.executionMode,
                    steps = builder.steps.map { s ->
                        StepNode(
                            id = s.id,
                            taskId = com.example.domain.core.task.TaskId("task_${s.id}"),
                            agentRole = s.role,
                            description = s.description.ifBlank { "${s.role.displayName} — خطوة ${s.id}" },
                            dependencies = s.dependencies,
                            assignedAgentId = s.assignedAgentId
                        )
                    }
                )
                val id = library.saveDefinition(
                    existingId = builder.editingDefinitionId?.let { WorkflowId(it) },
                    name = builder.name,
                    plan = plan
                )
                _state.update {
                    it.copy(
                        workflowBuilder = it.workflowBuilder.copy(editingDefinitionId = id.value),
                        diagnosticBanner = "تم حفظ خطة العمل في المكتبة (الإصدار محفوظ ويُحرَّر لاحقاً)."
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(errorMessage = "تعذر حفظ خطة العمل: ${e.localizedMessage}") }
            }
        }
    }

    /** Loads a library definition into the builder for editing. */
    fun loadWorkflowDefinitionIntoBuilder(definitionId: String) {
        val library = workflowLibraryService ?: return
        viewModelScope.launch {
            runCatching {
                val summary = _state.value.workflowLibrary.firstOrNull { it.workflowId.value == definitionId }
                val plan = library.loadDefinition(WorkflowId(definitionId))
                    ?: return@launch
                _state.update {
                    it.copy(
                        workflowBuilder = WorkflowBuilderState(
                            name = summary?.name ?: "خطة عمل",
                            goal = plan.goal,
                            executionMode = plan.executionMode,
                            steps = plan.steps.map { s ->
                                WorkflowBuilderStep(
                                    id = s.id,
                                    description = s.description,
                                    role = s.agentRole,
                                    dependencies = s.dependencies,
                                    assignedAgentId = s.assignedAgentId
                                )
                            },
                            editingDefinitionId = definitionId
                        )
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(errorMessage = "تعذر تحميل خطة العمل: ${e.localizedMessage}") }
            }
        }
    }

    /** Runs a library definition directly (records the run). */
    fun runWorkflowDefinition(definitionId: String) {
        val library = workflowLibraryService ?: return
        viewModelScope.launch {
            runCatching {
                val plan = library.loadDefinition(WorkflowId(definitionId)) ?: return@launch
                library.recordRun(WorkflowId(definitionId))
                executeWorkflow(plan)
            }
        }
    }

    /** Clones a library definition. */
    fun cloneWorkflowDefinition(definitionId: String) {
        val library = workflowLibraryService ?: return
        viewModelScope.launch {
            // GAP-13: degradation surfaced (was a bare runCatching).
            runCatching { library.cloneDefinition(WorkflowId(definitionId)) }
                .onFailure { failure ->
                    _state.update { it.copy(diagnosticBanner = "تعذر استنساخ خطة العمل: ${failure.localizedMessage}") }
                }
        }
    }

    /** Deletes a library definition. */
    fun deleteWorkflowDefinition(definitionId: String) {
        val library = workflowLibraryService ?: return
        viewModelScope.launch {
            // GAP-13: degradation surfaced (was a bare runCatching).
            runCatching { library.deleteDefinition(WorkflowId(definitionId)) }
                .onFailure { failure ->
                    _state.update { it.copy(diagnosticBanner = "تعذر حذف خطة العمل: ${failure.localizedMessage}") }
                }
        }
    }

    /**
     * RESUMES a durable interrupted workflow execution (report gap:
     * "Resume later"): the already-completed steps are seeded COMPLETED and
     * never re-executed; remaining steps run with real prior outputs as
     * upstream context.
     */
    fun resumeWorkflow(workflowId: String) {
        val persistence = workflowPersistenceService ?: return
        viewModelScope.launch {
            // GAP-13: degradation surfaced (was a bare runCatching).
            runCatching {
                // WORKSPACE-SCOPED resume (defect family 1): a workflow of
                // ANOTHER workspace cannot be resumed from this surface.
                val workspaceId = workspaceRuntimeService.activeWorkspaceIdOrNull()
                val resumable = persistence.resumable(workspaceId).firstOrNull { it.workflowId.value == workflowId }
                    ?: return@launch
                executeWorkflow(resumable.plan, resumable.completedStepIds)
            }.onFailure { failure ->
                _state.update { it.copy(diagnosticBanner = "تعذر استئناف التنفيذ: ${failure.localizedMessage}") }
            }
        }
    }

    /** Clears the feature's honest error channel (after the global snackbar). */
    fun clearErrorMessage() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** Dismisses the feature's transient local diagnostic banner. */
    fun dismissDiagnosticBanner() {
        _state.update { it.copy(diagnosticBanner = null) }
    }
}
