package com.example.domain.ports.resource

import com.example.domain.core.resource.ResourceId

/**
 * P0.2 — Cooldown consultation seam (Section E usable conjunction).
 *
 * The registry must apply the "not-in-cooldown" conjunct without owning health
 * metrics (Section D ownership boundary). The health track is owned exclusively
 * by ResourceHealthService, which provides this predicate at composition time.
 */
fun interface ResourceCooldownChecker {
    /** Returns true when the resource is currently in health cooldown. */
    suspend fun isInCooldown(resourceId: ResourceId): Boolean
}

/** No-op checker used when no health service is wired (e.g. unit tests). */
object NoCooldownChecker : ResourceCooldownChecker {
    override suspend fun isInCooldown(resourceId: ResourceId): Boolean = false
}
