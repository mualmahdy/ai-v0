package com.example.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import com.example.domain.core.session.ChatMode
import com.example.domain.core.session.ConversationSession
import com.example.domain.core.session.ConversationSessionId
import com.example.presentation.ui.screens.home.HomeScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ============================================================================
 * HomeWorkCenterTest — the work center's honest composition (UI Design
 * Closure, phase B — D-05)
 * ============================================================================
 *
 * The Home screen is a PURE composable over evidence-derived values (the
 * shell wires it to the projects/sessions/studio feature owners), so this
 * suite composes it directly with REAL state shapes:
 *
 *  - the live context header (workspace + REAL project name — the D-02
 *    honest hierarchy at the home surface);
 *  - the honest EMPTY state when no sessions exist (and NO resume button —
 *    no fabricated capability);
 *  - resume-last + the recent-sessions list when sessions exist, with the
 *    open callback firing the RIGHT session id;
 *  - the honest no-project note when the workspace has no active project.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class HomeWorkCenterTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun session(id: String, title: String, turns: Int = 3): ConversationSession =
        ConversationSession(
            id = ConversationSessionId(id),
            workspaceId = "default",
            title = title,
            mode = ChatMode.QUICK_CHAT,
            turnCount = turns,
            projectId = 1L,
            createdAtEpochMs = 1_700_000_000_000L,
            lastActiveAtEpochMs = 1_700_000_100_000L
        )

    @Test
    fun `the context header shows the workspace and the REAL project name`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MyApplicationTheme {
                    HomeScreen(
                        workspaceName = "مساحة الاختبار",
                        currentProjectName = "مشروع مساحة العمل الافتراضية",
                        recentSessions = emptyList(),
                        onNewSession = {},
                        onOpenSession = {},
                        onNavigate = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        composeRule.onNodeWithTag("home_context_header").assertIsDisplayed()
        composeRule.onNodeWithTag("home_project_card").assertIsDisplayed()
        // The REAL project name (never the workspace name relabeled).
        composeRule.onNodeWithTextSubstring("مشروع مساحة العمل الافتراضية").assertIsDisplayed()
    }

    @Test
    fun `no sessions - the honest empty state and NO resume button`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MyApplicationTheme {
                    HomeScreen(
                        workspaceName = "مساحة الاختبار",
                        currentProjectName = null,
                        recentSessions = emptyList(),
                        onNewSession = {},
                        onOpenSession = {},
                        onNavigate = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        composeRule.onNodeWithTag("home_sessions_empty").assertIsDisplayed()
        // No fabricated resume affordance when there is nothing to resume.
        assert(composeRule.onAllNodesWithTag("btn_home_resume_last").fetchSemanticsNodes().isEmpty())
        // The honest no-project note (D-02: never a relabeled workspace).
        composeRule.onNodeWithTextSubstring("لا مشروع نشط").assertIsDisplayed()
    }

    @Test
    fun `with sessions - resume last, the recent list, and the RIGHT open callback`() {
        val opened = mutableListOf<String>()
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MyApplicationTheme {
                    HomeScreen(
                        workspaceName = "مساحة الاختبار",
                        currentProjectName = "المشروع الأول",
                        recentSessions = listOf(
                            session("sess_a", "أول جلسة"),
                            session("sess_b", "ثاني جلسة")
                        ),
                        onNewSession = {},
                        onOpenSession = { opened.add(it) },
                        onNavigate = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        composeRule.onNodeWithTag("btn_home_resume_last").assertIsDisplayed()

        // Opening a specific recent session fires ITS id (scroll the lazy
        // list to the card first — below-the-fold cards are still real).
        composeRule.onNodeWithTag("home_session_sess_a").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("home_session_sess_b").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("sess_b"), opened)
    }
}

/** Assertion helper: a node whose text CONTAINS the substring is displayed. */
private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.onNodeWithTextSubstring(
    substring: String
): androidx.compose.ui.test.SemanticsNodeInteraction =
    onAllNodes(androidx.compose.ui.test.hasText(substring, substring = true))
        .fetchSemanticsNodes()
        .let { nodes ->
            if (nodes.isEmpty()) {
                throw AssertionError("No node with text containing '$substring'")
            }
            onAllNodes(androidx.compose.ui.test.hasText(substring, substring = true))[0]
        }
