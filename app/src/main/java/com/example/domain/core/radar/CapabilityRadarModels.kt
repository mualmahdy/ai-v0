package com.example.domain.core.radar

import com.example.domain.core.capability.CapabilityType

/**
 * ============================================================================
 * Capability & Evolution Radar — Domain Models (Governance Phase)
 * ============================================================================
 *
 * DISTINCTION FROM `RadarModels.kt` (same package):
 *   - `RadarModels.kt` models the EXTERNAL ECOSYSTEM discovery feed
 *     (RadarItem = a release/paper/repo discovered on the internet). That is
 *     "what exists outside".
 *   - THIS file models the INTERNAL OPERATIONAL capability radar: what the
 *     system can actually DO right now, derived from evidence. That is
 *     "what works inside".
 *
 * Truthfulness contract (Governance Phase, Section 1 of the directive):
 *
 *   Declared capability        != Operational capability
 *   Implemented                != Provisioned
 *   Provisioned                != Runtime-validated
 *   Runtime-validated          != Production-proven
 *   Passing JVM/Robolectric    != Android production readiness
 *
 * A capability is NEVER reported AVAILABLE merely because a class, interface,
 * provider, model, or UI control exists. State is DERIVED from evidence.
 */

/**
 * Operational state of a capability, derived from evidence + live registry
 * facts. Semantically richer than the legacy 3-value `CapabilityState`
 * (AVAILABLE/DEGRADED/UNAVAILABLE) which remains in use for per-resource
 * descriptors; this radar-level state captures WHY a capability is where it
 * is.
 *
 * State meanings:
 *   UNKNOWN    — no evidence and no declaration: the radar cannot say anything.
 *   PLANNED    — declared as a roadmap capability; no implementation exists.
 *   AVAILABLE  — implemented, provisioned, runtime-validated (recent success
 *                evidence), policy allows it, and at least one contributing
 *                resource is usable.
 *   PARTIAL    — implemented and (partially) provisioned but NOT runtime
 *                validated yet, or only some contributing resources are
 *                usable, or the UI exposure is missing while backend works.
 *   DEGRADED   — was operational (has success evidence) but recent evidence
 *                or live health shows failures / fallbacks (e.g. embedding
 *                model unavailable → lexical fallback active).
 *   BLOCKED    — policy, permission, or a hard dependency prevents execution
 *                (e.g. OFFLINE policy blocks cloud LLM capability).
 *   FAILED     — resources exist and were attempted but ALL recent evidence
 *                is failure; recovery not yet observed.
 *   DISABLED   — all contributing resources were explicitly disabled by the
 *                operator (control plane), not by failure.
 *   DEPRECATED — declared deprecated; still may work but is scheduled for
 *                removal.
 */
enum class OperationalCapabilityState {
    UNKNOWN,
    PLANNED,
    AVAILABLE,
    PARTIAL,
    DEGRADED,
    BLOCKED,
    FAILED,
    DISABLED,
    DEPRECATED
}

/**
 * The evaluation dimensions used to derive an [OperationalCapabilityState].
 * Each dimension is derived from a concrete source (declaration registry,
 * resource registry, evidence store, security policy, UI wiring) — never
 * hard-coded.
 *
 * null = dimension not evaluable for this capability (do not fabricate).
 */
data class CapabilityEvaluationDimensions(
    val declared: Boolean?,
    val implemented: Boolean?,
    val configured: Boolean?,
    val provisioned: Boolean?,
    val dependencyAvailable: Boolean?,
    val runtimeAvailable: Boolean?,
    val runtimeValidated: Boolean?,
    val resourceUsable: Boolean?,
    val policyAllowed: Boolean?,
    val healthConfirmed: Boolean?,
    val uiExposed: Boolean?,
    val evidenceFresh: Boolean?
) {
    companion object {
        val NONE = CapabilityEvaluationDimensions(
            declared = null, implemented = null, configured = null,
            provisioned = null, dependencyAvailable = null,
            runtimeAvailable = null, runtimeValidated = null,
            resourceUsable = null, policyAllowed = null,
            healthConfirmed = null, uiExposed = null, evidenceFresh = null
        )
    }
}

