package com.example.presentation.state

import com.example.domain.core.session.ConversationSession
import com.example.domain.core.session.ConversationTurn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * SessionTranscriptExporter — PURE durable-session presentation helpers
 * (FRONTIER chat upgrade 2026: conversation rename, search and export)
 * ============================================================================
 *
 * Two JVM-pure functions, no ViewModel logic and no Compose dependencies:
 *
 *  1. [filterSessions] — the session-list SEARCH contract: case-insensitive
 *     substring match over the fields a user actually remembers (title,
 *     agent name, model name). An empty/blank query is "all sessions";
 *     a no-match query is an honest EMPTY list (never a fabricated
 *     "not found" entry).
 *
 *  2. [toTranscriptMarkdown] — the session EXPORT contract: the durable
 *     truth (session header + ordered turns) rendered as a clean Markdown
 *     transcript the user can share or archive. Only REAL persisted fields
 *     are exported; runtime-only affordances (live reasoning, approval
 *     blocks) are honestly absent because they are not part of the durable
 *     turn.
 */
object SessionTranscriptExporter {

    /**
     * The session-list search: a query matches when ANY of the
     * user-rememberable fields contains it (case-insensitive — Arabic has
     * no case, Latin titles match regardless of case). Blank query = all.
     */
    fun filterSessions(sessions: List<ConversationSession>, query: String): List<ConversationSession> {
        val needle = query.trim()
        if (needle.isEmpty()) return sessions
        return sessions.filter { session ->
            session.title.contains(needle, ignoreCase = true) ||
                session.agentName?.contains(needle, ignoreCase = true) == true ||
                session.modelDisplayName?.contains(needle, ignoreCase = true) == true
        }
    }

    private fun formatDate(epochMs: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(epochMs))

    /**
     * Renders the durable transcript as Markdown:
     *
     * ```
     * # {title}
     *
     * > الدورات: N • التوكنز: M • أُنشئت: … • آخر نشاط: … • الوكيل/النموذج: …
     *
     * ## المستخدم
     * {prompt}
     *
     * ## المساعد{ (فاشلة)}{ — agent}
     * {answer}
     * _توكن: … • مدة: …_
     * ```
     *
     * Failed turns keep their honest "فشل" marker; a blank answer renders
     * the honest placeholder line, never a fabricated one.
     */
    fun toTranscriptMarkdown(session: ConversationSession, turns: List<ConversationTurn>): String {
        val sb = StringBuilder()
        sb.append("# ").append(session.title.trim()).append("\n\n")
        sb.append("> ")
            .append("الدورات: ${session.turnCount}")
            .append(" • ")
            .append("التوكنز: ${session.totalTokensConsumed}")
        if (session.createdAtEpochMs > 0) sb.append(" • أُنشئت: ${formatDate(session.createdAtEpochMs)}")
        if (session.lastActiveAtEpochMs > 0) sb.append(" • آخر نشاط: ${formatDate(session.lastActiveAtEpochMs)}")
        session.agentName?.let { sb.append(" • الوكيل: $it") }
        session.modelDisplayName?.let { sb.append(" • النموذج: $it") }
        sb.append("\n\n")

        if (turns.isEmpty()) {
            sb.append("_لا دورات محفوظة في هذه الجلسة._\n")
            return sb.toString()
        }

        turns.forEach { turn ->
            sb.append("## المستخدم\n")
                .append(turn.prompt.trim().ifBlank { "_(رسالة فارغة)_" })
                .append("\n\n")
            val agentLabel = turn.agentName?.let { " — $it" } ?: ""
            val failureLabel = if (turn.isSuccessful) "" else " (فاشلة)"
            sb.append("## المساعد$failureLabel$agentLabel\n")
                .append(turn.answer.trim().ifBlank { "_— لا يوجد نص لهذه الدورة —_" })
                .append("\n\n")
            val foot = buildList {
                if (turn.tokensConsumed > 0) add("توكن: ${turn.tokensConsumed}")
                if (turn.durationMs > 0) add("مدة: ${(turn.durationMs / 1000.0).let { "%.1f ثانية".format(it) }}")
                if (turn.modelResourceId != null) add("نموذج: ${turn.modelResourceId}")
            }
            if (foot.isNotEmpty()) sb.append("_${foot.joinToString(" • ")}_\n\n")
        }
        return sb.toString().trimEnd() + "\n"
    }
}
