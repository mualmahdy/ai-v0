package com.example.gapclosure

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.execution.ActionIdempotencyService
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.execution.IntentGate
import com.example.infrastructure.persistence.dao.ActionIntentDao
import com.example.infrastructure.persistence.entities.ActionIntentEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * ActionIdempotencyServiceTest — gap-closure P0-06
 * ============================================================================
 *
 * Proves the ACTION IDEMPOTENCY PROTOCOL:
 *  - begin() records the durable intent (INTENDED);
 *  - complete() records the outcome + fingerprint;
 *  - a SECOND begin() after completion returns AlreadyCompleted (replay,
 *    never re-execute);
 *  - fail() records FAILED (retry allowed through the loop);
 *  - without a ledger the gate reports LedgerUnavailable honestly.
 */
@RunWith(RobolectricTestRunner::class)
class ActionIdempotencyServiceTest {

    private lateinit var db: com.example.infrastructure.persistence.AppDatabase
    private lateinit var dao: ActionIntentDao
    private lateinit var service: ActionIdempotencyService

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.actionIntentDao()
        service = ActionIdempotencyService(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun action() = DecisionAction(
        type = DecisionActionType.EXECUTE_TOOL,
        targetId = "workspace_file_tool",
        payload = mapOf("action" to "write", "path" to "src/Main.kt")
    )

    @Test
    fun `begin records a fresh INTENDED intent`() = runBlocking {
        val gate = service.begin("exec_1", stepIndex = 2, action = action())
        assertTrue("Fresh intent must be Proceed", gate is IntentGate.Proceed)
        assertEquals("INTENDED", (gate as IntentGate.Proceed).intent.state.name)
        assertEquals("s2|EXECUTE_TOOL|workspace_file_tool", gate.intent.actionKey)
    }

    @Test
    fun `completed intent replays instead of re-executing`() = runBlocking {
        // First attempt: begin -> execute -> complete.
        val first = service.begin("exec_2", 0, action())
        assertTrue(first is IntentGate.Proceed)
        service.complete("exec_2", 0, action(), outputText = "تم حفظ الملف بنجاح")

        // Crash + resume: begin again for the SAME action key.
        val second = service.begin("exec_2", 0, action())
        assertTrue(
            "A COMPLETED intent must gate as AlreadyCompleted (exactly-once)",
            second is IntentGate.AlreadyCompleted
        )
        val replay = second as IntentGate.AlreadyCompleted
        assertEquals("COMPLETED", replay.intent.state.name)
        assertEquals("تم حفظ الملف بنجاح", replay.intent.outputSummary)
        assertTrue("Fingerprint must be present for replay verification", replay.intent.outputFingerprint!!.isNotBlank())
    }

    @Test
    fun `failed intent allows retry (Proceed again)`() = runBlocking {
        service.begin("exec_3", 1, action())
        service.fail("exec_3", 1, action(), error = "TOOL_TIMEOUT")

        val gate = service.begin("exec_3", 1, action())
        assertTrue("A FAILED intent is safely retryable", gate is IntentGate.Proceed)
    }

    @Test
    fun `fingerprint is deterministic and content-sensitive`() {
        assertEquals(
            "Same output must produce the same fingerprint",
            service.fingerprint("output-A"),
            service.fingerprint("output-A")
        )
        assertTrue(
            "Different outputs must produce different fingerprints",
            service.fingerprint("output-A") != service.fingerprint("output-B")
        )
    }

    @Test
    fun `ledger-less mode is reported honestly`() = runBlocking {
        val ledgerless = ActionIdempotencyService(null)
        val gate = ledgerless.begin("exec_4", 0, action())
        assertTrue(
            "Without a ledger exactly-once cannot be claimed — must report LedgerUnavailable",
            gate is IntentGate.LedgerUnavailable
        )
    }

    @Test
    fun `completedIntentCount counts per execution`() = runBlocking {
        service.begin("exec_5", 0, action())
        service.complete("exec_5", 0, action(), "ok")
        service.begin("exec_5", 1, action())
        service.complete("exec_5", 1, action(), "ok")
        service.begin("exec_5", 2, action()) // still INTENDED
        assertEquals(2, service.completedCount("exec_5"))
        assertEquals(0, service.completedCount("exec_other"))
    }

    @Test
    fun `action key is stable for the same (step, type, target)`() = runBlocking {
        val a1 = action()
        val a2 = action()
        assertEquals(
            service.actionKey("exec_6", 3, a1),
            service.actionKey("exec_6", 3, a2)
        )
        assertFalse(
            "Different step index must produce a different key",
            service.actionKey("exec_6", 3, a1) == service.actionKey("exec_6", 4, a1)
        )
    }
}
