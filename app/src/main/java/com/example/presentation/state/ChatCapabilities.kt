package com.example.presentation.state

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.radar.OperationalCapabilityState
import com.example.domain.core.radar.RadarCapabilityCheck

/**
 * ============================================================================
 * ChatCapabilityPolicy — the CHAT capability menu's availability contract
 * (CHAT CAPABILITIES Task 2 §3/§4)
 * ============================================================================
 *
 * "Conversation first + Capability on demand + Progressive disclosure": the
 * composer's "+" opens a CATEGORIZED menu (never a giant list), and EVERY
 * entry carries an honest availability state — the platform-wide rule is
 * that a capability is NEVER deleted from the UI just because it is not
 * ready; it is shown faded with the REAL reason instead.
 *
 * THIS IS A PURE POLICY over facts that the caller collects from the REAL
 * sources of truth (no second availability system is built):
 *  - the active-project binding (the storage layer is project-scoped);
 *  - the CapabilityRadarService's checks (THE authority for capability-level
 *    states — VISION is PLANNED there, for instance);
 *  - the live extension/tool registries (skills, MCP servers, tools);
 *  - the knowledge corpus count;
 *  - the wired search provider + the session network policy.
 */
enum class ChatCapabilityStatus {
    /** Clickable, normal visual treatment. */
    AVAILABLE,
    /** Visible but faded + non-clickable + the real reason. */
    UNAVAILABLE,
    /** Faded + "قريباً" — ONLY for capabilities that are genuinely planned. */
    PLANNED
}

/** The capability menu entries (keys are stable identities, not display text). */
enum class ChatCapabilityKey {
    ATTACH_FILE,
    ATTACH_FOLDER,
    VISION_ANALYSIS,
    KNOWLEDGE_RETRIEVAL,
    SEARCH_INTELLIGENCE,
    SKILLS,
    TOOLS,
    MCP_SERVERS
}

enum class ChatCapabilityCategory(val title: String) {
    FILES_AND_MEDIA("الملفات والوسائط"),
    KNOWLEDGE("المعرفة"),
    INTELLIGENCE("الذكاء")
}

/** One menu row: capability + its honest availability right now. */
data class ChatCapabilityItem(
    val key: ChatCapabilityKey,
    val category: ChatCapabilityCategory,
    val title: String,
    val subtitle: String,
    val status: ChatCapabilityStatus,
    val reason: String? = null,
    /** True when the capability runs but degraded (shown as a hint, still clickable). */
    val isDegraded: Boolean = false
)

/** The real-world facts the policy decides on (collected from live sources). */
data class ChatCapabilityFacts(
    /** The storage layer is project-scoped — attachments need an active project. */
    val activeProjectId: Long? = null,
    /** Folder attach needs the SAF tree→zip serializer (wired in production). */
    val folderAttachSupported: Boolean = true,
    /** The radar's checks (THE capability-level authority — e.g. VISION). */
    val radarChecks: Map<CapabilityType, RadarCapabilityCheck> = emptyMap(),
    /** Indexed knowledge documents (RAG retrieval is pointless on an empty corpus). */
    val knowledgeDocumentCount: Int = 0,
    /** The semantic engine's readiness (affects the knowledge hint only). */
    val semanticKnowledgeReady: Boolean? = null,
    /** Search pipeline provider wired (production: always — local fallback). */
    val searchProviderWired: Boolean = true,
    /** Current session network policy (drives the degraded hint). */
    val isNetworkAvailable: Boolean = true,
    /** ENABLED skills in the extension registry. */
    val enabledSkillCount: Int = 0,
    /** Tools registered in the runtime registry. */
    val registeredToolCount: Int = 0,
    /** MCP server descriptors (any health — the browser shows per-server state). */
    val mcpServerCount: Int = 0
)

object ChatCapabilityPolicy {

    /** The honest default for VISION when no radar check is available. */
    private const val VISION_PLANNED_REASON =
        "قدرة مخططة فقط (لا تنفيذ) — تحليل الصور غير مفعّل في هذا الإصدار."

