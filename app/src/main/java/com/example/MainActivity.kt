package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.fragment.app.FragmentActivity
import com.example.presentation.di.AppContainer
import com.example.presentation.di.ActivityViewModelFactory
import com.example.presentation.di.AgentsViewModelFactory
import com.example.presentation.di.ExtensionsViewModelFactory
import com.example.presentation.di.FilesViewModelFactory
import com.example.presentation.di.GovernanceViewModelFactory
import com.example.presentation.di.KnowledgeViewModelFactory
import com.example.presentation.di.MainViewModelFactory
import com.example.presentation.di.ProvidersViewModelFactory
import com.example.presentation.di.ProjectsViewModelFactory
import com.example.presentation.di.RadarViewModelFactory
import com.example.presentation.di.DecisionViewModelFactory
import com.example.presentation.di.ChatCapabilitiesViewModelFactory
import com.example.presentation.di.SessionsViewModelFactory
import com.example.presentation.di.SettingsViewModelFactory
import com.example.presentation.di.StudioViewModelFactory
import com.example.presentation.di.TasksViewModelFactory
import com.example.presentation.di.WorkflowsViewModelFactory
import com.example.presentation.ui.MainAppScreen
import com.example.presentation.ui.screens.applock.AppLockGate
import com.example.presentation.viewmodel.ActivityViewModel
import com.example.presentation.viewmodel.AgentsViewModel
import com.example.presentation.viewmodel.ExtensionsViewModel
import com.example.presentation.viewmodel.FilesViewModel
import com.example.presentation.viewmodel.GovernanceViewModel
import com.example.presentation.viewmodel.KnowledgeViewModel
import com.example.presentation.viewmodel.MainViewModel
import com.example.presentation.viewmodel.ProvidersViewModel
import com.example.presentation.viewmodel.ProjectsViewModel
import com.example.presentation.viewmodel.RadarViewModel
import com.example.presentation.viewmodel.DecisionViewModel
import com.example.presentation.viewmodel.WorkflowsViewModel
import com.example.presentation.viewmodel.SessionsViewModel
import com.example.presentation.viewmodel.SettingsViewModel
import com.example.presentation.viewmodel.StudioSignal
import com.example.presentation.viewmodel.ChatCapabilitiesViewModel
import com.example.presentation.viewmodel.StudioViewModel
import com.example.presentation.viewmodel.TasksViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * APP LOCK (backend) — the Activity is a THIN platform adapter: it forwards
 * lifecycle transitions to [AppContainer.appLockService] and shows the SYSTEM
 * authentication prompt through [AppContainer.appLockAuthenticator]. NO
 * policy logic, timeout arithmetic, persistence, or security decisions live
 * here (they belong to the service/adapter). FragmentActivity because the
 * official androidx BiometricPrompt requires a fragment host — a real
 * technical constraint of the platform prompt, not an architecture change.
 */
class MainActivity : FragmentActivity() {

