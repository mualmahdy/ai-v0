package com.example.gapclosure

import com.example.domain.core.network.NetworkPolicy
import com.example.infrastructure.network.EgressControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * ============================================================================
 * DEFECT FAMILY 1 REPAIR — EgressControlTest (scoped, fail-closed egress)
 * ============================================================================
 *
 * The PREVIOUS implementation (pinned by an earlier version of this test)
 * had three verified defects:
 *
 *  1. `sessionBlocks.containsValue(true)` — ONE blocked sandbox session
 *     denied egress for EVERY session and workspace (cross-session leak).
 *  2. `activePolicy ?: return true` — a missing policy ALLOWED egress
 *     (fail-OPEN), violating fail-closed semantics.
 *  3. Process-global mutable single-policy state — a mid-execution
 *     workspace switch silently re-targeted in-flight executions to
 *     another workspace's policy.
 *
 * The repaired EgressControl is an INSTANCE (injected by the composition
 * root) with a per-workspace policy REGISTRY, REQUEST-SCOPED decisions
 * (scope tag: workspaceId + optional sandbox sessionId), and FAIL-CLOSED
 * defaults. This test pins every repaired invariant:
 *
 *   1. OFFLINE workspace policy blocks egress before any socket.
 *   2. HYBRID/ONLINE policies allow egress.
 *   3. A session block denies ONLY requests carrying THAT session's scope —
 *      other sessions and workspaces are unaffected (no containsValue leak).
 *   4. FAIL-CLOSED: no pinned policy for the governing workspace → DENY
 *      (EGRESS_POLICY_MISSING); no scope and no active workspace → DENY
 *      (EGRESS_NO_PINNED_POLICY).
 *   5. Per-workspace policies: workspace A's policy does not leak into
 *      workspace B's decision; an execution pinned to A keeps A's policy
 *      after the ACTIVE workspace switches to B.
 */
@RunWith(RobolectricTestRunner::class)
class EgressControlTest {

    private lateinit var egress: EgressControl

    @Before
    fun setup() {
        egress = EgressControl()
    }

    @After
    fun reset() {
        egress.reset()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(egress.interceptor())
            .build()
    }

    private fun request(scope: EgressControl.EgressScopeTag? = null): Request {
        val builder = Request.Builder()
            .url("https://example.invalid/probe")
            .post("{}".toRequestBody("application/json".toMediaType()))
        if (scope != null) {
            builder.tag(EgressControl.EgressScopeTag::class.java, scope)
        }
        return builder.build()
    }

    private fun tag(workspaceId: String, sessionId: String? = null) =
        EgressControl.EgressScopeTag(workspaceId = workspaceId, sessionId = sessionId)

    // ---------------------------------------------------------------
    // Core semantics
    // ---------------------------------------------------------------

    @Test
    fun `offline workspace policy blocks egress before any socket`() {
        egress.pinWorkspacePolicy("ws_x", NetworkPolicy.OFFLINE)
        egress.setActiveWorkspace("ws_x")
        val decision = egress.decide(scope = tag("ws_x"), url = "https://example.invalid")
        assertTrue(decision is EgressControl.EgressDecision.Denied)
        assertEquals(
            "EGRESS_OFFLINE_POLICY",
            (decision as EgressControl.EgressDecision.Denied).reasonCode
        )
        try {
            client.newCall(request(tag("ws_x"))).execute()
            throw AssertionError("طلب شبكة كان يجب أن يُرفض قبل فتح المقبس")
        } catch (e: IOException) {
            assertTrue(e is EgressControl.EgressBlockedException)
            assertTrue(e.message!!.startsWith("EGRESS_BLOCKED"))
        }
    }

    @Test
    fun `hybrid policy allows egress`() {
        egress.pinWorkspacePolicy("ws_x", NetworkPolicy.HYBRID)
        egress.setActiveWorkspace("ws_x")
        assertTrue(egress.decide(scope = tag("ws_x"), url = "https://example.invalid") is EgressControl.EgressDecision.Allowed)
    }

    // ---------------------------------------------------------------
    // Defect 1: cross-session leak is ELIMINATED
    // ---------------------------------------------------------------

    @Test
    fun `a blocked session denies ONLY its own scope - no cross-session or cross-workspace leak`() {
        egress.pinWorkspacePolicy("ws_x", NetworkPolicy.HYBRID)
        egress.pinWorkspacePolicy("ws_y", NetworkPolicy.HYBRID)
        egress.setActiveWorkspace("ws_x")
        // ONE sandbox session in ws_x is blocked.
        egress.blockSession(workspaceId = "ws_x", sessionId = "sbx_blocked")

        // The blocked session's own scope: denied.
        assertTrue(
            egress.decide(scope = tag("ws_x", sessionId = "sbx_blocked"), url = "https://example.invalid")
                is EgressControl.EgressDecision.Denied
        )
        // A DIFFERENT session in the SAME workspace: ALLOWED (previous
        // implementation denied this via sessionBlocks.containsValue(true)).
        assertTrue(
            egress.decide(scope = tag("ws_x", sessionId = "sbx_other"), url = "https://example.invalid")
                is EgressControl.EgressDecision.Allowed
        )
        // Unscoped (user-driven) requests in the same workspace: ALLOWED.
        assertTrue(
            egress.decide(scope = null, url = "https://example.invalid")
                is EgressControl.EgressDecision.Allowed
        )
        // Another workspace entirely: ALLOWED.
        assertTrue(
            egress.decide(scope = tag("ws_y"), url = "https://example.invalid")
                is EgressControl.EgressDecision.Allowed
        )

        // Teardown releases the block (no stale leaks).
        egress.unblockSession("sbx_blocked")
        assertTrue(
            egress.decide(scope = tag("ws_x", sessionId = "sbx_blocked"), url = "https://example.invalid")
                is EgressControl.EgressDecision.Allowed
        )
    }