/**
 * Where a piece of capability evidence originated. Every value corresponds
 * to a REAL emission site in the runtime — no source is fabricated.
 */
enum class EvidenceSource {
    /** Successful/failed action execution observed on the orchestrator event bus. */
    ACTION_EXECUTION,
    /** Tool execution result (ToolResult event) — success or failure. */
    TOOL_EXECUTION,
    /** LLM call outcome (success / failure / degraded). */
    LLM_EXECUTION,
    /** Search action outcome. */
    SEARCH_EXECUTION,
    /** RAG retrieval outcome (semantic or lexical fallback). */
    RAG_RETRIEVAL,
    /** Embedding runtime outcome (including provisioning state changes). */
    EMBEDDING_RUNTIME,
    /** Provider/resource lifecycle transition from the control plane. */
    RESOURCE_LIFECYCLE,
    /** Model discovery result. */
    MODEL_DISCOVERY,
    /** Service connection test (probe) result. */
    HEALTH_CHECK,
    /** Security / permission decision affecting the capability. */
    POLICY_DECISION,
    /** Budget governance decision affecting the capability. */
    BUDGET_EVENT,
    /** Rate-limit encounter affecting the capability. */
    RATE_LIMIT_EVENT,
    /** Explicit user/operator action (enable/disable resource, grant…). */
    OPERATOR_ACTION,
    /** Android runtime fact (process restart, foreground constraints…). */
    PLATFORM_RUNTIME
}

/** Outcome carried by a piece of evidence. */
enum class EvidenceOutcome { SUCCESS, FAILURE, DEGRADED, NEUTRAL }

/**
 * One immutable, attributable observation supporting (or refuting) a
 * capability's operational state.
 *
 * Fields are optional where the originating subsystem cannot legitimately
 * know them — absence is truth, fabrication is forbidden.
 */
data class CapabilityEvidence(
    val id: String,
    val capabilityKey: String,
    val source: EvidenceSource,
    val outcome: EvidenceOutcome,
    val timestampEpochMs: Long,
    /** Reliability weight of the source observation, 0..1 (defaults honest). */
    val confidence: Float = 0.8f,
    val providerId: String? = null,
    val serviceId: String? = null,
    val modelId: String? = null,
    val resourceId: String? = null,
    val executionId: String? = null,
    val workspaceId: String? = null,
    val agentId: String? = null,
    /** Short human-readable detail (may be user-visible). */
    val detail: String? = null
)

/**
 * Derived health of a capability, computed from evidence outcome history —
 * distinct from the instantaneous state: a capability can be PARTIAL but
 * have HEALTHY trend on what does work, or AVAILABLE with FAILING trend.
 */
enum class CapabilityHealth { HEALTHY, DEGRADED_HEALTH, FAILING, UNAVAILABLE, UNKNOWN }

/** Trend of a capability's health across its recent evidence window. */
enum class CapabilityTrend { IMPROVING, STABLE, DETERIORATING, UNKNOWN }

/**
 * The authoritative derived radar view of ONE capability in ONE workspace
 * (or the global view when workspaceId == null).
 */
data class RadarCapabilityStatus(
    val capabilityKey: String,
    val workspaceId: String?,
    val state: OperationalCapabilityState,
    val dimensions: CapabilityEvaluationDimensions,
    val health: CapabilityHealth,
    val trend: CapabilityTrend,
    /** Number of evidence records considered in the last derivation. */
    val evidenceCount: Int,
    val lastEvidenceEpochMs: Long?,
    /** Resource identities currently contributing to this capability. */
    val contributingResourceIds: List<String>,
    /** Derived summary explaining WHY the state is what it is. */
    val rationale: String,
    val derivedAtEpochMs: Long
)

/** Kinds of capability change the radar detects (evolution detection). */
enum class CapabilityChangeType {
    NEWLY_AVAILABLE,
    DEGRADED,
    RESTORED,
    DISABLED_CHANGE,
    BLOCKED_CHANGE,
    UNBLOCKED,
    FAILED_CHANGE,
    DEPRECATED_CHANGE,
    EXPANDED,
    SHRUNK,
    RELIABILITY_CHANGED
}

