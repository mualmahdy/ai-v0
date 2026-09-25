package com.example.presentation.state

import com.example.domain.core.session.ChatMode
import com.example.domain.core.session.ConversationSession
import com.example.domain.core.session.ConversationSessionId
import com.example.domain.core.session.ConversationTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * SessionTranscriptExporterTest — the PURE contracts of FRONTIER session
 * management (rename/search/export): case-insensitive session search over
 * the fields a user remembers, and the durable-transcript Markdown export.
 * ============================================================================
 */
class SessionTranscriptExporterTest {

    private fun session(
        id: String = "s1",
        title: String = "نقاش حول الهندسة",
        agentName: String? = "المحلل",
        modelDisplayName: String? = "Test Model",
        turnCount: Int = 2
    ) = ConversationSession(
        id = ConversationSessionId(id),
        workspaceId = "ws",
        title = title,
        mode = ChatMode.QUICK_CHAT,
        agentName = agentName,
        modelDisplayName = modelDisplayName,
        turnCount = turnCount,
        totalTokensConsumed = 120,
        createdAtEpochMs = 1_700_000_000_000L,
        lastActiveAtEpochMs = 1_700_000_100_000L
    )

    private fun turn(
        id: String = "t1",
        prompt: String = "ما الفرق بين A و B؟",
        answer: String = "الفرق الجوهري هو…",
        isSuccessful: Boolean = true
    ) = ConversationTurn(
        id = id,
        sessionId = ConversationSessionId("s1"),
        prompt = prompt,
        answer = answer,
        agentName = "المحلل",
        tokensConsumed = 40,
        durationMs = 2_500L,
        isSuccessful = isSuccessful
    )

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    @Test
    fun `blank query returns all sessions`() {
        val sessions = listOf(session(id = "a"), session(id = "b"))
        assertEquals(2, SessionTranscriptExporter.filterSessions(sessions, "").size)
        assertEquals(2, SessionTranscriptExporter.filterSessions(sessions, "   ").size)
    }

    @Test
    fun `arabic title matches case and whitespace insensitively`() {
        val sessions = listOf(session(title = "خطة التسليم النهائية"))
        assertTrue(SessionTranscriptExporter.filterSessions(sessions, "التسليم").isNotEmpty())
        assertTrue(SessionTranscriptExporter.filterSessions(sessions, "  التسليم  ").isNotEmpty())
        // Honest substring semantics: a NON-substring word is an empty result
        // (no stemming, no fuzzy matching — never a fabricated match).
        assertTrue(SessionTranscriptExporter.filterSessions(sessions, "الاستلام").isEmpty())
    }

    @Test
    fun `search matches agent and model names too`() {
        val sessions = listOf(session(agentName = "المراجع التقني", modelDisplayName = "GPT-class"))
        assertTrue(SessionTranscriptExporter.filterSessions(sessions, "المراجع").isNotEmpty())
        assertTrue(SessionTranscriptExporter.filterSessions(sessions, "gpt").isNotEmpty()) // case-insensitive
    }

    @Test
    fun `no match is an honest empty list`() {
        val sessions = listOf(session(title = "عنوان عربي"))
        assertTrue(SessionTranscriptExporter.filterSessions(sessions, "klingon").isEmpty())
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    @Test
    fun `export renders title metadata and every turn in order`() {
        val md = SessionTranscriptExporter.toTranscriptMarkdown(
            session(),
            listOf(turn(id = "t1"), turn(id = "t2", prompt = "والمزيد؟", answer = "المزيد هنا"))
        )
        assertTrue(md.startsWith("# نقاش حول الهندسة"))
        assertTrue(md.contains("الدورات: 2"))
        assertTrue(md.contains("التوكنز: 120"))
        assertTrue(md.contains("الوكيل: المحلل"))
        // Both turns render in order: first t1's prompt, then t2's.
        assertTrue(md.indexOf("ما الفرق بين A و B؟") < md.indexOf("والمزيد؟"))
        assertTrue(md.contains("## المستخدم"))
        assertTrue(md.contains("## المساعد — المحلل"))
        // Per-turn honest footer (tokens + duration).
        assertTrue(md.contains("توكن: 40"))
        assertTrue(md.contains("2.5 ثانية"))
    }

    @Test
    fun `failed turns keep their honest failure marker`() {
        val md = SessionTranscriptExporter.toTranscriptMarkdown(
            session(),
            listOf(turn(isSuccessful = false))
        )
        assertTrue(md.contains("## المساعد (فاشلة)"))
    }

    @Test
    fun `empty transcript exports the honest placeholder - never fabricated turns`() {
        val md = SessionTranscriptExporter.toTranscriptMarkdown(session(turnCount = 0), emptyList())
        assertTrue(md.contains("لا دورات محفوظة في هذه الجلسة"))
        assertFalse(md.contains("## المستخدم"))
    }

    @Test
    fun `blank answer renders the honest placeholder line`() {
        val md = SessionTranscriptExporter.toTranscriptMarkdown(
            session(),
            listOf(turn(answer = "  "))
        )
        assertTrue(md.contains("_— لا يوجد نص لهذه الدورة —_"))
    }
}
