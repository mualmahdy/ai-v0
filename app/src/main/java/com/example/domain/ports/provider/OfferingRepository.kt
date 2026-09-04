package com.example.domain.ports.provider

import com.example.domain.core.provider.offering.ServiceOffering

/**
 * ============================================================================
 * OfferingRepository — Phase 4 canonical port (Correction #9)
 * ============================================================================
 *
 * Durable offering catalog. Discovery produces ServiceOfferings (NOT
 * ResourceRecords); they are persisted here until the user explicitly
 * materializes a resource from one of them.
 */
interface OfferingRepository {
    /** Insert-or-replace keyed by (id, serviceId). */
    suspend fun registerOffering(offering: ServiceOffering)

    suspend fun getOffering(offeringId: String): ServiceOffering?

    suspend fun findOfferingsForService(serviceId: String): List<ServiceOffering>

    suspend fun getAllOfferings(): List<ServiceOffering>

    /** Cascade delete for a service. */
    suspend fun clearForService(serviceId: String)
}
