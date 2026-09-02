package com.example.domain.core.resource

import com.example.domain.ports.resource.ResourceRegistryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * P0.7 — CapabilityResourceGraph Reindex (APPROVED-BASELINE v2.1, Section K /
 * P0.7 — LOCKED).
 *
 * Derived, ResourceId-keyed index of USABLE resources, populated ONLY from
 * ResourceRegistryService events:
 * - ResourceId nodes only; no provider-name or model-name lookup keys (test 6).
 * - The graph subscribes to the registry change flow ([start]) and
 *   rebuilds/updates on events; external components can only READ.
 * - Usability filter = the registry's usable conjunction (Section E):
 *   HEALTHY AND runtimeSupported AND NOT in cooldown — re-queried from the
 *   authoritative registry on every event, never computed locally.
 * - Graph owns no persistence; rebuilt from the registry on startup via
 *   [rebuildFromRegistry].
 */
class ResourceCapabilityGraph(
    private val registry: ResourceRegistryService
) {
    // Private mutable state; all public accessors are read-only projections.
    private val usableByCapability = HashMap<String, MutableSet<ResourceId>>()
    private val recordsById = HashMap<ResourceId, UsableResource>()
    private val capabilityByResource = HashMap<ResourceId, List<String>>()

    /**
     * Subscribes the graph to the registry change flow. The graph is populated
     * ONLY from these events (plus [rebuildFromRegistry]); no external component
     * can write to the graph.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        registry.observeResourceChanges().collect { event ->
            when (event) {
                is ResourceChangeEvent.Registered -> {
                    // Freshly registered resources are CONFIGURED + runtimeSupported=false
                    // (never usable yet); ensure they are not indexed.
                    removeRecord(event.record.resourceId)
                }
                is ResourceChangeEvent.LifecycleChanged ->
                    refreshResource(event.resourceId)
                is ResourceChangeEvent.RuntimeSupportChanged ->
                    refreshResource(event.resourceId)
                is ResourceChangeEvent.ConfigurationBumped -> Unit // version does not affect usability
            }
        }
    }

    /**
     * Rebuilds the graph from the registry (startup path — the graph owns no
     * persistence and is rebuilt from the authoritative registry).
     */
    suspend fun rebuildFromRegistry() {
        val all = registry.getAll()
        synchronized(this) {
            usableByCapability.clear()
            recordsById.clear()
            capabilityByResource.clear()
        }
        for (record in all) {
            refreshResource(record.resourceId)
        }
    }

    /** ResourceIds providing the capability (ResourceId keys only). */
    fun resourceIdsForCapability(capabilityId: String): Set<ResourceId> =
        synchronized(this) { usableByCapability[capabilityId]?.toSet() ?: emptySet() }

    /** All usable records currently indexed (read-only projection). */
    fun usableRecords(): List<UsableResource> =
        synchronized(this) { recordsById.values.toList() }

    /** Capabilities a given usable resource provides (ResourceId-keyed lookup). */
    fun capabilitiesOf(resourceId: ResourceId): List<String> =
        synchronized(this) { capabilityByResource[resourceId] ?: emptyList() }

    /** Inspect node keys: MUST all be ResourceIds, never provider/model names (test 6). */
    fun nodeResourceIds(): Set<ResourceId> =
        synchronized(this) { recordsById.keys.toSet() }

    // --- Internal reindexing (registry-event driven, registry-authoritative) ---

    private suspend fun refreshResource(resourceId: ResourceId) {
        val record = registry.get(resourceId) ?: run {
            removeRecord(resourceId)
            return
        }
        if (!record.isUsableIgnoringCooldown) {
            removeRecord(resourceId)
            return
        }
        val capability = record.capabilities.firstOrNull() ?: run {
            removeRecord(resourceId)
            return
        }
        // Re-query the authoritative usable conjunction (includes cooldown track).
        val usable = registry.queryUsableByCapability(capability)
            .firstOrNull { it.resourceId == resourceId }
        if (usable == null) {
            removeRecord(resourceId)
        } else {
            insertUsable(usable)
        }
    }

    private fun insertUsable(usable: UsableResource) {
        synchronized(this) {
            recordsById[usable.resourceId] = usable
            capabilityByResource[usable.resourceId] = usable.capabilities
            for (capability in usable.capabilities) {
                usableByCapability.getOrPut(capability) { mutableSetOf() }.add(usable.resourceId)
            }
        }
    }

    private fun removeRecord(resourceId: ResourceId) {
        synchronized(this) {
            recordsById.remove(resourceId)
            val capabilities = capabilityByResource.remove(resourceId) ?: return
            for (capability in capabilities) {
                usableByCapability[capability]?.remove(resourceId)
            }
        }
    }
}
