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
import com.example.presentation.di.MainViewModelFactory
import com.example.presentation.ui.MainAppScreen
import com.example.presentation.viewmodel.MainViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val appContainer: AppContainer by lazy {
        AppContainer(applicationContext)
    }

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(appContainer)
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
                        MainAppScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}
