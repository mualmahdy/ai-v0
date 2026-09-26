package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.repair.RepairCenterService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * SystemHealthViewModel — CLOSURE §11 (Repair / Recovery UX)
 * ============================================================================
 * The user-facing System/Workspace Health surface for the (previously
 * backend-only, ZERO-caller) RepairCenterService:
 *
 *   - DEGRADED SERVICES / INCONSISTENT STATE: the deterministic condition
 *     list (stale active-project pins, orphaned projects, invalid knowledge
 *     references) — each with an explain + one-tap REPAIR that runs the
 *     real deterministic repair and RE-DETECTS afterwards (the verification
 *     result is shown honestly).
 *   - INTERRUPTED EXECUTIONS: RUNNING tasks with NO live execution — bound
 *     to the EXACT task identity. Each offers the honest choice:
 *     RESUME (the real durable resume through the canonical execution
 *     context + checkpoint) or RECONCILE-TO-FAILED (the deterministic
 *     repair).
 *   - RECOVERABLE TRANSFERS / INVALID ARTIFACTS: surfaced as conditions the
 *     detector reports (honest labels — nothing is claimed repaired that
 *     was not verified).
 */
class SystemHealthViewModel(
    private val repairCenterService: RepairCenterService? = null,
    private val agentOrchestrator: AgentOrchestrator? = null
) : ViewModel() {

    data class InterruptedExecution(
        val taskId: String,
        val rawPrompt: String,
        val workspaceId: String? = null,
        val isResumable: Boolean,
        val degradationReason: String?
    )

    data class SystemHealthUiState(
        val isScanning: Boolean = false,
        val conditions: List<RepairCenterService.Condition> = emptyList(),
        val lastRepairReports: List<RepairCenterService.RepairReport> = emptyList(),
        val interruptedExecutions: List<InterruptedExecution> = emptyList(),
        val resumingTaskId: String? = null,
        val healthy: Boolean = true,
        val diagnosticBanner: String? = null,
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(SystemHealthUiState())
    val state: StateFlow<SystemHealthUiState> = _state.asStateFlow()

    init {
        scan()
    }

    /** DETECT: full scan (conditions + interrupted executions). */
    fun scan() {
        val repair = repairCenterService ?: return
        _state.update { it.copy(isScanning = true) }
        viewModelScope.launch {
            runCatching { repair.detectAll() }.onSuccess { conditions ->
                val interrupted = runCatching { repair.interruptedExecutions() }
                    .getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        isScanning = false,
                        conditions = conditions,
                        interruptedExecutions = interrupted.map { info ->
                            InterruptedExecution(
                                taskId = info.taskId,
                                rawPrompt = info.rawPrompt,
                                workspaceId = info.workspaceId,
                                isResumable = info.isResumable,
                                degradationReason = null
                            )
                        },
                        healthy = conditions.isEmpty() && interrupted.isEmpty()
                    )
                }
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        isScanning = false,
                        errorMessage = "تعذر فحص صحة النظام: ${failure.localizedMessage}"
                    )
                }
            }
        }
    }

    /** REPAIR + VERIFY: runs the real deterministic repair and shows the honest outcome. */
    fun repairCondition(condition: RepairCenterService.Condition) {
        val repair = repairCenterService ?: return
        viewModelScope.launch {
            runCatching { repair.repair(condition) }
                .onSuccess { report ->
                    _state.update { current ->
                        current.copy(
                            lastRepairReports = current.lastRepairReports + report,
                            diagnosticBanner = if (report.verifiedAfterRepair) {
                                "تم الإصلاح والتحقق منه: ${report.condition.code}"
                            } else {
                                "نُفّذ الإصلاح لكن التحقق بعد الإصلاح لم يؤكد زوال الحالة: ${report.condition.code}"
                            }
                        )
                    }
                    // Re-scan: the UI reflects the world as it IS after repair.
                    scan()
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(errorMessage = "فشل الإصلاح: ${failure.localizedMessage}")
                    }
                }
        }
    }

    /**
     * INTERRUPTED EXECUTION → RESUME: the REAL durable resume (canonical
     * execution context + checkpoint) through the orchestrator — bound to
     * the EXACT task identity. The resumed execution runs in the
     * process-wide ExecutionHost (survives leaving this screen).
     */
    fun resumeInterruptedExecution(taskId: String) {
        val orchestrator = agentOrchestrator ?: return
        _state.update { it.copy(resumingTaskId = taskId) }
        com.example.application.execution.ExecutionHost.launch(taskId, workspaceId = null) {
            var completed = false
            var failed = false
            runCatching { orchestrator.resumeTask(taskId) }
                .onSuccess { flow ->
                    flow.collect { event ->
                        when (event) {
                            is com.example.domain.core.events.ExecutionEvent.Completed -> completed = true
                            is com.example.domain.core.events.ExecutionEvent.Error -> failed = true
                            else -> Unit
                        }
                    }
                }
            _state.update {
                it.copy(
                    resumingTaskId = null,
                    diagnosticBanner = when {
                        completed -> "اكتمل استئناف المهمة $taskId."
                        failed -> "فشل الاستئناف بعد البدء — سجّل المهمة موجود والسبب ظاهر في السجل."
                        else -> "بدأ استئناف المهمة $taskId — تابع حالتها من مركز النشاط."
                    }
                )
            }
            scan()
        }
    }

    fun consumeDiagnostic() {
        _state.update { it.copy(diagnosticBanner = null) }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