    private val appContainer: AppContainer by lazy {
        AppContainer(applicationContext)
    }

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(appContainer)
    }

    // GAP-11 (Design Closure 2026): the tasks feature ViewModel — the
    // resumable-task board lives in its OWN feature VM (ADR-6 freeze on
    // growing MainViewModel).
    private val tasksViewModel: TasksViewModel by viewModels {
        TasksViewModelFactory(appContainer)
    }

    // ADR-6 slice 1 (Design Closure 2026 UI-redesign track): the FILES and
    // SETTINGS feature ViewModels — the sandbox explorer and the workspace
    // manager left the MainViewModel (same freeze rule).
    private val filesViewModel: FilesViewModel by viewModels {
        FilesViewModelFactory(appContainer)
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModelFactory(appContainer)
    }

    // ADR-6 SLICE 2 (Design Closure 2026 UI-redesign track): the STUDIO and
    // SESSIONS feature ViewModels — the conversation runtime and the
    // durable-session registry left the MainViewModel (same freeze rule).
    // The per-Activity studio signal bus connects them: StudioViewModel
    // emits cross-feature projections (execution events, session policy
    // changes); MainViewModel collects them into its display mirrors.
    private val studioSignalBus = MutableSharedFlow<StudioSignal>(extraBufferCapacity = 256)

    private val studioViewModel: StudioViewModel by viewModels {
        StudioViewModelFactory(appContainer, studioSignalBus)
    }

    // CHAT CAPABILITIES (Task 2): the chat feature's capability layer —
    // availability catalog, attachment drafts (SAF→sandbox→artifact), and
    // the governed tool/skill/MCP/search/knowledge invocation state.
    private val chatCapabilitiesViewModel: ChatCapabilitiesViewModel by viewModels {
        ChatCapabilitiesViewModelFactory(appContainer)
    }

    private val sessionsViewModel: SessionsViewModel by viewModels {
        SessionsViewModelFactory(appContainer)
    }

    // ADR-6 slice 3 (Design Closure 2026 UI-redesign track): the KNOWLEDGE
    // feature ViewModel — the RAG knowledge base, the local semantic engine
    // (previously-shared semanticModelReady), and the long-term memory
    // browser left the MainViewModel (same freeze rule).
    private val knowledgeViewModel: KnowledgeViewModel by viewModels {
        KnowledgeViewModelFactory(appContainer)
    }

    // ADR-6 slice 4 (Design Closure 2026 UI-redesign track): the GOVERNANCE
    // feature ViewModel — the observatory (capability radar + economic
    // budget) and the human approval surface left the MainViewModel (same
    // freeze rule). Collects the studio bus's network-policy changes for
    // the radar snapshot (its own display mirror).
    private val governanceViewModel: GovernanceViewModel by viewModels {
        GovernanceViewModelFactory(appContainer, studioSignalBus)
    }

    // ADR-6 slice 5 (Design Closure 2026 UI-redesign track): the PROVIDERS
    // feature ViewModel — the provider & resource control room (control-
    // plane flows, first-run provider bootstrap seeding, connect wizard,
    // credential dialog) left the MainViewModel (same freeze rule). Its
    // init triggers the idempotent provider bootstrap seeding exactly where
    // MainViewModel's init used to.
    private val providersViewModel: ProvidersViewModel by viewModels {
        ProvidersViewModelFactory(appContainer)
    }

    // ADR-6 slice 6 (Design Closure 2026 UI-redesign track): the RADAR
    // feature ViewModel — the intelligence radar & evolution observatory
    // left the MainViewModel (same freeze rule).
    private val radarViewModel: RadarViewModel by viewModels {
        RadarViewModelFactory(appContainer)
    }

    // ADR-6 slice 6 (Design Closure 2026 UI-redesign track): the DECISION
    // feature ViewModel — the CBR-MDP cockpit left the MainViewModel (same
    // freeze rule). It COLLECTS the decision share of the studio signal bus
    // itself (the governance pattern: each feature its own stake).
    private val decisionViewModel: DecisionViewModel by viewModels {
        DecisionViewModelFactory(appContainer, studioSignalBus)
    }

    // ADR-6 slice 6 (Design Closure 2026 UI-redesign track): the WORKFLOWS
    // feature ViewModel — the plan builder, durable library and resume
    // surface left the MainViewModel (same freeze rule). The TASK BOARD
    // stays with TasksViewModel (its owner since GAP-11).
    private val workflowsViewModel: WorkflowsViewModel by viewModels {
        WorkflowsViewModelFactory(appContainer)
    }

    // ADR-6 slice 7 (Design Closure 2026 UI-redesign track): the AGENTS
    // feature ViewModel — the durable agent catalog + the runtime
    // registration seam left the MainViewModel (same freeze rule). The
    // Studio picker, the Tasks builder and the Explorer row compose on it.
    private val agentsViewModel: AgentsViewModel by viewModels {
        AgentsViewModelFactory(appContainer)
    }

    // ADR-6 slice 7 (Design Closure 2026 UI-redesign track): the
    // EXTENSIONS feature ViewModel — the MCP + skills + plugins +
    // integrations control room left the MainViewModel (same freeze rule).
    private val extensionsViewModel: ExtensionsViewModel by viewModels {
        ExtensionsViewModelFactory(appContainer)
    }

    // ADR-6 slice 7 (Design Closure 2026 UI-redesign track): the ACTIVITY
    // feature ViewModel — the unified activity feed (per-execution trace +
    // workspace-scoped audit events) left the MainViewModel (same freeze
    // rule). It COLLECTS the activity stake of the studio signal bus itself
    // (the live execution id — the governance pattern).
    private val activityViewModel: ActivityViewModel by viewModels {
        ActivityViewModelFactory(appContainer, studioSignalBus)
    }

    // UI Design Closure (phase B — D-01): the PROJECTS feature ViewModel —
    // the real project-management surface (the active-projects list, the
    // honest current-project binding mirror, create/switch/rename/archive/
    // trash through the AUTHORITATIVE services). Follows the same ADR-6
    // freeze rule as every feature VM above.
    private val projectsViewModel: ProjectsViewModel by viewModels {
        ProjectsViewModelFactory(appContainer)
    }

    // ------------------------------------------------------------------
    // APP LOCK (backend) — thin platform integration ONLY
    // ------------------------------------------------------------------

    /** The last system-prompt availability seen (honest cover hints). */
    @Volatile
    private var appLockAvailabilityHint: String? = null

    /**
     * Forwards the app-to-background transition (single-activity app: the
     * Activity's onStop IS the app backgrounding — no new dependencies).
     */
    override fun onStop() {
        super.onStop()
        appContainer.appLockService.onAppBackgrounded()
    }

    /** Forwards the app-to-foreground transition and triggers the prompt. */
    override fun onStart() {
        super.onStart()
        appContainer.appLockService.onAppForegrounded()
        requestAppUnlock()
    }

    /**
     * Shows the SYSTEM authentication prompt when the STATE MACHINE asks for
     * it (fail-closed: availability problems keep the lock — they are honest
     * non-successes, never a bypass).
     */
    private fun requestAppUnlock() {
        val service = appContainer.appLockService
        if (!service.initialized.value) return // policy not proven yet — no prompt
        if (service.currentState.value !=
            com.example.domain.core.security.applock.AppLockState.AUTHENTICATION_REQUIRED
        ) {
            return // the machine decides when a prompt is demanded
        }
        val availability = appContainer.appLockAuthenticator.checkAvailability(this)
        appLockAvailabilityHint = when (availability) {
            com.example.application.applock.AppLockAuthAvailability.READY -> null
            com.example.application.applock.AppLockAuthAvailability.NONE_ENROLLED ->
                "لا يوجد أسلوب مصادقة مسجل على الجهاز (بصمة قوية أو قفل الشاشة) — سجّل أحدهما من إعدادات الجهاز ثم أعد المحاولة."
            com.example.application.applock.AppLockAuthAvailability.NO_HARDWARE ->
                "الجهاز لا يدعم المصادقة الحيوية، ولا يوجد قفل شاشة بديل متاح."
            com.example.application.applock.AppLockAuthAvailability.HARDWARE_UNAVAILABLE ->
                "جهاز المصادقة غير متاح حالياً — أعد المحاولة بعد قليل."
            com.example.application.applock.AppLockAuthAvailability.UNSUPPORTED ->
                "مزيج المصادقة المطلوب غير مدعوم على هذا الجهاز."
        }
        // Fail-closed: an unavailable authenticator keeps the app LOCKED (the
        // cover explains why); the state machine is not asked to authenticate.
        if (availability != com.example.application.applock.AppLockAuthAvailability.READY) return
        if (!service.beginAuthentication()) return
        appContainer.appLockAuthenticator.launchPrompt(this) { outcome ->
            service.onAuthenticationResult(outcome)
            if (outcome != com.example.domain.core.security.applock.AppLockAuthOutcome.SUCCESS) {
                // Re-arm the cover hint on the next attempt.
                appLockAvailabilityHint = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // FIX P2 (audit c03919d): FLAG_SECURE — the app renders live model
        // streams, user prompts, workspace files, and (masked) credential
        // dialogs; screenshots/recents previews must not capture them.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        // Audit 2026 fix: actually RUN the runtime bootstrap (previously dead
        // code) — adapter restore, MDP Q-table load, memory decay, and
        // process-death task recovery now happen on every app start.
        appContainer.bootstrapRuntime()
        setContent {
            MyApplicationTheme {
                // The app is Arabic-first (all user-facing copy is Arabic), so the
                // layout direction is pinned to RTL regardless of device locale —
                // mirrors, paddings and navigation follow the reading direction.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    // APP LOCK GATE: while the state machine demands
                    // authentication the app content is NOT composed at all
                    // (fail-closed cover; the system prompt is launched through
                    // the thin adapter above — no security logic here).
                    AppLockGate(
                        lockStateFlow = appContainer.appLockService.currentState,
                        initializedFlow = appContainer.appLockService.initialized,
                        availabilityText = { appLockAvailabilityHint },
                        onUnlockRequested = { requestAppUnlock() }
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            MainAppScreen(
                                viewModel = viewModel,
                                tasksViewModel = tasksViewModel,
                                filesViewModel = filesViewModel,
                                settingsViewModel = settingsViewModel,
                                studioViewModel = studioViewModel,
                                chatCapabilitiesViewModel = chatCapabilitiesViewModel,
                                sessionsViewModel = sessionsViewModel,
                                knowledgeViewModel = knowledgeViewModel,
                                governanceViewModel = governanceViewModel,
                                providersViewModel = providersViewModel,
                                radarViewModel = radarViewModel,
                                decisionViewModel = decisionViewModel,
                                workflowsViewModel = workflowsViewModel,
                                agentsViewModel = agentsViewModel,
                                extensionsViewModel = extensionsViewModel,
                                activityViewModel = activityViewModel,
                                projectsViewModel = projectsViewModel
                            )
                        }
                    }
                }
            }
        }
    }
}
