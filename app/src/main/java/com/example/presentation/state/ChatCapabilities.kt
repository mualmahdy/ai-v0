package com.example.presentation.state

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.radar.OperationalCapabilityState
import com.example.domain.core.radar.RadarCapabilityCheck

/**
 * ============================================================================
 * ChatCapabilityPolicy — the CHAT capability hub's availability contract
 * (CHAT CAPABILITIES Task 2 §3/§4; CHAT WORKSPACE PROFESSIONAL POLISH §3/§4)
 * ============================================================================
 *
 * "Conversation first + Capability on demand + Progressive disclosure": the
 * composer's entry point opens a CATEGORIZED hub (never a giant list), and
 * EVERY entry carries an honest availability state.
 *
 * UNAVAILABLE ≠ HIDDEN (§3 — the platform-wide rule): a capability that is
 * KNOWN to the architecture but has no runtime execution path in this
 * version is NEVER removed from the surface — it is shown disabled (faded,
 * non-clickable) with the REAL short reason. When the reason is not
 * knowable from code, the honest generic label is "غير متاح في هذا الإصدار"
 * — never an invented reason, never a vague "قريباً" (PLANNED is reserved for
 * capabilities the radar genuinely declares planned, e.g. Vision).
 *
 * The hub's four groups (§4):
 *   الملفات والسياق:  إرفاق ملف / إرفاق مجلد / استرجاع قاعدة المعرفة (RAG)
 *   البحث والذكاء:    بحث ذكي / وكيل / خطط الوكلاء / مهارات / أدوات / خوادم MCP
 *   الوسائط:          تحليل صورة (Vision) / توليد الصور / الصوت / الكاميرا / مشاركة الشاشة
 *   الإنشاء:          مستند / شيفرة / حفظ النتيجة كأثر
 *
 * THIS IS A PURE POLICY over facts that the caller collects from the REAL
 * sources of truth (no second availability system is built):
 *  - the active-project binding (the storage layer is project-scoped);
 *  - the CapabilityRadarService's checks (THE authority for capability-level
 *    states — VISION is PLANNED there, for instance);
 *  - the live extension/tool registries (skills, MCP servers, tools);
 *  - the knowledge corpus count;
 *  - the wired search provider + the session network policy;
 *  - the LLM connection state (the conversation generation path).
 */
enum class ChatCapabilityStatus {
    /** Clickable, normal visual treatment. */
    AVAILABLE,
    /** Visible but faded + non-clickable + the real reason. */
    UNAVAILABLE,
    /** Faded + "قريباً" — ONLY for capabilities that are genuinely planned. */
    PLANNED
}

/** The capability hub entries (keys are stable identities, not display text). */
enum class ChatCapabilityKey {
    // ---- Files & Context ----
    ATTACH_FILE,
    ATTACH_FOLDER,
    KNOWLEDGE_RETRIEVAL,
    // ---- Search & Intelligence ----
    SEARCH_INTELLIGENCE,
    AGENT,
    WORKFLOW,
    SKILLS,
    TOOLS,
    MCP_SERVERS,
    // ---- Media ----
    VISION_ANALYSIS,
    IMAGE_GENERATION,
    SPEECH,
    CAMERA,
    SCREEN_SHARE,
    // ---- Creation ----
    DOCUMENT_CREATION,
    CODE_CREATION,
    RESULT_TO_ARTIFACT
}

/**
 * The hub's four groups (§4) — ordered as the sheet renders them.
 */
enum class ChatCapabilityCategory(val title: String) {
    FILES_AND_CONTEXT("الملفات والسياق"),
    SEARCH_AND_INTELLIGENCE("البحث والذكاء"),
    MEDIA("الوسائط"),
    CREATION("الإنشاء")
}

/** One hub row: capability + its honest availability right now. */
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

/**
 * Composer prefill templates for the CREATION entries that reach the
 * conversation's real generation path (§4/§18: the draft is FILLED, never
 * auto-sent — exactly the ChatEmptyState starter contract).
 */
