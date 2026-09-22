package com.example.convergence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.session.ConversationSessionService
import com.example.domain.core.session.ChatMode
import com.example.domain.core.session.ConversationSessionId
import com.example.domain.ports.session.ConversationSessionRepositoryPort
import com.example.infrastructure.persistence.repository.RoomConversationSessionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ============================================================================
 * REPORT GAP-CLOSURE — ConversationSessionDurabilityTest
 * ============================================================================
 *
 * Report verdict: "Sessions NOT FIXED — the legacy session infrastructure
 * was DELETED without a durable replacement; the transcript lived only in
 * the ViewModel and died with the process."
 *
 * This test pins the restored durable session system:
 *  1. Sessions are WORKSPACE-scoped from birth (no cross-workspace bleed).
 *  2. Turn appends bump session aggregates transactionally (counters never
 *     drift from persisted turns).
 *  3. PROCESS DEATH: closing the Room database and reopening it preserves
 *     sessions AND full turn history (kill → reopen → resume conversation).
 *  4. Mode + model pins survive restarts (Quick Chat session identity).
 *  5. Deletes cascade to turns.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationSessionDurabilityTest {

    private lateinit var dbFile: File
    private lateinit var context: Context
    private lateinit var db: com.example.infrastructure.persistence.AppDatabase
    private lateinit var repository: ConversationSessionRepositoryPort
    private lateinit var service: ConversationSessionService

    /** File-backed DB (in-memory Room LOSES data on close — real process
     *  death needs a persisted file, same pattern as ProcessDeathRecoveryTest). */
    private fun openDb(): com.example.infrastructure.persistence.AppDatabase =
        Room.databaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = File.createTempFile("session_death_", ".db")
        db = openDb()
        repository = RoomConversationSessionRepository(
            database = db,
            sessionDao = db.conversationSessionDao(),
            turnDao = db.conversationTurnDao()
        )
        service = ConversationSessionService(
            repository = repository,
            workspaceIdProvider = { "ws_alpha" }
        )
    }

    @After
    fun teardown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun `session and full turn history survive process death`() = runBlocking {
        val session = service.createSession(
            mode = ChatMode.QUICK_CHAT,
            modelResourceId = "google_google_gemini_model_gemini-2_5_flash",
            modelDisplayName = "Gemini 2.5 Flash"
        )
        service.appendTurn(
            sessionId = session.id,
            prompt = "ما هو معمارية الدومين؟",
            answer = "فصل النطاق عن البنية التحتية…",
            agentName = "المحادثة السريعة",
            agentRole = "مساعد عام",
            modelResourceId = session.modelResourceId,
            tokensConsumed = 420,
            durationMs = 1_500,
            isSuccessful = true,
            eventCount = 9
        )
        service.appendTurn(
            sessionId = session.id,
            prompt = "اذكر مثالاً",
            answer = "مثال: طبقة النطاق في AI-V0…",
            agentName = "المحادثة السريعة",
            agentRole = "مساعد عام",
            modelResourceId = session.modelResourceId,
            tokensConsumed = 210,
            durationMs = 800,
            isSuccessful = false,
            eventCount = 3
        )

        // ---- PROCESS DEATH: close and reopen the database (file-backed) ----
        db.close()
        db = openDb()
        val reopenedRepository = RoomConversationSessionRepository(
            database = db,
            sessionDao = db.conversationSessionDao(),
            turnDao = db.conversationTurnDao()
        )
        val reopened = reopenedRepository.getSessionWithTurnsForWorkspace(session.id, "ws_alpha")

        assertNotNull("الجلسة يجب أن تنجو من موت العملية", reopened)
        assertEquals(2, reopened!!.turns.size)
        assertEquals("ما هو معمارية الدومين؟", reopened.turns.first().prompt)
        assertEquals(630, reopened.session.totalTokensConsumed)
        assertEquals(2, reopened.session.turnCount)
        assertEquals(ChatMode.QUICK_CHAT, reopened.session.mode)
        assertEquals("Gemini 2.5 Flash", reopened.session.modelDisplayName)
    }

    @Test
    fun `sessions are workspace-scoped with no cross-workspace bleed`() = runBlocking {
        val alphaSession = service.createSession(mode = ChatMode.QUICK_CHAT)

        val betaService = ConversationSessionService(
            repository = repository,
            workspaceIdProvider = { "ws_beta" }
        )
        betaService.createSession(mode = ChatMode.AGENT, agentId = "agent_coder", agentName = "مهندس البرمجيات")

        val alphaSessions = repository.observeSessions("ws_alpha").first()
        val betaSessions = repository.observeSessions("ws_beta").first()

        assertEquals(1, alphaSessions.size)
        assertEquals(alphaSession.id, alphaSessions.first().id)
        assertEquals(1, betaSessions.size)
        assertTrue(alphaSessions.none { it.id == betaSessions.first().id })
    }

    @Test
    fun `first-turn titling renames the default session title to the prompt`() = runBlocking {
        val session = service.createSession(mode = ChatMode.QUICK_CHAT)
        assertEquals(ConversationSessionService.DEFAULT_TITLE, session.title)

        service.appendTurn(
            sessionId = session.id,
            prompt = "لخص لي أبعاد تقرير الفجوات",
            answer = "…",
            agentName = null, agentRole = null, modelResourceId = null,
            tokensConsumed = 10, durationMs = 10, isSuccessful = true, eventCount = 1
        )
        service.titleFromPrompt(session.id, "لخص لي أبعاد تقرير الفجوات")

        val renamed = repository.getSession(session.id)
        assertEquals("لخص لي أبعاد تقرير الفجوات", renamed?.title)
    }

    @Test
    fun `delete cascades to turns and aggregates follow`() = runBlocking {
        val session = service.createSession(mode = ChatMode.AGENT, agentId = "agent_coder")
        service.appendTurn(
            sessionId = session.id, prompt = "q", answer = "a",
            agentName = null, agentRole = null, modelResourceId = null,
            tokensConsumed = 5, durationMs = 5, isSuccessful = true, eventCount = 1
        )
        assertEquals(1, repository.observeSessions("ws_alpha").first().size)

        service.deleteSession(session.id)

        assertNull(repository.getSession(session.id))
        assertEquals(0, repository.observeSessions("ws_alpha").first().size)
        assertNull(repository.getSessionWithTurnsForWorkspace(session.id, "ws_alpha"))
    }

    @Test
    fun `model pin update persists on the durable session`() = runBlocking {
        val session = service.createSession(mode = ChatMode.QUICK_CHAT)
        service.setSessionModel(
            sessionId = session.id,
            modelResourceId = "google_google_gemini_model_gemini-2_5_pro",
            modelDisplayName = "Gemini 2.5 Pro"
        )
        val updated = repository.getSession(session.id)
        assertEquals("Gemini 2.5 Pro", updated?.modelDisplayName)
        assertEquals("google_google_gemini_model_gemini-2_5_pro", updated?.modelResourceId)
    }

    // ------------------------------------------------------------------
    // RESIDUAL CLOSURE (approval integrity) — the REAL Room SQL predicate.
    // The approval-state mirror update is bound to (sessionId, approvalId):
    // a wrong/colliding approvalId from ANOTHER session can never flip this
    // session's block (the previous SQL matched rows by approvalId alone).
    // ------------------------------------------------------------------

    /** A repository WITH the timeline-events DAO wired (the SQL under test). */
    private fun timelineRepository() = RoomConversationSessionRepository(
        database = db,
        sessionDao = db.conversationSessionDao(),
        turnDao = db.conversationTurnDao(),
        timelineEventDao = db.chatTimelineEventDao()
    )

    private suspend fun seedApprovalEvent(
        sessionId: String,
        eventId: String,
        approvalId: String
    ) {
        val repo = timelineRepository()
        assertTrue(
            repo.appendTimelineEventForWorkspace(
                com.example.domain.core.session.ConversationTimelineEvent(
                    id = eventId,
                    sessionId = ConversationSessionId(sessionId),
                    kind = com.example.domain.core.session.TimelineEventKind.APPROVAL_BLOCK,
                    title = "تنفيذ أداة",
                    summary = "طلب موافقة",
                    approvalId = approvalId,
                    approvalState = "PENDING"
                ),
                workspaceId = "ws_alpha"
            )
        )
    }

    @Test
    fun `approval update with session B and approvalId A changes nothing (Room SQL)`() = runBlocking {
        val sessionA = service.createSession(mode = ChatMode.QUICK_CHAT)
        val sessionB = service.createSession(mode = ChatMode.QUICK_CHAT)
        seedApprovalEvent(sessionA.id.value, "apv_room_a", "approval_room_a")
        seedApprovalEvent(sessionB.id.value, "apv_room_b", "approval_room_b")

        // The WRONG pairing: session B + approvalId A.
        val applied = timelineRepository().updateTimelineEventApprovalStateForWorkspace(
            sessionId = sessionB.id,
            approvalId = "approval_room_a",
            state = "APPROVED",
            workspaceId = "ws_alpha"
        )

        assertFalse("no row may change for a (session B, approvalId A) pairing", applied)
        val eventsA = timelineRepository().timelineEventsForSession(sessionA.id)
        assertEquals(
            "approval A's row must stay PENDING — the SQL predicate is session+approvalId",
            "PENDING",
            eventsA.single { it.approvalId == "approval_room_a" }.approvalState
        )
        val eventsB = timelineRepository().timelineEventsForSession(sessionB.id)
        assertEquals(
            "PENDING",
            eventsB.single { it.approvalId == "approval_room_b" }.approvalState
        )
    }

    @Test
    fun `approval update with session A and approvalId A applies correctly (Room SQL)`() = runBlocking {
        val sessionA = service.createSession(mode = ChatMode.QUICK_CHAT)
        seedApprovalEvent(sessionA.id.value, "apv_room_ok", "approval_room_ok")

        val applied = timelineRepository().updateTimelineEventApprovalStateForWorkspace(
            sessionId = sessionA.id,
            approvalId = "approval_room_ok",
            state = "APPROVED",
            workspaceId = "ws_alpha"
        )

        assertTrue("the correct pairing must update the row", applied)
        val events = timelineRepository().timelineEventsForSession(sessionA.id)
        val event = events.single { it.approvalId == "approval_room_ok" }
        assertEquals("APPROVED", event.approvalState)
        assertTrue(event.isSuccessful)
    }

    @Test
    fun `approval update is still workspace-authorized (foreign workspace changes nothing)`() = runBlocking {
        val sessionA = service.createSession(mode = ChatMode.QUICK_CHAT)
        seedApprovalEvent(sessionA.id.value, "apv_room_ws", "approval_room_ws")

        val applied = timelineRepository().updateTimelineEventApprovalStateForWorkspace(
            sessionId = sessionA.id,
            approvalId = "approval_room_ws",
            state = "APPROVED",
            workspaceId = "ws_foreign"
        )

        assertFalse("a foreign workspace must never update another workspace's row", applied)
        val events = timelineRepository().timelineEventsForSession(sessionA.id)
        assertEquals("PENDING", events.single { it.approvalId == "approval_room_ws" }.approvalState)
    }
}
