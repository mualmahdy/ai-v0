package com.example.infrastructure.network

import com.example.domain.core.network.NetworkPolicy
import okhttp3.Interceptor
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * EgressControl — REAL network-egress enforcement (report gap: "network
 * egress restrictions NOT FIXED" under sandbox isolation)
 * ============================================================================
 *
 * The report's sandbox-isolation audit lists network-egress restriction as a
 * missing control: resource-level OFFLINE filtering removed remote RESOURCES
 * from decisions, but nothing stopped a raw HTTP client from dialing out.
 *
 * EgressControl closes that hole at the transport layer:
 *
 *  - The composition root pins the ACTIVE workspace's [NetworkPolicy] here
 *    ([setActivePolicy]) — the same authority that drives resource
 *    filtering, so there is ONE policy, not two.
 *  - [interceptor] is installed on every outbound OkHttp client (MCP,
 *    OpenAI-compatible LLM, Gemini, Tavily/multi-source search). When the
 *    active policy is OFFLINE, the interceptor FAILS CLOSED with an
 *    explicit [EgressBlockedException] before any socket is opened.
 *  - Default is UNSET = allow (JVM tests, pre-bootstrap), matching the
 *    previous behaviour — but production always pins the policy within
 *    milliseconds of startup, and switching a workspace to OFFLINE takes
 *    effect IMMEDIATELY for in-flight NEW requests.
 *
 * This is fail-closed egress governance, not a UI promise.
 */
object EgressControl {

    class EgressBlockedException(
        val policy: String,
        val url: String
    ) : IOException(
        "EGRESS_BLOCKED: سياسة مساحة العمل النشطة ($policy) تمنع أي اتصال شبكي خارجي — رفض صريح قبل فتح أي مقبس. ($url)"
    )

    /** The pinned policy of the ACTIVE workspace (null = not yet pinned). */
    @Volatile
    private var activePolicy: NetworkPolicy? = null

    /** The workspace id the pinned policy belongs to (observability). */
    @Volatile
    private var pinnedWorkspaceId: String? = null

    /** Optional per-session hard blocks (sandbox egress restrictions). */
    private val sessionBlocks = ConcurrentHashMap<String, Boolean>()

    /**
     * Pins the active workspace's network policy. Called by the composition
     * root whenever the active workspace (or its policy) changes.
     */
    fun setActivePolicy(workspaceId: String?, policy: NetworkPolicy?) {
        pinnedWorkspaceId = workspaceId
        activePolicy = policy
    }

    /** Hard-blocks egress while a governed sandbox session demands it. */
    fun blockForSession(sessionId: String, blocked: Boolean) {
        if (blocked) sessionBlocks[sessionId] = true else sessionBlocks.remove(sessionId)
    }

    /** True when ANY session block or an OFFLINE active policy forbids egress. */
    fun isEgressAllowed(url: String): Boolean {
        if (sessionBlocks.containsValue(true)) return false
        val policy = activePolicy ?: return true // not yet pinned (tests/bootstrap)
        return policy != NetworkPolicy.OFFLINE
    }

    /** Fails closed when egress is not allowed. */
    fun assertEgressAllowed(url: String) {
        if (!isEgressAllowed(url)) {
            throw EgressBlockedException(
                policy = activePolicy?.name ?: "SESSION_BLOCK",
                url = url
            )
        }
    }

    /**
     * The OkHttp interceptor installed on EVERY outbound client (LLM,
     * search, MCP). Runs BEFORE the connection is opened — an OFFLINE
     * workspace can never dial out, regardless of which layer asked.
     */
    fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        assertEgressAllowed(request.url.toString())
        chain.proceed(request)
    }

    /** Observability: current pinned state (for diagnostics surfaces). */
    fun currentPinnedState(): Pair<String?, NetworkPolicy?> = pinnedWorkspaceId to activePolicy

    /** Test hook — resets to the pristine un-pinned state. */
    fun reset() {
        activePolicy = null
        pinnedWorkspaceId = null
        sessionBlocks.clear()
    }
}
