package com.example.domain.core.runtime

/**
 * ============================================================================
 * Sandbox Lifecycle Domain Models — Phase 1 (Secure Runtime)
 * ============================================================================
 *
 * Honest sandbox semantics for an Android in-app agent runtime.
 *
 * Lifecycle: REQUESTED -> PROVISIONING -> READY -> RUNNING -> TERMINATING -> DESTROYED
 * Failure path: any state -> FAILED (terminal, reported honestly).
 *
 * HONESTY CONTRACT (critical):
 *  Android application processes CANNOT provide true process-level or
 *  network-level isolation for in-process code execution. This model forces
 *  every sandbox to declare its ACTUAL isolation level. A sandbox that is
 *  merely "inside the app data directory" reports
 *  [IsolationLevel.APP_SANDBOX_BEST_EFFORT] — NEVER [IsolationLevel.TRUE_ISOLATION].
 *  Admission for code-execution tools requires true isolation by default;
 *  with best-effort isolation they are DENIED with an explicit reason unless
 *  the operator policy explicitly opts in.
 */

enum class SandboxLifecycleState(val code: String) {
    REQUESTED("REQUESTED"),
    PROVISIONING("PROVISIONING"),
    READY("READY"),
    RUNNING("RUNNING"),
    TERMINATING("TERMINATING"),
    DESTROYED("DESTROYED"),
    FAILED("FAILED")
}

/** Network egress policy enforced (conceptually) inside the sandbox. */
enum class SandboxNetworkPolicy(val code: String, val displayLabelAr: String) {
    NO_NETWORK("NO_NETWORK", "لا اتصال شبكي"),
    LOCAL_ONLY("LOCAL_ONLY", "شبكة محلية فقط"),
    ALLOWLIST("ALLOWLIST", "قائمة سماح صريحة"),
    FULL_NETWORK("FULL_NETWORK", "شبكة كاملة")
}

/**
 * The ACTUAL isolation guarantee a sandbox instance can provide.
 * Reported honestly — never upgraded to please a requesting tool.
 */
enum class IsolationLevel(val code: String, val displayLabelAr: String) {
    /** True OS-level process + network isolation. NOT achievable in-app on Android. */
    TRUE_ISOLATION("TRUE_ISOLATION", "عزل حقيقي على مستوى العملية"),

    /** Android per-app sandbox (UID + app data dir). Best-effort only:
     *  no process boundary, no network firewall, no syscall filter. */
    APP_SANDBOX_BEST_EFFORT("APP_SANDBOX_BEST_EFFORT", "عزل التطبيق (أفضل جهد)"),

    /** No isolation at all. */
    NONE("NONE", "لا عزل")
}

/** Hard resource limits for one sandbox session. */
data class SandboxResourceLimits(
    val wallClockTimeoutMs: Long = 30_000L,
    val cpuTimeoutMs: Long = 10_000L,
    val memoryLimitBytes: Long = 64L * 1024 * 1024,
    val maxProcesses: Int = 1,
    val maxThreads: Int = 4,
    val maxOutputBytes: Long = 512L * 1024,
    val maxFileCount: Int = 200,
    val networkPolicy: SandboxNetworkPolicy = SandboxNetworkPolicy.NO_NETWORK
) {
    companion object {
        val Default = SandboxResourceLimits()
    }
}

/** A provisioned sandbox session with its lifecycle and honest guarantees. */
data class SandboxSession(
    val sessionId: String,
    val state: SandboxLifecycleState = SandboxLifecycleState.REQUESTED,
    val limits: SandboxResourceLimits = SandboxResourceLimits.Default,
    val isolationLevel: IsolationLevel,
    val workspaceId: String,
    val requestedBy: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val stateHistory: List<Pair<SandboxLifecycleState, Long>> = emptyList(),
    val failureReason: String? = null
)

/** Structured sandbox failures. */
sealed interface SandboxFailure {
    data class IllegalTransition(val from: SandboxLifecycleState, val to: SandboxLifecycleState) : SandboxFailure
    data class SessionNotFound(val sessionId: String) : SandboxFailure
    data class InsufficientIsolation(val required: IsolationLevel, val available: IsolationLevel, val message: String) : SandboxFailure
    data class LimitViolated(val limitName: String, val observedValue: String, val message: String) : SandboxFailure
    data class ProvisioningFailed(val reason: String) : SandboxFailure
}

/** Legal transition table — the single source of truth for lifecycle moves. */
object SandboxTransitions {
    private val legal: Map<SandboxLifecycleState, Set<SandboxLifecycleState>> = mapOf(
        SandboxLifecycleState.REQUESTED to setOf(SandboxLifecycleState.PROVISIONING, SandboxLifecycleState.FAILED, SandboxLifecycleState.DESTROYED),
        SandboxLifecycleState.PROVISIONING to setOf(SandboxLifecycleState.READY, SandboxLifecycleState.FAILED, SandboxLifecycleState.DESTROYED),
        SandboxLifecycleState.READY to setOf(SandboxLifecycleState.RUNNING, SandboxLifecycleState.TERMINATING, SandboxLifecycleState.FAILED, SandboxLifecycleState.DESTROYED),
        SandboxLifecycleState.RUNNING to setOf(SandboxLifecycleState.TERMINATING, SandboxLifecycleState.FAILED, SandboxLifecycleState.DESTROYED),
        SandboxLifecycleState.TERMINATING to setOf(SandboxLifecycleState.DESTROYED, SandboxLifecycleState.FAILED),
        SandboxLifecycleState.DESTROYED to emptySet(),
        SandboxLifecycleState.FAILED to emptySet()
    )

    fun isLegal(from: SandboxLifecycleState, to: SandboxLifecycleState): Boolean =
        legal[from]?.contains(to) == true

    val terminalStates: Set<SandboxLifecycleState> = setOf(SandboxLifecycleState.DESTROYED, SandboxLifecycleState.FAILED)
}
