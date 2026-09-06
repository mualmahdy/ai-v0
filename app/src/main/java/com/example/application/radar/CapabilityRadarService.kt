package com.example.application.radar

import com.example.domain.core.capability.CapabilityPrerequisites
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.capability.NetworkRequirement
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.radar.CapabilityChangeRecord
import com.example.domain.core.radar.CapabilityChangeType
import com.example.domain.core.radar.CapabilityEvaluationDimensions
import com.example.domain.core.radar.CapabilityEvidence
import com.example.domain.core.radar.CapabilityGap
import com.example.domain.core.radar.CapabilityGapReason
import com.example.domain.core.radar.CapabilityHealth
import com.example.domain.core.radar.CapabilityTrend
import com.example.domain.core.radar.EvidenceOutcome
import com.example.domain.core.radar.EvidenceSource
import com.example.domain.core.radar.OperationalCapabilityState
import com.example.domain.core.radar.RadarCapabilityCheck
import com.example.domain.core.radar.RadarCapabilityStatus
import com.example.domain.core.radar.RadarRecommendation
import com.example.domain.core.radar.RadarRecommendationType
import com.example.domain.core.radar.RadarSnapshot
import com.example.domain.core.radar.RecommendationPriority
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.ports.radar.CapabilityRadarPersistencePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * CapabilityRadarService — the operational Capability & Evolution Radar
 * ============================================================================
 *
 * A persistent DOMAIN subsystem (not a UI feature): capability state is
 * DERIVED from (a) real evidence observations and (b) live resource
 * registry facts, persisted via [CapabilityRadarPersistencePort], updated
 * event-driven from the SAME execution event bus the telemetry uses (no
 * second bus), and consulted by the Decision Engine before actions run.
 *
 * Derivation pipeline (per capability, per workspace):
 *
 *   declaration (static registry)  +  live resources  +  recent evidence
 *        -> [CapabilityEvaluationDimensions]
 *        -> [OperationalCapabilityState]  (never from class existence alone)
 *        -> change detection  -> [CapabilityChangeRecord]
 *        -> gap detection     -> [CapabilityGap]
 *        -> recommendation rules -> [RadarRecommendation]
 *        -> [RadarSnapshot] persisted + exposed as flows
 */
