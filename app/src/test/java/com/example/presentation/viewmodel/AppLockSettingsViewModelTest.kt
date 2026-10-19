package com.example.presentation.viewmodel

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.application.applock.AppLockAuthAvailability
import com.example.application.applock.AppLockAuthenticator
import com.example.application.applock.AppLockService
import com.example.domain.core.security.applock.AppLockMode
import com.example.domain.core.security.applock.AppLockPolicy
import com.example.domain.core.security.applock.AppLockState
import com.example.domain.core.security.applock.AppLockTimeout
import com.example.domain.ports.security.AppLockSettingsPort
import com.example.infrastructure.preferences.AppLockSettingsDataStore
import com.example.testing.awaitWhere
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * AppLockSettingsViewModelTest — STAGE 7 (the lock's REAL settings surface)
 * ============================================================================
 *
 * The app-lock backend shipped complete (service + DataStore + BiometricPrompt
 * adapter + the shell gate) but with NO reachable UI — the policy defaulted
 * to DISABLED forever. This suite pins the ViewModel that closes the loop:
 *
 *  - the settings surface reads the LIVE policy flow (the STAGE-7
 *    [AppLockService.policyFlow] completion);
 *  - enabling through the settings switch goes through the AUTHORITATIVE
 *    [AppLockService.setPolicy] (persisted to the REAL DataStore file and
 *    reloaded by a FRESH service — policy durability, not a UI-local
 *    toggle), with the fail-closed behavior (an un-authenticated process
 *    session locks immediately);
 *  - mode + timeout mutations compose the policy (IMMEDIATE clears the
 *    timeout; AFTER_TIMEOUT with no timeout picks the 1-minute default);
 *  - the device's honest authenticator availability projects into the
 *    state (a fake READY/NO_HARDWARE authenticator).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppLockSettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var dataStoreFile: File
    private lateinit var settings: AppLockSettingsDataStore
    private lateinit var service: AppLockService
    private lateinit var viewModel: AppLockSettingsViewModel

    /** The deterministic availability fake (the platform adapter's seam). */
    private class FakeAuthenticator(private val availability: AppLockAuthAvailability) : AppLockAuthenticator {
        override fun checkAvailability(context: Context): AppLockAuthAvailability = availability
        override fun launchPrompt(activity: FragmentActivity, onOutcome: (com.example.domain.core.security.applock.AppLockAuthOutcome) -> Unit) {
            // Never called in these tests — availability only.
        }
    }

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        dataStoreFile = File(context.filesDir, "test_applock_settings_${System.nanoTime()}.preferences_pb")
        settings = AppLockSettingsDataStore(dataStoreFile)
        service = AppLockService(settings = settings, auditTrail = null)
        service.initialize()
        viewModel = AppLockSettingsViewModel(
            appLockService = service,
            authenticator = FakeAuthenticator(AppLockAuthAvailability.READY),
            appContext = context
        )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        dataStoreFile.delete()
        Dispatchers.resetMain()
    }

    @Test
    fun `the settings surface reads the live policy flow`() = runBlocking {
        viewModel.state.awaitWhere { it.initialized }
        assertEquals(AppLockPolicy.DISABLED, viewModel.state.value.policy)
        assertEquals(AppLockAuthAvailability.READY, viewModel.state.value.availability)
    }

    @Test
    fun `enabling through the switch persists a real policy - a fresh service reloads it`() = runBlocking {
        viewModel.state.awaitWhere { it.initialized }

        viewModel.setEnabled(true)
        viewModel.state.awaitWhere { it.policy.enabled }

        val enabled = viewModel.state.value.policy
        // The sane default the settings surface picks: AFTER_TIMEOUT/1m.
        assertEquals(AppLockMode.AFTER_TIMEOUT, enabled.mode)
        assertEquals(AppLockTimeout.M1, enabled.timeout)

        // Fail-closed: enabling while the process session was NOT
        // authenticated locks immediately (the service contract).
        assertEquals(AppLockState.AUTHENTICATION_REQUIRED, service.currentState.value)

        // DURABILITY: a FRESH service over the SAME file reloads the policy
        // (the write went through the authoritative setPolicy, not a
        // UI-local toggle).
        val freshService = AppLockService(settings = settings, auditTrail = null)
        freshService.initialize()
        assertEquals(enabled, freshService.policyFlow.value)
    }

    @Test
    fun `IMMEDIATE mode clears the timeout and AFTER_TIMEOUT without one picks a minute`() = runBlocking {
        viewModel.state.awaitWhere { it.initialized }
        viewModel.setEnabled(true)
        viewModel.state.awaitWhere { it.policy.enabled }

        viewModel.setMode(AppLockMode.IMMEDIATE)
        viewModel.state.awaitWhere { it.policy.mode == AppLockMode.IMMEDIATE }
        assertEquals(AppLockTimeout.NONE, viewModel.state.value.policy.timeout)

        // Back to AFTER_TIMEOUT with the cleared timeout → the 1m default.
        viewModel.setMode(AppLockMode.AFTER_TIMEOUT)
        viewModel.state.awaitWhere { it.policy.mode == AppLockMode.AFTER_TIMEOUT }
        assertEquals(AppLockTimeout.M1, viewModel.state.value.policy.timeout)

        // The fixed catalogue accepts the 15m choice.
        viewModel.setTimeout(AppLockTimeout.M15)
        viewModel.state.awaitWhere { it.policy.timeout == AppLockTimeout.M15 }
        assertEquals(AppLockTimeout.M15, viewModel.state.value.policy.timeout)
    }

    @Test
    fun `disabling unlocks immediately and persists the disabled policy`() = runBlocking {
        viewModel.state.awaitWhere { it.initialized }
        viewModel.setEnabled(true)
        viewModel.state.awaitWhere { it.policy.enabled }

        viewModel.setEnabled(false)
        viewModel.state.awaitWhere { !it.policy.enabled }
        assertEquals(AppLockState.UNLOCKED, service.currentState.value)

        val loaded = settings.loadPolicy()
        assertTrue(loaded.isDisabled())
    }

    @Test
    fun `a device without hardware surfaces its honest availability`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = AppLockSettingsViewModel(
            appLockService = service,
            authenticator = FakeAuthenticator(AppLockAuthAvailability.NO_HARDWARE),
            appContext = context
        )
        vm.state.awaitWhere { it.availability != null }
        assertEquals(AppLockAuthAvailability.NO_HARDWARE, vm.state.value.availability)
        assertTrue(!vm.state.value.availabilityReady)
        vm.viewModelScope.cancel()
        assertNotNull(vm)
    }
}
