package com.example.gapclosure

import com.example.application.execution.ExecutionHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * ExecutionHostMultiExecutionTest — gap-closure P0-01
 * ============================================================================
 *
 * Proves the execution host is a MULTI-EXECUTION REGISTRY:
 *  - launching a second execution does NOT cancel the first;
 *  - cancel(key) cancels EXACTLY one execution;
 *  - activeExecutions reflects the live registry;
 *  - re-launching the same key replaces only that key's job.
 */
class ExecutionHostMultiExecutionTest {

    private fun awaitTrue(timeoutMs: Long = 3000, check: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!check() && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(20)
        }
    }

    @Test
    fun `launching a second execution does not cancel the first`() = runBlocking {
        val firstCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
        val secondCompleted = java.util.concurrent.atomic.AtomicBoolean(false)

        ExecutionHost.launch("task_A") {
            delay(300)
            firstCompleted.set(true)
        }
        ExecutionHost.launch("task_B") {
            delay(300)
            secondCompleted.set(true)
        }

        awaitTrue { firstCompleted.get() && secondCompleted.get() }
        assertTrue("First execution must complete (previously launch() cancelled it)", firstCompleted.get())
        assertTrue("Second execution must complete", secondCompleted.get())
        awaitTrue { ExecutionHost.activeExecutions.value.isEmpty() }
    }

    @Test
    fun `cancel cancels exactly one execution`() = runBlocking {
        val aCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val bCompleted = java.util.concurrent.atomic.AtomicBoolean(false)

        val jobA = ExecutionHost.launch("task_A") {
            try {
                delay(2000)
            } catch (e: kotlinx.coroutines.CancellationException) {
                aCancelled.set(true)
                throw e
            }
        }
        ExecutionHost.launch("task_B") {
            delay(400)
            bCompleted.set(true)
        }

        awaitTrue { ExecutionHost.isExecuting("task_B") }
        ExecutionHost.cancel("task_A")
        awaitTrue { aCancelled.get() }
        assertTrue("cancel(task_A) must cancel task_A only", aCancelled.get())

        awaitTrue { bCompleted.get() }
        assertTrue("task_B must keep running after task_A was cancelled", bCompleted.get())
        assertFalse(jobA.isActive)
        awaitTrue { !ExecutionHost.isExecuting("task_A") }
    }

    @Test
    fun `activeExecutions registry tracks live keys`() = runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        ExecutionHost.launch("tracked_task") {
            release.await()
        }
        awaitTrue { ExecutionHost.isExecuting("tracked_task") }
        assertTrue(
            "Registry must contain the live execution key",
            ExecutionHost.activeExecutions.value.containsKey("tracked_task")
        )
        release.countDown()
        awaitTrue { !ExecutionHost.isExecuting("tracked_task") }
        awaitTrue { !ExecutionHost.activeExecutions.value.containsKey("tracked_task") }
    }

    @Test
    fun `relaunching the same key replaces only that key`() = runBlocking {
        val firstCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val secondCompleted = java.util.concurrent.atomic.AtomicBoolean(false)

        ExecutionHost.launch("same_key") {
            try {
                delay(2000)
            } catch (e: kotlinx.coroutines.CancellationException) {
                firstCancelled.set(true)
                throw e
            }
        }
        ExecutionHost.launch("same_key") {
            delay(150)
            secondCompleted.set(true)
        }

        awaitTrue { firstCancelled.get() && secondCompleted.get() }
        assertTrue("Re-launch of the same key replaces that key's previous job", firstCancelled.get())
        assertTrue(secondCompleted.get())
        awaitTrue { ExecutionHost.activeExecutions.value.isEmpty() }
        assertEquals(0, ExecutionHost.activeExecutions.value.size)
    }
}