    fun resolve(facts: ChatCapabilityFacts): List<ChatCapabilityItem> = listOf(
        // ---------------- Files & Media ----------------
        ChatCapabilityItem(
            key = ChatCapabilityKey.ATTACH_FILE,
            category = ChatCapabilityCategory.FILES_AND_MEDIA,
            title = "إرفاق ملف",
            subtitle = "ملف من جهازك يُرسل مع الرسالة (نصوص وأكواد تُقرأ فعلياً)",
            status = if (facts.activeProjectId != null) {
                ChatCapabilityStatus.AVAILABLE
            } else {
                ChatCapabilityStatus.UNAVAILABLE
            },
            reason = if (facts.activeProjectId == null) {
                "غير متاح حالياً — تخزين المرفقات يتطلب مشروعاً نشطاً"
            } else null
        ),
        ChatCapabilityItem(
            key = ChatCapabilityKey.ATTACH_FOLDER,
            category = ChatCapabilityCategory.FILES_AND_MEDIA,
            title = "إرفاق مجلد",
            subtitle = "مجلد يُستورد كوحدة واحدة إلى ملعب المشروع",
            status = when {
                facts.activeProjectId == null -> ChatCapabilityStatus.UNAVAILABLE
                !facts.folderAttachSupported -> ChatCapabilityStatus.UNAVAILABLE
                else -> ChatCapabilityStatus.AVAILABLE
            },
            reason = when {
                facts.activeProjectId == null ->
                    "غير متاح حالياً — تخزين المرفقات يتطلب مشروعاً نشطاً"
                !facts.folderAttachSupported ->
                    "غير متاح في هذا التكوين — لا يوجد مسار استيراد للمجلدات"
                else -> null
            }
        ),
        ChatCapabilityItem(
            key = ChatCapabilityKey.VISION_ANALYSIS,
            category = ChatCapabilityCategory.FILES_AND_MEDIA,
            title = "تحليل صورة (Vision)",
            subtitle = "فهم محتوى الصور داخل المحادثة",
            // §7: VISION is NOT operational — the radar declares it
            // implemented=false ⇒ PLANNED. Never a fake vision request.
            status = ChatCapabilityStatus.PLANNED,
            reason = radarReason(facts, CapabilityType.VISION) ?: VISION_PLANNED_REASON
        ),

        // ---------------- Knowledge ----------------
        ChatCapabilityItem(
            key = ChatCapabilityKey.KNOWLEDGE_RETRIEVAL,
            category = ChatCapabilityCategory.KNOWLEDGE,
            title = "استرجاع من قاعدة المعرفة",
            subtitle = "بحث في المستندات المفهرسة (RAG) وعرض المقاطع ذات الصلة",
            status = if (facts.knowledgeDocumentCount > 0) {
                ChatCapabilityStatus.AVAILABLE
            } else {
                ChatCapabilityStatus.UNAVAILABLE
            },
            reason = if (facts.knowledgeDocumentCount == 0) {
                "غير متاح حالياً — لا توجد مستندات مفهرسة في قاعدة المعرفة بعد"
            } else null,
            isDegraded = facts.knowledgeDocumentCount > 0 &&
                facts.semanticKnowledgeReady == false
        ),
        ChatCapabilityItem(
            key = ChatCapabilityKey.SEARCH_INTELLIGENCE,
            category = ChatCapabilityCategory.KNOWLEDGE,
            title = "بحث ذكي",
            subtitle = "بحث متعدد المصادر مع ترتيب ومراجع قابلة للطي",
            status = if (facts.searchProviderWired) {
                ChatCapabilityStatus.AVAILABLE
            } else {
                ChatCapabilityStatus.UNAVAILABLE
            },
            reason = if (facts.searchProviderWired) null else {
                "غير متاح حالياً — لا يوجد مزود بحث داعم"
            },
            // The pipeline's honest local-only fallback when offline.
            isDegraded = facts.searchProviderWired && !facts.isNetworkAvailable
        ),

        // ---------------- Intelligence ----------------
        ChatCapabilityItem(
            key = ChatCapabilityKey.SKILLS,
            category = ChatCapabilityCategory.INTELLIGENCE,
            title = "المهارات",
            subtitle = "تشغيل مهارة مثبتة وعرض نتيجتها في المحادثة",
            status = if (facts.enabledSkillCount > 0) {
                ChatCapabilityStatus.AVAILABLE
            } else {
                ChatCapabilityStatus.UNAVAILABLE
            },
            reason = if (facts.enabledSkillCount == 0) {
                "غير متاح حالياً — لا توجد مهارات مفعّلة"
            } else null
        ),
        ChatCapabilityItem(
            key = ChatCapabilityKey.TOOLS,
            category = ChatCapabilityCategory.INTELLIGENCE,
            title = "الأدوات",
            subtitle = "تنفيذ أداة مسجلة عبر مسار التنفيذ المحكوم",
            status = if (facts.registeredToolCount > 0) {
                ChatCapabilityStatus.AVAILABLE
            } else {
                ChatCapabilityStatus.UNAVAILABLE
            },
            reason = if (facts.registeredToolCount == 0) {
                "غير متاح حالياً — لا توجد أدوات مسجلة في المدى الزمني الحالي"
            } else null
        ),
        ChatCapabilityItem(
            key = ChatCapabilityKey.MCP_SERVERS,
            category = ChatCapabilityCategory.INTELLIGENCE,
            title = "خوادم MCP",
            subtitle = "استدعاء أدوات الخوادم المتصلة (بروتوكول MCP)",
            status = if (facts.mcpServerCount > 0) {
                ChatCapabilityStatus.AVAILABLE
            } else {
                ChatCapabilityStatus.UNAVAILABLE
            },
            reason = if (facts.mcpServerCount == 0) {
                "غير متاح حالياً — لا توجد خوادم MCP مسجلة"
            } else null
        )
    )

