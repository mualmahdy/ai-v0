package com.example.convergence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.application.session.ConversationSessionService
import com.example.domain.core.session.ChatMode
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.repository.RoomConversationSessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * E2E ROOT-CAUSE REGRESSION — AppDatabaseSingletonProcessDeathTest
 * ============================================================================
 *
 * e2e-device.yml runs 1–15 ALL failed on the same step: the reopen phase of
 * DurableWorkspaceE2ETest.durableSession_survivesDatabaseReopen. Root cause
 * (diagnosed from the public CI evidence — green result-artifact upload, exit
 * code 1, 19.6 KB result XML — plus this code path):
 *
 *   AppDatabase.getInstance() is a process SINGLETON. The test models process
 *   death as first.close(), but close() does NOT clear the singleton — so the
 *   "restarted process" phase got the CLOSED cached instance back and its
 *   first query threw IllegalStateException.
 *
 * The JVM durability suites never saw this because they build their instances
 * directly (ProcessDeathRecoveryTest / ConversationSessionDurabilityTest use
 * private openDb() builders, bypassing the singleton); the e2e suite exercises
 * the PRODUCTION path (AppContainer → AppDatabase.getInstance) — correctly —
 * and therefore hit the missing memory-half of the kill simulation.
 *
 * This JVM mirror pins the FIXED contract on the production path:
 *
 *   close() + resetInstanceForProcessDeath() + getInstance()
 *     → returns a NEW open instance over the SAME durable file,
 *     → with the persisted session + turns intact.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseSingletonProcessDeathTest {

    private val productionDbName = "agent_orchestrator_platform.db"
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        // A FRESH process starts with EMPTY memory: earlier test classes in
        // this JVM may have left the process-singleton pointing at a closed
        // or deleted-file instance (e.g. Migration14to15AndDurableApprovalTest
        // deletes the file in finally without clearing the singleton). The
        // FIRST phase must own an instance built over THIS test's context.
        AppDatabase.resetInstanceForProcessDeath()
        context.deleteDatabase(productionDbName)
    }

    @After
    fun teardown() {
        // Leave the process-singleton and the file system clean for the rest
        // of the suite (Migration14to15AndDurableApprovalTest also uses
        // getInstance — the reset after every getInstance user keeps the
        // cross-test contract sound).
        AppDatabase.resetInstanceForProcessDeath()
        context.deleteDatabase(productionDbName)
    }

    @Test
    fun `getInstance after close and reset returns a NEW open instance with the durable data intact`() =
        runBlocking {
            context = ApplicationProvider.getApplicationContext<Context>()

            // ---- "First process": the PRODUCTION path (AppContainer uses
            //      getInstance) writes a session + one turn, then dies. ----
            val first = AppDatabase.getInstance(context)
            val service = ConversationSessionService(
                repository = RoomConversationSessionRepository(
                    database = first,
                    sessionDao = first.conversationSessionDao(),
                    turnDao = first.conversationTurnDao()
                ),
                workspaceIdProvider = { "ws_singleton" }
            )
            val session = service.createSession(mode = ChatMode.QUICK_CHAT)
            service.appendTurn(
                sessionId = session.id,
                prompt = "اختبار موت العملية",
                answer = "استجابة",
                agentName = "المحادثة السريعة",
                agentRole = "مساعد عام",
                modelResourceId = null,
                tokensConsumed = 12,
                durationMs = 40,
                isSuccessful = true,
                eventCount = 1
            )
            first.close() // ← the connection dies with the process.
            AppDatabase.resetInstanceForProcessDeath() // ← memory dies with it.

            // ---- "Restarted process": getInstance must build a FRESH open
            //      instance over the SAME durable file. ----
            val reopened = AppDatabase.getInstance(context)
            assertNotSame(
                "A restarted process must get a NEW instance — not the closed cached one",
                first,
                reopened
            )

            val loaded = RoomConversationSessionRepository(
                database = reopened,
                sessionDao = reopened.conversationSessionDao(),
                turnDao = reopened.conversationTurnDao()
            ).getSessionWithTurnsForWorkspace(session.id, "ws_singleton")

            assertNotNull("الجلسة يجب أن تنجو من موت العملية (إعادة فتح الملف نفسه)", loaded)
            assertEquals(1, loaded!!.turns.size)
            assertEquals("اختبار موت العملية", loaded.turns.first().prompt)
            reopened.close()
        }
}
