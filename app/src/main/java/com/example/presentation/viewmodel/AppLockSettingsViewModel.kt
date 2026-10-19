package com.example.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.applock.AppLockAuthAvailability
import com.example.application.applock.AppLockAuthenticator
import com.example.application.applock.AppLockService
import com.example.domain.core.security.applock.AppLockMode
import com.example.domain.core.security.applock.AppLockPolicy
import com.example.domain.core.security.applock.AppLockTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * AppLockSettingsViewModel — STAGE 7 (the app-lock SETTINGS surface)
 * ============================================================================
 *
 * The app-lock backend (service + DataStore + BiometricPrompt adapter + the
 * shell gate) shipped complete in an earlier track — but with NO way to
 * reach it: the policy defaulted to DISABLED forever because no UI could
 * change it. This ViewModel completes the feature: the Settings screen
 * composes on it to read the LIVE policy ([AppLockService.policyFlow]) and
 * to apply every mutation through the AUTHORITATIVE [AppLockService.setPolicy]
 * (persisted + audited + fail-closed — never a UI-local toggle that could
 * drift from the state machine).
 *
 * Availability honesty: the device's real authentication capability
 * ([AppLockAuthenticator.checkAvailability]) is surfaced verbatim — enabling
 * the lock without an enrolled authenticator is allowed (the policy is a
 * preference) but the user SEES that the next lock episode will explain "no
 * authenticator enrolled" until they enroll one from the device settings.
 */
class AppLockSettingsViewModel(
    private val appLockService: AppLockService,
    private val authenticator: AppLockAuthenticator,
    /** Application context ONLY (BiometricManager works from it; no Activity). */
    private val appContext: Context?
) : ViewModel() {

    data class AppLockSettingsUiState(
        /** The LIVE policy straight from the state machine. */
        val policy: AppLockPolicy = AppLockPolicy.DISABLED,
        /** Whether the service has proven its persisted policy at process start. */
        val initialized: Boolean = false,
        /** The device's honest authentication capability (null = not probed). */
        val availability: AppLockAuthAvailability? = null,
        /** The honest error channel (a failed policy write). */
        val errorMessage: String? = null
    ) {
        val availabilityReady: Boolean get() = availability == AppLockAuthAvailability.READY
    }

    private val _state = MutableStateFlow(AppLockSettingsUiState())
    val state: StateFlow<AppLockSettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            appLockService.policyFlow.collect { policy ->
                _state.update { it.copy(policy = policy) }
            }
        }
        viewModelScope.launch {
            appLockService.initialized.collect { initialized ->
                _state.update { it.copy(initialized = initialized) }
            }
        }
        refreshAvailability()
    }

    /** Re-probes the device's real authentication capability. */
    fun refreshAvailability() {
        val context = appContext ?: return
        viewModelScope.launch {
            val availability = runCatching {
                authenticator.checkAvailability(context)
            }.getOrNull()
            _state.update { it.copy(availability = availability) }
        }
    }

    /**
     * The enable/disable master switch — through the AUTHORITATIVE service
     * (persisted + audited; enabling while the process session is not
     * authenticated locks IMMEDIATELY — the fail-closed contract).
     */
    fun setEnabled(enabled: Boolean) {
        applyPolicy(
            if (enabled) {
                AppLockPolicy(
                    mode = AppLockMode.AFTER_TIMEOUT,
                    timeout = AppLockTimeout.M1
                )
            } else {
                AppLockPolicy.DISABLED
            }
        )
    }

    /** Sets the lock scheduling mode (IMMEDIATE / AFTER_TIMEOUT). */
    fun setMode(mode: AppLockMode) {
        val current = _state.value.policy
        applyPolicy(
            current.copy(
                mode = mode,
                timeout = if (mode == AppLockMode.AFTER_TIMEOUT && current.timeout == AppLockTimeout.NONE) {
                    AppLockTimeout.M1
                } else if (mode == AppLockMode.IMMEDIATE) {
                    AppLockTimeout.NONE
                } else {
                    current.timeout
                }
            )
        )
    }

    /** Sets the re-lock timeout (the fixed product catalogue 30s/1m/5m/15m). */
    fun setTimeout(timeout: AppLockTimeout) {
        applyPolicy(_state.value.policy.copy(timeout = timeout))
    }

    private fun applyPolicy(policy: AppLockPolicy) {
        viewModelScope.launch {
            runCatching { appLockService.setPolicy(policy) }
                .onFailure { e ->
                    _state.update {
                        it.copy(errorMessage = "تعذر تحديث سياسة القفل: ${e.localizedMessage}")
                    }
                }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
