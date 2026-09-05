package com.example.domain.core.workspace.context

import com.example.domain.core.workspace.ResourceEdge
import com.example.domain.core.workspace.ResourceGraph
import com.example.domain.core.workspace.ResourceNode
import com.example.domain.core.workspace.ResourceType

/**
 * ============================================================================
 * Workspace Intelligence Domain Models — Phase 5
 * ============================================================================
 *
 * Turns the previously-empty `Workspace.resourceGraph` into a live,
 * observable context engine. The audit found that `ResourceGraph` was
 * always `ResourceGraph()` (empty) and that `ResourceEdgeDao` was never
 * called by any service. This module:
 *
 *   1. Defines `WorkspaceEvent` — a sealed event stream for resource
 *      add/update/remove so the UI can render an activity feed.
 *   2. Defines `WorkspaceContextSnapshot` — a per-workspace active-context
 *      window summarizing which resources are currently "hot" (used in the
 *      last N minutes) and what dependencies exist between them.
 *   3. Defines `ProactiveSuggestion` — a structured suggestion the engine
 *      can emit ("you have an unused embedding provider", "this task has
 *      been waiting 30 minutes, do you want to cancel?", etc.).
 */

/**
 * Sealed event stream emitted by `WorkspaceContextEngine` whenever a
 * resource is added/updated/removed, or whenever an execution produces
 * a notable side-effect (new file, new memory, new knowledge document).
 *
 * These events are persisted to `execution_trace_nodes` (via the
 * TelemetryService) AND emitted to a hot StateFlow so the Unified
 * Activity Feed UI can render them in real time.
 */
sealed interface WorkspaceEvent {
    val workspaceId: String
    val occurredAtEpochMs: Long

    data class ResourceAdded(
        override val workspaceId: String,
        val node: ResourceNode,
        override val occurredAtEpochMs: Long = System.currentTimeMillis()
    ) : WorkspaceEvent

    data class ResourceUpdated(
        override val workspaceId: String,
        val node: ResourceNode,
        val changeDescription: String,
        override val occurredAtEpochMs: Long = System.currentTimeMillis()
    ) : WorkspaceEvent

    data class ResourceRemoved(
        override val workspaceId: String,
        val resourceId: String,
        val resourceType: ResourceType,
        override val occurredAtEpochMs: Long = System.currentTimeMillis()
    ) : WorkspaceEvent

    data class EdgeAdded(
        override val workspaceId: String,
        val edge: ResourceEdge,
        override val occurredAtEpochMs: Long = System.currentTimeMillis()
    ) : WorkspaceEvent

    data class ExecutionActivity(
        override val workspaceId: String,
        val executionId: String,
        val agentId: String?,
        val actionType: String,
        val summary: String,
        override val occurredAtEpochMs: Long = System.currentTimeMillis()
    ) : WorkspaceEvent

    data class SuggestionEmitted(
        override val workspaceId: String,
        val suggestion: ProactiveSuggestion,
        override val occurredAtEpochMs: Long = System.currentTimeMillis()
    ) : WorkspaceEvent
}

/**
 * Per-workspace active-context window. Built by `WorkspaceContextEngine`
 * and rebuilt whenever a `WorkspaceEvent` arrives.
 *
 * - `activeResources` = resources touched in the last `windowMs` ms.
 * - `recentEvents` = the last N events for the activity feed.
 * - `dependencySummary` = a flattened view of the resource graph edges
 *   (so the UI can render "Task T uses Tool X" without graph traversal).
 */
data class WorkspaceContextSnapshot(
    val workspaceId: String,
    val graph: ResourceGraph,
    val activeResources: List<ResourceNode>,
    val recentEvents: List<WorkspaceEvent>,
    val dependencySummary: List<DependencyLink>,
    val activeTaskCount: Int,
    val activeAgentCount: Int,
    val knowledgeDocumentCount: Int,
    val memoryCount: Int,
    val lastUpdatedEpochMs: Long
)

/**
 * Flat representation of a graph edge, suitable for direct UI rendering.
 */
data class DependencyLink(
    val sourceId: String,
    val sourceName: String,
    val sourceType: ResourceType,
    val targetId: String,
    val targetName: String,
    val targetType: ResourceType,
    val edgeType: String,
    val weight: Float
)

/**
 * Severity for a proactive suggestion.
 */
enum class SuggestionSeverity { INFO, WARN, ACTION_REQUIRED, CRITICAL }

/**
 * Kind of suggestion — drives which icon/action the UI shows.
 */
enum class SuggestionKind {
    UNUSED_RESOURCE,
    STALE_RESOURCE,
    FAILED_EXECUTION,
    HIGH_LATENCY,
    APPROACHING_BUDGET,
    CONFIGURATION_GAP,
    KNOWLEDGE_GAP,
    AGENT_AVAILABILITY,
    SECURITY_REMINDER,
    PROVIDER_FAILOVER_CANDIDATE
}

/**
 * Structured proactive suggestion. Emitted by `WorkspaceContextEngine`
 * based on heuristics over the live context snapshot.
 *
 * Example emissions:
 *   - "نموذج التضمين المسجل لم يُستخدم منذ 7 أيام — هل تريد تعطيله؟"
 *   - "هذه المهمة تنتظر منذ 30 دقيقة — هل تريد إلغائها؟"
 *   - "مزود Gemini أعاد 5 أخطاء متتالية — يُنصح بتفعيل مزود احتياطي."
 */
data class ProactiveSuggestion(
    val id: String,
    val workspaceId: String,
    val kind: SuggestionKind,
    val severity: SuggestionSeverity,
    val titleAr: String,
    val descriptionAr: String,
    val targetResourceId: String? = null,
    val targetResourceType: ResourceType? = null,
    val recommendedAction: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Configuration for the context engine.
 */
data class WorkspaceContextConfig(
    val activityWindowMs: Long = 30L * 60 * 1000, // 30 min
    val maxRecentEvents: Int = 100,
    val maxSuggestionsPerSnapshot: Int = 10,
    val staleResourceThresholdMs: Long = 7L * 24 * 60 * 60 * 1000, // 7 days
    val longRunningTaskThresholdMs: Long = 30L * 60 * 1000, // 30 min
    val highFailureRateThreshold: Float = 0.3f,
    val budgetApproachingLimitThreshold: Float = 0.85f
)
