package com.example.domain.core.resource

import com.example.domain.core.provider.offering.OfferingType

/**
 * ============================================================================
 * ResourceIdScheme — Phase 4 (Corrections #2/#3)
 * ============================================================================
 *
 * Deterministic construction of stable ResourceIds from the immutable identity
 * triple (providerId, serviceId, offeringId) + offering type.
 *
 * Stability matters because:
 *   - ResourceIds are persisted in Room and must survive restarts unchanged.
 *   - `RuntimeAdapterResolver` resolves adapters by ResourceId + configuration
 *     version; unstable ids would silently orphan persisted records.
 *   - Re-materializing the same offering must return the SAME id (idempotency)
 *     instead of creating duplicate resources.
 */
object ResourceIdScheme {

    private const val PREFIX = "res"

    /**
     * Stable id for a materialized offering-backed resource.
     */
    fun forOffering(
        providerId: String,
        serviceId: String,
        offeringType: OfferingType,
        offeringId: String
    ): ResourceId {
        return ResourceId(
            "$PREFIX:${providerId.lowercase()}:${serviceId.lowercase()}:" +
                "${offeringType.code.lowercase()}:${normalize(offeringId)}"
        )
    }

    /**
     * Stable id for an explicitly-registered in-process component
     * (local embedding engine, multi-source search composite, tools).
     */
    fun forInProcessComponent(componentKey: String): ResourceId {
        return ResourceId("$PREFIX:inprocess:${normalize(componentKey)}")
    }

    private fun normalize(raw: String): String {
        return raw.trim().lowercase().replace(Regex("\\s+"), "-")
    }
}
