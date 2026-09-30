package com.example.domain

import com.example.domain.core.rethrowIfCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Contract of [rethrowIfCancellation] — the single helper that keeps broad
 * `catch (e: Exception)` clauses from swallowing coroutine cancellation.
 *
 * All waiting is done on the coroutine primitives themselves
 * ([CompletableDeferred], [cancelAndJoin]) — no wall-clock polling.
 */
class CoroutineCancellationTest {

    @Test
    fun `rethrows a CancellationException as the same instance`() {
        val original = CancellationException("cancelled by user")
        try {
            original.rethrowIfCancellation()
            fail("expected the CancellationException to be rethrown")
        } catch (thrown: CancellationException) {
            assertSame(original, thrown)
        }
    }

    @Test
    fun `leaves ordinary throwables alone`() {
        // None of these may throw: the handler must proceed normally.
        java.io.IOException("io").rethrowIfCancellation()
        IllegalStateException("state").rethrowIfCancellation()
        RuntimeException("runtime").rethrowIfCancellation()
        java.net.SocketTimeoutException("socket timeout").rethrowIfCancellation()
        AssertionError("error").rethrowIfCancellation()
    }

    @Test
    fun `a withTimeout expiry is also a cancellation and is rethrown`() = runBlocking {
        // Documented contract: TimeoutCancellationException IS a CancellationException.
        // Code that wants to turn a timeout into a value must catch it in a clause
        // placed BEFORE the broad one (see the helper's KDoc).
        var captured: Throwable? = null
        try {
            withTimeout(1) { delay(60_000) }
        } catch (e: TimeoutCancellationException) {
            captured = e
        }
        val timeout = captured
        assertTrue("withTimeout must have expired", timeout is TimeoutCancellationException)
        try {
            timeout!!.rethrowIfCancellation()
            fail("expected the timeout cancellation to be rethrown")
        } catch (thrown: CancellationException) {
            assertSame(timeout, thrown)
        }
    }

    @Test
    fun `a cancelled coroutine does not run the failure branch when the helper is used`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var failureBranchRan = false

        val job = launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                failureBranchRan = true
            }
        }

        started.await()
        job.cancelAndJoin()

        assertTrue("the job must end as cancelled", job.isCancelled)
        assertFalse("cancellation must not be reclassified as a failure", failureBranchRan)
    }

    @Test
    fun `control - without the helper a broad catch swallows the cancellation`() = runBlocking {
        // Proves the test above is sensitive to the defect it guards against.
        val started = CompletableDeferred<Unit>()
        var failureBranchRan = false

        val job = launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } catch (e: Exception) {
                failureBranchRan = true
            }
        }

        started.await()
        job.cancelAndJoin()

        assertTrue("without the helper the failure branch is (wrongly) reached", failureBranchRan)
    }

    @Test
    fun `a flow with the helper survives first() aborting it`() = runBlocking {
        // first() aborts the upstream flow with an internal CancellationException
        // subtype. The handler below must let it through instead of emitting.
        val values = flow {
            try {
                emit(1)
                emit(2)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                emit(-1)
            }
        }
        assertEquals(1, values.first())
    }
}