object ChatCapabilityTemplates {
    const val DOCUMENT = "أنشئ لي مستنداً منظّماً حول: "
    const val CODE = "اكتب لي شيفرة برمجية لـ: "
}

/**
 * Honest reason constants for the version-level UNAVAILABLE entries — the
 * reasons were verified against the REAL runtime (not invented):
 *  - IMAGE_GENERATION / SPEECH: the provider architecture KNOWS these
 *    service types, but they map to ResourceType.INTEGRATION with NO
 *    registered validator/execution path (ResourceValidation's own comment:
 *    "validation fails explicitly rather than pretending").
 *  - CAMERA / SCREEN_SHARE: no capture/projection path exists at all.
 *  - RESULT_TO_ARTIFACT: no chat-side save-result-as-artifact action is
 *    wired (attachments DO register artifacts; results do not).
 */
object ChatCapabilityReasons {
    const val IMAGE_GENERATION =
        "غير متاح في هذا الإصدار — لا يوجد مسار تنفيذ لتوليد الصور"
    const val SPEECH =
        "غير متاح في هذا الإصدار — لا يوجد مسار تنفيذ للصوت"
    const val CAMERA =
        "غير متاح في هذا الإصدار — لا يوجد مسار للكاميرا"
    const val SCREEN_SHARE =
        "غير متاح في هذا الإصدار — لا يوجد مسار لمشاركة الشاشة"
    const val RESULT_TO_ARTIFACT =
        "غير متاح في هذا الإصدار — حفظ نتائج المحادثة كآثار غير مربوط بعد"
    const val NO_ACTIVE_LLM =
        "غير متاح حالياً — لا يوجد ذكاء نشط في مساحة العمل؛ اربط مزوّد LLM أولاً"
}

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
    /** The conversation generation path is LIVE (an operational LLM resource). */
    val hasActiveLlm: Boolean = false,
    /**
     * FUNCTIONAL CLOSURE (§20): OPERATIONAL skills — ENABLED manifests that
     * are ACTUALLY REGISTERED as runnable tool ports. A manifest whose tool
     * never registered is not invocable through the governed path, so it does
     * not count toward availability.
     */
    val enabledSkillCount: Int = 0,
    /**
     * Tools registered in the runtime registry — the registry IS the
     * operational truth for tools (admission/consent are per-invocation
     * policy, not availability).
     */
    val registeredToolCount: Int = 0,
    /**
     * FUNCTIONAL CLOSURE (§20): ENABLED MCP servers only (a disabled server is
     * not connectable — its existence says nothing about availability).
     */
    val mcpServerCount: Int = 0,
    /**
     * FUNCTIONAL CLOSURE (§20): of the ENABLED servers, how many are HEALTHY
     * (handshake-complete, tools registered). A healthy server is plainly
     * available; an enabled-but-unhealthy one is reachable only through the
     * browser's ping (shown as a degraded hint — the recovery path, not a
     * silent "available").
     */
    val healthyMcpServerCount: Int = 0
)

object ChatCapabilityPolicy {

    /** The honest default for VISION when no radar check is available. */
    private const val VISION_PLANNED_REASON =
        "قدرة مخططة فقط (لا تنفيذ) — تحليل الصور غير مفعّل في هذا الإصدار."

