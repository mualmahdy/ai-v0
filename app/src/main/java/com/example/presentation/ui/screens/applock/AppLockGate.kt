package com.example.presentation.ui.screens.applock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.domain.core.security.applock.AppLockState
import kotlinx.coroutines.flow.StateFlow

/**
 * ============================================================================
 * APP LOCK GATE — the shell's lock cover (UI ONLY, zero policy logic)
 * ============================================================================
 *
 * The Compose-side platform integration of the app-lock backend: while the
 * [AppLockService][com.example.application.applock.AppLockService] state
 * machine demands authentication, the app content is NOT composed at all
 * (the strongest possible hiding — combined with MainActivity's FLAG_SECURE
 * nothing of the workspace is rendered or capturable while locked). All
 * decisions — when to lock, timeout arithmetic, policy, authentication —
 * stay in the service and the platform adapter; this composable only
 * RENDERS the state and forwards the user's tap.
 *
 * `uninitialized` keeps the cover (without a prompt) until the persisted
 * policy has been loaded — fail-closed against the cold-start race where
 * content would otherwise render before an enabled policy is proven.
 */
@Composable
fun AppLockGate(
    lockStateFlow: StateFlow<AppLockState>,
    initializedFlow: StateFlow<Boolean>,
    availabilityText: () -> String?,
    onUnlockRequested: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val lockState by lockStateFlow.collectAsState()
    val initialized by initializedFlow.collectAsState()

    // The app content is composed ONLY while genuinely unlocked.
    if (lockState == AppLockState.UNLOCKED && initialized) {
        content()
        return
    }

    // The cover. While the policy is still loading there is nothing to ask
    // the user yet (no prompt — fail-closed, honest silence).
    if (initialized) {
        // ONE auto-prompt attempt per lock episode: the system
        // authentication prompt appears as soon as the cover does; a retry
        // after failure/cancel is the user's explicit tap.
        val attempted = remember(lockStateFlow) { arrayOf(false) }
        LaunchedEffect(lockState, initialized) {
            if (lockState == AppLockState.AUTHENTICATION_REQUIRED && !attempted[0]) {
                attempted[0] = true
                onUnlockRequested()
            }
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "التطبيق مقفل",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "أكّد هويتك عبر مصادقة النظام (بصمة قوية أو بيانات قفل الجهاز) للمتابعة.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(24.dp))
            when {
                !initialized -> CircularProgressIndicator()
                lockState == AppLockState.AUTHENTICATING -> CircularProgressIndicator()
                else -> {
                    availabilityText()?.let { hint ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    Button(
                        onClick = onUnlockRequested,
                        modifier = Modifier.fillMaxWidth(0.7f)
                    ) {
                        Text("فتح التطبيق")
                    }
                }
            }
        }
    }
}
