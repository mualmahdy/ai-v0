package com.example.domain.core

import kotlinx.coroutines.CancellationException

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
