package com.example.infrastructure.platform.applock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.example.application.applock.AppLockAuthAvailability
import com.example.domain.core.security.applock.AppLockAuthOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ============================================================================
 * BiometricAppLockAuthenticatorTest — the pure platform mappings (JVM)
 * ============================================================================
 *
 * Robolectric 4.16 ships no BiometricManager/BiometricPrompt shadows, so the
 * prompt itself is exercised on-device (connectedAndroidTest territory — not
 * available in this environment); what IS unit-testable here is the exact
 * honesty of the two mappings that guard the lock's fail-closed behavior:
 *
 *  - availability: every non-SUCCESS status is an honest NON-unlock answer;
 *  - prompt errors: ONLY the user's own dismissals map to CANCELLED — every
 *    hardware/system/lockout/enrollment condition maps to FAILED and can
 *    never unlock the app.
 */
class BiometricAppLockAuthenticatorTest {

    @Test
    fun `availability mapping is honest for every BiometricManager status`() {
        assertEquals(AppLockAuthAvailability.READY, AvailabilityMapper.fromCanAuthenticate(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(AppLockAuthAvailability.NONE_ENROLLED, AvailabilityMapper.fromCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertEquals(AppLockAuthAvailability.NO_HARDWARE, AvailabilityMapper.fromCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertEquals(AppLockAuthAvailability.HARDWARE_UNAVAILABLE, AvailabilityMapper.fromCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
        assertEquals(
            "unknown statuses are UNSUPPORTED, never READY",
            AppLockAuthAvailability.UNSUPPORTED,
            AvailabilityMapper.fromCanAuthenticate(-1)
        )
    }

    @Test
    fun `only the user's dismissals map to CANCELLED`() {
        assertEquals(AppLockAuthOutcome.CANCELLED, PromptErrorMapper.fromErrorCode(BiometricPrompt.ERROR_USER_CANCELED))
        assertEquals(AppLockAuthOutcome.CANCELLED, PromptErrorMapper.fromErrorCode(BiometricPrompt.ERROR_NEGATIVE_BUTTON))
        assertEquals(AppLockAuthOutcome.CANCELLED, PromptErrorMapper.fromErrorCode(BiometricPrompt.ERROR_CANCELED))
    }

    @Test
    fun `every hardware and system condition maps to FAILED (no bypass)`() {
        val nonUserCancelled = listOf(
            BiometricPrompt.ERROR_HW_UNAVAILABLE,
            BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
            BiometricPrompt.ERROR_TIMEOUT,
            BiometricPrompt.ERROR_NO_SPACE,
            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_VENDOR,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
            BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
            -999 // unknown future code — still fail-closed
        )
        nonUserCancelled.forEach { code ->
            assertEquals(
                "error code $code must be an honest FAILED, never an unlock",
                AppLockAuthOutcome.FAILED,
                PromptErrorMapper.fromErrorCode(code)
            )
        }
    }
}
