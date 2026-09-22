package com.example.infrastructure.platform.applock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.application.applock.AppLockAuthAvailability
import com.example.application.applock.AppLockAuthenticator
import com.example.domain.core.security.applock.AppLockAuthOutcome
import java.util.concurrent.Executor

/**
 * ============================================================================
 * BIOMETRIC APP LOCK AUTHENTICATOR — the Android platform adapter
 * ============================================================================
 *
 * The ONLY Android-specific piece of the app-lock backend: it shows the
 * SYSTEM authentication prompt (androidx.biometric) configured with the
 * product's fixed authenticator class:
 *
 *   BIOMETRIC_STRONG | DEVICE_CREDENTIAL
 *
 * — the user authenticates with a Class-3 (strong) fingerprint/face OR the
 * device's own PIN/pattern/password. This adapter performs NO
 * authentication of its own, stores nothing, and reports SUCCESS only when
 * the SYSTEM reports a successful authentication; hardware/unrollment/
 * cancellation conditions are honest non-successes (fail-closed — they can
 * never unlock the app).
 *
 * Prompt note: no negative-button text is set — with DEVICE_CREDENTIAL
 * among the allowed authenticators the system supplies its own cancel
 * affordance (supplying one is rejected on newer API levels).
 */
class BiometricAppLockAuthenticator : AppLockAuthenticator {

    override fun checkAvailability(context: Context): AppLockAuthAvailability =
        AvailabilityMapper.fromCanAuthenticate(
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        )

    override fun launchPrompt(
        activity: FragmentActivity,
        onOutcome: (AppLockAuthOutcome) -> Unit
    ) {
        val executor: Executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // Both allowed authenticators are equivalent product-wise:
                // strong biometric OR device credential — a system success IS
                // the unlock (there is no app-side credential to compare).
                onOutcome(AppLockAuthOutcome.SUCCESS)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onOutcome(PromptErrorMapper.fromErrorCode(errorCode))
            }

            // Soft failures (fingerprint not recognized, …) keep the prompt
            // alive; the terminal outcome arrives via onAuthenticationError
            // or onAuthenticationSucceeded only — a soft failure is NEVER a
            // SUCCESS and NEVER a bypass.
        }
        val prompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("فتح التطبيق")
            .setSubtitle("هذه الجلسة محمية بقفل التطبيق")
            .setDescription("أكّد هويتك ببصمة قوية أو ببيانات قفل الجهاز (رقم سري/نقش/كلمة مرور).")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(promptInfo)
    }
}

/**
 * Pure mapping of [BiometricManager.canAuthenticate] statuses — JVM-testable
 * without a device (the status codes are compile-time constants).
 */
internal object AvailabilityMapper {
    fun fromCanAuthenticate(status: Int): AppLockAuthAvailability = when (status) {
        BiometricManager.BIOMETRIC_SUCCESS -> AppLockAuthAvailability.READY
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AppLockAuthAvailability.NONE_ENROLLED
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> AppLockAuthAvailability.NO_HARDWARE
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> AppLockAuthAvailability.HARDWARE_UNAVAILABLE
        else -> AppLockAuthAvailability.UNSUPPORTED
    }
}

/**
 * Pure mapping of [BiometricPrompt] error codes — JVM-testable without a
 * device. Every hardware/system condition lands on FAILED (honest, no
 * bypass); only the USER's own dismissals land on CANCELLED.
 */
internal object PromptErrorMapper {
    fun fromErrorCode(errorCode: Int): AppLockAuthOutcome = when (errorCode) {
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED -> AppLockAuthOutcome.CANCELLED
        else -> AppLockAuthOutcome.FAILED
    }
}