    /**
     * The radar's rationale for a capability when a check exists — the radar
     * is the authority for capability-level states; its Arabic reasons are
     * already user-facing.
     */
    private fun radarReason(
        facts: ChatCapabilityFacts,
        type: CapabilityType
    ): String? {
        val check = facts.radarChecks[type] ?: return null
        return when (check.state) {
            OperationalCapabilityState.PLANNED ->
                check.rationale.ifBlank { VISION_PLANNED_REASON }
            else -> null
        }
    }

    /** Whether a menu row is clickable (ONLY Available rows are). */
    fun isClickable(item: ChatCapabilityItem): Boolean =
        item.status == ChatCapabilityStatus.AVAILABLE
}

/**
 * CHAT CAPABILITIES (Task 2 §19): the ADAPTIVE CHAT layout contract — a PURE
 * function of the width class, so the pane topology is unit-testable and the
 * composable shell is just its renderer. The same control is never duplicated
 * across panes: the sessions surface is EITHER a pane (medium+) OR a sheet
 * (compact); the context/execution pane exists ONLY at expanded width.
 */
object ChatAdaptiveLayout {

    /** The visible conversation surfaces for one width class. */
    data class ChatPanes(
        /** True when the sessions list is a permanent side pane. */
        val sessionsPane: Boolean,
        /** True when the sessions browser opens as a bottom sheet instead. */
        val sessionsSheet: Boolean,
        /** True when the context/execution pane is shown beside the chat. */
        val contextPane: Boolean,
        /** True when the header shows the browse-sessions button. */
        val headerSessionsButton: Boolean
    )

    fun panesFor(
        widthClass: com.example.presentation.ui.navigation.NavWidthClass
    ): ChatPanes = when (widthClass) {
        com.example.presentation.ui.navigation.NavWidthClass.COMPACT -> ChatPanes(
            sessionsPane = false,
            sessionsSheet = true,
            contextPane = false,
            headerSessionsButton = true
        )
        com.example.presentation.ui.navigation.NavWidthClass.MEDIUM -> ChatPanes(
            sessionsPane = true,
            sessionsSheet = false,
            contextPane = false,
            headerSessionsButton = false
        )
        com.example.presentation.ui.navigation.NavWidthClass.EXPANDED -> ChatPanes(
            sessionsPane = true,
            sessionsSheet = false,
            contextPane = true,
            headerSessionsButton = false
        )
    }
}