/** A detected capability state transition (persisted; feeds evolution). */
data class CapabilityChangeRecord(
    val id: String,
    val capabilityKey: String,
    val workspaceId: String?,
    val fromState: OperationalCapabilityState,
    val toState: OperationalCapabilityState,
    val changeType: CapabilityChangeType,
    val evidenceId: String?,
    val detail: String,
    val detectedAtEpochMs: Long
)

/** Root cause taxonomy for capability gaps. */
enum class CapabilityGapReason {
    DECLARED_NOT_OPERATIONAL,
    DEPENDENCY_MISSING,
    RESOURCE_NOT_PROVISIONED,
    PROVIDER_UNAVAILABLE,
    TOOL_UNAVAILABLE,
    POLICY_BLOCKING,
    ANDROID_PLATFORM_LIMITATION,
    INSUFFICIENT_BUDGET,
    RATE_LIMITED,
    MISSING_UI_INTEGRATION,
    RUNTIME_VALIDATION_MISSING,
    NO_IMPLEMENTATION
}

/** A capability the system wants/needs but does not operationally have. */
data class CapabilityGap(
    val capabilityKey: String,
    val workspaceId: String?,
    val reason: CapabilityGapReason,
    val detail: String,
    val evidenceId: String?,
    val detectedAtEpochMs: Long
)

/** Kinds of actionable recommendations the radar can produce. */
enum class RadarRecommendationType {
    PROVISION_RESOURCE,
    ENABLE_FALLBACK,
    RETRY_VALIDATION,
    PROVIDER_UNAVAILABLE_ACTION,
    CAPABILITY_DEGRADED_ACTION,
    BUDGET_ACTION,
    RATE_LIMIT_ACTION,
    UI_EXPOSURE_ACTION,
    RELIABILITY_ACTION,
    DEPRECATION_ACTION
}

enum class RecommendationPriority { LOW, MEDIUM, HIGH, CRITICAL }

/**
 * An actionable, evidence-backed recommendation. Recommendations are
 * generated by rules over derived state + gaps + changes — never hard-coded
 * cosmetic strings.
 */
data class RadarRecommendation(
    val id: String,
    val capabilityKey: String,
    val workspaceId: String?,
    val type: RadarRecommendationType,
    val priority: RecommendationPriority,
    val message: String,
    /** Suggested follow-up action hint (UI/decision layer decides). */
    val actionHint: String?,
    val supportingEvidenceIds: List<String>,
    val createdAtEpochMs: Long,
    val isDismissed: Boolean = false
)

/**
 * A complete radar snapshot for one workspace (or the global scope).
 * Snapshots are derived on demand and persisted for history; the UI layer
 * renders THIS object, not ad-hoc scattered state.
 */
data class RadarSnapshot(
    val workspaceId: String?,
    val takenAtEpochMs: Long,
    val capabilities: List<RadarCapabilityStatus>,
    val gaps: List<CapabilityGap>,
    val changes: List<CapabilityChangeRecord>,
    val recommendations: List<RadarRecommendation>
)

/**
 * Declaration of a capability the radar tracks. `capabilityType` reuses the
 * existing [CapabilityType] taxonomy (21 values) — the radar does not invent
 * a second capability vocabulary. `uiExposed` is an honest static map of
 * which capabilities have a first-class UI surface today.
 */
data class RadarCapabilityDeclaration(
    val capabilityType: CapabilityType,
    val uiExposed: Boolean,
    val implemented: Boolean,
    val plannedOnly: Boolean = false
)

/**
 * Result of a radar capability check requested by the decision layer.
 */
data class RadarCapabilityCheck(
    val capabilityKey: String,
    val state: OperationalCapabilityState,
    /** True when the capability can be used for execution RIGHT NOW. */
    val isExecutable: Boolean,
    val requiresFallback: Boolean,
    val rationale: String
)
