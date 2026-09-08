package com.example.infrastructure.network

import com.example.domain.core.execution.ExecutionScope
import com.example.domain.core.network.NetworkPolicy
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Interceptor
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * EgressControl — SCOPED network-egress enforcement (fail-closed)
 * ============================================================================
 *
 * REPAIR (cumulative correctness & authority order, defect family 1):
 * the previous implementation was a process-global mutable `object` whose
 * decisions leaked across unrelated flows:
 *
 *   1. `sessionBlocks.containsValue(true)` denied egress for EVERY session
 *      and workspace whenever ANY single sandbox session was blocked.
 *   2. `activePolicy ?: return true` allowed all egress whenever no policy
 *      had been pinned yet (fail-OPEN).
 *   3. A single mutable `activePolicy` field mixed the concerns of "which
 *      workspace is active" with "which policy governs THIS request", so a
 *      mid-execution workspace switch silently re-targeted in-flight
 *      executions to another workspace's policy.
 *
 * The repaired design:
 *
 *   - INSTANCE state, injected by the composition root into every outbound
 *     adapter (LLM, search, MCP). No process-global mutable singleton —
 *     tests construct their own instance, production shares exactly one
 *     composition-root-owned instance.
 *   - Per-workspace policy REGISTRY: every observed workspace keeps its
 *     pinned [NetworkPolicy], so an execution pinned to workspace A keeps
 *     A's policy even after the user switches the active workspace to B.
 *   - REQUEST-SCOPED decisions: adapters stamp the governing
 *     [EgressScopeTag] (workspaceId + optional sessionId) onto each OkHttp
 *     request from the [ExecutionScope] coroutine element pinned by the
 *     orchestrator. The interceptor evaluates ONLY that scope — one blocked
 *     sandbox session can never affect unrelated sessions or workspaces.
 *   - FAIL-CLOSED: a request whose workspace has no pinned policy is DENIED
 *     (`EGRESS_POLICY_MISSING`), and a request with neither scope nor active
 *     workspace fallback is DENIED (`EGRESS_NO_PINNED_POLICY`). Egress is
 *     only ever ALLOWED by an explicit ONLINE/HYBRID policy lookup.
 *   - Session blocks are (workspaceId, sessionId) pairs: they deny only
 *     requests whose own scope carries that session id.
 *
 * Unscoped requests (user-driven provider health checks, discovery — paths
 * that run outside an execution) resolve against the ACTIVE workspace's
 * pinned policy; when no active workspace is pinned they fail closed.
 */
class EgressControl {

    /** Explicit, machine-readable deny reason (auditable, testable). */
    sealed interface EgressDecision {
        data object Allowed : EgressDecision
        data class Denied(val reasonCode: String, val detail: String) : EgressDecision
    }

    class EgressBlockedException(
        val reasonCode: String,
        val scopeDescription: String,
        val url: String
    ) : IOException(
        "EGRESS_BLOCKED[$reasonCode]: $scopeDescription — رفض صريح قبل فتح أي مقبس. ($url)"
    )

    /**
     * Request-level governing scope: the workspace (and optionally the
     * sandbox session) a request belongs to. Derived from the execution's
     * pinned [ExecutionScope]; stamped onto the OkHttp request as a tag.
     */
    data class EgressScopeTag(
        val workspaceId: String,
        val sessionId: String? = null
    ) {
        val description: String
            get() = "workspace=$workspaceId" + (sessionId?.let { ", session=$it" } ?: "")
    }

    /** WorkspaceId -> pinned policy (registry of ALL observed workspaces). */
    private val workspacePolicies = ConcurrentHashMap<String, NetworkPolicy>()

    /** The workspace whose policy governs UN-scoped (user-driven) requests. */
    @Volatile
    private var activeWorkspaceId: String? = null

    /** Session-scoped hard blocks: sessionId -> owning workspaceId. */
    private val blockedSessions = ConcurrentHashMap<String, String>()

    // ------------------------------------------------------------------
    // Composition-root wiring (single authority: the workspace runtime)
    // ------------------------------------------------------------------

    /** Pins (or clears, when null) a workspace's network policy. */
    fun pinWorkspacePolicy(workspaceId: String, policy: NetworkPolicy?) {
        if (policy == null) workspacePolicies.remove(workspaceId)
        else workspacePolicies[workspaceId] = policy
    }

    /** Sets the workspace governing UN-scoped requests (null = none pinned). */
    fun setActiveWorkspace(workspaceId: String?) {
        activeWorkspaceId = workspaceId
    }

    /** Hard-blocks egress for ONE sandbox session (scoped — never global). */
    fun blockSession(workspaceId: String, sessionId: String) {
        blockedSessions[sessionId] = workspaceId
    }

    /** Releases a session block (sandbox teardown — prevents stale leaks). */
    fun unblockSession(sessionId: String) {
        blockedSessions.remove(sessionId)
    }

