package com.example.testing

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ============================================================================
 * DeterministicAwait — the shared STATE-FLOW await helper for real-stack
 * test suites (the closure of the documented load-flakiness family)
 * ============================================================================
 *
 * WHY THIS EXISTS
 * ---------------
 * The slice-6 documented load-flakiness family (ProjectsViewModelTest,
 * with the ProvidersViewModelTest 5s precedents): the old helper POLLED a
 * StateFlow's `.value` snapshot on wall-clock time (`Thread.sleep` in a
 * spin loop). Under full-suite `--rerun-tasks` load (850+ tests, single
 * Gradle JVM, serial GC) a Room invalidation emission can lag past ANY
 * fixed polling window — the timeout was raised 5s → 15s → 30s and the
 * family still dropped once per full run, while isolated reruns stayed
 * green. Polling wall-clock snapshots is structurally load-sensitive: it
 * depends on the TEST thread being scheduled often enough to observe a
 * TRANSIENT window, and on the producer landing inside that window.
 *
 * THE DETERMINISTIC FIX
 * ---------------------
 * Await the flow ITSELF, not a snapshot of it: `first { condition }`
 * SUSPENDS on the flow and resumes the moment an emission satisfies the
 * condition. There is no polling window to miss — the wait completes
 * whenever the terminal state lands, however loaded the machine is. The
 * emission is delivered by the producer's own thread (Room's query
 * executor / Dispatchers.IO), so blocking the test thread is safe — the
 * real-stack suites already block it inside `runBlocking` for direct DAO
 * reads.
 *
 * All awaited conditions in the migrated suites are STICKY terminal
 * states (the list stays grown, the binding stays moved, the error stays
 * set), so StateFlow conflation cannot hide them: a new collector always
 * receives the current value first, and every subsequent update is
 * delivered while actively collecting.
 *
 * The timeout is ONLY a hang guard (a genuine bug where the condition
 * never becomes true must fail the test instead of hanging the build
 * forever); it plays no role in load tolerance. On timeout the failure
 * carries the LAST OBSERVED VALUE — the diagnostic the old family never
 * had.
 */
fun <T> StateFlow<T>.awaitWhere(
    timeoutMs: Long = 60_000L,
    condition: (T) -> Boolean,
): T = runBlocking {
    withTimeoutOrNull(timeoutMs) {
        first { condition(it) }
    } ?: throw AssertionError(
        "awaitWhere: condition not satisfied within ${timeoutMs}ms " +
            "(this is a hang guard, not a load budget — the awaited flow never " +
            "reached the expected terminal state; last observed value: $value)"
    )
}
