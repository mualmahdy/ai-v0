package com.example.domain.core.extension.lifecycle

/**
 * ============================================================================
 * Extension Lifecycle Domain Models — Phase 5 (P1)
 * ============================================================================
 *
 * Closes the MCP/Extensions gap (audit: 40–45% → ~55%) by adding:
 *
 *   1. Full extension lifecycle state machine:
 *      INSTALLED → CONFIGURED → CONNECTED → HEALTHY → DISABLED → REMOVED
 *      (the audit found only `toggle*` and `registerNewMcpServer`).
 *
 *   2. Version compatibility check (the audit found `PluginManifest.version`
 *      and `SkillManifest.version` were strings with no compatibility check).
 *
 *   3. Capability negotiation result — what the server declared vs.
 *      what we actually got.
 *
 *   4. Periodic health monitoring config.
 */

enum class ExtensionLifecycleState(val storageCode: String) {
    INSTALLED("INSTALLED"),
    CONFIGURED("CONFIGURED"),
    CONNECTED("CONNECTED"),
    HEALTHY("HEALTHY"),
    DEGRADED("DEGRADED"),
    DISABLED("DISABLED"),
    REMOVED("REMOVED");

    companion object {
        fun fromStorageCode(code: String): ExtensionLifecycleState =
            entries.firstOrNull { it.storageCode == code } ?: INSTALLED
    }
}

data class ExtensionVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<ExtensionVersion> {
    override fun compareTo(other: ExtensionVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }
    override fun toString(): String = "$major.$minor.$patch"
    companion object {
        fun parse(s: String): ExtensionVersion? {
            val parts = s.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.size != 3) return null
            return ExtensionVersion(parts[0], parts[1], parts[2])
        }
    }
}

data class VersionCompatibility(
    val extensionVersion: ExtensionVersion,
    val hostVersion: ExtensionVersion,
    val isCompatible: Boolean,
    val reason: String
)

data class CapabilityNegotiationResult(
    val extensionId: String,
    val declaredCapabilities: List<String>,
    val grantedCapabilities: List<String>,
    val deniedCapabilities: List<String>,
    val isFullyNegotiated: Boolean
)

data class HealthMonitorConfig(
    val checkIntervalMs: Long = 5L * 60 * 1000, // 5 minutes
    val timeoutMs: Long = 10_000L,
    val failureThreshold: Int = 3,
    val recoveryThreshold: Int = 2
)

data class ExtensionHealthSnapshot(
    val extensionId: String,
    val isHealthy: Boolean,
    val lastCheckedAtEpochMs: Long,
    val consecutiveFailures: Int,
    val consecutiveSuccesses: Int,
    val latencyMs: Long,
    val errorMessage: String?
)

data class ExtensionInstallRequest(
    val extensionId: String,
    val name: String,
    val version: String,
    val manifestJson: String,
    val installedBy: String
)

data class ExtensionInstallResult(
    val extensionId: String,
    val isSuccessful: Boolean,
    val installedVersion: String,
    val reason: String
)

data class ExtensionUpdateResult(
    val extensionId: String,
    val previousVersion: String,
    val newVersion: String,
    val isSuccessful: Boolean,
    val reason: String
)

data class ExtensionRemovalResult(
    val extensionId: String,
    val isSuccessful: Boolean,
    val cleanedUpResources: List<String>,
    val reason: String
)
