package com.example.presentation.state

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.radar.OperationalCapabilityState
import com.example.domain.core.radar.RadarCapabilityCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * ChatCapabilityPolicyTest — CHAT CAPABILITIES Task 2 §4 (the platform
 * availability policy) + §7 (Vision honesty) + §19 (adaptive layout)
 * ============================================================================
 *
 * The REQUIRED test areas covered here (Task 2 §21):
 *  1. capability availability — Available rows are clickable;
 *  2. unavailable reason — every Unavailable row carries the REAL reason;
 *  3. planned state — "قريباً" appears ONLY for genuinely planned caps;
 *  27. unavailable vision behavior — VISION is PLANNED with the radar's
 *      honest rationale (implemented=false ⇒ never faked as available);
 *  26. adaptive layout state — the pane topology per width class;
 *  28. error/degraded capability behavior — degraded hints stay clickable.
 */
class ChatCapabilityPolicyTest {

    private fun radarCheck(
        capability: CapabilityType,
        state: OperationalCapabilityState,
        rationale: String
    ) = RadarCapabilityCheck(
        capabilityKey = capability.code,
        state = state,
        isExecutable = state == OperationalCapabilityState.AVAILABLE ||
            state == OperationalCapabilityState.DEGRADED,
        requiresFallback = state == OperationalCapabilityState.DEGRADED ||
            state == OperationalCapabilityState.PARTIAL,
        rationale = rationale
    )

    private fun facts(
        block: ChatCapabilityFacts.() -> ChatCapabilityFacts = { this }
    ): ChatCapabilityFacts = ChatCapabilityFacts().block()

    // ------------------------------------------------------------------
    // 1 — capability availability (§21.1)
    // ------------------------------------------------------------------

    @Test
    fun `a fully provisioned workspace resolves every entry point available`() {
        val resolved = ChatCapabilityPolicy.resolve(
            facts {
                copy(
                    activeProjectId = 7L,
                    knowledgeDocumentCount = 3,
                    enabledSkillCount = 2,
                    registeredToolCount = 4,
                    mcpServerCount = 3,
                    searchProviderWired = true,
                    isNetworkAvailable = true
                )
            }
        )
        assertEquals(8, resolved.size)
        resolved
            .filter { it.key != ChatCapabilityKey.VISION_ANALYSIS }
            .forEach { item ->
                assertEquals(
                    "expected AVAILABLE for ${item.key}: ${item.reason}",
                    ChatCapabilityStatus.AVAILABLE,
                    item.status
                )
                assertTrue(
                    "only AVAILABLE rows are clickable (${item.key})",
                    ChatCapabilityPolicy.isClickable(item)
                )
                assertNull(
                    "AVAILABLE rows carry NO unavailable reason (${item.key})",
                    item.reason
                )
            }
    }

    // ------------------------------------------------------------------
    // 2 — unavailable reason (§21.2)
    // ------------------------------------------------------------------

    @Test
    fun `every unavailable row carries its real reason and is not clickable`() {
        val resolved = ChatCapabilityPolicy.resolve(
            facts {
                copy(
                    activeProjectId = null,          // no active project
                    knowledgeDocumentCount = 0,      // empty corpus
                    enabledSkillCount = 0,           // no skills
                    registeredToolCount = 0,          // no tools
                    mcpServerCount = 0,               // no MCP servers
                    searchProviderWired = false       // no search provider
                )
            }
        )
        val expectedReasons = mapOf(
            ChatCapabilityKey.ATTACH_FILE to "مشروعاً نشطاً",
            ChatCapabilityKey.ATTACH_FOLDER to "مشروعاً نشطاً",
            ChatCapabilityKey.KNOWLEDGE_RETRIEVAL to "لا توجد مستندات مفهرسة",
            ChatCapabilityKey.SEARCH_INTELLIGENCE to "مزود بحث",
            ChatCapabilityKey.SKILLS to "لا توجد مهارات",
            ChatCapabilityKey.TOOLS to "لا توجد أدوات",
            ChatCapabilityKey.MCP_SERVERS to "لا توجد خوادم MCP"
        )
        expectedReasons.forEach { (key, reasonFragment) ->
            val item = resolved.first { it.key == key }
            assertEquals(
                "expected UNAVAILABLE for $key",
                ChatCapabilityStatus.UNAVAILABLE,
                item.status
            )
            assertNotNull("$key must carry a reason", item.reason)
            assertTrue(
                "$key reason must be the real one (got: ${item.reason})",
                item.reason!!.contains(reasonFragment)
            )
            assertFalse("UNAVAILABLE rows are never clickable", ChatCapabilityPolicy.isClickable(item))
        }
    }

    @Test
    fun `folder attach reports the real gap when the zipper is not wired`() {
        val resolved = ChatCapabilityPolicy.resolve(
            facts {
                copy(activeProjectId = 7L, folderAttachSupported = false)
            }
        )
        val folder = resolved.first { it.key == ChatCapabilityKey.ATTACH_FOLDER }
        assertEquals(ChatCapabilityStatus.UNAVAILABLE, folder.status)
        assertTrue(folder.reason!!.contains("مسار استيراد للمجلدات"))
        // The FILE attach stays available — one real gap never blocks the other.
        val file = resolved.first { it.key == ChatCapabilityKey.ATTACH_FILE }
        assertEquals(ChatCapabilityStatus.AVAILABLE, file.status)
    }

    // ------------------------------------------------------------------
    // 3 — planned state (§21.3) + 27 — unavailable vision behavior (§21.27)
    // ------------------------------------------------------------------

