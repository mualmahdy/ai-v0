package com.example.presentation.ui.navigation

/**
 * ============================================================================
 * WorkspaceRoutes — the real navigation graph of the smart workspace
 * ============================================================================
 *
 * Five context-centric primary destinations (the bottom NavigationBar on
 * compact widths / the side NavigationRail on medium+ widths — composed
 * from the ONE topLevelDestinations taxonomy): home / chat(studio) /
 * projects / activity / more.
 *
 * Secondary sections are reachable from the "المزيد" capability center's
 * three groups (Workspace / Intelligence / Governance & System). Every
 * destination maps to a real backend capability surface — nothing is
 * decorative.
 */
object WorkspaceRoutes {
    const val HOME = "home"
    const val STUDIO = "studio"
    const val PROJECTS = "projects"
    const val ACTIVITY = "activity"
    const val KNOWLEDGE = "knowledge"
    const val FILES = "files"
    const val MORE = "more"

    const val PROVIDERS = "providers"
    const val TASKS = "tasks"
    const val DECISION = "decision"
    const val RADAR = "radar"
    const val GOVERNANCE = "governance"
    const val EXTENSIONS = "extensions"
    const val SETTINGS = "settings"

    /** CLOSURE §11 — System Health (repair / recovery surface). */
    const val HEALTH = "health"

    /** UNIFIED OBJECT EXPLORER (report gap: discoverability). */
    const val EXPLORER = "explorer"

    val topLevel: List<String> = listOf(HOME, STUDIO, PROJECTS, ACTIVITY, MORE)

    fun isTopLevel(route: String?): Boolean = topLevel.contains(route)
}
