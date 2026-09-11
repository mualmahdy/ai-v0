package com.example.infrastructure.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * ============================================================================
 * GovernedHttpClientFactory — the ONE authority for outbound HTTP clients
 * (GAP-03 / ADR-3, Design Closure 2026)
 * ============================================================================
 *
 * AUDIT FINDING (GAP-03, P0): four production egress paths built private
 * OkHttpClients with NO EgressControl interceptor (resource validation
 * "Test Connection", MCP adapter JSON-RPC, Radar GitHub/RSS discovery,
 * ONNX semantic-model download), so an OFFLINE workspace could still emit
 * network traffic — the fail-closed egress contract was bypassable. A
 * fifth site (DiscoveryAdapterFactory) kept a globally mutable static
 * `egressControl` var, contradicting the instance-injection design.
 *
 * REPAIR (ADR-3, option b): this factory is the single constructor of
 * outbound clients. EVERY client it produces installs the EgressControl
 * interceptor FIRST, so a policy deny aborts the call before any socket is
 * opened, no matter which layer asked. The four bypass sites now obtain
 * their clients from here; the static var is gone (constructor injection).
 *
 * The remaining legacy adapters that build their own builder INLINE
 * (Gemini, OpenAI-compatible x2, MultiSourceSearch, IntegrationGateway,
 * McpClient, Tavily) already install `egressControl.interceptor()`
 * themselves — they are frozen as-is (documented allowlist) and
 * `GovernedEgressPerimeterTest` fails if any NEW direct
 * `OkHttpClient.Builder()` appears outside the factory/allowlist.
 *
 * Constructor default follows the repo convention: `EgressControl.default`
 * is UN-pinned and therefore fails CLOSED — production must inject the
 * composition-root factory (AppContainer.governedHttpClientFactory).
 */
class GovernedHttpClientFactory(
    private val egressControl: EgressControl = EgressControl.default
) {

    /**
     * Creates a governed client. The egress interceptor is ALWAYS installed —
     * there is no API to create an ungoverned client from this factory.
     *
     * @param connectTimeoutSeconds connection establishment budget.
     * @param readTimeoutSeconds     response read budget.
     * @param writeTimeoutSeconds    request write budget.
     */
    fun create(
        connectTimeoutSeconds: Long = DEFAULT_CONNECT_TIMEOUT_SECONDS,
        readTimeoutSeconds: Long = DEFAULT_READ_TIMEOUT_SECONDS,
        writeTimeoutSeconds: Long = DEFAULT_WRITE_TIMEOUT_SECONDS
    ): OkHttpClient = OkHttpClient.Builder()
        // EGRESS ENFORCEMENT — first interceptor in the chain: a policy deny
        // (OFFLINE workspace, unpinned policy, blocked session) throws
        // EgressBlockedException before the connection is dialed.
        .addInterceptor(egressControl.interceptor())
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 10L
        const val DEFAULT_READ_TIMEOUT_SECONDS = 30L
        const val DEFAULT_WRITE_TIMEOUT_SECONDS = 30L
    }
}
