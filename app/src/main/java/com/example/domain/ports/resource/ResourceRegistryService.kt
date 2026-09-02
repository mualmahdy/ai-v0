package com.example.domain.ports.resource

import com.example.domain.core.resource.ProviderId
import com.example.domain.core.resource.RegistryResult
import com.example.domain.core.resource.ResourceChangeEvent
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.resource.ResourceRecordInput
import com.example.domain.core.resource.ServiceId
import com.example.domain.core.resource.UsableResource
import kotlinx.coroutines.flow.Flow

/**
 * P0.2 — ResourceRegistryService (APPROVED-BASELINE v2.1, Section D — LOCKED).
 *
 * OWNS resource identity + registration + lifecycle (ownership boundary, Section D):
 *   ProviderRepository          -> persists provider CONFIGURATION
 *   ProviderControlPlaneService -> VALIDATES configuration, provides adapters
 *   ResourceRegistryService     -> OWNS resource identity + registration + lifecycle
 *   ResourceHealthService       -> OWNS transport health metrics
 *   CapabilityResourceGraph     -> DERIVED index (read-only, no ownership)
 *   ComponentRegistry           -> runtime dependency wiring only
 *
 * RULE REG-1: registration attempts for a logical resource identity that already
 *             exists return RejectedDuplicate. No overwrite, no silent merge.
 * RULE REG-2: runtime reality changes for the same logical resource go through
 *             bumpConfigurationVersion — not re-registration.
 * RULE REG-3: legacy providers without ResourceIds are migrated on first app start
 *             with lifecycleState=CONFIGURED and runtimeSupported=false.
 * RULE REG-4: (providerId, serviceId) is the logical resource key. One ResourceRecord
 *             per logical key. Two API accounts for the same provider+service MUST be
 *             modelled as distinct ServiceIds or ProviderIds.
 * RULE ID-7:  providerId + serviceId does NOT guarantee global uniqueness by itself —
 *             the registry enforces uniqueness and rejects duplicates.
 */
interface ResourceRegistryService {

    /**
     * Registers a new resource. REJECTS duplicates.
     * Duplicate definition: same logical resource identity (providerId + serviceId)
     * already registered (RULE REG-1 / REG-4).
     */
    suspend fun register(record: ResourceRecordInput): RegistryResult

    /** Exact lookup by immutable ResourceId. */
    suspend fun get(resourceId: ResourceId): ResourceRecord?

    /** Lookup by logical key (providerId + serviceId). Returns the single resource, if one exists. */
    suspend fun getByLogicalKey(providerId: ProviderId, serviceId: ServiceId): ResourceRecord?

    /** Lists all registered resources (used for graph rebuilds on startup). */
    suspend fun getAll(): List<ResourceRecord>

    /**
     * Increment configuration version. Does NOT change resourceId (RULE ID-1/2/3).
     */
    suspend fun bumpConfigurationVersion(resourceId: ResourceId): RegistryResult

    /**
     * Lifecycle transitions (user/registry intent track). Validates transition
     * validity per the Section E table and emits a [ResourceChangeEvent.LifecycleChanged].
     */
    suspend fun setLifecycleState(resourceId: ResourceId, newState: ResourceLifecycleState): RegistryResult

    /**
     * Query by capability. Returns only resources that satisfy the LOCKED usable
     * conjunction (Section E):
     *   lifecycleState == HEALTHY AND runtimeSupported == true AND NOT in cooldown.
     * Cooldown (health track) is consulted via the injected cooldown checker, so
     * the registry never computes health itself (ownership boundary).
     */
    suspend fun queryUsableByCapability(capabilityId: String): List<UsableResource>

    /**
     * Runtime support flag. Callable ONLY from the control-plane pathway — enforced
     * by requiring the single [RuntimeSupportToken] instance that was issued to the
     * ProviderControlPlaneService (P0 "simple check" per Section K / P0.2 rules).
     */
    suspend fun setRuntimeSupported(
        resourceId: ResourceId,
        supported: Boolean,
        token: RuntimeSupportToken
    ): RegistryResult

    /**
     * Change event stream for CapabilityResourceGraph and other listeners.
     */
    fun observeResourceChanges(): Flow<ResourceChangeEvent>
}

/**
 * P0.2 — RuntimeSupportToken: sealed capability proving the caller acts on behalf
 * of the ProviderControlPlaneService adapter-verification pathway (RULE AD-1:
 * runtimeSupported=true ONLY after a real adapter was created AND a minimal real
 * invocation succeeded).
 *
 * The constructor is private: exactly one token is issued at composition time
 * (AppContainer) and injected into the ProviderControlPlaneService. No other
 * component can obtain one (P0 "simple check" enforcement — Section K / P0.2).
 */
class RuntimeSupportToken private constructor() {
    companion object {
        /** Composition-root-only factory. Called exactly once in AppContainer. */
        fun issueForControlPlane(): RuntimeSupportToken = RuntimeSupportToken()
    }
}
