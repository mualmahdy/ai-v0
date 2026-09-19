package com.example.presentation.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.R

/**
 * ============================================================================
 * TopLevelDestinations — the ONE navigation taxonomy (UI Design Closure,
 * phase C — defects D-03/D-04/D-10)
 * ============================================================================
 *
 * The bottom bar and the adaptive rail compose from THIS single list, so
 * the five primary destinations can never drift apart again. Icon identity
 * is part of the contract and is pinned by a test:
 *
 *  - HOME    → Icons.Default.Home (was Psychology — duplicated with
 *              CHAT, defect D-03)
 *  - CHAT    → Icons.AutoMirrored.Filled.Chat (directional glyph — flips
 *              with the pinned RTL layout)
 *  - PROJECTS→ Icons.Default.FolderOpen              (open-folder — distinct
 *              from the Explorer compass; defect D-04 family)
 *  - ACTIVITY→ Icons.Default.NotificationsActive
 *  - MORE    → Icons.Default.MoreHoriz
 *
 * AutoMirrored icons (Chat) flip with the RTL layout direction (the app
 * pins Rtl), which is the correct behavior for directional glyphs; Home is
 * a symmetric house and needs no mirroring.
 */
data class TopLevelDestination(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
    val tag: String
)

val topLevelDestinations: List<TopLevelDestination> = listOf(
    TopLevelDestination(
        route = WorkspaceRoutes.HOME,
        icon = Icons.Default.Home,
        labelRes = R.string.nav_home,
        tag = "nav_tab_home"
    ),
    TopLevelDestination(
        route = WorkspaceRoutes.STUDIO,
        icon = Icons.AutoMirrored.Filled.Chat,
        labelRes = R.string.nav_chat,
        tag = "nav_tab_chat"
    ),
    TopLevelDestination(
        route = WorkspaceRoutes.PROJECTS,
        icon = Icons.Default.FolderOpen,
        labelRes = R.string.nav_projects,
        tag = "nav_tab_projects"
    ),
    TopLevelDestination(
        route = WorkspaceRoutes.ACTIVITY,
        icon = Icons.Default.NotificationsActive,
        labelRes = R.string.nav_activity,
        tag = "nav_tab_activity"
    ),
    TopLevelDestination(
        route = WorkspaceRoutes.MORE,
        icon = Icons.Default.MoreHoriz,
        labelRes = R.string.nav_more,
        tag = "nav_tab_more"
    )
)

/**
 * The app's adaptive width classes (Material 3 window size class thresholds,
 * defect D-10):
 *
 *  - COMPACT  (< 600dp): bottom NavigationBar — the phone portrait shell;
 *  - MEDIUM   (600–839dp): side NavigationRail — tablets/folders unfolded
 *    and phones in landscape keep the content height;
 *  - EXPANDED (≥ 840dp): side NavigationRail — desktop-class widths.
 *
 * The thresholds are the canonical M3 breakpoints (600/840dp). Computed
 * from the live configuration so the shell re-shells on rotation/resize.
 */
enum class NavWidthClass { COMPACT, MEDIUM, EXPANDED }

fun navWidthClassForWidthDp(widthDp: Int): NavWidthClass = when {
    widthDp < 600 -> NavWidthClass.COMPACT
    widthDp < 840 -> NavWidthClass.MEDIUM
    else -> NavWidthClass.EXPANDED
}