class CapabilityRadarService(
    private val persistence: CapabilityRadarPersistencePort,
    /** Live snapshot of the authoritative resource registry (suspend — room DAO). */
    private val resourceSnapshotProvider: suspend () -> List<ResourceRecord>,
    /** Honest ONNX semantic embedding provisioning state (null = unknown). */
    private val embeddingSemanticProvisioned: () -> Boolean? = { null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    /**
     * Honest static declaration registry: which capabilities are declared,
     * implemented in production code paths, and have a first-class UI
     * surface TODAY. This map is maintained by hand and must never claim
     * more than exists (UI exposure list verified against screens).
     */
    private val declarations: Map<CapabilityType, RadarDeclarationInternal> = buildDeclarations()

    private data class RadarDeclarationInternal(
        val implemented: Boolean,
        val uiExposed: Boolean,
        val plannedOnly: Boolean = false
    )

    // workspaceId -> conflated re-derivation job
    private val derivationJobs = ConcurrentHashMap<String, Job>()
    private val deriveMutex = Mutex()

    // ------------------------------------------------------------------
    // EVIDENCE INGESTION (event-driven updates — same bus as telemetry)
    // ------------------------------------------------------------------

    /**
     * Records one real evidence observation, persists it, and schedules a
     * conflated re-derivation for the affected workspace. Fire-and-forget:
     * radar ingestion must never break the runtime.
     */
    fun recordEvidence(evidence: CapabilityEvidence) {
        scope.launch {
            try {
                persistence.insertEvidence(evidence)
                scheduleReDerivation(evidence.workspaceId)
            } catch (_: Throwable) {
                // Observability/derivation is best-effort.
            }
        }
    }

    /**
     * Subscribes the radar to the orchestrator's execution event bus (the
     * SAME bus TelemetryService uses). Maps runtime outcomes to capability
     * evidence with full attribution.
     */
    fun subscribeToExecutionEvents(events: Flow<ExecutionEvent>) {
        scope.launch {
            events.collect { event ->
                try {
                    handleExecutionEvent(event)
                } catch (_: Throwable) {
                    // Radar must never break the event pipeline.
                }
            }
        }
    }

    private suspend fun handleExecutionEvent(event: ExecutionEvent) {
        when (event) {
            is ExecutionEvent.ActionCompleted -> {
                val cap = capabilityForActionType(event.action.type.name)
                if (cap != null) {
                    recordEvidenceSuspend(
                        cap, EvidenceOutcome.SUCCESS, EvidenceSource.ACTION_EXECUTION,
                        event.executionId, event.action.decisionRecord?.providerId,
                        event.action.decisionRecord?.serviceId,
                        event.action.payload["modelId"]?.toString(),
                        event.action.decisionRecord?.selectedResourceId?.value,
                        "نجاح تنفيذ ${event.action.type.code}"
                    )
                }
            }
            is ExecutionEvent.ActionFailed -> {
                val cap = capabilityForActionType(event.action.type.name)
                if (cap != null) {
                    recordEvidenceSuspend(
                        cap, EvidenceOutcome.FAILURE, EvidenceSource.ACTION_EXECUTION,
                        event.executionId, event.action.decisionRecord?.providerId,
                        event.action.decisionRecord?.serviceId,
                        event.action.payload["modelId"]?.toString(),
                        event.action.decisionRecord?.selectedResourceId?.value,
                        "فشل تنفيذ ${event.action.type.code}: ${event.errorDescription.take(120)}"
                    )
                }
            }
            is ExecutionEvent.Degraded -> {
                // Degradation of streaming/LLM execution marks LLM capability degraded.
                recordEvidenceSuspend(
                    CapabilityType.LLM_GENERATION, EvidenceOutcome.DEGRADED, EvidenceSource.LLM_EXECUTION,
                    event.executionId, null, null, null, null,
                    "تنفيذ متدهور: ${event.reason.name} — ${event.message.take(120)}"
                )
            }
            is ExecutionEvent.Error -> {
                recordEvidenceSuspend(
                    CapabilityType.LLM_GENERATION, EvidenceOutcome.FAILURE, EvidenceSource.LLM_EXECUTION,
                    event.executionId, null, null, null, null,
                    "خطأ تنفيذ (${event.failureCode}): ${event.message.take(120)}"
                )
            }
            is ExecutionEvent.ToolResult -> {
                val outcome = when (event.outcome) {
                    is com.example.domain.core.Outcome.Success<*> -> EvidenceOutcome.SUCCESS
                    is com.example.domain.core.Outcome.Degraded<*, *> -> EvidenceOutcome.DEGRADED
                    is com.example.domain.core.Outcome.Error<*> -> EvidenceOutcome.FAILURE
                    else -> EvidenceOutcome.NEUTRAL
                }
                recordEvidenceSuspend(
                    CapabilityType.TOOL_EXECUTION, outcome, EvidenceSource.TOOL_EXECUTION,
                    event.executionId, null, null, null,
                    event.toolName.lowercase(),
                    "نتيجة أداة ${event.toolName}: $outcome"
                )
            }
            is ExecutionEvent.BudgetGateDecision -> {
                if (event.decision != com.example.domain.core.budget.EconomicGateDecision.ALLOWED.name) {
                    recordEvidenceSuspend(
                        CapabilityType.LLM_GENERATION, EvidenceOutcome.DEGRADED, EvidenceSource.BUDGET_EVENT,
                        event.executionId, event.providerId, null, event.modelId, null,
                        "بوابة الميزانية: ${event.decision} — ${event.reason.take(120)}"
                    )
                }
            }
            is ExecutionEvent.RateLimitEncountered -> {
                val cap = capabilityForResourceType(event.resourceType)
                recordEvidenceSuspend(
                    cap, EvidenceOutcome.DEGRADED, EvidenceSource.RATE_LIMIT_EVENT,
                    event.executionId, event.providerId, null, event.modelId, null,
                    "حد المعدل (${event.scopeKey}): إعادة المحاولة بعد ${event.retryAfterMs ?: "?"}ms"
                )
            }
            else -> Unit
        }
    }

    private suspend fun recordEvidenceSuspend(
        capability: CapabilityType,
        outcome: EvidenceOutcome,
        source: EvidenceSource,
        executionId: String,
        providerId: String?,
        serviceId: String?,
        modelId: String?,
        resourceId: String?,
        detail: String
    ) {
        val workspaceId = workspaceIdProvider?.invoke()
        persistence.insertEvidence(
            CapabilityEvidence(
                id = "ev_${UUID.randomUUID()}",
                capabilityKey = capability.code,
                source = source,
                outcome = outcome,
                timestampEpochMs = System.currentTimeMillis(),
                confidence = when (source) {
                    EvidenceSource.ACTION_EXECUTION, EvidenceSource.TOOL_EXECUTION -> 0.9f
                    EvidenceSource.LLM_EXECUTION -> 0.85f
                    else -> 0.8f
                },
                providerId = providerId,
                serviceId = serviceId,
                modelId = modelId,
                resourceId = resourceId,
                executionId = executionId,
                workspaceId = workspaceId,
                agentId = null, // agent attribution is derived at decision time via DecisionRecord
                detail = detail
            )
        )
        scheduleReDerivation(workspaceId)
    }

    /** Late-bound active workspace provider (wired by the AppContainer). */
    var workspaceIdProvider: (() -> String?)? = null

    /**
     * Control-plane lifecycle evidence sink (late-bound var, following the
     * existing delegationExecutor pattern — no second event bus).
     */
    var controlPlaneEvidenceSink: ((CapabilityEvidence) -> Unit)? = null
        private set

    fun installControlPlaneSink() {
        controlPlaneEvidenceSink = { evidence -> recordEvidence(evidence) }
    }

    // ------------------------------------------------------------------
    // DERIVATION
    // ------------------------------------------------------------------

    private fun scheduleReDerivation(workspaceId: String?) {
        val key = workspaceId ?: GLOBAL_SCOPE
        derivationJobs[key]?.cancel()
        derivationJobs[key] = scope.launch {
            try {
                deriveSnapshotSuspend(workspaceId, NetworkPolicy.HYBRID, isNetworkAvailable = true)
            } catch (_: Throwable) {
                // best-effort
            }
        }
    }

    /**
     * Derives the full radar snapshot for a workspace (or global scope),
     * persists states/changes/recommendations, and returns it. Conflated —
     * concurrent calls serialize on [deriveMutex].
     */
    suspend fun deriveSnapshot(
        workspaceId: String?,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true
    ): RadarSnapshot = deriveMutex.withLock {
        deriveSnapshotSuspend(workspaceId, networkPolicy, isNetworkAvailable)
    }

    private suspend fun deriveSnapshotSuspend(
        workspaceId: String?,
        networkPolicy: NetworkPolicy,
        isNetworkAvailable: Boolean
    ): RadarSnapshot {
        val now = System.currentTimeMillis()
        val resources = runCatching { resourceSnapshotProvider() }.getOrDefault(emptyList())
        val order = CapabilityPrerequisites.resolvePrerequisites(CapabilityType.values().toSet()).resolutionOrder

        val previousStates = persistence.capabilityStatusesForWorkspace(workspaceId)
            .associateBy { it.capabilityKey }

        val derived = mutableListOf<RadarCapabilityStatus>()
        val changes = mutableListOf<CapabilityChangeRecord>()
        val gaps = mutableListOf<CapabilityGap>()

        // Derive in dependency order so dependency availability uses fresh states.
        val derivedByCode = HashMap<String, RadarCapabilityStatus>()
        for (cap in order) {
            val status = deriveCapabilityStatus(
                cap, workspaceId, resources, previousStates[cap.code],
                derivedByCode, networkPolicy, isNetworkAvailable, now
            )
            derived.add(status)
            derivedByCode[cap.code] = status

            // Change detection (state transition)
            val previous = previousStates[cap.code]
            if (previous != null && previous.state != status.state) {
                val changeType = classifyChange(previous.state, status.state, previous.health, status.health)
                val change = CapabilityChangeRecord(
                    id = "chg_${UUID.randomUUID()}",
                    capabilityKey = cap.code,
                    workspaceId = workspaceId,
                    fromState = previous.state,
                    toState = status.state,
                    changeType = changeType,
                    evidenceId = null,
                    detail = "${previous.state.name} → ${status.state.name}: ${status.rationale}",
                    detectedAtEpochMs = now
                )
                changes.add(change)
            }

            // Gap detection
            val gap = detectGap(status, cap, workspaceId, now)
            if (gap != null) gaps.add(gap)
        }

        // Persist states + changes
        runCatching { persistence.upsertCapabilityStatuses(derived) }
        changes.forEach { runCatching { persistence.insertChange(it) } }

        // Recommendations (rules over gaps + statuses + changes)
        val recommendations = generateRecommendations(derived, gaps, changes, workspaceId, resources, now)
        runCatching { persistence.upsertRecommendations(recommendations) }

        return RadarSnapshot(
            workspaceId = workspaceId,
            takenAtEpochMs = now,
            capabilities = derived,
            gaps = gaps,
            changes = changes,
            recommendations = recommendations
        )
    }

    private suspend fun deriveCapabilityStatus(
        cap: CapabilityType,
        workspaceId: String?,
        resources: List<ResourceRecord>,
        previous: RadarCapabilityStatus?,
        derivedSoFar: Map<String, RadarCapabilityStatus>,
        networkPolicy: NetworkPolicy,
        isNetworkAvailable: Boolean,
        now: Long
    ): RadarCapabilityStatus {
        val declaration = declarations[cap]
        val declared = declaration != null
        val implemented = declaration?.implemented ?: false
        val uiExposed = declaration?.uiExposed ?: false

        // Live resource facts
        val contributing = resources.filter { cap in it.capabilities }
        val anyConfigured = contributing.any { it.lifecycleState >= ResourceLifecycleState.CONFIGURED }
        val anyProvisioned = contributing.any {
            it.lifecycleState == ResourceLifecycleState.ENABLED || it.lifecycleState == ResourceLifecycleState.ACTIVE
        }
        val anyRuntimeValidated = contributing.any { it.runtimeSupported }
        val usable = contributing.filter {
            (it.lifecycleState == ResourceLifecycleState.ENABLED || it.lifecycleState == ResourceLifecycleState.ACTIVE) &&
                it.runtimeSupported &&
                it.healthStatus != com.example.domain.core.provider.HealthStatus.UNAVAILABLE
        }
        val allDisabled = contributing.isNotEmpty() && contributing.all {
            it.lifecycleState == ResourceLifecycleState.DISABLED || it.lifecycleState == ResourceLifecycleState.DEPRECATED
        }

        // Policy availability (offline analysis)
        val offline = networkPolicy == NetworkPolicy.OFFLINE || !isNetworkAvailable
        val policyAllowed = when (cap.defaultNetworkRequirement) {
            NetworkRequirement.ONLINE_ONLY -> !offline
            NetworkRequirement.OFFLINE_ONLY, NetworkRequirement.LOCAL_ONLY -> true
            NetworkRequirement.HYBRID -> true // hybrid allowed either way; locality nuance below
        }

        // Dependency availability from freshly derived prerequisites
        val prereqStates = CapabilityPrerequisites.getPrerequisites(cap)
            .mapNotNull { derivedSoFar[it.code] }
        val dependencyAvailable = if (CapabilityPrerequisites.getPrerequisites(cap).isEmpty()) true
        else prereqStates.isNotEmpty() && prereqStates.all {
            it.state == OperationalCapabilityState.AVAILABLE || it.state == OperationalCapabilityState.DEGRADED
        }

        // Evidence-derived health + freshness
        val evidence = persistence.recentEvidenceForCapability(cap.code, workspaceId, EVIDENCE_WINDOW)
        val successCount = evidence.count { it.outcome == EvidenceOutcome.SUCCESS }
        val failureCount = evidence.count { it.outcome == EvidenceOutcome.FAILURE }
        val degradedCount = evidence.count { it.outcome == EvidenceOutcome.DEGRADED }
        val lastEvidenceMs = evidence.firstOrNull()?.timestampEpochMs
        val evidenceFresh = lastEvidenceMs != null && (now - lastEvidenceMs) < EVIDENCE_STALENESS_MS

        val health: CapabilityHealth = when {
            usable.isEmpty() && contributing.isNotEmpty() -> CapabilityHealth.UNAVAILABLE
            evidence.isEmpty() -> CapabilityHealth.UNKNOWN
            successCount == 0 && failureCount > 0 -> CapabilityHealth.FAILING
            failureCount > successCount -> CapabilityHealth.FAILING
            degradedCount > 0 && degradedCount >= successCount -> CapabilityHealth.DEGRADED_HEALTH
            failureCount * 2 > successCount -> CapabilityHealth.DEGRADED_HEALTH
            else -> CapabilityHealth.HEALTHY
        }

        // Trend vs previous health
        val trend: CapabilityTrend = when {
            previous == null || previous.health == CapabilityHealth.UNKNOWN -> CapabilityTrend.UNKNOWN
            health.ordinal < previous.health.ordinal -> CapabilityTrend.IMPROVING
            health.ordinal > previous.health.ordinal -> CapabilityTrend.DETERIORATING
            else -> CapabilityTrend.STABLE
        }

        // Special honest sources: local semantic embedding provisioning
        val embeddingProvisioned = if (cap == CapabilityType.EMBEDDING) embeddingSemanticProvisioned() else null

        val dimensions = CapabilityEvaluationDimensions(
            declared = declared,
            implemented = implemented,
            configured = if (contributing.isEmpty()) null else anyConfigured,
            provisioned = if (contributing.isEmpty()) null else anyProvisioned,
            dependencyAvailable = dependencyAvailable,
            runtimeAvailable = if (contributing.isEmpty()) null else usable.isNotEmpty(),
            runtimeValidated = if (contributing.isEmpty()) embeddingProvisioned else (anyRuntimeValidated || (embeddingProvisioned ?: false)),
            resourceUsable = if (contributing.isEmpty()) null else usable.isNotEmpty(),
            policyAllowed = policyAllowed,
            healthConfirmed = if (evidence.isEmpty()) null else health == CapabilityHealth.HEALTHY,
            uiExposed = uiExposed,
            evidenceFresh = if (evidence.isEmpty()) null else evidenceFresh
        )

        val state = composeState(
            declared = declared,
            implemented = implemented,
            plannedOnly = declaration?.plannedOnly ?: false,
            allDisabled = allDisabled,
            policyAllowed = policyAllowed,
            dependencyAvailable = dependencyAvailable,
            hasUsableResource = usable.isNotEmpty() ||
                (cap == CapabilityType.EMBEDDING && embeddingProvisioned == true),
            hasContributing = contributing.isNotEmpty(),
            anyProvisioned = anyProvisioned || (embeddingProvisioned == true),
            health = health,
            hasPriorSuccess = successCount > 0,
            uiExposed = uiExposed
        )

        val rationale = buildRationale(cap, state, dimensions, usable, evidence.size)

        return RadarCapabilityStatus(
            capabilityKey = cap.code,
            workspaceId = workspaceId,
            state = state,
            dimensions = dimensions,
            health = health,
            trend = trend,
            evidenceCount = evidence.size,
            lastEvidenceEpochMs = lastEvidenceMs,
            contributingResourceIds = usable.map { it.resourceId.value }.ifEmpty { contributing.map { it.resourceId.value } },
            rationale = rationale,
            derivedAtEpochMs = now
        )
    }

    /** The core truthful state composition — the heart of the radar. */
    private fun composeState(
        declared: Boolean,
        implemented: Boolean,
        plannedOnly: Boolean,
        allDisabled: Boolean,
        policyAllowed: Boolean,
        dependencyAvailable: Boolean,
        hasUsableResource: Boolean,
        hasContributing: Boolean,
        anyProvisioned: Boolean,
        health: CapabilityHealth,
        hasPriorSuccess: Boolean,
        uiExposed: Boolean
    ): OperationalCapabilityState {
        if (!declared) return OperationalCapabilityState.UNKNOWN
        if (plannedOnly || !implemented) return OperationalCapabilityState.PLANNED
        if (allDisabled) return OperationalCapabilityState.DISABLED
        if (!policyAllowed) return OperationalCapabilityState.BLOCKED
        if (!dependencyAvailable) return OperationalCapabilityState.BLOCKED
        if (hasUsableResource) {
            return when (health) {
                CapabilityHealth.HEALTHY, CapabilityHealth.UNKNOWN ->
                    if (uiExposed) OperationalCapabilityState.AVAILABLE
                    else OperationalCapabilityState.PARTIAL // backend works, UI missing
                CapabilityHealth.DEGRADED_HEALTH -> OperationalCapabilityState.DEGRADED
                CapabilityHealth.FAILING ->
                    if (hasPriorSuccess) OperationalCapabilityState.DEGRADED else OperationalCapabilityState.FAILED
                CapabilityHealth.UNAVAILABLE -> OperationalCapabilityState.DEGRADED
            }
        }
        // No usable resource:
        if (health == CapabilityHealth.FAILING) return OperationalCapabilityState.FAILED
        if (hasContributing || anyProvisioned) return OperationalCapabilityState.PARTIAL
        return OperationalCapabilityState.PARTIAL // implemented but nothing provisioned — honest PARTIAL
    }

    // ------------------------------------------------------------------
    // GAP DETECTION
    // ------------------------------------------------------------------

    private fun detectGap(
        status: RadarCapabilityStatus,
        cap: CapabilityType,
        workspaceId: String?,
        now: Long
    ): CapabilityGap? {
        val d = status.dimensions
        val reason: CapabilityGapReason? = when (status.state) {
            OperationalCapabilityState.PLANNED -> CapabilityGapReason.NO_IMPLEMENTATION
            OperationalCapabilityState.BLOCKED ->
                if (d.dependencyAvailable == false) CapabilityGapReason.DEPENDENCY_MISSING
                else CapabilityGapReason.POLICY_BLOCKING
            OperationalCapabilityState.FAILED ->
                if (d.resourceUsable == false) CapabilityGapReason.PROVIDER_UNAVAILABLE
                else CapabilityGapReason.RUNTIME_VALIDATION_MISSING
            OperationalCapabilityState.DISABLED -> CapabilityGapReason.RESOURCE_NOT_PROVISIONED
            OperationalCapabilityState.DEGRADED -> CapabilityGapReason.PROVIDER_UNAVAILABLE
            OperationalCapabilityState.PARTIAL -> when {
                d.provisioned == false || d.provisioned == null -> CapabilityGapReason.RESOURCE_NOT_PROVISIONED
                d.runtimeValidated == false -> CapabilityGapReason.RUNTIME_VALIDATION_MISSING
                d.uiExposed == false -> CapabilityGapReason.MISSING_UI_INTEGRATION
                d.resourceUsable == false -> CapabilityGapReason.PROVIDER_UNAVAILABLE
                else -> CapabilityGapReason.DECLARED_NOT_OPERATIONAL
            }
            OperationalCapabilityState.UNKNOWN, OperationalCapabilityState.AVAILABLE ->
                if (status.state == OperationalCapabilityState.UNKNOWN) CapabilityGapReason.DECLARED_NOT_OPERATIONAL else null
            OperationalCapabilityState.DEPRECATED -> null
        }
        if (reason == null) return null
        return CapabilityGap(
            capabilityKey = cap.code,
            workspaceId = workspaceId,
            reason = reason,
            detail = "${status.rationale} (الحالة: ${status.state.name})",
            evidenceId = null,
            detectedAtEpochMs = now
        )
    }

    // ------------------------------------------------------------------
    // EVOLUTION DETECTION
    // ------------------------------------------------------------------

    private fun classifyChange(
        from: OperationalCapabilityState,
        to: OperationalCapabilityState,
        fromHealth: CapabilityHealth,
        toHealth: CapabilityHealth
    ): CapabilityChangeType = when {
        // Recovery transitions are checked FIRST so DEGRADED/FAILED -> AVAILABLE
        // is RESTORED, not NEWLY_AVAILABLE.
        (from == OperationalCapabilityState.DEGRADED || from == OperationalCapabilityState.FAILED) &&
            to == OperationalCapabilityState.AVAILABLE ->
            CapabilityChangeType.RESTORED
        from != OperationalCapabilityState.AVAILABLE && to == OperationalCapabilityState.AVAILABLE ->
            CapabilityChangeType.NEWLY_AVAILABLE
        from == OperationalCapabilityState.BLOCKED && to != OperationalCapabilityState.BLOCKED ->
            CapabilityChangeType.UNBLOCKED
        to == OperationalCapabilityState.BLOCKED && from != OperationalCapabilityState.BLOCKED ->
            CapabilityChangeType.BLOCKED_CHANGE
        to == OperationalCapabilityState.DEGRADED && from == OperationalCapabilityState.AVAILABLE ->
            CapabilityChangeType.DEGRADED
        to == OperationalCapabilityState.DISABLED && from != OperationalCapabilityState.DISABLED ->
            CapabilityChangeType.DISABLED_CHANGE
        to == OperationalCapabilityState.DEPRECATED && from != OperationalCapabilityState.DEPRECATED ->
            CapabilityChangeType.DEPRECATED_CHANGE
        to == OperationalCapabilityState.FAILED && from != OperationalCapabilityState.FAILED ->
            CapabilityChangeType.FAILED_CHANGE
        fromHealth != toHealth && from != to ->
            CapabilityChangeType.RELIABILITY_CHANGED
        else -> CapabilityChangeType.RELIABILITY_CHANGED
    }

    // ------------------------------------------------------------------
    // RECOMMENDATIONS (evidence-based rules)
    // ------------------------------------------------------------------

    private fun generateRecommendations(
        statuses: List<RadarCapabilityStatus>,
        gaps: List<CapabilityGap>,
        changes: List<CapabilityChangeRecord>,
        workspaceId: String?,
        resources: List<ResourceRecord>,
        now: Long
    ): List<RadarRecommendation> {
        val out = mutableListOf<RadarRecommendation>()
        val byCode = statuses.associateBy { it.capabilityKey }
        val gapByCode = gaps.associateBy { it.capabilityKey }

        for (gap in gaps) {
            val cap = CapabilityType.fromCode(gap.capabilityKey) ?: continue
            val status = byCode[gap.capabilityKey] ?: continue
            val priority = when {
                status.state == OperationalCapabilityState.FAILED ||
                    status.state == OperationalCapabilityState.BLOCKED -> RecommendationPriority.HIGH
                status.state == OperationalCapabilityState.DEGRADED -> RecommendationPriority.MEDIUM
                else -> RecommendationPriority.LOW
            }
            val (type, message, hint) = when (gap.reason) {
                CapabilityGapReason.RESOURCE_NOT_PROVISIONED -> Triple(
                    RadarRecommendationType.PROVISION_RESOURCE,
                    "القدرة «${cap.displayName}» معلنة ومطبقة لكن دون مورد مُهيّأ — قم بتوفير/تمكين مورد يدعمها.",
                    "open_provider_manager"
                )
                CapabilityGapReason.RUNTIME_VALIDATION_MISSING -> Triple(
                    RadarRecommendationType.RETRY_VALIDATION,
                    "مورد «${cap.displayName}» موجود لكن لم يُتحقق منه في وقت التشغيل — شغّل التحقق (validate) من لوحة التحكم.",
                    "validate_resource"
                )
                CapabilityGapReason.PROVIDER_UNAVAILABLE -> Triple(
                    RadarRecommendationType.PROVIDER_UNAVAILABLE_ACTION,
                    "مزود «${cap.displayName}» غير متاح أو فاشل حالياً — تحقق من الاتصال/المفتاح أو فعّل مزوداً بديلاً.",
                    "check_provider_health"
                )
                CapabilityGapReason.DEPENDENCY_MISSING -> {
                    val missing = CapabilityPrerequisites.getPrerequisites(cap)
                        .filter { byCode[it.code]?.state?.let { s -> s != OperationalCapabilityState.AVAILABLE && s != OperationalCapabilityState.DEGRADED } != false }
                        .joinToString("، ") { it.displayName }
                    Triple(
                        RadarRecommendationType.CAPABILITY_DEGRADED_ACTION,
                        "القدرة «${cap.displayName}» محجوبة بسبب تبعية ناقصة: $missing.",
                        "resolve_dependencies"
                    )
                }
                CapabilityGapReason.POLICY_BLOCKING -> Triple(
                    RadarRecommendationType.CAPABILITY_DEGRADED_ACTION,
                    "«${cap.displayName}» محجوبة بسياسة الشبكة (OFFLINE) — غيّر السياسة أو فعّل مورداً محلياً.",
                    "review_network_policy"
                )
                CapabilityGapReason.MISSING_UI_INTEGRATION -> Triple(
                    RadarRecommendationType.UI_EXPOSURE_ACTION,
                    "القدرة «${cap.displayName}» تعمل في الخلفية دون واجهة مستخدم — أضف سطح UI مخصصاً لها.",
                    "expose_capability_ui"
                )
                CapabilityGapReason.NO_IMPLEMENTATION -> Triple(
                    RadarRecommendationType.CAPABILITY_DEGRADED_ACTION,
                    "القدرة «${cap.displayName}» مخططة فقط (لا تنفيذ) — حدّد أولوية التنفيذ في خطة التطور.",
                    "plan_implementation"
                )
                CapabilityGapReason.RATE_LIMITED -> Triple(
                    RadarRecommendationType.RATE_LIMIT_ACTION,
                    "«${cap.displayName}» مقيدة بحد المعدل — خفّف الوتيرة أو انتظر نافذة جديدة.",
                    "backoff"
                )
                CapabilityGapReason.INSUFFICIENT_BUDGET -> Triple(
                    RadarRecommendationType.BUDGET_ACTION,
                    "«${cap.displayName}» متوقفة بسبب الميزانية — راجع مخصصات الميزانية أو فعّل النموذج المحلي.",
                    "review_budget"
                )
                CapabilityGapReason.ANDROID_PLATFORM_LIMITATION -> Triple(
                    RadarRecommendationType.CAPABILITY_DEGRADED_ACTION,
                    "«${cap.displayName}» مقيدة بحدود منصة Android الحالية.",
                    null
                )
                CapabilityGapReason.DECLARED_NOT_OPERATIONAL -> Triple(
                    RadarRecommendationType.CAPABILITY_DEGRADED_ACTION,
                    "«${cap.displayName}» غير قابلة للتشغيل حالياً — راجع التفاصيل في لوحة القدرات.",
                    null
                )
                CapabilityGapReason.TOOL_UNAVAILABLE -> Triple(
                    RadarRecommendationType.PROVIDER_UNAVAILABLE_ACTION,
                    "أداة «${cap.displayName}» غير متاحة — تحقق من تسجيل الأداة ودورتها الحياتية.",
                    "check_tools"
                )
            }
            out.add(
                RadarRecommendation(
                    id = "rec_${gap.capabilityKey}_${gap.reason.name}",
                    capabilityKey = gap.capabilityKey,
                    workspaceId = workspaceId,
                    type = type,
                    priority = priority,
                    message = message,
                    actionHint = hint,
                    supportingEvidenceIds = emptyList(),
                    createdAtEpochMs = now
                )
            )
        }

        // Reliability recommendation: failing trend with prior success → fallback advice
        for (status in statuses) {
            if (status.trend == CapabilityTrend.DETERIORATING &&
                status.state == OperationalCapabilityState.DEGRADED
            ) {
                out.add(
                    RadarRecommendation(
                        id = "rec_${status.capabilityKey}_reliability",
                        capabilityKey = status.capabilityKey,
                        workspaceId = workspaceId,
                        type = RadarRecommendationType.RELIABILITY_ACTION,
                        priority = RecommendationPriority.MEDIUM,
                        message = "موثوقية «${status.capabilityKey}» تتدهور (اتجاه تنازلي) — فعّل مساراً بديلاً (fallback) أو تحقق من الموارد المساهمة.",
                        actionHint = "enable_fallback",
                        supportingEvidenceIds = emptyList(),
                        createdAtEpochMs = now
                    )
                )
            }
        }

        return out.distinctBy { it.id }
    }

    // ------------------------------------------------------------------
    // DECISION-ENGINE CHECK SURFACE
    // ------------------------------------------------------------------

    /**
     * Fast capability check for the decision engine. Uses the persisted
     * derived state (kept fresh by event-driven re-derivation); falls back
     * to a fresh derivation when nothing is persisted yet.
     */
    suspend fun checkCapability(
        capability: CapabilityType,
        workspaceId: String?
    ): RadarCapabilityCheck {
        val persisted = persistence.getCapabilityStatus(capability.code, workspaceId)
            ?: deriveSnapshot(workspaceId).capabilities.firstOrNull { it.capabilityKey == capability.code }
        val state = persisted?.state ?: OperationalCapabilityState.UNKNOWN
        val executable = state == OperationalCapabilityState.AVAILABLE || state == OperationalCapabilityState.DEGRADED
        return RadarCapabilityCheck(
            capabilityKey = capability.code,
            state = state,
            isExecutable = executable,
            requiresFallback = state == OperationalCapabilityState.DEGRADED || state == OperationalCapabilityState.PARTIAL,
            rationale = persisted?.rationale ?: "لا توجد حالة مشتقة للقدرة بعد."
        )
    }

    // ------------------------------------------------------------------
    // UI / OBSERVATORY SURFACE (flows straight from persistence)
    // ------------------------------------------------------------------

    fun observeCapabilityStatuses(workspaceId: String?): Flow<List<RadarCapabilityStatus>> =
        persistence.observeCapabilityStatuses(workspaceId)

    fun observeRecommendations(workspaceId: String?): Flow<List<RadarRecommendation>> =
        persistence.observeRecommendations(workspaceId)

    fun observeChanges(workspaceId: String?, limit: Int = 20): Flow<List<CapabilityChangeRecord>> =
        persistence.observeChanges(workspaceId, limit)

    suspend fun recentChanges(workspaceId: String?, limit: Int = 20): List<CapabilityChangeRecord> =
        persistence.recentChangesForWorkspace(workspaceId, limit)

    suspend fun snapshot(workspaceId: String?): RadarSnapshot? {
        val statuses = persistence.capabilityStatusesForWorkspace(workspaceId)
        if (statuses.isEmpty()) return null
        return RadarSnapshot(
            workspaceId = workspaceId,
            takenAtEpochMs = statuses.maxOf { it.derivedAtEpochMs },
            capabilities = statuses,
            gaps = emptyList(),
            changes = persistence.recentChangesForWorkspace(workspaceId, 20),
            recommendations = persistence.activeRecommendationsForWorkspace(workspaceId)
        )
    }

    suspend fun dismissRecommendation(id: String) = persistence.dismissRecommendation(id)

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun capabilityForActionType(actionTypeName: String): CapabilityType? = when (actionTypeName) {
        "EXECUTE_STEP", "SELECT_MODEL", "RETRY" -> CapabilityType.LLM_GENERATION
        "SEARCH" -> CapabilityType.SEARCH
        "RETRIEVE_MEMORY" -> CapabilityType.MEMORY_RETRIEVAL
        "RETRIEVE_KNOWLEDGE" -> CapabilityType.EMBEDDING
        "EXECUTE_TOOL", "SELECT_TOOL" -> CapabilityType.TOOL_EXECUTION
        "EXECUTE_MCP" -> CapabilityType.MCP_INVOCATION
        "EXECUTE_SKILL" -> CapabilityType.CODE_ENGINEERING
        "USE_INTEGRATION" -> CapabilityType.INTEGRATION_SYNC
        "DELEGATE" -> CapabilityType.AGENT_DELEGATION
        else -> null
    }

    private fun capabilityForResourceType(resourceType: String): CapabilityType = when (resourceType) {
        "SEARCH" -> CapabilityType.SEARCH
        "EMBEDDING" -> CapabilityType.EMBEDDING
        "TOOL" -> CapabilityType.TOOL_EXECUTION
        else -> CapabilityType.LLM_GENERATION
    }

    private fun buildRationale(
        cap: CapabilityType,
        state: OperationalCapabilityState,
        d: CapabilityEvaluationDimensions,
        usable: List<ResourceRecord>,
        evidenceCount: Int
    ): String = buildString {
        append(state.name)
        append(" — ")
        append(
            when (state) {
                OperationalCapabilityState.UNKNOWN -> "قدرة غير معلنة في الرادار."
                OperationalCapabilityState.PLANNED -> "معلنة كخطة؛ لا تنفيذ إنتاجي بعد."
                OperationalCapabilityState.AVAILABLE ->
                    "موارد قابلة للاستخدام: ${usable.joinToString(",") { it.resourceId.value.take(40) }}" +
                        (if (evidenceCount > 0) " + $evidenceCount دليل حديث." else " (بلا أدلة حديثة — حالة من السجل الحي).")
                OperationalCapabilityState.PARTIAL -> buildString {
                    append("مطبقة")
                    if (d.provisioned == false) append("؛ دون مورد مُهيّأ")
                    if (d.runtimeValidated == false) append("؛ دون تحقق وقت تشغيل")
                    if (d.uiExposed == false) append("؛ دون واجهة مستخدم")
                    append(".")
                }
                OperationalCapabilityState.DEGRADED -> "تعمل بتحذيرات/تدهور (أدلة: $evidenceCount)."
                OperationalCapabilityState.BLOCKED ->
                    if (d.dependencyAvailable == false) "محجوبة بسبب تبعية ناقصة."
                    else "محجوبة بسياسة (شبكة/أذونات)."
                OperationalCapabilityState.FAILED -> "أدلة الفشل حديثة دون تعافٍ مُلاحظ."
                OperationalCapabilityState.DISABLED -> "كل الموارد المساهمة معطلة يدوياً."
                OperationalCapabilityState.DEPRECATED -> "القدرة مهملة رسمياً."
            }
        )
    }

    /**
     * The honest declaration registry. Verified against the codebase:
     * implemented = a production execution path exists; uiExposed = a
     * first-class screen surface exists.
     */
    private fun buildDeclarations(): Map<CapabilityType, RadarDeclarationInternal> = mapOf(
        CapabilityType.LLM_GENERATION to RadarDeclarationInternal(implemented = true, uiExposed = true),
        CapabilityType.REASONING to RadarDeclarationInternal(implemented = true, uiExposed = false),
        CapabilityType.STREAMING to RadarDeclarationInternal(implemented = true, uiExposed = true),
        CapabilityType.VISION to RadarDeclarationInternal(implemented = false, uiExposed = false),
        CapabilityType.SEARCH to RadarDeclarationInternal(implemented = true, uiExposed = false),
        CapabilityType.EMBEDDING to RadarDeclarationInternal(implemented = true, uiExposed = true),
        CapabilityType.VECTOR_STORE to RadarDeclarationInternal(implemented = true, uiExposed = false),
        CapabilityType.MEMORY_RETRIEVAL to RadarDeclarationInternal(implemented = true, uiExposed = true),
        CapabilityType.TOOL_EXECUTION to RadarDeclarationInternal(implemented = true, uiExposed = true),
        CapabilityType.SHELL_EXECUTION to RadarDeclarationInternal(implemented = false, uiExposed = false),
        CapabilityType.SYSTEM_EXECUTION to RadarDeclarationInternal(implemented = false, uiExposed = false),
        CapabilityType.FILE_STORAGE to RadarDeclarationInternal(implemented = true, uiExposed = true),
        CapabilityType.FILE_READ to RadarDeclarationInternal(implemented = true, uiExposed = true),
        CapabilityType.FILE_WRITE to RadarDeclarationInternal(implemented = true, uiExposed = true),
        CapabilityType.CODE_ANALYSIS to RadarDeclarationInternal(implemented = true, uiExposed = false),
        CapabilityType.CODE_ENGINEERING to RadarDeclarationInternal(implemented = true, uiExposed = true),
        CapabilityType.SECURITY_AUDIT to RadarDeclarationInternal(implemented = true, uiExposed = false),
        CapabilityType.MCP_INVOCATION to RadarDeclarationInternal(implemented = true, uiExposed = true),
        CapabilityType.AGENT_DELEGATION to RadarDeclarationInternal(implemented = true, uiExposed = true),
        CapabilityType.INTEGRATION_SYNC to RadarDeclarationInternal(implemented = true, uiExposed = true),
        CapabilityType.HASH_COMPUTATION to RadarDeclarationInternal(implemented = true, uiExposed = false)
    )

    companion object {
        const val GLOBAL_SCOPE = "__global__"
        const val EVIDENCE_WINDOW = 20
        const val EVIDENCE_STALENESS_MS = 24 * 60 * 60 * 1000L
    }
}