    // ------------------------------------------------------------------
    // Decision
    // ------------------------------------------------------------------

    /** The decision used by [interceptor] — exposed for tests/diagnostics. */
    fun decide(request: Request): EgressDecision {
        val tag = request.tag(EgressScopeTag::class.java)
        return decide(scope = tag, url = request.url.toString())
    }

    fun decide(scope: EgressScopeTag?, url: String): EgressDecision {
        // 1. Resolve the governing workspace for THIS request.
        val governingWorkspace = scope?.workspaceId ?: activeWorkspaceId
            ?: return EgressDecision.Denied(
                "EGRESS_NO_PINNED_POLICY",
                "لا يوجد نطاق تنفيذ ولا مساحة عمل نشطة مثبتة — الرفض الافتراضي (fail-closed)."
            )

        // 2. The workspace's policy MUST exist (fail-closed, not allow).
        val policy = workspacePolicies[governingWorkspace]
            ?: return EgressDecision.Denied(
                "EGRESS_POLICY_MISSING",
                "سياسة الشبكة لمساحة العمل '$governingWorkspace' غير مثبتة — الرفض الافتراضي (fail-closed)."
            )

        // 3. Session blocks apply ONLY to the request's OWN session scope.
        val session = scope?.sessionId
        if (session != null && blockedSessions[session] != null) {
            return EgressDecision.Denied(
                "EGRESS_SESSION_BLOCKED",
                "جلسة الصندوق الرملي $session (مساحة $governingWorkspace) تمنع أي اتصال خارجي."
            )
        }

        // 4. OFFLINE policy denies before any socket is opened.
        if (policy == NetworkPolicy.OFFLINE) {
            return EgressDecision.Denied(
                "EGRESS_OFFLINE_POLICY",
                "سياسة مساحة العمل '$governingWorkspace' هي OFFLINE."
            )
        }

        return EgressDecision.Allowed
    }

    /** Fails closed when egress is not allowed. */
    fun assertEgressAllowed(request: Request) {
        when (val decision = decide(request)) {
            is EgressDecision.Denied -> throw EgressBlockedException(
                reasonCode = decision.reasonCode,
                scopeDescription = decision.detail,
                url = request.url.toString()
            )
            EgressDecision.Allowed -> Unit
        }
    }

    // ------------------------------------------------------------------
    // Adapter integration
    // ------------------------------------------------------------------

    /**
     * The OkHttp interceptor installed on EVERY outbound client (LLM,
     * search, MCP). Runs BEFORE the connection is opened — an OFFLINE or
     * un-pinned workspace can never dial out, regardless of which layer
     * asked.
     */
    fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        assertEgressAllowed(request)
        chain.proceed(request)
    }

    /**
     * Stamps the governing [EgressScopeTag] onto a request builder from the
     * CURRENT coroutine's pinned [ExecutionScope]. Adapters call this when
     * building every outbound request so the decision is scoped to the
     * pinned execution, not to whatever workspace is currently active.
     */
    suspend fun applyEgressScope(builder: Request.Builder): Request.Builder {
        val executionScope = currentCoroutineContext()[ExecutionScope.Key]
        if (executionScope != null) {
            builder.tag(
                EgressScopeTag::class.java,
                EgressScopeTag(
                    workspaceId = executionScope.workspaceId,
                    sessionId = executionScope.sessionId
                )
            )
        }
        return builder
    }

    /**
     * Pre-flight egress decision for NON-OkHttp transports (raw
     * HttpURLConnection adapters — model discovery/embeddings). Resolves the
     * scope from the current coroutine's [ExecutionScope] exactly like
     * [applyEgressScope], so EVERY outbound transport obeys the SAME policy
     * (no bypass path).
     */
    suspend fun assertEgressAllowedForCurrentScope(url: String) {
        val executionScope = currentCoroutineContext()[ExecutionScope.Key]
        val tag = executionScope?.let { EgressScopeTag(it.workspaceId, it.sessionId) }
        when (val decision = decide(scope = tag, url = url)) {
            is EgressDecision.Denied -> throw EgressBlockedException(
                reasonCode = decision.reasonCode,
                scopeDescription = decision.detail,
                url = url
            )
            EgressDecision.Allowed -> Unit
        }
    }

    /** Observability: current pinned state (for diagnostics surfaces). */
    fun currentPinnedState(): Pair<String?, NetworkPolicy?> {
        val active = activeWorkspaceId
        return active to active?.let { workspacePolicies[it] }
    }

    /** Test hook — resets to the pristine un-pinned state. */
    fun reset() {
        workspacePolicies.clear()
        activeWorkspaceId = null
        blockedSessions.clear()
    }

    companion object {
        /**
         * Standalone instance used as the constructor default of outbound
         * adapters (JVM tests / direct construction). It is UN-pinned and
         * therefore fails CLOSED by design — production must inject the
         * composition-root instance with pinned workspace policies.
         */
        val default: EgressControl = EgressControl()
    }
}
