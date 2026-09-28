package com.example.application.provider

import com.example.domain.core.resource.OperationalResourceSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * OperationalResourceSnapshotStore — CLOSURE P1-1 (audit 2026 §5/D1)
 * ============================================================================
 *
 * The in-memory truth surface for per-resource operational capability
 * snapshots. The control plane's [ProviderControlPlaneService.validateResource]
 * is the ONLY writer (validation is the only place runtime evidence exists);
 * future consumers (decision layer, radar, offering correction) read from
 * here so the whole runtime shares ONE notion of "what this resource
 * PROVED it can do".
 *
 * Merge semantics on re-validation: [OperationalResourceSnapshot.mergedWith] —
 * a newer probe's verdict wins whenever it is not UNPROBED; an UNPROBED
 * field honestly inherits the previous run's verdict (a probe that could not
 * run must not erase earlier runtime evidence).
 *
 * Persistence is deliberately NOT this stage's concern: snapshots describe
 * the CURRENT process's runtime evidence (a cold-start process has probed
 * nothing — and says so honestly, via UNPROBED everywhere, rather than
 * replaying stale claims from a previous process).
 */
class OperationalResourceSnapshotStore {

    private val snapshots = ConcurrentHashMap<String, OperationalResourceSnapshot>()

    /** Records (merge-with-previous) the snapshot for its resource id. */
    fun record(snapshot: OperationalResourceSnapshot) {
        val key = snapshot.resourceId
        if (key.isBlank()) return // never index an unkeyed snapshot
        snapshots.compute(key) { _, previous -> snapshot.mergedWith(previous) }
    }

    /** The latest merged snapshot for a resource id, if any. */
    fun snapshotFor(resourceId: String): OperationalResourceSnapshot? =
        snapshots[resourceId]

    /** All recorded snapshots (e.g. for diagnostics surfaces). */
    fun all(): List<OperationalResourceSnapshot> = snapshots.values.toList()

    /** Test seam: drops every recorded snapshot. */
    fun clear() = snapshots.clear()
}
