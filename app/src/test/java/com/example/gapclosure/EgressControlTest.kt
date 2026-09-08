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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * ============================================================================
 * REPORT GAP-CLOSURE — EgressControlTest
 * ============================================================================
 *
 * Report verdict: "network egress isolation NOT FIXED" (sandbox isolation
 * section) — resource filtering removed remote resources from DECISIONS,
 * but nothing stopped a raw HTTP client from dialing out.
 *
 * This test pins the transport-layer guard:
 *  1. An OFFLINE active workspace FAILS CLOSED on every outbound request.
 *  2. HYBRID/ONLINE policies allow egress.
 *  3. Per-session sandbox blocks deny egress even under HYBRID.
 *  4. Un-pinned state (tests / pre-bootstrap) allows (previous behaviour).
 */
@RunWith(RobolectricTestRunner::class)
class EgressControlTest {

    @After
    fun reset() {
        EgressControl.reset()
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(EgressControl.interceptor())
        .build()

    private val request = Request.Builder()
        .url("https://example.invalid/probe")
        .post("{}".toRequestBody("application/json".toMediaType()))
        .build()

    @Test
    fun `offline workspace policy blocks egress before any socket`() {
        EgressControl.setActivePolicy("ws_x", NetworkPolicy.OFFLINE)
        assertFalse(EgressControl.isEgressAllowed("https://example.invalid"))
        try {
            client.newCall(request).execute()
            throw AssertionError("طلب شبكة كان يجب أن يُرفض قبل فتح المقبس")
        } catch (e: IOException) {
            assertTrue(e is EgressControl.EgressBlockedException)
            assertEquals("EGRESS_BLOCKED", e.message?.substringBefore(":"))
        }
    }

    @Test
    fun `hybrid policy allows egress`() {
        EgressControl.setActivePolicy("ws_x", NetworkPolicy.HYBRID)
        assertTrue(EgressControl.isEgressAllowed("https://example.invalid"))
    }

    @Test
    fun `session sandbox block denies egress even under hybrid policy`() {
        EgressControl.setActivePolicy("ws_x", NetworkPolicy.HYBRID)
        EgressControl.blockForSession("sbx_1", blocked = true)
        assertFalse(EgressControl.isEgressAllowed("https://example.invalid"))
        EgressControl.blockForSession("sbx_1", blocked = false)
        assertTrue(EgressControl.isEgressAllowed("https://example.invalid"))
    }

    @Test
    fun `unpinned state allows egress (tests and pre-bootstrap parity)`() {
        assertTrue(EgressControl.isEgressAllowed("https://example.invalid"))
    }
}
