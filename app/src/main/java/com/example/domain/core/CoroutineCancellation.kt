package com.example.domain.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Cancellation-safe companion for broad `catch` clauses.
 *
 * In Kotlin coroutines, cancellation is delivered as a [CancellationException]
 * thrown from the next suspension point. A broad `catch (e: Exception)` (or
 * `Throwable`) around suspending code therefore also catches the cancellation
 * itself. If the handler turns it into an ordinary failure value — an
 * `Outcome.Error`, an `ExecutionEvent.Error`, a FAILED task row — then:
 *
 *  - the caller's cancel is silently reclassified as a provider/step failure;
 *  - the coroutine keeps running after being cancelled (structured concurrency
 *    is broken: parents wait for work that was told to stop);
 *  - in a `flow { }` builder, emitting from the handler after a downstream
 *    operator such as `first()`/`take()` aborted the flow violates exception
 *    transparency and throws an unrelated [IllegalStateException].
 *
 * Call this as the FIRST statement of every broad catch clause that can run in
 * a coroutine context:
 *
 * ```
 * try {
 *     suspendingCall()
 * } catch (e: Exception) {
 *     e.rethrowIfCancellation()
 *     Outcome.Error(...)
 * }
 * ```
 *
 * Behaviour:
 *  - [CancellationException] (including `TimeoutCancellationException` and the
 *    internal flow-abort exception) is rethrown unchanged — same instance.
 *  - Every other [Throwable] is left alone, so the handler proceeds normally.
 *
 * A caller that wants to convert a `withTimeout` expiry into a value must catch
 * `TimeoutCancellationException` in a clause placed BEFORE the broad one; that
 * clause is then reached first and this call is never made for it.
 *
 * This is deliberately NOT a `suspend` function: it is usable from plain
 * lambdas and non-suspend helpers without changing their signatures.
 */
fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}

/**
 * Cancellation-aware companion for catches around THIRD-PARTY suspending I/O
 * (Room's suspend DAOs are the canonical case in this codebase).
 *
 * Why this exists (HOTFIX — the CI unit-test failure after d8cf5c0):
 * Room delivers "the database was closed while the suspend insert ran" AS a
 * [CancellationException]/[JobCancellationException] thrown from its own
 * coroutine machinery. That is NOT the caller's cancellation — nobody
 * cancelled the business coroutine — but a plain
 * [rethrowIfCancellation] rethrows it anyway, which:
 *
 *  - breaks the honest-outage contract of the catch (the failure is no
 *    longer counted/surfaced — the audit-trail observability test pins
 *    exactly this); and
 *  - injects a FALSE cancellation into the caller: a perfectly healthy
 *    coroutine dies with JobCancellationException, which is strictly worse
 *    for structured concurrency than the swallow it replaced.
 *
 * This suspend variant rethrows ONLY when the CURRENT coroutine is genuinely
 * cancelled (its job is no longer active). A CancellationException arriving
 * while the current job is still active is by definition a foreign signal
 * (closed executor/pool) and is left for the handler to classify as an
 * honest failure.
 *
 * Use [rethrowIfCancellation] in catches that only guard YOUR OWN coroutine
 * machinery; use this wherever the guarded call goes through a third-party
 * suspend bridge that may leak cancellation semantics (Room, and any
 * library that owns its own dispatcher).
 */
suspend fun Throwable.rethrowIfGenuineCancellation() {
    if (this is CancellationException &&
        !currentCoroutineContext().isActive
    ) {
        throw this
    }
}
