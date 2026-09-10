package com.example.domain.core.project

import com.example.domain.core.context.ResourceHealthState

/**
 * REPAIR ORDER §27 — safe project lifecycle. Irreversible deletion is
 * NEVER the first/default action; destructive states come after explicit,
 * separately-confirmed transitions.
 *
 *   ACTIVE ──archive──▶ ARCHIVED ──restore──▶ ACTIVE
 *      │                    │
 *      └────trash──────────┴───▶ TRASHED ──restore──▶ ACTIVE (pre-trash state)
 *                                    │
 *                                    ├─delete─▶ DELETED (rows removed, purge scheduled)
 *                                    └─purge──▶ PURGED  (irreversible, data destroyed)
 */
enum class ProjectLifecycleState {
    ACTIVE,
    ARCHIVED,
    TRASHED,
    DELETED,
    PURGED;

    val isSelectable: Boolean get() = this == ACTIVE
    val isMutablyVisible: Boolean get() = this == ACTIVE || this == ARCHIVED
}

/**
 * REPAIR ORDER §16 — resource locality, separated honestly.
 * ON_DEVICE: the compute runs on this Android device.
 * LAN: another device on the local network.
 * EMULATOR_HOST: 10.0.2.2-style loopback to the emulator's host machine —
 *                NOT on-device, and must never be labelled as such.
 * REMOTE: internet cloud endpoint.
 */
enum class ResourceLocality {
    ON_DEVICE,
    LAN,
    EMULATOR_HOST,
    REMOTE,
    UNKNOWN
}

/**
 * REPAIR ORDER §24 — explicit project dependency classification.
 */
enum class DependencyRequirement { REQUIRED, OPTIONAL }

enum class DependencyStatus { RESOLVED, MISSING, INCOMPATIBLE }

enum class ProjectDependencyType {
    MODEL,
    PROVIDER,
    EMBEDDING_MODEL,
    TOOL,
    WORKFLOW,
    AGENT,
    SHARED_ARTIFACT
}

data class ProjectDependency(
    val id: Long = 0L,
    val projectId: Long,
    val type: ProjectDependencyType,
    /** Stable key: model resource id, tool name, agent id… */
    val key: String,
    val requirement: DependencyRequirement,
    val status: DependencyStatus,
    val detail: String? = null,
    val metadataJson: String = "{}"
)

/**
 * REPAIR ORDER §25 — project readiness, derived from authoritative
 * runtime/resource state, never manually asserted by UI.
 */
enum class ProjectReadinessState { READY, DEGRADED, BLOCKED }

data class ProjectReadinessReport(
    val projectId: Long,
    val state: ProjectReadinessState,
    val findings: List<ReadinessFinding>
) {
    data class ReadinessFinding(
        val component: String,
        val state: ResourceHealthState,
        val message: String
    )

    val blockingCount: Int get() = findings.count { it.state == ResourceHealthState.UNAVAILABLE || it.state == ResourceHealthState.MISCONFIGURED }
}

/**
 * First-class domain Project (the previous ProjectMetadata was a UI DTO
 * with no ownership or lifecycle). `workspaceId` is REQUIRED: a project
 * without an owning workspace is an orphan the repair center must handle.
 */
data class Project(
    val id: Long,
    val workspaceId: String,
    val name: String,
    val description: String? = null,
    val rootPath: String = "",
    val lifecycleState: ProjectLifecycleState = ProjectLifecycleState.ACTIVE,
    val createdAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val archivedAtEpochMs: Long? = null,
    val trashedAtEpochMs: Long? = null
)

/**
 * A project snapshot (REPAIR ORDER §26) — point-in-time protective state
 * bundle used before destructive operations (move, large imports,
 * migrations, bulk changes). Excludes secrets by construction.
 */
data class ProjectSnapshot(
    val id: String,
    val projectId: Long,
    val workspaceId: String,
    val label: String,
    val reason: String,
    val manifestJson: String,
    val contentHash: String,
    val createdAtEpochMs: Long
)