    /**
     * CHAT FINAL CLOSURE (§8 capability truthfulness): the presentation
     * state of a RADAR-TRACKED capability is DERIVED from the radar's REAL
     * operational state — the radar is the declared authority, so an
     * AVAILABLE/DEGRADED capability shows as available (degraded hinted),
     * and BLOCKED/FAILED/DISABLED/PARTIAL/DEPRECATED/UNKNOWN show as
     * honestly unavailable with the radar's rationale. PLANNED is reserved
     * for the radar's genuine PLANNED declarations (or the honest default
     * when no radar exists in the composition). No fake availability is
     * ever created — and a real state is never masked as "قريباً".
     */
    private fun radarCapabilityStatus(
        facts: ChatCapabilityFacts,
        type: CapabilityType
    ): ChatCapabilityStatus {
        val check = facts.radarChecks[type] ?: return ChatCapabilityStatus.PLANNED
        return when (check.state) {
            OperationalCapabilityState.AVAILABLE -> ChatCapabilityStatus.AVAILABLE
            // Executable with fallback (the radar's own isExecutable rule) —
            // degraded hint, still clickable (the SEARCH row's convention).
            OperationalCapabilityState.DEGRADED -> ChatCapabilityStatus.AVAILABLE
            // The radar's GENUINE planned declaration (its current VISION
            // declaration table: implemented=false).
            OperationalCapabilityState.PLANNED -> ChatCapabilityStatus.PLANNED
            // UNKNOWN / PARTIAL / BLOCKED / FAILED / DISABLED / DEPRECATED:
            // not executable right now — honestly unavailable with the
            // radar's rationale (never masked as "قريباً").
            else -> ChatCapabilityStatus.UNAVAILABLE
        }
    }

    /** The degraded hint of a radar-tracked capability (§8). */
    private fun radarCapabilityDegraded(
        facts: ChatCapabilityFacts,
        type: CapabilityType
    ): Boolean {
        val check = facts.radarChecks[type] ?: return false
        return check.state == OperationalCapabilityState.DEGRADED
    }

    /**
     * The radar's rationale surfaced for ANY non-default state (§8 — the
     * REAL reason the radar derived, never an invented one).
     */
    private fun radarStateReason(
        facts: ChatCapabilityFacts,
        type: CapabilityType,
        fallback: String
    ): String? {
        val check = facts.radarChecks[type] ?: return fallback
        return when (check.state) {
            OperationalCapabilityState.PLANNED,
            OperationalCapabilityState.UNKNOWN ->
                check.rationale.ifBlank { fallback }
            else -> check.rationale.ifBlank { fallback }
        }
    }