    // ---------------------------------------------------------------
    // Defect 2: fail-closed defaults
    // ---------------------------------------------------------------

    @Test
    fun `missing policy for the governing workspace fails CLOSED`() {
        egress.setActiveWorkspace("ws_x") // active but NO policy pinned
        val decision = egress.decide(scope = null, url = "https://example.invalid")
        assertTrue(decision is EgressControl.EgressDecision.Denied)
        assertEquals("EGRESS_POLICY_MISSING", (decision as EgressControl.EgressDecision.Denied).reasonCode)
    }

    @Test
    fun `no scope and no active workspace fails CLOSED`() {
        val decision = egress.decide(scope = null, url = "https://example.invalid")
        assertTrue(decision is EgressControl.EgressDecision.Denied)
        assertEquals("EGRESS_NO_PINNED_POLICY", (decision as EgressControl.EgressDecision.Denied).reasonCode)
    }

    @Test
    fun `scoped request to an un-pinned workspace fails CLOSED`() {
        egress.pinWorkspacePolicy("ws_x", NetworkPolicy.HYBRID)
        val decision = egress.decide(scope = tag("ws_unpinned"), url = "https://example.invalid")
        assertTrue(decision is EgressControl.EgressDecision.Denied)
        assertEquals("EGRESS_POLICY_MISSING", (decision as EgressControl.EgressDecision.Denied).reasonCode)
    }

    // ---------------------------------------------------------------
    // Defect 3: per-workspace policies — no process-global leak
    // ---------------------------------------------------------------

    @Test
    fun `workspace policies are isolated - offline ws_y does not affect ws_x`() {
        egress.pinWorkspacePolicy("ws_x", NetworkPolicy.HYBRID)
        egress.pinWorkspacePolicy("ws_y", NetworkPolicy.OFFLINE)
        egress.setActiveWorkspace("ws_y")
        // ws_y is offline...
        assertTrue(
            egress.decide(scope = tag("ws_y"), url = "https://example.invalid")
                is EgressControl.EgressDecision.Denied
        )
        // ...but a request SCOPED to ws_x keeps ws_x's policy.
        assertTrue(
            egress.decide(scope = tag("ws_x"), url = "https://example.invalid")
                is EgressControl.EgressDecision.Allowed
        )
    }

    @Test
    fun `an execution pinned to ws_x keeps ws_x policy after the active workspace switches`() {
        egress.pinWorkspacePolicy("ws_x", NetworkPolicy.HYBRID)
        egress.setActiveWorkspace("ws_x")
        // Mid-execution the user switches the ACTIVE workspace to OFFLINE ws_y.
        egress.pinWorkspacePolicy("ws_y", NetworkPolicy.OFFLINE)
        egress.setActiveWorkspace("ws_y")
        // The still-running execution pinned to ws_x keeps ITS policy.
        assertTrue(
            egress.decide(scope = tag("ws_x"), url = "https://example.invalid")
                is EgressControl.EgressDecision.Allowed
        )
        // Unscoped (new user-driven) requests now follow ws_y.
        assertTrue(
            egress.decide(scope = null, url = "https://example.invalid")
                is EgressControl.EgressDecision.Denied
        )
    }

    @Test
    fun `unpinning a workspace policy fails closed for its scoped requests`() {
        egress.pinWorkspacePolicy("ws_x", NetworkPolicy.HYBRID)
        egress.setActiveWorkspace("ws_x")
        assertTrue(
            egress.decide(scope = tag("ws_x"), url = "https://example.invalid")
                is EgressControl.EgressDecision.Allowed
        )
        egress.pinWorkspacePolicy("ws_x", null)
        val decision = egress.decide(scope = tag("ws_x"), url = "https://example.invalid")
        assertTrue(decision is EgressControl.EgressDecision.Denied)
        assertEquals("EGRESS_POLICY_MISSING", (decision as EgressControl.EgressDecision.Denied).reasonCode)
    }

    @Test
    fun `fresh standalone instance is fail-closed (not allow-by-default)`() {
        val standalone = EgressControl()
        val decision = standalone.decide(scope = null, url = "https://example.invalid")
        assertTrue(decision is EgressControl.EgressDecision.Denied)
        assertEquals("EGRESS_NO_PINNED_POLICY", (decision as EgressControl.EgressDecision.Denied).reasonCode)
    }
}