    @Test
    fun `vision is PLANNED with the radar rationale — never faked as available`() {
        val resolved = ChatCapabilityPolicy.resolve(
            facts {
                copy(
                    radarChecks = mapOf(
                        CapabilityType.VISION to radarCheck(
                            CapabilityType.VISION,
                            OperationalCapabilityState.PLANNED,
                            "قدرة مخططة فقط (لا تنفيذ)"
                        )
                    )
                )
            }
        )
        val vision = resolved.first { it.key == ChatCapabilityKey.VISION_ANALYSIS }
        assertEquals(ChatCapabilityStatus.PLANNED, vision.status)
        assertFalse("PLANNED rows are never clickable", ChatCapabilityPolicy.isClickable(vision))
        assertEquals("قدرة مخططة فقط (لا تنفيذ)", vision.reason)

        // Even a fully provisioned workspace NEVER promotes Vision.
        val provisioned = ChatCapabilityPolicy.resolve(
            facts {
                copy(
                    activeProjectId = 7L,
                    radarChecks = mapOf(
                        CapabilityType.VISION to radarCheck(
                            CapabilityType.VISION,
                            OperationalCapabilityState.PLANNED,
                            "قدرة مخططة فقط (لا تنفيذ)"
                        )
                    )
                )
            }
        )
        assertEquals(
            ChatCapabilityStatus.PLANNED,
            provisioned.first { it.key == ChatCapabilityKey.VISION_ANALYSIS }.status
        )
    }

    @Test
    fun `vision planned label is honest even without a radar check`() {
        val resolved = ChatCapabilityPolicy.resolve(facts())
        val vision = resolved.first { it.key == ChatCapabilityKey.VISION_ANALYSIS }
        assertEquals(ChatCapabilityStatus.PLANNED, vision.status)
        assertNotNull(vision.reason)
        assertTrue(vision.reason!!.contains("لا تنفيذ"))
    }

    // ------------------------------------------------------------------
    // 28 — degraded capability behavior (§21.28)
    // ------------------------------------------------------------------

    @Test
    fun `an offline session keeps search AVAILABLE but honestly degraded`() {
        val resolved = ChatCapabilityPolicy.resolve(
            facts {
                copy(searchProviderWired = true, isNetworkAvailable = false)
            }
        )
        val search = resolved.first { it.key == ChatCapabilityKey.SEARCH_INTELLIGENCE }
        assertEquals(ChatCapabilityStatus.AVAILABLE, search.status)
        assertTrue("degraded search stays clickable", ChatCapabilityPolicy.isClickable(search))
        assertTrue("the degraded hint is set", search.isDegraded)
    }

    @Test
    fun `knowledge stays available but degraded-hinted without the semantic engine`() {
        val resolved = ChatCapabilityPolicy.resolve(
            facts {
                copy(knowledgeDocumentCount = 5, semanticKnowledgeReady = false)
            }
        )
        val knowledge = resolved.first { it.key == ChatCapabilityKey.KNOWLEDGE_RETRIEVAL }
        assertEquals(ChatCapabilityStatus.AVAILABLE, knowledge.status)
        assertTrue(knowledge.isDegraded)
    }

    // ------------------------------------------------------------------
    // 26 — adaptive layout state (§21.26)
    // ------------------------------------------------------------------

    @Test
    fun `compact is chat-first — sessions are a sheet, no panes`() {
        val panes = ChatAdaptiveLayout.panesFor(
            com.example.presentation.ui.navigation.NavWidthClass.COMPACT
        )
        assertFalse(panes.sessionsPane)
        assertTrue(panes.sessionsSheet)
        assertFalse(panes.contextPane)
        assertTrue(panes.headerSessionsButton)
    }

    @Test
    fun `medium shows the sessions pane and hides the sheet + header button`() {
        val panes = ChatAdaptiveLayout.panesFor(
            com.example.presentation.ui.navigation.NavWidthClass.MEDIUM
        )
        assertTrue(panes.sessionsPane)
        assertFalse(panes.sessionsSheet)
        assertFalse(panes.contextPane)
        assertFalse("no duplicated sessions control at medium+", panes.headerSessionsButton)
    }

    @Test
    fun `expanded adds the context-execution pane`() {
        val panes = ChatAdaptiveLayout.panesFor(
            com.example.presentation.ui.navigation.NavWidthClass.EXPANDED
        )
        assertTrue(panes.sessionsPane)
        assertFalse(panes.sessionsSheet)
        assertTrue(panes.contextPane)
        assertFalse(panes.headerSessionsButton)
    }

    @Test
    fun `the width-class breakpoints are the M3 ones the shell uses`() {
        assertEquals(
            com.example.presentation.ui.navigation.NavWidthClass.COMPACT,
            com.example.presentation.ui.navigation.navWidthClassForWidthDp(599)
        )
        assertEquals(
            com.example.presentation.ui.navigation.NavWidthClass.MEDIUM,
            com.example.presentation.ui.navigation.navWidthClassForWidthDp(600)
        )
        assertEquals(
            com.example.presentation.ui.navigation.NavWidthClass.EXPANDED,
            com.example.presentation.ui.navigation.navWidthClassForWidthDp(840)
        )
    }

    // ------------------------------------------------------------------
    // The menu structure itself (§3 — categorized, not a giant list)
    // ------------------------------------------------------------------

    @Test
    fun `the menu is categorized into the three agreed groups`() {
        val resolved = ChatCapabilityPolicy.resolve(facts())
        val categories = resolved.map { it.category }.distinct()
        assertEquals(
            listOf(
                ChatCapabilityCategory.FILES_AND_MEDIA,
                ChatCapabilityCategory.KNOWLEDGE,
                ChatCapabilityCategory.INTELLIGENCE
            ),
            categories
        )
    }
}