    fun resolve(facts: ChatCapabilityFacts): List<ChatCapabilityItem> = listOf(
        // ---------------- Files & Context ----------------
        ChatCapabilityItem(
            key = ChatCapabilityKey.ATTACH_FILE,
            category = ChatCapabilityCategory.FILES_AND_CONTEXT,
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
            category = ChatCapabilityCategory.FILES_AND_CONTEXT,
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
            key = ChatCapabilityKey.KNOWLEDGE_RETRIEVAL,
            category = ChatCapabilityCategory.FILES_AND_CONTEXT,
            title = "استرجاع من قاعدة المعرفة (RAG)",
            subtitle = "بحث في المستندات المفهرسة وعرض المقاطع ذات الصلة",
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

        // ---------------- Search & Intelligence ----------------
        ChatCapabilityItem(
            key = ChatCapabilityKey.SEARCH_INTELLIGENCE,
            category = ChatCapabilityCategory.SEARCH_AND_INTELLIGENCE,
            title = "بحث ذكي",
            // CHAT FINAL CLOSURE (§9 Deep Research truthfulness): this is the
            // SEARCH INTELLIGENCE pipeline (analyze → decompose → fan-out →
            // dedup → rank → citations) — NOT an independent Deep Research
            // workflow (none exists in this version). The copy names exactly
            // what runs; it never borrows a bigger feature's name.
            subtitle = "بحث متعدد المصادر: تحليل الاستعلام → تفتيت → مصادر متعددة → ترتيب → مراجع",
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
        ChatCapabilityItem(
            key = ChatCapabilityKey.AGENT,
            category = ChatCapabilityCategory.SEARCH_AND_INTELLIGENCE,
            title = "الوكيل والنموذج",
            subtitle = "اختيار الوكيل أو نموذج المحادثة (الكتالوج الدائم + بناء وكيل)",
            status = ChatCapabilityStatus.AVAILABLE
        ),
        ChatCapabilityItem(
            key = ChatCapabilityKey.WORKFLOW,
            category = ChatCapabilityCategory.SEARCH_AND_INTELLIGENCE,
            title = "خطط الوكلاء والمهام",
            subtitle = "بناء خطط الوكلاء ومتابعة المهام الدائمة من لوحة المهام",
            status = ChatCapabilityStatus.AVAILABLE
        ),
        ChatCapabilityItem(
            key = ChatCapabilityKey.SKILLS,
            category = ChatCapabilityCategory.SEARCH_AND_INTELLIGENCE,
            title = "المهارات",
            subtitle = "تشغيل مهارة مثبتة وعرض نتيجتها في المحادثة",
            // FUNCTIONAL CLOSURE (§20): the fact is the OPERATIONAL count —
            // ENABLED manifests actually registered as runnable tool ports
            // (the caller computes it from the real registry).
            status = if (facts.enabledSkillCount > 0) {
                ChatCapabilityStatus.AVAILABLE
            } else {
                ChatCapabilityStatus.UNAVAILABLE
            },
            reason = if (facts.enabledSkillCount == 0) {
                "غير متاح حالياً — لا توجد مهارات مفعّلة قابلة للتنفيذ"
            } else null
        ),
        ChatCapabilityItem(
            key = ChatCapabilityKey.TOOLS,
            category = ChatCapabilityCategory.SEARCH_AND_INTELLIGENCE,
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
            category = ChatCapabilityCategory.SEARCH_AND_INTELLIGENCE,
            title = "خوادم MCP",
            subtitle = "استدعاء أدوات الخوادم المتصلة (بروتوكول MCP)",
            // FUNCTIONAL CLOSURE (§20): operational truth — the existence of
            // servers says NOTHING about availability. ONLY a healthy+enabled
            // server (handshake-complete, tools registered) makes the entry
            // available; anything less is UNAVAILABLE with the REAL reason.
            // (The handshake/recovery path lives on the EXTENSIONS screen —
            // never blocked by this entry being honestly unavailable.)
            status = if (facts.healthyMcpServerCount > 0) {
                ChatCapabilityStatus.AVAILABLE
            } else {
                ChatCapabilityStatus.UNAVAILABLE
            },
            reason = when {
                facts.mcpServerCount == 0 ->
                    "غير متاح حالياً — لا توجد خوادم MCP مفعّلة"
                facts.healthyMcpServerCount == 0 ->
                    "لا يوجد خادم متصل (سليم ومفعّل) — أنجز المصافحة من شاشة «الإضافات» ثم أعد المحاولة"
                else -> null
            }
        ),

        // ---------------- Media ----------------
        ChatCapabilityItem(
            key = ChatCapabilityKey.VISION_ANALYSIS,
            category = ChatCapabilityCategory.MEDIA,
            title = "تحليل صورة (Vision)",
            subtitle = "فهم محتوى الصور داخل المحادثة",
            // CHAT FINAL CLOSURE (§8): the status is DERIVED from the radar's
            // real operational state (see [radarCapabilityStatus]) — PLANNED
            // only when the radar genuinely declares it planned (its current
            // declaration table: implemented=false) or when no radar exists.
            // A future radar AVAILABLE/DEGRADED state is reflected truthfully;
            // BLOCKED/FAILED/DISABLED show the real reason. Never a fake
            // availability, never a masked real state.
            status = radarCapabilityStatus(facts, CapabilityType.VISION),
            reason = radarStateReason(facts, CapabilityType.VISION, VISION_PLANNED_REASON),
            isDegraded = radarCapabilityDegraded(facts, CapabilityType.VISION)
        ),
        ChatCapabilityItem(
            key = ChatCapabilityKey.IMAGE_GENERATION,
            category = ChatCapabilityCategory.MEDIA,
            title = "توليد الصور",
            subtitle = "إنشاء صور من وصف نصي",
            // §3 UNAVAILABLE ≠ HIDDEN: the service type EXISTS in the provider
            // architecture, but there is NO runtime execution path (it maps
            // to INTEGRATION which fails validation explicitly) — shown
            // disabled with the real reason, never hidden.
            status = ChatCapabilityStatus.UNAVAILABLE,
            reason = ChatCapabilityReasons.IMAGE_GENERATION
        ),
        ChatCapabilityItem(
            key = ChatCapabilityKey.SPEECH,
            category = ChatCapabilityCategory.MEDIA,
            title = "الصوت (إدخال وإخراج)",
            subtitle = "تحويل الكلام إلى نص والعكس",
            // §3: same verified situation as image generation — the provider
            // architecture knows the SPEECH service type; no execution path.
            status = ChatCapabilityStatus.UNAVAILABLE,
            reason = ChatCapabilityReasons.SPEECH
        ),
        ChatCapabilityItem(
            key = ChatCapabilityKey.CAMERA,
            category = ChatCapabilityCategory.MEDIA,
            title = "الكاميرا",
            subtitle = "التقاط صورة أو فيديو للمحادثة",
            // §3: no capture path exists in this version at all.
            status = ChatCapabilityStatus.UNAVAILABLE,
            reason = ChatCapabilityReasons.CAMERA
        ),
        ChatCapabilityItem(
            key = ChatCapabilityKey.SCREEN_SHARE,
            category = ChatCapabilityCategory.MEDIA,
            title = "مشاركة الشاشة",
            subtitle = "مشاركة محتوى الشاشة مع المحادثة",
            // §3: no projection path exists in this version at all.
            status = ChatCapabilityStatus.UNAVAILABLE,
            reason = ChatCapabilityReasons.SCREEN_SHARE
        ),

        // ---------------- Creation ----------------
        ChatCapabilityItem(
            key = ChatCapabilityKey.DOCUMENT_CREATION,
            category = ChatCapabilityCategory.CREATION,
            title = "إنشاء مستند",
            subtitle = "عبر المحادثة — يُعبّأ قالب الطلب في حقل الإدخال ثم ترسله",
            // The REAL path is the conversation's LLM generation; the entry
            // only PREFILLS the draft (§18 — never auto-sends).
            status = if (facts.hasActiveLlm) {
                ChatCapabilityStatus.AVAILABLE
            } else {
                ChatCapabilityStatus.UNAVAILABLE
            },
            reason = if (!facts.hasActiveLlm) ChatCapabilityReasons.NO_ACTIVE_LLM else null
        ),
        ChatCapabilityItem(
            key = ChatCapabilityKey.CODE_CREATION,
            category = ChatCapabilityCategory.CREATION,
            title = "كتابة شيفرة",
            subtitle = "عبر المحادثة — يُعبّأ قالب الطلب في حقل الإدخال ثم ترسله",
            status = if (facts.hasActiveLlm) {
                ChatCapabilityStatus.AVAILABLE
            } else {
                ChatCapabilityStatus.UNAVAILABLE
            },
            reason = if (!facts.hasActiveLlm) ChatCapabilityReasons.NO_ACTIVE_LLM else null
        ),
        ChatCapabilityItem(
            key = ChatCapabilityKey.RESULT_TO_ARTIFACT,
            category = ChatCapabilityCategory.CREATION,
            title = "حفظ النتيجة كأثر",
            subtitle = "تحويل إجابة أو نتيجة إلى أثر دائم قابل للتصفح",
            // §3: attachments DO register artifacts (the real import path);
            // a chat-side save-RESULT-as-artifact action is not wired yet —
            // honest disabled row, never hidden.
            status = ChatCapabilityStatus.UNAVAILABLE,
            reason = ChatCapabilityReasons.RESULT_TO_ARTIFACT
        )
    )

    /** Whether a hub row is clickable (ONLY Available rows are). */
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
        val headerSessionsButton: Boolean,
        /** True when an active artifact gets a dedicated permanent pane. */
        val artifactPane: Boolean = false,
        /** True when an active artifact falls back to a sheet. */
        val artifactSheet: Boolean = false
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

    // ------------------------------------------------------------------
    // CHAT FINAL CLOSURE (§10 responsive topology): the EFFECTIVE pane
    // policy for the ACTUAL available width — a PURE function, unit-testable
    // without composition. The class topology (compact = chat + sheet,
    // medium = sessions + chat, expanded = sessions + chat + context) is the
    // BASELINE; fixed pane widths are replaced by proportional shares with
    // clamps, and a pane that cannot coexist with a USABLE chat column
    // (≥ [CHAT_MIN_WIDTH_DP]) is honestly downgraded: the context pane drops
    // first (its content stays reachable through the header's context sheet),
    // then the sessions pane falls back to the bottom-sheet surface (its
    // compact-equivalent). The chat column itself is NEVER squeezed to an
    // unusable strip by sibling panes.
    // ------------------------------------------------------------------

    /**
     * The minimum USABLE chat column. 360dp keeps the composer and one
     * comfortable reading column intact — a 700dp-class device (600dp after
     * the rail) keeps its sessions pane with a 380dp chat, while a 600dp-class
     * device honestly falls back to the sheet instead of a ~220dp strip.
     */
    const val CHAT_MIN_WIDTH_DP = 360

    private const val SESSIONS_PANE_MIN_DP = 220
    private const val SESSIONS_PANE_MAX_DP = 300
    private const val SESSIONS_PANE_FRACTION = 0.30f

    private const val CONTEXT_PANE_MIN_DP = 240
    private const val CONTEXT_PANE_MAX_DP = 280
    private const val CONTEXT_PANE_FRACTION = 0.22f

    private const val ARTIFACT_PANE_MIN_DP = 280
    private const val ARTIFACT_PANE_MAX_DP = 320
    private const val ARTIFACT_PANE_FRACTION = 0.26f

    /** The effective topology + concrete pane widths for an available width. */
    data class ChatPanePolicy(
        val panes: ChatPanes,
        /** 0 when the sessions pane is not shown. */
        val sessionsPaneWidthDp: Int,
        /** 0 when the context pane is not shown. */
        val contextPaneWidthDp: Int,
        /** 0 when the artifact is not rendered as a permanent pane. */
        val artifactPaneWidthDp: Int = 0
    )

    /**
     * Resolves the effective pane policy for [widthClass] at the ACTUAL
     * [availableWidthDp] (the width the chat shell measured, already net of
     * the navigation rail and shell paddings).
     *
     * ARTIFACT CANVAS (§10): [artifactActive] promotes the artifact preview
     * to a DEDICATED pane — but with the LOWEST pane priority (sessions
     * first, context second, artifact third): the artifact pane is added
     * only when the existing expanded topology already fits AND the
     * artifact's minimum still leaves the chat its usable column. Any other
     * topology honestly downgrades the artifact to the sheet surface.
     */
    fun panePolicyFor(
        widthClass: com.example.presentation.ui.navigation.NavWidthClass,
        availableWidthDp: Int,
        artifactActive: Boolean = false
    ): ChatPanePolicy {
        val available = availableWidthDp.coerceAtLeast(0)
        val compactPanes = panesFor(com.example.presentation.ui.navigation.NavWidthClass.COMPACT)
        val compactWithArtifact = compactPanes.copy(artifactSheet = artifactActive)
        return when (widthClass) {
            com.example.presentation.ui.navigation.NavWidthClass.COMPACT ->
                ChatPanePolicy(compactWithArtifact, sessionsPaneWidthDp = 0, contextPaneWidthDp = 0)

            com.example.presentation.ui.navigation.NavWidthClass.MEDIUM ->
                sidePaneWidth(
                    availableWidthDp = available,
                    fraction = SESSIONS_PANE_FRACTION,
                    minDp = SESSIONS_PANE_MIN_DP,
                    maxDp = SESSIONS_PANE_MAX_DP
                )?.let { sessionsWidth ->
                    ChatPanePolicy(
                        panes = panesFor(com.example.presentation.ui.navigation.NavWidthClass.MEDIUM).copy(
                            artifactSheet = artifactActive
                        ),
                        sessionsPaneWidthDp = sessionsWidth,
                        contextPaneWidthDp = 0
                    )
                } ?: ChatPanePolicy(compactWithArtifact, sessionsPaneWidthDp = 0, contextPaneWidthDp = 0)

            com.example.presentation.ui.navigation.NavWidthClass.EXPANDED -> {
                // Pane priority is sessions > context > artifact. The
                // artifact pane is added ONLY after the existing expanded
                // topology already preserves the usable chat column; it
                // never displaces the context or sessions panes.
                val contextWidth = sidePaneWidth(
                    availableWidthDp = available,
                    fraction = CONTEXT_PANE_FRACTION,
                    minDp = CONTEXT_PANE_MIN_DP,
                    maxDp = CONTEXT_PANE_MAX_DP
                )
                val sessionsWidthWithContext = contextWidth?.let { ctx ->
                    sidePaneWidth(
                        availableWidthDp = available - ctx,
                        fraction = SESSIONS_PANE_FRACTION,
                        minDp = SESSIONS_PANE_MIN_DP,
                        maxDp = SESSIONS_PANE_MAX_DP
                    )
                }
                if (contextWidth != null && sessionsWidthWithContext != null) {
                    val artifactWidth = if (artifactActive) {
                        sidePaneWidth(
                            availableWidthDp = available - contextWidth - sessionsWidthWithContext,
                            fraction = ARTIFACT_PANE_FRACTION,
                            minDp = ARTIFACT_PANE_MIN_DP,
                            maxDp = ARTIFACT_PANE_MAX_DP
                        )
                    } else null
                    ChatPanePolicy(
                        panes = panesFor(com.example.presentation.ui.navigation.NavWidthClass.EXPANDED).copy(
                            artifactPane = artifactWidth != null,
                            artifactSheet = artifactActive && artifactWidth == null
                        ),
                        sessionsPaneWidthDp = sessionsWidthWithContext,
                        contextPaneWidthDp = contextWidth,
                        artifactPaneWidthDp = artifactWidth ?: 0
                    )
                } else {
                    sidePaneWidth(
                        availableWidthDp = available,
                        fraction = SESSIONS_PANE_FRACTION,
                        minDp = SESSIONS_PANE_MIN_DP,
                        maxDp = SESSIONS_PANE_MAX_DP
                    )?.let { sessionsWidth ->
                        ChatPanePolicy(
                            panes = panesFor(com.example.presentation.ui.navigation.NavWidthClass.MEDIUM).copy(
                                artifactSheet = artifactActive
                            ),
                            sessionsPaneWidthDp = sessionsWidth,
                            contextPaneWidthDp = 0
                        )
                    } ?: ChatPanePolicy(compactWithArtifact, sessionsPaneWidthDp = 0, contextPaneWidthDp = 0)
                }
            }
        }
    }

    /**
     * The width of one side pane: its proportional share clamped to
     * [minDp, maxDp] — as long as the REMAINING chat column keeps at least
     * [CHAT_MIN_WIDTH_DP]; otherwise the pane shrinks to what the chat can
     * spare. Null when even the pane's minimum cannot coexist with a usable
     * chat column (the pane must be downgraded).
     */
    private fun sidePaneWidth(
        availableWidthDp: Int,
        fraction: Float,
        minDp: Int,
        maxDp: Int
    ): Int? {
        if (availableWidthDp < minDp + CHAT_MIN_WIDTH_DP) return null
        val proportional = (availableWidthDp * fraction).let {
            kotlin.math.round(it).toInt()
        }
        val clamped = proportional.coerceIn(minDp, maxDp)
        if (availableWidthDp - clamped >= CHAT_MIN_WIDTH_DP) return clamped
        // The proportional clamp squeezed the chat — shrink the pane to the
        // chat's actual spare capacity (still within its own bounds).
        return (availableWidthDp - CHAT_MIN_WIDTH_DP).coerceIn(minDp, maxDp)
    }
}
