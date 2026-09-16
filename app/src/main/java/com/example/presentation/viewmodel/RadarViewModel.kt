package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.radar.IntelligenceRadarPipeline
import com.example.domain.core.Outcome
import com.example.domain.core.evolution.EvolutionCandidate
import com.example.domain.core.evolution.EvolutionStage
import com.example.domain.core.radar.RadarItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * RadarViewModel — ADR-6 slice 6 (Design Closure 2026 UI-redesign track)
 * ============================================================================
 *
 * The intelligence radar & evolution observatory left the MainViewModel per
 * ADR-6 option-ج, with its whole dependency set (intelligenceRadarPipeline).
 *
 * STATE: the 3 radar UiState fields (radarItems / evolutionCandidates /
 * isRadarRefreshing) moved to this feature-owned [RadarUiState] with its own
 * error + banner channels.
 *
 * BEHAVIOR (moved verbatim): the two Room-backed pipeline observers (they
 * lived in MainViewModel.observeSubsystems), the refresh with the HONEST
 * spinner flag (GAP-23: isRadarRefreshing previously had no writer — the
 * spinner could never appear), and the FULL evolution lifecycle actions
 * (GAP-CLOSURE P1-17): stage promotion with the governance gate's verdict
 * surfaced honestly (FIX F-10), the security audit, the governance approval,
 * the registered-capability measurement and the retirement.
 */
class RadarViewModel(
    private val intelligenceRadarPipeline: IntelligenceRadarPipeline
) : ViewModel() {

    data class RadarUiState(
        /** The ecosystem feed (9-stage pipeline output, Room-backed). */
        val radarItems: List<RadarItem> = emptyList(),
        /** The evolution candidates in their promotion lifecycle. */
        val evolutionCandidates: List<EvolutionCandidate> = emptyList(),
        /**
         * GAP-23 (Design Closure 2026): honestly WRITTEN around the pipeline
         * call — previously a no-writer field, so the refresh spinner could
         * never appear.
         */
        val isRadarRefreshing: Boolean = false,
        /** The feature's own transient diagnostic channel (local banner). */
        val diagnosticBanner: String? = null,
        /** The feature's own honest error channel (global snackbar). */
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(RadarUiState())
    val state: StateFlow<RadarUiState> = _state.asStateFlow()

    init {
        // (Moved verbatim from MainViewModel.observeSubsystems — the two
        // radar collectors were its last radar responsibility.)
        viewModelScope.launch {
            intelligenceRadarPipeline.radarItems.collect { items ->
                _state.update { it.copy(radarItems = items) }
            }
        }
        viewModelScope.launch {
            intelligenceRadarPipeline.evolutionCandidates.collect { candidates ->
                _state.update { it.copy(evolutionCandidates = candidates) }
            }
        }
    }

    /**
     * GAP-23 (Design Closure 2026): the refresh indicator is now WRITTEN
     * honestly around the pipeline call — previously a no-writer field,
     * so RadarScreen's spinner could never appear.
     */
    fun refreshRadar() {
        viewModelScope.launch {
            _state.update { it.copy(isRadarRefreshing = true) }
            try {
                intelligenceRadarPipeline.refreshRadarFeed()
            } finally {
                _state.update { it.copy(isRadarRefreshing = false) }
            }
        }
    }

    fun advanceCandidateStage(candidateId: String, nextStage: EvolutionStage) {
        viewModelScope.launch {
            // FIX F-10: surface the governance gate's verdict honestly instead
            // of silently ignoring a rejected promotion.
            when (val outcome = intelligenceRadarPipeline.advanceEvolutionStage(candidateId, nextStage)) {
                is Outcome.Success -> {
                    _state.update {
                        it.copy(diagnosticBanner = "تمت ترقية المرشح إلى ${nextStage.displayName}.")
                    }
                }
                is Outcome.Error -> {
                    _state.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                }
                else -> Unit
            }
        }
    }

    /**
     * GAP-CLOSURE P1-17 — the acquisition loop is now COMPLETE and operable:
     * security audit, governance approval, registration measurement, and
     * retirement all have explicit, durable entry points.
     */
    fun recordCandidateSecurityAudit(candidateId: String, passed: Boolean) {
        viewModelScope.launch {
            when (val r = intelligenceRadarPipeline.recordSecurityAudit(candidateId, passed, "تدقيق من مرصد التطور")) {
                is Outcome.Error -> _state.update { it.copy(errorMessage = r.diagnosticMessage) }
                is Outcome.Success -> _state.update {
                    it.copy(diagnosticBanner = "نتيجة التدقيق الأمني: ${if (passed) "ناجح" else "فاشل"}.")
                }
                else -> Unit
            }
        }
    }

    fun recordCandidateGovernanceApproval(candidateId: String, approved: Boolean) {
        viewModelScope.launch {
            when (val r = intelligenceRadarPipeline.recordGovernanceApproval(candidateId, approved)) {
                is Outcome.Error -> _state.update { it.copy(errorMessage = r.diagnosticMessage) }
                is Outcome.Success -> _state.update {
                    it.copy(diagnosticBanner = "موافقة الحوكمة: ${if (approved) "ممنوحة" else "مرفوضة"}.")
                }
                else -> Unit
            }
        }
    }

    fun measureRegisteredCapability(candidateId: String) {
        viewModelScope.launch {
            when (val r = intelligenceRadarPipeline.measureRegisteredCapability(candidateId)) {
                is Outcome.Error -> _state.update { it.copy(errorMessage = r.diagnosticMessage) }
                is Outcome.Success -> _state.update {
                    it.copy(diagnosticBanner = "تم تسجيل قياس أساسي للقدرة في تدفق أدلة رادار القدرات.")
                }
                else -> Unit
            }
        }
    }

    fun retireRegisteredCapability(candidateId: String, reason: String) {
        viewModelScope.launch {
            when (val r = intelligenceRadarPipeline.retireCapability(candidateId, reason)) {
                is Outcome.Error -> _state.update { it.copy(errorMessage = r.diagnosticMessage) }
                is Outcome.Success -> _state.update {
                    it.copy(diagnosticBanner = "أُحيلت القدرة المسجلة إلى التقاعد: $reason")
                }
                else -> Unit
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
