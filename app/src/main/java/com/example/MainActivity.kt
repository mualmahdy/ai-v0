package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import com.example.presentation.di.AppContainer
import com.example.presentation.di.FilesViewModelFactory
import com.example.presentation.di.GovernanceViewModelFactory
import com.example.presentation.di.KnowledgeViewModelFactory
import com.example.presentation.di.MainViewModelFactory
import com.example.presentation.di.ProvidersViewModelFactory
import com.example.presentation.di.SessionsViewModelFactory
import com.example.presentation.di.SettingsViewModelFactory
import com.example.presentation.di.StudioViewModelFactory
import com.example.presentation.di.TasksViewModelFactory
import com.example.presentation.ui.MainAppScreen
import com.example.presentation.viewmodel.FilesViewModel
import com.example.presentation.viewmodel.GovernanceViewModel
import com.example.presentation.viewmodel.KnowledgeViewModel
import com.example.presentation.viewmodel.MainViewModel
import com.example.presentation.viewmodel.ProvidersViewModel
import com.example.presentation.viewmodel.SessionsViewModel
import com.example.presentation.viewmodel.SettingsViewModel
import com.example.presentation.viewmodel.StudioSignal
import com.example.presentation.viewmodel.StudioViewModel
import com.example.presentation.viewmodel.TasksViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {

    private val appContainer: AppContainer by lazy {
        AppContainer(applicationContext)
    }

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(appContainer, studioSignalBus)
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
                            sessionsViewModel = sessionsViewModel,
                            knowledgeViewModel = knowledgeViewModel,
                            governanceViewModel = governanceViewModel,
                            providersViewModel = providersViewModel
                        )
                    }
                }
            }
        }
    }
}
