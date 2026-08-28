package com.example.domain.core.extension

/**
 * Plugin lifecycle states as required by AI-V0 Platform:
 * DISCOVERED, INSPECTED, VALIDATED, INSTALLED, ENABLED, VERIFIED, DISABLED, REMOVED
 */
enum class PluginState {
    DISCOVERED,
    INSPECTED,
    VALIDATED,
    INSTALLED,
    ENABLED,
    VERIFIED,
    DISABLED,
    REMOVED
}

/**
 * Plugin manifest representing an extensibility package.
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val packageUrl: String? = null,
    val declaredTools: List<String> = emptyList(),
    val declaredProviders: List<String> = emptyList(),
    val declaredIntegrations: List<String> = emptyList(),
    val requiredPermissions: List<String> = emptyList(),
    val state: PluginState = PluginState.DISCOVERED,
    val trustLevel: String = "SANDBOXED", // SANDBOXED, VERIFIED, TRUSTED
    val signature: String? = null,
    val author: String = "External Contributor",
    val installedTimestampMs: Long? = null
)
