package com.example.presentation.ui.navigation

/**
 * ============================================================================
 * WorkspaceRoutes — the real navigation graph of the smart workspace
 * ============================================================================
 *
 * Five context-centric primary destinations (bottom bar) plus secondary
 * sections reachable from the "المزيد" dashboard. Every destination maps to
 * a real backend capability surface — nothing is decorative.
 */
object WorkspaceRoutes {
    const val STUDIO = "studio"
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

    /** UNIFIED OBJECT EXPLORER (report gap: discoverability). */
    const val EXPLORER = "explorer"

    val topLevel: List<String> = listOf(STUDIO, ACTIVITY, KNOWLEDGE, FILES, MORE)

    fun isTopLevel(route: String?): Boolean = topLevel.contains(route)
}
